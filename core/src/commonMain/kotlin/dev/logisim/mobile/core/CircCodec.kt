package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

sealed interface CircImportResult {
    data class Success(val document: CircuitDocument) : CircImportResult
    data class Failure(val notices: List<LocalizedText>) : CircImportResult {
        val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
}

sealed interface CircProjectImportResult {
    data class Success(val project: CircuitProject, val migrations: List<CircMigration> = emptyList()) : CircProjectImportResult
    data class Failure(val notices: List<LocalizedText>) : CircProjectImportResult {
        val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
}

/** Reads complete projects and retains presentation metadata that the editor cannot yet change. */
object CircCodec {
    /** Mobile-owned identity; the desktop ignores this extra component attribute. */
    const val COMPONENT_ID_ATTRIBUTE = "logisim-mobile-id"
    private const val ORIGINAL_SEQUENTIAL_ATTRIBUTE = "logisim-mobile-original-sequential"
    private const val ORIGINAL_DEFAULT_LABEL_FONT = "SansSerif plain 12"

    @kotlin.jvm.JvmOverloads
    fun read(xml: String, registry: ComponentRegistry = ComponentCatalog.registry): CircImportResult = when (val result = readProject(xml, registry)) {
        is CircProjectImportResult.Success -> if (result.project.circuits.size != 1) {
            CircImportResult.Failure(listOf(message(Messages.CodecMultipleCircuits)))
        } else {
            val project = result.project
            val canonicalEmptyContext = project.sourceVersion == "5.1.0-dev" &&
                project.metadata == mapOf("version" to "1.0") && project.libraries.isEmpty() && project.extraXml.isEmpty() && project.mainXml == null
            val context = if (canonicalEmptyContext) null else CircuitFileMetadata(
                sourceVersion = project.sourceVersion, metadata = project.metadata,
                libraries = project.libraries, extraXml = project.extraXml,
                mainCircuitName = project.mainCircuitName,
                mainXml = project.mainXml,
            )
            CircImportResult.Success(project.mainCircuit.copy(fileMetadata = context))
        }
        is CircProjectImportResult.Failure -> CircImportResult.Failure(result.notices)
    }

    @kotlin.jvm.JvmOverloads
    fun readProject(xml: String, registry: ComponentRegistry = ComponentCatalog.registry): CircProjectImportResult =
        readProject(xml, registry, evolutionNames = true)

    /** [evolutionNames] false keeps the names of a flattened multi-file project, whose circuits are matched across files by name. */
    internal fun readProject(xml: String, registry: ComponentRegistry, evolutionNames: Boolean): CircProjectImportResult = try {
        val migrations = mutableListOf<CircMigration>()
        CircProjectImportResult.Success(importProject(CircXmlParser(xml).parse(), migrations, registry, evolutionNames), migrations)
    } catch (error: IllegalArgumentException) {
        CircProjectImportResult.Failure(listOf(error.localizedDescription(message(Messages.ErrorCircInvalid))))
    }

    @kotlin.jvm.JvmOverloads
    fun write(document: CircuitDocument, registry: ComponentRegistry = ComponentCatalog.registry): String {
        val context = document.fileMetadata
        return writeProject(CircuitProject(
            circuits = listOf(document), mainCircuitName = document.name,
            sourceVersion = context?.sourceVersion ?: "5.1.0-dev",
            metadata = context?.metadata.orEmpty(), libraries = context?.libraries.orEmpty(),
            extraXml = context?.extraXml.orEmpty(),
            mainXml = context?.mainXml,
        ), registry)
    }

    @kotlin.jvm.JvmOverloads
    fun writeCircuit(project: CircuitProject, name: String, registry: ComponentRegistry = ComponentCatalog.registry): String {
        val byName = project.circuits.associateBy { it.name }
        requireLocalized(name in byName) { message(Messages.ErrorCircuitMissing, "name" to name) }
        val included = linkedSetOf(name)
        val pending = ArrayDeque(listOf(name))
        while (pending.isNotEmpty()) {
            val circuit = byName.getValue(pending.removeFirst())
            for (component in circuit.components) {
                if (component.kind != ComponentKind.SUBCIRCUIT) continue
                val dependency = checkNotNull(component.circuitName)
                if (included.add(dependency)) pending.addLast(dependency)
            }
        }
        val extraXml = project.extraXml.map { xml ->
            val element = CircXmlParser(xml).parse()
            if (element.name != "toolbar" && element.name != "mappings") return@map xml
            val tools = element.children.filterNot {
                it.name == "tool" && it.attributes["lib"].isNullOrEmpty() &&
                    it.attributes["name"] in byName && it.attributes["name"] !in included
            }
            if (tools == element.children) xml
            else CircXmlRewrite.element(element.name, element.attributes, element.text, tools.map { it.rawXml })
        }
        return writeProject(project.copy(
            circuits = included.map { byName.getValue(it) },
            mainCircuitName = name,
            extraXml = extraXml,
        ), registry)
    }

