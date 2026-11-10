package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class VisualGifInteropTest {
    @Test
    fun imageIoRecoversColorsAcrossGifDictionaryResets() {
        val width = 157
        val height = 161
        val random = Random(92)
        val expected = IntArray(width * height) {
            val index = random.nextInt(256)
            val red = (index ushr 5) * 255 / 7
            val green = ((index ushr 2) and 7) * 255 / 7
            val blue = (index and 3) * 255 / 3
            0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        val decoded = ImageIO.read(ByteArrayInputStream(VisualGif.encode(width, height, expected)))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (offset in expected.indices) {
            assertEquals(expected[offset], decoded.getRGB(offset % width, offset / width), "pixel $offset")
        }
    }

    @Test
    fun imageIoReadsTheSmallestSingleFrame() {
        val decoded = ImageIO.read(ByteArrayInputStream(VisualGif.encode(1, 1, intArrayOf(0xffff0000.toInt()))))
        assertEquals(0xffff0000.toInt(), decoded.getRGB(0, 0))
    }
}
