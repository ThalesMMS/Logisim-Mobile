package dev.logisim.mobile

import androidx.compose.runtime.Composable

// VoiceOver is out of scope; input focus is returned by the callers.
private val noFocusReturn = AccessibilityFocusReturn { false }

@Composable
internal actual fun rememberAccessibilityFocusReturn(): AccessibilityFocusReturn = noFocusReturn
