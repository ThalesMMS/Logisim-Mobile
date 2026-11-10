package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationComponentsTest {
    @Test
    fun adderCarriesAcrossEightAndSixtyFourBits() {
        val byte = SimulationComponents.add(LogicVector.of(8, 255uL), LogicVector.of(8, 1uL), LogicVector.low(1))
        assertEquals(0uL, byte[0].toULongOrNull())
        assertEquals(1uL, byte[1].toULongOrNull())
        val wide = SimulationComponents.add(LogicVector.of(64, ULong.MAX_VALUE), LogicVector.low(64), LogicVector.high(1))
        assertEquals(0uL, wide[0].toULongOrNull())
        assertEquals(1uL, wide[1].toULongOrNull())
    }

    @Test
    fun floatingCarryIsZeroAndPartialUnknownPropagatesUpward() {
        val result = SimulationComponents.add(
            LogicVector.fromBits(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.LOW)),
            LogicVector.low(4), LogicVector.unknown(1),
        )
        assertEquals(listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.UNKNOWN, LogicValue.UNKNOWN),
            (0..3).map { result[0].bit(it) })
    }

    @Test
    fun romImageExpandsRunsAndEscapedLineBreaks() {
        val image = SimulationComponents.readRomContents("addr/data: 4 8\\nf 1e 1d 2c e0 f0 6*0 1\\n4 3 2\\n")
        assertEquals(16, image.size)
        assertEquals(0x0fuL, image[0uL])
        assertEquals(0xe0uL, image[4uL])
        assertEquals(1uL, image[12uL])
        assertEquals(2uL, image[15uL])
    }

    @Test
    fun romImageSupportsExplicitAddressesAndComments() {
        val image = SimulationComponents.readRomContents("v2.0 raw\n0: 1 a # comment\n10: ff")
        assertEquals(mapOf(0uL to 1uL, 1uL to 10uL, 16uL to 255uL), image)
    }

    @Test
    fun configurableXorDistinguishesExactlyOneFromOddParity() {
        val gate = CircuitComponent("xor", ComponentKind.XOR, GridPoint(0, 0), attributes = mapOf("width" to "4", "inputs" to "3"))
        val inputs = List(3) { LogicVector.of(4, 0b1010uL) }
        assertEquals(0uL, SimulationComponents.combinational(gate, inputs, inputs)!!.single().toULongOrNull())
        assertEquals(0b1010uL, SimulationComponents.combinational(gate.copy(attributes = gate.attributes + ("xor" to "odd")), inputs, inputs)!!.single().toULongOrNull())
    }

    @Test
    fun openCollectorOutputKeepsKnownZeroAndReleasesKnownOne() {
        val gate = CircuitComponent("buf", ComponentKind.BUFFER, GridPoint(0, 0), attributes = mapOf("width" to "4", "out" to "0Z"))
        val inputs = listOf(LogicVector.of(4, 0b1010uL))
        val output = SimulationComponents.combinational(gate, inputs, inputs)!!.single()
        assertEquals(listOf(LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.UNKNOWN), (0..3).map(output::bit))
    }

    @Test
    fun disabledControlledBufferReleasesBusAndControlErrorPropagates() {
        val buffer = CircuitComponent("buf", ComponentKind.CONTROLLED_BUFFER, GridPoint(0, 0), attributes = mapOf("width" to "8"))
        val data = LogicVector.of(8, 42uL)
        fun run(control: LogicVector) = SimulationComponents.combinational(buffer, listOf(data, control), emptyList())!!.single()
        assertEquals(data, run(LogicVector.high(1)))
        assertEquals(LogicVector.unknown(8), run(LogicVector.low(1)))
        assertEquals(LogicVector.error(8), run(LogicVector.error(1)))
    }
}
