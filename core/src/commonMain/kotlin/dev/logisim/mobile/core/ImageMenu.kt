package dev.logisim.mobile.core

/** Straight-alpha ARGB pixels, row by row, as ImageBitmap.readPixels returns them. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height) }
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
}

/**
 * The Image component's MenuExtender (std.base.Image, d58a61e0) over decoded pixels: Reset to Original Size,
 * Optimize Image Size and Make Outer Background Transparent, with util.ImageUtil's rules.
 */
object ImageMenu {
    /** ImageUtil.MAX_IMAGE_DIMENSION: Evolution caches a larger image downscaled to fit it. */
    const val MAX_CACHED_DIMENSION = 2048

    /** The cached image's size (ImageUtil.enforceMaxDimensions), which every menu action works on. */
    fun cachedSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= MAX_CACHED_DIMENSION && height <= MAX_CACHED_DIMENSION) return width to height
        val scale = minOf(MAX_CACHED_DIMENSION.toDouble() / width, MAX_CACHED_DIMENSION.toDouble() / height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** The decoded image as Evolution caches it. */
    fun cached(image: ArgbImage): ArgbImage {
        val (width, height) = cachedSize(image.width, image.height)
        return if (width == image.width && height == image.height) image else scale(image, width, height)
    }

    /** Reset to Original Size: width and height of the cached image. */
    fun resetSize(image: ArgbImage): Map<String, String> =
        mapOf("width" to image.width.toString(), "height" to image.height.toString())

    /** ImageUtil.getOptimizedTargetDimensions. */
    fun optimizedSize(width: Int, height: Int, frameWidth: Int, frameHeight: Int, scale: String): Pair<Int, Int> {
        if (frameWidth <= 0 || frameHeight <= 0) return 0 to 0
        if (scale.equals("stretch", ignoreCase = true) || scale.equals("cover", ignoreCase = true)) return frameWidth to frameHeight
        val factor = minOf(frameWidth.toDouble() / width, frameHeight.toDouble() / height)
        if (factor >= 1.0) return width to height
        return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
    }

    sealed interface Optimization {
        /** The image already has the target size: Evolution shows imageAlreadyOptimizedMessage. */
        data class AlreadyOptimized(val width: Int, val height: Int) : Optimization
        /** Confirmed with imageOptimizeConfirmMessage; [frame] is the new width and height, set only for "fit". */
        data class Resize(val width: Int, val height: Int, val targetWidth: Int, val targetHeight: Int,
                          val frame: Pair<Int, Int>?) : Optimization
    }

    fun optimization(image: ArgbImage, frameWidth: Int, frameHeight: Int, scale: String): Optimization? {
        val (targetWidth, targetHeight) = optimizedSize(image.width, image.height, frameWidth, frameHeight, scale)
        if (targetWidth <= 0 || targetHeight <= 0) return null
        if (targetWidth == image.width && targetHeight == image.height) return Optimization.AlreadyOptimized(image.width, image.height)
        val fit = scale.equals("fit", ignoreCase = true)
        return Optimization.Resize(image.width, image.height, targetWidth, targetHeight, if (fit) targetWidth to targetHeight else null)
    }

    /** ImageUtil.optimizeImage: bilinear scaling to the target; "cover" scales to cover it, centred and cropped. */
    fun optimize(image: ArgbImage, targetWidth: Int, targetHeight: Int, scale: String): ArgbImage {
        if (!scale.equals("cover", ignoreCase = true)) return scale(image, targetWidth, targetHeight)
        val factor = maxOf(targetWidth.toDouble() / image.width, targetHeight.toDouble() / image.height)
        val coveredWidth = maxOf(1, (image.width * factor).toInt())
        val coveredHeight = maxOf(1, (image.height * factor).toInt())
        return draw(image, (targetWidth - coveredWidth) / 2, (targetHeight - coveredHeight) / 2, coveredWidth, coveredHeight,
            targetWidth, targetHeight)
    }

    /** Graphics2D.drawImage(image, 0, 0, width, height) with VALUE_INTERPOLATION_BILINEAR into a new image. */
    fun scale(image: ArgbImage, width: Int, height: Int): ArgbImage =
        if (width == image.width && height == image.height) image else draw(image, 0, 0, width, height, width, height)

    /**
     * Java2D's TransformHelper with BilinearInterp, then the SrcOver MaskBlit into a transparent image: every target
     * pixel centre maps back through the inverse transform in 32.32 fixed point, half a pixel up and to the left,
     * the four premultiplied neighbours (clamped at the edges) blend with 8-bit fractions, and the result is
     * unpremultiplied with AlphaMath's div8table.
     */
    private fun draw(image: ArgbImage, x: Int, y: Int, drawWidth: Int, drawHeight: Int, width: Int, height: Int): ArgbImage {
        require(drawWidth > 0 && drawHeight > 0 && width > 0 && height > 0)
        val premultiplied = IntArray(image.pixels.size) { premultiply(image.pixels[it]) }
        // AffineTransform.createInverse of translate(x, y) scale(drawWidth / w, drawHeight / h).
        val m00 = drawWidth.toDouble() / image.width
        val m11 = drawHeight.toDouble() / image.height
        val inverseX = 1.0 / m00
        val inverseY = 1.0 / m11
        val translateX = -x / m00
        val translateY = -y / m11
        val stepX = fixed(inverseX)
        val stepY = fixed(inverseY)
        val output = IntArray(width * height)
        val columns = maxOf(0, x) until minOf(width, x + drawWidth)
        val rows = maxOf(0, y) until minOf(height, y + drawHeight)
        // The first pixel centre maps through the inverse once; later rows and columns add the fixed-point steps.
        val xBase = fixed((columns.first + 0.5) * inverseX + translateX) - HALF
        var yLong = fixed((rows.first + 0.5) * inverseY + translateY) - HALF
        for (row in rows) {
            var xLong = xBase
            val yWhole = (yLong shr 32).toInt()
            val yFactor = ((yLong and 0xFFFFFFFFL) ushr 24).toInt()
            val top = yWhole.coerceIn(0, image.height - 1)
            val bottom = if (yWhole < 0 || yWhole + 1 >= image.height) top else yWhole + 1
            for (column in columns) {
                val xWhole = (xLong shr 32).toInt()
                val xFactor = ((xLong and 0xFFFFFFFFL) ushr 24).toInt()
                val left = xWhole.coerceIn(0, image.width - 1)
                val right = if (xWhole < 0 || xWhole + 1 >= image.width) left else xWhole + 1
                val p00 = premultiplied[top * image.width + left]
                val p01 = premultiplied[top * image.width + right]
                val p10 = premultiplied[bottom * image.width + left]
                val p11 = premultiplied[bottom * image.width + right]
                var result = 0
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    fun c(pixel: Int) = (pixel ushr shift) and 255
                    val upper = (c(p00) shl 8) + (c(p01) - c(p00)) * xFactor
                    val lower = (c(p10) shl 8) + (c(p11) - c(p10)) * xFactor
                    val value = ((upper shl 8) + (lower - upper) * yFactor + (1 shl 15)) shr 16
                    result = result or (value shl shift)
                }
                output[row * width + column] = unpremultiply(result)
                xLong += stepX
            }
            yLong += stepY
        }
        return ArgbImage(width, height, output)
    }

    private const val HALF = 1L shl 31
    private fun fixed(value: Double): Long = (value * 4294967296.0).toLong()

    /** AlphaMath.c mul8table and div8table. */
    private val mul8 = Array(256) { i -> IntArray(256) { j ->
        if (i == 0 || j == 0) 0 else {
            val increment = (i.toLong() shl 16) + (i shl 8) + i
            (((increment * j + (1L shl 23)) ushr 24) and 255).toInt()
        }
    } }
    private val div8 = Array(256) { i -> IntArray(256) { j ->
        if (i == 0) 0 else if (j >= i) 255 else {
            val increment = ((0xFFL shl 24) + i / 2) / i
            (((1L shl 23) + increment * j) ushr 24).toInt()
        }
    } }

    private fun premultiply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 255) return argb
        return (a shl 24) or (mul8[a][(argb shr 16) and 255] shl 16) or (mul8[a][(argb shr 8) and 255] shl 8) or mul8[a][argb and 255]
    }

    private fun unpremultiply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 0) return 0
        if (a == 255) return argb
        return (a shl 24) or (div8[a][(argb shr 16) and 255] shl 16) or (div8[a][(argb shr 8) and 255] shl 8) or div8[a][argb and 255]
    }

    /**
     * ImageUtil.makeWhiteTransparent: a flood fill from the four borders through near-white pixels (alpha > 0 and
     * red, green and blue above 235) clears their alpha and keeps their colour.
     */
    fun makeWhiteTransparent(image: ArgbImage): ArgbImage {
        val width = image.width
        val height = image.height
        val output = image.pixels.copyOf()
        fun white(index: Int): Boolean {
            val argb = image.pixels[index]
            return (argb ushr 24) and 255 > 0 && (argb shr 16) and 255 > 235 && (argb shr 8) and 255 > 235 && argb and 255 > 235
        }
        val visited = BooleanArray(output.size)
        val queue = ArrayDeque<Int>()
        fun seed(x: Int, y: Int) {
            val index = y * width + x
            if (!visited[index] && white(index)) { visited[index] = true; queue.addLast(index) }
        }
        for (x in 0 until width) { seed(x, 0); seed(x, height - 1) }
        for (y in 0 until height) { seed(0, y); seed(width - 1, y) }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            output[index] = image.pixels[index] and 0x00FFFFFF
            val x = index % width
            val y = index / width
            if (y + 1 < height) seed(x, y + 1)
            if (y > 0) seed(x, y - 1)
            if (x + 1 < width) seed(x + 1, y)
            if (x > 0) seed(x - 1, y)
        }
        return ArgbImage(width, height, output)
    }
}
