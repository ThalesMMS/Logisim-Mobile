package dev.logisim.mobile

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Tool fields open the right soft keyboard and never autocorrect identifiers or hexadecimal values (#84). */
class FieldKeyboardsTest {
    @Test
    fun numbersUseTheDigitPadAndFinishWithDone() {
        assertEquals(KeyboardType.Number, FieldKeyboards.Number.keyboardType)
        assertEquals(ImeAction.Done, FieldKeyboards.Number.imeAction)
    }

    @Test
    fun hexadecimalValuesAreUpperCaseAsciiWithoutAutocorrect() {
        assertEquals(KeyboardType.Ascii, FieldKeyboards.Hex.keyboardType)
        assertEquals(KeyboardCapitalization.Characters, FieldKeyboards.Hex.capitalization)
        assertFalse(FieldKeyboards.Hex.autoCorrectEnabled == true)
    }

    @Test
    fun codeKeepsCaseAndIsNotAutocorrected() {
        assertEquals(KeyboardType.Ascii, FieldKeyboards.Code.keyboardType)
        assertEquals(KeyboardCapitalization.None, FieldKeyboards.Code.capitalization)
        assertFalse(FieldKeyboards.Code.autoCorrectEnabled == true)
        // No IME action is forced, so multi-line code keeps the return key for new lines.
        assertEquals(ImeAction.Unspecified, FieldKeyboards.Code.imeAction)
    }

    @Test
    fun addressesUseTheUriKeyboard() {
        assertEquals(KeyboardType.Uri, FieldKeyboards.Uri.keyboardType)
        assertFalse(FieldKeyboards.Uri.autoCorrectEnabled == true)
    }
}
