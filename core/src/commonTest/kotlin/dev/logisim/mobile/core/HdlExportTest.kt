package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdlExportTest {
    @Test
    fun exportIsDeterministicAndRetainsSourcePortIdentities() {
        val fixture = HdlFixtureCases.andBus()
        for (language in HdlLanguage.entries) {
            val first = HdlExporter.export(fixture.project, language)
            assertTrue(first.successful, first.diagnostics.joinToString())
            assertEquals(first, HdlExporter.export(fixture.project, language))
            assertEquals(listOf("i0", "i1", "o0"), first.ports.map { it.sourceComponentId })
            assertEquals(listOf(8, 8, 8), first.ports.map { it.width })
            assertEquals(listOf("c_and_bus.${language.extension}"), first.files.map { it.path })
        }
    }

    @Test
    fun allFixturesAgreeWithTheActualCircuitSimulatorAndExportBothLanguages() {
        verifyFixtures(HdlFixtureCases.all())
    }

    @Test
    fun ttlGatePackagesAgreeWithTheActualCircuitSimulatorAndExportBothLanguages() {
        verifyFixtures(listOf(HdlFixtureCases.ttl7400()) + HdlFixtureCases.ttlBasicGates())
    }

    private fun verifyFixtures(fixtures: List<HdlFixtureCase>) {
        for (fixture in fixtures) {
            val simulator = CircuitSimulator()
            var state = SimulationState()
            for ((index, frame) in fixture.vectors.withIndex()) {
                val inputs = frame.inputs.mapValues { (id, value) ->
                    LogicVector.of(fixture.project.mainCircuit.components.single { it.id == id }.width, value)
                }
                state = simulator.settle(fixture.project, state, vectorInputs = inputs,
                    options = mapOf("randomSeed" to "0x5deece66d"))
                assertFalse(state.oscillating, "${fixture.id} vector $index")
                for ((id, expected) in frame.outputs) assertEquals(expected, state.vectorValues[id]?.toULongOrNull(), "${fixture.id} vector $index output $id")
                for (id in frame.errorOutputs) assertTrue(state.vectorValues[id]?.isError == true, "${fixture.id} vector $index output $id")
            }
            for (language in HdlLanguage.entries) {
                val result = HdlExporter.export(fixture.project, language)
                assertTrue(result.successful, "${fixture.id}/$language: ${result.diagnostics}")
                assertTrue(result.files.isNotEmpty())
            }
        }
    }

    @Test
    fun hierarchyUsesOneChildDefinitionAndSeparateInstances() {
        val result = HdlExporter.export(HdlFixtureCases.hierarchy().project, HdlLanguage.VERILOG)
        assertTrue(result.successful, result.diagnostics.joinToString())
        assertEquals(listOf("c_child.v", "c_main.v"), result.files.map { it.path })
        assertEquals(2, Regex("c_child v_instance[01]").findAll(result.files.last().content).count())
    }

    @Test
    fun instancePinWidthsMustMatchTheReferencedCircuit() {
        val project = HdlFixtureCases.hierarchy().project
        val main = project.mainCircuit.copy(components = project.mainCircuit.components.map { component ->
            if (component.id != "instance0") component else component.copy(explicitPorts = component.explicitPorts.mapIndexed { index, port ->
                if (index == 0) port.copy(width = 2) else port
            })
        })
        val invalid = CircuitProject(project.circuits.map { if (it.name == main.name) main else it }, project.mainCircuitName)
        val result = HdlExporter.export(invalid, HdlLanguage.VHDL)
        assertFalse(result.successful)
        assertTrue(result.files.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "HDL_INSTANCE_PORT_WIDTH" && it.componentId == "instance0" })
    }

    @Test
    fun caseInsensitiveAndUnicodeNameCollisionsAreResolvedReproducibly() {
        val fixture = HdlFixtureCases.andBus()
        val main = fixture.project.mainCircuit.copy(name = "entity", components = fixture.project.mainCircuit.components.map {
            it.copy(label = if (it.kind == ComponentKind.INPUT) "A" else "a")
        })
        val result = HdlExporter.export(CircuitProject(listOf(main)), HdlLanguage.VHDL)
        assertTrue(result.successful, result.diagnostics.joinToString())
        assertEquals("c_entity", result.topLevelName)
        assertEquals(3, result.ports.map { it.name.lowercase() }.distinct().size)
        assertTrue(result.ports.all { it.name.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals("sa_u00ed_da", hdlIdentifier("Saída"))
    }

    @Test
    fun unsupportedRamProfilesFailWithoutProducingPartialFiles() {
        val ram = CircuitComponent("ram", ComponentKind.RAM, GridPoint(200, 100))
        val outputEnable = ram.layout().inputs.single { it.name == MemoryLayouts.outputEnableName(0) }
        val profiles = listOf(
            listOf(ram.copy(attributes = mapOf("databus" to "bidir"))),
            listOf(ram.copy(attributes = mapOf("databus" to "bibusControlled"))),
            listOf(ram.copy(attributes = mapOf("trigger" to "low"))),
            listOf(ram.copy(attributes = mapOf("asyncread" to "true"))),
            listOf(ram.copy(attributes = mapOf("clearpin" to "true"))),
            listOf(ram.copy(attributes = mapOf("enables" to "line", "trigger" to "high"))),
            listOf(ram.copy(attributes = mapOf("enables" to "line", "databus" to "bidir"))),
            listOf(ram.copy(attributes = mapOf("enables" to "line", "clearpin" to "true"))),
            listOf(ram.copy(attributes = mapOf("dataWidth" to "16", "byteenables" to "byteEnables", "asyncread" to "true"))),
            listOf(ram, CircuitComponent("oe", ComponentKind.INPUT, outputEnable.position)),
        )
        for (components in profiles) for (language in HdlLanguage.entries) {
            val document = CircuitDocument("unsupported", components)
            val result = HdlExporter.export(CircuitProject(listOf(document)), language)
            assertFalse(result.successful)
            assertTrue(result.files.isEmpty())
            assertTrue(result.diagnostics.any {
                it.code == "HDL_UNSUPPORTED_CONFIGURATION" && it.circuitId == document.id && it.componentId == "ram"
            }, "$language, $components: ${result.diagnostics}")
        }
    }

    @Test
    fun undrivenPinsAndMultipleDriversAreOriginLinkedErrors() {
        val point = GridPoint(100, 100)
        val undriven = CircuitDocument("undriven", listOf(CircuitComponent("out", ComponentKind.OUTPUT, point)))
        val missing = HdlExporter.export(CircuitProject(listOf(undriven)), HdlLanguage.VHDL)
        assertFalse(missing.successful)
        assertTrue(missing.diagnostics.any { it.code == "HDL_UNDRIVEN_OUTPUT" && it.componentId == "out" })
        val shorted = CircuitDocument("shorted", listOf(
            CircuitComponent("a", ComponentKind.INPUT, point), CircuitComponent("b", ComponentKind.INPUT, point),
            CircuitComponent("out", ComponentKind.OUTPUT, point)))
        val conflict = HdlExporter.export(CircuitProject(listOf(shorted)), HdlLanguage.VERILOG)
        assertFalse(conflict.successful)
        assertTrue(conflict.diagnostics.any { it.code == "HDL_MULTIPLE_DRIVERS" })
    }

    @Test
    fun twoOutputsOfOneComponentCannotDriveTheSameNetwork() {
        val ff = HdlFixtureCases.flipFlop().project.mainCircuit
        val outputs = ff.components.single { it.id == "ff" }.layout().outputs
        val shorted = ff.copy(wires = listOf(WireSegment(outputs[0].position, outputs[1].position)))
        val result = HdlExporter.export(CircuitProject(listOf(shorted)), HdlLanguage.VERILOG)
        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { it.code == "HDL_MULTIPLE_DRIVERS" && it.componentId == "ff" })
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun recursionAndOpenCollectorConfigurationsAreRejectedBeforeArtifactExport() {
        val loop = CircuitDocument("loop", listOf(CircuitComponent("self", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "loop")))
        val recursive = HdlExporter.export(CircuitProject(listOf(loop)), HdlLanguage.VHDL)
        assertFalse(recursive.successful)
        assertTrue(recursive.files.isEmpty())
        assertTrue(recursive.diagnostics.any { it.code == "HDL_RECURSIVE_CIRCUIT" && it.componentId == "self" })
        val fixture = HdlFixtureCases.andBus()
        val document = fixture.project.mainCircuit.copy(components = fixture.project.mainCircuit.components.map {
            if (it.id == "gate") it.copy(attributes = it.attributes + ("out" to "0Z")) else it
        })
        val unsupported = HdlExporter.export(CircuitProject(listOf(document)), HdlLanguage.VERILOG)
        assertFalse(unsupported.successful)
        assertTrue(unsupported.files.isEmpty())
        assertTrue(unsupported.diagnostics.any { it.code == "HDL_UNSUPPORTED_CONFIGURATION" && it.componentId == "gate" })
    }

    @Test
    fun diagnosticNoticesResolveInBothAppLanguages() {
        val diagnostic = HdlExportDiagnostic("HDL_UNSUPPORTED_CONFIGURATION", HdlDiagnosticSeverity.ERROR,
            "Mixed signed/unsigned multiplication has no RTL mapping yet.", "main", "multiply",
            reason = dev.logisim.mobile.core.localization.message("hdl_reason_mixed_multiply"))
        assertEquals("Unsupported HDL configuration: Mixed signed/unsigned multiplication has no RTL mapping yet.",
            diagnostic.notice.resolve(dev.logisim.mobile.core.localization.AppLanguage.ENGLISH))
        assertEquals("Configuração HDL não suportada: Multiplicação mista com e sem sinal ainda não tem mapeamento RTL.",
            diagnostic.notice.resolve(dev.logisim.mobile.core.localization.AppLanguage.PORTUGUESE))
    }

    @Test
    fun invalidAttributesRetainTheirComponentOrigin() {
        val invalid = CircuitDocument("invalid", listOf(CircuitComponent("bad_gate", ComponentKind.AND,
            GridPoint(200, 100), attributes = mapOf("width" to "65"))))
        val result = HdlExporter.export(CircuitProject(listOf(invalid)), HdlLanguage.VHDL)
        assertFalse(result.successful)
        assertTrue(result.files.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "HDL_INVALID_LAYOUT" && it.componentId == "bad_gate" && it.circuitId == invalid.id })
    }

    @Test
    fun clockBecomesAnExplicitTopLevelInputInsteadOfASimulationOscillator() {
        val document = CircuitDocument("clock", listOf(
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100)),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100))))
        val result = HdlExporter.export(CircuitProject(listOf(document)), HdlLanguage.VERILOG)
        assertTrue(result.successful, result.diagnostics.joinToString())
        assertTrue(result.diagnostics.any { it.code == "HDL_EXTERNAL_STIMULUS" && it.componentId == "clock" })
        assertEquals(PortDirection.INPUT, result.ports.single { it.sourceComponentId == "clock" }.direction)
        assertFalse(result.files.single().content.contains("always #"))
    }

    @Test
    fun ttlGatePackagesWithExposedPowerPinsCannotBeExported() {
        for (fixture in listOf(HdlFixtureCases.ttl7400()) + HdlFixtureCases.ttlBasicGates()) {
            val document = fixture.project.mainCircuit.copy(components = fixture.project.mainCircuit.components.map {
                if (it.id == "chip") it.copy(attributes = mapOf("VccGndPorts" to "true")) else it
            })
            for (language in HdlLanguage.entries) {
                val result = HdlExporter.export(CircuitProject(listOf(document)), language)
                assertFalse(result.successful)
                assertTrue(result.files.isEmpty())
                assertTrue(result.diagnostics.any {
                    it.code == "HDL_UNSUPPORTED_CONFIGURATION" && it.componentId == "chip"
                }, "${fixture.id}/$language: ${result.diagnostics}")
            }
        }
    }
}
