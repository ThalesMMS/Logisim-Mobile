package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CircuitExamplesTest {
    @Test
    fun everyBundledExampleImportsAndProducesItsDeclaredResults() {
        val runner = ExampleRunner()
        assertEquals(CircuitExamples.all.size, CircuitExamples.all.map { it.id }.distinct().size)
        assertTrue(CircuitExamples.all.map { it.id }.containsAll(listOf("and", "d-flip-flop", "bus-adder", "hierarchy", "rom", "sap1", "bus-conflict")))
        for (example in CircuitExamples.all) {
            val result = runner.run(example)
            assertTrue(result.observations.isNotEmpty(), example.id)
            assertTrue(result.failures.isEmpty(), result.failures.joinToString("\n"))
        }
    }

    @Test
    fun userExportsPreserveEveryExampleResult() {
        val runner = ExampleRunner()
        for (example in CircuitExamples.all) {
            val session = runner.initialize(example)
            val saved = example.copy(xml = CircCodec.writeProject(session.project))
            assertEquals(runner.run(example), runner.run(saved), example.id)
        }
    }

    @Test
    fun malformedExampleInputIsRejectedInsteadOfTruncated() {
        val runner = ExampleRunner()
        val initial = runner.initialize(CircuitExamples.find("bus-adder")!!)
        assertFailsWith<IllegalArgumentException> { runner.advance(initial, ExampleAction("bad", mapOf("A" to "1"))) }
        assertFailsWith<IllegalArgumentException> { runner.advance(initial, ExampleAction("bad", mapOf("missing" to "00000000"))) }
    }
}
