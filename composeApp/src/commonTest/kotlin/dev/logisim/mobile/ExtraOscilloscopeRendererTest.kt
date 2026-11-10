package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.ExtraOscilloscopeState
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtraOscilloscopeRendererTest {
    private val low = LogicValue.LOW
    private val high = LogicValue.HIGH
    private fun component(vararg attributes: Pair<String, String>) = CircuitComponent(
        "scope", ExtraIoKinds.Oscilloscope, GridPoint(100, 200),
        attributes = mapOf("inputs" to "1", "nState" to "4") + attributes,
    )

    @Test
    fun firstCapturedHalfCycleStartsAtTheLeftOfTheDisplay() {
        val state = ExtraOscilloscopeState(1, 4, listOf(listOf(high, low)))
        val plot = extraOscilloscopePlot(component(), state)
        assertEquals(Rect(100f, 158f, 255f, 250f), plot.bounds)
        assertEquals(listOf(ExtraScopeLine(Offset(110f, 180f), Offset(125f, 180f))), plot.rows[0].lines)
        assertEquals(listOf(ExtraScopeLine(Offset(110f, 230f), Offset(125f, 230f))), plot.rows[1].lines)
        assertEquals(listOf(ExtraScopeClockLabel(1, Offset(117f, 173f))), plot.clockLabels)
    }

    @Test
    fun frontLineSettingSelectsRisingFallingOrBothEdges() {
        val samples = listOf(listOf(low, low), listOf(high, low), listOf(low, high), listOf(high, high))
        val state = ExtraOscilloscopeState(1, 4, samples)
        assertEquals(listOf(125f, 155f), extraOscilloscopePlot(component(), state).edges)
        assertEquals(listOf(140f), extraOscilloscopePlot(component("frontlines" to "falling"), state).edges)
        assertEquals(listOf(125f, 140f, 155f), extraOscilloscopePlot(component("frontlines" to "both"), state).edges)
        val hidden = extraOscilloscopePlot(component("frontlines" to "no"), state)
        assertTrue(hidden.edges.isEmpty())
        assertTrue(hidden.clockLabels.isEmpty())
    }

    @Test
    fun hidingTheClockMovesTheFirstInputIntoTheTopRow() {
        val state = ExtraOscilloscopeState(1, 4, listOf(listOf(high, high)))
        val plot = extraOscilloscopePlot(component("showclock" to "false"), state)
        assertEquals(Rect(100f, 190f, 255f, 250f), plot.bounds)
        assertEquals(1, plot.rows.size)
        assertEquals(listOf(ExtraScopeLine(Offset(110f, 210f), Offset(125f, 210f))), plot.rows.single().lines)
        assertTrue(plot.clockLabels.isEmpty())
    }

    @Test
    fun unknownCellsBreakWaveformsInsteadOfDrawingAFalseLogicLevel() {
        val state = ExtraOscilloscopeState(1, 4, listOf(listOf(high, low), listOf(low, LogicValue.UNKNOWN), listOf(high, high)))
        val lines = extraOscilloscopePlot(component(), state).rows[1].lines
        assertEquals(listOf(
            ExtraScopeLine(Offset(110f, 230f), Offset(125f, 230f)),
            ExtraScopeLine(Offset(140f, 210f), Offset(155f, 210f)),
        ), lines)
    }

    @Test
    fun clockNumbersWrapFromNinetyNineToOne() {
        val samples = List(8) { listOf(if (it % 2 == 0) high else low, low) }
        val state = ExtraOscilloscopeState(1, 4, samples, clockNumber = 1)
        assertEquals(listOf(97, 98, 99, 1), extraOscilloscopePlot(component(), state).clockLabels.map { it.number })
    }

    @Test
    fun importedColorsUseDesktopRgbaOrder() {
        assertEquals(0xff00d0d0.toInt(), extraScopeColor("#00d0d0").toArgb())
        assertEquals(0x8000d0d0.toInt(), extraScopeColor("#00d0d080").toArgb())
    }
}
