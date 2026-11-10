package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

/** The caller resolves files inside its granted document scope. This codec never opens paths or URLs. */
fun interface CircuitLibraryResolver {
    fun resolve(fromSourceId: String, reference: String): CircuitLibrarySource?
}

data class CircuitLibrarySource(val id: String, val xml: String, val revision: String = "")
data class CircuitLibrarySnapshot(val sourceId: String, val revision: String, val circuitNames: List<String>)
data class CircuitLibraryChange(val sourceId: String, val notice: LocalizedText) {
    constructor(sourceId: String, message: String) : this(sourceId, LocalizedText.literal(message))
    val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
}

class ResolvedCircuitProject internal constructor(
    val project: CircuitProject,
    val rootSourceId: String,
    val snapshots: List<CircuitLibrarySnapshot>,
    val migrations: List<CircMigration>,
    internal val sources: Map<String, LibraryFile>,
    internal val references: List<LibraryReference>,
    internal val libraryOrigins: Map<String, Pair<String, String>>,
) {
    fun changes(resolver: CircuitLibraryResolver): List<CircuitLibraryChange> = references.mapNotNull { reference ->
        val original = sources.getValue(reference.target).source
        val current = try { resolver.resolve(reference.from, reference.path) } catch (failure: Exception) {
            return@mapNotNull CircuitLibraryChange(original.id, message(Messages.CodecLibraryCheckFailed, "reference_path" to reference.path, "failure_message" to failure.localizedDescription(LocalizedText.literal(""))))
        }
        when {
            current == null -> CircuitLibraryChange(original.id, message(Messages.CodecLibraryMissing, "reference_path" to reference.path))
            current.id != original.id -> CircuitLibraryChange(original.id, message(Messages.CodecLibraryIdentityChanged, "reference_path" to reference.path))
            current.xml != original.xml || current.revision != original.revision -> CircuitLibraryChange(original.id, message(Messages.CodecLibraryChanged, "reference_path" to reference.path))
            else -> null
        }
    }.distinctBy { it.sourceId }
}

sealed interface CircLibraryImportResult {
    data class Success(val resolved: ResolvedCircuitProject) : CircLibraryImportResult
    data class Failure(val notices: List<LocalizedText>) : CircLibraryImportResult {
        val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
    }
}

internal data class LibraryFile(
    val source: CircuitLibrarySource,
    val root: CircXmlElement,
    val circuits: Map<String, CircXmlElement>,
    val dependencies: Map<String, String>,
    val names: Map<String, String> = emptyMap(),
)
internal data class LibraryReference(val from: String, val path: String, val target: String)

object CircLibraryCodec {
    fun read(xml: String, sourceId: String, resolver: CircuitLibraryResolver): CircLibraryImportResult =
        read(xml, sourceId, resolver, ComponentCatalog.registry)

