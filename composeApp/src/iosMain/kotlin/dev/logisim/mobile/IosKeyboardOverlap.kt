package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSValue
import platform.UIKit.CGRectValue
import platform.UIKit.UIKeyboardFrameEndUserInfoKey
import platform.UIKit.UIKeyboardWillChangeFrameNotification
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIScreen

/**
 * How many points of the screen's bottom the software keyboard covers, from UIKit's own keyboard notifications.
 * A dialog scene does not always receive Compose's IME insets (A1: the Insert dialog stayed under the number pad), so
 * the dialog viewport also keeps clear of the keyboard UIKit reports. A hardware keyboard, which hides the software
 * one, reports a frame below the screen: no overlap.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun rememberIosKeyboardOverlap(): State<Double> {
    val overlap = remember { mutableDoubleStateOf(0.0) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        fun update(notification: NSNotification?) {
            val frame = notification?.userInfo?.get(UIKeyboardFrameEndUserInfoKey) as? NSValue ?: return
            val top = frame.CGRectValue().useContents { origin.y }
            val screen = UIScreen.mainScreen.bounds.useContents { size.height }
            overlap.doubleValue = (screen - top).coerceIn(0.0, screen)
        }
        val changed = center.addObserverForName(UIKeyboardWillChangeFrameNotification, null, NSOperationQueue.mainQueue) { update(it) }
        val hidden = center.addObserverForName(UIKeyboardWillHideNotification, null, NSOperationQueue.mainQueue) {
            overlap.doubleValue = 0.0
        }
        onDispose {
            center.removeObserver(changed)
            center.removeObserver(hidden)
        }
    }
    return overlap
}
