package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Built-in library membership is stored in the project's native .circ declarations. */
object ProjectBuiltinLibraries {
    /**
     * The display name of each built-in library, as Evolution's Library.getDisplayName; the descriptor written to the
     * .circ (`#Plexers`) never changes.
     */
    private val displayNames = mapOf(
        "#Wiring" to Messages.LibraryWiring,
        "#Gates" to Messages.LibraryGates,
        "#Plexers" to Messages.LibraryPlexers,
        "#Arithmetic" to Messages.LibraryArithmetic,
        "#FPArithmetic" to Messages.LibraryFloat,
        "#Memory" to Messages.LibraryMemory,
        "#I/O" to Messages.LibraryIo,
        "#Input/Output-Extra" to Messages.LibraryExtra,
        "#TTL" to Messages.LibraryTtl,
        "#Base" to Messages.LibraryAnnotations,
        "#HDL-IP" to "library_hdl_ip",
        "#TCL" to "library_tcl",
        "#BFH-Praktika" to "library_bfh",
        "#Soc" to "library_soc",
    )

    /** The localized name of [library]; another library (an extension's) shows its descriptor without the `#`. */
    fun displayName(library: String): LocalizedText =
        displayNames[library]?.let { LocalizedText(it, fallback = library.removePrefix("#")) }
            ?: LocalizedText.literal(library.removePrefix("#"))

    fun available(registry: ComponentRegistry): List<String> =
        registry.definitions.map { it.library }.filter { it.startsWith('#') }.distinct()

    fun includes(project: CircuitProject, library: String): Boolean =
        !library.startsWith('#') || library in project.libraries.values

    fun withDefaults(project: CircuitProject, registry: ComponentRegistry): CircuitProject =
        available(registry).fold(project, ::add)

    fun add(project: CircuitProject, library: String): CircuitProject {
        if (library in project.libraries.values) return project
        val id = generateSequence(0) { it + 1 }.map(Int::toString).first { it !in project.libraries }
        return project.copy(libraries = project.libraries + (id to library))
    }

    fun remove(project: CircuitProject, library: String, registry: ComponentRegistry): CircuitProject {
        val ids = project.libraries.filterValues { it == library }.keys
        if (ids.isEmpty()) return project
        val usedInCircuit = project.circuits.any { circuit -> circuit.components.any { component ->
            component.xmlAttributes["lib"] in ids || registry.definition(component.kind)?.library == library
        } }
        val elements = project.extraXml.map { CircXmlParser(it).parse() }
        val usedByTool = elements.any { element ->
            element.name in setOf("toolbar", "mappings") && element.children.any { it.name == "tool" && it.attributes["lib"] in ids }
        }
        requireLocalized(!usedInCircuit && !usedByTool) { message("active_library_in_use", "name" to displayName(library)) }
        return project.copy(
            libraries = project.libraries - ids,
            extraXml = elements.filterNot { it.name == "lib" && it.attributes["name"] in ids }.map { it.rawXml },
        )
    }
}
