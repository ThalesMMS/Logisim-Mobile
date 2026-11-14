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

/**
 * The simulation controls in the order of Evolution's header (UnifiedHeaderBar's simPill: play, step, tick half,
 * tick full, frequency), then Mobile's auto-tick toggle and, set apart, Reset.
 */
internal enum class SimulationControl { RUN, EVENT, TICK_HALF, TICK_FULL, FREQUENCY, AUTO_TICK, RESET }

/** Run, the half-cycle tick and Reset stay on the bar at every width; the others join it in this order while they fit. */
internal val simulationControlPriority: List<SimulationControl> = listOf(
    SimulationControl.RUN, SimulationControl.TICK_HALF, SimulationControl.RESET,
    SimulationControl.FREQUENCY, SimulationControl.TICK_FULL, SimulationControl.EVENT, SimulationControl.AUTO_TICK,
)
private val requiredSimulationControls = simulationControlPriority.take(3).toSet()

/**
 * Sizes in dp of the simulation bar's parts. Glyph buttons keep the 48 dp touch target at any font scale; only the
 * frequency chip grows with the text, and its width is fixed for the font so a new rate never moves other controls.
 */
internal data class SimulationBarMetrics(
    val frequency: Float,
    val button: Float = 48f,
    val separator: Float = 9f,
    val gap: Float = 2f,
    val more: Float = 48f,
)

/**
 * The controls a simulation bar [available] dp wide shows, in bar order. Whatever stays out goes to one overflow
 * menu, which then takes room too. The answer depends on the width and font only, never on the run state, the tick
 * count or the chosen rate, so using a control never reflows the bar.
 */
internal fun simulationBarControls(available: Float, metrics: SimulationBarMetrics): List<SimulationControl> {
    val all = SimulationControl.entries
    if (metrics.total(all, more = false) <= available) return all
    val shown = requiredSimulationControls.toMutableSet()
    for (control in simulationControlPriority) {
        if (control !in shown && metrics.total(shown + control, more = true) <= available) shown += control
    }
    return all.filter { it in shown }
}

/**
 * The narrowest content a simulation bar has: Run, the half-cycle tick, the divider, Reset and the overflow button,
 * each at its full touch target. A parent that gives the bar less squeezes those buttons below 48 dp.
 */
internal fun simulationBarMinimumWidth(metrics: SimulationBarMetrics): Float =
    metrics.total(requiredSimulationControls, more = true)

private fun SimulationBarMetrics.total(shown: Collection<SimulationControl>, more: Boolean): Float {
    val widths = shown.map { if (it == SimulationControl.FREQUENCY) frequency else button } +
        listOfNotNull(separator.takeIf { SimulationControl.RESET in shown && shown.size > 1 }, this.more.takeIf { more })
    return widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
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
