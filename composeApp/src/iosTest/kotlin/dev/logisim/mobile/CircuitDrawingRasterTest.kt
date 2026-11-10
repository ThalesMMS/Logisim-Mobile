@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.logisim.mobile

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises real Skia bitmaps and the native embedded-image decoder, without opening a UI. */
class CircuitDrawingRasterTest {
    init { createRasterTextMeasurer() }

    @Test fun aStoredImageAndWireUseTheSameWorldRendererInBitmapPngAndPdf() {
        val registry = ComponentCatalog.registry
        val pixels = VisualPng.encode(2, 1, intArrayOf(0xffff0000.toInt(), 0x8000ff00.toInt()))
        val image = EmbeddedImage.fromBytes(pixels, "image/png")
        val wire = WireSegment(GridPoint(0, 0), GridPoint(60, 0))
        val original = AnnotationEdits.insertImage(CircuitDocument("export-fixture"), "image", GridPoint(10, 10), image, 40, 20, "stretch")
            .copy(wires = listOf(wire))
        val xml = CircCodec.write(original, registry)
        val document = assertIs<CircImportResult.Success>(CircCodec.read(xml, registry)).document
        val simulation = CircuitSimulator(registry = registry).settle(document)
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val images = AnnotationImageCache()
        val bounds = requireNotNull(circuitDrawingBounds(document, measurer, images, registry))
        assertEquals(VisualBounds(-5.0, -5.0, 65.0, 30.0), bounds)
        val draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
            drawCircuitDrawing(document, simulation, measurer, images, registry)
        }
        val options = VisualExportOptions(margin = 0.0, background = 0xffffffffu)
        val bitmap = renderDrawingBitmap(bounds, options, draw = draw)
        assertEquals(70, bitmap.width)
        assertEquals(35, bitmap.height)
        val bitmapPixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        assertEquals(0xffff0000.toInt(), bitmapPixels[25 * 70 + 20])
        assertEquals(0xff7fff7f.toInt(), bitmapPixels[25 * 70 + 50])
        assertEquals(0xff2828ff.toInt(), bitmapPixels[5 * 70 + 35])
        assertEquals(0xffffffff.toInt(), bitmapPixels[0])

        val png = renderDrawingPng(bounds, options.copy(scale = 2.0), draw = draw)
        val pngBitmap = decodeEmbeddedAnnotationImage(png)
        assertEquals(140, pngBitmap.width)
        assertEquals(70, pngBitmap.height)
        val decoded = IntArray(pngBitmap.width * pngBitmap.height).also { pngBitmap.readPixels(it) }
        assertEquals(0xffff0000.toInt(), decoded[50 * 140 + 40])
        assertEquals(0xff7fff7f.toInt(), decoded[50 * 140 + 100])
        assertEquals(0xff2828ff.toInt(), decoded[10 * 140 + 70])

