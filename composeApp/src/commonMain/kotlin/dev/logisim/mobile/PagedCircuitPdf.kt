package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.PrintHeader
import dev.logisim.mobile.core.printableCircuits
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualPdf
import dev.logisim.mobile.core.VisualPdfRaster
import dev.logisim.mobile.core.VisualPdfSheet
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Defaults of Evolution's print dialog: the current circuit, its header template, and rotation to fit. */
internal data class PagedCircuitPdfOptions(
    val circuitIds: Set<String>? = null,
    val header: String = PrintHeader.DEFAULT,
    val rotateToFit: Boolean = true,
)

/** Evolution prints each circuit's bounds expanded by four units, whatever the image export margin. */
internal const val PRINT_BORDER = 4.0

@Composable
internal fun PagedCircuitPdfControls(
    activeCircuitId: String,
    circuits: List<CircuitDocument>,
    options: PagedCircuitPdfOptions,
    enabled: Boolean,
    onChange: (PagedCircuitPdfOptions) -> Unit,
    text: (String) -> String,
) {
    Text(text("visual_export_pdf_help"), style = MaterialTheme.typography.bodySmall)
    val selection = options.circuitIds
    PdfCheckbox(text("visual_export_pdf_select_circuits"), selection != null, enabled && circuits.isNotEmpty()) { checked ->
        val initial = circuits.firstOrNull { it.id == activeCircuitId } ?: circuits.firstOrNull()
        onChange(options.copy(circuitIds = if (checked) setOfNotNull(initial?.id) else null))
    }
    if (selection != null) {
        circuits.forEach { circuit ->
            PdfCheckbox(circuit.name, circuit.id in selection, enabled) { checked ->
                onChange(options.copy(circuitIds = if (checked) selection + circuit.id else selection - circuit.id))
            }
        }
        if (circuits.none { it.id in selection }) {
            Text(text("visual_export_pdf_select_one"), style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedTextField(
        options.header, { if (it.length <= 240) onChange(options.copy(header = it)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(text("visual_export_pdf_header")) },
        supportingText = { Text(text("visual_export_pdf_header_help")) },
    )
    PdfCheckbox(text("visual_export_pdf_rotate"), options.rotateToFit, enabled) { onChange(options.copy(rotateToFit = it)) }
}

@Composable
private fun PdfCheckbox(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
    LabeledCheckbox(title, checked, enabled, onChange = onChange)

/** Project order is the page order. Empty circuits have no page. */
internal fun printableCircuits(context: EditorToolContext): List<CircuitDocument> = context.project.printableCircuits()

internal suspend fun buildPagedCircuitPdf(
    context: EditorToolContext,
    source: CircuitExportSource,
    registry: ComponentRegistry,
    selectionIds: Set<String>?,
    filename: String,
    options: VisualExportOptions,
    pdf: PagedCircuitPdfOptions,
    density: Density,
    fontResolver: FontFamily.Resolver,
): DocumentAsset = withContext(Dispatchers.Default) {
    val circuits = pdf.circuitIds?.let { ids -> printableCircuits(context).filter { it.id in ids } } ?: listOf(context.document)
    requireLocalized(circuits.isNotEmpty()) { message("visual_export_pdf_select_one") }
    val pageSelection = selectionIds.takeIf { pdf.circuitIds == null }
    val format = VisualExportFormat.PDF
    val name = visualExportFilename(filename, format)
    val headerMeasurer = TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr)
    val bytes = VisualPdf.encodePages(circuits.size, rotateToFit = pdf.rotateToFit) { index ->
        ensureActive()
        val document = circuits[index]
        val pageContext = context.forVisualExport(document, registry)
        val bounds = source.bounds(pageContext, pageSelection) ?: throw LocalizedArgumentException(message("visual_export_empty"))
        val drawing = renderDrawingBitmap(bounds, options.copy(margin = PRINT_BORDER), density) { source.draw(this, pageContext, pageSelection) }
        val header = PrintHeader.expand(pdf.header, document.name, index + 1, circuits.size)
        VisualPdfSheet(drawing.pdfRaster(), headerMeasurer.rasterizePdfHeader(header), options.scale)
    }
    DocumentAsset(name, format.mimeType, bytes)
}

internal fun EditorToolContext.forVisualExport(document: CircuitDocument, registry: ComponentRegistry): EditorToolContext {
    if (this.document.id == document.id) return this
    val simulation = if (document.name == project.mainCircuitName) projectSimulation else {
        CircuitSimulator(registry = registry).settle(project.copy(mainCircuitName = document.name),
            options = SimulationProjectOptions.read(project).asMap())
    }
    return copy(document = document, simulation = simulation, selectedComponents = emptyList(),
        selectedWires = emptySet(), activeInstancePath = emptyList())
}

private fun TextMeasurer.rasterizePdfHeader(text: String): VisualPdfRaster? {
    if (text.isBlank()) return null
    val layout = measure(text, TextStyle(color = Color.Black, fontSize = 24.sp), maxLines = 1,
        softWrap = false, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = 1588))
    val bounds = VisualBounds(0.0, 0.0, layout.size.width.coerceAtLeast(1).toDouble(), layout.size.height.coerceAtLeast(1).toDouble())
    return renderDrawingBitmap(bounds, VisualExportOptions(margin = 0.0, background = null)) { drawText(layout) }.pdfRaster()
}

private fun ImageBitmap.pdfRaster(): VisualPdfRaster =
    VisualPdfRaster(width, height, IntArray(width * height).also { readPixels(it) })
