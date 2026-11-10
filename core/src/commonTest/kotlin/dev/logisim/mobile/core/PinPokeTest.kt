package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Evolution d58a61e0 `Pin.PinPoker.handleBitPress` sequences, as printed by scripts/oracle/base-tools (#345). */
class PinPokeTest {
    private fun sequence(start: LogicVector, radix: String, tristate: Boolean, bits: List<Int>): List<String> {
        var value = start
        return listOf(value.display.replace('?', 'U')) + bits.map { bit ->
            value = PinPoke.poke(value, bit, radix, tristate)
            value.display.replace('?', 'U')
        }
    }

    @Test
    fun pokesMatchEvolutionPinPoker() {
        assertEquals(listOf("0", "1", "0", "1", "0"), sequence(LogicVector.low(1), "2", false, listOf(0, 0, 0, 0)))
        assertEquals(listOf("U", "0", "1", "U", "0"), sequence(LogicVector.unknown(1), "2", true, listOf(0, 0, 0, 0)))
        assertEquals(listOf("0000", "0001", "0000", "0100", "0000", "1000"),
            sequence(LogicVector.low(4), "2", false, listOf(0, 0, 2, 2, 3)))
        assertEquals(listOf("00000000", "00000001", "00000010", "00010010", "00010011"),
            sequence(LogicVector.low(8), "16", false, listOf(0, 0, 4, 0)))
        assertEquals(listOf("UUU", "UU0", "UU1", "UUU", "U0U"), sequence(LogicVector.unknown(3), "2", true, listOf(0, 0, 0, 1)))
    }

    @Test
    fun hexDigitWrapsAndUndefinedBitsBecomeOnesOnTwoStatePins() {
        assertEquals("00000000", PinPoke.poke(LogicVector.of(8, 0x0FuL), 0, "16", false).display)
        // A two-state pin normalizes U to 1 first, so an all-U digit is "ones" and clears.
        assertEquals("0000", PinPoke.poke(LogicVector.unknown(4), 0, "16", false).display)
        // Octal digit at the top of a 4-bit pin covers only the remaining bit.
        assertEquals("1000", PinPoke.poke(LogicVector.low(4), 3, "8", false).display)
    }

    @Test
    fun tapTargetsTheBitOrDigitUnderThePointer() {
        val registry = ComponentCatalog.registry
        fun pin(width: Int, radix: String, appearance: String = "classic") = registry.create(ComponentKind.INPUT, "p",
            GridPoint(100, 400), mapOf("width" to width.toString(), "radix" to radix, "appearance" to appearance))
        // Classic hex pin (80,390)-(100,410): digit 0 at the right, digit 1 to its left.
        assertEquals(0, PinPoke.target(pin(8, "16"), 95, 400, registry))
        assertEquals(4, PinPoke.target(pin(8, "16"), 88, 400, registry))
        // Classic binary pin, 4 bits (60,390)-(100,410): 10 px per bit from the right.
        assertEquals(0, PinPoke.target(pin(4, "2"), 95, 400, registry))
        assertEquals(2, PinPoke.target(pin(4, "2"), 75, 400, registry))
        assertEquals(3, PinPoke.target(pin(4, "2"), 65, 400, registry))
        assertNull(PinPoke.target(pin(8, "10unsigned"), 90, 400, registry))
        assertEquals(0, PinPoke.target(pin(1, "2"), 95, 400, registry))
    }
}
