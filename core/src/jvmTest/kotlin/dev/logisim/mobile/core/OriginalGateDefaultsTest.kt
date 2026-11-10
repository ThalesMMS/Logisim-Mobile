package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OriginalGateDefaultsTest {
    private val registry = ComponentCatalog.registry

    @Test
    fun originalAndPreservesAllFiveTerminalsAndSimulationAfterReopening() {
        val source = originalAndSource()
        assertFalse(source.contains("name=\"inputs\""))
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project
        assertOriginalAnd(project)

        val saved = CircCodec.writeProject(project)
        assertFalse(saved.contains("name=\"inputs\""))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved)).project
        assertOriginalAnd(reopened)
    }

    @Test
    fun explicitInputCountsOverrideTheOriginalDefault() {
        for (count in listOf(2, 3, 5, 64)) {
            val source = originalAndSource().replace(
                "<comp lib=\"1\" loc=\"(300,200)\" name=\"AND Gate\"/>",
                "<comp lib=\"1\" loc=\"(300,200)\" name=\"AND Gate\"><a name=\"inputs\" val=\"$count\"/></comp>",
            )
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project
            val and = project.mainCircuit.components.single { it.kind == ComponentKind.AND }
            assertEquals(count.toString(), and.attributes["inputs"])
            assertFalse("inputs" in and.implicitAttributes)
            assertEquals(count, registry.layout(and).inputs.size)
            val reopened = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(project)),
            ).project
            val reopenedAnd = reopened.mainCircuit.components.single { it.kind == ComponentKind.AND }
            assertEquals(count, registry.layout(reopenedAnd).inputs.size)
            if (count == 2) {
                assertEquals(LogicValue.HIGH, CircuitSimulator().settle(project).componentValues[and.id])
                assertEquals(LogicValue.HIGH, CircuitSimulator().settle(reopened).componentValues[reopenedAnd.id])
            }
        }
    }

    @Test
    fun sharedOriginalGateDefaultsDoNotChangeModernImportsOrNewGates() {
        val gates = listOf("AND Gate", "OR Gate", "NAND Gate", "NOR Gate", "XOR Gate", "XNOR Gate",
            "Odd Parity", "Even Parity")
        for (name in gates) {
            for ((version, count) in mapOf("2.6.3" to 5, "2.7.1" to 5, "2.7.2" to 2, "3.0.0" to 2, "5.1.0-dev" to 2)) {
                val source = """
                    <project source="$version" version="1.0">
                      <lib desc="#Gates" name="1"/>
                      <main name="main"/>
                      <circuit name="main"><comp lib="1" loc="(300,200)" name="$name"/></circuit>
                    </project>
                """.trimIndent()
                val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project
                val imported = project.mainCircuit.components.single()
                assertEquals(count, registry.layout(imported).inputs.size, "$name from $version")
                val created = registry.create(imported.kind, "new", GridPoint(300, 200))
                assertEquals(2, registry.layout(created).inputs.size, "new $name")
                val withNewGate = project.withCircuit(project.mainCircuit.copy(components = listOf(created)))
                val reopened = assertIs<CircProjectImportResult.Success>(
                    CircCodec.readProject(CircCodec.writeProject(withNewGate)),
                ).project.mainCircuit.components.single()
                assertEquals(2, registry.layout(reopened).inputs.size, "new $name reopened from $version")
            }
        }
    }

    private fun assertOriginalAnd(project: CircuitProject) {
        val document = project.mainCircuit
        val and = document.components.single { it.kind == ComponentKind.AND }
        assertEquals("5", and.attributes["inputs"])
        assertEquals("5", and.implicitAttributes["inputs"])
        val ports = registry.layout(and).inputs
        assertEquals(listOf(180, 190, 200, 210, 220).map { GridPoint(250, it) }, ports.map { it.position })
        val topology = CircuitTopology.elaborate(document)
        assertEquals(listOf(true, false, true, false, true), ports.map { port ->
            assertNotNull(topology.netAt(port.position)).terminals
                .single { it.reference == DiagnosticTerminalRef(and.id, port.name) }.connected
        })
        for (y in listOf(180, 200, 220)) {
            val driver = document.components.single { it.kind == ComponentKind.CONSTANT && it.position == GridPoint(200, y) }
            val references = assertNotNull(topology.netAt(GridPoint(250, y))).terminals.map { it.reference }
            assertTrue(DiagnosticTerminalRef(driver.id, "out") in references)
            assertTrue(DiagnosticTerminalRef(and.id, "in${(y - 180) / 10}") in references)
        }
        val state = CircuitSimulator().settle(project)
        val output = document.components.single { it.label == "Y" }
        assertEquals(LogicValue.LOW, state.componentValues[and.id])
        assertEquals(LogicValue.LOW, state.componentValues[output.id])
        assertFalse(state.oscillating)
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString())
    }

    private fun originalAndSource(): String = assertNotNull(javaClass.getResourceAsStream("/original-and-default-inputs.circ"))
        .bufferedReader().use { it.readText() }
}
