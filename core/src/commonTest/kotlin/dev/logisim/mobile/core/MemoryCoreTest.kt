package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryCoreTest {
    private fun input(address: ULong = 0uL, data: ULong = 0uL, clock: LogicValue = LogicValue.LOW,
                      we: LogicValue = LogicValue.HIGH, oe: LogicValue = LogicValue.HIGH,
                      width: Int = 8, addressBits: Int = 4,
                      bytes: List<LogicValue> = emptyList(), lines: List<LogicValue> = emptyList()) =
        MemoryPortInput(LogicVector.of(addressBits, address), listOf(LogicVector.of(width, data)), oe, we, clock, lines, bytes)

    @Test
    fun synchronousRamReadsAfterWriteAndHoldsWithoutAnEdge() {
        val c = MemoryConfiguration(addressBits = 4)
        var state = MemoryRuntimeState.initial(c)
        state = MemoryCore.evaluate(c, state, listOf(input(data = 0xabuL))).state
        assertEquals(null, state.outputs[0][0].toULongOrNull())
        state = MemoryCore.evaluate(c, state, listOf(input(data = 0xabuL, clock = LogicValue.HIGH))).state
        assertEquals(0xabuL, state.image[0])
        assertEquals(0xabuL, state.outputs[0][0].toULongOrNull())
        state = MemoryCore.evaluate(c, state, listOf(input(address = 1uL, data = 3uL, clock = LogicValue.HIGH))).state
        assertEquals(0uL, state.image[1])
        assertEquals(0xabuL, state.outputs[0][0].toULongOrNull())
    }

    @Test
    fun readBeforeWriteReturnsThePreviousWord() {
        val c = MemoryConfiguration(addressBits = 4, readBehavior = MemoryReadBehavior.BEFORE_WRITE)
        val initial = MemoryRuntimeState.initial(c, MemoryImage(4, 8, mapOf(2L to 0x55uL)))
        val state = MemoryCore.evaluate(c, initial, listOf(input(address = 2uL, data = 0xaauL, clock = LogicValue.HIGH))).state
        assertEquals(0xaauL, state.image[2])
        assertEquals(0x55uL, state.outputs[0][0].toULongOrNull())
    }

    @Test
    fun byteMasksPreserveDisabledBytesIncludingPartialHighByte() {
        val c = MemoryConfiguration(addressBits = 4, dataBits = 20, byteEnables = true)
        val initial = MemoryRuntimeState.initial(c, MemoryImage(4, 20, mapOf(0L to 0xabcdeuL)))
        val state = MemoryCore.evaluate(c, initial, listOf(input(data = 0x12345uL, width = 20,
            clock = LogicValue.HIGH, bytes = listOf(LogicValue.HIGH, LogicValue.LOW, LogicValue.HIGH)))).state
        assertEquals(0x1bc45uL, state.image[0])
        assertEquals(0x1bc45uL, state.outputs[0][0].toULongOrNull())
    }

    @Test
    fun lowLevelWriteAndSeparateOutputDisableFollowDesktopModes() {
        val c = MemoryConfiguration(addressBits = 4, trigger = MemoryTrigger.LOW)
        var state = MemoryRuntimeState.initial(c)
        state = MemoryCore.evaluate(c, state, listOf(input(data = 9uL, we = LogicValue.LOW))).state
        assertEquals(9uL, state.image[0])
        assertEquals(9uL, state.outputs[0][0].toULongOrNull())
        state = MemoryCore.evaluate(c, state, listOf(input(address = 1uL, data = 4uL,
            we = LogicValue.HIGH, oe = LogicValue.LOW))).state
        assertEquals(0uL, state.image[1])
        assertEquals(9uL, state.outputs[0][0].toULongOrNull())
        val controlled = c.copy(bus = MemoryBus.CONTROLLED)
        state = MemoryCore.evaluate(controlled, state, listOf(input(oe = LogicValue.LOW, we = LogicValue.HIGH))).state
        assertTrue(state.outputs[0][0].isUnknown)
    }

    @Test
    fun lineEnableWritesSelectedWordsAndRejectsMisalignment() {
        val c = MemoryConfiguration(addressBits = 4, enables = MemoryEnables.LINE, lineSize = 4)
        val incoming = input(clock = LogicValue.HIGH, lines = listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.HIGH))
            .copy(data = listOf(1uL, 2uL, 3uL, 4uL).map { LogicVector.of(8, it) })
        val state = MemoryCore.evaluate(c, MemoryRuntimeState.initial(c), listOf(incoming)).state
        assertEquals(listOf(0uL, 2uL, 0uL, 4uL), (0L..3L).map { state.image[it] })
        assertEquals(listOf(0uL, 2uL, 0uL, 4uL), state.outputs[0].map { it.toULongOrNull() })
        val bad = incoming.copy(address = LogicVector.of(4, 1uL))
        val misaligned = MemoryCore.evaluate(c, MemoryRuntimeState.initial(c), listOf(bad)).state
        assertTrue(misaligned.image.cells.isEmpty())
        assertTrue(misaligned.outputs[0].all { it.isError })
    }

    @Test
    fun lineRamOverflowStaysInTheShortBackingPageWithoutExtendingTheImage() {
        for (bits in listOf(4, 12)) {
            val c = MemoryConfiguration(addressBits = bits, enables = MemoryEnables.LINE, lineSize = 2,
                allowMisaligned = true, clearPin = true)
            val last = (1uL shl bits) - 1uL
            fun step(previous: MemoryRuntimeState, address: ULong, clock: LogicValue, lane: Int? = null,
                     word: ULong = 0uL, clear: LogicValue = LogicValue.LOW): MemoryRuntimeState {
                val port = input(address, clock = clock, we = if (lane != null) LogicValue.HIGH else LogicValue.LOW,
                    addressBits = bits, lines = List(2) { if (it == lane) LogicValue.HIGH else LogicValue.LOW })
                    .copy(data = List(2) { LogicVector.of(8, if (it == lane) word else 0uL) })
                return MemoryCore.evaluate(c, previous, listOf(port), clear).state
            }
            fun outputs(state: MemoryRuntimeState) = state.outputs.single().map { it.toULongOrNull() }

            val initial = MemoryRuntimeState.initial(c)
            val first = step(initial, last, LogicValue.HIGH, 0, 0xa5uL)
            val low = step(first, last, LogicValue.LOW)
            val second = step(low, last, LogicValue.HIGH, 1, 0x3cuL)
            val extra = if (bits == 4) 0x3cuL else 0uL
            assertEquals(listOf(0xa5uL, 0uL), outputs(first))
            assertEquals(listOf(0xa5uL, extra), outputs(second))

            val zero = step(second, 0uL, LogicValue.HIGH)
            assertEquals(listOf(0uL, 0uL), outputs(zero))
            assertEquals(listOf(0xa5uL, extra), outputs(step(zero, last, LogicValue.HIGH)))

            assertEquals(MemoryImage(bits, 8, mapOf(last.toLong() to 0xa5uL)), second.image)
            val encoded = MemoryImageCodec.encodeCirc(second.image)
            val reopened = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeCirc(encoded)).image
            assertEquals(second.image, reopened)
            assertEquals(listOf(0xa5uL, 0uL), outputs(step(MemoryRuntimeState.initial(c, reopened), last, LogicValue.LOW)))

            val nonvolatile = MemoryCore.reset(c.copy(nonvolatile = true), second)
            assertEquals(listOf(0xa5uL, extra), outputs(step(nonvolatile, last, LogicValue.LOW)))
            assertEquals(listOf(0uL, 0uL), outputs(step(MemoryCore.reset(c, second), last, LogicValue.LOW)))
            assertEquals(listOf(0uL, 0uL), outputs(step(second, last, LogicValue.HIGH, clear = LogicValue.HIGH)))
        }
    }

    @Test
    fun clearHasPriorityAndSharedBusFloats() {
        val c = MemoryConfiguration(addressBits = 4, clearPin = true, bus = MemoryBus.SHARED)
        val state = MemoryCore.evaluate(c, MemoryRuntimeState.initial(c, MemoryImage(4, 8, mapOf(0L to 0xffuL))),
            listOf(input(data = 7uL, clock = LogicValue.HIGH)), LogicValue.HIGH).state
        assertTrue(state.image.cells.isEmpty())
        assertTrue(state.outputs[0][0].isUnknown)
        assertEquals(LogicValue.LOW, state.clocks[0])
    }

    @Test
    fun unknownAddressesCannotWriteAndErrorAddressesProduceErrors() {
        val c = MemoryConfiguration(addressBits = 4, asynchronousRead = true)
        val unknown = input(clock = LogicValue.HIGH).copy(address = LogicVector.unknown(4))
        val initial = MemoryRuntimeState.initial(c)
        val unknownState = MemoryCore.evaluate(c, initial, listOf(unknown)).state
        assertTrue(unknownState.image.cells.isEmpty())
        assertTrue(unknownState.outputs[0][0].isUnknown)
        val error = MemoryCore.evaluate(c, initial, listOf(unknown.copy(address = LogicVector.error(4)))).state
        assertTrue(error.outputs[0][0].isError)
    }

    @Test
    fun dualPortCollisionWritesBLastAndReadsInPortOrder() {
        val c = MemoryConfiguration(addressBits = 4, dualPort = true)
        val a = input(data = 0x11uL, clock = LogicValue.HIGH)
        val b = input(data = 0x22uL, clock = LogicValue.HIGH)
        val result = MemoryCore.evaluate(c, MemoryRuntimeState.initial(c), listOf(a, b))
        assertEquals(0x22uL, result.state.image[0])
        assertEquals(0x11uL, result.state.outputs[0][0].toULongOrNull())
        assertEquals(0x22uL, result.state.outputs[1][0].toULongOrNull())
        assertEquals(1, result.diagnostics.size)
        val war = c.copy(readBehavior = MemoryReadBehavior.BEFORE_WRITE)
        val before = MemoryCore.evaluate(war, MemoryRuntimeState.initial(war), listOf(a, b))
        assertEquals(0uL, before.state.outputs[0][0].toULongOrNull())
        assertEquals(0x11uL, before.state.outputs[1][0].toULongOrNull())
    }

    @Test
    fun romLineReadsAndMemoryInstancesAreIndependent() {
        val c = MemoryConfiguration(addressBits = 4, readOnly = true, lineSize = 2)
        val image = MemoryImage(4, 8, mapOf(2L to 0xabuL, 3L to 0xcduL))
        val result = MemoryCore.evaluate(c, MemoryRuntimeState.initial(c, image), listOf(input(address = 2uL))).state
        assertEquals(listOf(0xabuL, 0xcduL), result.outputs[0].map { it.toULongOrNull() })
        val other = MemoryRuntimeState.initial(c)
        assertTrue(other.image.cells.isEmpty())
        assertFalse(result.image === other.image)
    }

    @Test
    fun resetRetainsOnlyNonvolatileRamAndRomContent() {
        val image = MemoryImage(4, 64, mapOf(15L to ULong.MAX_VALUE))
        val c = MemoryConfiguration(addressBits = 4, dataBits = 64)
        val state = MemoryRuntimeState.initial(c, image)
        assertTrue(MemoryCore.reset(c, state).image.cells.isEmpty())
        assertEquals(ULong.MAX_VALUE, MemoryCore.reset(c.copy(nonvolatile = true), state).image[15])
        assertEquals(ULong.MAX_VALUE, MemoryCore.reset(c.copy(readOnly = true), state).image[15])
    }
}
