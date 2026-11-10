package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class MemoryImageFormat(val header: String?, val littleEndian: Boolean = false) {
    RAW("v2.0 raw"),
    HEX_WORDS_PLAIN("v3.0 hex words plain"),
    HEX_WORDS_ADDRESSED("v3.0 hex words addressed"),
    HEX_BYTES_PLAIN_BIG("v3.0 hex bytes plain big-endian"),
    HEX_BYTES_PLAIN_LITTLE("v3.0 hex bytes plain little-endian", true),
    HEX_BYTES_ADDRESSED_BIG("v3.0 hex bytes addressed big-endian"),
    HEX_BYTES_ADDRESSED_LITTLE("v3.0 hex bytes addressed little-endian", true),
    BINARY_BIG(null),
    BINARY_LITTLE(null, true),
    ASCII_BIG(null),
    ASCII_LITTLE(null, true);

    val description: String get() = localizedDescription().resolve(AppLanguage.ENGLISH)
}

data class MemoryImageLimits(
    val maxInputBytes: Int = 16 * 1024 * 1024,
    val maxNonZeroWords: Int = 1_048_576,
    val maxExportBytes: Int = 32 * 1024 * 1024,
) {
    init { require(maxInputBytes > 0 && maxNonZeroWords > 0 && maxExportBytes > 0) }
}

data class MemoryImageDiagnostic(val line: Int?, val detail: LocalizedText) {
    constructor(line: Int?, message: String) : this(line, LocalizedText.literal(message))
    val notice: LocalizedText get() = if (line == null) detail else message(Messages.MemoryErrorLine, "line" to line, "reason" to detail)
    val message: String get() = detail.resolve(AppLanguage.PORTUGUESE)
    override fun toString(): String = notice.resolve(AppLanguage.PORTUGUESE)
}

sealed interface MemoryImageImportResult {
    data class Success(
        val image: MemoryImage,
        val format: MemoryImageFormat,
        val firstPageAllocated: Boolean = image.hasFirstPageWords(),
    ) : MemoryImageImportResult
    data class Failure(val diagnostics: List<MemoryImageDiagnostic>) : MemoryImageImportResult
}

/** Formats exposed by the desktop HexFile chooser. Invalid imports never return partial data. */
object MemoryImageCodec {
    fun decode(
        bytes: ByteArray,
        addressBits: Int,
        dataBits: Int,
        format: MemoryImageFormat? = null,
        limits: MemoryImageLimits = MemoryImageLimits(),
    ): MemoryImageImportResult = checked {
        val empty = dimensions(addressBits, dataBits)
        if (bytes.size > limits.maxInputBytes) fail(null, message(Messages.MemoryCodecFileLimit, "bytes" to limits.maxInputBytes).copy(quantity = limits.maxInputBytes.toLong()))
        if (format == MemoryImageFormat.BINARY_BIG || format == MemoryImageFormat.BINARY_LITTLE) {
            val writer = PackedWriter(empty, format.littleEndian, limits)
            writer.write(0, bytes, null)
            return@checked writer.result(format)
        }
        if (format == MemoryImageFormat.ASCII_BIG || format == MemoryImageFormat.ASCII_LITTLE) {
            val writer = PackedWriter(empty, format.littleEndian, limits)
            writer.write(0, decodeEscapes(bytes), null)
            return@checked writer.result(format)
        }
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { fail(null, message(Messages.MemoryCodecInvalidUtf8)) }
        val source = text.removePrefix("\uFEFF").lines().mapIndexed { index, value -> SourceLine(index + 1, value) }
        val first = source.indexOfFirst { it.text.substringBefore('#').isNotBlank() }
        if (first < 0) fail(null, message(Messages.MemoryCodecEmpty))
        val firstText = source[first].text.substringBefore('#').trim()
        val hasHeader = firstText.startsWith("v2.", true) || firstText.startsWith("v3.", true)
        val body = if (hasHeader) source.drop(first + 1) else source.drop(first)
        val selected = if (hasHeader) {
            val parsed = parseHeader(firstText, body, source[first].number)
            if (format != null && parsed != format) fail(source[first].number, message(Messages.MemoryCodecHeaderMismatch))
            parsed
        } else format ?: fail(source[first].number, message(Messages.MemoryCodecMissingFormat))
        when (selected) {
            MemoryImageFormat.RAW, MemoryImageFormat.HEX_WORDS_PLAIN,
            MemoryImageFormat.HEX_WORDS_ADDRESSED -> decodeWords(empty, body, selected, limits)
            else -> decodeHexBytes(empty, body, selected, limits)
        }
    }

