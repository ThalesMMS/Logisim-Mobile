package dev.logisim.mobile

import androidx.compose.runtime.Composable

/**
 * Moves the screen reader's focus to the node tagged with a test tag in the window that hosts the
 * composition where it was remembered. Returning input focus is not enough on Android: when a
 * dialog or sheet window closes, TalkBack puts its own focus on the first node of the window that
 * regains focus (a drag handle or the first session tab) instead of the control that opened it
 * (#322). [moveTo] waits for that window to regain focus, then requests accessibility focus on
 * the node and keeps it there while the screen reader settles. It returns whether the node holds
 * the screen reader's focus; without an active screen reader it does nothing and returns false.
 */
internal fun interface AccessibilityFocusReturn {
    suspend fun moveTo(tag: String): Boolean
}

@Composable
internal expect fun rememberAccessibilityFocusReturn(): AccessibilityFocusReturn
