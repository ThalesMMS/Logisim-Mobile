package dev.logisim.mobile.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtraIoLogicTest {
    @Test
    fun bcdDigitsKeepTheDesktopPortOrderAndUndefinedBehavior() {
        assertEquals(listOf(1uL, 9uL, 1uL, 8uL), ExtraIoLogic.binaryToBcd(LogicVector.of(13, 8191u), 13).map { it.toULongOrNull() })
        assertEquals(listOf(5uL, 1uL), ExtraIoLogic.binaryToBcd(LogicVector.of(4, 15u), 4).map { it.toULongOrNull() })
        assertEquals(listOf(15uL, 0uL, 0uL), ExtraIoLogic.binaryToBcd(LogicVector.unknown(9), 9).map { it.toULongOrNull() })
    }

    @Test
    fun decimalDecoderHasSevenSegmentsAndRejectsNonDecimalCodes() {
        val masks = listOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f)
        for ((digit, mask) in masks.withIndex()) {
            assertEquals((0..6).map { ((mask shr it) and 1).toULong() },
                ExtraIoLogic.bcdToSegments(LogicVector.of(4, digit.toULong())).map { it.toULongOrNull() })
        }
        assertTrue(ExtraIoLogic.bcdToSegments(LogicVector.of(4, 10u)).all { it.isUnknown })
        assertTrue(ExtraIoLogic.bcdToSegments(LogicVector.error(4)).all { it.isUnknown })
    }

    @Test
    fun switchesPreserveBusValuesAndDisconnectTheUnselectedSide() {
        val input = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.ERROR, LogicValue.UNKNOWN, LogicValue.LOW))
        assertEquals(input, ExtraIoLogic.switch(input, true))
        assertEquals(LogicVector.unknown(4), ExtraIoLogic.switch(input, false))
        assertEquals(listOf(input, LogicVector.unknown(4)), ExtraIoLogic.twoWaySwitch(input, false))
        assertEquals(listOf(LogicVector.unknown(4), input), ExtraIoLogic.twoWaySwitch(input, true))
        assertEquals(input, ExtraIoLogic.twoWaySelect(LogicVector.low(4), input, true))
    }

    @Test
    fun bipolarLedOnlyLightsOnOppositeDefinedInputs() {
        assertEquals(TwoPinLedState.FORWARD, ExtraIoLogic.twoPinLed(LogicValue.HIGH, LogicValue.LOW, false))
        assertEquals(TwoPinLedState.OFF, ExtraIoLogic.twoPinLed(LogicValue.LOW, LogicValue.HIGH, false))
        assertEquals(TwoPinLedState.REVERSE, ExtraIoLogic.twoPinLed(LogicValue.LOW, LogicValue.HIGH, true))
        assertEquals(TwoPinLedState.OFF, ExtraIoLogic.twoPinLed(LogicValue.ERROR, LogicValue.LOW, true))
    }

    @Test
    fun sliderDirectionChangesPreserveValueAndWidthsQuantizePosition() {
        val state = ExtraSliderState().move(128)
        assertEquals(128uL, state.value(8).toULongOrNull())
        assertEquals(8uL, state.value(4).toULongOrNull())
        assertEquals(1uL, state.value(1).toULongOrNull())
        val reversed = state.direction(true)
        assertEquals(127, reversed.position)
        assertEquals(state.value(8), reversed.value(8))
        assertEquals(0uL, reversed.move(255).value(8).toULongOrNull())
        assertEquals(255, state.move(999).position)
    }

    @Test
    fun plaRomDecodesProductTermsWithMostSignificantInputFirst() {
        val table = PlaRomTable.empty(2, 2, 2)
            .withLiteral(0, 0, 2).withLiteral(0, 1, 1).withConnection(0, 0, true)
            .withLiteral(1, 0, 1).withLiteral(1, 1, 2).withConnection(1, 1, true)
        assertEquals(2uL, table.evaluate(LogicVector.of(2, 2u), LogicValue.LOW, false).toULongOrNull())
        assertEquals(1uL, table.evaluate(LogicVector.of(2, 1u), LogicValue.UNKNOWN, false).toULongOrNull())
        assertEquals(0uL, table.evaluate(LogicVector.low(2), LogicValue.LOW, false).toULongOrNull())
        assertEquals(LogicVector.unknown(2), table.evaluate(LogicVector.low(2), LogicValue.HIGH, false))
        assertEquals(LogicVector.error(2), table.evaluate(LogicVector.unknown(2), LogicValue.LOW, false))
        assertEquals(table, PlaRomTable.decode(2, 2, 2, table.encode()))
        val larger = table.resize(3, 3, 3)
        assertEquals(2, larger.literals[0][0])
        assertTrue(larger.connections[1][1])
    }

    @Test
    fun plaRomEmptyRowsAreErrorsAndBadContentsAreRejected() {
        assertEquals(LogicVector.error(1), PlaRomTable.empty(1, 1, 1).evaluate(LogicVector.low(1), LogicValue.LOW, false))
        assertEquals("", PlaRomTable.empty(4, 4, 4).encode())
        assertFailsWith<IllegalArgumentException> { PlaRomTable.decode(1, 1, 1, "2*3") }
        assertFailsWith<IllegalArgumentException> { PlaRomTable.decode(1, 1, 1, "3 1") }
        assertFailsWith<IllegalArgumentException> { PlaRomTable.decode(1, 1, 1, "2 2") }
    }

    @Test
    fun oscilloscopeCapturesBothEdgesAndUpdatesTheCurrentColumn() {
        var state = ExtraOscilloscopeState(1, 4)
        state = state.sample(LogicValue.LOW, listOf(LogicValue.LOW), LogicValue.HIGH, LogicValue.LOW)
        assertTrue(state.samples.isEmpty())
        state = state.sample(LogicValue.HIGH, listOf(LogicValue.LOW), LogicValue.HIGH, LogicValue.LOW)
        assertEquals(listOf(listOf(LogicValue.HIGH, LogicValue.LOW)), state.samples)
        state = state.sample(LogicValue.HIGH, listOf(LogicValue.HIGH), LogicValue.HIGH, LogicValue.LOW)
        assertEquals(listOf(listOf(LogicValue.HIGH, LogicValue.HIGH)), state.samples)
        state = state.sample(LogicValue.LOW, listOf(LogicValue.ERROR), LogicValue.UNKNOWN, LogicValue.LOW)
        assertEquals(listOf(LogicValue.LOW, LogicValue.UNKNOWN), state.samples.last())
        assertEquals(state, state.sample(LogicValue.HIGH, listOf(LogicValue.LOW), LogicValue.LOW, LogicValue.LOW))
        assertTrue(state.sample(LogicValue.HIGH, listOf(LogicValue.LOW), LogicValue.HIGH, LogicValue.HIGH).samples.isEmpty())
    }

    @Test
    fun oscilloscopeRetainsOnlyItsConfiguredNumberOfHalfCycles() {
        var state = ExtraOscilloscopeState(1, 4)
        repeat(13) { tick ->
            val value = if (tick % 2 == 0) LogicValue.LOW else LogicValue.HIGH
            state = state.sample(value, listOf(value), LogicValue.HIGH, LogicValue.LOW)
        }
        assertEquals(8, state.samples.size)
        assertEquals(listOf(LogicValue.LOW, LogicValue.LOW), state.samples.last())
        assertEquals(6, state.clockNumber)
    }

    @Test
    fun astableTimerUsesActiveElapsedTimeAndPausesWithoutCatchingUp() {
        var state = Ne555State().advance(0, true, false, LogicValue.UNKNOWN)
        assertTrue(state.outputHigh)
        repeat(6) { state = state.advance((it + 1) * 100L, true, false, LogicValue.UNKNOWN) }
        assertFalse(state.outputHigh)
        val voltage = state.capacitorVoltage
        state = state.advance(10_000, false, false, LogicValue.UNKNOWN)
        assertEquals(voltage, state.capacitorVoltage)
        val resumed = state.advance(10_010, true, false, LogicValue.UNKNOWN)
        assertTrue(abs(resumed.capacitorVoltage - voltage) < 0.1)
    }

    @Test
    fun monostableTimerRequiresTriggerReleaseBeforeRearming() {
        var state = Ne555State().advance(0, true, true, LogicValue.LOW, 1000, 10)
        assertTrue(state.outputHigh)
        state = state.advance(20, true, true, LogicValue.LOW, 1000, 10)
        assertFalse(state.outputHigh)
        assertTrue(state.waiting)
        repeat(10) { state = state.advance(40 + it * 20L, true, true, LogicValue.LOW, 1000, 10) }
        assertTrue(state.waiting)
        state = state.advance(260, true, true, LogicValue.HIGH, 1000, 10)
        assertFalse(state.waiting)
        state = state.advance(280, true, true, LogicValue.LOW, 1000, 10)
        assertTrue(state.outputHigh)
    }

    @Test
    fun buzzerResolvesFrequencyVolumeAndDisconnectedInputsLikeDesktop() {
        val tone = BuzzerTone.fromInputs(LogicVector.of(14, 4400u), LogicValue.HIGH,
            LogicVector.of(7, 127u), LogicVector.of(8, 64u), true, channels = 1)
        assertEquals(440, tone.frequencyHz)
        assertEquals(32767.0, tone.amplitude)
        assertEquals(64, tone.duty)
        assertTrue(tone.audible)
        val unknown = BuzzerTone.fromInputs(LogicVector.unknown(14), LogicValue.HIGH,
            LogicVector.unknown(7), LogicVector.unknown(8))
        assertEquals(440, unknown.frequencyHz)
        assertEquals(0.5, unknown.amplitude)
        assertEquals(128, unknown.duty)
        assertFalse(tone.copy(frequencyHz = 0).audible)
        assertFalse(tone.copy(enabled = false).audible)
    }

    @Test
    fun buzzerSquarePhaseWrapMatchesEvolutionAt49Hz() {
        val tone = BuzzerTone(true, 49, 32767.0, BuzzerWaveform.Square, channels = 1, smoothLevel = 0)
        val pcm = tone.pcm()

        assertEquals(44100, pcm.sampleRate)
        assertContentEquals(shortArrayOf(-32767, 0, 32767, 0), pcm.stereoSamples.copyOfRange(0, 4))
        assertEquals(-32767, pcm.stereoSamples[1800].toInt())
    }

    @Test
    fun buzzerProducesStereoPcmAndAValidWaveContainer() {
        val tone = BuzzerTone(true, 1000, 1000.0, BuzzerWaveform.Square, channels = 1, smoothLevel = 0)
        val pcm = tone.pcm()
        assertEquals(45000, pcm.sampleRate)
        assertEquals(90000, pcm.stereoSamples.size)
        assertEquals(1000, pcm.stereoSamples[0].toInt())
        assertEquals(0, pcm.stereoSamples[1].toInt())
        assertEquals(-1000, pcm.stereoSamples[46].toInt())
        val wav = pcm.wav()
        assertContentEquals("RIFF".encodeToByteArray(), wav.copyOfRange(0, 4))
        assertContentEquals("WAVE".encodeToByteArray(), wav.copyOfRange(8, 12))
        assertEquals(44 + pcm.stereoSamples.size * 2, wav.size)
        assertTrue(tone.copy(enabled = false).pcm().stereoSamples.isEmpty())
    }
}
