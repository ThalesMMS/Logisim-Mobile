package dev.logisim.mobile

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.WindowInfo
import kotlinx.coroutines.flow.collect

internal fun cancelCanvasInteractionOnFocusLoss(
    activeGesture: CanvasGestureMachine?,
    hasPendingInput: Boolean,
    clearInteraction: () -> Unit,
    onCommand: (CanvasCommand) -> Unit,
) {
    activeGesture?.cancel()
    clearInteraction()
    if (activeGesture != null || hasPendingInput) onCommand(CanvasCommand.CANCEL)
}

/** Window focus can change without changing the canvas focus target. */
internal suspend fun observeCanvasWindowFocus(
    windowInfo: WindowInfo,
    activeGesture: () -> CanvasGestureMachine?,
    onFocusLost: () -> Unit,
) {
    var wasFocused = false
    snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
        if (!focused) {
            val gesture = activeGesture()
            gesture?.cancel()
            if (wasFocused || gesture != null) onFocusLost()
        }
        wasFocused = focused
    }
}
