package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-02: dp-based zoom (C48), the logarithmic zoom track (C22), the header measurements (R0708-1/2) and C30 labels. */
class EditorHeaderZoomTest {
    @Test
    fun zoomPercentCountsDensityIndependentPixels() {
        assertEquals(100f, zoomPercentOf(scale = 3f, density = 3f))
        assertEquals(100f, zoomPercentOf(scale = 1f, density = 1f))
        assertEquals(50f, zoomPercentOf(scale = 1.375f, density = 2.75f))
    }

    @Test
    fun theSliderTrackIsLogarithmicFrom5To1200Percent() {
        assertEquals(5f, zoomSliderPercent(0f), 0.001f)
        assertEquals(1200f, zoomSliderPercent(1f), 0.01f)
        assertEquals(0f, zoomSliderPosition(5f))
        assertEquals(1f, zoomSliderPosition(1200f))
        // Each doubling takes the same length, and the middle is the geometric mean.
        assertEquals(sqrt(5.0 * 1200.0).toFloat(), zoomSliderPercent(0.5f), 0.01f)
        val step = zoomSliderPosition(200f) - zoomSliderPosition(100f)
        assertEquals(step, zoomSliderPosition(100f) - zoomSliderPosition(50f), 0.0001f)
        // 100% sits near the middle of the track rather than in its first tenth.
        assertTrue(zoomSliderPosition(100f) in 0.5f..0.6f, "${zoomSliderPosition(100f)}")
        for (percent in listOf(5f, 50f, 100f, 200f, 1000f, 1200f)) {
            assertEquals(percent, zoomSliderPercent(zoomSliderPosition(percent)), percent * 0.001f)
        }
        // Out of range values stay on the track.
        assertEquals(0f, zoomSliderPosition(1f))
        assertEquals(1f, zoomSliderPosition(5000f))
        assertEquals(0f, zoomSliderPosition(Float.NaN))
    }

    @Test
    fun setZoomAcceptsTheRangeThePinchReaches() {
        assertEquals(5f, parseZoomPercent("5"))
        assertEquals(1200f, parseZoomPercent("1200%"))
        assertNull(parseZoomPercent("4.9"))
        assertNull(parseZoomPercent("1201"))
    }

    @Test
    fun theCeilingAndFitScaleWithTheDensity() {
        val anchor = Offset(100f, 100f)
        assertEquals(12f, CircuitViewport().zoomedTo(1000f, anchor).scale)
        assertEquals(36f, CircuitViewport().zoomedTo(1000f, anchor, maxScale = MaxZoomPerDp * 3f).scale)
        assertEquals(33f, CircuitViewport(3f).zoomed(11f, anchor, maxScale = MaxZoomPerDp * 3f).scale)
        // A single AND gate on a 3x phone (390 x 844 dp): Fit used to stop at 4 px, a third of the desktop size.
        val and = CircuitDocument(components = listOf(CircuitComponent("and", ComponentKind.AND, GridPoint(100, 100))))
        val size = IntSize(1170, 2532)
        val old = fittedViewport(and, size, 72f, 0f)
        val dense = fittedViewport(and, size, 72f, 0f, maxScale = FitZoomPerDp * 3f)
        assertEquals(4f, old.scale)
        assertTrue(dense.scale > 4f, "${dense.scale}")
        assertTrue(dense.scale <= 12f)
    }

    @Test
    fun theEditorZoomsInDpAndKeepsItsCeilingInDp() {
        val state = EditorState(null)
        state.updateViewport(CanvasViewportState(CircuitViewport(1f), IntSize(1170, 2532), true, true))
        state.canvasDensity = 3f
        state.zoomToPercent(100f)
        assertEquals(3f, state.viewport.view.scale)
        assertEquals(100f, state.zoomPercent)
        state.zoomBy(1000f)
        assertEquals(36f, state.viewport.view.scale)
        assertEquals(1200f, state.zoomPercent)
        assertFalse(state.viewport.fitted)
    }

    @Test
    fun theSimulationBarFloorRoundsEachPartAsTheRowDoes() {
        assertEquals(SimulationControlBarMinWidth.value.toInt(), simulationControlBarMinWidthPx(Density(1f)))
        // 4 x 132 for the buttons, 11 + 3 + 11 for the divider, 4 x 6 for the gaps and 2 x 6 for the padding.
        assertEquals(589, simulationControlBarMinWidthPx(Density(2.75f)))
        assertTrue(simulationControlBarMinWidthPx(Density(2.75f)) > with(Density(2.75f)) { SimulationControlBarMinWidth.roundToPx() })
    }

    @Test
    fun theMenuBarFoldsWhenItDoesNotFitBesideTheControls() {
        // 1024 dp with the simulation controls in the header: the 100% English bar fits, a 200% one does not.
        assertTrue(headerFitsMenuBar(1024.dp, 353.dp, 2, 1, simulationInHeader = true, counterMin = 60.dp))
        assertFalse(headerFitsMenuBar(1024.dp, 750.dp, 2, 1, simulationInHeader = true, counterMin = 120.dp))
        // Without them the title keeps two touch targets.
        assertTrue(headerFitsMenuBar(720.dp, 400.dp, 2, 0, simulationInHeader = false, counterMin = 0.dp))
        assertFalse(headerFitsMenuBar(720.dp, 700.dp, 2, 0, simulationInHeader = false, counterMin = 0.dp))
    }

    @Test
    fun theTitleReserveCountsThePickerChevron() {
        // The title's 48 dp target plus the 12 dp chevron and its 4 dp gap, so the name keeps 32 dp beside its padding.
        assertEquals(64.dp, HeaderTitleMinWidth)
        // Explorer, two trailing buttons and a divider with their 2 dp gaps take 171 dp; a bar that leaves the
        // simulation floor, a 60 dp counter and 48 dp, but not the chevron's 16 dp too, folds into the compact menu.
        val fixed = 171.dp
        val exact = 1024.dp - fixed - SimulationControlBarMinWidth - 60.dp - HeaderTitleMinWidth
        assertTrue(headerFitsMenuBar(1024.dp, exact, 2, 1, simulationInHeader = true, counterMin = 60.dp))
        assertFalse(headerFitsMenuBar(1024.dp, exact + 1.dp, 2, 1, simulationInHeader = true, counterMin = 60.dp))
        assertFalse(headerFitsMenuBar(1024.dp, exact + 16.dp, 2, 1, simulationInHeader = true, counterMin = 60.dp))
    }

    @Test
    fun undoAndRedoNameTheirActionAsEvolutionDoes() {
        val strings = Localizer(AppLanguage.ENGLISH)
        val action = message("history_add_component", "name" to message("component_and"))
        val labels = editorHistoryLabels(strings, action, null)
        assertEquals("Undo " + action.resolve(AppLanguage.ENGLISH), labels.undoLabel)
        assertEquals("Undo, " + action.resolve(AppLanguage.ENGLISH), labels.undoDescription)
        assertEquals(message("edit_cant_redo").resolve(AppLanguage.ENGLISH), labels.redoLabel)
        // With nothing to redo, assistive technology hears the plain name with the disabled state.
        assertEquals(message("action_redo").resolve(AppLanguage.ENGLISH), labels.redoDescription)
        val portuguese = editorHistoryLabels(Localizer(AppLanguage.PORTUGUESE), null, action)
        assertEquals("Refazer " + action.resolve(AppLanguage.PORTUGUESE), portuguese.redoLabel)
        assertEquals(message("edit_cant_undo").resolve(AppLanguage.PORTUGUESE), portuguese.undoLabel)
    }
}
