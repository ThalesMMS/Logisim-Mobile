package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** W3-05 (C90): a multi-bit JAR pin's hexadecimal value and the bit string the frames carry. */
class JarPinValueTest {
    @Test
    fun hexadecimalDigitsExpandToTheBitsMostSignificantFirst() {
        assertEquals("1011111011101111", jarHexToBits("beef", 16))
        assertEquals("1011111011101111", jarHexToBits("BEEF", 16))
        // Fewer digits are zero-extended; an empty value has no bits.
        assertEquals("00000101", jarHexToBits("5", 8))
        assertNull(jarHexToBits("", 8))
        // A value needing more bits than the pin has, or a character that is not a hex digit, is refused.
        assertEquals("101010", jarHexToBits("2A", 6))
        assertEquals("111111", jarHexToBits("3F", 6))
        assertNull(jarHexToBits("4A", 6))
        assertNull(jarHexToBits("100", 8))
        assertNull(jarHexToBits("G", 8))
        assertNull(jarHexToBits("1?", 8))
        assertEquals(2, jarHexDigits(6))
        assertEquals(4, jarHexDigits(16))
        assertEquals(8, jarHexDigits(32))
        // 32 and 64 bits stay exact.
        assertEquals("1".repeat(32), jarHexToBits("FFFFFFFF", 32))
        assertEquals("1" + "0".repeat(63), jarHexToBits("8000000000000000", 64))
    }

    @Test
    fun onlyCompleteKnownBitsHaveAHexadecimalForm() {
        assertEquals("BEEF", jarBitsToHex("1011111011101111", 16))
        assertEquals("2A", jarBitsToHex("101010", 6))
        assertEquals("05", jarBitsToHex("00000101", 8))
        assertEquals("", jarBitsToHex("10?010", 6))
        assertEquals("", jarBitsToHex("E01010", 6))
        assertEquals("", jarBitsToHex("10101", 6))
        // Round trip for every 6-bit value.
        for (value in 0 until 64) {
            val bits = value.toString(2).padStart(6, '0')
            assertEquals(bits, jarHexToBits(jarBitsToHex(bits, 6), 6))
        }
    }
}
