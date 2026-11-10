package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.TraceFrame
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription

enum class TraceFileRecordingState { STOPPED, RECORDING, PAUSED, STOPPING }

enum class TraceFileFormat { TIMESTAMPED, LOGISIM_2_7_1 }

/** A bounded write queue independent of the trace table's retained history. UI-thread only. */
class TraceFileRecording(private val recorder: TraceRecorder, private val onFailure: (LocalizedText) -> Unit) {
    var state by mutableStateOf(TraceFileRecordingState.STOPPED)
        private set
    var name by mutableStateOf("")
        private set
    var failure by mutableStateOf<LocalizedText?>(null)
        private set
    private var writer: TraceFileWriter? = null
    private var selectedSignals = emptyList<TraceSignal>()
    private val pending = ArrayDeque<String>()
    private var pendingCharacters = 0
    private var writing = false
    private var closing = false
    private var documentIdentity: Long? = null
    private var lastRecordedFrame: TraceFrame? = null
    private var lastSampleTime: Long? = null
    private var format = TraceFileFormat.TIMESTAMPED
    private var legacyHeaderPending = false

    init { recorder.onCapture = ::capture }

    fun bindDocument(identity: Long) {
        if (documentIdentity != null && documentIdentity != identity) stop()
        documentIdentity = identity
    }

    fun start(
        destination: TraceFileWriter,
        header: Boolean,
        format: TraceFileFormat = TraceFileFormat.TIMESTAMPED,
    ) {
        check(state == TraceFileRecordingState.STOPPED)
        failure = null
        lastRecordedFrame = null
        lastSampleTime = null
        selectedSignals = recorder.signals.toList()
        this.format = format
        legacyHeaderPending = header && format == TraceFileFormat.LOGISIM_2_7_1
        writer = destination
        name = destination.name
        state = TraceFileRecordingState.RECORDING
        if (header && format == TraceFileFormat.TIMESTAMPED) enqueue(buildString {
            append("time_").append(recorder.timeUnit.name.lowercase())
            selectedSignals.forEach { signal ->
                append('\t').append(tsv("${signal.reference.key}:${signal.label}[${signal.width}]"))
            }
            append('\n')
        })
        captureCurrentFrame()
    }

    fun pause() { if (state == TraceFileRecordingState.RECORDING) state = TraceFileRecordingState.PAUSED }
    fun resume() {
        if (state == TraceFileRecordingState.PAUSED) {
            state = TraceFileRecordingState.RECORDING
            captureCurrentFrame()
        }
    }

    fun stop() {
        if (state == TraceFileRecordingState.STOPPED) return
        state = TraceFileRecordingState.STOPPING
        drain()
    }

    private fun captureCurrentFrame() {
        val frame = if (format == TraceFileFormat.LOGISIM_2_7_1) recorder.lastCompletedFrame
            else recorder.frames.lastOrNull()
        frame?.let { capture(it, propagationComplete = true) }
    }

    private fun capture(frame: TraceFrame, propagationComplete: Boolean) {
        if (state != TraceFileRecordingState.RECORDING && state != TraceFileRecordingState.PAUSED) return
        if (format == TraceFileFormat.LOGISIM_2_7_1 && !propagationComplete) return
        if (lastSampleTime?.let { frame.time < it } == true) {
            fail(message("trace_file_clock_reset"))
            return
        }
        if (recorder.signals != selectedSignals) {
            fail(message("trace_file_signals_changed"))
            return
        }
        lastSampleTime = frame.time
        if (state == TraceFileRecordingState.PAUSED) return
        if (format == TraceFileFormat.LOGISIM_2_7_1) {
            captureLegacy(frame)
            return
        }
        if (frame == lastRecordedFrame) return
        lastRecordedFrame = frame
        enqueue(buildString {
            append(frame.time)
            frame.values.forEachIndexed { index, value ->
                append('\t').append(value?.let(selectedSignals[index].radix::format) ?: "<missing>")
            }
            append('\n')
        })
    }

