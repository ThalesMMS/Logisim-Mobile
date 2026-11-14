package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualGif
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Bounds and drawing include the same objects in world coordinates, without editor handles.
 * bounds, draw and scene run on background workers against the immutable context snapshot.
 * They must not mutate UI state or share mutable renderer caches with the interactive canvas.
 * Use request-local text measurement and decoding caches; scene discovery and export may overlap.
 */
interface CircuitExportSource {
    fun bounds(context: EditorToolContext, selectionIds: Set<String>?): VisualBounds?
    fun draw(scope: DrawScope, context: EditorToolContext, selectionIds: Set<String>?)

    /** Return null unless this scene represents every exported object, including component symbols. */
    fun scene(context: EditorToolContext, selectionIds: Set<String>?): VisualScene? = null

    /** Override when the host also exposes selected wires or other drawing objects. */
    fun selectionIds(context: EditorToolContext): Set<String> = context.selectedComponents.map { it.id }.toSet()
}

enum class VisualExportAction { SAVE, SHARE, PRINT }

enum class VisualExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"), SVG("svg", "image/svg+xml"), PDF("pdf", "application/pdf"),
    JPEG("jpg", "image/jpeg"), GIF("gif", "image/gif"), TIKZ("tex", "application/x-tex");

    val isBatchImage: Boolean get() = this == PNG || this == JPEG || this == GIF
    val supportsTransparency: Boolean get() = this != JPEG && this != GIF
}

/** Complete on the UI thread after the native adapter accepts, cancels, or fails the request. */
fun interface VisualExportDelivery {
    fun deliver(asset: DocumentAsset, action: VisualExportAction, onResult: (AssetActionResult) -> Unit)
    fun supports(action: VisualExportAction): Boolean = true
    val supportsBatchSave: Boolean get() = false
    fun saveBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) =
        onResult(AssetActionResult.Failure(message("visual_export_action_unavailable")))
}

fun DocumentAssetActions.visualExportDelivery(): VisualExportDelivery = object : VisualExportDelivery {
    override fun supports(action: VisualExportAction): Boolean = action != VisualExportAction.PRINT || supportsPrint
    override val supportsBatchSave: Boolean get() = supportsBatchCreate
    override fun saveBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) = createBatch(assets, onResult)
    override fun deliver(asset: DocumentAsset, action: VisualExportAction, onResult: (AssetActionResult) -> Unit) {
        when (action) {
            VisualExportAction.SAVE -> create(asset, onResult)
            VisualExportAction.SHARE -> share(asset, onResult)
            VisualExportAction.PRINT -> {
                if (asset.mimeType == VisualExportFormat.PDF.mimeType && supportsPrint) printPdf(asset, onResult)
                else onResult(AssetActionResult.Failure(message("visual_export_print_pdf")))
            }
        }
    }
}

fun visualExportEditorTool(source: CircuitExportSource, delivery: VisualExportDelivery): EditorTool =
    EditorTool("visual-export", "Export drawing", titleText = message("visual_export_title"), requiresProjectGraph = true) { context ->
        VisualExportPanel(context, source, delivery)
    }

