package dev.logisim.mobile.core

/**
 * Evolution d58a61e0 `resources/logisim/default.templ`, which File > New starts from without a user template (#324):
 * its libraries in its order, its toolbar and its mouse mappings. The template does not load #HDL-IP; a project adds
 * it from the library manager, as Evolution's Project > Load Library does.
 */
object EvolutionDefaultTemplate {
    val libraries: List<String> = listOf("#Wiring", "#Gates", "#Plexers", "#Arithmetic", "#FPArithmetic", "#Memory",
        "#I/O", "#TTL", "#TCL", "#Base", "#BFH-Praktika", "#Input/Output-Extra", "#Soc")

    /** The template toolbar. Original Logisim projects name the output pin with `output`, as 2.7.1 does. */
    fun toolbar(sourceVersion: String): List<ProjectToolbarItem> {
        val output = if (sourceVersion.startsWith("2.7.")) mapOf("output" to "true") else mapOf("type" to "output")
        return listOf(
            ProjectToolbarItem.Tool("#Base", "Poke Tool"),
            ProjectToolbarItem.Tool("#Base", "Edit Tool"),
            ProjectToolbarItem.Tool("#Base", "Wiring Tool"),
            ProjectToolbarItem.Tool("#Base", "Text Tool"),
            ProjectToolbarItem.Tool("#Base", "Image"),
            ProjectToolbarItem.Separator,
            ProjectToolbarItem.Tool("#Wiring", "Pin"),
            ProjectToolbarItem.Tool("#Wiring", "Pin", mapOf("facing" to "west") + output),
            ProjectToolbarItem.Separator,
        ) + listOf("NOT Gate", "AND Gate", "OR Gate", "XOR Gate", "NAND Gate", "NOR Gate").map { ProjectToolbarItem.Tool("#Gates", it) } +
            ProjectToolbarItem.Separator +
            listOf("D Flip-Flop", "Register").map { ProjectToolbarItem.Tool("#Memory", it) }
    }

    /** The template mouse mappings: middle button pokes, right button and Ctrl+click open the menu. */
    val mappings: List<Pair<String, ProjectMouseTool>> = listOf(
        "Button2" to ProjectMouseEditTool.POKE.reference,
        "Button3" to ProjectMouseEditTool.MENU.reference,
        "Ctrl Button1" to ProjectMouseEditTool.MENU.reference,
    )

    /** [project] with the template's libraries (those [registry] provides), toolbar and mouse mappings. */
    fun apply(project: CircuitProject, registry: ComponentRegistry): CircuitProject {
        val available = ProjectBuiltinLibraries.available(registry).toSet()
        val ids = libraries.filter { it in available }.withIndex().associate { (index, library) -> library to index.toString() }
        fun tool(library: String, name: String, extra: Map<String, String> = emptyMap(), attributes: Map<String, String> = emptyMap()): String? {
            val id = ids[library] ?: return null
            val opening = "<tool lib=\"$id\" name=\"${escape(name)}\"" + extra.entries.joinToString("") { " ${it.key}=\"${escape(it.value)}\"" }
            return if (attributes.isEmpty()) "$opening/>" else "$opening>" +
                attributes.entries.joinToString("") { "\n      <a name=\"${escape(it.key)}\" val=\"${escape(it.value)}\"/>" } + "\n    </tool>"
        }
        val toolbar = toolbar(project.sourceVersion).mapNotNull { item ->
            when (item) {
                is ProjectToolbarItem.Tool -> tool(requireNotNull(item.library), item.name, attributes = item.attributes)
                else -> "<sep/>"
            }
        }
        val mappings = mappings.mapNotNull { (gesture, target) -> tool(requireNotNull(target.library), target.name, mapOf("map" to gesture)) }
        return project.copy(
            libraries = ids.entries.associate { (library, id) -> id to library },
            extraXml = project.extraXml + listOf(
                "<mappings>" + mappings.joinToString("") { "\n    $it" } + "\n  </mappings>",
                "<toolbar>" + toolbar.joinToString("") { "\n    $it" } + "\n  </toolbar>",
            ),
        )
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
