package dev.logisim.mobile

import androidx.compose.ui.unit.Density
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAXIMUM_IMAGE_BATCH_BYTES = 64L * 1024 * 1024

internal suspend fun buildImageBatch(
    context: EditorToolContext,
    source: CircuitExportSource,
    circuitIds: Set<String>,
    format: VisualExportFormat,
    options: VisualExportOptions,
    density: Density,
    registry: ComponentRegistry,
): List<DocumentAsset> = withContext(Dispatchers.Default) {
    requireLocalized(format.isBatchImage) { message("visual_export_batch_format") }
    val documents = printableCircuits(context).filter { it.id in circuitIds }
    requireLocalized(documents.isNotEmpty()) { message("visual_export_batch_choose") }
    var totalBytes = 0L
    val names = visualBatchFilenames(documents.map { it.name }, format)
    documents.zip(names).map { (document, name) ->
        ensureActive()
        val asset = try {
            buildVisualExportAsset(context.forVisualExport(document, registry), source, null, format, name, options, density)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LocalizedArgumentException(message("visual_export_batch_file_error", "name" to name,
                "reason" to error.localizedDescription(message("visual_export_unknown_error"))))
        }
        totalBytes += asset.size
        requireLocalized(totalBytes <= MAXIMUM_IMAGE_BATCH_BYTES) { message("visual_export_batch_size") }
        asset
    }
}

internal fun visualBatchFilenames(circuitNames: List<String>, format: VisualExportFormat): List<String> {
    val used = mutableSetOf<String>()
    return circuitNames.map { circuitName ->
        val stem = circuitName.map { char ->
            if (char.isISOControl() || char in "\\/:*?\"<>|") '_' else char
        }.joinToString("").trim().take(80).trimEnd { it == '.' || it.isWhitespace() || it.code in 0xd800..0xdbff }
            .ifEmpty { "circuit" }
        var name = "$stem.${format.extension}"
        var suffix = 2
        while (!used.add(name.lowercase())) {
            name = "$stem-${suffix++}.${format.extension}"
        }
        name
    }
}
