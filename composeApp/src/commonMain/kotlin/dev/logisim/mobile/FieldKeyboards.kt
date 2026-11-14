package dev.logisim.mobile

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Soft keyboards for tool fields. Numbers get the digit pad; hexadecimal values and code keep identifiers such as
 * `nand`, `std_logic` or `0xFF` intact (no autocorrect, suggestions or smart punctuation).
 */
internal object FieldKeyboards {
    /** Unsigned decimal counts such as the HDL sample advance or a register index. */
    val Number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)

    /** Hexadecimal words and bytes; `hex()` accepts upper case. */
    val Hex = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters,
        autoCorrectEnabled = false)

    /** Source code, JSON and file names. Multi-line fields keep the return key for new lines. */
    val Code = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false)

    /** Host addresses such as the companion URL. */
    val Uri = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
}
