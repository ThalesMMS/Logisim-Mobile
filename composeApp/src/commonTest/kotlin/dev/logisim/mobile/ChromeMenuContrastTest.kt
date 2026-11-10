package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Menu-bar, compact, context and field menus read their colors from the light/dark chrome scheme. */
class ChromeMenuContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun darkMenusUseADarkContainerWithReadableItems() {
        val scheme = logisimColorScheme(dark = true)
        // ChromeMenuBar / ChromeCompactMenu / canvas context menu use surface; other dropdowns surfaceContainer.
        for (container in listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)) {
            assertTrue(container.luminance() < 0.1f, "dark menu container $container must be dark")
            assertTrue(contrast(scheme.onSurface, container) >= 4.5f, "item text on $container: ${contrast(scheme.onSurface, container)}")
            assertTrue(contrast(ChromePalette.Dark.ink, container) >= 4.5f, "icon ink on $container")
            // Disabled items stay visibly dimmer than enabled ones but legible against the popup.
            val disabled = scheme.onSurface.copy(alpha = 0.38f).compositeOver(container)
            assertTrue(contrast(disabled, container) in 1.8f..contrast(scheme.onSurface, container))
        }
        assertEquals(ChromePalette.Dark.surface, scheme.surface)
        assertEquals(ChromePalette.Dark.ink, scheme.onSurface)
    }

    @Test
    fun lightMenusKeepTheirWhitePopup() {
        val scheme = logisimColorScheme(dark = false)
        assertEquals(Color.White, scheme.surface)
        for (container in listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)) {
            assertTrue(contrast(scheme.onSurface, container) >= 4.5f, "item text on $container")
        }
    }

    /** The original bug: dark-mode ink on the old hard-coded white popup. */
    @Test
    fun darkInkOnAWhitePopupWasUnreadable() {
        assertTrue(contrast(ChromePalette.Dark.ink, Color.White) < 1.5f)
    }
}
