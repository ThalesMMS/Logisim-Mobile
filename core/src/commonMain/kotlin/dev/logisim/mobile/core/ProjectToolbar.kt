package dev.logisim.mobile.core

sealed interface ProjectToolbarItem {
    data object Separator : ProjectToolbarItem
    data class Tool(val library: String?, override val name: String, val attributes: Map<String, String> = emptyMap()) : ProjectToolbarItem
    data class Imported internal constructor(internal val element: CircXmlElement) : ProjectToolbarItem

    val isSeparator: Boolean get() = this == Separator || this is Imported && element.name == "sep"
    val name: String? get() = when (this) {
        Separator -> null
        is Tool -> name
        is Imported -> element.attributes["name"] ?: element.name
    }
}

sealed interface ProjectToolbarBinding {
    enum class Mode : ProjectToolbarBinding { INTERACT, EDIT, SELECT, WIRE }
    data class Place(val component: CircuitComponent) : ProjectToolbarBinding
    data class Text(val prototype: CircuitComponent) : ProjectToolbarBinding
}

/** Reads the .circ toolbar without treating unavailable tools as circuit import errors. */
object ProjectToolbar {
    fun read(project: CircuitProject): List<ProjectToolbarItem> =
        find(project)?.second?.children?.map { ProjectToolbarItem.Imported(it) }.orEmpty()

    fun binding(item: ProjectToolbarItem, project: CircuitProject, registry: ComponentRegistry): ProjectToolbarBinding? {
        if (item.isSeparator) return null
        val libraries = project.libraries.toMutableMap()
        val node = when (item) {
            ProjectToolbarItem.Separator -> return null
            is ProjectToolbarItem.Tool -> CircXmlParser(writeItem(item, libraries)).parse()
            is ProjectToolbarItem.Imported -> item.element.takeIf { it.name == "tool" } ?: return null
        }
        val name = node.attributes["name"] ?: return null
        val library = libraries[node.attributes["lib"]]
        if (library == "#Base") {
            when (name) {
                "Poke Tool" -> return ProjectToolbarBinding.Mode.INTERACT
                "Edit Tool" -> return ProjectToolbarBinding.Mode.EDIT
                "Select Tool" -> return ProjectToolbarBinding.Mode.SELECT
                "Wiring Tool" -> return ProjectToolbarBinding.Mode.WIRE
            }
        }
        val textTool = library == "#Base" && name == "Text Tool"
        val component = CircCodec.projectToolPrototype(node, if (textTool) "Text" else name, libraries, project, registry)
            ?: return null
        return if (textTool) ProjectToolbarBinding.Text(component) else ProjectToolbarBinding.Place(component)
    }

    fun withToolAttributes(item: ProjectToolbarItem, updates: Map<String, String>): ProjectToolbarItem = when (item) {
        ProjectToolbarItem.Separator -> item
        is ProjectToolbarItem.Tool -> item.copy(attributes = item.attributes + updates)
        is ProjectToolbarItem.Imported -> if (item.element.name == "tool") {
            ProjectToolbarItem.Imported(CircXmlParser(updateProjectToolAttributes(item.element, updates)).parse())
        } else item
    }

    /** Replaces direct children in their slots, retaining unknown entries and XML outside those slots. */
    fun apply(project: CircuitProject, items: List<ProjectToolbarItem>): CircuitProject {
        if (items == read(project)) return project
        val libraries = project.libraries.toMutableMap()
        val children = items.map { writeItem(it, libraries) }
        val toolbar = find(project)
        val extraXml = if (toolbar == null) {
            project.extraXml + CircXmlRewrite.element("toolbar", emptyMap(), "\n", children)
        } else {
            val (index, element) = toolbar
            project.extraXml.toMutableList().apply { this[index] = replaceChildren(element, children) }
        }
        return project.copy(extraXml = extraXml, libraries = libraries)
    }

    private fun find(project: CircuitProject): Pair<Int, CircXmlElement>? =
        project.extraXml.withIndex().firstNotNullOfOrNull { (index, xml) ->
            val element = CircXmlParser(xml).parse()
            if (element.name == "toolbar") index to element else null
        }

    private fun writeItem(item: ProjectToolbarItem, libraries: MutableMap<String, String>): String = when (item) {
        ProjectToolbarItem.Separator -> "<sep/>"
        is ProjectToolbarItem.Imported -> item.element.rawXml
        is ProjectToolbarItem.Tool -> projectToolXml(item.name, item.library, libraries, item.attributes)
    }

    private fun replaceChildren(element: CircXmlElement, children: List<String>): String {
        val xml = element.rawXml
        val closing = xml.lastIndexOf("</")
        if (closing < 0) return CircXmlRewrite.element("toolbar", element.attributes, "\n", children)
        return buildString {
            var cursor = 0
            element.children.forEachIndexed { index, child ->
                val start = child.sourceOffset - element.sourceOffset
                append(xml, cursor, start)
                children.getOrNull(index)?.let { append(it) }
                cursor = start + child.rawXml.length
            }
            append(xml, cursor, closing)
            children.drop(element.children.size).forEach { append("\n  ").append(it) }
            if (children.size > element.children.size) append('\n')
            append(xml, closing, xml.length)
        }
    }
}