    fun read(xml: String, sourceId: String, resolver: CircuitLibraryResolver, registry: ComponentRegistry): CircLibraryImportResult = try {
        requireLocalized(sourceId.isNotBlank()) { message(Messages.CodecSourceIdentityRequired) }
        val files = linkedMapOf<String, LibraryFile>()
        val references = mutableListOf<LibraryReference>()
        val legacyMigrations = mutableListOf<CircMigration>()
        var total = 0L
        fun load(source: CircuitLibrarySource, stack: List<String>) {
            requireLocalized(source.id.isNotBlank()) { message(Messages.CodecResolverIdentityRequired) }
            requireLocalized(source.id !in stack) { message(Messages.CodecLibraryCycle, "value1" to (stack + source.id).joinToString(" -> ")) }
            files[source.id]?.let {
                requireLocalized(it.source == source) { message(Messages.CodecLibraryChangedDuringRead, "source_id" to source.id) }
                return
            }
            requireLocalized(files.size < 128 && stack.size < 32) { message(Messages.CodecLibraryCountLimit) }
            total += source.xml.length
            requireLocalized(total <= CircBundleCodec.MAX_TOTAL_BYTES) { message(Messages.CodecLibraryContentLimit) }
            val input = CircXmlParser(source.xml).parse()
            requireLocalized(input.name == "project") { message(Messages.CodecLibraryProjectInvalid, "source_id" to source.id) }
            input.requireTextBeforeChildren()
            val profile = CircFormatProfile.parse(input.attributes["source"])
            val root = if (profile.needsLegacyConversion) LegacyCircMigration.repair(input, profile) else input
            val circuits = root.children.filter { it.name == "circuit" }.associateBy {
                it.attributes["name"] ?: throw LocalizedArgumentException(message(Messages.CodecLibraryCircuitNameRequired, "source_id" to source.id))
            }
            requireLocalized(circuits.isNotEmpty() && circuits.size == root.children.count { it.name == "circuit" }) { message(Messages.CodecLibraryCircuitsInvalid, "source_id" to source.id) }
            if (profile.needsLegacyConversion) {
                val main = root.children.firstOrNull { it.name == "main" }?.attributes?.get("name") ?: circuits.keys.first()
                legacyMigrations += LegacyCircMigration.notice(profile, main)
            }
            val dependencies = linkedMapOf<String, String>()
            for (library in root.children.filter { it.name == "lib" }) {
                val description = library.attributes["desc"].orEmpty()
                if (!description.startsWith("file#")) continue
                val id = library.attributes["name"] ?: throw LocalizedArgumentException(message(Messages.CodecLibraryIdentityRequired))
                val path = description.removePrefix("file#")
                requireLocalized(path.isNotBlank() && path.length <= 2048 && path.none { it.code < 32 }) { message(Messages.CodecLibraryReferenceInvalid) }
                val target = resolver.resolve(source.id, path) ?: throw LocalizedArgumentException(message(Messages.CodecLibraryReferenceMissing, "path" to path, "source_id" to source.id))
                load(target, stack + source.id)
                requireLocalized(dependencies.put(id, target.id) == null) { message(Messages.CodecLibraryIdDuplicate, "id" to id) }
                references += LibraryReference(source.id, path, target.id)
            }
            files[source.id] = LibraryFile(source, root, circuits, dependencies)
        }
        load(CircuitLibrarySource(sourceId, xml), emptyList())
        val ordered = listOf(files.getValue(sourceId)) + files.values.filter { it.source.id != sourceId }
        val reserved = ordered.first().circuits.keys.toMutableSet()
        val named = ordered.mapIndexed { index, file ->
            val names = file.circuits.keys.associateWith { name ->
                if (index == 0) name else {
                    val prefix = "library_${identityHash(file.source.id)}_"
                    var candidate = prefix + name
                    var suffix = 1
                    while (!reserved.add(candidate)) candidate = prefix + name + "_${suffix++}"
                    candidate
                }
            }
            file.copy(names = names)
        }.associateBy { it.source.id }
        val exposedCache = mutableMapOf<Pair<String, String>, Pair<LibraryFile, String>?>()
        fun exposed(fileId: String, name: String): Pair<LibraryFile, String>? {
            val key = fileId to name
            if (key in exposedCache) return exposedCache[key]
            val file = named.getValue(fileId)
            if (name in file.circuits) return (file to name).also { exposedCache[key] = it }
            val matches = file.dependencies.values.distinct().mapNotNull { exposed(it, name) }.distinctBy { it.first.source.id to it.second }
            requireLocalized(matches.size <= 1) { message(Messages.CodecLibraryCircuitAmbiguous, "fileId" to fileId, "name" to name) }
            return matches.singleOrNull().also { exposedCache[key] = it }
        }
        val libraryIds = named.values.flatMapIndexed { index, file ->
            file.root.children.filter { it.name == "lib" && !it.attributes["desc"].orEmpty().startsWith("file#") }
                .map { file.source.id to it.attributes.getValue("name") }.map { it to "library_${index}_${it.second}" }
        }.toMap()
        fun rewrite(file: LibraryFile, element: CircXmlElement): String {
            val declarations = when (element.name) {
                "circuit" -> element.children.filter { it.name == "comp" }
                "toolbar", "mappings" -> element.children.filter { it.name == "tool" }
                else -> emptyList()
            }
            return CircXmlRewrite.tree(element) { node ->
                if (declarations.none { it === node }) node.attributes else {
                    val name = node.attributes["name"].orEmpty()
                    val id = node.attributes["lib"]?.takeUnless(String::isEmpty)
                    val dependency = file.dependencies[id]
                    when {
                        dependency != null -> {
                            val target = exposed(dependency, name) ?: throw LocalizedArgumentException(message(Messages.CodecExternalCircuitMissing, "name" to name))
                            (node.attributes - "lib") + ("name" to target.first.names.getValue(target.second))
                        }
                        id == null && name in file.names -> (node.attributes - "lib") + ("name" to file.names.getValue(name))
                        id != null -> node.attributes + ("lib" to (libraryIds[file.source.id to id] ?: throw LocalizedArgumentException(message(Messages.CodecLibraryUnknown, "id" to id))))
                        else -> node.attributes
                    }
                }
            }
        }
        val root = named.getValue(sourceId)
        val children = mutableListOf<String>()
        for (file in named.values) {
            for (library in file.root.children.filter { it.name == "lib" && !it.attributes["desc"].orEmpty().startsWith("file#") }) {
                val attrs = library.attributes + ("name" to libraryIds.getValue(file.source.id to library.attributes.getValue("name")))
                children += CircXmlRewrite.attributes(library.rawXml, attrs)
            }
        }
        for (node in root.root.children.filter { it.name !in setOf("lib", "circuit") }) children += rewrite(root, node)
        for (file in named.values) for ((name, circuit) in file.circuits) {
            val rewritten = CircXmlParser(rewrite(file, circuit)).parse()
            children += CircXmlRewrite.attributes(rewritten.rawXml, rewritten.attributes + mapOf(
                "name" to file.names.getValue(name),
                "id" to circuitId(file, name, sourceId),
                "logisim-mobile-source" to (rewritten.attributes["logisim-mobile-source"] ?: file.root.attributes["source"].orEmpty()),
            ))
        }
        val flattened = CircXmlRewrite.element("project", savedProjectAttributes(root.root), root.root.text, children)
        val imported = when (val result = CircCodec.readProject(flattened, registry, evolutionNames = false)) {
            is CircProjectImportResult.Success -> result
            is CircProjectImportResult.Failure -> throw LocalizedArgumentException(LocalizedText.join(result.notices))
        }
        CircLibraryImportResult.Success(ResolvedCircuitProject(imported.project, sourceId,
            named.values.map { CircuitLibrarySnapshot(it.source.id, it.source.revision, it.names.values.toList()) },
            legacyMigrations + imported.migrations, named, references,
            libraryIds.entries.associate { it.value to it.key }))
    } catch (failure: Exception) {
        CircLibraryImportResult.Failure(listOf(failure.localizedDescription(message(Messages.CodecLibrariesResolutionFailed))))
    }

