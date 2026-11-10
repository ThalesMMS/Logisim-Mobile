package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TestVectorRunnerTest {
    @Test
    fun runsAllRowsAgainstTheRealAndCircuitAndReportsExactDifferences() {
        val project = CircuitProject(listOf(DemoCircuits.andGate()))
        val table = TestVectorFormat.read("A B Y\n0 0 0\n0 1 0\n1 0 0\n1 1 1\n1 1 0\n")
        val runner = TestVectorRunSession(project, project.mainCircuitName, table)
        runner.run()
        assertEquals(VectorRunStatus.COMPLETED, runner.status)
        assertEquals(4, runner.passedCount)
        assertEquals(1, runner.failedCount)
        assertEquals(VectorDifference("Y", "0", "1"), runner.results.last().differences.single())
        assertTrue(runner.exportCsv().contains("\"Minha_primeira_porta_AND\",5,6,0,0,FAIL"))
        assertTrue(runner.exportCsv().contains("Y: 0 != 1"))
        val replay = runner.exportReplay()
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(replay.projectXml)).project
        val repeated = TestVectorRunSession(reopened, replay.circuitName, TestVectorFormat.read(replay.vectorText))
        repeated.run()
        assertEquals(runner.results.map { it.passed }, repeated.results.map { it.passed })
    }

    @Test
    fun continuesSequencesByClockingBeforeApplyingTheNextInputValues() {
        val circuit = DemoCircuits.flipFlop()
        val table = TestVectorFormat.read("D Clock Q <set> <seq>\n1 0 0 1 1\n0 1 1 1 2\n1 0 1 1 3\n0 1 1 1 4\n0 0 0 2 1\n")
        val runner = TestVectorRunSession(CircuitProject(listOf(circuit)), circuit.name, table)
        runner.run()
        assertEquals(5, runner.passedCount, runner.results.joinToString { it.differences.toString() })
        assertEquals(listOf(0uL, 1uL, 1uL, 1uL, 0uL), runner.results.map { it.observed.last().toULongOrNull() })
    }

    @Test
    fun pauseCancelAndResetKeepTheCompletedRowsWithoutRunningAhead() {
        val circuit = DemoCircuits.andGate()
        val runner = TestVectorRunSession(CircuitProject(listOf(circuit)), circuit.name,
            TestVectorFormat.read("A B Y\n0 0 0\n1 1 1\n"))
        runner.advance()
        runner.pause()
        assertNull(runner.advance())
        assertEquals(1, runner.completedCount)
        runner.resume()
        runner.cancel()
        assertNull(runner.advance())
        assertEquals(VectorRunStatus.CANCELLED, runner.status)
        runner.reset()
        assertEquals(0, runner.completedCount)
        assertEquals(VectorRunStatus.READY, runner.status)
        runner.run()
        assertEquals(2, runner.passedCount)
    }

    @Test
    fun selectsANonMainCircuitAndChecksPinWidthsBeforeExecution() {
        val empty = CircuitDocument("main")
        val circuit = DemoCircuits.andGate()
        val project = CircuitProject(listOf(empty, circuit), "main")
        val runner = TestVectorRunSession(project, circuit.name, TestVectorFormat.read("A B Y\n1 1 1\n"))
        assertTrue(runner.advance()!!.passed)
        assertEquals(circuit.name, runner.state.circuitName)
        assertFailsWith<VectorBindingException> { TestVectorRunSession(project, circuit.name, TestVectorFormat.read("A[8]\n0x00\n")) }
        assertFailsWith<VectorBindingException> { TestVectorRunSession(project, circuit.name, TestVectorFormat.read("missing\n0\n")) }
        assertFailsWith<VectorBindingException> { TestVectorRunSession(project, "missing", runner.table) }
    }

    @Test
    fun supportsPartialMasksAndFloatingWhileRefusingDontCareInputs() {
        val input = CircuitComponent("a", ComponentKind.INPUT, GridPoint(10, 10), "A", attributes = mapOf("width" to "8"))
        val output = CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(30, 10), "Y", attributes = mapOf("width" to "8"))
        val circuit = CircuitDocument("bus", listOf(input, output), listOf(WireSegment(input.position, output.position)))
        val project = CircuitProject(listOf(circuit))
        val table = TestVectorFormat.read("A[8] Y[8]\n0xA5 0xAx\n<float> <float>\n0x00 <DC>\n")
        val runner = TestVectorRunSession(project, circuit.name, table)
        runner.run()
        assertEquals(3, runner.passedCount)
        assertFailsWith<VectorBindingException> { TestVectorRunSession(project, circuit.name, TestVectorFormat.read("A[8] Y[8]\n<DC> <DC>\n")) }
        assertFalse(TestVectorRunSession.compatible(LogicVector.unknown(8), LogicVector.error(8)))
    }

    @Test
    fun combinationalRowsResetSequentialStorageAndAmbiguousPinsAreRejected() {
        val circuit = DemoCircuits.flipFlop()
        val runner = TestVectorRunSession(CircuitProject(listOf(circuit)), circuit.name,
            TestVectorFormat.read("D Clock Q\n1 0 0\n1 0 0\n"))
        runner.run()
        assertEquals(2, runner.passedCount)
        val duplicate = circuit.copy(components = circuit.components + CircuitComponent("other", ComponentKind.INPUT, GridPoint(0, 0), "D"))
        assertFailsWith<VectorBindingException> {
            TestVectorRunSession(CircuitProject(listOf(duplicate)), duplicate.name, TestVectorFormat.read("D\n0\n"))
        }
    }
}
