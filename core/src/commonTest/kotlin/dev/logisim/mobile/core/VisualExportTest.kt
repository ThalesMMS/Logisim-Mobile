package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualExportTest {
    private val first = VisualItem("gate<&\"", VisualBounds(-12.0, -8.0, 52.0, 28.0), listOf(
        VisualPrimitive.Rectangle(VisualBounds(-10.0, -6.0, 50.0, 26.0), VisualPaint(fill = 0x80ffffffu, strokeWidth = 2.0)),
        VisualPrimitive.Text("A < B & \"C\"\nçΩ", VisualPoint(20.0, 8.0), VisualFont("Monospaced", 12.0, bold = true), align = VisualTextAlign.CENTER),
    ))
    private val second = VisualItem("wire", VisualBounds(100.0, 0.0, 200.0, 10.0), listOf(
        VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(100.0, 5.0)), VisualPathCommand.LineTo(VisualPoint(200.0, 5.0))), VisualPaint(stroke = 0xff00aa00u, strokeWidth = 3.0)),
    ))

    @Test
    fun exportsSelectionScaleMarginsAndEscapedTextWithoutViewport() {
        val scene = VisualScene(listOf(first, second))
        val options = VisualExportOptions(selection = setOf(first.id), scale = 2.0, margin = 5.0, background = null)
        val frame = VisualExport.frame(scene, options)
        assertEquals(148, frame.pixelWidth)
        assertEquals(92, frame.pixelHeight)
        assertEquals(VisualBounds(-17.0, -13.0, 57.0, 33.0), frame.bounds)
        val svg = VisualExport.svg(scene, options).decodeToString()
        assertTrue(svg.contains("viewBox=\"-17 -13 74 46\""))
        assertTrue(svg.contains("A &lt; B &amp; &quot;C&quot;"))
        assertTrue(svg.contains("font-family=\"monospace\""))
        assertTrue(svg.contains("<tspan x=\"20\" dy=\"14.399999999999999\">çΩ</tspan>") || svg.contains("<tspan x=\"20\" dy=\"14.4\">çΩ</tspan>"))
        assertFalse(svg.contains("data-object-id=\"wire\""))
    }

    @Test
    fun preservesEmbeddedImageBytesAndBounds() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val image = VisualPrimitive.Image(VisualBounds(0.0, 0.0, 20.0, 10.0), "image/png", original)
        original[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), image.encodedBytes)
        val exported = image.encodedBytes
        exported[0] = 99
        assertEquals(1, image.encodedBytes.first().toInt())
        val svg = VisualExport.svg(VisualScene(listOf(VisualItem("image", image.bounds, listOf(image))))).decodeToString()
        assertTrue(svg.contains("href=\"data:image/png;base64,AQIDBAU=\""))
        assertTrue(svg.contains("preserveAspectRatio=\"none\""))
        assertFalse(svg.contains("file:"))
    }

    @Test
    fun clippingAndTransformAreExplicitAndStable() {
        val group = VisualPrimitive.Group(VisualTransform(10.0, 20.0, 90.0, 2.0, -1.0), first.primitives)
        val scene = VisualScene(listOf(VisualItem("group", VisualBounds(0.0, 0.0, 100.0, 100.0), listOf(group))))
        val svg = VisualExport.svg(scene, VisualExportOptions(clip = VisualBounds(20.0, 30.0, 40.0, 50.0), margin = 0.0)).decodeToString()
        assertTrue(svg.contains("viewBox=\"20 30 20 20\""))
        assertTrue(svg.contains("clip-path=\"url(#circuit-clip)\""))
        assertTrue(svg.contains("translate(10 20) rotate(90) scale(2 -1)"))
        assertEquals(svg, VisualExport.svg(scene, VisualExportOptions(clip = VisualBounds(20.0, 30.0, 40.0, 50.0), margin = 0.0)).decodeToString())
    }

    @Test
    fun vectorExportAcceptsLargeCircuitsButRasterLimitIsExplicit() {
        val scene = VisualScene(listOf(VisualItem("large", VisualBounds(0.0, 0.0, 100_000.0, 100_000.0), emptyList())))
        assertEquals(100020, VisualExport.frame(scene).pixelWidth)
        assertTrue(VisualExport.svg(scene).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { VisualExport.frame(scene, raster = true) }
        assertEquals(101, VisualExport.frame(scene, VisualExportOptions(scale = 0.001), raster = true).pixelWidth)
    }

    @Test
    fun imageScalingModesMatchSvgViewportRules() {
        val modes = mapOf("stretch" to "none", "fit" to "xMidYMid meet", "cover" to "xMidYMid slice")
        modes.forEach { (mode, attribute) ->
            val image = VisualPrimitive.Image(VisualBounds(0.0, 0.0, 20.0, 20.0), "image/png", byteArrayOf(1), mode)
            val scene = VisualScene(listOf(VisualItem("image", image.bounds, listOf(image))))
            assertTrue(VisualExport.svg(scene).decodeToString().contains("preserveAspectRatio=\"$attribute\""))
        }
        assertFailsWith<IllegalArgumentException> { VisualPrimitive.Image(VisualBounds(0.0, 0.0, 20.0, 20.0), "image/png", byteArrayOf(1), "unknown") }
    }

    @Test
    fun textLayoutUsesMeasuredHeightForEveryVerticalAnchor() {
        val text = VisualPrimitive.Text("first\nsecond", VisualPoint(100.0, 200.0), align = VisualTextAlign.CENTER)
        assertEquals(VisualBounds(80.0, 185.0, 120.0, 205.0), text.layoutBounds(40.0, 20.0, 15.0))
        assertEquals(VisualBounds(80.0, 200.0, 120.0, 220.0), text.copy(verticalAlign = VisualTextVerticalAlign.TOP).layoutBounds(40.0, 20.0, 15.0))
        assertEquals(VisualBounds(80.0, 190.0, 120.0, 210.0), text.copy(verticalAlign = VisualTextVerticalAlign.CENTER).layoutBounds(40.0, 20.0, 15.0))
        assertEquals(VisualBounds(80.0, 180.0, 120.0, 200.0), text.copy(verticalAlign = VisualTextVerticalAlign.BOTTOM).layoutBounds(40.0, 20.0, 15.0))
        assertEquals(VisualBounds(60.0, 190.0, 100.0, 210.0), text.copy(align = VisualTextAlign.END, verticalAlign = VisualTextVerticalAlign.CENTER).layoutBounds(40.0, 20.0, 15.0))
    }

    @Test
    fun pdfPagesValidatePrintableBoundsAndKeepTransparency() {
        assertFailsWith<IllegalArgumentException> { VisualPdfPage(widthPoints = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { VisualPdfPage(10.0, 10.0, 6.0) }
        assertFailsWith<IllegalArgumentException> { VisualPdf.encode(2, 2, IntArray(3)) }
        val opaque = VisualPdf.encode(1, 1, intArrayOf(0xffff0000.toInt())).decodeToString()
        assertTrue(opaque.startsWith("%PDF-1.4"))
        assertTrue(opaque.contains("/MediaBox [0 0 595 842]"))
        assertFalse(opaque.contains("/SMask"))
        val alpha = VisualPdf.encode(1, 1, intArrayOf(0x80ff0000.toInt())).decodeToString()
        assertTrue(alpha.contains("/SMask 5 0 R"))
        assertTrue(alpha.contains("/ColorSpace /DeviceGray"))
    }

    @Test
    fun multiPagePdfCentersEachHeaderAndOnlyRotatesTheWideDrawing() {
        fun raster(width: Int, height: Int) = VisualPdfRaster(width, height, IntArray(width * height) { 0xff336699.toInt() })
        val sheets = listOf(
            VisualPdfSheet(raster(100, 10), raster(200, 28), drawingPixelsPerPoint = 0.1),
            VisualPdfSheet(raster(10, 60), raster(200, 28), drawingPixelsPerPoint = 0.1),
        )
        val pdf = VisualPdf.encodePages(sheets.size, rotateToFit = true, sheet = sheets::get).decodeToString()
        assertTrue(pdf.contains("/Kids [3 0 R 7 0 R] /Count 2"))
        assertEquals(2, Regex("/MediaBox ").findAll(pdf).count())
        assertEquals(2, Regex("/Header Do").findAll(pdf).count())
        assertEquals(1, Regex("0 1 -1 0 595 0 cm").findAll(pdf).count())
        assertTrue(pdf.contains("100 0 0 14 371 557 cm\n/Header Do"))
        assertTrue(pdf.contains("100 0 0 14 247.5 804 cm\n/Header Do"))
        assertTrue(pdf.contains("100 0 0 600 247.5 204 cm\n/Circuit Do"))
        val uprightPdf = VisualPdf.encodePages(sheets.size, sheet = sheets::get).decodeToString()
        assertFalse(uprightPdf.contains("0 1 -1 0 595 0 cm"))
        assertFailsWith<IllegalArgumentException> { VisualPdf.encodePages(0, sheet = sheets::get) }
    }

    @Test
    fun rejectsEmptySelectionInvalidDimensionsAndDuplicateObjects() {
        assertFailsWith<IllegalArgumentException> { VisualExport.frame(VisualScene(emptyList())) }
        assertFailsWith<IllegalArgumentException> { VisualExport.frame(VisualScene(listOf(first)), VisualExportOptions(selection = setOf("missing"))) }
        assertFailsWith<IllegalArgumentException> { VisualScene(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { VisualExportOptions(scale = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { VisualBounds(10.0, 0.0, 0.0, 10.0) }
        assertFailsWith<IllegalArgumentException> { VisualPng.encode(2, 2, IntArray(3)) }
    }
}
