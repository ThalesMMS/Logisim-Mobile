package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.logisim.mobile.core.BuzzerPcm
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.ArgbImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/*
 * JVM host target for tests only. It draws through Skia like the iOS app, so exports that record the
 * Compose canvas (TikZ, SVG, rasters) run on the host without a simulator. It has no window or app entry.
 */

private val noFocusReturn = AccessibilityFocusReturn { false }

@Composable
internal actual fun rememberAccessibilityFocusReturn(): AccessibilityFocusReturn = noFocusReturn

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

@Composable
internal actual fun Modifier.appDialogViewport(onDismissRequest: () -> Unit, properties: DialogProperties): Modifier = this

internal actual fun appDialogProperties(properties: DialogProperties): DialogProperties = properties

internal actual fun editorSheetDialogProperties() = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    scrimColor = Color.Transparent,
)

@Composable
internal actual fun NumericPropertyDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    field: @Composable (Modifier) -> Unit,
    validationMessage: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    AppAlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Modifier.fillMaxWidth())
            validationMessage()
        }
    })
}

internal actual fun Modifier.exportEditorTestTags(): Modifier = this

internal actual val editorMenusTakeFocus: Boolean = false

actual val currentShortcutPlatform: ShortcutPlatform = ShortcutPlatform.IOS

actual fun createExtraIoAudioPlayer(): ExtraIoAudioPlayer = object : ExtraIoAudioPlayer {
    override fun play(pcm: BuzzerPcm) = Unit
    override fun stop() = Unit
}

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
