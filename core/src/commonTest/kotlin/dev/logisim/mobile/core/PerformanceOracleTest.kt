package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PerformanceOracleTest {
    @Test
    fun originalAndImportedFixturesKeepLiteralLowHighLowOutputs() {
        for (fixture in fixtures()) {
            val oracle = PerformanceOracle(fixture)
            val rebound = oracle.verifyImported(read(oracle.serializedXml))
            assertEquals(fixture.observedOutputs.size, rebound.observedOutputs.size)
            assertTrue(rebound.observedOutputs.isNotEmpty())
            for (case in listOf(fixture, rebound, oracle.referenceFixture)) {
                val simulator = CircuitSimulator()
                var state = SimulationState()
                for (high in listOf(false, true, false)) {
                    state = simulator.settle(case.project, state, vectorInputs = case.inputs(high))
                    assertFalse(state.oscillating)
                    val literal = LogicVector.of(8, if (high) 0x5auL else 0uL)
                    case.observedOutputs.forEach { id -> assertEquals(literal, state.vectorValues[id]) }
                    oracle.verifyState(case, state, high)
                }
            }
        }
    }

    @Test
    fun serializedSourceAndImportedCanonicalRoundTripsStayIdempotent() {
        for (fixture in fixtures()) {
            val oracle = PerformanceOracle(fixture)
            val source = CircCodec.writeProject(fixture.project)
            assertEquals(source, oracle.serializedXml)
            oracle.verifySerialized(source)
            val imported = read(source)
            val canonical = CircCodec.writeProject(imported.project)
            val second = read(canonical)
            assertEquals(canonical, CircCodec.writeProject(second.project))
            assertEquals(canonical, CircCodec.writeProject(oracle.referenceFixture.project))
            oracle.verifyImported(imported)
            oracle.verifyImported(second)
        }
    }

    @Test
    fun sameLengthWrongSerializedPayloadIsRejected() {
        for (fixture in fixtures()) {
            val oracle = PerformanceOracle(fixture)
            val wrong = oracle.serializedXml.replaceFirst("name=\"AND Gate\"", "name=\"XOR Gate\"")
            assertNotEquals(oracle.serializedXml, wrong)
            assertEquals(oracle.serializedXml.length, wrong.length)
            val imported = read(wrong)
            assertEquals(fixture.project.mainCircuit.components.size, imported.project.mainCircuit.components.size)
            assertFailsWith<IllegalStateException> { oracle.verifySerialized(wrong) }
        }
    }

    @Test
    fun sameCountImportWithWrongComponentKindIsRejected() {
        for (fixture in fixtures()) {
            val id = fixture.drivenInputs.first()
            val wrong = changeMain(fixture.project) { circuit -> circuit.copy(components = circuit.components.map {
                if (it.id == id) it.copy(kind = ComponentKind.OUTPUT) else it
            }) }
            val imported = read(CircCodec.writeProject(wrong))
            assertEquals(fixture.project.mainCircuit.components.size, imported.project.mainCircuit.components.size)
            assertEquals(ComponentKind.OUTPUT, imported.project.mainCircuit.components.single { it.id == id }.kind)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
        }
    }

    @Test
    fun changedComponentIdentityCannotBeReboundByItsUnchangedKindAndPosition() {
        for (fixture in fixtures()) {
            val id = fixture.drivenInputs.first()
            val before = fixture.project.mainCircuit.components.single { it.id == id }
            val wrong = changeMain(fixture.project) { circuit -> circuit.copy(components = circuit.components.map {
                if (it.id == id) it.copy(id = "replaced-input-id") else it
            }) }
            val imported = read(CircCodec.writeProject(wrong))
            val replacement = imported.project.mainCircuit.components.single { it.id == "replaced-input-id" }
            assertEquals(before.kind, replacement.kind)
            assertEquals(before.position, replacement.position)
            assertEquals(fixture.project.mainCircuit.components.size, imported.project.mainCircuit.components.size)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
        }
    }

    @Test
    fun sameCountImportWithChangedWireOrComponentGeometryIsRejected() {
        for (fixture in fixtures()) {
            val original = fixture.project.mainCircuit
            val wire = original.wires.first()
            val changedWire = changeMain(fixture.project) { circuit -> circuit.copy(wires =
                listOf(wire.copy(from = GridPoint(wire.from.x + 10, wire.from.y))) + circuit.wires.drop(1)) }
            val id = fixture.drivenInputs.first()
            val changedComponent = changeMain(fixture.project) { circuit -> circuit.copy(components = circuit.components.map {
                if (it.id == id) it.copy(position = GridPoint(it.position.x + 10, it.position.y)) else it
            }) }
            for (wrong in listOf(changedWire, changedComponent)) {
                val imported = read(CircCodec.writeProject(wrong))
                assertEquals(original.components.size, imported.project.mainCircuit.components.size)
                assertEquals(original.wires.size, imported.project.mainCircuit.wires.size)
                assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
            }
        }
    }

    @Test
    fun sameCountImportWithChangedWidthOrEffectiveAttributeIsRejected() {
        for (fixture in fixtures()) {
            val id = fixture.drivenInputs.first()
            val changedWidth = changeMain(fixture.project) { circuit -> circuit.copy(components = circuit.components.map {
                if (it.id == id) it.copy(attributes = it.attributes + ("width" to "4")) else it
            }) }
            val changedGate = fixture.project.copy(circuits = fixture.project.circuits.map { circuit ->
                circuit.copy(components = circuit.components.map {
                    if (it.kind == ComponentKind.AND) it.copy(attributes = it.attributes + ("inputs" to "3")) else it
                })
            })
            for (wrong in listOf(changedWidth, changedGate)) {
                val imported = read(CircCodec.writeProject(wrong))
                assertEquals(fixture.project.mainCircuit.components.size, imported.project.mainCircuit.components.size)
                assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
            }
        }
    }

    @Test
    fun changedSubcircuitDefinitionIsRejectedEvenWhenMainCircuitCountsMatch() {
        for (size in listOf(2, 3)) {
            val fixture = PerformanceCircuits.hierarchicalGates(size)
            val wrong = fixture.project.copy(circuits = fixture.project.circuits.map { circuit ->
                if (circuit.name == fixture.project.mainCircuitName) circuit
                else circuit.copy(components = circuit.components.map {
                    if (it.kind == ComponentKind.AND) it.copy(kind = ComponentKind.OR) else it
                })
            })
            val imported = read(CircCodec.writeProject(wrong))
            val reference = read(CircCodec.writeProject(fixture.project)).project
            assertEquals(reference.mainCircuit, imported.project.mainCircuit)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
        }
    }

    @Test
    fun missingSubcircuitDefinitionIsRejectedThroughTheRealCodec() {
        for (size in listOf(2, 3)) {
            val fixture = PerformanceCircuits.hierarchicalGates(size)
            val oracle = PerformanceOracle(fixture)
            val missing = oracle.serializedXml.replace(Regex(
                "<circuit\\b[^>]*\\bname=\"and8\"[^>]*>.*?</circuit>", RegexOption.DOT_MATCHES_ALL), "")
            assertNotEquals(oracle.serializedXml, missing)
            val imported = CircCodec.readProject(missing)
            assertIs<CircProjectImportResult.Failure>(imported)
            assertFailsWith<IllegalStateException> { oracle.verifyImported(imported) }
        }
    }

    @Test
    fun changedCustomAppearancePortGeometryIsRejected() {
        for (size in listOf(2, 3)) {
            val fixture = PerformanceCircuits.hierarchicalGates(size)
            val reference = read(CircCodec.writeProject(fixture.project)).project
            val wrong = fixture.project.copy(circuits = fixture.project.circuits.map { circuit ->
                if (circuit.name == fixture.project.mainCircuitName) circuit
                else circuit.copy(extraXml = circuit.extraXml.map {
                    it.replace("pin=\"0,0\" x=\"60\"", "pin=\"0,0\" x=\"50\"")
                })
            })
            val imported = read(CircCodec.writeProject(wrong))
            val before = reference.mainCircuit.components.first { it.kind == ComponentKind.SUBCIRCUIT }
            val after = imported.project.mainCircuit.components.first { it.kind == ComponentKind.SUBCIRCUIT }
            assertNotEquals(before.explicitPorts, after.explicitPorts)
            assertEquals(reference.mainCircuit.components.size, imported.project.mainCircuit.components.size)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
        }
    }

    @Test
    fun changedCanonicalMetadataIsRejectedEvenWhenComponentAndWireCountsMatch() {
        for (fixture in fixtures()) {
            val wrong = fixture.project.copy(metadata = fixture.project.metadata + ("oracle-proof" to "changed"))
            val imported = read(CircCodec.writeProject(wrong))
            assertEquals("changed", imported.project.metadata["oracle-proof"])
            assertEquals(fixture.project.mainCircuit.components.size, imported.project.mainCircuit.components.size)
            assertEquals(fixture.project.mainCircuit.wires.size, imported.project.mainCircuit.wires.size)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyImported(imported) }
        }
    }

    @Test
    fun settledButWrongLiteralOutputIsRejected() {
        for (fixture in fixtures()) {
            val state = highState(fixture)
            val output = fixture.observedOutputs.first()
            val wrong = state.copy(vectorValues = state.vectorValues + (output to LogicVector.of(8, 0x5buL)))
            assertFalse(wrong.oscillating)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyState(fixture, wrong, true) }
        }
    }

    @Test
    fun settledButMissingOutputIsRejected() {
        for (fixture in fixtures()) {
            val state = highState(fixture)
            val wrong = state.copy(vectorValues = state.vectorValues - fixture.observedOutputs.first())
            assertFalse(wrong.oscillating)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyState(fixture, wrong, true) }
        }
    }

    @Test
    fun correctLowBitsWithWrongOutputWidthAreRejected() {
        for (fixture in fixtures()) {
            val state = highState(fixture)
            val output = fixture.observedOutputs.first()
            val wrong = state.copy(vectorValues = state.vectorValues + (output to LogicVector.of(4, 0xauL)))
            assertFalse(wrong.oscillating)
            assertEquals(0xauL, wrong.vectorValues.getValue(output).value)
            assertEquals(4, wrong.vectorValues.getValue(output).width)
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyState(fixture, wrong, true) }
        }
    }

    @Test
    fun oscillationIsRejectedEvenWhenAllOutputLiteralsStillMatch() {
        for (fixture in fixtures()) {
            val state = highState(fixture)
            val wrong = state.copy(oscillating = true)
            fixture.observedOutputs.forEach { assertEquals(LogicVector.of(8, 0x5auL), wrong.vectorValues[it]) }
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyState(fixture, wrong, true) }
        }
    }

    @Test
    fun missingHierarchicalStateIsRejectedEvenWhenRootOutputsStillMatch() {
        for (size in listOf(2, 3)) {
            val fixture = PerformanceCircuits.hierarchicalGates(size)
            val state = highState(fixture)
            assertEquals(size, state.circuitStates.size)
            val wrong = state.copy(circuitStates = state.circuitStates - state.circuitStates.keys.first())
            assertFalse(wrong.oscillating)
            fixture.observedOutputs.forEach { assertEquals(LogicVector.of(8, 0x5auL), wrong.vectorValues[it]) }
            assertFailsWith<IllegalStateException> { PerformanceOracle(fixture).verifyState(fixture, wrong, true) }
        }
    }

    @Test
    fun wrongRootOrChildIdentityIsRejectedEvenWhenOutputLiteralsStillMatch() {
        for (fixture in fixtures()) {
            val oracle = PerformanceOracle(fixture)
            val state = highState(fixture)
            val wrongStates = mutableListOf(
                state.copy(circuitName = "wrong-root-circuit"),
                state.copy(instancePath = "wrong-root-path"),
            )
            if (state.circuitStates.isNotEmpty()) {
                val (path, child) = state.circuitStates.entries.first()
                wrongStates += state.copy(circuitStates = state.circuitStates + (path to child.copy(circuitName = "wrong-child-circuit")))
                wrongStates += state.copy(circuitStates = state.circuitStates + (path to child.copy(instancePath = "wrong-child-path")))
            }
            for (wrong in wrongStates) {
                assertFalse(wrong.oscillating)
                fixture.observedOutputs.forEach { assertEquals(LogicVector.of(8, 0x5auL), wrong.vectorValues[it]) }
                assertFailsWith<IllegalStateException> { oracle.verifyState(fixture, wrong, true) }
            }
        }
    }

    @Test
    fun alteredOutputOrInputFixtureMappingsCannotBypassStateVerification() {
        for (fixture in fixtures()) {
            val oracle = PerformanceOracle(fixture)
            val state = highState(fixture)
            val wrongCases = listOf(
                fixture.copy(observedOutputs = emptyList()),
                fixture.copy(drivenInputs = fixture.drivenInputs.drop(1)),
                fixture.copy(highInputs = fixture.highInputs + (fixture.drivenInputs.first() to LogicVector.low(8))),
            )
            for (wrong in wrongCases) {
                assertFailsWith<IllegalStateException> { oracle.verifyState(wrong, state, true) }
            }
        }
    }

    private fun fixtures(): List<PerformanceCircuits.Case> = listOf(2, 3).flatMap { size ->
        listOf(PerformanceCircuits.independentGates(size), PerformanceCircuits.hierarchicalGates(size))
    }

    private fun read(xml: String): CircProjectImportResult.Success =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))

    private fun highState(fixture: PerformanceCircuits.Case): SimulationState {
        val state = CircuitSimulator().settle(fixture.project, vectorInputs = fixture.inputs(true))
        assertFalse(state.oscillating)
        fixture.observedOutputs.forEach { assertEquals(LogicVector.of(8, 0x5auL), state.vectorValues[it]) }
        return state
    }

    private fun changeMain(project: CircuitProject, transform: (CircuitDocument) -> CircuitDocument): CircuitProject =
        project.copy(circuits = project.circuits.map { if (it.name == project.mainCircuitName) transform(it) else it })
}
