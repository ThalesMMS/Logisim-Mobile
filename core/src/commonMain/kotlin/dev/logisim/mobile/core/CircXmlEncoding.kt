package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/** Decodes a .circ file into text whose declaration is safe to save as UTF-8. */
object CircXmlEncoding {
    private val declaration = Regex(
        """^<\?xml[ \t\r\n]+version[ \t\r\n]*=[ \t\r\n]*(['"])1\.0\1[ \t\r\n]+encoding[ \t\r\n]*=[ \t\r\n]*(['"])([A-Za-z][A-Za-z0-9._-]*)\2""",
    )

    // Windows-1252 differs from Latin-1 only in this range. NUL marks undefined bytes.
    private const val WINDOWS_1252_C1 =
        "€\u0000‚ƒ„…†‡" + "ˆ‰Š‹Œ\u0000Ž\u0000" + "\u0000‘’“”•–—" + "˜™š›œ\u0000žŸ"

    fun decode(bytes: ByteArray): String {
        val utf16 = when {
            bytes.startsWith(0x00, 0x00, 0xfe, 0xff) || bytes.startsWith(0xff, 0xfe, 0x00, 0x00) ||
                bytes.startsWith(0x00, 0x00, 0x00, 0x3c) || bytes.startsWith(0x3c, 0x00, 0x00, 0x00) ->
                invalid("UTF-32")
            bytes.startsWith(0xfe, 0xff) -> Utf16(littleEndian = false, bomLength = 2)
            bytes.startsWith(0xff, 0xfe) -> Utf16(littleEndian = true, bomLength = 2)
            bytes.startsWith(0x00, 0x3c) -> Utf16(littleEndian = false, bomLength = 0)
            bytes.startsWith(0x3c, 0x00) -> Utf16(littleEndian = true, bomLength = 0)
            else -> null
        }
        if (utf16 != null) {
            val xml = utf16.decode(bytes)
            val encoding = declaredEncoding(xml)
            val name = encoding?.value?.uppercase()
            if (name != utf16.name && !(utf16.bomLength > 0 && (name == null || name == "UTF-16"))) {
                invalid(name ?: utf16.name)
            }
            return normalize(xml, encoding)
        }

        val start = if (bytes.startsWith(0xef, 0xbb, 0xbf)) 3 else 0
        val encoding = declaredEncoding(asciiDeclaration(bytes, start))?.value?.uppercase() ?: "UTF-8"
        if (start != 0 && encoding != "UTF-8") invalid(encoding)
        val xml = when (encoding) {
            "UTF-8" -> try {
                bytes.decodeToString(startIndex = start, throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                invalid(encoding)
            }
            "US-ASCII" -> {
                if (bytes.any { it < 0 }) invalid(encoding)
                bytes.decodeToString()
            }
            "ISO-8859-1" -> buildString(bytes.size) {
                for (byte in bytes) append((byte.toInt() and 0xff).toChar())
            }
            "WINDOWS-1252" -> buildString(bytes.size) {
                for (byte in bytes) {
                    val code = byte.toInt() and 0xff
                    val char = if (code in 0x80..0x9f) WINDOWS_1252_C1[code - 0x80] else code.toChar()
                    if (code in 0x80..0x9f && char == '\u0000') invalid(encoding)
                    append(char)
                }
            }
            else -> invalid(encoding)
        }
        return normalize(xml, declaredEncoding(xml))
    }

    private fun asciiDeclaration(bytes: ByteArray, start: Int): String {
        if (bytes.size - start < 5 || bytes.decodeToString(start, start + 5) != "<?xml") return ""
        var end = start + 5
        while (end < bytes.lastIndex && bytes[end] >= 0) {
            if (bytes[end] == '?'.code.toByte() && bytes[end + 1] == '>'.code.toByte()) {
                return bytes.decodeToString(start, end + 2)
            }
            end++
        }
        return ""
    }

    private fun declaredEncoding(xml: String): MatchGroup? = declaration.find(xml)?.groups?.get(3)

    private fun normalize(xml: String, encoding: MatchGroup?): String =
        if (encoding == null || encoding.value == "UTF-8") xml else xml.replaceRange(encoding.range, "UTF-8")

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }

    private fun invalid(encoding: String): Nothing =
        throw LocalizedArgumentException(message(Messages.CodecXmlEncodingInvalid, "encoding" to encoding))

    private class Utf16(val littleEndian: Boolean, val bomLength: Int) {
        val name = if (littleEndian) "UTF-16LE" else "UTF-16BE"

        fun decode(bytes: ByteArray): String = buildString((bytes.size - bomLength) / 2) {
            if (bytes.size % 2 != 0) invalid(name)
            var offset = bomLength
            var highSurrogate = false
            while (offset < bytes.size) {
                val first = bytes[offset].toInt() and 0xff
                val second = bytes[offset + 1].toInt() and 0xff
                val codeUnit = if (littleEndian) first or (second shl 8) else (first shl 8) or second
                if ((codeUnit in 0xdc00..0xdfff) != highSurrogate) invalid(name)
                highSurrogate = codeUnit in 0xd800..0xdbff
                append(codeUnit.toChar())
                offset += 2
            }
            if (highSurrogate) invalid(name)
        }
    }
}
