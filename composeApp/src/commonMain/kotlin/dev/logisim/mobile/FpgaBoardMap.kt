package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import dev.logisim.mobile.core.FpgaBoardPicture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The decoded picture of a board XML, or null while it loads or when the board has none. The pin picker draws it. */
@Composable
internal fun rememberFpgaBoardBitmap(xml: String?): ImageBitmap? = key(xml) {
    produceState<ImageBitmap?>(null) {
        if (xml != null) {
            value = withContext(Dispatchers.Default) {
                try {
                    FpgaBoardPicture.read(xml)?.let { decodeEmbeddedAnnotationImage(it.imageBytes()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }
    }.value
}
