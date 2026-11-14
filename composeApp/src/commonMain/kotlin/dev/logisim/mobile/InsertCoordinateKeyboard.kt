package dev.logisim.mobile

import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * The platform keyboard of the Insert dialog's X and Y fields, beyond their number keyboard type: iOS's number pad has
 * neither a Return key (so Next and Done cannot be reached) nor a minus sign, so iOS asks for its numbers and
 * punctuation keyboard instead. Null keeps the platform's number keyboard, which on Android has both.
 */
internal expect val InsertCoordinateImeOptions: PlatformImeOptions?
