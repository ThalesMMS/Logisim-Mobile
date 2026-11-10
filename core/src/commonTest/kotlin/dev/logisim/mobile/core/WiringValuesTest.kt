package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WiringValuesTest {
    private val zero = LogicVector.low(1)
    private val one = LogicVector.high(1)
    private val unknown = LogicVector.unknown(1)
    private val error = LogicVector.error(1)
    private val mixed = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR))

    @Test
    fun pinPullActsOnlyOnUndefinedBitsAndTristateStartsFloating() {
        assertEquals("????", WiringValues.initialPin(4, "tristate", 7uL).display)
        assertEquals("0111", WiringValues.initialPin(4, "simple", 7uL).display)
        assertEquals("E110", WiringValues.pinDrive(mixed, "pullup").display)
        assertEquals("E010", WiringValues.pinDrive(mixed, "pulldown").display)
        assertEquals(mixed, WiringValues.pinDrive(mixed, "simple"))
    }

    @Test
    fun transistorsMaskOnlyTheirWeakLogicLevel() {
        assertEquals("E?1?", WiringValues.transistor(mixed, zero, pType = true).display)
        assertEquals("E??0", WiringValues.transistor(mixed, one, pType = false).display)
        assertEquals("????", WiringValues.transistor(mixed, one, pType = true).display)
        assertEquals("????", WiringValues.transistor(mixed, zero, pType = false).display)
    }

    @Test
    fun anUnknownTransistorGateRetainsOnlyFloatingBits() {
        assertEquals("E?EE", WiringValues.transistor(mixed, unknown, pType = true).display)
        assertEquals("E?EE", WiringValues.transistor(mixed, error, pType = false).display)
    }

    @Test
    fun transmissionGateRequiresComplementaryControls() {
        assertEquals(mixed, WiringValues.transmissionGate(mixed, zero, one))
        assertEquals("????", WiringValues.transmissionGate(mixed, one, zero).display)
        assertEquals("E?EE", WiringValues.transmissionGate(mixed, one, one).display)
        assertEquals("E?EE", WiringValues.transmissionGate(mixed, unknown, zero).display)
    }

    @Test
    fun extensionPreservesUndefinedBitsAndTruncatesWithoutResolvingThem() {
        assertEquals("0000E?10", WiringValues.extend(mixed, 8, "zero").display)
        assertEquals("1111E?10", WiringValues.extend(mixed, 8, "one").display)
        assertEquals("EEEEE?10", WiringValues.extend(mixed, 8, "sign").display)
        assertEquals("????E?10", WiringValues.extend(mixed, 8, "input", unknown).display)
        assertEquals("10", WiringValues.extend(mixed, 2, "sign").display)
    }

    @Test
    fun sixtyFourBitExtensionRetainsItsMostSignificantBit() {
        assertEquals(ULong.MAX_VALUE, WiringValues.extend(one, 64, "sign").toULongOrNull())
        assertEquals(1uL, WiringValues.extend(one, 64, "zero").toULongOrNull())
    }

    @Test
    fun clockUsesTheLowPhaseBeforeTheHighPhase() {
        assertEquals(listOf("0", "0", "0", "1", "1", "0", "0", "0", "1", "1"),
            (0L..9L).map { WiringValues.clock(it, 2, 3, 0).display })
        assertEquals("1", WiringValues.clock(0, 2, 3, -1).display)
        assertEquals("0", WiringValues.clock(0, 2, 3, 5).display)
    }

    @Test
    fun powerOnResetChangesAtItsWallClockDeadline() {
        val pulse = PowerOnResetPulse(100, 2000, startsHigh = true)
        assertEquals("1", pulse.output(2099).display)
        assertEquals("0", pulse.output(2100).display)
        assertEquals("0", PowerOnResetPulse(100, 2000, startsHigh = false).output(100).display)
        assertEquals("1", PowerOnResetPulse(100, 2000, startsHigh = false).output(2100).display)
    }
}
