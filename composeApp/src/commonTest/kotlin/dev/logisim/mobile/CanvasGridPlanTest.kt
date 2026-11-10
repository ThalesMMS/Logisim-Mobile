package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasGridPlanTest {
    @Test
    fun minimumZoomKeepsFiniteGridSpacingWithoutIntegerOverflow() {
        val plan = assertNotNull(canvasGridPlan(CircuitViewport(1e-9f, Offset(-230f, 80f)), IntSize(1080, 2400), 3f))
        assertTrue(plan.spacing.isFinite() && plan.spacing >= 21f)
        assertTrue(plan.columns.toLong() * plan.rows <= 16_000)
        assertTrue(plan.first.x in 0f..plan.spacing && plan.first.y in 0f..plan.spacing)
    }

    @Test
    fun gridPhaseMatchesTheWorldGridAfterPositiveAndNegativePanning() {
        val plan = assertNotNull(canvasGridPlan(CircuitViewport(1f, Offset(5f, -3f)), IntSize(100, 80), 1f))
        assertEquals(10f, plan.spacing)
        assertEquals(Offset(5f, 7f), plan.first)
        assertEquals(10, plan.columns)
        assertEquals(8, plan.rows)
    }

    @Test
    fun aLargeDisplayStillBoundsTheNumberOfDotsPerFrame() {
        val plan = assertNotNull(canvasGridPlan(CircuitViewport(), IntSize(12_000, 8_000), 1f))
        assertTrue(plan.columns.toLong() * plan.rows <= 16_000)
        assertTrue(plan.spacing >= 10f)
    }
}
