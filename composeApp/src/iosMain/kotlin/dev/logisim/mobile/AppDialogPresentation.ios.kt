package dev.logisim.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min
import kotlin.math.roundToInt

private class DialogContentBounds {
    var content: Rect? = null
}

@Composable
internal actual fun Modifier.appDialogViewport(onDismissRequest: () -> Unit, properties: DialogProperties): Modifier {
    val window = LocalWindowInfo.current.containerSize
    if (window.width <= 0 || window.height <= 0) return this
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val zeroInsets = WindowInsets(0, 0, 0, 0)
    val platformInsets = if (properties.usePlatformInsets) WindowInsets.systemBars.union(WindowInsets.displayCutout) else zeroInsets
    val keyboardInsets = if (properties.useSoftwareKeyboardInset) WindowInsets.ime else zeroInsets
    val insets = platformInsets.union(keyboardInsets)
    val left = insets.getLeft(density, direction).coerceIn(0, window.width)
    val right = insets.getRight(density, direction).coerceIn(0, window.width - left)
    val top = insets.getTop(density).coerceIn(0, window.height)
    // UIKit's keyboard frame too: the dialog scene does not always see Compose's IME insets (A1).
    val keyboardOverlap by rememberIosKeyboardOverlap()
    val keyboard = if (properties.useSoftwareKeyboardInset) (keyboardOverlap * density.density).roundToInt() else 0
    val bottom = maxOf(insets.getBottom(density), keyboard).coerceIn(0, window.height - top)
    val safeWidth = window.width - left - right
    val safeHeight = window.height - top - bottom
    val preferredWidth = with(density) {
        val smallestWidth = min(window.width, window.height).toDp()
        when {
            smallestWidth >= 600.dp -> 580.dp
            smallestWidth >= 480.dp -> 440.dp
            else -> 320.dp
        }.roundToPx()
    }
    val contentMaxWidth = if (properties.usePlatformDefaultWidth) min(safeWidth, preferredWidth) else safeWidth
    val hitBounds = remember { DialogContentBounds() }
    val dismiss = rememberUpdatedState(onDismissRequest)

    val outsideDismiss = if (properties.dismissOnClickOutside) Modifier.pointerInput(hitBounds) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val content = hitBounds.content ?: return@awaitEachGesture
            if (content.contains(down.position)) return@awaitEachGesture
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            down.consume()
            val up = waitForUpOrCancellation()
            if (up != null && !content.contains(up.position)) {
                up.consume()
                dismiss.value()
            }
        }
    } else Modifier
    return then(outsideDismiss).layout { measurable, _ ->
        val surface = measurable.measure(Constraints(maxWidth = contentMaxWidth, maxHeight = safeHeight))
        val x = left + (safeWidth - surface.width) / 2
        val y = top + (safeHeight - surface.height) / 2
        hitBounds.content = Rect(x.toFloat(), y.toFloat(), (x + surface.width).toFloat(), (y + surface.height).toFloat())
        // The scene must own the whole viewport before centering its Material3 content.
        // Otherwise an initially unbounded iOS layer can prune the shifted AX descendants.
        layout(window.width, window.height) { surface.place(x, y) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun appDialogProperties(properties: DialogProperties): DialogProperties = DialogProperties(
    dismissOnBackPress = properties.dismissOnBackPress,
    dismissOnClickOutside = properties.dismissOnClickOutside,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    useSoftwareKeyboardInset = false,
    scrimColor = properties.scrimColor,
    animateTransition = properties.animateTransition,
)

internal actual fun editorSheetDialogProperties() = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
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
