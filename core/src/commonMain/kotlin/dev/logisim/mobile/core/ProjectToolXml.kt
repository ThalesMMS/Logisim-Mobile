package dev.logisim.mobile.core

internal fun projectToolXml(
    name: String,
    library: String?,
    libraries: MutableMap<String, String>,
    attributes: Map<String, String> = emptyMap(),
    extraAttributes: Map<String, String> = emptyMap(),
): String {
    val reference = if (library == null) emptyMap() else {
        val id = libraries.entries.firstOrNull { it.value == library }?.key
            ?: generateSequence(0) { it + 1 }.map(Int::toString).first { it !in libraries }
                .also { libraries[it] = library }
        mapOf("lib" to id)
    }
    return CircXmlRewrite.element("tool", reference + ("name" to name) + extraAttributes, "",
        attributes.map { (key, value) -> toolAttributeXml(key, value) })
}

internal fun updateProjectToolAttributes(tool: CircXmlElement, updates: Map<String, String>): String {
    if (updates.isEmpty()) return tool.rawXml
    val pending = updates.toMutableMap()
    var xml = tool.rawXml
    for (attribute in tool.children.asReversed()) {
        if (attribute.name != "a") continue
        val key = attribute.attributes["name"] ?: continue
        val value = pending.remove(key) ?: continue
        if (attribute.children.isNotEmpty() || attribute.attributes.keys.any { it !in setOf("name", "val") }) continue
        val start = attribute.sourceOffset - tool.sourceOffset
        xml = xml.replaceRange(start, start + attribute.rawXml.length, toolAttributeXml(key, value))
    }
    if (pending.isEmpty()) return xml
    val children = pending.entries.joinToString("\n") { (key, value) -> toolAttributeXml(key, value) }
    val closing = xml.lastIndexOf("</tool")
    if (closing >= 0) return xml.replaceRange(closing, closing, "\n$children\n")
    val selfClosing = xml.lastIndexOf("/>")
    return xml.replaceRange(selfClosing, selfClosing + 2, ">\n$children\n</tool>")
}

private fun toolAttributeXml(name: String, value: String): String =
    CircXmlRewrite.element("a", mapOf("name" to name, "val" to value), "", emptyList())
