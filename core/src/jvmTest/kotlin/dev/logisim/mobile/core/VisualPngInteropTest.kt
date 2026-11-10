package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VisualPngInteropTest {
    @Test
    fun standardPngDecoderReadsEveryArgbPixelAcrossDeflateBlocks() {
        val width = 257
        val height = 257
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            ((if ((x + y) % 3 == 0) 128 else 255) shl 24) or ((x and 255) shl 16) or ((y and 255) shl 8) or ((x xor y) and 255)
        }
        val png = VisualPng.encode(width, height, pixels)
        assertEquals(137, png[0].toInt() and 255)
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (y in 0 until height) for (x in 0 until width) assertEquals(pixels[y * width + x], decoded.getRGB(x, y), "Pixel $x,$y")
    }

    @Test
    fun rgbaTransparentAndOpaquePixelsRetainTheirChannels() {
        val pixels = intArrayOf(0x00010203, 0x80ff0080.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt())
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(VisualPng.encode(2, 2, pixels))))
        assertEquals(pixels[0], decoded.getRGB(0, 0))
        assertEquals(pixels[1], decoded.getRGB(1, 0))
        assertEquals(pixels[2], decoded.getRGB(0, 1))
        assertEquals(pixels[3], decoded.getRGB(1, 1))
    }

    @Test
    fun everyChunkHasAValidCrcAndIendTerminatesTheFile() {
        val png = VisualPng.encode(2, 1, intArrayOf(0xff123456.toInt(), 0x7fabcdef))
        fun integer(offset: Int) = ((png[offset].toInt() and 255) shl 24) or
            ((png[offset + 1].toInt() and 255) shl 16) or
            ((png[offset + 2].toInt() and 255) shl 8) or (png[offset + 3].toInt() and 255)
        var offset = 8
        val types = mutableListOf<String>()
        while (offset < png.size) {
            val length = integer(offset)
            val type = png.copyOfRange(offset + 4, offset + 8).decodeToString()
            val crc = CRC32().apply { update(png, offset + 4, length + 4) }.value.toInt()
            assertEquals(crc, integer(offset + 8 + length), type)
            types += type
            offset += 12 + length
        }
        assertEquals(listOf("IHDR", "sRGB", "IDAT", "IEND"), types)
        assertEquals(png.size, offset)
    }

    @Test
    fun svgIsValidXmlAndContainsAllPathCommands() {
        val commands = listOf(VisualPathCommand.MoveTo(VisualPoint(0.0, 0.0)),
            VisualPathCommand.LineTo(VisualPoint(10.0, 0.0)),
            VisualPathCommand.QuadTo(VisualPoint(15.0, 5.0), VisualPoint(10.0, 10.0)),
            VisualPathCommand.CubicTo(VisualPoint(8.0, 12.0), VisualPoint(2.0, 12.0), VisualPoint(0.0, 10.0)), VisualPathCommand.Close)
        val scene = VisualScene(listOf(VisualItem("shape", VisualBounds(-1.0, -1.0, 16.0, 13.0), listOf(VisualPrimitive.Path(commands)))))
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(VisualExport.svg(scene)))
        assertEquals("http://www.w3.org/2000/svg", xml.documentElement.namespaceURI)
        val path = xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").item(0).attributes.getNamedItem("d").nodeValue
        assertTrue(path.contains('M') && path.contains('L') && path.contains('Q') && path.contains('C') && path.contains('Z'))
    }

    @Test
    fun svgTextKeepsAnchorCoordinatesAndMapsVerticalBaselines() {
        val expected = listOf(
            VisualTextVerticalAlign.BASELINE to "alphabetic",
            VisualTextVerticalAlign.TOP to "text-before-edge",
            VisualTextVerticalAlign.CENTER to "central",
            VisualTextVerticalAlign.BOTTOM to "text-after-edge",
        )
        val texts = expected.map { (alignment, _) ->
            VisualPrimitive.Text("Label", VisualPoint(12.0, 34.0), verticalAlign = alignment)
        }
        val scene = VisualScene(listOf(VisualItem("labels", VisualBounds(0.0, 0.0, 100.0, 100.0), texts)))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(VisualExport.svg(scene)))
        val nodes = xml.getElementsByTagName("text")
        assertEquals(4, nodes.length)
        expected.forEachIndexed { index, (_, baseline) ->
            val attributes = nodes.item(index).attributes
            assertEquals("12", attributes.getNamedItem("x").nodeValue)
            assertEquals("34", attributes.getNamedItem("y").nodeValue)
            assertEquals(baseline, attributes.getNamedItem("dominant-baseline").nodeValue)
        }
    }
}
