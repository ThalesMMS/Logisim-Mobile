package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
class ArithmeticLogicTest {
    private fun value(width: Int, number: ULong) = LogicVector.of(width, number)
    private fun evaluate(op: ArithmeticOperation, width: Int, vararg numbers: ULong, mode: String = "unsigned") =
        ArithmeticLogic.evaluate(op, width, numbers.map { value(width, it) }, mode)

    @Test fun allThirteenArithmeticEntriesAreNamed() {
        assertEquals(listOf("Adder", "Subtractor", "Multiplier", "Divider", "Negator", "Exponentiator",
            "SquareRoot", "Absolute", "Comparator", "MinMax", "Shifter", "BitAdder", "BitFinder"),
            ArithmeticOperation.entries.map { it.fileName })
    }

    @Test fun addAndBorrowAtSixtyFourBits() {
        assertEquals(listOf(value(64, 0uL), value(1, 1uL)),
            evaluate(ArithmeticOperation.ADDER, 64, ULong.MAX_VALUE, 1uL, 0uL))
        assertEquals(listOf(value(64, ULong.MAX_VALUE), value(1, 1uL)),
            evaluate(ArithmeticOperation.SUBTRACTOR, 64, 0uL, 1uL, 0uL))
    }

    @Test fun multiplyPreservesBothHalvesAndSignedModes() {
        assertEquals(listOf(value(64, 1uL), value(64, ULong.MAX_VALUE - 1uL)),
            evaluate(ArithmeticOperation.MULTIPLIER, 64, ULong.MAX_VALUE, ULong.MAX_VALUE, 0uL))
        assertEquals(listOf(value(64, ULong.MAX_VALUE - 1uL), value(64, ULong.MAX_VALUE)),
            evaluate(ArithmeticOperation.MULTIPLIER, 64, ULong.MAX_VALUE, 2uL, 0uL, mode = "twosComplement"))
        assertEquals(listOf(value(8, 0xfeuL), value(8, 0xffuL)),
            evaluate(ArithmeticOperation.MULTIPLIER, 8, 0xffuL, 2uL, 0uL, mode = "signedUnsigned"))
    }

    @Test fun divideUsesDoubleWidthDividendAndMatchesDesktopZeroDivisor() {
        assertEquals(listOf(value(64, 0uL), value(64, 1uL)),
            evaluate(ArithmeticOperation.DIVIDER, 64, 1uL, 2uL, 0uL))
        assertEquals(listOf(value(64, 1uL shl 63), value(64, 0uL)),
            evaluate(ArithmeticOperation.DIVIDER, 64, 0uL, 2uL, 1uL))
        assertEquals(listOf(value(64, 42uL), value(64, 0uL)),
            evaluate(ArithmeticOperation.DIVIDER, 64, 42uL, 0uL, 0uL))
        assertEquals(listOf(value(8, 0xfeuL), value(8, 0xffuL)),
            ArithmeticLogic.evaluate(ArithmeticOperation.DIVIDER, 8,
                listOf(value(8, 0xf9uL), value(8, 3uL), LogicVector.unknown(8)), "twosComplement"))
    }

    @Test fun powersAreTruncatedOnlyAfterTheFullDoubleWidthResult() {
        assertEquals(listOf(value(64, 0uL), value(64, 1uL)),
            evaluate(ArithmeticOperation.EXPONENTIATOR, 64, 2uL, 64uL))
        assertEquals(listOf(value(64, ULong.MAX_VALUE), value(64, ULong.MAX_VALUE)),
            evaluate(ArithmeticOperation.EXPONENTIATOR, 64, ULong.MAX_VALUE, 3uL, mode = "twosComplement"))
        assertEquals(listOf(value(8, 0uL), value(8, 0uL)),
            evaluate(ArithmeticOperation.EXPONENTIATOR, 8, 2uL, 0xffuL, mode = "twosComplement"))
    }