@Composable
fun VisualExportPanel(
    context: EditorToolContext,
    source: CircuitExportSource,
    delivery: VisualExportDelivery,
    printerSource: CircuitExportSource? = null,
    registry: ComponentRegistry = ComponentCatalog.registry,
) {
    val language = LocalAppLanguage.current
    if (!context.projectExports.access.graphAvailable) {
        Text(message("project_graph_unavailable").resolve(language))
        return
    }
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val coroutineScope = rememberCoroutineScope()
    fun t(key: String) = message(key).resolve(language)
    var format by remember { mutableStateOf(VisualExportFormat.PNG) }
    var filename by remember(context.document.id) { mutableStateOf(context.document.name) }
    var scale by remember { mutableStateOf("1") }
    var margin by remember { mutableStateOf("10") }
    var transparent by remember { mutableStateOf(false) }
    var selectionOnly by remember { mutableStateOf(false) }
    var printerView by remember(context.document.id, context.printerView) { mutableStateOf(context.printerView) }
    var pdfOptions by remember(context.documentIdentity) { mutableStateOf(PagedCircuitPdfOptions()) }
    var batchSelected by remember { mutableStateOf(false) }
    var batchCircuits by remember(context.document.id) { mutableStateOf(setOf(context.document.id)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<LocalizedText?>(null) }
    var failed by remember { mutableStateOf(false) }
    val exportSource = if (printerView && printerSource != null) printerSource else source
    val printable = remember(context.project) { printableCircuits(context) }
    val pdfCircuits = if (format == VisualExportFormat.PDF) pdfOptions.circuitIds else null
    val batchAvailable = format.isBatchImage && printable.size > 1 && delivery.supportsBatchSave
    val batch = batchAvailable && batchSelected
    val selectedCircuits = printable.map { it.id }.filterTo(mutableSetOf()) { it in batchCircuits }
    val availableSelection = exportSource.selectionIds(context).toSet()
    val selection = if (!batch && selectionOnly && pdfCircuits == null) availableSelection else null
    val selectionAvailable = when {
        batch -> selectedCircuits.isNotEmpty()
        pdfCircuits != null -> printable.any { it.id in pdfCircuits }
        else -> selection == null || selection.isNotEmpty()
    }
    // Checked as typed (C104): the scale and margin, and the image size they give before anything is rendered.
    val scaleValue = exportNumber(scale)?.takeIf { it > 0.0 }
    val marginValue = exportNumber(margin)?.takeIf { it >= 0.0 }
    val showsSize = !batch && format != VisualExportFormat.PDF
    val drawingBounds = remember(exportSource, context.document, context.selectedWires, selection, showsSize) {
        if (showsSize) runCatching { exportSource.bounds(context, selection) }.getOrNull() else null
    }
    val outputSize = if (scaleValue != null && marginValue != null) drawingBounds?.let { VisualExport.pixelSize(it, scaleValue, marginValue) } else null
    val raster = format == VisualExportFormat.PNG || format == VisualExportFormat.JPEG || format == VisualExportFormat.GIF
    val sizeProblem = outputSize?.let { size ->
        when {
            !size.fitsInt -> "visual_export_dimensions_large"
            raster && !size.fitsRaster() -> "visual_export_pixel_limit"
            else -> null
        }
    }
    val inputsValid = scaleValue != null && (marginValue != null || format == VisualExportFormat.PDF) && sizeProblem == null

    fun complete(result: AssetActionResult, action: VisualExportAction, batch: Boolean = false) {
        busy = false
        if (!context.projectExports.available) return
        failed = result is AssetActionResult.Failure
        val notice = when (result) {
            AssetActionResult.Completed -> message(when (action) {
                VisualExportAction.SAVE -> if (batch) "visual_export_batch_saved" else "visual_export_saved"
                VisualExportAction.SHARE -> "visual_export_share_presented"
                VisualExportAction.PRINT -> "visual_export_print_presented"
            })
            AssetActionResult.Cancelled -> message("visual_export_cancelled")
            is AssetActionResult.Failure -> result.notice
        }
        status = notice
        context.showNotice(notice)
    }

    fun exportFailure(failure: Throwable): AssetActionResult.Failure {
        val reason = (failure as? LocalizedArgumentException)?.text
            ?: failure.message?.let { LocalizedText.literal(it) } ?: message("visual_export_unknown_error")
        return AssetActionResult.Failure(message("visual_export_failed", "reason" to reason))
    }

    fun export(action: VisualExportAction) {
        if (busy || !context.projectExports.ensureAvailable(context.showNotice)) return
        val requestedFormat = format
        val requestedFilename = filename
        val requestedScaleText = scale
        val requestedMarginText = margin
        val requestedTransparent = transparent && requestedFormat.supportsTransparency
        val requestedSelection = selection?.toSet()
        // PDF is the printed document: the paged layout of Evolution's File > Print.
        val requestedPdf = pdfOptions.takeIf { requestedFormat == VisualExportFormat.PDF }
        val requestedBatch = batch
        val requestedCircuits = selectedCircuits.toSet()
        busy = true
        failed = false
        status = null
        coroutineScope.launch {
            runCatching {
                requireLocalized(delivery.supports(action) && (!requestedBatch ||
                    (action == VisualExportAction.SAVE && delivery.supportsBatchSave))) {
                    message("visual_export_action_unavailable")
                }
                requireLocalized(action != VisualExportAction.PRINT || requestedFormat == VisualExportFormat.PDF) { message("visual_export_print_pdf") }
                requireLocalized(requestedSelection == null || requestedSelection.isNotEmpty()) { message("visual_export_select_objects") }
                val requestedScale = requestedScaleText.replace(',', '.').toDoubleOrNull()
                    ?: throw LocalizedArgumentException(message("visual_export_invalid_scale"))
                requireLocalized(requestedScale.isFinite() && requestedScale > 0.0) { message("visual_export_invalid_scale") }
                val requestedMargin = requestedMarginText.replace(',', '.').toDoubleOrNull()
                    ?: throw LocalizedArgumentException(message("visual_export_invalid_margin"))
                requireLocalized(requestedMargin.isFinite() && requestedMargin >= 0.0) { message("visual_export_invalid_margin") }
                val options = VisualExportOptions(scale = requestedScale, margin = requestedMargin, background = if (requestedTransparent) null else 0xffffffffu)
                when {
                    requestedBatch -> buildImageBatch(context, exportSource, requestedCircuits, requestedFormat, options, density, registry)
                    requestedPdf != null -> listOf(buildPagedCircuitPdf(context, exportSource, registry, requestedSelection,
                        requestedFilename, options, requestedPdf, density, fontResolver))
                    else -> listOf(buildVisualExportAsset(context, exportSource, requestedSelection,
                        requestedFormat, requestedFilename, options, density))
                }
            }.fold(onSuccess = { payload ->
                if (!context.projectExports.available) { busy = false; return@launch }
                // Adapters may fail before opening their native picker as well as through the callback.
                var completed = false
                fun delivered(result: AssetActionResult) {
                    if (!completed) {
                        completed = true
                        complete(result, action, requestedBatch)
                    }
                }
                runCatching {
                    if (requestedBatch) delivery.saveBatch(payload, ::delivered)
                    else delivery.deliver(payload.single(), action, ::delivered)
                }.onFailure {
                    if (it is CancellationException) throw it
                    delivered(exportFailure(it))
                }
            }, onFailure = { failure ->
                if (failure is CancellationException) throw failure
                complete(exportFailure(failure), action)
            })
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("visual_export_heading"), style = MaterialTheme.typography.titleMedium)
        Text(t("visual_export_help"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisualExportFormat.entries.forEach { option ->
                FilterChip(selected = format == option, enabled = !busy, onClick = { format = option }, label = { Text(if (option == VisualExportFormat.TIKZ) "TikZ" else option.name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (batchAvailable) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(batchSelected, enabled = !busy, role = Role.Checkbox,
                onValueChange = { batchSelected = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(batchSelected, null, enabled = !busy)
                Text(t("visual_export_batch"))
            }
            if (batch) {
                Text(t("visual_export_batch_help"))
                printable.forEach { circuit ->
                    val selected = circuit.id in selectedCircuits
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { checked -> batchCircuits = if (checked) batchCircuits + circuit.id else batchCircuits - circuit.id }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, null, enabled = !busy)
                        Text(circuit.name)
                    }
                }
            }
        }
        if (!batch) OutlinedTextField(filename, { filename = it }, enabled = !busy, label = { Text(t("visual_export_filename")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // Evolution's ExportImage scale slider: 2^(k/6) from 12.5% to 800%, with the percentage beside it.
        val percent = scaleValue?.let { message("visual_export_scale_value", "percent" to exportPercent(it)).resolve(language) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sliderLabel = t("visual_export_scale_slider")
            Slider(value = (scaleValue?.let(::exportStepForScale) ?: 0).toFloat(), enabled = !busy,
                onValueChange = { scale = formatExportScale(exportScaleForStep(it.roundToInt())) },
                valueRange = EXPORT_SCALE_STEPS.first.toFloat()..EXPORT_SCALE_STEPS.last.toFloat(),
                steps = EXPORT_SCALE_STEPS.last - EXPORT_SCALE_STEPS.first - 1,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("visual-export-scale-slider").semantics {
                    contentDescription = sliderLabel
                    percent?.let { stateDescription = it }
                })
            Text(percent.orEmpty(), modifier = Modifier.widthIn(min = 56.dp).testTag("visual-export-scale-percent"),
                style = MaterialTheme.typography.titleMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXPORT_SCALE_PRESETS.forEach { preset ->
                val value = preset / 100.0
                FilterChip(selected = scaleValue == value, enabled = !busy, onClick = { scale = formatExportScale(value) },
                    label = { Text(message("visual_export_scale_value", "percent" to preset).resolve(language)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("visual-export-scale-preset-$preset"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(scale, { scale = it }, enabled = !busy, label = { Text(t("visual_export_scale")) }, singleLine = true,
                isError = scaleValue == null, supportingText = { Text(t(if (scaleValue == null) "visual_export_invalid_scale" else "visual_export_scale_help")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            if (format != VisualExportFormat.PDF) OutlinedTextField(margin, { margin = it }, enabled = !busy, label = { Text(t("visual_export_margin")) }, singleLine = true,
                isError = marginValue == null, supportingText = if (marginValue == null) { { Text(t("visual_export_invalid_margin")) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        outputSize?.let { size ->
            Text(message("visual_export_output_size", "width" to size.width.toLong(), "height" to size.height.toLong()).resolve(language),
                modifier = Modifier.testTag("visual-export-pixel-size"), style = MaterialTheme.typography.bodyMedium)
        }
        sizeProblem?.let { problem ->
            Text(t(problem), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("visual-export-pixel-limit"))
        }
        if (!batch && pdfCircuits == null) {
            val canSelectObjects = !busy && (availableSelection.isNotEmpty() || selectionOnly)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(selectionOnly, enabled = canSelectObjects, role = Role.Checkbox, onValueChange = { selectionOnly = it }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selectionOnly, null, enabled = canSelectObjects)
                Text(t("visual_export_selection"))
            }
            if (availableSelection.isEmpty()) Text(t("visual_export_select_objects"), style = MaterialTheme.typography.bodySmall)
        }
        if (format == VisualExportFormat.PDF) {
            PagedCircuitPdfControls(context.document.id, printable, pdfOptions, !busy, { pdfOptions = it }, ::t)
        }
        if (format.supportsTransparency) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(transparent, enabled = !busy, role = Role.Checkbox, onValueChange = { transparent = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(transparent, null, enabled = !busy)
            Text(t("visual_export_transparent"))
        }
        if (printerSource != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(printerView, enabled = !busy, role = Role.Checkbox, onValueChange = { printerView = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(printerView, null, enabled = !busy)
                Text(t("action_printer_view"))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Registered with the tool sheet, so its banner does not repeat the result shown here (C74).
        AccessibleEditorNotice(status, Modifier.testTag("visual-export-status"), isError = failed)
        val ready = !busy && selectionAvailable && inputsValid
        Button(onClick = { export(VisualExportAction.SAVE) }, enabled = ready && delivery.supports(VisualExportAction.SAVE), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("visual-export-save")) { Text(t(if (batch) "visual_export_batch_save" else "visual_export_save")) }
        if (!batch) TextButton(onClick = { export(VisualExportAction.SHARE) }, enabled = ready && delivery.supports(VisualExportAction.SHARE), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("visual-export-share")) { Text(t("visual_export_share")) }
        if (!batch) TextButton(onClick = { export(VisualExportAction.PRINT) }, enabled = ready && format == VisualExportFormat.PDF && delivery.supports(VisualExportAction.PRINT), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(t("visual_export_print")) }
    }
}

/** Rendering, encoding and the asset's defensive byte copy all finish away from the UI thread. */
internal suspend fun buildVisualExportAsset(
    context: EditorToolContext,
    source: CircuitExportSource,
    selectionIds: Set<String>?,
    format: VisualExportFormat,
    filename: String,
    options: VisualExportOptions,
    density: Density,
): DocumentAsset = withContext(Dispatchers.Default) {
    val outputName = visualExportFilename(filename, format)
    val bytes = when (format) {
        VisualExportFormat.SVG -> {
            // A complete vector scene keeps its object ids; any other drawing is recorded from the canvas, like Evolution.
            val scene = source.scene(context, selectionIds)
            if (scene != null) VisualExport.svg(scene, options)
            else {
                val bounds = source.bounds(context, selectionIds) ?: throw LocalizedArgumentException(message("visual_export_empty"))
                renderDrawingSvg(bounds, options, density) { source.draw(this, context, selectionIds) }
            }
        }
        VisualExportFormat.TIKZ -> {
            val bounds = source.bounds(context, selectionIds) ?: throw LocalizedArgumentException(message("visual_export_empty"))
            renderDrawingTikz(bounds, options, density) { source.draw(this, context, selectionIds) }
        }
        VisualExportFormat.PNG, VisualExportFormat.PDF -> {
            val bounds = source.bounds(context, selectionIds) ?: throw LocalizedArgumentException(message("visual_export_empty"))
            if (format == VisualExportFormat.PNG) renderDrawingPng(bounds, options, density) { source.draw(this, context, selectionIds) }
            else renderDrawingPdf(bounds, options, density = density) { source.draw(this, context, selectionIds) }
        }
        VisualExportFormat.JPEG, VisualExportFormat.GIF -> {
            val bounds = source.bounds(context, selectionIds) ?: throw LocalizedArgumentException(message("visual_export_empty"))
            val opaque = options.copy(background = (options.background ?: 0xffffffffu) or 0xff000000u)
            val bitmap = renderDrawingBitmap(bounds, opaque, density) { source.draw(this, context, selectionIds) }
            if (format == VisualExportFormat.JPEG) encodeVisualJpeg(bitmap)
            else VisualGif.encode(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) })
        }
    }
    DocumentAsset(outputName, format.mimeType, bytes)
}

/** The steps of Evolution's ExportImage scale slider (SLIDER_DIVISIONS = 6): 2^(k/6) for k in -18..18, 12.5%–800%. */
internal val EXPORT_SCALE_STEPS = -18..18
internal val EXPORT_SCALE_PRESETS = listOf(25, 50, 100, 200, 400)

internal fun exportScaleForStep(step: Int): Double = 2.0.pow(step.coerceIn(EXPORT_SCALE_STEPS) / 6.0)

/** The slider step nearest to [scale]; scales beyond 12.5%–800% sit at the ends. */
internal fun exportStepForScale(scale: Double): Int = (6 * log2(scale)).roundToInt().coerceIn(EXPORT_SCALE_STEPS)

internal fun exportPercent(scale: Double): Long = (scale * 100).roundToLong()

/** A scale as the exact field shows it: at most four decimals and no trailing zeros ("2", "0.25", "1.1225"). */
internal fun formatExportScale(scale: Double): String {
    val tenThousandths = (scale * 10_000).roundToLong()
    val whole = tenThousandths / 10_000
    val fraction = (tenThousandths % 10_000).toString().padStart(4, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

/** A typed scale or margin; a decimal comma is accepted, as on Portuguese keyboards. */
internal fun exportNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun visualExportFilename(input: String, format: VisualExportFormat): String {
    val name = input.trim()
    requireLocalized(name.isNotEmpty() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { message("visual_export_invalid_filename") }
    val extension = name.substringAfterLast('.', "").lowercase()
    val stem = if (extension == "jpeg" || VisualExportFormat.entries.any { it.extension == extension }) name.substringBeforeLast('.') else name
    requireLocalized(stem.isNotBlank()) { message("visual_export_invalid_filename") }
    return "$stem.${format.extension}"
}

internal expect fun encodeVisualJpeg(bitmap: ImageBitmap): ByteArray