    fun read(bundle: CircuitBundle, registry: ComponentRegistry = ComponentCatalog.registry): CircLibraryImportResult =
        read(CircXmlEncoding.decode(bundle.files.getValue(bundle.mainFile)), bundle.mainFile, CircuitLibraryResolver { from, reference ->
            val path = BundlePaths.resolve(from, reference)
            bundle.files[path]?.let { CircuitLibrarySource(path, CircXmlEncoding.decode(it)) }
        }, registry)

    fun bundle(resolved: ResolvedCircuitProject, editedProject: CircuitProject = resolved.project,
        registry: ComponentRegistry = ComponentCatalog.registry): CircuitBundle {
        data class Definition(val source: String, val localName: String, val document: CircuitDocument)
        val originals = resolved.sources.values.flatMap { file -> file.circuits.keys.map { name ->
            circuitId(file, name, resolved.rootSourceId) to (file to name)
        } }.toMap()
        val definitions = editedProject.circuits.map { document ->
            val original = originals[document.id]
            val source = original?.first?.source?.id ?: resolved.rootSourceId
            val name = if (original != null && document.name == original.first.names.getValue(original.second)) original.second else document.name
            Definition(source, name, document)
        }
        val byName = definitions.associateBy { it.document.name }
        val byId = definitions.associateBy { it.document.id }
        val renamedByGlobalName = resolved.sources.values.flatMap { file -> file.names.map { (old, global) ->
            global to byId[circuitId(file, old, resolved.rootSourceId)]
        } }.toMap()
        val originalCache = mutableMapOf<Pair<String, String>, Definition?>()
        fun originalDefinition(source: String, name: String): Definition? {
            val key = source to name
            if (key in originalCache) return originalCache[key]
            val file = resolved.sources.getValue(source)
            val found = if (name in file.circuits) byId[circuitId(file, name, resolved.rootSourceId)] else {
                file.dependencies.values.distinct().mapNotNull { originalDefinition(it, name) }.distinct().singleOrNull()
            }
            originalCache[key] = found
            return found
        }
        val mainSource = byName.getValue(editedProject.mainCircuitName).source
        requireLocalized(mainSource == resolved.rootSourceId) { message(Messages.CodecExternalMainExport) }
        val paths = linkedMapOf<String, String>()
        paths[mainSource] = "main.circ"
        for (source in resolved.sources.keys) if (source !in paths) {
            var path = "library/library_${identityHash(source)}.circ"
            var suffix = 1
            while (path in paths.values) path = "library/library_${identityHash(source)}_${suffix++}.circ"
            paths[source] = path
        }
        val serializedProject = CircXmlParser(CircCodec.writeProject(editedProject, registry)).parse()
        val serialized = serializedProject.children
            .filter { it.name == "circuit" }.associateBy { it.attributes.getValue("id") }
        val serializedLibraries = serializedProject.children
            .filter { it.name == "lib" }.associateBy { it.attributes.getValue("name") }
        val result = linkedMapOf<String, ByteArray>()
        for ((sourceId, file) in resolved.sources) {
            val owned = definitions.filter { it.source == sourceId }
            requireLocalized(owned.isNotEmpty()) { message(Messages.CodecExternalEmptyExport, "sourceId" to sourceId) }
            requireLocalized(owned.map { it.localName }.distinct().size == owned.size) { message(Messages.CodecExternalNamesDuplicate, "sourceId" to sourceId) }
            val libraries = file.root.children.filter { it.name == "lib" }.associateBy { it.attributes.getValue("name") }.toMutableMap()
            val libraryXml = linkedMapOf<String, String>()
            for ((id, library) in libraries) {
                val target = file.dependencies[id]
                libraryXml[id] = if (target == null) library.rawXml else {
                    val updated = CircXmlRewrite.tree(library) { node ->
                        if (node.name != "tool" || library.children.none { it === node }) node.attributes else {
                            val definition = originalDefinition(target, node.attributes["name"].orEmpty())
                            if (definition == null) node.attributes else node.attributes + ("name" to definition.localName)
                        }
                    }
                    CircXmlRewrite.attributes(updated,
                        library.attributes + ("desc" to "file#${relativePath(paths.getValue(sourceId), paths.getValue(target))}"))
                }
            }
            fun availableId(): String {
                var candidate = 0
                while (candidate.toString() in libraryXml) candidate++
                return candidate.toString()
            }
            fun externalLibrary(target: String): String {
                file.dependencies.entries.firstOrNull { it.value == target }?.let { return it.key }
                val description = "file#${relativePath(paths.getValue(sourceId), paths.getValue(target))}"
                libraryXml.entries.firstOrNull { CircXmlParser(it.value).parse().attributes["desc"] == description }?.let { return it.key }
                val id = availableId()
                libraryXml[id] = CircXmlRewrite.element("lib", mapOf("name" to id, "desc" to description), "", emptyList())
                return id
            }
            fun builtinLibrary(globalId: String): String {
                val origin = resolved.libraryOrigins[globalId]
                if (origin?.first == sourceId && origin.second in libraryXml) return origin.second
                val description = serializedLibraries[globalId]?.attributes?.get("desc")
                    ?: throw LocalizedArgumentException(message(Messages.CodecExternalLibraryUnknown, "globalId" to globalId))
                libraries.entries.firstOrNull { it.value.attributes["desc"] == description }?.let { return it.key }
                val id = availableId()
                val xml = CircXmlRewrite.element("lib", mapOf("name" to id, "desc" to description), "", emptyList())
                libraryXml[id] = xml
                libraries[id] = CircXmlParser(xml).parse()
                return id
            }
            for ((globalId, library) in serializedLibraries) {
                val origin = resolved.libraryOrigins[globalId]
                if (origin?.first == sourceId || origin == null && sourceId == resolved.rootSourceId) {
                    val localId = builtinLibrary(globalId)
                    val xml = CircXmlRewrite.attributes(library.rawXml, library.attributes + ("name" to localId))
                    libraryXml[localId] = xml
                    libraries[localId] = CircXmlParser(xml).parse()
                }
            }
            fun metadata(element: CircXmlElement, merged: Boolean): String {
                if (element.name !in setOf("toolbar", "mappings")) return element.rawXml
                return CircXmlRewrite.tree(element) { node ->
                    if (element.children.none { it === node } || node.name != "tool") node.attributes else {
                        val name = node.attributes["name"].orEmpty()
                        val library = node.attributes["lib"]?.takeUnless(String::isEmpty)
                        val definition = when {
                            merged && library == null -> byName[name] ?: renamedByGlobalName[name]
                            !merged && library == null -> originalDefinition(sourceId, name)
                            !merged && file.dependencies[library] != null -> originalDefinition(file.dependencies.getValue(library!!), name)
                            else -> null
                        }
                        when {
                            definition != null -> {
                                val attrs = (node.attributes - "lib") + ("name" to definition.localName)
                                if (definition.source == sourceId) attrs else attrs + ("lib" to externalLibrary(definition.source))
                            }
                            merged && library != null -> node.attributes + ("lib" to builtinLibrary(library))
                            else -> node.attributes
                        }
                    }
                }
            }
            val circuitXml = owned.map { definition ->
                val node = serialized.getValue(definition.document.id)
                val components = node.children.filter { it.name == "comp" }.zip(definition.document.components)
                    .associate { it.first.sourceOffset to it.second }
                val rewritten = CircXmlRewrite.tree(node) { element ->
                    if (element.name != "comp" || node.children.none { it === element }) element.attributes else {
                        val component = components.getValue(element.sourceOffset)
                        if (component.kind == ComponentKind.SUBCIRCUIT) {
                            val target = byName[component.circuitName] ?: throw LocalizedArgumentException(message(Messages.CodecExternalSubcircuitMissing))
                            val attrs = (element.attributes - "lib") + ("name" to target.localName)
                            if (target.source == sourceId) attrs else attrs + ("lib" to externalLibrary(target.source))
                        } else element.attributes + ("lib" to builtinLibrary(element.attributes.getValue("lib")))
                    }
                }
                CircXmlRewrite.attributes(rewritten, node.attributes + mapOf(
                    "name" to definition.localName, "id" to definition.document.id, "logisim-mobile-global-id" to "true"))
            }
            val selected = if (sourceId == mainSource) byName.getValue(editedProject.mainCircuitName).localName else {
                val old = file.root.children.firstOrNull { it.name == "main" }?.attributes?.get("name")
                owned.firstOrNull { it.localName == old }?.localName ?: owned.first().localName
            }
            val metadataRoot = if (sourceId == resolved.rootSourceId) serializedProject else file.root
            val main = metadataRoot.children.firstOrNull { it.name == "main" }?.let {
                CircXmlRewrite.attributes(it.rawXml, it.attributes + ("name" to selected))
            } ?: CircXmlRewrite.element("main", mapOf("name" to selected), "", emptyList())
            val other = metadataRoot.children.filter { it.name !in setOf("lib", "circuit", "main") }.map {
                metadata(it, sourceId == resolved.rootSourceId)
            }
            val xml = CircXmlRewrite.element("project", savedProjectAttributes(metadataRoot), metadataRoot.text,
                libraryXml.values.toList() + main + other + circuitXml)
            result[paths.getValue(sourceId)] = xml.encodeToByteArray()
        }
        result["manifest.xml"] = CircBundleCodec.manifestBytes()
        val bundle = CircuitBundle("main.circ", result)
        val reopened = read(bundle, registry)
        requireLocalized(reopened is CircLibraryImportResult.Success) {
            LocalizedText.join((reopened as CircLibraryImportResult.Failure).notices)
        }
        return bundle
    }

