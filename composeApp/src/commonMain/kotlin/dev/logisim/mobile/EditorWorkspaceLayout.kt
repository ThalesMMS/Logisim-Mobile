package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val EditorSidebarWidth = 264.dp

/**
 * Keeps the canvas and sidebar in their composition slots as the toolbar moves. A horizontal
 * toolbar spans the whole window, as in Evolution; a vertical one is a narrow rail beside the canvas.
 */
@Composable
internal fun EditorWorkspaceLayout(
    placement: EditorToolbarPlacement,
    dividerColor: Color,
    modifier: Modifier = Modifier,
    sidebar: (@Composable () -> Unit)? = null,
    toolbar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = {
        Box { sidebar?.invoke() }
        Box { toolbar() }
        Box(Modifier.background(dividerColor))
        Box(Modifier.background(dividerColor))
        Box { content() }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val vertical = placement.orientation == EditorToolbarOrientation.VERTICAL
        val hidden = placement == EditorToolbarPlacement.HIDDEN
        val toolbarPanel = measurables[1].measure(when {
            hidden -> Constraints.fixed(0, 0)
            vertical -> Constraints(maxWidth = minOf(64.dp.roundToPx(), width), minHeight = height, maxHeight = height)
            else -> Constraints(minWidth = width, maxWidth = width, maxHeight = height)
        })
        val line = 1.dp.roundToPx()
        val toolbarDivider = if (hidden) 0 else line
        val railWidth = if (vertical) toolbarPanel.width + toolbarDivider else 0
        val barHeight = if (!vertical && !hidden) toolbarPanel.height + toolbarDivider else 0
        val bodyHeight = (height - barHeight).coerceAtLeast(0)
        val sidebarWidth = if (sidebar != null) minOf(EditorSidebarWidth.roundToPx(), (width - railWidth) / 2) else 0
        val sidebarDivider = if (sidebar != null) line else 0
        val sidebarPanel = measurables[0].measure(Constraints.fixed(sidebarWidth, bodyHeight))
        val divider = measurables[2].measure(if (vertical) Constraints.fixed(toolbarDivider, height) else Constraints.fixed(width, toolbarDivider))
        val sidebarLine = measurables[3].measure(Constraints.fixed(sidebarDivider, bodyHeight))
        val canvasWidth = (width - railWidth - sidebarWidth - sidebarDivider).coerceAtLeast(0)
        val canvas = measurables[4].measure(Constraints.fixed(canvasWidth, bodyHeight))

        val relativePlacement = if (layoutDirection == LayoutDirection.Rtl) when (placement) {
            EditorToolbarPlacement.LEFT -> EditorToolbarPlacement.RIGHT
            EditorToolbarPlacement.RIGHT -> EditorToolbarPlacement.LEFT
            else -> placement
        } else placement
        layout(width, height) {
            val bodyY = if (relativePlacement == EditorToolbarPlacement.TOP) barHeight else 0
            // LEFT docks the rail at the window edge; DOWN_MIDDLE docks it between the sidebar and the canvas.
            val railX = when (relativePlacement) {
                EditorToolbarPlacement.LEFT -> 0
                EditorToolbarPlacement.DOWN_MIDDLE -> sidebarWidth + sidebarDivider
                EditorToolbarPlacement.RIGHT -> width - railWidth
                else -> 0
            }
            val sidebarX = if (relativePlacement == EditorToolbarPlacement.LEFT) railWidth else 0
            sidebarPanel.placeRelative(sidebarX, bodyY)
            sidebarLine.placeRelative(sidebarX + sidebarWidth, bodyY)
            val canvasX = sidebarWidth + sidebarDivider + when (relativePlacement) {
                EditorToolbarPlacement.LEFT, EditorToolbarPlacement.DOWN_MIDDLE -> railWidth
                else -> 0
            }
            canvas.placeRelative(canvasX, bodyY)
            when (relativePlacement) {
                EditorToolbarPlacement.TOP -> {
                    toolbarPanel.placeRelative(0, 0)
                    divider.placeRelative(0, toolbarPanel.height)
                }
                EditorToolbarPlacement.BOTTOM -> {
                    divider.placeRelative(0, bodyHeight)
                    toolbarPanel.placeRelative(0, bodyHeight + toolbarDivider)
                }
                EditorToolbarPlacement.LEFT, EditorToolbarPlacement.DOWN_MIDDLE -> {
                    toolbarPanel.placeRelative(railX, 0)
                    divider.placeRelative(railX + toolbarPanel.width, 0)
                }
                EditorToolbarPlacement.RIGHT -> {
                    divider.placeRelative(railX, 0)
                    toolbarPanel.placeRelative(railX + toolbarDivider, 0)
                }
                EditorToolbarPlacement.HIDDEN -> Unit
            }
        }
    }
}