    @kotlin.jvm.JvmOverloads
    fun writeProject(
        project: CircuitProject,
        registry: ComponentRegistry = ComponentCatalog.registry,
        removeUnusedLibraries: Boolean = false,
    ): String {
        requireLocalized(project.circuits.isNotEmpty()) { message(Messages.CodecProjectEmpty) }
        requireLocalized(project.circuits.any { it.name == project.mainCircuitName }) { message(Messages.CodecMainMissing) }
        val vhdlDefinitions = project.sharedVhdlDefinitions().associateBy { it.name }
        fun sharedVhdl(component: CircuitComponent) =
            vhdlDefinitions[component.xmlAttributes[SharedVhdlDefinition.referenceAttribute]]?.takeIf { it.matches(component) }
        val libraries = project.libraries.toMutableMap()
        for (component in project.circuits.flatMap { it.components }) {
            if (component.kind == ComponentKind.SUBCIRCUIT || sharedVhdl(component) != null) continue
            val description = componentDescription(component, registry).first
            if (description !in libraries.values) {
                var id = 0
                while (id.toString() in libraries) id++
                libraries[id.toString()] = description
            }
        }
        if (removeUnusedLibraries) {
            // XmlWriter.fromLibrary: keep #Base and libraries a component, toolbar tool or mouse mapping uses.
            val usedDescriptions = project.circuits.flatMap { it.components }
                .filter { it.kind != ComponentKind.SUBCIRCUIT && sharedVhdl(it) == null }
                .mapTo(mutableSetOf()) { componentDescription(it, registry).first }
            val toolLibraries = project.extraXml.flatMap { xml ->
                val element = CircXmlParser(xml).parse()
                if (element.name == "toolbar" || element.name == "mappings") {
                    element.children.mapNotNull { tool -> tool.attributes["lib"].takeIf { tool.name == "tool" } }
                } else emptyList()
            }.toSet()
            libraries.entries.removeAll { (id, description) ->
                description != "#Base" && description !in usedDescriptions && id !in toolLibraries
            }
        }
        val preservedLibraries = project.extraXml.mapNotNull { xml ->
            val element = CircXmlParser(xml).parse()
            if (element.name == "lib") element.attributes["name"] to xml else null
        }.toMap()
        val floatInArithmetic = omitRecreatedFloatLibrary(project, libraries, preservedLibraries)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project")
            val attributes = linkedMapOf("source" to project.sourceVersion, "version" to "1.0") + (project.metadata - projectTextKey)
            attributes.forEach { (key, value) -> appendAttribute(key, value) }
            val projectText = project.metadata[projectTextKey]
            val rootIndent = if (projectText == null) "  " else ""
            val rootLineBreak = if (projectText == null) "\n" else ""
            append('>').append(rootLineBreak)
            projectText?.let { append(escape(it)) }
            for ((id, description) in libraries) {
                val preserved = preservedLibraries[id]
                if (preserved != null) append(rootIndent).append(preserved).append(rootLineBreak)
                else {
                    append(rootIndent).append("<lib")
                    appendAttribute("desc", description)
                    appendAttribute("name", id)
                    append("/>").append(rootLineBreak)
                }
            }
            // Evolution writes its toolbox in order: circuits, then VHDL entities added later. Keep shared
            // VHDL after the circuits so Evolution reopens them at the same toolbox positions.
            val trailingXml = project.extraXml.filter { CircXmlParser(it).parse().name == "vhdl" }
            for (xml in project.extraXml) {
                if (CircXmlParser(xml).parse().name !in setOf("lib", "main", "circuit", "vhdl")) append(rootIndent).append(xml).append(rootLineBreak)
            }
            append(rootIndent).append(writeMain(project)).append(rootLineBreak)
            val fileProfile by lazy { CircFormatProfile.parse(project.sourceVersion) }
            for (circuit in project.circuits) {
                val profile by lazy {
                    circuit.xmlAttributes["logisim-mobile-source"]?.let(CircFormatProfile::parse) ?: fileProfile
                }
                requireLocalized(circuit.name.isNotBlank()) { message(Messages.ErrorCircuitNameRequired) }
                append(rootIndent).append("<circuit")
                (circuit.xmlAttributes + mapOf("name" to circuit.name, "id" to circuit.id))
                    .forEach { (name, value) -> appendAttribute(name, value) }
                append(">\n")
                circuit.attributes.forEach { (key, value) -> appendCircuitAttribute(key, value, "    ") }
                for (xml in circuit.extraXml) append("    ").append(xml).append('\n')
                for (component in circuit.components) {
                    requireComponentIdentity(component.id)
                    append("    <comp")
                    val attrs = component.xmlAttributes.toMutableMap()
                    val vhdl = sharedVhdl(component)
                    attrs.remove(SharedVhdlDefinition.referenceAttribute)
                    if (vhdl != null) attrs.remove(SharedVhdlDefinition.appearanceAttribute)
                    attrs["loc"] = pointText(component.position)
                    attrs[COMPONENT_ID_ATTRIBUTE] = component.id
                    var originalSequentialOverride = false
                    if (component.kind == ComponentKind.SUBCIRCUIT) {
                        attrs.remove("lib")
                        attrs["name"] = component.circuitName ?: throw LocalizedArgumentException(message(Messages.CodecSubcircuitReferenceRequired))
                        requireLocalized(project.circuits.any { it.name == component.circuitName }) { message(Messages.CodecSubcircuitMissing, "component_circuitName" to component.circuitName) }
                    } else if (vhdl != null) {
                        attrs.remove("lib")
                        attrs["name"] = vhdl.name
                    } else {
                        val (componentLibrary, name) = componentDescription(component, registry)
                        val description = if (floatInArithmetic && componentLibrary == "#FPArithmetic") "#Arithmetic" else componentLibrary
                        attrs["name"] = name
                        if (libraries[attrs["lib"]] != description) attrs["lib"] = libraries.entries.first { it.value == description }.key
                        if (description == "#Memory" && CircFormatProfile.isSequential(name)) {
                            originalSequentialOverride = component.isOriginalSequential != profile.originalLogisim
                            if (originalSequentialOverride) attrs[ORIGINAL_SEQUENTIAL_ATTRIBUTE] = component.isOriginalSequential.toString()
                            else attrs.remove(ORIGINAL_SEQUENTIAL_ATTRIBUTE)
                        }
                    }
                    attrs.forEach { (key, value) -> appendAttribute(key, value) }
                    append(">\n")
                    val componentAttributes = (if (component.xmlAttributes.isEmpty()) defaultExportAttributes(component) else emptyMap()).toMutableMap()
                    componentAttributes.putAll(component.attributes.filter { (name, value) -> component.implicitAttributes[name] != value })
                    val definition = registry.definition(component.kind)
                    val destinationDefaults = importedDefaults(definition, attrs.getValue("name"), componentAttributes, profile)
                    for ((name, value) in component.implicitAttributes) {
                        if (component.attributes[name] == value && destinationDefaults[name] != value) {
                            componentAttributes[name] = value
                        }
                    }
                    if (definition != null && profile != fileProfile) {
                        // Evolution reads omitted component attributes using the project source.
                        val fileDefaults = importedDefaults(definition, attrs.getValue("name"), componentAttributes, fileProfile)
                        for ((name, schemaDefault) in definition.schema.defaults) {
                            if (name in componentAttributes || name.startsWith("logisim-mobile-")) continue
                            val circuitDefault = destinationDefaults[name] ?: schemaDefault
                            val fileDefault = fileDefaults[name] ?: schemaDefault
                            if (circuitDefault != fileDefault) {
                                componentAttributes[name] = component.attributes[name] ?: schemaDefault
                            }
                        }
                    }
                    if (component.kind == ComponentKind.SPLITTER) {
                        // Mobile exports made before the Evolution default fix used balanced omission.
                        val mobileMapping = if (profile.originalLogisim) null
                            else component.copy(attributes = componentAttributes).splitterBitEnds()
                        component.splitterBitEnds().forEachIndexed { bit, branch ->
                            val name = "bit$bit"
                            val value = branch?.toString() ?: "none"
                            val differsFromDesktop = destinationDefaults[name]?.let { it != value } == true
                            val differsFromMobile = mobileMapping != null && mobileMapping.getOrNull(bit) != branch
                            if (name !in componentAttributes && (differsFromDesktop || differsFromMobile)) {
                                componentAttributes[name] = value
                            }
                        }
                    }
                    val originalMemoryAttribute = if (definition?.library == "#Memory") when (definition.fileName) {
                        "RAM" -> ORIGINAL_RAM_ATTRIBUTE
                        "ROM" -> ORIGINAL_ROM_ATTRIBUTE
                        else -> null
                    } else null
                    if (originalMemoryAttribute != null && destinationDefaults[originalMemoryAttribute] == "true" &&
                        component.attributes[originalMemoryAttribute] != "true") {
                        componentAttributes[originalMemoryAttribute] = "false"
                    }
                    if (destinationDefaults[ORIGINAL_CONTROLLED_GATE_ATTRIBUTE] == "true" &&
                        component.attributes[ORIGINAL_CONTROLLED_GATE_ATTRIBUTE] != "true") {
                        componentAttributes[ORIGINAL_CONTROLLED_GATE_ATTRIBUTE] = "false"
                    }
                    if (destinationDefaults[ORIGINAL_DIVIDER_ATTRIBUTE] == "true" &&
                        component.attributes[ORIGINAL_DIVIDER_ATTRIBUTE] != "true") {
                        componentAttributes[ORIGINAL_DIVIDER_ATTRIBUTE] = "false"
                    }
                    if (destinationDefaults[ORIGINAL_HEX_DIGIT_ATTRIBUTE] == "true" && !component.isOriginalHexDigit) {
                        componentAttributes[ORIGINAL_HEX_DIGIT_ATTRIBUTE] = "false"
                    }
                    if (destinationDefaults[ORIGINAL_MULTIPLIER_ATTRIBUTE] == "true" &&
                        component.attributes[ORIGINAL_MULTIPLIER_ATTRIBUTE] != "true") {
                        componentAttributes[ORIGINAL_MULTIPLIER_ATTRIBUTE] = "false"
                    }
                    if (destinationDefaults[ORIGINAL_TRANSISTOR_ATTRIBUTE] == "true" &&
                        component.attributes[ORIGINAL_TRANSISTOR_ATTRIBUTE] != "true") {
                        componentAttributes[ORIGINAL_TRANSISTOR_ATTRIBUTE] = "false"
                    }
                    if (originalSequentialOverride) component.attributes["appearance"]?.let { componentAttributes["appearance"] = it }
                    if (component.label.isNotEmpty() || "label" in componentAttributes) componentAttributes["label"] = component.label
                    if (vhdl != null) {
                        componentAttributes.remove("content")
                        val genericNames = componentAttributes.keys.filter { it.startsWith("generic.") }
                        if (genericNames.isNotEmpty()) {
                            val names = HdlIpLibrary.parse(component).parameters.keys
                            for (key in genericNames) {
                                val generic = key.removePrefix("generic.")
                                val name = names.firstOrNull { it.equals(generic, ignoreCase = true) } ?: generic
                                componentAttributes["vhdl_$name"] = componentAttributes.getValue(key)
                                componentAttributes.remove(key)
                            }
                        }
                    }
                    if (component.kind == ExtraIoKinds.Ne555) ExtraIoAttributes.normalizeNe555Attributes(componentAttributes)
                    if (component.kind == IoKinds.DipSwitch) {
                        val key = IoAttributes.Positions
                        componentAttributes[key.name]?.let(key.parse)?.let { positions ->
                            componentAttributes[key.name] = "0x${positions.toUInt().toString(16)}"
                        }
                    }
                    componentAttributes.forEach { (key, value) -> appendCircuitAttribute(key, value, "      ") }
                    for (xml in component.extraXml) append("      ").append(xml).append('\n')
                    append("    </comp>\n")
                }
                for (wire in circuit.wires) {
                    append("    <wire")
                    appendAttribute("from", pointText(wire.from))
                    appendAttribute("to", pointText(wire.to))
                    append("/>\n")
                }
                append("  </circuit>").append(rootLineBreak)
            }
            for (xml in trailingXml) append(rootIndent).append(xml).append(rootLineBreak)
            append("</project>\n")
        }
    }

