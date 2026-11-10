package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** ImageUtil's rules for the Image menu (#359); scripts/oracle/base-tools compares the pixels with Evolution. */
class ImageMenuTest {
    @Test
    fun sizesFollowImageUtil() {
        // enforceMaxDimensions (scripts/oracle/base-tools image-desktop.tsv).
        assertEquals(2048 to 500, ImageMenu.cachedSize(4096, 1000))
        assertEquals(68 to 2048, ImageMenu.cachedSize(100, 3000))
        assertEquals(2048 to 6, ImageMenu.cachedSize(2049, 7))
        assertEquals(2048 to 2048, ImageMenu.cachedSize(2048, 2048))
        // getOptimizedTargetDimensions.
        assertEquals(40 to 26, ImageMenu.optimizedSize(48, 32, 40, 30, "fit"))
        assertEquals(48 to 32, ImageMenu.optimizedSize(48, 32, 400, 400, "fit"))
        assertEquals(320 to 150, ImageMenu.optimizedSize(48, 32, 320, 150, "stretch"))
        assertEquals(30 to 60, ImageMenu.optimizedSize(48, 32, 30, 60, "cover"))
        val image = ArgbImage(48, 32, IntArray(48 * 32) { -1 })
        assertIs<ImageMenu.Optimization.AlreadyOptimized>(ImageMenu.optimization(image, 64, 64, "fit"))
        assertEquals(ImageMenu.Optimization.Resize(48, 32, 64, 64, null), ImageMenu.optimization(image, 64, 64, "stretch"))
        assertEquals(mapOf("width" to "48", "height" to "32"), ImageMenu.resetSize(image))
    }

    @Test
    fun transparencyFloodsOnlyFromTheBorder() {
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF000000.toInt()
        val light = 0xFFE0E0E0.toInt()
        // A white frame around an ink ring that encloses a white pixel, and a light grey pixel on the border.
        val pixels = intArrayOf(
            white, white, white, white, white,
            white, ink, ink, ink, white,
            white, ink, white, ink, light,
            white, ink, ink, ink, white,
            white, white, white, white, white,
        )
        val result = ImageMenu.makeWhiteTransparent(ArgbImage(5, 5, pixels)).pixels
        val clear = 0x00FFFFFF
        assertContentEquals(intArrayOf(
            clear, clear, clear, clear, clear,
            clear, ink, ink, ink, clear,
            clear, ink, white, ink, light,
            clear, ink, ink, ink, clear,
            clear, clear, clear, clear, clear,
        ), result)
    }

    @Test
    fun bilinearScalingUsesPixelCentres() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Halving two columns samples between them; doubling keeps the edge pixels and blends the middle.
        assertContentEquals(intArrayOf(0xFF808080.toInt()), ImageMenu.scale(ArgbImage(2, 1, intArrayOf(black, white)), 1, 1).pixels)
        assertContentEquals(intArrayOf(black, 0xFF404040.toInt(), 0xFFBFBFBF.toInt(), white),
            ImageMenu.scale(ArgbImage(2, 1, intArrayOf(black, white)), 4, 1).pixels)
        // Cover: the scaled image is centred and cropped to the frame.
        assertEquals(2 to 4, ImageMenu.optimize(ArgbImage(2, 1, intArrayOf(black, white)), 2, 4, "cover").let { it.width to it.height })
    }
}
