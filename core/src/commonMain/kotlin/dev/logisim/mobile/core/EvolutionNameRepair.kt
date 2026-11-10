package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message

/**
 * `XmlReader.ensureLogisimCompatibility` (Evolution d58a61e0), applied when a file is opened (#321): circuit names,
 * circuit `label` attributes and the labels of library components that Evolution would rename get the same new text,
 * with a suffix derived from the old text instead of Evolution's random one. Unlike Evolution, `<main>` follows the
 * renamed circuit and a circuit without an `id` keeps its old name as its Mobile identity.
 */
internal object EvolutionNameRepair {
    fun repair(root: CircXmlElement, migrations: MutableList<CircMigration>): CircXmlElement {
        val circuits = root.children.filter { it.name == "circuit" }
        val names = circuits.mapNotNull { it.attributes["name"] }
        val renamed = names.filter(EvolutionNames::renamedOnLoad).associateWith { EvolutionNames.validLabel(it) }
        fun labelOf(node: CircXmlElement) = node.takeIf { it.name == "a" && it.attributes["name"] == "label" }?.attributes?.get("val")
        val labels = circuits.flatMap { circuit ->
            circuit.children.mapNotNull(::labelOf) + circuit.children
                .filter { it.name == "comp" && "lib" in it.attributes }.flatMap { it.children.mapNotNull(::labelOf) }
        }.filter { it.isNotEmpty() && EvolutionNames.renamedOnLoad(it) }.distinct().associateWith { EvolutionNames.validLabel(it) }
        if (renamed.isEmpty() && labels.isEmpty()) return root

        val reported = mutableSetOf<Pair<String, String>>()
        fun relabel(node: CircXmlElement, circuit: String): String {
            val old = labelOf(node)
            val new = old?.let(labels::get) ?: return node.rawXml
            if (reported.add(circuit to old)) {
                migrations += CircMigration("evolution-label-renamed", circuit, null,
                    message("evolution_name_renamed_on_load", "old" to old, "new" to new))
            }
            return CircXmlRewrite.attributes(node.rawXml, node.attributes + ("val" to new))
        }
        fun renameAttribute(node: CircXmlElement, attribute: String): String =
            renamed[node.attributes[attribute]]?.let { CircXmlRewrite.attributes(node.rawXml, node.attributes + (attribute to it)) }
                ?: node.rawXml
        fun rewriteCircuit(node: CircXmlElement): String {
            val old = node.attributes["name"].orEmpty()
            val name = renamed[old] ?: old
            if (name != old) migrations += CircMigration("evolution-circuit-renamed", name, null,
                message("evolution_name_renamed_on_load", "old" to old, "new" to name))
            val body = replaceChildren(node) { child ->
                when {
                    child.name == "a" && child.attributes["name"] == "circuit" -> renameAttribute(child, "val")
                    child.name == "a" -> relabel(child, name)
                    child.name == "comp" && "lib" !in child.attributes -> renameAttribute(child, "name")
                    child.name == "comp" -> replaceChildren(child) { relabel(it, name) }
                    else -> child.rawXml
                }
            }
            return if (name == old) body
                else CircXmlRewrite.attributes(body, node.attributes + ("name" to name) + ("id" to (node.attributes["id"] ?: old)))
        }
        val xml = replaceChildren(root) { node ->
            when (node.name) {
                "main" -> renameAttribute(node, "name")
                "circuit" -> rewriteCircuit(node)
                else -> node.rawXml
            }
        }
        return CircXmlParser(xml).parse()
    }

    /** [element]'s XML with each child replaced by [transform], keeping everything between the children as it was. */
    private fun replaceChildren(element: CircXmlElement, transform: (CircXmlElement) -> String): String {
        var xml = element.rawXml
        for (child in element.children.asReversed()) {
            val replacement = transform(child)
            if (replacement == child.rawXml) continue
            val start = child.sourceOffset - element.sourceOffset
            xml = xml.replaceRange(start, start + child.rawXml.length, replacement)
        }
        return xml
    }
}