    private fun savedProjectAttributes(root: CircXmlElement): Map<String, String> =
        if (CircFormatProfile.parse(root.attributes["source"]).needsLegacyConversion)
            root.attributes + ("source" to LegacyCircMigration.targetSource) else root.attributes

    private fun relativePath(from: String, to: String): String = when {
        '/' !in from -> "./$to"
        '/' !in to -> "../$to"
        else -> "./${to.substringAfterLast('/')}"
    }

    private fun circuitId(file: LibraryFile, name: String, rootId: String): String {
        val node = file.circuits.getValue(name)
        val id = node.attributes["id"] ?: name
        return if (file.source.id == rootId || node.attributes["logisim-mobile-global-id"] == "true") id else qualifiedId(file.source.id, id)
    }

    internal fun qualifiedId(source: String, localId: String): String = "library:${identityHash(source)}:$localId"
    internal fun identityHash(text: String): String {
        var value = 0xcbf29ce484222325uL
        for (byte in text.encodeToByteArray()) value = (value xor byte.toUByte().toULong()) * 0x100000001b3uL
        return value.toString(16)
    }
}

internal object CircXmlRewrite {
    fun attributes(xml: String, attributes: Map<String, String>): String {
        var quote: Char? = null
        val end = xml.indices.first { index ->
            val char = xml[index]
            if (quote != null) { if (char == quote) quote = null; false }
            else when (char) { '\'', '"' -> { quote = char; false }; '>' -> true; else -> false }
        }
        val tag = CircXmlParser(xml).parse().name
        val selfClosing = xml.substring(0, end).trimEnd().endsWith('/')
        return "<$tag" + attributes.entries.joinToString("") { " ${it.key}=\"${escape(it.value)}\"" } +
            (if (selfClosing) "/>" else ">") + xml.substring(end + 1)
    }
    fun tree(element: CircXmlElement, transform: (CircXmlElement) -> Map<String, String>): String {
        var original = element.rawXml
        for (child in element.children.asReversed()) {
            val start = child.sourceOffset - element.sourceOffset
            requireLocalized(start >= 0 && original.substring(start, start + child.rawXml.length) == child.rawXml) { message(Messages.CodecXmlStructureInconsistent) }
            val replacement = tree(child, transform)
            original = original.replaceRange(start, start + child.rawXml.length, replacement)
        }
        return attributes(original, transform(element))
    }
    fun element(name: String, attributes: Map<String, String>, text: String, children: List<String>): String =
        "<$name" + attributes.entries.joinToString("") { " ${it.key}=\"${escape(it.value)}\"" } + ">" +
            escape(text) + children.joinToString(if (text.isBlank()) "\n" else "") + "</$name>"
    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
