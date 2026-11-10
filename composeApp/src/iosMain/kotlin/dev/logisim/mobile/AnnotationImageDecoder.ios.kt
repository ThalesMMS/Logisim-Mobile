package dev.logisim.mobile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.logisim.mobile.core.ArgbImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun decodeEmbeddedAnnotationImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual fun encodeAnnotationPng(image: ArgbImage): ByteArray {
    // BGRA_8888 bytes in memory order are the ARGB ints in little-endian order.
    val bytes = ByteArray(image.pixels.size * 4)
    for ((index, argb) in image.pixels.withIndex()) {
        bytes[index * 4] = argb.toByte()
        bytes[index * 4 + 1] = (argb shr 8).toByte()
        bytes[index * 4 + 2] = (argb shr 16).toByte()
        bytes[index * 4 + 3] = (argb ushr 24).toByte()
    }
    val raster = Image.makeRaster(ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, image.width * 4)
    return checkNotNull(raster.encodeToData(EncodedImageFormat.PNG)).bytes
}