    /**
     * #323: Evolution's XmlReader.repairFloatLibrary adds a #FPArithmetic library after #Arithmetic to every file older
     * than 4.1.0dev and moves the FP components into it, so a declared #FPArithmetic would load twice. Such files leave
     * it out and keep their FP components in #Arithmetic, as Evolution 4.0 wrote them. A #FPArithmetic referenced by a
     * toolbar, a mouse mapping or library tool defaults stays declared. Returns whether FP components go to #Arithmetic.
     */
    private fun omitRecreatedFloatLibrary(project: CircuitProject, libraries: MutableMap<String, String>,
                                          preservedLibraries: Map<String?, String>): Boolean {
        val profile = runCatching { CircFormatProfile.parse(project.sourceVersion) }.getOrNull() ?: return false
        if (!profile.before(4, 1, 0) || "#Arithmetic" !in libraries.values) return false
        val float = libraries.filterValues { it == "#FPArithmetic" }.keys
        if (float.isEmpty()) return false
        val referenced = project.extraXml.any { xml ->
            val element = CircXmlParser(xml).parse()
            element.name in setOf("toolbar", "mappings") && element.children.any { it.attributes["lib"] in float }
        } || float.any { id -> preservedLibraries[id]?.let { CircXmlParser(it).parse().children.isNotEmpty() } == true }
        if (referenced) return false
        float.forEach(libraries::remove)
        return true
    }