    fun decodeText(
        text: String,
        addressBits: Int,
        dataBits: Int,
        format: MemoryImageFormat? = null,
        limits: MemoryImageLimits = MemoryImageLimits(),
    ): MemoryImageImportResult = decode(text.encodeToByteArray(), addressBits, dataBits, format, limits)

    fun decodeCirc(text: String, limits: MemoryImageLimits = MemoryImageLimits()): MemoryImageImportResult {
        val normalized = text.replace("\\n", "\n").replace("\\r", "\r")
        val newline = normalized.indexOf('\n')
        val header = if (newline < 0) normalized else normalized.substring(0, newline)
        val match = Regex("^\\s*addr/data:\\s+(\\d+)\\s+(\\d+)\\s*$").matchEntire(header)
            ?: return MemoryImageImportResult.Failure(listOf(MemoryImageDiagnostic(1, message(Messages.MemoryCodecCircHeader))))
        val addressBits = match.groupValues[1].toIntOrNull()
        val dataBits = match.groupValues[2].toIntOrNull()
        if (addressBits == null || dataBits == null) return MemoryImageImportResult.Failure(
            listOf(MemoryImageDiagnostic(1, message(Messages.MemoryCodecDimensions))))
        val body = if (newline < 0) "0" else normalized.substring(newline + 1).ifBlank { "0" }
        return decodeText(body, addressBits, dataBits, MemoryImageFormat.RAW, limits)
    }

    fun encodeCirc(image: MemoryImage, limits: MemoryImageLimits = MemoryImageLimits()): String =
        "addr/data: ${image.addressBits} ${image.dataBits}\n" + rawBody(image, limits)

    fun encode(image: MemoryImage, format: MemoryImageFormat, limits: MemoryImageLimits = MemoryImageLimits()): ByteArray {
        val body = when (format) {
            MemoryImageFormat.RAW -> rawBody(image, limits).encodeToByteArray()
            MemoryImageFormat.HEX_WORDS_PLAIN -> wordsBody(image, false, limits).encodeToByteArray()
            MemoryImageFormat.HEX_WORDS_ADDRESSED -> wordsBody(image, true, limits).encodeToByteArray()
            MemoryImageFormat.BINARY_BIG, MemoryImageFormat.BINARY_LITTLE -> packedBytes(image, format.littleEndian, limits)
            MemoryImageFormat.ASCII_BIG, MemoryImageFormat.ASCII_LITTLE ->
                escapeBytes(packedBytes(image, format.littleEndian, limits), limits).encodeToByteArray()
            else -> {
                val addressed = format == MemoryImageFormat.HEX_BYTES_ADDRESSED_BIG ||
                    format == MemoryImageFormat.HEX_BYTES_ADDRESSED_LITTLE
                hexBytesBody(packedBytes(image, format.littleEndian, limits), addressed, limits).encodeToByteArray()
            }
        }
        val header = format.header?.let { "$it\n".encodeToByteArray() } ?: byteArrayOf()
        requireLocalized(header.size.toLong() + body.size <= limits.maxExportBytes) { message(Messages.MemoryCodecExportLimit, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong()) }
        return header + body
    }

    fun encodeText(image: MemoryImage, format: MemoryImageFormat, limits: MemoryImageLimits = MemoryImageLimits()): String {
        requireLocalized(format != MemoryImageFormat.BINARY_BIG && format != MemoryImageFormat.BINARY_LITTLE) { message(Messages.MemoryCodecBinaryText) }
        return encode(image, format, limits).decodeToString()
    }

