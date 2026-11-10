package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** Embedded or exported bytes. Native adapters own temporary URLs and their lifetime. */
class DocumentAsset(val name: String, val mimeType: String, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    val size: Int get() = content.size
}

sealed interface AssetOpenResult {
    data class Opened(val asset: DocumentAsset) : AssetOpenResult
    data object Cancelled : AssetOpenResult
    data class Failure(val notice: LocalizedText) : AssetOpenResult
}

sealed interface AssetActionResult {
    /** Saved, or the system share/print interface was presented; not proof of external delivery. */
    data object Completed : AssetActionResult
    data object Cancelled : AssetActionResult
    data class Failure(val notice: LocalizedText) : AssetActionResult
}

/** UI-thread callbacks; cancellation is distinct from an error and keeps the current document. */
interface DocumentAssetActions {
    val traceFiles: TraceFileActions? get() = null
    val analysisTables: AnalysisTableFiles? get() = null
    val supportsPrint: Boolean get() = false
    val supportsBatchCreate: Boolean get() = false
    fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit)
    fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit)
    fun createBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) =
        onResult(AssetActionResult.Failure(message("visual_export_action_unavailable")))
    fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit)
    fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit)
}