    /** XmlReader.repairFloatLibrary for files older than 4.1.0dev that name #Arithmetic but not #FPArithmetic. */
    private fun recreateFloatLibrary(libraries: LinkedHashMap<String, String>, profile: CircFormatProfile) {
        if (!profile.before(4, 1, 0) || "#FPArithmetic" in libraries.values) return
        val arithmetic = libraries.entries.firstOrNull { it.value == "#Arithmetic" }?.key ?: return
        var id = "float"
        var number = 1
        while (id in libraries) id = "float${number++}"
        val entries = libraries.entries.map { it.key to it.value }
        libraries.clear()
        for ((key, value) in entries) {
            libraries[key] = value
            if (key == arithmetic) libraries[id] = "#FPArithmetic"
        }
    }

    private fun importProject(input: CircXmlElement, migrations: MutableList<CircMigration>, registry: ComponentRegistry,
                              evolutionNames: Boolean): CircuitProject {
        requireLocalized(input.name == "project") { message(Messages.CodecProjectRootRequired) }
        input.requireTextBeforeChildren()
        val projectSource = input.attributes["source"]
        val profile = CircFormatProfile.parse(projectSource)
        val converted = if (profile.needsLegacyConversion) LegacyCircMigration.repair(input, profile) else input
        // Original Logisim (2.7.1 and earlier) accepts any name, so its files keep them; Evolution renames them on open.
        val root = if (evolutionNames && !profile.originalLogisim) EvolutionNameRepair.repair(converted, migrations) else converted
        val originalProfile by lazy { CircFormatProfile.parse("2.7.1") }
        requireLocalized(root.attributes["version"] == null || root.attributes["version"] == "1.0") { message(Messages.CodecFormatVersionUnsupported, "root_attributes__version" to root.attributes["version"]) }
        val libraries = linkedMapOf<String, String>()
        for (library in root.children.filter { it.name == "lib" }) {
            val id = library.attributes["name"] ?: throw LocalizedArgumentException(message(Messages.CodecLibraryIdentityRequired))
            val description = library.attributes["desc"] ?: throw LocalizedArgumentException(message(Messages.CodecLibraryDescriptionRequired))
            requireLocalized(libraries.put(id, description) == null) { message(Messages.CodecLibraryDuplicate, "id" to id) }
        }
        recreateFloatLibrary(libraries, profile)
        val circuitNodes = root.children.filter { it.name == "circuit" }
        requireLocalized(circuitNodes.isNotEmpty()) { message(Messages.CodecProjectNoCircuits) }
        val names = circuitNodes.map { it.attributes["name"] ?: "" }
        requireLocalized(names.all { it.isNotBlank() } && names.distinct().size == names.size) { message(Messages.CodecCircuitNamesInvalid) }
        val vhdl = root.children.filter { it.name == "vhdl" }.map(SharedVhdlDefinition::read)
        val vhdlByName = vhdl.associateBy { it.name }
        requireLocalized(vhdlByName.size == vhdl.size && names.none { it in vhdlByName }) { message("shared_vhdl_name_conflict") }
        val mains = root.children.filter { it.name == "main" }
        requireLocalized(mains.size <= 1) { message(Messages.CodecMultipleMainCircuits) }
        val main = mains.singleOrNull()?.attributes?.get("name") ?: names.first()
        requireLocalized(main in names) { message(Messages.CodecMainCircuitMissing, "main" to main) }
        if (profile.needsLegacyConversion) migrations += LegacyCircMigration.notice(profile, main)
        validateOptions(root, main, migrations)
        val mainXml = mains.singleOrNull()?.takeIf {
            it.attributes.keys.any { key -> key != "name" } || it.children.isNotEmpty() || it.text.isNotBlank()
        }?.rawXml
        val documents = circuitNodes.map { node ->
            requireLocalized(node.text.isBlank()) { message(Messages.CodecCircuitTextUnexpected, "node_attributes__name" to node.attributes["name"]) }
            val circuitSource = node.attributes["logisim-mobile-source"]
            val circuitProfile = circuitSource?.let(CircFormatProfile::parse) ?: profile
            val sourceMissing = (circuitSource ?: projectSource).isNullOrBlank()
            val (attributes, preservedAttributes) = readAttributes(node)
            val componentNodes = node.children.filter { it.name == "comp" }
            val explicitIds = componentNodes.mapNotNull { it.attributes[COMPONENT_ID_ATTRIBUTE] }
            explicitIds.forEach(::requireComponentIdentity)
            requireLocalized(explicitIds.distinct().size == explicitIds.size) { message(Messages.ErrorComponentIdsUnique) }
            val usedIds = explicitIds.toMutableSet()
            var nextLegacyNumber = 1
            val components = componentNodes.mapIndexed { index, component ->
                val id = component.attributes[COMPONENT_ID_ATTRIBUTE] ?: run {
                    nextLegacyNumber = maxOf(nextLegacyNumber, index + 1)
                    while ("import-$nextLegacyNumber" in usedIds) nextLegacyNumber++
                    "import-${nextLegacyNumber++}".also(usedIds::add)
                }
                val circuitName = node.attributes.getValue("name")
                val shared = vhdlByName[component.attributes["name"]]?.takeIf { component.attributes["lib"].isNullOrEmpty() }
                val imported = readComponent(component, libraries, names, id, circuitProfile, circuitName, migrations, registry, shared)
                if (sourceMissing) {
                    val original = try {
                        readComponent(component, libraries, names, id, originalProfile, circuitName, mutableListOf(), registry, shared)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    // Only the omitted label font differs between the editions; the file keeps the current one.
                    requireLocalized(imported.withoutImplicitLabelFont() == original?.withoutImplicitLabelFont()) {
                        message(Messages.CodecSourceVersionAmbiguous, "circuit" to circuitName, "component" to component.attributes.getValue("name"))
                    }
                }
                imported
            }
            val wires = node.children.filter { it.name == "wire" }.map { wire ->
                requireLocalized(wire.children.isEmpty() && wire.text.isBlank()) { message(Messages.CodecWireContentUnsupported) }
                requireLocalized(wire.attributes.keys.all { it in setOf("from", "to") }) { message(Messages.CodecWireAttributesUnsupported) }
                WireSegment(parsePoint(wire.attributes["from"]), parsePoint(wire.attributes["to"]))
            }
            CircuitDocument(
                name = node.attributes.getValue("name"), components = components, wires = wires,
                attributes = if (circuitProfile.holyCross && node.children.isNotEmpty() && "appearance" !in attributes &&
                    "circuitnamedbox" !in attributes && node.children.none { it.name == "appear" }
                ) attributes + ("appearance" to "evolution") else attributes,
                extraXml = preservedAttributes + node.children.filter { it.name !in setOf("comp", "wire", "a") }.map { it.rawXml },
                id = node.attributes["id"] ?: node.attributes.getValue("name"),
                xmlAttributes = (node.attributes - setOf("name", "id")).let {
                    if (circuitSource != null && circuitSource.isBlank()) it + ("logisim-mobile-source" to circuitProfile.source)
                    else if (circuitSource != null && circuitProfile.needsLegacyConversion)
                        it + ("logisim-mobile-source" to LegacyCircMigration.targetSource)
                    else it
                },
            )
        }
        val byName = documents.associateBy { it.name }
        val complete = documents.map { circuit -> circuit.copy(components = circuit.components.map { component ->
            if (component.kind != ComponentKind.SUBCIRCUIT) component
            else bindSubcircuit(component, byName.getValue(component.circuitName!!))
        }) }
        rejectRecursiveCircuits(complete)
        return CircuitProject(
            circuits = complete, mainCircuitName = main,
            sourceVersion = if (profile.needsLegacyConversion) LegacyCircMigration.targetSource else profile.source,
            metadata = (root.attributes - "source") + (if (root.text.isBlank()) emptyMap() else mapOf(projectTextKey to root.text)),
            extraXml = root.children.filter { it.name !in setOf("main", "circuit") }.map { it.rawXml },
            libraries = libraries,
            mainXml = mainXml,
        )
    }

    private fun writeMain(project: CircuitProject): String {
        val original = project.mainXml ?: return buildString {
            append("<main")
            appendAttribute("name", project.mainCircuitName)
            append("/>")
        }
        val element = CircXmlParser(original).parse()
        requireLocalized(element.name == "main") { message(Messages.CodecMainMetadataInvalid) }
        var quote: Char? = null
        val openingEnd = original.indices.first { index ->
            val character = original[index]
            if (quote != null) {
                if (character == quote) quote = null
                false
            } else when (character) {
                '\'', '"' -> { quote = character; false }
                '>' -> true
                else -> false
            }
        }
        val selfClosing = original.substring(0, openingEnd).trimEnd().endsWith('/')
        return buildString {
            append("<main")
            (element.attributes + ("name" to project.mainCircuitName)).forEach { (name, value) -> appendAttribute(name, value) }
            append(if (selfClosing) "/>" else ">")
            append(original.substring(openingEnd + 1))
        }
    }

    private fun validateOptions(root: CircXmlElement, main: String, migrations: MutableList<CircMigration>) {
        var randomDelay = 0
        for (option in root.children.filter { it.name == "options" }.flatMap { it.children }) {
            val name = option.attributes["name"]
            val value = option.attributes["val"] ?: option.text.trim()
            when (name) {
                "simlimit", "propagationLimit" -> requireLocalized(value.toIntOrNull()?.let { it > 0 } == true) { message(Messages.SimulationOptionsErrorImportLimit, "value" to value) }
                "tickFrequencyHz" -> requireLocalized(value.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true) { message(Messages.SimulationOptionsErrorImportFrequency, "value" to value) }
                "autoPropagating", "memoryStartupUnknown" -> requireLocalized(value == "true" || value == "false") { message(Messages.SimulationOptionsErrorImportBoolean, "name" to name, "value" to value) }
            }
            if (name == "simrand") {
                randomDelay = requireNotNullLocalized(value.toIntOrNull()?.takeIf { it >= 0 }) {
                    message(Messages.SimulationOptionsErrorValue, "name" to name, "value" to value)
                }
            }
            if (name == "gateUndefined") requireLocalized(value in setOf("ignore", "error")) { message(Messages.CodecUndefinedInputsUnsupported, "value" to value) }
        }
        if (randomDelay > 1) migrations += CircMigration("simulation-noise-preserved", main, null,
            message(Messages.CodecRandomSimulationEnabled, "value" to randomDelay))
    }

    internal fun projectToolPrototype(
        tool: CircXmlElement, name: String, libraries: Map<String, String>,
        project: CircuitProject, registry: ComponentRegistry,
    ): CircuitComponent? {
        val library = tool.attributes["lib"]?.takeUnless(String::isEmpty)
        if (library != null && library !in libraries) return null
        val shared = if (library == null) project.sharedVhdlDefinitions().firstOrNull { it.name == name } else null
        if (library == null && shared == null && project.circuit(name) == null) return null
        val attributes = mapOf("name" to name, "loc" to "(0,0)") +
            (if (library == null) emptyMap() else mapOf("lib" to library))
        return try {
            val component = readComponent(
                tool.copy(name = "comp", attributes = attributes),
                libraries, project.circuits.map { it.name }, "project-tool", CircFormatProfile.parse(project.sourceVersion),
                project.mainCircuitName, mutableListOf(), registry, shared,
            )
            val child = component.circuitName?.let(project::circuit)
            if (child != null) bindSubcircuit(component, child) else component
        } catch (_: IllegalArgumentException) {
            // Unavailable or invalid metadata tools do not prevent opening the circuit.
            null
        }
    }

    private fun readComponent(
        element: CircXmlElement, libraries: Map<String, String>, circuitNames: List<String>, componentId: String,
        profile: CircFormatProfile, circuit: String, migrations: MutableList<CircMigration>, registry: ComponentRegistry,
        shared: SharedVhdlDefinition? = null,
    ): CircuitComponent {
        requireLocalized(element.text.isBlank()) { message(Messages.CodecComponentTextUnexpected) }
        val name = element.attributes["name"] ?: throw LocalizedArgumentException(message(Messages.CodecComponentNameRequired))
        val libraryId = element.attributes["lib"]?.takeUnless(String::isEmpty)
        val originalLibrary = libraries[libraryId]
        val library = profile.library(originalLibrary, name)
        val (ownAttributes, preserved) = readAttributes(element)
        if (shared != null) {
            validateFacing(name, ownAttributes)
            return shared.bind(CircuitComponent(
                componentId, HdlIpLibrary.VHDL, parsePoint(element.attributes["loc"]), ownAttributes["label"].orEmpty(),
                ownAttributes, xmlAttributes = element.attributes,
                extraXml = preserved + element.children.filter { it.name != "a" }.map { it.rawXml },
            ))
        }
        val mobileExport = COMPONENT_ID_ATTRIBUTE in element.attributes
        val profileDefaults = profile.implicitAttributes(name, ownAttributes, mobileExport)
        val subcircuit = libraryId == null && name in circuitNames
        val definition = if (subcircuit) null else registry.resolve(library.orEmpty(), name, profileDefaults + ownAttributes)
            ?: throw LocalizedArgumentException(message(Messages.CodecComponentUnsupported, "name" to name,
                "value2" to (library ?: libraryId ?: message(Messages.CodecExternalSubcircuit))))
        val implicit = importedDefaults(definition, name, ownAttributes, profile, mobileExport)
        val attributes = (implicit + ownAttributes).toMutableMap()
        if (implicit.isNotEmpty()) migrations += CircMigration("version-defaults", circuit, componentId,
            message(Messages.CodecMigrationImplicitAttributes, "name" to name, "value2" to implicit.keys.sorted().joinToString(), "profile_source" to profile.source))
        if (library != originalLibrary) migrations += CircMigration("floating-point-library", circuit, componentId,
            message(Messages.CodecMigrationFpLibrary, "name" to name))
        val kind = definition?.kind ?: ComponentKind.SUBCIRCUIT
        if (kind == ExtraIoKinds.Ne555) ExtraIoAttributes.normalizeNe555Attributes(attributes)
        if (kind == GatesLibrary.Pla) GatesLibrary.normalizePlaFileAttributes(attributes)
        if (kind == IoKinds.DipSwitch) {
            val key = IoAttributes.Positions
            attributes[key.name]?.let { raw ->
                // Keep the historic hex meaning of bare digits in Mobile-marked files.
                val positions = requireNotNullLocalized(
                    if (mobileExport) key.parse(raw) else parseEvolutionHexInteger(raw)?.toULong(),
                ) { message(Messages.AttributeRawInvalid, "name" to key.name, "raw" to raw) }
                attributes[key.name] = key.format(positions)
            }
        }
        definition?.schema?.parse(attributes)
        validateFacing(name, attributes)
        // Pins default to east in desktop files, including output probes.
        if (kind == ComponentKind.INPUT || kind == ComponentKind.OUTPUT) attributes.getOrPut("facing") { "east" }
        return CircuitComponent(
            id = componentId, kind = kind, position = parsePoint(element.attributes["loc"]),
            label = attributes["label"].orEmpty(), attributes = attributes,
            circuitName = name.takeIf { subcircuit }, xmlAttributes = element.attributes,
            extraXml = preserved + element.children.filter { it.name != "a" }.map { it.rawXml },
            implicitAttributes = implicit,
            isOriginalSequential = definition?.library == "#Memory" && CircFormatProfile.isSequential(name) &&
                (element.attributes[ORIGINAL_SEQUENTIAL_ATTRIBUTE]?.toBooleanStrictOrNull() ?: profile.originalLogisim),
        )
    }

    private fun importedDefaults(
        definition: ComponentDefinition?, name: String, attributes: Map<String, String>, profile: CircFormatProfile,
        mobileExport: Boolean = false,
    ): Map<String, String> {
        val definitionDefaults = definition?.let {
            (if (it.kind in ComponentKind.entries) emptyMap() else it.schema.defaults) +
                it.defaultAttributes + it.defaultsForVersion(profile.source)
        }.orEmpty()
        // StdAttr.DEFAULT_LABEL_FONT was SansSerif plain 12 in 2.7.1; Evolution's bold 16 is the schema default.
        val labelFont = if (profile.originalLogisim && (definition == null || "labelfont" in definition.schema.defaults))
            mapOf("labelfont" to ORIGINAL_DEFAULT_LABEL_FONT) else emptyMap()
        return (definitionDefaults + profile.implicitAttributes(name, attributes, mobileExport) + labelFont).filterKeys { it !in attributes }
    }

    private fun CircuitComponent.withoutImplicitLabelFont(): CircuitComponent =
        if ("labelfont" !in implicitAttributes) this
        else copy(attributes = attributes - "labelfont", implicitAttributes = implicitAttributes - "labelfont")

    private fun validateFacing(name: String, attributes: Map<String, String>) {
        attributes["facing"]?.let { requireLocalized(it in setOf("east", "west", "north", "south")) { message(Messages.CodecComponentDirectionInvalid, "name" to name, "it" to it) } }

    }

    private fun readAttributes(element: CircXmlElement): Pair<Map<String, String>, List<String>> {
        val attrs = linkedMapOf<String, String>()
        val preserved = mutableListOf<String>()
        val names = mutableSetOf<String>()
        for (child in element.children.filter { it.name == "a" }) {
            val key = child.attributes["name"] ?: throw LocalizedArgumentException(message(Messages.CodecAttributeNameRequired))
            requireLocalized(names.add(key)) { message(Messages.CodecAttributeDuplicate, "key" to key) }
            val value = child.attributes["val"]
            if (child.children.isNotEmpty() || child.attributes.keys.any { it !in setOf("name", "val") }) preserved += child.rawXml
            else {
                requireLocalized(value == null || child.text.isBlank()) { message(Messages.CodecAttributeTwoValues, "key" to key) }
                attrs[key] = value ?: child.text
            }
        }
        return attrs to preserved
    }

    internal fun bindSubcircuit(component: CircuitComponent, child: CircuitDocument): CircuitComponent {
        val appearance = SymbolAppearance.rendered(child)
        val bound = if (appearance != null) AppearanceEdits.bind(component, child, appearance)
        else bindClassicAppearance(component, child)
        return bound.copy(sharedLabel = child.attributes["clabel"].orEmpty())
    }

    private fun bindClassicAppearance(component: CircuitComponent, child: CircuitDocument): CircuitComponent {
        val pins = child.components.filter { it.kind in setOf(ComponentKind.INPUT, ComponentKind.OUTPUT) }
        val edges = listOf("north", "south", "east", "west").associateWith { edge ->
            pins.filter { opposite(it.attributes["facing"] ?: "east") == edge }
                .sortedWith(if (edge in setOf("east", "west")) compareBy({ it.position.y }, { it.position.x }) else compareBy({ it.position.x }, { it.position.y }))
        }
        val north = edges.getValue("north").size; val south = edges.getValue("south").size
        val east = edges.getValue("east").size; val west = edges.getValue("west").size
        val vertical = maxOf(north, south); val horizontal = maxOf(east, west)
        fun dimension(count: Int, others: Int) = if (count < 3) 30 else 10 * count + if (others == 0) 0 else 10
        fun offset(count: Int, opposite: Int, others: Int): Int {
            val max = maxOf(count, opposite)
            return (when (max) { 0, 1 -> if (others == 0) 15 else 10; 2 -> 10; else -> if (others == 0) 5 else 10 }) + 10 * ((max - count) / 2)
        }
        val width = dimension(vertical, horizontal); val height = dimension(horizontal, vertical)
        val offsets = mapOf("north" to offset(north, south, horizontal), "south" to offset(south, north, horizontal),
            "east" to offset(east, west, vertical), "west" to offset(west, east, vertical))
        val anchor = when { east > 0 -> GridPoint(width, offsets.getValue("east")); north > 0 -> GridPoint(offsets.getValue("north"), 0)
            west > 0 -> GridPoint(0, offsets.getValue("west")); south > 0 -> GridPoint(offsets.getValue("south"), height); else -> GridPoint(0, 0) }
        val ports = edges.flatMap { (edge, edgePins) -> edgePins.mapIndexed { index, pin ->
            val along = offsets.getValue(edge) + index * 10
            val point = when (edge) { "north" -> GridPoint(along, 0); "south" -> GridPoint(along, height); "east" -> GridPoint(width, along); else -> GridPoint(0, along) }
            ComponentPort(pin.id, GridPoint(point.x - anchor.x, point.y - anchor.y),
                if (pin.kind == ComponentKind.INPUT) PortDirection.INPUT else PortDirection.OUTPUT, pin.width, pin.label)
        } }
        return component.copy(explicitPorts = ports, explicitBounds = ComponentBounds(-anchor.x, -anchor.y, width - anchor.x, height - anchor.y))
    }

    private fun rejectRecursiveCircuits(circuits: List<CircuitDocument>) {
        val byName = circuits.associateBy { it.name }
        val visited = mutableSetOf<String>()
        fun visit(name: String, path: Set<String>) {
            requireLocalized(name !in path) { message(Messages.CodecRecursiveSubcircuits, "value1" to (path + name).joinToString(" → ")) }
            if (!visited.add(name)) return
            for (component in byName.getValue(name).components) component.circuitName?.let { visit(it, path + name) }
        }
        circuits.forEach { visit(it.name, emptySet()) }
    }



    private fun opposite(facing: String) = when (facing) { "east" -> "west"; "west" -> "east"; "north" -> "south"; else -> "north" }

    private fun defaultExportAttributes(component: CircuitComponent): Map<String, String> = when (component.kind.name) {
        "INPUT", "OUTPUT" -> mapOf("facing" to (if (component.kind == ComponentKind.OUTPUT) "west" else "east"), "type" to if (component.kind == ComponentKind.OUTPUT) "output" else "input", "width" to "1", "appearance" to "classic")
        "CLOCK" -> mapOf("facing" to "east", "highDuration" to "1", "lowDuration" to "1", "phaseOffset" to "0", "appearance" to "classic")
        "D_FLIP_FLOP", "REGISTER", "COUNTER" -> mapOf("appearance" to "classic", "trigger" to "rising")
        "AND", "OR", "XOR", "NAND", "NOR", "XNOR" -> mapOf("facing" to "east", "width" to "1", "size" to "50", "inputs" to "2", "out" to "01")
        "NOT" -> mapOf("facing" to "east", "width" to "1", "size" to "30", "out" to "01")
        "BUFFER" -> mapOf("facing" to "east", "width" to "1", "out" to "01")
        else -> emptyMap()
    }

    private const val projectTextKey = "#text"
    private val pointPattern = Regex("\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)")
    private val xmlAttributeNamePattern = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")

    private fun componentDescription(component: CircuitComponent, registry: ComponentRegistry): Pair<String, String> {
        val definition = registry.requireDefinition(component.kind)
        return definition.library to definition.fileName
    }

    private fun parsePoint(text: String?): GridPoint {
        val match = text?.let { pointPattern.matchEntire(it) }
            ?: throw LocalizedArgumentException(message(Messages.CodecPositionInvalid, "text" to text))
        val x = match.groupValues[1].toIntOrNull(); val y = match.groupValues[2].toIntOrNull()
        requireLocalized(x != null && y != null && x in -1_000_000..1_000_000 && y in -1_000_000..1_000_000) { message(Messages.CodecPositionLimit) }
        return GridPoint(x, y)
    }

    private fun pointText(point: GridPoint): String {
        requireLocalized(point.x in -1_000_000..1_000_000 && point.y in -1_000_000..1_000_000) { message(Messages.CodecPositionLimit) }
        return "(${point.x},${point.y})"
    }

    private fun StringBuilder.appendCircuitAttribute(name: String, value: String, indent: String) {
        append(indent).append("<a")
        appendAttribute("name", name)
        if (name == "contents") append('>').append(escape(value)).append("</a>\n")
        else { appendAttribute("val", value); append("/>\n") }
    }

    private fun StringBuilder.appendAttribute(name: String, value: String) {
        requireLocalized(xmlAttributeNamePattern.matches(name)) { message(Messages.CodecXmlAttributeNameInvalid, "name" to name) }
        append(' ').append(name).append("=\"").append(escape(value)).append('"')
    }

    internal fun writeVhdlDefinition(definition: SharedVhdlDefinition): String = buildString {
        append("<vhdl")
        definition.xmlAttributes.forEach { (name, value) -> appendAttribute(name, value) }
        append('>').append(escape(definition.source)).append("</vhdl>")
    }

    private fun escape(text: String): String = buildString {
        text.forEach { char ->
            requireLocalized(char.code >= 32 || char in "\r\n\t") { message(Messages.CodecXmlCharacterInvalid) }
            append(when (char) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; '\n' -> "&#10;"; '\r' -> "&#13;"; '\t' -> "&#9;"; else -> char.toString() })
        }
    }
}
