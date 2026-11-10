package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtlSequentialTest {
    private val l = LogicValue.LOW
    private val h = LogicValue.HIGH
    private val u = LogicValue.UNKNOWN
    private val e = LogicValue.ERROR
    private fun run(model: String, pins: Map<Int, LogicValue>, previous: TtlState = TtlState()) =
        requireNotNull(TtlSequential.evaluate(model, pins, previous))
    private fun value(result: TtlResult, vararg pins: Int): ULong? =
        LogicVector.fromBits(pins.map { result.outputs.getValue(it) }).toULongOrNull()
    private fun rise(model: String, pins: Map<Int, LogicValue>, clock: Int, state: TtlState = TtlState()): TtlResult {
        val low = run(model, pins + (clock to l), state)
        return run(model, pins + (clock to h), low.state)
    }
    private fun fall(model: String, pins: Map<Int, LogicValue>, clock: Int, state: TtlState = TtlState()): TtlResult {
        val high = run(model, pins + (clock to h), state)
        return run(model, pins + (clock to l), high.state)
    }

    @Test fun dual7474CapturesOnRisingAndAsyncControlsDominate() {
        val pins = mapOf(1 to h, 4 to h, 2 to h, 13 to h, 10 to h, 11 to l)
        val captured = rise("7474", pins, 3)
        assertEquals(h, captured.outputs[5]); assertEquals(l, captured.outputs[6])
        val cleared = run("7474", pins + (1 to l) + (3 to h), captured.state)
        assertEquals(l, cleared.outputs[5])
        assertEquals(u, run("7474", pins + (1 to l) + (4 to l), captured.state).outputs[5])
        assertEquals(8, captured.delay)
    }

    @Test fun dual7476UsesFallingEdgesAndIndependentHalves() {
        val pins = mapOf(2 to h, 3 to h, 4 to h, 15 to h, 5 to l, 6 to h, 7 to h, 9 to l, 12 to l)
        val first = fall("7476", pins, 1)
        assertEquals(h, first.outputs[14]); assertEquals(l, first.outputs[11])
        val next = fall("7476", pins, 1, first.state)
        assertEquals(l, next.outputs[14])
        val set = run("7476", pins + (6 to l), next.state)
        assertEquals(h, set.outputs[11])
    }

    @Test fun jk74107ResolvesUnknownControlsByAgreement() {
        val pins = mapOf(1 to u, 4 to h, 13 to h, 10 to h, 8 to l, 11 to l, 9 to l)
        val next = fall("74107", pins, 12)
        assertEquals(u, next.outputs[3])
        assertEquals(u, next.outputs[2])
        val reset = run("74107", pins + (13 to l), next.state)
        assertEquals(l, reset.outputs[3]); assertEquals(h, reset.outputs[2])
        assertEquals(e, run("74107", pins + (13 to e), reset.state).outputs[3])
    }

    @Test fun ripple7493HasIndependentDivideByTwoAndDivideByEight() {
        val pins = mapOf(2 to l, 3 to l, 1 to l)
        val a = fall("7493", pins, 14)
        assertEquals(1uL, value(a, 12, 9, 8, 11))
        val b = fall("7493", pins + (14 to l), 1, a.state)
        assertEquals(3uL, value(b, 12, 9, 8, 11))
        assertEquals(0uL, value(run("7493", pins + (2 to h) + (3 to h), b.state), 12, 9, 8, 11))
    }

    @Test fun monostable74123RetriggersAndExpiresWithoutClock() {
        val inactive = mapOf(1 to l, 2 to l, 3 to h, 9 to l, 10 to l, 11 to h)
        val first = requireNotNull(TtlSequential.evaluate("74123", inactive, TtlState(), elapsedTimeMillis = 10))
        val active = requireNotNull(TtlSequential.evaluate("74123", inactive + (2 to h), first.state,
            mapOf("1Rext" to "100", "1Cext" to "1000000"), 10))
        assertEquals(h, active.outputs[13]); assertEquals(55L, active.nextWakeupMillis)
        val dropped = requireNotNull(TtlSequential.evaluate("74123", inactive, active.state,
            mapOf("1Rext" to "100", "1Cext" to "1000000"), 20))
        val retrigger = requireNotNull(TtlSequential.evaluate("74123", inactive + (2 to h), dropped.state,
            mapOf("1Rext" to "100", "1Cext" to "1000000"), 21))
        assertEquals(66L, retrigger.nextWakeupMillis)
        val expired = requireNotNull(TtlSequential.evaluate("74123", inactive + (2 to h), retrigger.state,
            elapsedTimeMillis = 66))
        assertEquals(l, expired.outputs[13]); assertEquals(h, expired.outputs[4])
    }

    @Test fun counters74161And74163DifferInClearTiming() {
        val pins = mapOf(1 to h, 9 to h, 7 to h, 10 to h)
        for (model in listOf("74161", "74163")) {
            val counted = rise(model, pins, 2)
            assertEquals(1uL, value(counted, 14, 13, 12, 11))
            val clear = run(model, pins + (1 to l) + (2 to h), counted.state)
            assertEquals(if (model == "74161") 0uL else 1uL, value(clear, 14, 13, 12, 11))
        }
    }

    @Test fun serial74164CombinesInputsAndShiftsPriorOutputs() {
        val pins = mapOf(9 to h, 1 to h, 2 to h)
        val first = rise("74164", pins, 8)
        assertEquals(1uL, value(first, 3, 4, 5, 6, 10, 11, 12, 13))
        val second = rise("74164", pins + (1 to l), 8, first.state)
        assertEquals(2uL, value(second, 3, 4, 5, 6, 10, 11, 12, 13))
    }

    @Test fun parallel74165LoadsAsynchronouslyAndInhibitsShift() {
        val pins = mapOf(1 to l, 6 to h, 5 to l, 4 to l, 3 to l, 14 to l, 13 to l, 12 to l, 11 to l, 15 to h)
        val loaded = run("74165", pins)
        assertEquals(h, loaded.outputs[9]); assertEquals(l, loaded.outputs[7])
        val held = rise("74165", pins + (1 to h), 2, loaded.state)
        assertEquals(h, held.outputs[9])
        val shifted = rise("74165", pins + (1 to h) + (15 to l), 2, held.state)
        assertEquals(l, shifted.outputs[9])
    }

    @Test fun parallel74166LoadsOnRisingAndClearsAsynchronously() {
        val pins = mapOf(9 to h, 15 to l, 6 to l, 14 to h, 12 to l, 11 to l, 10 to l, 5 to l, 4 to l, 3 to l, 2 to l)
        val loaded = rise("74166", pins, 7)
        assertEquals(h, loaded.outputs[13])
        assertEquals(l, run("74166", pins + (9 to l), loaded.state).outputs[13])
    }

    @Test fun parallel74165ShiftsWhenLoadIsUndefinedButNotActiveLow() {
        val previous = TtlState(mapOf("q" to LogicVector.of(8, 1uL)), mapOf("2" to l))
        val result = run("74165", mapOf(1 to u, 2 to h, 15 to l, 10 to l), previous)
        assertEquals(l, result.outputs[9])
    }

    @Test fun register74173LoadsEnabledAndTristatesWithoutLosingData() {
        val pins = mapOf(15 to l, 9 to l, 10 to l, 1 to l, 2 to l, 11 to h, 12 to l, 13 to h, 14 to l)
        val loaded = rise("74173", pins, 7)
        assertEquals(5uL, value(loaded, 6, 5, 4, 3))
        val disabled = run("74173", pins + (1 to h) + (7 to h), loaded.state)
        assertTrue(disabled.outputs.values.all { it == u })
        assertEquals(5uL, value(run("74173", pins + (7 to h), disabled.state), 6, 5, 4, 3))
    }

    @Test fun quad74175CapturesAllFourDataPins() {
        val pins = mapOf(1 to h, 4 to h, 5 to l, 12 to h, 13 to l)
        val loaded = rise("74175", pins, 9)
        assertEquals(5uL, value(loaded, 2, 7, 10, 15))
        assertEquals(10uL, value(loaded, 3, 6, 11, 14))
    }

    @Test fun decade74190CountsBothDirectionsAndPreservesPartialAgreement() {
        val pins = mapOf(11 to h, 4 to l, 5 to l)
        val one = rise("74190", pins, 14)
        assertEquals(1uL, value(one, 3, 2, 6, 7))
        val zero = rise("74190", pins + (5 to h), 14, one.state)
        assertEquals(0uL, value(zero, 3, 2, 6, 7)); assertEquals(h, zero.outputs[12])
        val partial = run("74190", pins + (11 to l) + (15 to u) + (1 to l) + (10 to l) + (9 to l))
        assertEquals(u, partial.outputs[3]); assertEquals(l, partial.outputs[2])
    }

    @Test fun upDown74192And74193WrapAtTheirOwnModulus() {
        for (model in listOf("74192", "74193")) {
            val pins = mapOf(14 to l, 11 to h, 5 to h)
            val initial = run(model, pins + (4 to h))
            val wrap = fall(model, pins, 4, initial.state)
            assertEquals(l, wrap.outputs[13])
            val counted = run(model, pins + (4 to h), wrap.state)
            assertEquals(if (model == "74192") 9uL else 15uL, value(counted, 3, 2, 6, 7))
            assertEquals(4L, counted.delay)
        }
    }

    @Test fun universal74194LoadsAndShiftsBothDirections() {
        val pins = mapOf(1 to h, 9 to h, 10 to h, 3 to h, 4 to l, 5 to h, 6 to l)
        val loaded = rise("74194", pins, 11)
        assertEquals(10uL, value(loaded, 12, 13, 14, 15))
        val shifted = rise("74194", pins + (10 to l) + (2 to l), 11, loaded.state)
        assertEquals(5uL, value(shifted, 12, 13, 14, 15))
    }

    @Test fun addressable74259ResolvesUncertainAddress() {
        val pins = mapOf(15 to h, 14 to l, 13 to h, 1 to l, 2 to l, 3 to l)
        val loaded = run("74259", pins)
        assertEquals(1uL, value(loaded, 4, 5, 6, 7, 9, 10, 11, 12))
        val uncertain = run("74259", pins + (1 to u) + (13 to l), loaded.state)
        assertEquals(e, uncertain.outputs[4]); assertEquals(l, uncertain.outputs[5])
        assertEquals(0uL, value(run("74259", pins + (15 to l) + (14 to h), uncertain.state), 4, 5, 6, 7, 9, 10, 11, 12))
    }

    @Test fun octalRegisterFamiliesRespectTheirDistinctControlPins() {
        val data = mapOf(3 to h, 4 to l, 7 to h, 8 to l, 13 to h, 14 to l, 17 to h, 18 to l)
        for (model in listOf("74273", "74374", "74377")) {
            val enabled = if (model == "74273") h else l
            val loaded = rise(model, data + (1 to enabled), 11)
            assertEquals(85uL, value(loaded, 2, 5, 6, 9, 12, 15, 16, 19), model)
            assertEquals(if (model == "74374") 1L else 8L, loaded.delay)
            val changed = run(model, data + (1 to if (enabled == h) l else h) + (11 to h), loaded.state)
            if (model == "74273") assertEquals(0uL, value(changed, 2, 5, 6, 9, 12, 15, 16, 19))
            if (model == "74374") assertTrue(changed.outputs.values.all { it == u })
            if (model == "74377") assertEquals(85uL, value(changed, 2, 5, 6, 9, 12, 15, 16, 19))
        }
    }

    @Test fun universal74299KeepsDedicatedSerialOutputsWhileBusIsDisabled() {
        val pins = mapOf(9 to h, 1 to h, 19 to h, 2 to l, 3 to l,
            16 to h, 4 to l, 15 to l, 5 to l, 14 to l, 6 to l, 13 to l, 7 to h)
        val loaded = rise("74299", pins, 12)
        assertEquals(h, loaded.outputs[8]); assertEquals(h, loaded.outputs[17])
        assertEquals(u, loaded.outputs[7])
        val held = run("74299", pins + (1 to l) + (19 to l) + (12 to h), loaded.state)
        assertEquals(129uL, value(held, 16, 4, 15, 5, 14, 6, 13, 7))
    }

    @Test fun dual74390DividesByFiveAndByTwoIndependently() {
        val pins = mapOf(2 to l, 14 to l, 1 to l, 15 to l, 12 to l)
        var state = TtlState()
        repeat(5) { state = fall("74390", pins, 4, state).state }
        val five = run("74390", pins + (4 to l), state)
        assertEquals(0uL, value(five, 3, 5, 6, 7))
        val one = fall("74390", pins + (4 to l), 1, five.state)
        assertEquals(1uL, value(one, 3, 5, 6, 7))
        assertEquals(0uL, value(one, 13, 11, 10, 9))
        val secondHalf = fall("74390", pins + (4 to l), 15, one.state)
        assertEquals(1uL, value(secondHalf, 13, 11, 10, 9))
    }

    @Test fun dual74393CountsFallingEdgesAndAsyncReset() {
        val pins = mapOf(2 to l, 12 to l, 13 to l)
        var state = TtlState()
        repeat(3) { state = fall("74393", pins, 1, state).state }
        assertEquals(3uL, value(run("74393", pins + (1 to l), state), 3, 4, 5, 6))
        assertEquals(0uL, value(run("74393", pins + (2 to h), state), 3, 4, 5, 6))
    }

    @Test fun shiftStore74594And74595CapturePreShiftDataOnSharedEdge() {
        for (model in listOf("74594", "74595")) {
            val pins = mapOf(10 to h, 13 to if (model == "74594") h else l, 14 to h, 12 to l)
            val shifted = rise(model, pins, 11)
            val before = run(model, pins + (11 to l), shifted.state)
            val simultaneous = run(model, pins + (11 to h) + (12 to h), before.state)
            assertEquals(1uL, value(simultaneous, 15, 1, 2, 3, 4, 5, 6, 7), model)
            assertEquals(3uL, simultaneous.state.data.getValue("shift").toULongOrNull())
        }
    }

    @Test fun registerFile74670WritesBeforeReadingAndKeepsFourWords() {
        val pins = mapOf(11 to l, 12 to l, 14 to h, 13 to l, 5 to h, 4 to l,
            15 to h, 1 to l, 2 to h, 3 to l)
        val write = run("74670", pins)
        assertEquals(5uL, value(write, 10, 9, 7, 6))
        val other = run("74670", pins + (12 to h) + (5 to l), write.state)
        assertEquals(0uL, value(other, 10, 9, 7, 6))
    }

    @Test fun decoded744017UsesBothQualifiedClockEdges() {
        val first = rise("744017", mapOf(13 to l, 15 to l), 14)
        assertEquals(h, first.outputs[2]); assertEquals(l, first.outputs[3])
        val second = fall("744017", mapOf(14 to h, 15 to l), 13, first.state)
        assertEquals(h, second.outputs[4]); assertEquals(l, second.outputs[2])
        assertEquals(h, second.outputs[12])
    }

    @Test fun binary744060ExposesStagesFourThroughFourteen() {
        var state = TtlState()
        repeat(8) { state = fall("744060", mapOf(12 to l), 11, state).state }
        val result = run("744060", mapOf(11 to l, 12 to l), state)
        assertEquals(h, result.outputs[7]); assertEquals(l, result.outputs[5])
        assertEquals(l, run("744060", mapOf(12 to h), state).outputs[7])
    }

    @Test fun serial744094HasStrobeAndFallingSerialLatch() {
        val pins = mapOf(1 to h, 15 to h, 2 to h)
        var state = TtlState()
        repeat(8) { state = rise("744094", pins, 3, state).state }
        val loaded = run("744094", pins + (3 to h), state)
        assertEquals(255uL, value(loaded, 4, 5, 6, 7, 14, 13, 12, 11))
        assertEquals(h, loaded.outputs[9])
        val falling = run("744094", pins + (3 to l), loaded.state)
        assertEquals(h, falling.outputs[10])
        val disabled = run("744094", pins + (15 to l), falling.state)
        assertEquals(u, disabled.outputs[4]); assertEquals(h, disabled.outputs[9])
    }

    @Test fun supportedSetContainsOnlyImplementedSequentialModels() {
        assertEquals(29, TtlSequential.supportedModels.size)
        assertFalse("744075" in TtlSequential.supportedModels)
    }
}
