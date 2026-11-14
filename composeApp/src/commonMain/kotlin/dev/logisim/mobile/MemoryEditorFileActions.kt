package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageDiagnostic
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

/** What opening a memory file produced, as the memory editor's Open file presents it. */
sealed interface MemoryImportOutcome {
    data class Imported(val result: MemoryImageImportResult.Success) : MemoryImportOutcome

    /**
     * The file has no header the decoder recognizes (headerless text, binary or escaped ASCII data). The editor keeps
     * the bytes and asks for a format, as Evolution's HexFormatDialog does, then decodes them again.
     */
    class NeedsFormat(val bytes: ByteArray, val diagnostics: List<MemoryImageDiagnostic>) : MemoryImportOutcome

    data class Failed(val notice: LocalizedText) : MemoryImportOutcome
    data object Cancelled : MemoryImportOutcome
}

/** Binary image operations are independent of the active .circ file association. */
interface MemoryEditorFileActions {
    /**
     * Opens a file for [component]. A file whose header names its format is decoded in that format (Evolution's
     * HexFile auto-detection); [format] is used only for files without a recognized header.
     */
    fun importImage(component: CircuitComponent, format: MemoryImageFormat, onResult: (MemoryImageImportResult?) -> Unit)
    fun exportImage(component: CircuitComponent, format: MemoryImageFormat, contents: ByteArray)

    /** Opens a file with format auto-detection, returning [MemoryImportOutcome.NeedsFormat] when a choice is needed. */
    fun importImageWithChooser(component: CircuitComponent, onResult: (MemoryImportOutcome) -> Unit) =
        importImage(component, MemoryImageFormat.HEX_WORDS_ADDRESSED) { result ->
            onResult(when (result) {
                null -> MemoryImportOutcome.Cancelled
                is MemoryImageImportResult.Success -> MemoryImportOutcome.Imported(result)
                is MemoryImageImportResult.Failure -> MemoryImportOutcome.Failed(LocalizedText.join(result.diagnostics.map { it.notice }))
            })
        }

    /** Decodes the bytes kept by [MemoryImportOutcome.NeedsFormat] in the format the user chose. */
    fun decodeImage(component: CircuitComponent, bytes: ByteArray, format: MemoryImageFormat, onResult: (MemoryImageImportResult) -> Unit) {
        val configuration = try {
            MemoryComponents.configuration(component)
        } catch (error: IllegalArgumentException) {
            onResult(MemoryImageImportResult.Failure(listOf(MemoryImageDiagnostic(null,
                error.localizedDescription(message("native_memory_import_failed"))))))
            return
        }
        onResult(MemoryImageCodec.decode(bytes, configuration.addressBits, configuration.dataBits, format))
    }
}

/** Decode failures that mean "no usable header", so the user (or the caller's fallback) must name the format. */
internal fun MemoryImageImportResult.Failure.needsFormat(): Boolean = diagnostics.any {
    it.detail.key == Messages.MemoryCodecMissingFormat || it.detail.key == Messages.MemoryCodecInvalidUtf8 ||
        it.detail.key == Messages.MemoryCodecHeaderUnsupported
}

/** A first line that looks like a header ("v2.1 raw") but names no format the decoder reads. */
internal fun MemoryImageImportResult.Failure.unsupportedHeader(): Boolean =
    diagnostics.any { it.detail.key == Messages.MemoryCodecHeaderUnsupported }

/**
 * Whether decoding the same data again in [format] can succeed. Every text format reads the header line again, so an
 * unsupported header is loaded only as Binary or ASCII, which take the bytes as they are.
 */
internal fun MemoryImageImportResult.Failure.decodableAs(format: MemoryImageFormat): Boolean =
    needsFormat() && (format.header == null || !unsupportedHeader())
