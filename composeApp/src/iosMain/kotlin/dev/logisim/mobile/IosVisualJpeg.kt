package dev.logisim.mobile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun encodeVisualJpeg(bitmap: ImageBitmap): ByteArray {
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    try {
        val data = image.encodeToData(EncodedImageFormat.JPEG, quality = 90)
            ?: throw LocalizedStateException(message("visual_export_image_encoding_failed"))
        return try { data.bytes } finally { data.close() }
    } finally {
        image.close()
    }
}
