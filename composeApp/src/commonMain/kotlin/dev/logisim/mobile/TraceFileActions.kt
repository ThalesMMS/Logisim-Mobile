package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

sealed interface TraceFileOpenResult {
    data class Opened(val writer: TraceFileWriter) : TraceFileOpenResult
    data object Cancelled : TraceFileOpenResult
    data class Failure(val notice: LocalizedText) : TraceFileOpenResult
}

/** Native destinations append UTF-8 bytes and deliver callbacks on the UI thread. */
interface TraceFileWriter {
    val name: String
    fun append(text: String, onResult: (AssetActionResult) -> Unit)
    fun close(onResult: (AssetActionResult) -> Unit)
}

interface TraceFileActions {
    val supportsAppend: Boolean get() = true
    fun select(append: Boolean, onResult: (TraceFileOpenResult) -> Unit)
    fun create(name: String, mimeType: String, onResult: (TraceFileOpenResult) -> Unit) =
        onResult(TraceFileOpenResult.Failure(message("visual_export_action_unavailable")))
}