    private fun captureLegacy(frame: TraceFrame) {
        val previous = lastRecordedFrame
        if (frame.values == previous?.values || (previous == null && frame.values.all { it == null })) return
        lastRecordedFrame = frame
        enqueue(buildString {
            if (legacyHeaderPending) {
                append(selectedSignals.joinToString("\t") { signal ->
                    val path = signal.legacyInstancePath
                    tsv(if (path.isEmpty()) signal.label else "$path.${signal.label}")
                })
                append('\n')
                legacyHeaderPending = false
            }
            frame.values.forEachIndexed { index, value ->
                if (index != 0) append('\t')
                if (value != null) append(value.legacyDisplay(selectedSignals[index].radix))
            }
            append('\n')
        })
    }

    private fun LogicVector.legacyDisplay(radix: TraceRadix): String = when (radix) {
        TraceRadix.BINARY -> legacyBinary()
        TraceRadix.DECIMAL -> when {
            isError -> "Error"
            !isFullyDefined -> "???"
            width == 32 -> value.toInt().toString()
            else -> value.toString()
        }
        TraceRadix.HEXADECIMAL -> buildString {
            for (start in ((width - 1) / 4) * 4 downTo 0 step 4) {
                // Logisim 2.7.1 uses the first unknown or error bit from the high end of each digit.
                val undefined = ((unknownMask or errorMask) and (15uL shl start)).takeHighestOneBit()
                append(when {
                    undefined and errorMask != 0uL -> 'E'
                    undefined != 0uL -> 'x'
                    else -> "0123456789abcdef"[((value shr start) and 15uL).toInt()]
                })
            }
        }
    }

    private fun LogicVector.legacyBinary(): String = buildString {
        for (index in width - 1 downTo 0) {
            append(bit(index).display.replace('?', 'x'))
            if (index > 0 && index % 4 == 0) append(' ')
        }
    }

    private fun enqueue(text: String) {
        // UTF-8 uses at most three bytes per UTF-16 code unit, including surrogate pairs.
        if (pendingCharacters + text.length > MAX_PENDING_CHARACTERS) {
            fail(message("trace_file_queue_full"))
            return
        }
        pending.addLast(text)
        pendingCharacters += text.length
        drain()
    }

    private fun drain() {
        val destination = writer ?: return
        if (writing || closing) return
        if (pending.isNotEmpty()) {
            val text = pending.joinToString("")
            pending.clear()
            pendingCharacters = 0
            writing = true
            try {
                destination.append(text) { result ->
                    writing = false
                    if (result is AssetActionResult.Failure) fail(result.notice)
                    else if (result == AssetActionResult.Cancelled) fail(message("trace_file_write_failed"))
                    drain()
                }
            } catch (error: Exception) {
                writing = false
                fail(error.localizedDescription(message("trace_file_write_failed")))
            }
        } else if (state == TraceFileRecordingState.STOPPING) {
            closing = true
            try {
                destination.close(::closed)
            } catch (error: Exception) {
                closed(AssetActionResult.Failure(error.localizedDescription(message("trace_file_write_failed"))))
            }
        }
    }

    private fun closed(result: AssetActionResult) {
        closing = false
        writer = null
        state = TraceFileRecordingState.STOPPED
        val notice = when (result) {
            is AssetActionResult.Failure -> result.notice
            AssetActionResult.Cancelled -> message("trace_file_write_failed")
            AssetActionResult.Completed -> null
        }
        if (notice != null) { failure = notice; onFailure(notice) }
    }

    private fun fail(notice: LocalizedText) {
        pending.clear()
        pendingCharacters = 0
        state = TraceFileRecordingState.STOPPING
        failure = notice
        onFailure(notice)
        drain()
    }

    private fun tsv(value: String): String = if (value.any { it == '\t' || it == '\r' || it == '\n' || it == '"' })
        "\"${value.replace("\"", "\"\"")}\"" else value

    private companion object { const val MAX_PENDING_CHARACTERS = 256 * 1024 }
}
