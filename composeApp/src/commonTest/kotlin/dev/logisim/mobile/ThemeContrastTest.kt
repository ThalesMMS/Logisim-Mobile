package dev.logisim.mobile

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * C156/C158: every Material slot comes from the chrome palette in both modes, with WCAG AA contrast for text
 * (4.5:1) and 3:1 for the selection and component boundaries, instead of the Material baseline lavender and pink.
 */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun assertContrast(minimum: Double, foreground: Color, background: Color, what: String) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= minimum, "$what: ${(ratio * 100).toInt() / 100.0}:1 is below $minimum:1")
    }

    private val slots: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "primary" to { it.primary }, "onPrimary" to { it.onPrimary }, "primaryContainer" to { it.primaryContainer },
        "onPrimaryContainer" to { it.onPrimaryContainer }, "inversePrimary" to { it.inversePrimary },
        "secondary" to { it.secondary }, "onSecondary" to { it.onSecondary }, "secondaryContainer" to { it.secondaryContainer },
        "onSecondaryContainer" to { it.onSecondaryContainer }, "tertiary" to { it.tertiary }, "onTertiary" to { it.onTertiary },
        "tertiaryContainer" to { it.tertiaryContainer }, "onTertiaryContainer" to { it.onTertiaryContainer },
        "background" to { it.background }, "onBackground" to { it.onBackground }, "surface" to { it.surface },
        "onSurface" to { it.onSurface }, "surfaceVariant" to { it.surfaceVariant }, "onSurfaceVariant" to { it.onSurfaceVariant },
        "surfaceTint" to { it.surfaceTint }, "inverseSurface" to { it.inverseSurface }, "inverseOnSurface" to { it.inverseOnSurface },
        "error" to { it.error }, "onError" to { it.onError }, "errorContainer" to { it.errorContainer },
        "onErrorContainer" to { it.onErrorContainer }, "outline" to { it.outline }, "outlineVariant" to { it.outlineVariant },
        "scrim" to { it.scrim }, "surfaceBright" to { it.surfaceBright }, "surfaceDim" to { it.surfaceDim },
        "surfaceContainer" to { it.surfaceContainer }, "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest }, "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainerLowest" to { it.surfaceContainerLowest }, "primaryFixed" to { it.primaryFixed },
        "primaryFixedDim" to { it.primaryFixedDim }, "onPrimaryFixed" to { it.onPrimaryFixed },
        "onPrimaryFixedVariant" to { it.onPrimaryFixedVariant }, "secondaryFixed" to { it.secondaryFixed },
        "secondaryFixedDim" to { it.secondaryFixedDim }, "onSecondaryFixed" to { it.onSecondaryFixed },
        "onSecondaryFixedVariant" to { it.onSecondaryFixedVariant }, "tertiaryFixed" to { it.tertiaryFixed },
        "tertiaryFixedDim" to { it.tertiaryFixedDim }, "onTertiaryFixed" to { it.onTertiaryFixed },
        "onTertiaryFixedVariant" to { it.onTertiaryFixedVariant },
    )

    /** Material's error reds and the black scrim are kept on purpose; every other slot follows the chrome palette. */
    private val keptBaseline = setOf("error", "onError", "errorContainer", "onErrorContainer", "scrim")

    private fun Color.neutral(): Boolean = red == green && green == blue

    @Test
    fun noMaterialBaselineHueLeaksIntoEitherScheme() {
        for (dark in listOf(false, true)) {
            val scheme = logisimColorScheme(dark)
            val baseline = if (dark) darkColorScheme() else lightColorScheme()
            val leaks = slots.filter { (name, slot) ->
                name !in keptBaseline && slot(scheme) == slot(baseline) && !slot(baseline).neutral()
            }.map { it.first }
            assertEquals(emptyList(), leaks, "Baseline Material colors in the ${if (dark) "dark" else "light"} scheme")
            // The reported lavender dialog, menu, sheet and container colors.
            val lavender = listOf(0xFFECE6F0, 0xFFF3EDF7, 0xFFE8DEF8, 0xFFF7F2FA, 0xFFE7E0EC, 0xFF4A4458, 0xFF49454F,
                0xFF7D5260, 0xFFEFB8C8).map { Color(it) }
            assertEquals(emptyList(), slots.filter { (_, slot) -> slot(scheme) in lavender }.map { it.first }, "dark=$dark")
        }
    }

    @Test
    fun surfacesKeepAaTextAndNoAccentTint() {
        for (dark in listOf(false, true)) {
            val scheme = logisimColorScheme(dark)
            val surfaces = listOf("surface" to scheme.surface, "background" to scheme.background,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest, "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer, "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest, "surfaceVariant" to scheme.surfaceVariant,
                "surfaceBright" to scheme.surfaceBright, "surfaceDim" to scheme.surfaceDim)
            for ((name, surface) in surfaces) {
                assertContrast(4.5, scheme.onSurface, surface, "dark=$dark onSurface on $name")
                assertContrast(4.5, scheme.onSurfaceVariant, surface, "dark=$dark onSurfaceVariant on $name")
            }
            // Dialogs (surfaceContainerHigh), menus (surfaceContainer) and sheets (surfaceContainerLow) are chrome surfaces.
            val palette = if (dark) ChromePalette.Dark else ChromePalette.Light
            assertTrue(scheme.surfaceContainerHigh in setOf(palette.surface, palette.panel), "dark=$dark dialog container")
            assertTrue(scheme.surfaceContainer in setOf(palette.surface, palette.panel), "dark=$dark menu container")
            assertEquals(palette.paper, scheme.surfaceContainerLow, "dark=$dark sheet container")
            // Tonal elevation blends surfaceTint into surfaces; it must not tint sheets and dialogs with the accent.
            assertEquals(scheme.surface, scheme.surfaceTint, "dark=$dark surfaceTint")
        }
    }

    @Test
    fun accentWarningAndInverseColorsKeepAaText() {
        for (dark in listOf(false, true)) {
            val scheme = logisimColorScheme(dark)
            assertContrast(4.5, scheme.onPrimary, scheme.primary, "dark=$dark onPrimary")
            assertContrast(4.5, scheme.onSecondary, scheme.secondary, "dark=$dark onSecondary")
            assertContrast(4.5, scheme.onTertiary, scheme.tertiary, "dark=$dark onTertiary")
            assertContrast(4.5, scheme.onPrimaryContainer, scheme.primaryContainer, "dark=$dark onPrimaryContainer")
            assertContrast(4.5, scheme.onSecondaryContainer, scheme.secondaryContainer, "dark=$dark onSecondaryContainer")
            assertContrast(4.5, scheme.onTertiaryContainer, scheme.tertiaryContainer, "dark=$dark onTertiaryContainer")
            assertContrast(4.5, scheme.inverseOnSurface, scheme.inverseSurface, "dark=$dark inverseOnSurface")
            // Diagnostics show warnings as tertiary text on sheets, dialogs and the plain surface.
            for (surface in listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHigh)) {
                assertContrast(4.5, scheme.tertiary, surface, "dark=$dark warning text")
                assertContrast(3.0, scheme.outline, surface, "dark=$dark text field and switch outline")
            }
            assertContrast(3.0, scheme.outline, scheme.surfaceContainerHighest, "dark=$dark unchecked switch thumb on its track")
        }
    }

    @Test
    fun memoryEditorSelectionHasAThreeToOneCue() {
        for (dark in listOf(false, true)) {
            val scheme = logisimColorScheme(dark)
            // Selected words are secondaryContainer with a primary border; unselected words are surfaceVariant.
            assertNotEquals(scheme.secondaryContainer, scheme.surfaceVariant, "dark=$dark selected and unselected fills")
            assertContrast(3.0, scheme.primary, scheme.surfaceVariant, "dark=$dark selection border against an unselected word")
            assertContrast(3.0, scheme.primary, scheme.secondaryContainer, "dark=$dark selection border against its fill")
            assertContrast(3.0, scheme.primary, scheme.surfaceContainerLow, "dark=$dark selection border against the sheet")
            assertContrast(4.5, scheme.onSurface, scheme.secondaryContainer, "dark=$dark word text when selected")
        }
    }

    @Test
    fun chromeMutedTextAndSelectionCueMeetWcag() {
        for (palette in listOf(ChromePalette.Light, ChromePalette.Dark)) {
            val which = if (palette == ChromePalette.Dark) "dark" else "light"
            for ((name, background) in listOf("bar" to palette.bar, "panel" to palette.panel, "heading" to palette.heading,
                "paper" to palette.paper, "surface" to palette.surface)) {
                assertContrast(4.5, palette.muted, background, "$which muted on $name")
                assertContrast(4.5, palette.ink, background, "$which ink on $name")
            }
            // The armed tool, the selected toolbar button and the active circuit: the accent border or bar is the cue.
            for ((name, background) in listOf("bar" to palette.bar, "panel" to palette.panel, "selected" to palette.selected)) {
                assertContrast(3.0, palette.accent, background, "$which selection accent against $name")
                assertContrast(3.0, palette.selectedBorder, background, "$which selected border against $name")
            }
            assertContrast(4.5, palette.ink, palette.selected, "$which ink on the selected tint")
        }
    }
}
