package dev.logisim.mobile

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.message
import java.io.ByteArrayOutputStream

internal actual fun encodeVisualJpeg(bitmap: ImageBitmap): ByteArray {
    val bytes = ByteArrayOutputStream()
    checkLocalized(bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 90, bytes)) {
        message("visual_export_image_encoding_failed")
    }
    return bytes.toByteArray()
}
