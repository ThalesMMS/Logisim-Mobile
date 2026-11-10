package dev.logisim.mobile

internal enum class EditorToolbarOrientation { HORIZONTAL, VERTICAL }

internal val EditorToolbarPlacement.orientation: EditorToolbarOrientation
    get() = when (this) {
        EditorToolbarPlacement.LEFT, EditorToolbarPlacement.RIGHT, EditorToolbarPlacement.DOWN_MIDDLE ->
            EditorToolbarOrientation.VERTICAL
        else -> EditorToolbarOrientation.HORIZONTAL
    }

/**
 * The placement used for the window's current size. Without a sidebar there is nothing to dock
 * between, so DOWN_MIDDLE becomes LEFT. On short landscape windows the default top toolbar moves
 * to a side rail so that the circuit keeps most of the height.
 */
internal fun EditorToolbarPlacement.effectivePlacement(
    hasSidebar: Boolean,
    hasProjectToolbar: Boolean,
    shortLandscape: Boolean = false,
): EditorToolbarPlacement = when {
    this == EditorToolbarPlacement.DOWN_MIDDLE && !hasSidebar -> EditorToolbarPlacement.LEFT
    this == EditorToolbarPlacement.HIDDEN && !hasProjectToolbar -> EditorToolbarPlacement.TOP
    this == EditorToolbarPlacement.TOP && shortLandscape -> EditorToolbarPlacement.LEFT
    else -> this
}

/** Where the run, step, tick and reset controls go for a window size and toolbar placement. */
internal enum class SimulationControlsPlacement { TOOLBAR, HEADER, BOTTOM_BAR }

internal fun simulationControlsPlacement(
    placement: EditorToolbarPlacement,
    narrow: Boolean,
    showHeaderBar: Boolean = true,
): SimulationControlsPlacement = when {
    narrow -> SimulationControlsPlacement.BOTTOM_BAR
    placement.orientation == EditorToolbarOrientation.VERTICAL || placement == EditorToolbarPlacement.HIDDEN ->
        if (showHeaderBar) SimulationControlsPlacement.HEADER else SimulationControlsPlacement.BOTTOM_BAR
    else -> SimulationControlsPlacement.TOOLBAR
}

/** Widths are the buttons' measured pixel widths, already bounded by their parent. */
internal fun toolbarVisibleActionCount(
    widths: List<Int>,
    availableWidth: Int,
    overflowWidth: Int,
    spacing: Int,
): Int {
    if (widths.isEmpty()) return 0
    val available = availableWidth.toLong()
    val gap = spacing.toLong()
    val allActionsWidth = widths.sumOf { it.toLong() } + gap * (widths.size - 1)
    if (allActionsWidth <= available) return widths.size

    var occupied = overflowWidth.toLong()
    var visible = 0
    for (width in widths) {
        val withNextAction = occupied + gap + width
        if (withNextAction > available) break
        occupied = withNextAction
        visible++
    }
    return visible
}