        val pdf = renderDrawingPdf(bounds, options, draw = draw)
        assertEquals("%PDF-1.4", pdf.copyOfRange(0, 8).decodeToString())
        assertTrue(pdf.decodeToString().contains("/Subtype /Image"))
        assertTrue(pdf.decodeToString().contains("/Width 70 /Height 35"))
        val directory = NSTemporaryDirectory() + "logisim-circuit-export/"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        write(directory + "circuit.png", png)
        write(directory + "circuit.pdf", pdf)
        write(directory + "circuit.circ", xml.encodeToByteArray())
        println("Native circuit export artifacts: $directory")
    }

    @Test fun printerViewHidesSimulationButKeepsLabelsAnnotationsAndBusGeometry() = runBlocking {
        val document = CircuitDocument("Printer view", components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 100), label = "Input"),
            CircuitComponent("output", ComponentKind.OUTPUT, GridPoint(220, 100), label = "Output"),
            CircuitComponent("led", IoKinds.Led, GridPoint(160, 180), label = "LED"),
            CircuitComponent("bus", ComponentKind.INPUT, GridPoint(100, 260), label = "Bus input", attributes = mapOf("width" to "8")),
            CircuitComponent("bus-output", ComponentKind.OUTPUT, GridPoint(220, 260), label = "Bus output", attributes = mapOf("width" to "8")),
            CircuitComponent("note", AnnotationLibrary.Text, GridPoint(130, 40), attributes = mapOf("text" to "Printed circuit")),
        ), wires = listOf(
            WireSegment(GridPoint(100, 100), GridPoint(220, 100)),
            WireSegment(GridPoint(160, 100), GridPoint(160, 180)),
            WireSegment(GridPoint(100, 260), GridPoint(220, 260)),
        ))
        val simulator = CircuitSimulator()
        val low = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.low(1), "bus" to LogicVector.of(8, 0x3cuL)))
        val high = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.high(1), "bus" to LogicVector.of(8, 0xa5uL)))
        assertEquals(LogicValue.HIGH, high.componentValues["output"])
        assertEquals(LogicValue.HIGH, (high.componentStates["led"] as IoLedState).value)
        assertEquals(0xa5uL, high.vectorValues["bus-output"]?.toULongOrNull())
        val density = Density(1f)
        val fontResolver = createFontFamilyResolver()
        val measurer = TextMeasurer(fontResolver, density, LayoutDirection.Ltr)
        val bounds = VisualBounds(40.0, 20.0, 280.0, 300.0)
        fun render(circuit: CircuitDocument, simulation: SimulationState, printerView: Boolean): IntArray {
            val bitmap = renderDrawingBitmap(bounds, VisualExportOptions(margin = 0.0, background = 0xffffffffu)) {
                drawCircuitDrawing(circuit, simulation, measurer, AnnotationImageCache(),
                    style = CircuitDrawingStyle(highContrastSignals = true, printerView = printerView))
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        }
        val liveLow = render(document, low, false)
        val liveHigh = render(document, high, false)
        assertFalse(liveLow.contentEquals(liveHigh))
        val printLow = render(document, low, true)
        val printHigh = render(document, high, true)
        assertContentEquals(printLow, printHigh)
        assertContentEquals(liveHigh, render(document, high, false))
        assertEquals(0xff000000.toInt(), printHigh[240 * 240 + 90])
        assertEquals(0xff000000.toInt(), printHigh[241 * 240 + 90])
        assertEquals(0xffffffff.toInt(), printHigh[243 * 240 + 90])
        val withoutLabels = document.copy(components = document.components.map { it.copy(label = "") })
        assertFalse(printHigh.contentEquals(render(withoutLabels, high, true)))
        val withoutAnnotation = document.copy(components = document.components.filter { it.id != "note" })
        assertFalse(printHigh.contentEquals(render(withoutAnnotation, high, true)))

        val registry = ComponentCatalog.registry
        val printSource = RenderedCircuitExportSource(registry, fontResolver, density, LayoutDirection.Ltr,
            CircuitDrawingStyle(highContrastSignals = true, printerView = true))
        val liveSource = RenderedCircuitExportSource(registry, fontResolver, density, LayoutDirection.Ltr,
            CircuitDrawingStyle(highContrastSignals = true))

        suspend fun export(circuit: CircuitDocument, simulation: SimulationState, source: CircuitExportSource, format: VisualExportFormat): ByteArray {
            val context = EditorToolContext(
                CircuitProject(listOf(circuit)), circuit, simulation, emptyList(),
                applyProject = { error("Export must not mutate the project") },
                applyDocument = { error("Export must not mutate the document") },
                selectCircuit = { error("Export must not change selection") },
                selectComponent = { error("Export must not change selection") },
                showStatus = { error("The builder must not update UI status") },
            )
            return buildVisualExportAsset(context, source, null, format, "printer-view",
                VisualExportOptions(margin = 0.0, background = 0xffffffffu), density).bytes
        }

        val printPng = export(document, high, printSource, VisualExportFormat.PNG)
        val printPdf = export(document, high, printSource, VisualExportFormat.PDF)
        assertContentEquals(printPng, export(document, low, printSource, VisualExportFormat.PNG))
        assertContentEquals(printPdf, export(document, low, printSource, VisualExportFormat.PDF))
        assertFalse(printPng.contentEquals(export(document, high, liveSource, VisualExportFormat.PNG)))
        assertFalse(printPdf.contentEquals(export(document, high, liveSource, VisualExportFormat.PDF)))
        assertFalse(printPng.contentEquals(export(withoutLabels, high, printSource, VisualExportFormat.PNG)))
        assertFalse(printPdf.contentEquals(export(withoutLabels, high, printSource, VisualExportFormat.PDF)))

        val bitmap = decodeEmbeddedAnnotationImage(printPng)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        assertTrue(0xff000000.toInt() in pixels)
        assertContentEquals(VisualPdf.encode(bitmap.width, bitmap.height, pixels), printPdf)
    }

    @Test fun tikzExportIncludesTheDefaultAnsiGatePinWireAndLiteralLabel() = runBlocking {
        val gate = CircuitComponent("gate", ComponentKind.OR, GridPoint(100, 60), label = "Saída & A_B #1")
        val gateInput = gate.inputPorts().first()
        val pin = CircuitComponent("pin", ComponentKind.INPUT, GridPoint(20, gateInput.y), label = "Entrada")
        val wire = WireSegment(pin.position, gateInput)
        val document = CircuitDocument("TikZ", components = listOf(pin, gate), wires = listOf(wire))
        val context = EditorToolContext(
            CircuitProject(listOf(document)), document, CircuitSimulator().settle(document), emptyList(),
            applyProject = { error("Export must not mutate the project") },
            applyDocument = { error("Export must not mutate the document") },
            selectCircuit = { error("Export must not change selection") },
            selectComponent = { error("Export must not change selection") },
            showStatus = { error("The builder must not update UI status") },
        )
        val density = Density(1f)
        val source = RenderedCircuitExportSource(ComponentCatalog.registry, createFontFamilyResolver(), density, LayoutDirection.Ltr)
        val options = VisualExportOptions(background = null)
        val asset = buildVisualExportAsset(context, source, null, VisualExportFormat.TIKZ, "circuit.png", options, density)
        assertEquals("circuit.tex", asset.name)
        assertEquals("application/x-tex", asset.mimeType)
        val tikz = asset.bytes.decodeToString()
        assertTrue("\\begin{tikzpicture}" in tikz)
        assertTrue(".. controls" in tikz)
        assertTrue("rectangle" in tikz.substringAfter("\\begin{scope}"))
        // Like Evolution's TikZWriter, coordinates are output pixels (y down, x=1pt,y=-1pt) after the export margin.
        assertTrue("[x=1pt,y=-1pt" in tikz)
        assertTrue(hasTikzSegment(tikz, wire.to.x - wire.from.x, wire.to.y - wire.from.y))
        assertTrue("Entrada" in tikz)
        assertTrue("Saída\\ \\&\\ A\\_B\\ \\#1" in tikz)
        assertTrue(tikz.endsWith("\\end{tikzpicture}\n"))

        val selected = buildVisualExportAsset(context, source, setOf(circuitDrawingWireId(wire)),
            VisualExportFormat.TIKZ, "wire.tex", options, density).bytes.decodeToString()
        assertTrue(hasTikzSegment(selected, wire.to.x - wire.from.x, wire.to.y - wire.from.y))
        assertFalse(".. controls" in selected)
        assertFalse("\\node" in selected)

        val saved = temporaryAsset(asset)
        try {
            assertEquals("circuit.tex", saved.url.lastPathComponent)
            assertEquals(asset.size, assertNotNull(NSData.dataWithContentsOfURL(saved.url)).length.toInt())
        } finally { saved.remove() }
        val directory = NSTemporaryDirectory() + "logisim-circuit-export/"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        write(directory + asset.name, asset.bytes)
        println("Native TikZ export artifact: $directory${asset.name}")
    }

    private fun write(path: String, bytes: ByteArray) = bytes.usePinned {
        assertTrue(NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile(path, true))
    }
}

/** True when [tikz] has a straight `(x1,y1) -- (x2,y2)` segment with the given offset in output pixels. */
private fun hasTikzSegment(tikz: String, dx: Int, dy: Int): Boolean =
    Regex("""\((-?[0-9.]+),(-?[0-9.]+)\) -- \((-?[0-9.]+),(-?[0-9.]+)\)""").findAll(tikz).any { match ->
        val (x1, y1, x2, y2) = match.destructured.toList().map(String::toDouble)
        kotlin.math.abs(x2 - x1 - dx) < 1e-3 && kotlin.math.abs(y2 - y1 - dy) < 1e-3
    }
