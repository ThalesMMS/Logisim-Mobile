package dev.logisim.mobile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal fun Modifier.editorDialogBounds(): Modifier {
    // Read the parent window before AlertDialog creates its separate composition.
    val windowSize = LocalWindowInfo.current.containerSize
    if (windowSize.height <= 0) return this
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
    val availableHeight = with(density) {
        (windowSize.height - insets.getTop(this) - insets.getBottom(this)).coerceAtLeast(0).toDp()
    }
    return heightIn(max = availableHeight)
}
