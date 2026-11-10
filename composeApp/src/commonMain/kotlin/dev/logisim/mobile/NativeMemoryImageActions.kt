package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageDiagnostic
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.MemoryImageLimits
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The window owns the scope; cancelling it prevents a late import from reaching its editor. */
class NativeMemoryImageActions(
    private val assets: DocumentAssetActions,
    private val scope: CoroutineScope,
    private val onNotice: (LocalizedText) -> Unit = {},
    private val onFailure: (LocalizedText) -> Unit = {},
    private val resolveNotice: (LocalizedText) -> String = { it.resolve(systemAppLanguage()) },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MemoryEditorFileActions {
    override fun importImage(
        component: CircuitComponent,
        format: MemoryImageFormat,
        onResult: (MemoryImageImportResult?) -> Unit,
    ) {
        if (!scope.isActive) return
        val configuration = try {
            MemoryComponents.configuration(component)
        } catch (error: IllegalArgumentException) {
            onResult(failure(error.localizedDescription(message("native_memory_import_failed"))))
            return
        }
        assets.open(listOf("application/octet-stream"), MAX_MEMORY_FILE_BYTES) { opened ->
            if (!scope.isActive) return@open
            when (opened) {
                AssetOpenResult.Cancelled -> onResult(null)
                is AssetOpenResult.Failure -> onResult(failure(opened.notice))
                is AssetOpenResult.Opened -> scope.launch {
                    val decoded = withContext(workerDispatcher) {
                        MemoryImageCodec.decode(opened.asset.bytes, configuration.addressBits, configuration.dataBits,
                            format, MemoryImageLimits(maxInputBytes = MAX_MEMORY_FILE_BYTES))
                    }
                    onResult(decoded)
                }
            }
        }
    }

    override fun exportImage(component: CircuitComponent, format: MemoryImageFormat, contents: ByteArray) {
        if (!scope.isActive) return
        if (contents.size > MAX_MEMORY_FILE_BYTES) {
            onFailure(message("native_memory_export_too_large"))
            return
        }
        scope.launch {
            val asset = withContext(workerDispatcher) {
                val binary = format == MemoryImageFormat.BINARY_BIG || format == MemoryImageFormat.BINARY_LITTLE
                val ascii = format == MemoryImageFormat.ASCII_BIG || format == MemoryImageFormat.ASCII_LITTLE
                val extension = if (binary) "bin" else if (ascii) "txt" else "hex"
                val label = component.label.ifBlank { component.kind.name.lowercase() }
                    .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }.joinToString("")
                    .ifBlank { "memory" }
                val name = if (label.endsWith(".$extension", ignoreCase = true)) label else "$label.$extension"
                DocumentAsset(name, if (binary) "application/octet-stream" else "text/plain", contents)
            }
            assets.create(asset) { result ->
                if (!scope.isActive) return@create
                when (result) {
                    AssetActionResult.Completed -> onNotice(message("native_memory_export_complete"))
                    AssetActionResult.Cancelled -> Unit
                    is AssetActionResult.Failure -> onFailure(result.notice)
                }
            }
        }
    }

    private fun failure(notice: LocalizedText): MemoryImageImportResult.Failure =
        MemoryImageImportResult.Failure(listOf(MemoryImageDiagnostic(null, resolveNotice(notice))))
}

private const val MAX_MEMORY_FILE_BYTES = 64 * 1024 * 1024
