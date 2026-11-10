package dev.logisim.mobile.core

import java.awt.image.DirectColorModel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoColorCompatibilityTest {
    @Test
    fun directFormatsMatchTheColorModelsUsedByTheDesktop() {
        val formats = listOf(
            Triple(VideoColorFormat.RGB111, DirectColorModel(3, 4, 2, 1), 8),
            Triple(VideoColorFormat.RGB444, DirectColorModel(12, 0xF00, 0x0F0, 0x00F), 4096),
            Triple(VideoColorFormat.RGB555, DirectColorModel(15, 0x7C00, 0x03E0, 0x001F), 32768),
            Triple(VideoColorFormat.RGB565, DirectColorModel(16, 0xF800, 0x07E0, 0x001F), 65536),
        )
        for ((format, desktop, values) in formats) {
            for (value in 0 until values) assertEquals(desktop.getRGB(value), format.argb(value), "$format $value")
        }
        val rgb = DirectColorModel(24, 0xFF0000, 0x00FF00, 0x0000FF)
        val random = Random(61)
        repeat(2048) {
            val value = random.nextInt()
            assertEquals(rgb.getRGB(value), VideoColorFormat.RGB888.argb(value))
        }
    }
}
