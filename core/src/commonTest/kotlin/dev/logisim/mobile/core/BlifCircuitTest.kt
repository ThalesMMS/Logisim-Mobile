package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Native BLIF simulation; scripts/oracle/blif-circuit compares the same rules with Evolution d58a61e0. */
class BlifCircuitTest {
    private val registry = ComponentCatalog.registry
    private val simulator = CircuitSimulator(registry = registry)

    /** One BLIF at (100, 100), a Pin per port on Evolution's grid and a probe Pin per output. */
    private fun document(source: String?, inputs: List<Int>, outputs: List<Int>): CircuitDocument {
        val blif = CircuitComponent("hdl", HdlIpLibrary.BLIF, GridPoint(100, 100),
            attributes = source?.let { mapOf("content" to it) }.orEmpty())
        return CircuitDocument("main", components = listOf(blif) + inputs.mapIndexed { index, width ->
            CircuitComponent("in$index", ComponentKind.INPUT, GridPoint(100, 140 + index * 10), attributes = mapOf("width" to "$width"))
        } + outputs.mapIndexed { index, width ->
            CircuitComponent("out$index", ComponentKind.OUTPUT, GridPoint(240, 140 + index * 10), attributes = mapOf("width" to "$width"))
        })
    }

    private fun bits(text: String) = LogicVector.fromBits(text.reversed().map {
        when (it) { '0' -> LogicValue.LOW; '1' -> LogicValue.HIGH; 'E' -> LogicValue.ERROR; else -> LogicValue.UNKNOWN }
    })

    private fun run(document: CircuitDocument, steps: List<List<String>>, outputs: Int): List<String> {
        var state = SimulationState()
        return steps.map { values ->
            state = simulator.settle(document, state, vectorInputs = values.withIndex().associate { "in${it.index}" to bits(it.value) })
            assertTrue(!state.oscillating)
            (0 until outputs).joinToString(" ") { state.vectorValues.getValue("out$it").display }
        }
    }

    @Test fun defaultLightswitchTogglesOnRisingEdgesAndResets() {
        val document = document(null, listOf(1, 1), listOf(1))
        val steps = listOf("00", "10", "00", "10", "00", "10", "11", "01", "00").map { listOf("${it[0]}", "${it[1]}") }
        assertEquals(listOf("0", "1", "1", "0", "0", "1", "0", "0", "0"), run(document, steps, 1))
    }

    @Test fun gatesTreatUnknownAndErrorAsNotHigh() {
        val source = """
            .model gates
            .inputs a b
            .outputs and or xor nand nor nxor andnot ornot buf
            .subckt AND A=a B=b Y=and
            .subckt OR A=a B=b Y=or
            .subckt XOR A=a B=b Y=xor
            .subckt NAND A=a B=b Y=nand
            .subckt NOR A=a B=b Y=nor
            .subckt NXOR A=a B=b Y=nxor
            .gate ANDNOT A=a B=b Y=andnot
            .gate ORNOT A=a B=b Y=ornot
            .subckt BUF A=a Y=buf
            .end
        """.trimIndent()
        // Output order: and andnot buf nand nor nxor or ornot xor.
        val document = document(source, listOf(1, 1), List(9) { 1 })
        assertEquals(listOf(
            "0 0 0 1 1 1 0 1 0",
            "0 1 1 1 0 0 1 1 1",
            "0 0 ? 1 0 0 1 0 1",
            "0 0 E 1 1 1 0 1 0",
            "1 0 1 0 0 1 1 1 0",
        ), run(document, listOf(listOf("0", "0"), listOf("1", "0"), listOf("x", "1"), listOf("E", "0"), listOf("1", "1")), 9))
    }

