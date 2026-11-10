package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicVectorTest {
    @Test
    fun integerWidthsKeepAllBitsIncludingUnsigned64BitValues() {
        for (width in listOf(1, 8, 16, 32, 64)) {
            val allOnes = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
            assertEquals(allOnes, LogicVector.of(width, ULong.MAX_VALUE).toULongOrNull())
            assertEquals("1".repeat(width), LogicVector.high(width).display)
            assertEquals("0".repeat(width), LogicVector.low(width).display)
            assertEquals(LogicValue.HIGH, LogicVector.high(width).bit(width - 1))
        }
        assertEquals(9_223_372_036_854_775_808uL, LogicVector.of(64, 1uL shl 63).toULongOrNull())
    }

    @Test
    fun masksNormalizeErrorBeforeUnknownBeforeKnown() {
        val value = LogicVector.fromMasks(8, 0xffuL, 0x0cuL, 0x18uL)
        assertEquals("111EE?11", value.display)
        assertEquals(0x18uL, value.errorMask)
        assertEquals(0x04uL, value.unknownMask)
        assertEquals(0xe3uL, value.value)
        assertNull(value.toULongOrNull())
    }

    @Test
    fun gatesMatchDesktopFourStateTables() {
        val states = listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR)
        val andTable = "000001EE0EEE0EEE"
        val orTable = "01EE1111E1EEE1EE"
        val xorTable = "01EE10EEEEEEEEEE"
        for ((operation, expected) in listOf(
            Pair<(LogicVector, LogicVector) -> LogicVector, String>({ a, b -> a and b }, andTable),
            Pair({ a, b -> a or b }, orTable),
            Pair({ a, b -> a xor b }, xorTable),
        )) {
            val actual = states.flatMap { a -> states.map { b ->
                operation(LogicVector.fromBit(a), LogicVector.fromBit(b)).display
            } }.joinToString("")
            assertEquals(expected, actual)
        }
        assertEquals("10EE", states.joinToString("") { LogicVector.fromBit(it).inverted().display })
    }

    @Test
    fun gatesResolveEachBitIndependently() {
        val a = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR))
        val b = LogicVector.of(4, 0b1010uL)
        assertEquals("E010", (a and b).display)
        assertEquals("1E10", (a or b).display)
        assertEquals("EE00", (a xor b).display)
        assertEquals("EE01", a.inverted().display)
    }

    @Test
    fun resolutionPreservesKnownBitsAndReportsOnlyConflicts() {
        val a = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.HIGH))
        val b = LogicVector.fromBits(listOf(LogicValue.UNKNOWN, LogicValue.HIGH, LogicValue.LOW, LogicValue.LOW))
        assertEquals("E011", LogicVector.resolve(listOf(a, b), 4).display)
        assertEquals("E011", LogicVector.resolve(listOf(b, a), 4).display)
        assertEquals("????", LogicVector.resolve(emptyList(), 4).display)
        assertEquals("11", LogicVector.resolve(listOf(LogicVector.high(2), LogicVector.unknown(2)), 2).display)
    }

    @Test
    fun resolutionOfMixedWidthsDoesNotInventDrivers() {
        assertEquals("1?1", LogicVector.of(1, 1uL).combine(LogicVector.fromBits(listOf(
            LogicValue.UNKNOWN, LogicValue.UNKNOWN, LogicValue.HIGH))).display)
        assertEquals("??1", LogicVector.resolve(listOf(LogicVector.high(1)), 3).display)
    }

    @Test
    fun extensionAndTruncationRespectFillState() {
        val original = LogicVector.of(4, 0xauL)
        assertEquals("00001010", original.resize(8).display)
        assertEquals("11111010", original.resize(8, LogicValue.HIGH).display)
        assertEquals("????1010", original.resize(8, LogicValue.UNKNOWN).display)
        assertEquals("EEEE1010", original.resize(8, LogicValue.ERROR).display)
        assertEquals("10", original.resize(2).display)
        assertEquals(ULong.MAX_VALUE, LogicVector.high(1).resize(64, LogicValue.HIGH).toULongOrNull())
    }

    @Test
    fun slicesConcatenationAndBitReplacementKeepState() {
        val value = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR, LogicValue.LOW))
        assertEquals("E?", value.slice(1, 2).display)
        assertEquals("0E?110", value.concat(LogicVector.of(2, 2uL)).display)
        assertEquals("01?1", value.withBit(2, LogicValue.HIGH).display)
        val full = LogicVector.high(32).concat(LogicVector.low(32))
        assertEquals(0xffff_ffff_0000_0000uL, full.toULongOrNull())
        assertEquals("1".repeat(32), full.slice(32, 32).display)
    }

    @Test
    fun triStateControlMatchesDesktop() {
        val data = LogicVector.of(8, 0xa5uL)
        assertEquals("????????", LogicVector.low(1).controls(data).display)
        assertEquals(data, LogicVector.high(1).controls(data))
        assertEquals(data, LogicVector.unknown(1).controls(data))
        assertEquals("EEEEEEEE", LogicVector.error(1).controls(data).display)
        assertEquals("1?1?", LogicVector.of(4, 0b1010uL).controls(LogicVector.high(4)).display)
        assertEquals("EEEEEEEE", LogicVector.high(2).controls(data).display)
    }

    @Test
    fun invalidWidthsAndBitRangesFailAtBoundary() {
        for (width in listOf(-1, 0, 65)) {
            assertFailsWith<IllegalArgumentException> { LogicVector.low(width) }
        }
        assertFailsWith<IllegalArgumentException> { LogicVector.high(64).concat(LogicVector.low(1)) }
        assertFailsWith<IllegalArgumentException> { LogicVector.high(8).slice(7, 2) }
        assertFailsWith<IllegalArgumentException> { LogicVector.high(8).bit(-1) }
        assertFailsWith<IllegalArgumentException> { LogicVector.high(8).withBit(8, LogicValue.LOW) }
        assertTrue(LogicVector.fromBits(listOf(LogicValue.UNKNOWN)).isUnknown)
    }
}
