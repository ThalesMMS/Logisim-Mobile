package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InputValueCodecTest {
    private fun value(text: String, width: Int, radix: String, tristate: Boolean = false): LogicVector =
        assertIs<InputValueParse.Value>(InputValueCodec.parse(text, width, radix, tristate), text).vector

    private fun error(text: String, width: Int, radix: String, tristate: Boolean = false) =
        assertIs<InputValueParse.Invalid>(InputValueCodec.parse(text, width, radix, tristate), text).error

    @Test
    fun signedDecimalReadsAndShowsTwosComplement() {
        assertEquals(LogicVector.of(8, 0xFDuL), value("-3", 8, "10signed"))
        assertEquals("-3", InputValueCodec.format(LogicVector.of(8, 0xFDuL), "10signed"))
        assertEquals("127", InputValueCodec.format(LogicVector.of(8, 0x7FuL), "10signed"))
        assertEquals(LogicVector.of(8, 0x80uL), value("-128", 8, "10signed"))
        assertEquals(LogicVector.of(8, 7uL), value("+7", 8, "10signed"))
    }

    @Test
    fun outOfRangeValuesNameTheRange() {
        val tooSmall = error("-129", 8, "10signed")
        assertEquals("input_value_error_range", tooSmall.key)
        assertEquals("Out of range: enter a value from -128 to 127.", tooSmall.resolve(AppLanguage.ENGLISH))
        assertEquals("Fora do intervalo: digite um valor de -128 a 127.", tooSmall.resolve(AppLanguage.PORTUGUESE))
        assertEquals("input_value_error_range", error("128", 8, "10signed").key)
        assertEquals("Out of range: enter a value from 0 to 255.", error("256", 8, "10unsigned").resolve())
        assertEquals("input_value_error_range", error("-1", 8, "10unsigned").key)
        assertEquals(LogicVector.of(8, 255uL), value("255", 8, "10unsigned"))
        assertEquals("Out of range: enter a value from 0 to FF.", error("1FF", 8, "16").resolve())
        assertEquals("input_value_error_range", error("0x100", 8, "10unsigned").key)
    }

    @Test
    fun floatPinsTakeNumbersAndShowThemBack() {
        assertEquals(LogicVector.of(32, 0x3FC00000uL), value("1.5", 32, "float"))
        assertEquals("1.5", InputValueCodec.format(LogicVector.of(32, 0x3FC00000uL), "float"))
        assertEquals(LogicVector.of(32, 0x3FC00000uL), value("1,5", 32, "float"))
        assertEquals(LogicVector.of(64, (-2.25).toRawBits().toULong()), value("-2.25", 64, "float"))
        assertEquals("-2.25", InputValueCodec.format(LogicVector.of(64, (-2.25).toRawBits().toULong()), "float"))
        assertEquals(LogicVector.of(32, Float.POSITIVE_INFINITY.toRawBits().toULong()), value("inf", 32, "float"))
        assertEquals(LogicVector.of(32, 0x3FC00000uL), value("0x3FC00000", 32, "float"))
        assertEquals("input_value_error_float", error("one", 32, "float").key)
        // Evolution shows NaN for a float radix on another width; the editor reads the integer instead.
        assertEquals("10unsigned", InputValueCodec(12, "float", false).radix)
        assertEquals(LogicVector.of(12, 100uL), value("100", 12, "float"))
    }

    @Test
    fun onlyTriStatePinsAcceptX() {
        assertEquals(LogicVector.unknown(8), value("x", 8, "10signed", tristate = true))
        assertEquals(LogicVector.unknown(8), value("???", 8, "16", tristate = true))
        assertEquals("input_value_error_unknown_simple", error("x", 8, "10signed").key)
        assertEquals("x", InputValueCodec.format(LogicVector.unknown(8), "10signed"))
        val partial = LogicVector.fromMasks(4, 0b0100uL, 0b1010uL, 0uL)
        assertEquals("x1x0", InputValueCodec(4, "2", tristate = true).format(partial))
        assertEquals(partial, value("x1x0", 4, "2", tristate = true))
        assertEquals("0bx1x0", InputValueCodec(4, "16", tristate = true).format(partial))
        assertEquals(partial, value("0bx1x0", 4, "16", tristate = true))
        assertEquals("input_value_error_binary", error("x1x0", 4, "2").key)
    }

    @Test
    fun prefixesAndRadixDigits() {
        assertEquals(LogicVector.of(8, 5uL), value("0b101", 8, "10signed"))
        assertEquals(LogicVector.of(8, 0xFDuL), value("0xfd", 8, "10signed"))
        assertEquals(LogicVector.of(8, 0xFDuL), value("fd", 8, "16"))
        assertEquals("FD", InputValueCodec.format(LogicVector.of(8, 0xFDuL), "16"))
        assertEquals("0FD", InputValueCodec.format(LogicVector.of(12, 0xFDuL), "16"))
        assertEquals(LogicVector.of(8, 255uL), value("377", 8, "8"))
        assertEquals("377", InputValueCodec.format(LogicVector.of(8, 255uL), "8"))
        assertEquals(LogicVector.of(8, 5uL), value("0000_0101", 8, "2"))
        assertEquals("00000101", InputValueCodec.format(LogicVector.of(8, 5uL), "2"))
        assertEquals("input_value_error_octal", error("8", 8, "8").key)
        assertEquals("input_value_error_hexadecimal", error("0xG", 8, "10unsigned").key)
        assertEquals("input_value_error_decimal", error("1.5", 8, "10unsigned").key)
        assertEquals("input_value_error_empty", error("  ", 8, "10unsigned").key)
    }

    @Test
    fun sixtyFourBitBounds() {
        assertEquals(LogicVector.of(64, ULong.MAX_VALUE), value("18446744073709551615", 64, "10unsigned"))
        assertEquals("input_value_error_range", error("18446744073709551616", 64, "10unsigned").key)
        assertEquals("Out of range: enter a value from 0 to 18446744073709551615.",
            error("-1", 64, "10unsigned").resolve())
        assertEquals(LogicVector.of(64, 0x8000000000000000uL), value("-9223372036854775808", 64, "10signed"))
        assertEquals("-9223372036854775808", InputValueCodec.format(LogicVector.of(64, 0x8000000000000000uL), "10signed"))
        assertEquals("input_value_error_range", error("9223372036854775808", 64, "10signed").key)
        assertEquals(LogicVector.of(64, ULong.MAX_VALUE), value("0xFFFFFFFFFFFFFFFF", 64, "16"))
        assertEquals("input_value_error_range", error("0x1FFFFFFFFFFFFFFFF", 64, "16").key)
        assertEquals(LogicVector.of(1, 1uL), value("-1", 1, "10signed"))
        assertEquals("input_value_error_range", error("1", 1, "10signed").key)
    }

    @Test
    fun hintsAndKeyboardsFollowTheRadix() {
        assertEquals("From -128 to 127", InputValueCodec(8, "10signed", false).hint.resolve())
        assertEquals("De 0 a 255, ou x para indefinido", InputValueCodec(8, "10unsigned", true).hint.resolve(AppLanguage.PORTUGUESE))
        assertEquals(InputValueKeyboard.SIGNED, InputValueCodec(8, "10signed", false).keyboard)
        assertEquals(InputValueKeyboard.NUMBER, InputValueCodec(8, "10unsigned", false).keyboard)
        assertEquals(InputValueKeyboard.HEXADECIMAL, InputValueCodec(8, "16", false).keyboard)
        assertEquals(InputValueKeyboard.TEXT, InputValueCodec(8, "10unsigned", true).keyboard)
        assertEquals("Signed decimal", InputValueCodec(8, "10signed", false).radixName.resolve())
        assertEquals("2", InputValueCodec(8, "bogus", false).radix)
    }
}
