package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** The BoardPicture encoding used by Evolution, including the legacy RGB form. */
class FpgaBoardPicture private constructor(
    val width: Int,
    val height: Int,
    private val table: List<String>,
    private val stream: String,
) {
    fun imageBytes(): ByteArray {
        val lookup = table.withIndex().associate { it.value to it.index }
        val compressed = stream.startsWith('@')
        var offset = if (compressed) 1 else 0
        fun next(): Int {
            requirePicture(offset < stream.length)
            val end = offset + if (stream[offset] in "+-=") 2 else 1
            requirePicture(end <= stream.length)
            val token = stream.substring(offset, end)
            offset = end
            return lookup[token] ?: throw LocalizedArgumentException(message("fpga_board_picture_invalid"))
        }
        if (compressed) {
            val bytes = ByteArray(stream.length - 1)
            var count = 0
            while (offset < stream.length) bytes[count++] = (next() - 128).toByte()
            val image = bytes.copyOf(count)
            validateEncodedImage(image)
            return image
        }
        val pixels = IntArray(width * height) { -0x1000000 or (next() shl 16) or (next() shl 8) or next() }
        requirePicture(offset == stream.length)
        return VisualPng.encode(width, height, pixels)
    }

    internal fun appendXml(xml: StringBuilder) {
        xml.append("<BoardPicture>\n")
        xml.fpgaBoardXmlElement("PictureDimension", mapOf("Width" to width.toString(), "Height" to height.toString()))
        xml.fpgaBoardXmlElement("CompressionCodeTable", mapOf("TableData" to table.joinToString(" ")))
        xml.fpgaBoardXmlElement("PixelData", mapOf("PixelRGB" to stream))
        xml.append("</BoardPicture>\n")
    }

    companion object {
        const val MAPPING_WIDTH = 740
        const val MAPPING_HEIGHT = 400
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()"
        private const val MAX_STREAM_LENGTH = 1_900_000
        private val codes = listOf("", "+", "-", "=").flatMap { prefix -> ALPHABET.map { prefix + it } }

        fun read(xml: String): FpgaBoardPicture? {
            val picture = CircXmlParser(xml).parse().children.singleOrNull { it.name == "BoardPicture" } ?: return null
            fun attribute(element: String, name: String) = picture.children.singleOrNull { it.name == element }?.attributes?.get(name)
            val width = attribute("PictureDimension", "Width")?.toIntOrNull() ?: 0
            val height = attribute("PictureDimension", "Height")?.toIntOrNull() ?: 0
            val rawTable = attribute("CompressionCodeTable", "TableData").orEmpty()
            requirePicture(rawTable.length <= 4096)
            val table = rawTable.trim().split(Regex("\\s+"))
            val stream = attribute("PixelData", "PixelRGB").orEmpty()
            requirePicture(width in 1..4096 && height in 1..4096 && width * height <= 2_000_000)
            requirePicture(table.size == codes.size && table.toSet() == codes.toSet() && stream.length in 1..MAX_STREAM_LENGTH)
            return FpgaBoardPicture(width, height, table, stream)
        }

        fun fromEncodedImage(width: Int, height: Int, bytes: ByteArray): FpgaBoardPicture {
            requirePicture(width in 1..4096 && height in 1..4096 && width * height <= 2_000_000)
            requirePicture(bytes.isNotEmpty() && bytes.size < MAX_STREAM_LENGTH / 2)
            val image = validateEncodedImage(bytes)
            requirePicture(width == image.width && height == image.height)
            val counts = IntArray(256)
            bytes.forEach { counts[it.toInt() + 128]++ }
            val table = MutableList(256) { "" }
            counts.indices.sortedByDescending { counts[it] }.forEachIndexed { rank, value -> table[value] = codes[rank] }
            val stream = buildString {
                append('@')
                bytes.forEach { append(table[it.toInt() + 128]) }
            }
            return FpgaBoardPicture(width, height, table, stream)
        }

        private fun requirePicture(condition: Boolean) =
            requireLocalized(condition) { message("fpga_board_picture_invalid") }

        private fun validateEncodedImage(bytes: ByteArray): EmbeddedImage {
            val mime = when (bytes.firstOrNull()?.toInt()?.and(255)) {
                255 -> "image/jpeg"
                137 -> "image/png"
                else -> throw LocalizedArgumentException(message("fpga_board_picture_invalid"))
            }
            val image = EmbeddedImage.fromBytes(bytes, mime)
            requirePicture(image.width * image.height <= 2_000_000)
            return image
        }
    }
}
