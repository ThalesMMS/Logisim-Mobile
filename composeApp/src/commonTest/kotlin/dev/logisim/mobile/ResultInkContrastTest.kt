package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * C62: passing test vector rows and the trace cursor take their colours from the chrome palette, so the dark theme
 * keeps AA text contrast (4.5:1) for a pass and 3:1 for the cursor line, where the fixed 0xFF08792F green and
 * 0xFF8C2999 purple fell to about 2.3:1 and 1.8:1 on the dark panel.
 */
class ResultInkContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun backgrounds(dark: Boolean): List<Pair<String, Color>> {
        val palette = if (dark) ChromePalette.Dark else ChromePalette.Light
        val scheme = logisimColorScheme(dark)
        return listOf("bar" to palette.bar, "panel" to palette.panel, "paper" to palette.paper, "surface" to palette.surface,
            "scheme.surface" to scheme.surface, "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer)
    }

    @Test
    fun passingRowsKeepAaContrastInBothThemes() {
        for (dark in listOf(false, true)) {
            val ink = (if (dark) ChromePalette.Dark else ChromePalette.Light).passInk
            for ((name, background) in backgrounds(dark)) {
                val ratio = contrast(ink, background)
                assertTrue(ratio >= 4.5, "pass ink on $name (dark=$dark): $ratio")
            }
        }
    }

    @Test
    fun theTraceCursorStandsOutInBothThemes() {
        for (dark in listOf(false, true)) {
            val cursor = (if (dark) ChromePalette.Dark else ChromePalette.Light).traceCursor
            for ((name, background) in backgrounds(dark)) {
                val ratio = contrast(cursor, background)
                assertTrue(ratio >= 3.0, "trace cursor on $name (dark=$dark): $ratio")
            }
        }
    }
}