    @Test fun tristateDriversResolvePerBitLikeTheDesktopBus() {
        val source = """
            .model desktop_bus2
            .inputs a[0] a[1] b[0] b[1] en_a[0] en_a[1] en_b[0] en_b[1]
            .outputs y[0] y[1]
            .names ${'$'}undef
            .subckt TRIS A=a[0] B=en_a[0] Y=left0
            .subckt TRISI A=b[0] B=en_b[0] Y=right0
            .gate BUS A=left0 B=right0 Y=y[0]
            .subckt TRIS A=a[1] B=en_a[1] Y=left1
            .subckt TRISI A=b[1] B=en_b[1] Y=right1
            .subckt BUS A=left1 B=${'$'}undef Y=left1_resolved
            .gate BUS A=left1_resolved B=right1 Y=y[1]
            .end
        """.trimIndent()
        val document = document(source, listOf(2, 2, 2, 2), listOf(2))
        assertEquals(listOf("10", "??", "10", "1E"), run(document, listOf(
            listOf("10", "00", "11", "11"), listOf("10", "00", "00", "11"),
            listOf("10", "10", "11", "00"), listOf("10", "11", "11", "00")), 1))
    }

    @Test fun flipFlopsLatchesAndPullsFollowTheSequentialScript() {
        val source = """
            .model seq
            .inputs clk d e en
            .outputs q[0] q[1] latch up
            .subckt NOT A=q[0] Y=d0
            .subckt XOR A=q[0] B=q[1] Y=d1
            .subckt DFF C=clk D=d0 Q=q[0]
            .subckt DFF C=clk D=d1 Q=q[1]
            .subckt DLATCH D=d E=e Q=latch
            .subckt TRIS A=d B=en Y=up
            .subckt PULLUP Y=up
            .end
        """.trimIndent()
        // Inputs: clk d e en. Outputs: latch up q.
        val document = document(source, listOf(1, 1, 1, 1), listOf(1, 1, 2))
        val steps = listOf("0100", "1100", "0100", "1110", "1001", "0000", "1011").map { step -> step.map { "$it" } }
        assertEquals(listOf("0 1 00", "0 1 01", "0 1 01", "1 1 10", "1 0 10", "1 1 10", "0 0 11"), run(document, steps, 3))
    }

    @Test fun unparsableSourceRunsTheTemplateAndKeepsItsText() {
        val source = ".model bad\n.inputs a b\n.outputs y\n.names a b y\n11 1\n.end"
        val document = document(source, listOf(1, 1), listOf(1))
        assertEquals(listOf("0", "1", "1", "0"), run(document, listOf(listOf("0", "0"), listOf("1", "0"),
            listOf("0", "0"), listOf("1", "0")), 1))
        val state = simulator.settle(document)
        assertTrue(state.diagnostics.any { "odd .names" in it }, state.diagnostics.toString())
        val xml = CircCodec.writeProject(CircuitProject(listOf(document)), registry)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry))
            .project.mainCircuit.components.single { it.kind == HdlIpLibrary.BLIF }
        assertEquals(source, reopened.attributes["content"])
    }

    @Test fun resetClearsTheFlipFlops() {
        val document = document(null, listOf(1, 1), listOf(1))
        var state = simulator.settle(document, vectorInputs = mapOf("in0" to bits("0"), "in1" to bits("0")))
        state = simulator.settle(document, state, vectorInputs = mapOf("in0" to bits("1"), "in1" to bits("0")))
        assertEquals("1", state.vectorValues.getValue("out0").display)
        state = simulator.settle(document, state, vectorInputs = mapOf("in0" to bits("0"), "in1" to bits("0")), reset = true)
        assertIs<BlifCircuitState>(state.componentStates.getValue("hdl"))
        assertEquals("0", state.vectorValues.getValue("out0").display)
        // A fresh flip-flop remembers no clock level, so a clock already high is a rising edge, as in Evolution.
        state = simulator.settle(document, state, vectorInputs = mapOf("in0" to bits("1"), "in1" to bits("0")), reset = true)
        assertEquals("1", state.vectorValues.getValue("out0").display)
    }
}
