package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * The window bounds of controls floating over the canvas (the placement strip, the selection bar). A placement drag
 * released over one adds nothing, as AddTool's SHOW_ADD_NO for a release outside the visible drawing area (R06-2).
 */
internal class CanvasOverlayRegions {
    private val regions = mutableMapOf<Any, Rect>()

    fun update(key: Any, bounds: Rect) { regions[key] = bounds }
    fun remove(key: Any) { regions.remove(key) }
    fun contains(windowPosition: Offset): Boolean = regions.values.any { it.contains(windowPosition) }
}

internal val LocalCanvasOverlayRegions = staticCompositionLocalOf<CanvasOverlayRegions?> { null }

/** Marks this control as floating over the canvas, so a placement released on it is not a drop on the circuit. */
@Composable
internal fun Modifier.canvasOverlayRegion(): Modifier {
    val regions = LocalCanvasOverlayRegions.current ?: return this
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.remove(key) } }
    return onGloballyPositioned { regions.update(key, it.boundsInWindow()) }
}
