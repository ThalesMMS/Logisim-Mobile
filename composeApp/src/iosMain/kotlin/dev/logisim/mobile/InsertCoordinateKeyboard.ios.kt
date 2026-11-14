package dev.logisim.mobile

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.input.PlatformImeOptions
import platform.UIKit.UIKeyboardTypeNumbersAndPunctuation

/** The numbers and punctuation keyboard: digits, a minus sign and a Return key that shows Next or Done. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual val InsertCoordinateImeOptions: PlatformImeOptions? = PlatformImeOptions {
    keyboardType(UIKeyboardTypeNumbersAndPunctuation)
}
