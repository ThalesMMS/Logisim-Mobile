package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorSheetScrollGuardTest {
    private val leftover = Offset(0f, 480f)

    @Test fun accessibilityScrollPastTheTopOfAListDoesNotDragTheSheet() {
        // TalkBack's scroll backward reaches the list as UserInput with no pointer down. The guard
        // consumes what the list leaves, so the sheet stays expanded and the IME cannot settle it
        // to Hidden afterwards (#309).
        val guard = EditorSheetScrollGuard()
        assertEquals(leftover, guard.onPostScroll(Offset.Zero, leftover, NestedScrollSource.UserInput))
    }

    @Test fun fingerDragsStillReachTheSheet() {
        val guard = EditorSheetScrollGuard().apply { pointerDown = true }
        assertEquals(Offset.Zero, guard.onPostScroll(Offset.Zero, leftover, NestedScrollSource.UserInput))
    }

    @Test fun flingsAndProgrammaticScrollsAreLeftToTheSheet() {
        val guard = EditorSheetScrollGuard()
        assertEquals(Offset.Zero, guard.onPostScroll(Offset.Zero, leftover, NestedScrollSource.SideEffect))
    }
}