    @Test fun squareRootHandlesMaximumUnsignedDoubleWord() {
        assertEquals(listOf(value(64, ULong.MAX_VALUE), value(64, ULong.MAX_VALUE - 1uL)),
            evaluate(ArithmeticOperation.SQUARE_ROOT, 64, ULong.MAX_VALUE, ULong.MAX_VALUE))
        assertEquals(listOf(value(8, 15uL), value(8, 30uL)),
            evaluate(ArithmeticOperation.SQUARE_ROOT, 8, 255uL, 0uL))
    }

    @Test fun absoluteReportsOverflowForTheSmallestNegative() {
        assertEquals(listOf(value(64, 1uL shl 63), value(1, 1uL)),
            evaluate(ArithmeticOperation.ABSOLUTE, 64, 1uL shl 63))
        assertEquals(listOf(value(8, 7uL), value(1, 0uL)),
            evaluate(ArithmeticOperation.ABSOLUTE, 8, 0xf9uL))
    }

    @Test fun comparatorDecidesBeforeUnknownLowerBits() {
        val a = LogicVector.unknown(8).withBit(7, LogicValue.HIGH)
        val b = LogicVector.unknown(8).withBit(7, LogicValue.LOW)
        assertEquals(listOf(value(1, 1uL), value(1, 0uL), value(1, 0uL)),
            ArithmeticLogic.evaluate(ArithmeticOperation.COMPARATOR, 8, listOf(a, b), "unsigned"))
        assertEquals(listOf(value(1, 0uL), value(1, 0uL), value(1, 1uL)),
            ArithmeticLogic.evaluate(ArithmeticOperation.COMPARATOR, 8, listOf(a, b), "twosComplement"))
    }

    @Test fun minMaxUsesSignedOrUnsignedOrdering() {
        assertEquals(listOf(value(64, ULong.MAX_VALUE), value(64, 0uL)),
            evaluate(ArithmeticOperation.MIN_MAX, 64, ULong.MAX_VALUE, 0uL, mode = "twosComplement"))
        assertEquals(listOf(value(64, 0uL), value(64, ULong.MAX_VALUE)),
            evaluate(ArithmeticOperation.MIN_MAX, 64, ULong.MAX_VALUE, 0uL))
    }

    @Test fun shiftsAndRotationsPreservePerBitStates() {
        val input = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR, LogicValue.LOW))
        assertEquals("E?10", ArithmeticLogic.evaluate(ArithmeticOperation.SHIFTER, 4,
            listOf(input, value(2, 1uL)), shift = "ll").single().display)
        assertEquals("10E?", ArithmeticLogic.evaluate(ArithmeticOperation.SHIFTER, 4,
            listOf(input, value(2, 1uL)), shift = "rr").single().display)
        assertEquals("1111", ArithmeticLogic.evaluate(ArithmeticOperation.SHIFTER, 4,
            listOf(value(4, 8uL), value(2, 3uL)), shift = "ar").single().display)
    }

    @Test fun bitAdderMarksOnlyUncertainCountBitsAsErrors() {
        val input = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.LOW))
        assertEquals("0EE", ArithmeticLogic.evaluate(ArithmeticOperation.BIT_ADDER, 4, listOf(input)).single().display)
        val all = List(64) { value(64, ULong.MAX_VALUE) }
        assertEquals(4096uL, ArithmeticLogic.evaluate(ArithmeticOperation.BIT_ADDER, 64, all).single().toULongOrNull())
    }

    @Test fun bitFinderStopsAtTheFirstUncertainBit() {
        val input = LogicVector.low(64).withBit(63, LogicValue.HIGH)
        assertEquals(listOf(value(1, 1uL), value(6, 63uL)),
            ArithmeticLogic.evaluate(ArithmeticOperation.BIT_FINDER, 64, listOf(input), finder = "low1"))
        assertEquals(listOf(LogicVector.error(1), LogicVector.error(6)),
            ArithmeticLogic.evaluate(ArithmeticOperation.BIT_FINDER, 64,
                listOf(input.withBit(0, LogicValue.UNKNOWN)), finder = "low1"))
    }
}
