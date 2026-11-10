package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

internal data class CircXmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<CircXmlElement>,
    val text: String = "",
    val rawXml: String = "",
    val sourceOffset: Int = 0,
)

internal fun CircXmlElement.requireTextBeforeChildren() {
    for ((index, child) in children.withIndex()) {
        val start = child.sourceOffset - sourceOffset + child.rawXml.length
        val end = children.getOrNull(index + 1)?.let { it.sourceOffset - sourceOffset }
            ?: rawXml.lastIndexOf("</")
        val between = rawXml.substring(start, end)
        requireLocalized(CircXmlParser("<text>$between</text>").parse().text.isBlank()) {
            message(Messages.CodecXmlBetweenElements)
        }
    }
}

/** Small bounded XML reader for the .circ subset, without DTD or external resources. */
internal class CircXmlParser(private val source: String) {
    private var offset = 0
    private var nodes = 0

    fun parse(): CircXmlElement {
        requireLocalized(source.length <= 2_000_000) { message(Messages.ErrorXmlSize) }
        if (source.startsWith('\uFEFF')) offset++
        skipMisc(allowDeclaration = true)
        val root = element(0)
        skipMisc(allowDeclaration = false)
        requireLocalized(offset == source.length) { message(Messages.ErrorXmlTrailing) }
        return root
    }

    private fun element(depth: Int): CircXmlElement {
        requireLocalized(depth < 32 && ++nodes <= 12_000) { message(Messages.ErrorXmlStructure) }
        val start = offset
        expect("<")
        val name = name()
        val attributes = mutableMapOf<String, String>()
        while (true) {
            val hadSpace = whitespace()
            if (take("/>")) return CircXmlElement(name, attributes, emptyList(), rawXml = source.substring(start, offset), sourceOffset = start)
            if (take(">")) break
            requireLocalized(hadSpace && attributes.size < 64) { message(Messages.ErrorXmlAttributes) }
            val key = name()
            whitespace()
            expect("=")
            whitespace()
            requireLocalized(offset < source.length && source[offset] in "\"'") { message(Messages.ErrorXmlQuotes) }
            val quote = source[offset++]
            val begin = offset
            while (offset < source.length && source[offset] != quote) {
                requireLocalized(source[offset] != '<') { message(Messages.ErrorXmlAttributeCharacter) }
                offset++
            }
            requireLocalized(offset < source.length) { message(Messages.ErrorXmlUnterminatedAttribute) }
            val value = decode(source.substring(begin, offset++))
            requireLocalized(attributes.put(key, value) == null) { message(Messages.ErrorXmlDuplicateAttribute, "name" to key) }
        }
        val children = mutableListOf<CircXmlElement>()
        val body = StringBuilder()
        while (true) {
            requireLocalized(offset < source.length) { message(Messages.CodecXmlElementUnterminated, "name" to name) }
            when {
                take("</") -> {
                    val end = name()
                    whitespace()
                    expect(">")
                    requireLocalized(end == name) { message(Messages.CodecXmlClosingInvalid, "end" to end, "name" to name) }
                    return CircXmlElement(name, attributes, children, body.toString(), source.substring(start, offset), start)
                }
                source.startsWith("<!--", offset) -> comment()
                take("<![CDATA[") -> {
                    val end = source.indexOf("]]>", offset)
                    requireLocalized(end >= 0) { message(Messages.CodecXmlCdataUnterminated) }
                    for (index in offset until end) requireTextCharacter(source[index])
                    body.append(source, offset, end)
                    offset = end + 3
                }
                source.startsWith("<!", offset) -> throw LocalizedArgumentException(message(Messages.CodecXmlEntitiesUnsupported))
                source.startsWith("<?", offset) -> throw LocalizedArgumentException(message(Messages.CodecXmlInstructionUnsupported))
                source[offset] == '<' -> children += element(depth + 1)
                else -> {
                    val begin = offset
                    while (offset < source.length && source[offset] != '<') offset++
                    val text = decode(source.substring(begin, offset))
                    body.append(text)
                }
            }
        }
    }

    private fun skipMisc(allowDeclaration: Boolean) {
        var declarationAllowed = allowDeclaration
        while (true) {
            whitespace()
            when {
                source.startsWith("<!--", offset) -> comment()
                source.startsWith("<?xml", offset) -> {
                    requireLocalized(declarationAllowed) { message(Messages.CodecXmlDeclarationPosition) }
                    val end = source.indexOf("?>", offset + 6)
                    requireLocalized(end >= 0) { message(Messages.CodecXmlDeclarationUnterminated) }
                    val declaration = source.substring(offset, end + 2)
                    requireLocalized(Regex("""<\?xml\s+version\s*=\s*(['"])1\.0\1(?:\s+encoding\s*=\s*(['"])[A-Za-z][A-Za-z0-9._-]*\2)?(?:\s+standalone\s*=\s*(['"])(?:yes|no)\3)?\s*\?>""").matches(declaration)) {
                        message(Messages.CodecXmlDeclarationInvalid)
                    }
                    offset = end + 2
                    declarationAllowed = false
                }
                else -> return
            }
        }
    }

    private fun comment() {
        expect("<!--")
        val end = source.indexOf("-->", offset)
        requireLocalized(end >= 0 && "--" !in source.substring(offset, end)) { message(Messages.CodecXmlCommentInvalid) }
        offset = end + 3
    }

    private fun name(): String {
        val begin = offset
        requireLocalized(offset < source.length && (source[offset].isLetter() || source[offset] == '_')) { message(Messages.CodecXmlNameInvalid) }
        offset++
        while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_-.:")) offset++
        return source.substring(begin, offset)
    }

    private fun decode(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char != '&') {
                requireTextCharacter(char)
                append(char)
                continue
            }
            val end = text.indexOf(';', index)
            requireLocalized(end in index..(index + 16).coerceAtMost(text.lastIndex)) { message(Messages.CodecXmlEntityInvalid) }
            val entity = text.substring(index, end)
            when (entity) {
                "amp" -> append('&')
                "lt" -> append('<')
                "gt" -> append('>')
                "quot" -> append('"')
                "apos" -> append('\'')
                else -> {
                    val number = when {
                        entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                        entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    requireLocalized(number != null && (number == 9 || number == 10 || number == 13 || number in 32..0xD7FF || number in 0xE000..0xFFFD || number in 0x10000..0x10FFFF)) {
                        message(Messages.CodecXmlEntityUnsupported, "entity" to entity)
                    }
                    if (number <= 0xFFFF) append(number.toChar()) else {
                        append(((number - 0x10000) / 1024 + 0xD800).toChar())
                        append(((number - 0x10000) % 1024 + 0xDC00).toChar())
                    }
                }
            }
            index = end + 1
        }
    }

    private fun requireTextCharacter(char: Char) {
        requireLocalized(char.code >= 32 || char in "\r\n\t") { message(Messages.CodecXmlCharacterInvalid) }
    }

    private fun whitespace(): Boolean {
        val begin = offset
        while (offset < source.length && source[offset] in " \r\n\t") offset++
        return offset > begin
    }

    private fun take(token: String): Boolean {
        if (!source.startsWith(token, offset)) return false
        offset += token.length
        return true
    }

    private fun expect(token: String) {
        requireLocalized(take(token)) { message(Messages.CodecXmlExpectedToken, "offset" to offset, "token" to token) }
    }
}
