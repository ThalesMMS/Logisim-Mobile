package dev.logisim.mobile

import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.text.CharacterCodingException

private const val IoBufferBytes = 64 * 1024

/** A picker-owned stream. read returns -1 at EOF, or fills up to buffer.size bytes. */
interface AnalysisTableInput {
    val name: String
    fun read(buffer: ByteArray): Int
    fun close()
    fun <T> withReadAccess(operation: () -> T): T = operation()
}

sealed interface AnalysisTableSelection {
    data class Opened(val input: AnalysisTableInput) : AnalysisTableSelection
    data class Failure(val notice: LocalizedText) : AnalysisTableSelection
    data object Cancelled : AnalysisTableSelection
}

interface AnalysisTableFiles {
    fun select(onResult: (AnalysisTableSelection) -> Unit)
    fun createSpoolFile(): AnalysisSpoolFile
}

/** At 20 bits and 256 output bits, expanded CSV is about 556 MiB. */
internal fun AnalysisTableInput.lines(cancelled: () -> Boolean): Sequence<String> = sequence {
    val maximumBytes = 640L * 1024 * 1024
    val chunk = ByteArray(IoBufferBytes)
    val line = ByteArray(IoBufferBytes)
    var total = 0L
    var size = 0
    var number = 1
    var skipLineFeed = false

    fun invalidLine(): Nothing = throw LocalizedArgumentException(message("analysis_table_file_invalid_line", "line" to number))
    fun decode(): String = try {
        line.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        invalidLine()
    }

    while (true) {
        if (cancelled()) throw AnalysisCancelledException()
        val read = read(chunk)
        if (read == -1) break
        check(read in 1..chunk.size) { "Invalid read from the table file provider." }
        total += read
        if (total > maximumBytes) throw LocalizedArgumentException(message("analysis_table_file_invalid"))
        for (index in 0 until read) {
            val byte = chunk[index]
            if (byte == 10.toByte() && skipLineFeed) {
                skipLineFeed = false
                continue
            }
            skipLineFeed = byte == 13.toByte()
            if (skipLineFeed || byte == 10.toByte()) {
                yield(decode())
                number++
                size = 0
            } else {
                if (size == line.size) invalidLine()
                line[size++] = byte
            }
        }
    }
    if (size > 0) yield(decode())
}

/** Private temporary records. append copies the buffer, and appended records can be read before sealing. */
internal interface AnalysisTableSpool {
    fun append(record: ByteArray)
    fun seal()
    fun read(index: Int, record: ByteArray)
    fun close()
}

interface AnalysisSpoolFile {
    fun write(offset: Int, bytes: ByteArray, count: Int)
    fun read(offset: Int, bytes: ByteArray, count: Int)
    fun close()
}

internal class BufferedAnalysisTableSpool(
    private val recordSize: Int,
    private val file: AnalysisSpoolFile,
) : AnalysisTableSpool {
    private val recordsPerBlock = IoBufferBytes / recordSize
    private val buffer = ByteArray(recordsPerBlock * recordSize)
    private var recordCount = 0
    private var cachedBlock = -1
    private var readWhileAppending: ByteArray? = null
    private var sealed = false

    override fun append(record: ByteArray) {
        check(!sealed && record.size == recordSize)
        val within = recordCount % recordsPerBlock
        record.copyInto(buffer, within * recordSize)
        recordCount++
        if (within + 1 == recordsPerBlock) file.write(recordCount * recordSize - buffer.size, buffer, buffer.size)
    }

    override fun seal() {
        check(!sealed)
        val pending = (recordCount % recordsPerBlock) * recordSize
        if (pending > 0) file.write(recordCount * recordSize - pending, buffer, pending)
        sealed = true
        cachedBlock = -1
        readWhileAppending = null
    }

    override fun read(index: Int, record: ByteArray) {
        check(index in 0 until recordCount && record.size == recordSize)
        val block = index / recordsPerBlock
        val pendingBlock = !sealed && block == recordCount / recordsPerBlock
        val source = when {
            sealed || pendingBlock -> buffer
            else -> readWhileAppending ?: ByteArray(buffer.size).also { readWhileAppending = it }
        }
        if (!pendingBlock && cachedBlock != block) {
            val first = block * recordsPerBlock
            val count = minOf(recordsPerBlock, recordCount - first)
            file.read(first * recordSize, source, count * recordSize)
            cachedBlock = block
        }
        val start = index % recordsPerBlock * recordSize
        source.copyInto(record, startIndex = start, endIndex = start + recordSize)
    }

    override fun close() = file.close()
}
