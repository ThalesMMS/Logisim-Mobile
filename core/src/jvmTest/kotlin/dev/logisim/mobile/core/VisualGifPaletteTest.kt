package dev.logisim.mobile.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evolution's ExportImage writes GIF through ImageIO; the Mobile encoder must decode to the same pixels. */
class VisualGifPaletteTest {
    private fun imageIoGif(width: Int, height: Int, argb: IntArray): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "GIF", it) }.toByteArray()
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun assertSameAsImageIo(width: Int, height: Int, argb: IntArray) {
        val expected = imageIoGif(width, height, argb)
        val actual = ImageIO.read(ByteArrayInputStream(VisualGif.encode(width, height, argb)))
        for (offset in argb.indices) {
            val x = offset % width
            val y = offset / width
            assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel ($x, $y) of ${argb[offset].toUInt().toString(16)}")
        }
    }

    @Test
    fun keepsEveryColourOfASmallPaletteExactly() {
        val colors = intArrayOf(0xffffffff.toInt(), 0xff7fff7f.toInt(), 0xffff0000.toInt(), 0xff000000.toInt(), 0xff00a000.toInt())
        val argb = IntArray(64 * 48) { colors[(it / 7 + it % 5) % colors.size] }
        val decoded = ImageIO.read(ByteArrayInputStream(VisualGif.encode(64, 48, argb)))
        for (offset in argb.indices) assertEquals(argb[offset], decoded.getRGB(offset % 64, offset / 64))
        assertSameAsImageIo(64, 48, argb)
    }

    @Test
    fun matchesImageIoWhenTheImageHasMoreThan256Colours() {
        // Antialiased drawings: a few dominant colours plus ramps between them, like the qualification raster.
        val random = Random(350)
        val width = 211
        val height = 173
        val argb = IntArray(width * height) {
            when (random.nextInt(10)) {
                in 0..5 -> 0xffffffff.toInt()
                6 -> 0xff000000.toInt() or (random.nextInt(256) * 0x010101)
                7 -> 0xff000000.toInt() or (random.nextInt(256) shl 8) or 0x7f007f
                else -> 0xff000000.toInt() or random.nextInt(0x1000000)
            }
        }
        assertTrue(argb.toSet().size > 256)
        assertSameAsImageIo(width, height, argb)
    }

    @Test
    fun matchesImageIoOnLongRunsOfOneColour() {
        val width = 300
        val height = 120
        val random = Random(7)
        val argb = IntArray(width * height) { if ((it / 37) % 3 == 0) 0xff000000.toInt() or random.nextInt(0x1000000) else 0xff336699.toInt() }
        assertSameAsImageIo(width, height, argb)
    }
}
