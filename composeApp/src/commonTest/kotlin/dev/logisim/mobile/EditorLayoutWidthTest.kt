package dev.logisim.mobile

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorLayoutWidthTest {
    @Test
    fun theSoftwareKeyboardDoesNotDropTheSideExplorer() {
        // Landscape tablet (#319): 1280x800 dp less system bars, with a 320 dp keyboard open.
        assertTrue(editorLayoutIsWide(1280.dp, 752.dp, 0.dp))
        assertTrue(editorLayoutIsWide(1280.dp, 432.dp, 320.dp))
    }

    @Test
    fun shortOrNarrowWindowsStayCompact() {
        assertFalse(editorLayoutIsWide(1280.dp, 432.dp, 0.dp))
        assertFalse(editorLayoutIsWide(700.dp, 1000.dp, 0.dp))
        assertFalse(editorLayoutIsWide(900.dp, 380.dp, 0.dp))
    }
}