    private data class SourceLine(val number: Int, val text: String)
    private class InvalidImage(val diagnostic: MemoryImageDiagnostic) : RuntimeException(diagnostic.message)
    private fun fail(line: Int?, notice: LocalizedText): Nothing = throw InvalidImage(MemoryImageDiagnostic(line, notice))
    private inline fun checked(action: () -> MemoryImageImportResult): MemoryImageImportResult = try { action() }
        catch (error: InvalidImage) { MemoryImageImportResult.Failure(listOf(error.diagnostic)) }

    private fun dimensions(addressBits: Int, dataBits: Int): MemoryImage {
        if (addressBits !in 2..24) fail(null, message(Messages.MemoryErrorAddressBits))
        if (dataBits !in 1..64) fail(null, message(Messages.MemoryErrorDataBits))
        return MemoryImage(addressBits, dataBits)
    }

    private fun parseHeader(header: String, body: List<SourceLine>, line: Int): MemoryImageFormat {
        val tokens = header.lowercase().split(Regex("\\s+"))
        if (tokens == listOf("v2.0", "raw")) return MemoryImageFormat.RAW
        if (tokens.firstOrNull() != "v3.0" || tokens.getOrNull(1) != "hex") fail(line, message(Messages.MemoryCodecHeaderUnsupported))
        val tail = tokens.drop(2)
        val known = setOf("words", "bytes", "plain", "addressed", "big-endian", "little-endian")
        if (tail.any { it !in known } || tail.size != tail.distinct().size) fail(line, message(Messages.MemoryCodecHeaderOption))
        if (("words" in tail) == ("bytes" in tail)) fail(line, message(Messages.MemoryCodecHeaderUnits))
        if ("plain" in tail && "addressed" in tail || "big-endian" in tail && "little-endian" in tail) fail(line, message(Messages.MemoryCodecHeaderConflict))
        if ("words" in tail && tail.any { it.endsWith("-endian") }) fail(line, message(Messages.MemoryCodecHeaderByteOrder))
        val addressed = "addressed" in tail || ("plain" !in tail && body.any { ':' in it.text.substringBefore('#') })
        val little = "little-endian" in tail
        return when {
            "words" in tail -> if (addressed) MemoryImageFormat.HEX_WORDS_ADDRESSED else MemoryImageFormat.HEX_WORDS_PLAIN
            addressed -> if (little) MemoryImageFormat.HEX_BYTES_ADDRESSED_LITTLE else MemoryImageFormat.HEX_BYTES_ADDRESSED_BIG
            else -> if (little) MemoryImageFormat.HEX_BYTES_PLAIN_LITTLE else MemoryImageFormat.HEX_BYTES_PLAIN_BIG
        }
    }

    private fun hex(text: String, line: Int): ULong {
        val value = text.removePrefix("0x").removePrefix("0X")
        return value.toULongOrNull(16) ?: fail(line, message(Messages.MemoryCodecHexValue, "value" to text))
    }

