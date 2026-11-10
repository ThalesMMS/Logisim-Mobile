package dev.logisim.mobile

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualScene
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Writes the Mobile TikZ and SVG exports that scripts/oracle/visual-export/vector.py compares with Evolution's
 * TikZWriter into composeApp/build/evolution-vector-export. The JVM host draws through Skia, as the iOS app does.
 */
@OptIn(InternalComposeUiApi::class)
class EvolutionVectorExportHostTest {
    init {
        // A desktop window registers Skia text; the host test has none.
        registerSkikoComposeImplementation()
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/visual-export").isDirectory }
    private val output = File(root, "composeApp/build/evolution-vector-export").apply { mkdirs() }
    private val density = Density(1f)
    // Evolution's ExportImage: circuit bounds expanded by 5, at the chosen scale.
    private val options = VisualExportOptions(scale = 1.0, margin = 5.0)

    private fun context(path: String): EditorToolContext {
        val project = (CircCodec.readProject(File(root, path).readText()) as CircProjectImportResult.Success).project
        val document = assertNotNull(project.circuit(project.mainCircuitName))
        return EditorToolContext(project, document, SimulationState(), emptyList(),
            applyProject = { error("Export must not mutate the project") },
            applyDocument = { error("Export must not mutate the document") },
            selectCircuit = { error("Export must not change selection") },
            selectComponent = { error("Export must not change selection") },
            showStatus = { error("The builder must not update UI status") })
    }

    private fun source(shape: EditorGateShape = EditorGateShape.ANSI, printerView: Boolean = false) = RenderedCircuitExportSource(
        ComponentCatalog.registry, createFontFamilyResolver(), density, LayoutDirection.Ltr, CircuitDrawingStyle(gateShape = shape, printerView = printerView))

    private fun export(name: String, path: String, shape: EditorGateShape = EditorGateShape.ANSI, printerView: Boolean = false): Map<VisualExportFormat, String> = runBlocking {
        val context = context(path)
        val source = source(shape, printerView)
        val bounds = assertNotNull(source.bounds(context, null))
        val frame = VisualExport.frame(VisualScene(listOf(VisualItem("drawing", bounds, emptyList()))), options)
        val suffix = if (shape == EditorGateShape.ANSI) "" else "-${shape.name.lowercase()}"
        File(output, "$name$suffix.frame.txt").writeText("bounds\t${frame.bounds.left}\t${frame.bounds.top}\t${frame.pixelWidth}\t${frame.pixelHeight}\n" +
            "scene\t${if (source.scene(context, null) == null) "canvas" else "vector"}\n")
        listOf(VisualExportFormat.TIKZ, VisualExportFormat.SVG).associateWith { format ->
            val asset = buildVisualExportAsset(context, source, null, format, name, options, density)
            File(output, "$name$suffix.${format.extension}").writeBytes(asset.bytes)
            asset.bytes.decodeToString()
        }
    }

    private fun svgElements(svg: String, tag: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(svg.encodeToByteArray())).getElementsByTagName(tag)

    @Test
    fun ansiGatesExportSvgAndTikzFromTheCanvas() {
        assertNull(source().scene(context("scripts/oracle/visual-export/svg-gates.circ"), null), "ANSI gates have no vector scene")
        val files = export("svg-gates", "scripts/oracle/visual-export/svg-gates.circ")
        val svg = files.getValue(VisualExportFormat.SVG)
        assertTrue(svg.contains("viewBox=\"0 0 "))
        assertTrue(svgElements(svg, "path").length >= 11, "wires and gate outlines are paths")
        val tikz = files.getValue(VisualExportFormat.TIKZ)
        assertTrue(tikz.contains("\\begin{tikzpicture}[x=1pt,y=-1pt,line cap=rect]"))
        assertTrue(tikz.contains("\\definecolor{custcol_FFFFFF}{HTML}{FFFFFF}"))
    }

    @Test
    fun iecGatesKeepTheirVectorSceneSvgAndRecordTikz() {
        val files = export("svg-gates", "scripts/oracle/visual-export/svg-gates.circ", EditorGateShape.IEC)
        assertTrue(files.getValue(VisualExportFormat.SVG).contains("data-object-id"))
        assertTrue(files.getValue(VisualExportFormat.TIKZ).contains("\\end{tikzpicture}"))
    }

    @Test
    fun labelledGatesAndTunnelsExportInBothGateShapes() {
        for (shape in listOf(EditorGateShape.ANSI, EditorGateShape.IEC)) {
            val files = export("svg-labels", "scripts/oracle/visual-export/svg-labels.circ", shape)
            val texts = svgElements(files.getValue(VisualExportFormat.SVG), "text")
            assertTrue((0 until texts.length).any { texts.item(it).textContent == "tunnele" }, "$shape tunnel label")
            assertTrue((0 until texts.length).any { texts.item(it).textContent == "nandn" }, "$shape gate label")
            // OddParityGate draws its "2k+1" rectangle in every gate shape (#358).
            assertTrue((0 until texts.length).any { texts.item(it).textContent == "2k+1" }, "$shape parity symbol")
        }
    }

    @Test
    fun printerViewOmitsTheInputLinesOfUnconnectedGateInputs() {
        // PainterShaped.paintInputLines: in the printer view only inputs with a wire or another component's end get a line (#360).
        val svg = export("svg-printer", "scripts/oracle/visual-export/svg-printer.circ", printerView = true).getValue(VisualExportFormat.SVG)
        val paths = svgElements(svg, "path")
        val terminalLines = (0 until paths.length).map { paths.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("stroke-width") == "3" }
        assertEquals(3, terminalLines.size, "the wired OR inputs and the XOR input touched by a pin")
    }

    @Test
    fun qualificationCircuitExportsSvgWithTextAndImage() {
        val files = export("visual_export_qualification", "fixtures/visual-export/qualification.circ")
        val svg = files.getValue(VisualExportFormat.SVG)
        val texts = svgElements(svg, "text")
        assertTrue((0 until texts.length).any { texts.item(it).textContent == "EXPORT34_FAR" })
        assertEquals(1, svgElements(svg, "image").length, "the embedded annotation image is kept as PNG")
        assertTrue(files.getValue(VisualExportFormat.TIKZ).contains("[Image: 2x1]"))
    }
}
