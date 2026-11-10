package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequentialLibraryTest {
    private val registry = ComponentRegistry(SequentialLibrary.definitions)

    private inner class Device(kind: ComponentKind, attributes: Map<String, String> = emptyMap(),
        val options: Map<String, String> = emptyMap()) {
        var component = registry.create(kind, "device", GridPoint(100, 200), attributes)
        private val behavior = registry.requireDefinition(kind).behavior!!
        var state: ComponentState = EmptyComponentState
        val inputs = mutableMapOf<String, LogicVector>()
        fun bit(name: String, value: LogicValue) { inputs[name] = LogicVector.fromBit(value) }
        fun word(name: String, value: ULong, width: Int = component.width) { inputs[name] = LogicVector.of(width, value) }
        fun evaluate(): Map<String, LogicVector> {
            val result = behavior.evaluate(ComponentEvaluationContext(component, registry.attributes(component),
                inputs, inputs.keys, state, options = options))
            state = result.state
            return result.outputs
        }
        fun edge(): Map<String, LogicVector> {
            bit("clock", LogicValue.LOW); evaluate()
            bit("clock", LogicValue.HIGH); return evaluate()
        }
    }

    @Test fun catalogueContainsEverySequentialMemoryDevice() {
        assertEquals(listOf("D Flip-Flop", "T Flip-Flop", "J-K Flip-Flop", "S-R Flip-Flop",
            "Register", "Counter", "Shift Register", "Random"), SequentialLibrary.definitions.map { it.fileName })
        assertTrue(SequentialLibrary.definitions.all { it.library == "#Memory" && !it.supportsRotation })
    }

    @Test fun flipFlopClearWinsOverPresetAndData() {
        for (kind in listOf(ComponentKind.D_FLIP_FLOP, SequentialLibrary.TFlipFlop,
            SequentialLibrary.JKFlipFlop, SequentialLibrary.SRFlipFlop)) {
            val device = Device(kind)
            device.bit("clear", LogicValue.HIGH); device.bit("preset", LogicValue.HIGH)
            assertEquals("0", device.edge().getValue("q").display)
            device.bit("clear", LogicValue.LOW)
            assertEquals("1", device.evaluate().getValue("q").display)
        }
    }

    @Test fun dFlipFlopIgnoresUnknownAndErrorData() {
        val device = Device(ComponentKind.D_FLIP_FLOP)
        device.bit("d", LogicValue.HIGH)
        assertEquals("1", device.edge().getValue("q").display)
        for (value in listOf(LogicValue.UNKNOWN, LogicValue.ERROR)) {
            device.bit("d", value)
            assertEquals("1", device.edge().getValue("q").display)
        }
    }

    @Test fun dLatchTracksInputAtHighLevelOnly() {
        val device = Device(ComponentKind.D_FLIP_FLOP, mapOf("trigger" to "high"))
        device.bit("clock", LogicValue.HIGH)
        device.bit("d", LogicValue.HIGH)
        assertEquals("1", device.evaluate().getValue("q").display)
        device.bit("d", LogicValue.LOW)
        assertEquals("0", device.evaluate().getValue("q").display)
        device.bit("clock", LogicValue.LOW); device.bit("d", LogicValue.HIGH)
        assertEquals("0", device.evaluate().getValue("q").display)
    }

    @Test fun fallingTriggerDoesNotTreatUnknownClockAsAnEdge() {
        val device = Device(ComponentKind.D_FLIP_FLOP, mapOf("trigger" to "falling"))
        device.bit("d", LogicValue.HIGH)
        device.bit("clock", LogicValue.HIGH); device.evaluate()
        device.bit("clock", LogicValue.UNKNOWN); device.evaluate()
        device.bit("clock", LogicValue.LOW)
        assertEquals("0", device.evaluate().getValue("q").display)
        device.bit("clock", LogicValue.HIGH); device.evaluate()
        device.bit("clock", LogicValue.LOW)
        assertEquals("1", device.evaluate().getValue("q").display)
    }

    @Test fun tFlipFlopTogglesOnlyForHighData() {
        val device = Device(SequentialLibrary.TFlipFlop)
        device.bit("t", LogicValue.HIGH)
        assertEquals("1", device.edge().getValue("q").display)
        assertEquals("0", device.edge().getValue("q").display)
        for (value in listOf(LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.ERROR)) {
            device.bit("t", value)
            assertEquals("0", device.edge().getValue("q").display)
        }
    }

    @Test fun jkInputsHoldSetResetAndToggle() {
        val device = Device(SequentialLibrary.JKFlipFlop)
        val inputs = listOf(0 to 0, 1 to 0, 0 to 0, 1 to 1, 1 to 1, 0 to 1)
        val actual = inputs.map { (j, k) ->
            device.bit("j", if (j == 1) LogicValue.HIGH else LogicValue.LOW)
            device.bit("k", if (k == 1) LogicValue.HIGH else LogicValue.LOW)
            device.edge().getValue("q").display
        }
        assertEquals(listOf("0", "1", "1", "0", "1", "0"), actual)
    }

    @Test fun srIllegalInputRetainsStateLikeDesktop() {
        val device = Device(SequentialLibrary.SRFlipFlop)
        device.bit("s", LogicValue.HIGH); device.bit("r", LogicValue.LOW)
        assertEquals("1", device.edge().getValue("q").display)
        device.bit("r", LogicValue.HIGH)
        assertEquals("1", device.edge().getValue("q").display)
        device.bit("s", LogicValue.LOW)
        assertEquals("0", device.edge().getValue("q").display)
    }

    @Test fun startupUnknownAndComplementFollowDesktop() {
        val device = Device(ComponentKind.D_FLIP_FLOP, options = mapOf("memoryStartupUnknown" to "true"))
        val output = device.evaluate()
        assertEquals("?", output.getValue("q").display)
        assertEquals("E", output.getValue("nq").display)
    }

    @Test fun registerAllowsFloatingEnableAndCapturesUndefinedBits() {
        val device = Device(ComponentKind.REGISTER, mapOf("width" to "4"))
        device.inputs["data"] = LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR, LogicValue.LOW))
        assertEquals("0E?1", device.edge().getValue("q").display)
        device.bit("enable", LogicValue.LOW); device.word("data", 15uL, 4)
        assertEquals("0E?1", device.edge().getValue("q").display)
        device.bit("clear", LogicValue.HIGH)
        assertEquals("0000", device.evaluate().getValue("q").display)
    }

    @Test fun registerLowLatchTracksWhileLow() {
        val device = Device(ComponentKind.REGISTER, mapOf("width" to "8", "trigger" to "low"))
        device.bit("clock", LogicValue.LOW); device.word("data", 0x81uL, 8)
        assertEquals(0x81uL, device.evaluate().getValue("q").toULongOrNull())
        device.word("data", 0x12uL, 8)
        assertEquals(0x12uL, device.evaluate().getValue("q").toULongOrNull())
        device.bit("clock", LogicValue.HIGH); device.word("data", 0xffuL, 8)
        assertEquals(0x12uL, device.evaluate().getValue("q").toULongOrNull())
    }

    @Test fun counterWrapsAndMasksLoadedValueAtConfiguredMaximum() {
        val device = Device(ComponentKind.COUNTER, mapOf("width" to "4", "max" to "0x9"))
        device.word("data", 14uL, 4); device.bit("load", LogicValue.HIGH)
        assertEquals(8uL, device.edge().getValue("q").toULongOrNull())
        device.bit("load", LogicValue.LOW)
        val terminal = device.edge()
        assertEquals(9uL, terminal.getValue("q").toULongOrNull())
        assertEquals("1", terminal.getValue("carry").display)
        assertEquals(0uL, device.edge().getValue("q").toULongOrNull())
        device.bit("up", LogicValue.LOW)
        assertEquals(9uL, device.edge().getValue("q").toULongOrNull())
    }

    @Test fun counterGoalModesAreDistinct() {
        val actual = listOf("wrap", "stay", "continue", "load").map { mode ->
            val device = Device(ComponentKind.COUNTER, mapOf("width" to "4", "max" to "9", "ongoal" to mode))
            device.bit("load", LogicValue.HIGH); device.word("data", 9uL, 4); device.edge()
            device.bit("load", LogicValue.LOW); device.word("data", 3uL, 4)
            device.edge().getValue("q").toULongOrNull()
        }
        assertEquals(listOf(0uL, 9uL, 10uL, 3uL), actual)
    }

    @Test fun counterWithoutMaximumUsesTheEvolutionFileDefault() {
        // Evolution loads a missing maximum as 0xFF and limits it to the width.
        val wide = Device(ComponentKind.COUNTER, mapOf("width" to "16"))
        wide.word("data", 0xABCDuL, 16); wide.bit("load", LogicValue.HIGH)
        assertEquals(0xCDuL, wide.edge().getValue("q").toULongOrNull())
        val narrow = Device(ComponentKind.COUNTER, mapOf("width" to "4"))
        narrow.word("data", 0xFuL, 4); narrow.bit("load", LogicValue.HIGH)
        assertEquals(0xFuL, narrow.edge().getValue("q").toULongOrNull())
    }

    @Test fun counterWidthEditsUpdateTheMaximumLikeEvolution() {
        val counter = CircuitComponent("c", ComponentKind.COUNTER, GridPoint(0, 0))
        val wide = registry.reconfigure(counter, mapOf("width" to "16"))
        assertEquals("0xffff", wide.attributes["max"])
        val narrowed = registry.reconfigure(wide.copy(attributes = wide.attributes + ("max" to "0x1234")), mapOf("width" to "8"))
        assertEquals("0x34", narrowed.attributes["max"])
        assertEquals("0x5", registry.reconfigure(counter, mapOf("width" to "16", "max" to "0x5")).attributes["max"])
    }

    @Test fun counterClearHasPriorityAndSuppressesDownCarry() {
        val device = Device(ComponentKind.COUNTER, mapOf("width" to "64", "max" to "0xffffffffffffffff"))
        device.bit("up", LogicValue.LOW); device.bit("clear", LogicValue.HIGH)
        device.bit("load", LogicValue.HIGH); device.word("data", ULong.MAX_VALUE, 64)
        val output = device.edge()
        assertEquals(0uL, output.getValue("q").toULongOrNull())
        assertEquals("0", output.getValue("carry").display)
        device.bit("clear", LogicValue.LOW); device.bit("load", LogicValue.LOW)
        assertEquals("1", device.evaluate().getValue("carry").display)
        assertEquals(ULong.MAX_VALUE, device.edge().getValue("q").toULongOrNull())
    }

    @Test fun counterDisabledHoldsValue() {
        val device = Device(ComponentKind.COUNTER, mapOf("width" to "8"))
        device.bit("enable", LogicValue.LOW)
        repeat(5) { assertEquals(0uL, device.edge().getValue("q").toULongOrNull()) }
        device.bit("enable", LogicValue.UNKNOWN)
        assertEquals(1uL, device.edge().getValue("q").toULongOrNull())
    }

    @Test fun counterStartupUnknownMatchesTheDesktopUnsignedRead() {
        val device = Device(ComponentKind.COUNTER, mapOf("width" to "8"), mapOf("memoryStartupUnknown" to "true"))
        device.bit("clock", LogicValue.LOW)
        assertEquals(255uL, device.evaluate().getValue("q").toULongOrNull())
    }

    @Test fun parallelShiftRegisterLoadsAndShiftsInDesktopStageOrder() {
        val device = Device(SequentialLibrary.ShiftRegister, mapOf("width" to "8", "length" to "3"))
        device.word("parallel0", 0x12uL, 8); device.word("parallel1", 0x34uL, 8); device.word("parallel2", 0x56uL, 8)
        device.bit("load", LogicValue.HIGH)
        val loaded = device.edge()
        assertEquals(listOf(0x12uL, 0x34uL, 0x56uL), (0..2).map { loaded.getValue("stage$it").toULongOrNull() })
        assertEquals(0x56uL, loaded.getValue("q").toULongOrNull())
        device.bit("load", LogicValue.LOW); device.word("serial", 0x78uL, 8)
        val shifted = device.edge()
        assertEquals(listOf(0x78uL, 0x12uL, 0x34uL), (0..2).map { shifted.getValue("stage$it").toULongOrNull() })
        assertEquals(0x34uL, shifted.getValue("q").toULongOrNull())
    }

    @Test fun serialShiftRegisterHonorsEnableClearAndUnknownData() {
        val device = Device(SequentialLibrary.ShiftRegister, mapOf("width" to "4", "length" to "2", "parallel" to "false"))
        device.inputs["serial"] = LogicVector.unknown(4)
        device.bit("shift", LogicValue.LOW)
        repeat(3) { assertEquals("0000", device.edge().getValue("q").display) }
        device.bit("shift", LogicValue.HIGH); device.edge()
        assertEquals("????", device.edge().getValue("q").display)
        device.bit("clear", LogicValue.HIGH)
        assertEquals("0000", device.evaluate().getValue("q").display)
    }

    @Test fun randomMatchesTheDesktopReferenceSequenceAtFullWidth() {
        val device = Device(SequentialLibrary.Random, mapOf("width" to "64", "seed" to "1"))
        device.bit("clock", LogicValue.LOW)
        val expected = listOf(0xCFC5D07F6F03C29BuL, 0xBF424132963FE08DuL, 0x19A37D5757AAF520uL,
            0xBF08119F05CD56D6uL, 0x2F47184B86186FA4uL, 0x97299FCAE7202345uL)
        val actual = listOf(device.evaluate().getValue("q").toULongOrNull()) +
            (1..5).map { device.edge().getValue("q").toULongOrNull() }
        assertEquals(expected, actual)
    }

    @Test fun randomMasksOutputAtEverySupportedWidth() {
        for (width in 1..64) {
            val device = Device(SequentialLibrary.Random, mapOf("width" to "$width", "seed" to "1"))
            val mask = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
            assertEquals(0xCFC5D07F6F03C29BuL and mask, device.evaluate().getValue("q").toULongOrNull())
            assertEquals(0xBF424132963FE08DuL and mask, device.edge().getValue("q").toULongOrNull())
        }
    }

    @Test fun randomResetRestoresSeedAndBlocksClock() {
        val device = Device(SequentialLibrary.Random, mapOf("width" to "64", "seed" to "1"))
        device.bit("reset", LogicValue.LOW); device.evaluate(); device.edge()
        device.bit("reset", LogicValue.HIGH)
        assertEquals(0xCFC5D07F6F03C29BuL, device.evaluate().getValue("q").toULongOrNull())
        assertEquals(0xCFC5D07F6F03C29BuL, device.edge().getValue("q").toULongOrNull())
        device.bit("reset", LogicValue.LOW); device.bit("enable", LogicValue.LOW)
        assertEquals(0xCFC5D07F6F03C29BuL, device.edge().getValue("q").toULongOrNull())
        device.bit("enable", LogicValue.UNKNOWN)
        assertEquals(0xBF424132963FE08DuL, device.edge().getValue("q").toULongOrNull())
    }

    @Test fun unseededRandomCanUseReproducibleSessionSeed() {
        val device = Device(SequentialLibrary.Random, mapOf("width" to "64"), mapOf("randomSeed" to "1"))
        assertEquals(0xCFC5D07F6F03C29BuL, device.evaluate().getValue("q").toULongOrNull())
    }

    @Test fun deviceInstancesNeverShareStoredState() {
        val first = Device(SequentialLibrary.TFlipFlop)
        val second = Device(SequentialLibrary.TFlipFlop)
        first.bit("t", LogicValue.HIGH); first.edge()
        assertEquals("1", first.evaluate().getValue("q").display)
        assertEquals("0", second.evaluate().getValue("q").display)
    }

    @Test fun heldClockDoesNotRepeatCounterShiftOrRandomEdges() {
        val counter = Device(ComponentKind.COUNTER)
        val random = Device(SequentialLibrary.Random, mapOf("width" to "64", "seed" to "1"))
        val shift = Device(SequentialLibrary.ShiftRegister, mapOf("width" to "8", "length" to "2", "parallel" to "false"))
        shift.word("serial", 0xabuL, 8)
        assertEquals(1uL, counter.edge().getValue("q").toULongOrNull())
        assertEquals(0xBF424132963FE08DuL, random.edge().getValue("q").toULongOrNull())
        assertEquals(0uL, shift.edge().getValue("q").toULongOrNull())
        repeat(10) {
            assertEquals(1uL, counter.evaluate().getValue("q").toULongOrNull())
            assertEquals(0xBF424132963FE08DuL, random.evaluate().getValue("q").toULongOrNull())
            assertEquals(0uL, shift.evaluate().getValue("q").toULongOrNull())
        }
    }

    @Test fun counterWrapIsUnsignedAtEverySupportedWidth() {
        for (width in 1..64) {
            val maximum = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
            val device = Device(ComponentKind.COUNTER, mapOf("width" to "$width", "max" to "0x${maximum.toString(16)}"))
            device.word("data", maximum, width); device.bit("load", LogicValue.HIGH)
            assertEquals(maximum, device.edge().getValue("q").toULongOrNull())
            device.bit("load", LogicValue.LOW)
            assertEquals(0uL, device.edge().getValue("q").toULongOrNull())
            device.bit("up", LogicValue.LOW)
            assertEquals(maximum, device.edge().getValue("q").toULongOrNull())
        }
    }

    @Test fun layoutsPreserveClassicAndEvolutionPinPositions() {
        val classic = Device(SequentialLibrary.JKFlipFlop)
        assertEquals(mapOf("j" to GridPoint(60, 200), "k" to GridPoint(60, 220), "clock" to GridPoint(60, 210),
            "q" to GridPoint(100, 200), "nq" to GridPoint(100, 220), "clear" to GridPoint(90, 230), "preset" to GridPoint(70, 230)),
            registry.layout(classic.component).ports.associate { it.name to it.position })
        val evolution = Device(ComponentKind.COUNTER, mapOf("width" to "64", "appearance" to "evolution"))
        val layout = registry.layout(evolution.component)
        assertEquals(ComponentBounds(100, 200, 400, 470), layout.bounds)
        assertEquals(GridPoint(400, 310), layout.ports.single { it.name == "q" }.position)
    }

    @Test fun shiftGeometryMatchesParallelPortCountsAtMaximumLength() {
        val classic = Device(SequentialLibrary.ShiftRegister, mapOf("length" to "64"))
        assertEquals(134, registry.layout(classic.component).ports.size)
        assertEquals(ComponentBounds(100, 180, 760, 220), registry.layout(classic.component).bounds)
        val evolution = Device(SequentialLibrary.ShiftRegister, mapOf("length" to "64", "appearance" to "evolution"))
        assertEquals(133, registry.layout(evolution.component).ports.size)
        assertEquals(GridPoint(220, 1550), registry.layout(evolution.component).ports.single { it.name == "q" }.position)
        assertFalse(registry.layout(evolution.component).ports.any { it.name == "stage63" })
    }

    @Test fun schemaRejectsInvalidWidthsLengthsAndEdgeLatchModes() {
        assertFailsWith<IllegalArgumentException> { Device(ComponentKind.REGISTER, mapOf("width" to "65")) }
        assertFailsWith<IllegalArgumentException> { Device(SequentialLibrary.ShiftRegister, mapOf("length" to "0")) }
        assertFailsWith<IllegalArgumentException> { Device(SequentialLibrary.TFlipFlop, mapOf("trigger" to "high")) }
        assertFailsWith<IllegalArgumentException> { Device(SequentialLibrary.Random, mapOf("seed" to "2147483648")) }
    }
}
