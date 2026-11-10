package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Copies authorized circuit definitions into the active file, with no live external path. */
object ProjectCircuitLibraries {
    private const val libraryId = "logisim-mobile-library-id"
    private const val libraryName = "logisim-mobile-library-name"
    private const val sourceName = "logisim-mobile-library-source-name"
    private const val circuitSource = "logisim-mobile-source"

    data class Library(val id: String, val name: String, val circuits: List<CircuitDocument>)

    fun list(project: CircuitProject): List<Library> = project.circuits
        .filter { it.xmlAttributes[libraryId] != null && it.xmlAttributes[libraryName] != null }
        .groupBy { it.xmlAttributes.getValue(libraryId) }
        .map { (id, circuits) -> Library(id, circuits.first().xmlAttributes.getValue(libraryName), circuits) }

    fun attach(project: CircuitProject, source: CircuitProject, name: String): CircuitProject {
        val group = unique(list(project).mapTo(mutableSetOf()) { it.id }, name)
        return project.copy(circuits = project.circuits + install(project, source, group, name))
    }

    fun reload(project: CircuitProject, id: String, source: CircuitProject): CircuitProject {
        val library = list(project).firstOrNull { it.id == id } ?: return project
        val matches = source.circuits.mapNotNull { incoming ->
            val previous = library.circuits.singleOrNull { attached ->
                val originalName = attached.xmlAttributes[sourceName]
                if (originalName != null) originalName == incoming.name
                else attached.name == incoming.name || legacyAlias(attached, incoming, library)
            }
            previous?.let { incoming.name to it }
        }.toMap()
        requireLocalized(matches.values.distinctBy { it.id }.size == matches.size) {
            message("active_library_reload_incompatible", "name" to library.name)
        }
        val oldByName = library.circuits.associateBy { it.name }
        val installed = install(project, source, id, library.name, matches)
        val updatedByName = installed.associateBy { it.name }
        val external = project.circuits.filterNot { it.name in oldByName }
        val used = references(project, external).intersect(oldByName.keys)
        for (name in used) {
            val old = oldByName.getValue(name)
            val updated = updatedByName[name]
            requireLocalized(updated != null && interfaceOf(old) == interfaceOf(updated)) {
                message("active_library_reload_incompatible", "name" to name)
            }
        }
        val retained = project.circuits.mapNotNull { circuit ->
            if (circuit.name in oldByName) updatedByName[circuit.name]
            else circuit.copy(components = circuit.components.map { component ->
                val updated = updatedByName[component.circuitName]
                if (component.kind == ComponentKind.SUBCIRCUIT && updated != null)
                    CircCodec.bindSubcircuit(component, updated)
                else component
            })
        }
        return project.copy(circuits = retained + installed.filterNot { it.name in oldByName })
    }

    private fun legacyAlias(attached: CircuitDocument, incoming: CircuitDocument, library: Library): Boolean {
        if (!matchesUniqueName(attached.id, "library:${library.id}:${incoming.id}")) return false
        // Repeated imports suffixed the group name but based circuit aliases on the original filename.
        val baseName = library.name.substringBeforeLast(" (")
        return matchesUniqueName(attached.name, "${library.name}: ${incoming.name}") ||
            (baseName != library.name && matchesUniqueName(library.name, baseName) &&
                matchesUniqueName(attached.name, "$baseName: ${incoming.name}"))
    }

    private fun matchesUniqueName(value: String, base: String): Boolean {
        if (value == base) return true
        val suffix = value.removePrefix("$base (")
        return suffix != value && suffix.endsWith(")") && (suffix.dropLast(1).toIntOrNull() ?: 0) >= 2
    }

    private fun interfaceOf(document: CircuitDocument): List<ComponentPort> =
        placement(document).explicitPorts.map { it.copy(name = "") }
            .sortedWith(compareBy({ it.position.x }, { it.position.y }, { it.direction }, { it.width }, { it.label }))

    private fun install(
        project: CircuitProject,
        source: CircuitProject,
        group: String,
        name: String,
        matches: Map<String, CircuitDocument> = emptyMap(),
    ): List<CircuitDocument> {
        val ids = project.circuits.mapTo(mutableSetOf()) { it.id }
        val names = project.circuits.mapTo(mutableSetOf()) { it.name }
        val targetNames = source.circuits.associate { document ->
            val target = matches[document.name]?.name
                ?: if (names.add(document.name)) document.name else unique(names, "$name: ${document.name}")
            document.name to target
        }
        return source.circuits.map { document ->
            document.copy(
                id = matches[document.name]?.id ?: unique(ids, "library:$group:${document.id}"),
                name = targetNames.getValue(document.name),
                components = document.components.map { component ->
                    if (component.kind == ComponentKind.SUBCIRCUIT)
                        component.copy(circuitName = targetNames.getValue(requireNotNull(component.circuitName)))
                    else component
                },
                xmlAttributes = document.xmlAttributes + mapOf(
                    libraryId to group, libraryName to group, sourceName to document.name,
                    circuitSource to (document.xmlAttributes[circuitSource] ?: source.sourceVersion),
                ),
            )
        }
    }

    fun detach(project: CircuitProject, id: String): CircuitProject {
        val library = list(project).firstOrNull { it.id == id } ?: return project
        val removed = library.circuits.mapTo(mutableSetOf()) { it.name }
        val remaining = project.circuits.filter { it.name !in removed }
        requireLocalized(references(project, remaining).none { it in removed }) {
            message("active_library_in_use", "name" to library.name)
        }
        return project.copy(circuits = remaining)
    }

    private fun references(project: CircuitProject, external: List<CircuitDocument>): Set<String> = buildSet {
        add(project.mainCircuitName)
        external.forEach { circuit ->
            circuit.components.forEach { component ->
                if (component.kind == ComponentKind.SUBCIRCUIT) component.circuitName?.let(::add)
            }
        }
        project.extraXml.forEach { xml ->
            val node = CircXmlParser(xml).parse()
            if (node.name in setOf("toolbar", "mappings")) node.children.forEach { tool ->
                if (tool.name == "tool" && tool.attributes["lib"].isNullOrEmpty()) tool.attributes["name"]?.let(::add)
            }
        }
    }

    fun placement(circuit: CircuitDocument): CircuitComponent = CircCodec.bindSubcircuit(
        CircuitComponent("library-tool", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = circuit.name),
        circuit,
    )

    fun canPlace(project: CircuitProject, circuit: CircuitDocument, parent: CircuitDocument): Boolean {
        val byName = project.circuits.associateBy { it.name }
        val visited = mutableSetOf<String>()
        fun reachesParent(name: String): Boolean {
            if (!visited.add(name)) return false
            if (name == parent.name) return true
            return byName[name]?.components.orEmpty().any { it.circuitName?.let(::reachesParent) == true }
        }
        return byName[circuit.name]?.id == circuit.id && !reachesParent(circuit.name)
    }

    private fun unique(used: MutableSet<String>, base: String): String {
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) candidate = "$base (${suffix++})"
        return candidate
    }
}
