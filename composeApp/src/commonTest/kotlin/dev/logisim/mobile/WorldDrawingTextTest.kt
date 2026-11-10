package dev.logisim.mobile

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldDrawingTextTest {
    @Test
    fun circuitFontKeepsItsWorldSizeAcrossScreenDensityAndAccessibilityFontScale() {
        for (density in listOf(1f, 2f, 3f)) for (fontScale in listOf(1f, 1.6f, 2f)) {
            with(Density(density, fontScale)) {
                assertEquals(12f, worldTextSize(12f).toPx(), 0.001f)
            }
        }
    }
}