    private fun decodeWords(empty: MemoryImage, lines: List<SourceLine>, format: MemoryImageFormat,
                            limits: MemoryImageLimits): MemoryImageImportResult.Success {
        val cells = mutableMapOf<Long, ULong>()
        var address = 0L
        var firstPageAllocated = false
        for (line in lines) {
            var text = line.text.substringBefore('#').trim()
            if (text.isEmpty()) continue
            if (format == MemoryImageFormat.HEX_WORDS_ADDRESSED) {
                val addressed = addressedLine(text, line.number)
                address = addressed.first
                text = addressed.second
            }
            for (token in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val parts = token.split('*')
                if (parts.size > 2 || parts.size == 2 && format != MemoryImageFormat.RAW) fail(line.number, message(Messages.MemoryCodecRepeatFormat))
                val count = if (parts.size == 2) parts[0].toLongOrNull()?.takeIf { it > 0 }
                    ?: fail(line.number, message(Messages.MemoryCodecRepeatCount)) else 1L
                val value = hex(parts.last(), line.number)
                if (value and empty.mask.inv() != 0uL) fail(line.number, message(Messages.MemoryCodecTokenWidth, "value" to token, "bits" to empty.dataBits).copy(quantity = empty.dataBits.toLong()))
                if (address < 0 || count > empty.capacity - address) fail(line.number, message(Messages.MemoryCodecWordCapacity))
                // The raw decoder writes batches, allocating a page even for an all-zero batch.
                if (address < EVOLUTION_PAGE_SIZE && (format == MemoryImageFormat.RAW || value != 0uL)) firstPageAllocated = true
                if (value == 0uL) {
                    val end = address + count
                    cells.keys.filter { it >= address && it < end }.forEach(cells::remove)
                } else {
                    for (offset in 0 until count) {
                        cells[address + offset] = value
                        if (cells.size > limits.maxNonZeroWords) fail(line.number, message(Messages.MemoryCodecNonzeroLimit, "count" to limits.maxNonZeroWords).copy(quantity = limits.maxNonZeroWords.toLong()))
                    }
                }
                address += count
            }
        }
        return MemoryImageImportResult.Success(MemoryImage(empty.addressBits, empty.dataBits, cells), format, firstPageAllocated)
    }

    private fun addressedLine(text: String, line: Int): Pair<Long, String> {
        val match = Regex("^((?:0[xX])?[0-9a-fA-F]+)(?:\\s*:\\s*|\\s+)(.*)$").matchEntire(text)
            ?: fail(line, message(Messages.MemoryCodecAddressedLine))
        val address = hex(match.groupValues[1], line)
        if (address > Long.MAX_VALUE.toULong()) fail(line, message(Messages.MemoryCodecAddress))
        val data = match.groupValues[2].trimStart().substringBefore("  ").substringBefore("\t\t")
        return address.toLong() to data
    }

    private fun decodeHexBytes(empty: MemoryImage, lines: List<SourceLine>, format: MemoryImageFormat,
                               limits: MemoryImageLimits): MemoryImageImportResult.Success {
        val addressed = format == MemoryImageFormat.HEX_BYTES_ADDRESSED_BIG || format == MemoryImageFormat.HEX_BYTES_ADDRESSED_LITTLE
        val writer = PackedWriter(empty, format.littleEndian, limits)
        if (addressed) {
            for (line in lines) {
                val text = line.text.substringBefore('#').trim()
                if (text.isEmpty()) continue
                val data = addressedLine(text, line.number)
                writer.write(data.first, hexBytes(data.second, line.number), line.number)
            }
        } else {
            val text = lines.joinToString("\n") { it.text.substringBefore('#') }
            writer.write(0, hexBytes(text, lines.firstOrNull()?.number ?: 1), lines.firstOrNull()?.number)
        }
        return writer.result(format)
    }

