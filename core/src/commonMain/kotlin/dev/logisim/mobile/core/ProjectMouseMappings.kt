package dev.logisim.mobile.core

enum class ProjectMouseButton { PRIMARY, MIDDLE, SECONDARY }

/** One mouse button and the modifier keys represented by Logisim's map attribute. */
data class ProjectMouseGesture(
    val button: ProjectMouseButton,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    fun fileValue(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        add("Button${button.ordinal + 1}")
    }.joinToString(" ")

    companion object {
        internal fun parse(value: String): ProjectMouseGesture? {
            val tokens = value.split(Regex("\\s+")).filter(String::isNotEmpty).toSet()
            val button = ProjectMouseButton.entries.singleOrNull { "Button${it.ordinal + 1}" in tokens } ?: return null
            if (tokens.any { it !in setOf("Ctrl", "Alt", "Shift", "Button${button.ordinal + 1}") }) return null
            return ProjectMouseGesture(button, "Ctrl" in tokens, "Alt" in tokens, "Shift" in tokens)
        }
    }
}

data class ProjectMouseTool(val library: String?, val name: String)

enum class ProjectMouseEditTool(val fileName: String) {
    POKE("Poke Tool"), EDIT("Edit Tool"), SELECT("Select Tool"),
    WIRING("Wiring Tool"), TEXT("Text Tool"), MENU("Menu Tool");

    val reference get() = ProjectMouseTool("#Base", fileName)
}

sealed interface ProjectMouseAction {
    data class Edit(val tool: ProjectMouseEditTool) : ProjectMouseAction
    data class Place(val prototype: CircuitComponent) : ProjectMouseAction
    data class Text(val prototype: CircuitComponent) : ProjectMouseAction
}

data class ProjectMouseBinding(val name: String, val action: ProjectMouseAction?)

object ProjectMouseMappings {
    fun read(project: CircuitProject, registry: ComponentRegistry = ComponentCatalog.registry): Map<ProjectMouseGesture, ProjectMouseBinding> =
        buildMap {
            for (xml in project.extraXml) {
                val root = CircXmlParser(xml).parse()
                if (root.name != "mappings") continue
                for (tool in root.children) {
                    val gesture = gesture(tool) ?: continue
                    val name = tool.attributes["name"] ?: continue
                    val library = project.libraries[tool.attributes["lib"]]
                    val edit = ProjectMouseEditTool.entries.firstOrNull { it.fileName == name && library == "#Base" }
                    val action = when {
                        edit == ProjectMouseEditTool.TEXT ->
                            CircCodec.projectToolPrototype(tool, "Text", project.libraries, project, registry)?.let(ProjectMouseAction::Text)
                        edit != null -> ProjectMouseAction.Edit(edit)
                        else -> CircCodec.projectToolPrototype(tool, name, project.libraries, project, registry)
                            ?.let(ProjectMouseAction::Place)
                    }
                    put(gesture, ProjectMouseBinding(name, action))
                }
            }
        }

    /** Edit one gesture. Other rows, unknown children, and comments remain in the project. */
    fun set(project: CircuitProject, gesture: ProjectMouseGesture, tool: ProjectMouseTool?): CircuitProject {
        val roots = project.extraXml.map { CircXmlParser(it).parse() }
        val current = roots.asReversed().firstNotNullOfOrNull { root ->
            if (root.name == "mappings") root.children.lastOrNull { gesture(it) == gesture } else null
        }
        if (tool == null && current == null) return project
        if (tool != null && current != null && current.attributes["name"] == tool.name &&
            ((tool.library == null && current.attributes["lib"].isNullOrEmpty()) ||
                (tool.library != null && project.libraries[current.attributes["lib"]] == tool.library))) return project
        val libraries = project.libraries.toMutableMap()
        val row = tool?.let {
            projectToolXml(it.name, it.library, libraries, extraAttributes = mapOf("map" to gesture.fileValue()))
        }
        var inserted = false
        val extraXml = project.extraXml.zip(roots).map { (xml, root) ->
            if (root.name != "mappings") return@map xml
            var updated = root.rawXml
            for (child in root.children.asReversed().filter { gesture(it) == gesture }) {
                val start = child.sourceOffset - root.sourceOffset
                updated = updated.removeRange(start, start + child.rawXml.length)
            }
            if (row != null && !inserted) {
                inserted = true
                val end = updated.lastIndexOf("</mappings")
                updated = if (end >= 0) updated.substring(0, end) + "\n    " + row + "\n  " + updated.substring(end)
                    else updated.substring(0, updated.lastIndexOf("/>")) + ">\n    " + row + "\n  </mappings>"
            }
            xml.replaceRange(root.sourceOffset, root.sourceOffset + root.rawXml.length, updated)
        }
        return project.copy(
            libraries = libraries,
            extraXml = if (row != null && !inserted) extraXml + "<mappings>\n    $row\n  </mappings>" else extraXml,
        )
    }

    fun withToolAttributes(project: CircuitProject, gesture: ProjectMouseGesture, updates: Map<String, String>): CircuitProject {
        val roots = project.extraXml.map { CircXmlParser(it).parse() }
        val index = roots.indexOfLast { root -> root.name == "mappings" && root.children.any { gesture(it) == gesture } }
        if (index < 0) return project
        val tool = roots[index].children.last { gesture(it) == gesture }
        val metadata = project.extraXml[index]
        val updated = metadata.replaceRange(tool.sourceOffset, tool.sourceOffset + tool.rawXml.length,
            updateProjectToolAttributes(tool, updates))
        if (updated == metadata) return project
        return project.copy(extraXml = project.extraXml.toMutableList().apply { this[index] = updated })
    }

    private fun gesture(tool: CircXmlElement): ProjectMouseGesture? =
        if (tool.name == "tool") tool.attributes["map"]?.let(ProjectMouseGesture::parse) else null
}
