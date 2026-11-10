package dev.logisim.mobile

import dev.logisim.mobile.core.FloatingPointNumbers
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.localization.Messages

enum class WirePokeRadix(val label: String) {
    BINARY(Messages.PreferencesBinary),
    OCTAL(Messages.PreferencesOctal),
    SIGNED_DECIMAL(Messages.PreferencesSignedDecimal),
    UNSIGNED_DECIMAL(Messages.PreferencesUnsignedDecimal),
    HEXADECIMAL(Messages.PreferencesHexadecimal),
    FLOATING_POINT(Messages.PreferencesFloatingPoint);

    companion object {
        internal fun fromSignalRadix(radix: EditorSignalRadix): WirePokeRadix = when (radix) {
            EditorSignalRadix.BINARY -> BINARY
            EditorSignalRadix.HEXADECIMAL -> HEXADECIMAL
            EditorSignalRadix.SIGNED_DECIMAL -> SIGNED_DECIMAL
            EditorSignalRadix.UNSIGNED_DECIMAL -> UNSIGNED_DECIMAL
        }
    }
}

internal fun wirePokeReadings(
    value: LogicVector,
    primary: WirePokeRadix,
    secondary: WirePokeRadix?,
    characters: ValueCharacters = ValueCharacters.Default,
): List<String> =
    buildList {
        add(primary.format(value, characters))
        if (value.width > 1 && secondary != null) add(secondary.format(value, characters))
        if (value.width in FloatingPointNumbers.widths) add(WirePokeRadix.FLOATING_POINT.format(value, characters))
    }

private fun WirePokeRadix.format(value: LogicVector, characters: ValueCharacters): String = when (this) {
    WirePokeRadix.BINARY -> value.display(characters).reversed().chunked(4).joinToString(" ").reversed()
    WirePokeRadix.OCTAL -> value.groupedDigits(3, characters)
    WirePokeRadix.HEXADECIMAL -> value.groupedDigits(4, characters)
    WirePokeRadix.SIGNED_DECIMAL -> value.pokeDecimal(EditorSignalRadix.SIGNED_DECIMAL, characters)
    WirePokeRadix.UNSIGNED_DECIMAL -> value.pokeDecimal(EditorSignalRadix.UNSIGNED_DECIMAL, characters)
    WirePokeRadix.FLOATING_POINT -> {
        val number = FloatingPointNumbers.decode(value)
        if (value.width == 64) number.toString() else number.toFloat().toString()
    }
}

private fun LogicVector.pokeDecimal(radix: EditorSignalRadix, characters: ValueCharacters): String = when {
    isError -> LogicValue.ERROR.display(characters)
    !isFullyDefined -> LogicValue.UNKNOWN.display(characters)
    else -> formatEditorSignal(this, radix, characters)
}

private fun LogicVector.groupedDigits(bitsPerDigit: Int, characters: ValueCharacters): String = buildString {
    for (low in ((width - 1) / bitsPerDigit) * bitsPerDigit downTo 0 step bitsPerDigit) {
        val digit = slice(low, minOf(bitsPerDigit, width - low))
        if (digit.isFullyDefined) {
            append(digit.value.toInt().digitToChar(1 shl bitsPerDigit).lowercaseChar())
        } else {
            // The desktop shows the first nonnumeric bit when reading this digit from the left.
            val undefined = (digit.width - 1 downTo 0).map(digit::bit)
                .first { it == LogicValue.UNKNOWN || it == LogicValue.ERROR }
            append(characters.of(undefined))
        }
    }
}