    private fun hexBytes(text: String, line: Int): ByteArray {
        val hex = text.split(Regex("\\s+")).joinToString("") { it.removePrefix("0x").removePrefix("0X") }
        if (hex.length % 2 != 0) fail(line, message(Messages.MemoryCodecByteDigits))
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                ?: fail(line, message(Messages.MemoryCodecHexByte))
        }
    }

    private class PackedWriter(val empty: MemoryImage, val little: Boolean, val limits: MemoryImageLimits) {
        private val cells = mutableMapOf<Long, ULong>()
        private var firstPageAllocated = false
        fun write(byteAddress: Long, bytes: ByteArray, line: Int?) {
            val totalBits = empty.capacity * empty.dataBits
            val byteCapacity = (totalBits + 7) / 8
            if (byteAddress < 0 || byteAddress > byteCapacity || bytes.size > byteCapacity - byteAddress) fail(line, message(Messages.MemoryCodecByteCapacity))
            for ((offset, byte) in bytes.withIndex()) for (bit in 0..7) {
                val streamBit = (byteAddress + offset) * 8 + bit
                val sourceBit = if (little) bit else 7 - bit
                val set = byte.toInt() and (1 shl sourceBit) != 0
                if (streamBit >= totalBits) {
                    if (set) fail(line, message(Messages.MemoryCodecOverflowBits))
                    continue
                }
                val address = streamBit / empty.dataBits
                val destinationBit = (streamBit % empty.dataBits).toInt().let { if (little) it else empty.dataBits - 1 - it }
                val mask = 1uL shl destinationBit
                val current = cells[address] ?: 0uL
                val next = if (set) current or mask else current and mask.inv()
                if (next != 0uL && address !in cells && cells.size >= limits.maxNonZeroWords) {
                    fail(line, message(Messages.MemoryCodecNonzeroLimit, "count" to limits.maxNonZeroWords).copy(quantity = limits.maxNonZeroWords.toLong()))
                }
                if (next == 0uL) cells.remove(address) else {
                    cells[address] = next
                    if (address < EVOLUTION_PAGE_SIZE) firstPageAllocated = true
                }
            }
            if (cells.size > limits.maxNonZeroWords) fail(line, message(Messages.MemoryCodecNonzeroLimit, "count" to limits.maxNonZeroWords).copy(quantity = limits.maxNonZeroWords.toLong()))
        }
        fun result(format: MemoryImageFormat) = MemoryImageImportResult.Success(
            MemoryImage(empty.addressBits, empty.dataBits, cells), format, firstPageAllocated)
    }

    private fun decodeEscapes(bytes: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        var index = 0
        var line = 1
        val simple = mapOf('0' to 0, 'a' to 7, 'b' to 8, 't' to 9, 'n' to 10, 'v' to 11,
            'f' to 12, 'r' to 13, '\\' to 92, '\'' to 39, '"' to 34, '?' to 63)
        while (index < bytes.size) {
            val character = bytes[index++].toInt() and 0xff
            if (character == 10) line++
            if (character !in 32..126) continue
            if (character != 92) { result += character.toByte(); continue }
            while (index < bytes.size && (bytes[index].toInt() and 0xff) !in 32..126) {
                if (bytes[index].toInt() == 10) line++
                index++
            }
            if (index >= bytes.size) fail(line, message(Messages.MemoryCodecEscapeIncomplete))
            val escape = bytes[index++].toInt().toChar()
            val value = if (escape == 'x') {
                val digits = StringBuilder(2)
                while (index < bytes.size && digits.length < 2) {
                    val digit = bytes[index++].toInt() and 0xff
                    if (digit == 10) line++
                    if (digit in 32..126) digits.append(digit.toChar())
                }
                if (digits.length != 2) fail(line, message(Messages.MemoryCodecHexIncomplete))
                digits.toString().toIntOrNull(16) ?: fail(line, message(Messages.MemoryCodecHexEscape))
            } else simple[escape] ?: fail(line, message(Messages.MemoryCodecEscape, "value" to escape))
            result += value.toByte()
        }
        return result.toByteArray()
    }

    private fun rawBody(image: MemoryImage, limits: MemoryImageLimits): String {
        val tokens = mutableListOf<String>()
        var cursor = 0L
        val entries = image.cells.entries.sortedBy { it.key }
        fun token(count: Long, value: ULong) {
            if (count >= 4) tokens += "$count*${value.toString(16)}"
            else repeat(count.toInt()) { tokens += value.toString(16) }
        }
        var index = 0
        while (index < entries.size) {
            val first = entries[index]
            if (first.key > cursor) token(first.key - cursor, 0uL)
            var end = index + 1
            while (end < entries.size && entries[end].key == entries[end - 1].key + 1 && entries[end].value == first.value) end++
            token((end - index).toLong(), first.value)
            cursor = entries[end - 1].key + 1
            index = end
        }
        if (tokens.isEmpty()) tokens += "0"
        val body = tokens.chunked(8).joinToString("\n") { it.joinToString(" ") } + "\n"
        requireLocalized(body.length <= limits.maxExportBytes) { message(Messages.MemoryCodecExportLimit, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong()) }
        return body
    }

    internal fun writeFullHexWordsPlain(image: MemoryImage, output: Appendable) {
        output.append(MemoryImageFormat.HEX_WORDS_PLAIN.header).append('\n')
        writeWords(image, addressed = false, count = image.capacity, output = output)
    }

    private fun wordsBody(image: MemoryImage, addressed: Boolean, limits: MemoryImageLimits): String {
        val width = (image.dataBits + 3) / 4
        val count = (image.cells.keys.maxOrNull() ?: 0L) + 1
        requireLocalized(count * (width + 1) <= limits.maxExportBytes) { message(Messages.MemoryCodecExportRaw, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong()) }
        return buildString { writeWords(image, addressed, count, this) }
    }

    private fun writeWords(image: MemoryImage, addressed: Boolean, count: Long, output: Appendable) {
        val width = (image.dataBits + 3) / 4
        val columns = if (width == 1) 32 else if (width <= 4) 16 else 8
        val addressWidth = (image.capacity - 1).toString(16).length
        for (address in 0 until count) {
            if (address % columns == 0L && addressed) output.append(address.toString(16).padStart(addressWidth, '0')).append(": ")
            else if (address % columns != 0L) output.append(' ')
            output.append(image[address].toString(16).padStart(width, '0'))
            if (address % columns == columns - 1L || address == count - 1) output.append('\n')
        }
    }

    private fun packedBytes(image: MemoryImage, little: Boolean, limits: MemoryImageLimits): ByteArray {
        val count = (image.cells.keys.maxOrNull() ?: 0L) + 1
        val bytes = (count * image.dataBits + 7) / 8
        requireLocalized(bytes <= limits.maxExportBytes) { message(Messages.MemoryCodecExportRaw, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong()) }
        val result = ByteArray(bytes.toInt())
        for ((address, value) in image.cells) for (bit in 0 until image.dataBits) {
            val streamBit = address * image.dataBits + bit
            val source = if (little) bit else image.dataBits - 1 - bit
            if (value and (1uL shl source) != 0uL) {
                val byte = (streamBit / 8).toInt()
                val target = (streamBit % 8).toInt().let { if (little) it else 7 - it }
                result[byte] = (result[byte].toInt() or (1 shl target)).toByte()
            }
        }
        return result
    }

    private fun hexBytesBody(bytes: ByteArray, addressed: Boolean, limits: MemoryImageLimits): String = buildString {
        val width = maxOf(1, (bytes.size - 1).toString(16).length)
        val lineCount = (bytes.size.toLong() + 31) / 32
        requireLocalized(bytes.size.toLong() * 2 + lineCount * (if (addressed) width + 3 else 1) <= limits.maxExportBytes) {
            message(Messages.MemoryCodecExportRaw, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong())
        }
        for (index in bytes.indices) {
            if (addressed && index % 32 == 0) append(index.toString(16).padStart(width, '0')).append(": ")
            append((bytes[index].toInt() and 0xff).toString(16).padStart(2, '0'))
            if (index % 32 == 31 || index == bytes.lastIndex) append('\n')
        }
    }

    private fun escapeBytes(bytes: ByteArray, limits: MemoryImageLimits): String = buildString {
        val escapes = mapOf(0 to "\\0", 7 to "\\a", 8 to "\\b", 9 to "\\t", 10 to "\\n", 11 to "\\v", 12 to "\\f", 13 to "\\r", 92 to "\\\\")
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(escapes[value] ?: if (value in 32..126) value.toChar().toString() else "\\x" + value.toString(16).padStart(2, '0'))
            requireLocalized(length <= limits.maxExportBytes) { message(Messages.MemoryCodecExportRaw, "bytes" to limits.maxExportBytes).copy(quantity = limits.maxExportBytes.toLong()) }
        }
    }
}
