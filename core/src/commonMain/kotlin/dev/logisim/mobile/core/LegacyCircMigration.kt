package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Normalizes the pre-2.6.3 document structure before the ordinary component reader runs. */
internal object LegacyCircMigration {
    const val targetSource = "2.7.1"

    private val baseTools = setOf("Poke Tool", "Edit Tool", "Select Tool", "Wiring Tool", "Text Tool", "Menu Tool", "Text")

    fun notice(profile: CircFormatProfile, circuit: String) = CircMigration("legacy-project-converted", circuit, null,
        message(Messages.CodecLegacyProjectConverted, "source" to profile.source, "target" to targetSource))

    fun repair(root: CircXmlElement, profile: CircFormatProfile): CircXmlElement {
        val libraries = root.children.filter { it.name == "lib" }
        val libraryNames = mutableSetOf<String>()
        for (library in libraries) {
            val name = library.attributes["name"]
            requireLocalized(name != null) { message(Messages.CodecLibraryIdentityRequired) }
            requireLocalized(libraryNames.add(name)) { message(Messages.CodecLibraryDuplicate, "id" to name) }
        }
        val obsolete = libraries.filter { it.attributes["desc"] == "#Legacy" }
        val obsoleteIds = obsolete.mapNotNull { it.attributes["name"] }.toSet()
        val hasObsoleteComponent = root.children.filter { it.name == "circuit" }.any { circuit ->
            circuit.children.any { it.name == "comp" && it.attributes["lib"] in obsoleteIds }
        }
        // The desktop drops these components. Refuse a lossy conversion instead.
        requireLocalized(!hasObsoleteComponent) { message(Messages.CodecLegacyLibraryUnsupported) }

        val splitLibraries = libraries.none { it.attributes["desc"] == "#Wiring" }
        val oldBase = libraries.lastOrNull { it.attributes["desc"] == "#Base" && "name" in it.attributes }
        val oldGates = libraries.lastOrNull { it.attributes["desc"] == "#Gates" && "name" in it.attributes }
        var nextId = 0
        fun allocateId(): String {
            while (!libraryNames.add(nextId.toString())) nextId++
            return (nextId++).toString()
        }
        val wiringId = if (splitLibraries) oldBase?.attributes?.get("name") ?: allocateId() else null
        val baseId = if (splitLibraries && oldBase != null) allocateId() else null

        fun libraryFor(library: String?, name: String?): String? = when {
            !splitLibraries -> library
            oldBase != null && library == oldBase.attributes["name"] && name in baseTools -> baseId
            oldGates != null && library == oldGates.attributes["name"] && name == "Constant" -> wiringId
            else -> library
        }

        fun rewriteReference(node: CircXmlElement, name: String? = node.attributes["name"]): String {
            val attrs = node.attributes.toMutableMap()
            if (name != null) attrs["name"] = name
            libraryFor(attrs["lib"], name)?.let { attrs["lib"] = it }
            return rewrite(node, attrs)
        }

        fun rewriteCircuit(node: CircXmlElement): String {
            val labels = node.children.filter { it.name == "a" }.mapNotNull { it.attributes["name"] }.toSet()
            val children = node.children.map { child ->
                val name = child.attributes["name"]
                when {
                    child.name == "comp" -> rewriteReference(child)
                    child.name == "a" && name != null && name.startsWith("label") && "c$name" !in labels ->
                        rewrite(child, child.attributes + ("name" to "c$name"))
                    else -> child.rawXml
                }
            }
            return rewrite(node, children = children)
        }

        fun rewriteToolbar(node: CircXmlElement): String {
            val children = node.children.filterNot { it.name == "tool" && it.attributes["lib"] in obsoleteIds }
            val tools = children.filter { it.name == "tool" }
            val select = tools.lastOrNull { it.attributes["name"] == "Select Tool" }
            val wiring = tools.lastOrNull { it.attributes["name"] == "Wiring Tool" }
            val merge = node.name == "toolbar" && profile.before(2, 3, 0) && select != null && wiring != null &&
                tools.none { it.attributes["name"] == "Edit Tool" }
            return rewrite(node, children = children.mapNotNull { tool ->
                when {
                    tool.name != "tool" -> tool.rawXml
                    merge && tool === wiring -> null
                    merge && tool === select -> rewriteReference(tool, "Edit Tool")
                    else -> rewriteReference(tool)
                }
            })
        }

        fun isTool(node: CircXmlElement, names: Set<String>) = node.name == "tool" && node.attributes["name"] in names
        val movedFromBase = oldBase?.children.orEmpty().filter { isTool(it, baseTools) }
        val movedFromGates = oldGates?.children.orEmpty().filter { it.name == "tool" && it.attributes["name"] == "Constant" }
        val children = root.children.mapNotNull { node ->
            when {
                node in obsolete -> null
                splitLibraries && node === oldBase -> rewrite(node, node.attributes + ("desc" to "#Wiring"),
                    (node.children.filterNot { it in movedFromBase } + movedFromGates).map { it.rawXml })
                splitLibraries && node === oldGates -> rewrite(node,
                    children = node.children.filterNot { it in movedFromGates }.map { it.rawXml })
                node.name == "circuit" -> rewriteCircuit(node)
                node.name == "toolbar" || node.name == "mappings" -> rewriteToolbar(node)
                else -> node.rawXml
            }
        }.toMutableList()
        if (splitLibraries && oldBase == null) children += CircXmlRewrite.element("lib",
            mapOf("name" to requireNotNull(wiringId), "desc" to "#Wiring"), "", movedFromGates.map { it.rawXml })
        if (baseId != null) children += CircXmlRewrite.element("lib",
            mapOf("name" to baseId, "desc" to "#Base"), "", movedFromBase.map { it.rawXml })
        return CircXmlParser(rewrite(root, children = children)).parse()
    }

    private fun rewrite(
        node: CircXmlElement,
        attributes: Map<String, String> = node.attributes,
        children: List<String> = node.children.map { it.rawXml },
    ): String = if (attributes == node.attributes && children == node.children.map { it.rawXml }) node.rawXml
        else CircXmlRewrite.element(node.name, attributes, node.text, children)
}
