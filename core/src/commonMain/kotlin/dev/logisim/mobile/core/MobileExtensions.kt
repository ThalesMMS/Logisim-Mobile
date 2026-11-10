package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

enum class ExtensionCapability(val serialized: String) {
    PURE_LOGIC("pure-logic"), STATEFUL_LOGIC("stateful-logic"), CUSTOM_RENDERING("custom-rendering"),
    COMPANION_TCL("companion-tcl"), COMPANION_JAR("companion-jar");
}

data class ExtensionComponentSpec(val kind: String, val fileName: String, val library: String)

data class MobileExtensionManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val publisher: String,
    val licenseId: String,
    val licenseNotice: String,
    val apiMajor: Int = 1,
    val apiMinor: Int = 0,
    val capabilities: Set<ExtensionCapability>,
    val components: List<ExtensionComponentSpec>,
    val legacyJarClasses: Set<String> = emptySet(),
) {
    init {
        require(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+").matches(id)) { "Invalid extension ID." }
        require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9.-]+)?").matches(version)) { "Invalid extension version." }
        require(displayName.isNotBlank() && displayName.length <= 256 && publisher.isNotBlank() && publisher.length <= 256) { "Extension name and publisher are required and limited to 256 characters." }
        require(licenseId.isNotBlank() && licenseId.length <= 128 && licenseNotice.isNotBlank() && licenseNotice.length <= 64_000) { "Extension license and notice are required and bounded." }
        require(apiMajor >= 0 && apiMinor >= 0 && capabilities.isNotEmpty()) { "Invalid extension API or capabilities." }
        require(components.size in 1..256 && components.map { it.kind }.distinct().size == components.size) { "Invalid extension component list." }
        require(components.map { it.library to it.fileName }.distinct().size == components.size) { "Duplicate extension file identity." }
        require(components.all { Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}").matches(it.kind) && it.fileName.isNotBlank() && it.fileName.length <= 256 && it.library.isNotBlank() && it.library.length <= 256 }) { "Invalid extension component identity." }
        require(legacyJarClasses.all { Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*").matches(it) }) { "Invalid legacy JAR class." }
    }

    val fingerprint: String get() = ExtensionDigest.sha256(MobileExtensionManifestCodec.write(this).encodeToByteArray())
}

object MobileExtensionManifestCodec {
    fun read(xml: String): MobileExtensionManifest {
        require(xml.length <= 128_000) { "Extension manifest exceeds 128 KB." }
        val root = CircXmlParser(xml).parse()
        require(root.name == "mobile-extension" && root.text.isBlank()) { "Expected mobile-extension manifest." }
        require(root.attributes.keys == setOf("id", "version", "name", "publisher", "license", "api")) { "Unknown or missing manifest attributes." }
        val api = root.attributes.getValue("api").split('.')
        require(api.size == 2) { "Invalid extension API version." }
        val capabilities = mutableSetOf<ExtensionCapability>()
        val components = mutableListOf<ExtensionComponentSpec>()
        val legacy = mutableSetOf<String>()
        var notice: String? = null
        for (node in root.children) when (node.name) {
            "notice" -> {
                require(notice == null && node.attributes.isEmpty() && node.children.isEmpty()) { "Invalid license notice." }
                notice = node.text
            }
            "capability" -> {
                require(node.attributes.keys == setOf("name") && node.children.isEmpty() && node.text.isBlank()) { "Invalid capability." }
                val capability = ExtensionCapability.entries.firstOrNull { it.serialized == node.attributes["name"] }
                    ?: throw IllegalArgumentException("Unsupported extension capability: ${node.attributes["name"]}.")
                require(capabilities.add(capability)) { "Duplicate extension capability." }
            }
            "component" -> {
                require(node.attributes.keys == setOf("kind", "name", "library") && node.children.isEmpty() && node.text.isBlank()) { "Invalid component declaration." }
                components += ExtensionComponentSpec(node.attributes.getValue("kind"), node.attributes.getValue("name"), node.attributes.getValue("library"))
            }
            "legacy-jar" -> {
                require(node.attributes.keys == setOf("class") && node.children.isEmpty() && node.text.isBlank()) { "Invalid JAR migration declaration." }
                require(legacy.add(node.attributes.getValue("class"))) { "Duplicate JAR class." }
            }
            else -> throw IllegalArgumentException("Unsupported manifest element: ${node.name}.")
        }
        return MobileExtensionManifest(root.attributes.getValue("id"), root.attributes.getValue("version"),
            root.attributes.getValue("name"), root.attributes.getValue("publisher"), root.attributes.getValue("license"),
            notice ?: throw IllegalArgumentException("Missing license notice."), api[0].toIntOrNull() ?: -1,
            api[1].toIntOrNull() ?: -1, capabilities, components, legacy)
    }

    fun write(manifest: MobileExtensionManifest): String = buildString {
        append("<mobile-extension id=\"").append(xml(manifest.id)).append("\" version=\"").append(xml(manifest.version))
        append("\" name=\"").append(xml(manifest.displayName)).append("\" publisher=\"").append(xml(manifest.publisher))
        append("\" license=\"").append(xml(manifest.licenseId)).append("\" api=\"").append(manifest.apiMajor).append('.').append(manifest.apiMinor).append("\">\n")
        append("  <notice>").append(xml(manifest.licenseNotice)).append("</notice>\n")
        manifest.capabilities.sortedBy { it.serialized }.forEach { append("  <capability name=\"").append(it.serialized).append("\"/>\n") }
        manifest.components.sortedBy { it.kind }.forEach {
            append("  <component kind=\"").append(xml(it.kind)).append("\" name=\"").append(xml(it.fileName))
                .append("\" library=\"").append(xml(it.library)).append("\"/>\n")
        }
        manifest.legacyJarClasses.sorted().forEach { append("  <legacy-jar class=\"").append(xml(it)).append("\"/>\n") }
        append("</mobile-extension>\n")
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

data class ExtensionPolicy(
    val allowedCapabilities: Set<ExtensionCapability> = setOf(ExtensionCapability.PURE_LOGIC,
        ExtensionCapability.STATEFUL_LOGIC, ExtensionCapability.CUSTOM_RENDERING),
    val reviewedLicenses: Set<String> = setOf("GPL-3.0-only", "GPL-3.0-or-later", "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD"),
)

data class ExtensionApproval(val fingerprint: String, val capabilities: Set<ExtensionCapability>, val acceptedLicenseId: String)
data class StaticMobileExtension(val manifest: MobileExtensionManifest, val definitions: List<ComponentDefinition>)

sealed interface ExtensionRegistration {
    data class Installed(val catalog: MobileExtensionCatalog) : ExtensionRegistration
    data class Rejected(val notice: LocalizedText) : ExtensionRegistration {
        val reason: String get() = notice.resolve()
    }
}

/** Only definitions compiled into the application can be registered. A manifest never loads code. */
class MobileExtensionCatalog private constructor(
    val baseRegistry: ComponentRegistry,
    policy: ExtensionPolicy,
    installed: List<StaticMobileExtension>,
    private val legacyDefinitions: List<ComponentDefinition>,
) {
    constructor(baseRegistry: ComponentRegistry, policy: ExtensionPolicy = ExtensionPolicy()) :
        this(baseRegistry, policy, emptyList(), emptyList())
    val policy = policy.copy(allowedCapabilities = policy.allowedCapabilities.toSet(), reviewedLicenses = policy.reviewedLicenses.toSet())
    val installed = installed.map { extension -> extension.copy(manifest = extension.manifest.copy(
        capabilities = extension.manifest.capabilities.toSet(), components = extension.manifest.components.toList(),
        legacyJarClasses = extension.manifest.legacyJarClasses.toSet()), definitions = extension.definitions.toList()) }
    val registry: ComponentRegistry = this.installed.fold(baseRegistry) { result, extension -> result.withAdditional(extension.definitions) }
        .withAdditional(legacyDefinitions)

    fun install(provider: StaticMobileExtension, approval: ExtensionApproval): ExtensionRegistration {
        val manifest = provider.manifest
        fun rejected(key: String, vararg arguments: Pair<String, Any?>) = ExtensionRegistration.Rejected(message(key, *arguments))
        if (manifest.apiMajor != 1 || manifest.apiMinor > 0) return rejected(Messages.ExtensionsErrorApi, "major" to manifest.apiMajor, "minor" to manifest.apiMinor)
        if (installed.any { it.manifest.id == manifest.id }) return rejected(Messages.ExtensionsErrorInstalled)
        if (approval.fingerprint != manifest.fingerprint || approval.capabilities != manifest.capabilities || approval.acceptedLicenseId != manifest.licenseId)
            return rejected(Messages.ExtensionsErrorApproval)
        if (manifest.licenseId !in policy.reviewedLicenses) return rejected(Messages.ExtensionsErrorLicense, "license" to manifest.licenseId)
        if (!policy.allowedCapabilities.containsAll(manifest.capabilities)) return rejected(Messages.ExtensionsErrorCapabilities)
        val declared = manifest.components.associate { it.kind to (it.library to it.fileName) }
        val actual = provider.definitions.associate { it.kind.name to (it.library to it.fileName) }
        if (declared != actual || provider.definitions.size != declared.size) return rejected(Messages.ExtensionsErrorDefinitions)
        if (provider.definitions.any { candidate -> registry.definitions.any { it.library == candidate.library && it.fileName == candidate.fileName } })
            return rejected(Messages.ExtensionsErrorConflict)
        if (provider.definitions.any { it.behavior != null && !it.isCombinational } && ExtensionCapability.STATEFUL_LOGIC !in manifest.capabilities)
            return rejected(Messages.ExtensionsErrorStateful)
        return try { ExtensionRegistration.Installed(MobileExtensionCatalog(baseRegistry, policy, installed + provider, legacyDefinitions)) }
        catch (failure: IllegalArgumentException) { rejected(Messages.ExtensionsErrorRegistry, "details" to failure.message.orEmpty()) }
    }

    fun bindLegacyLibrary(reference: JarLibraryReference, extensionId: String): ComponentRegistry =
        withLegacyLibrary(reference, extensionId).registry

    /** Keeps explicit migration choices in the session catalog across further registrations. */
    fun withLegacyLibrary(reference: JarLibraryReference, extensionId: String): MobileExtensionCatalog {
        val provider = installed.singleOrNull { it.manifest.id == extensionId }
            ?: throw IllegalArgumentException("Select an installed extension for this JAR reference.")
        require(reference.className in provider.manifest.legacyJarClasses) { "The extension does not declare this JAR migration." }
        val definitions = provider.definitions.mapNotNull { definition ->
            val existing = registry.resolve(reference.descriptor, definition.fileName, emptyMap())
            if (existing != null) return@mapNotNull null
            val preferredKind = "${definition.kind.name}@${reference.libraryId}"
            val kind = if (registry.definition(ComponentKind(preferredKind)) == null) preferredKind else
                "$preferredKind@${ExtensionDigest.sha256(reference.descriptor.encodeToByteArray())}"
            definition.copy(kind = ComponentKind(kind), library = reference.descriptor)
        }
        return MobileExtensionCatalog(baseRegistry, policy, installed, legacyDefinitions + definitions)
    }
}

data class JarLibraryReference(val libraryId: String, val descriptor: String, val path: String, val className: String, val originalXml: String)
data class ExtensionDocumentEnvelope(val originalXml: String, val jarLibraries: List<JarLibraryReference>, val unknownLibraries: List<String>) {
    fun exportOriginal(): String = originalXml
}

object ExtensionReferenceScanner {
    private val builtinLibraries = setOf("#Base", "#Gates", "#Wiring", "#Plexers", "#Arithmetic",
        "#FPArithmetic", "#Memory", "#I/O", "#TTL", "#HDL-IP", "#TCL", "#BFH-Praktika", "#Input/Output-Extra", "#Soc")

    /** Inspection is data-only. Unknown/JAR references stay in the original document verbatim. */
    fun inspect(xml: String, recognizedLibraries: Set<String> = builtinLibraries): ExtensionDocumentEnvelope {
        val project = CircXmlParser(xml).parse()
        require(project.name == "project") { "Expected project document." }
        val jars = mutableListOf<JarLibraryReference>()
        val unknown = mutableListOf<String>()
        for (library in project.children.filter { it.name == "lib" }) {
            val descriptor = library.attributes["desc"] ?: throw IllegalArgumentException("Missing library descriptor.")
            if (descriptor.startsWith("jar#")) {
                val separator = descriptor.lastIndexOf('#')
                require(separator > 4 && separator < descriptor.lastIndex) { "Invalid JAR library descriptor." }
                jars += JarLibraryReference(library.attributes["name"].orEmpty(), descriptor,
                    descriptor.substring(4, separator), descriptor.substring(separator + 1), library.rawXml)
            } else if (!descriptor.startsWith("file#") && descriptor !in recognizedLibraries) unknown += descriptor
        }
        return ExtensionDocumentEnvelope(xml, jars, unknown)
    }
}
