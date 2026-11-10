package dev.logisim.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.logisim.mobile.core.ArgbImage
import java.io.ByteArrayOutputStream

internal actual fun decodeEmbeddedAnnotationImage(bytes: ByteArray): ImageBitmap =
    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Não foi possível decodificar a imagem incorporada." }.asImageBitmap()

internal actual fun encodeAnnotationPng(image: ArgbImage): ByteArray {
    val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    try {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return output.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
