package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlIpIntegrationTest {
    private val registry = ComponentCatalog.registry
    private val source = """
        -- Original source contains <, > and & for XML preservation.
        library ieee; use ieee.std_logic_1164.all;
        entity chip is generic (WIDTH : positive := 4);
        port (a, b : in std_logic; y : out std_logic); end entity;
        architecture rtl of chip is begin y <= a and b; end rtl;
    """.trimIndent()

    private fun document(name: String = "main") = CircuitDocument(name, components = listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(40, 140)),
        CircuitComponent("b", ComponentKind.INPUT, GridPoint(40, 150)),
        CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(100, 100), label = "HDL_source",
            attributes = mapOf("content" to source, "vhdl_WIDTH" to "8", "future-metadata" to "retained")),
        CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(300, 140)),
    ), wires = listOf(
        WireSegment(GridPoint(40, 140), GridPoint(100, 140)),
        WireSegment(GridPoint(40, 150), GridPoint(100, 150)),
        WireSegment(GridPoint(240, 140), GridPoint(300, 140)),
    ))

    private fun readHdlComponent(name: String, attributes: String = "") =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project source="5.1.0-dev" version="1.0">
              <lib desc="#HDL-IP" name="0"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" name="$name" loc="(120,120)">$attributes</comp>
              </circuit>
            </project>
        """.trimIndent(), registry)).project

    @Test fun omittedHdlSourceUsesDesktopTerminalsAndSurvivesRoundTrip() {
        fun terminal(name: String, x: Int, y: Int, direction: PortDirection, width: Int) =
            ComponentTerminal(name, GridPoint(x, y), direction, width, name)

        val cases = listOf(
            Triple("VHDL Entity", "VHDL_Component", listOf(
                terminal("horloge_i", 120, 160, PortDirection.INPUT, 1),
                terminal("val_i", 120, 170, PortDirection.INPUT, 4),
                terminal("max_o", 260, 160, PortDirection.OUTPUT, 1),
                terminal("cpt_o", 260, 170, PortDirection.OUTPUT, 4),
            )),
            Triple("BLIFCircuit", "lightswitch", listOf(
                terminal("button", 120, 160, PortDirection.INPUT, 1),
                terminal("reset", 120, 170, PortDirection.INPUT, 1),
                terminal("light_out", 260, 160, PortDirection.OUTPUT, 1),
            )),
        )
        for ((name, top, expectedPorts) in cases) {
            val project = readHdlComponent(name)
            val component = project.mainCircuit.components.single()
            val design = HdlIpLibrary.parse(component)
            assertTrue(design.isExecutable, design.diagnostics.toString())
            assertEquals(top, design.topLevel)
            assertEquals(expectedPorts, component.layout(registry).ports)
            val initial = CircuitSimulator(registry = registry).settle(project)
            if (component.kind == HdlIpLibrary.BLIF) {
                // BLIF runs natively, as in Evolution; only VHDL waits for a companion session.
                assertIs<BlifCircuitState>(initial.componentStates[component.id])
            } else {
                val state = assertIs<HdlIpState>(initial.componentStates[component.id])
                assertEquals(HdlIpStatus.UNAVAILABLE, state.status)
                assertEquals(null, state.sessionId)
            }
            val xml = CircCodec.writeProject(project, registry)
            assertFalse(xml.contains("name=\"content\""))
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry))
                .project.mainCircuit.components.single()
            assertEquals(design, HdlIpLibrary.parse(reopened))
            assertEquals(expectedPorts, reopened.layout(registry).ports)
        }
    }

    @Test fun explicitHdlSourceIncludingEmptySourceDoesNotUseTheDefault() {
        val explicitSources = mapOf(
            "VHDL Entity" to "entity custom is port (i : in std_logic; o : out std_logic); end custom;",
            "BLIFCircuit" to ".model custom&#10;.inputs i&#10;.outputs o&#10;.end",
        )
        for ((name, explicit) in explicitSources) {
            val project = readHdlComponent(name, """<a name="content">$explicit</a>""")
            val component = project.mainCircuit.components.single()
            val design = HdlIpLibrary.parse(component)
            assertTrue(design.isExecutable, design.diagnostics.toString())
            assertEquals("custom", design.topLevel)
            assertEquals(explicit.replace("&#10;", "\n"), design.source)
            assertEquals(listOf("i", "o"), design.ports.map { it.name })

            val empty = readHdlComponent(name, """<a name="content" val=""/>""").mainCircuit.components.single()
            val emptyDesign = HdlIpLibrary.parse(empty)
            assertEquals("", emptyDesign.source)
            // Evolution's BLIF parser accepts an empty source as a circuit without ports.
            assertEquals(name == "BLIFCircuit", emptyDesign.isExecutable)
            assertTrue(emptyDesign.ports.isEmpty())
        }
    }

    @Test fun sourceParametersMetadataAndDynamicPortsSurviveCircRoundTrip() {
        val original = document()
        val xml = CircCodec.writeProject(CircuitProject(listOf(original)), registry)
        assertTrue(xml.contains("VHDL Entity"))
        assertTrue(xml.contains("#HDL-IP"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project.mainCircuit
        val component = reopened.components.single { it.kind == HdlIpLibrary.VHDL }
        assertEquals(source, component.attributes["content"])
        assertEquals("8", component.attributes["vhdl_WIDTH"])
        assertEquals("8", HdlIpLibrary.parse(component).parameters["WIDTH"])
        assertEquals("retained", component.attributes["future-metadata"])
        assertEquals("HDL_source", component.label)
        assertEquals(HdlIpLibrary.layout(original.components[2], HdlIpLibrary.parse(original.components[2])), component.layout(registry))
        assertTrue(CircuitNetworks(reopened, registry).diagnostics.isEmpty())
        val defaultProject = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(reopened, defaultProject.mainCircuit)
        val withoutHdl = ComponentRegistry(registry.definitions.filterNot { it.kind in setOf(HdlIpLibrary.VHDL, HdlIpLibrary.BLIF) })
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml, withoutHdl))
        val largeSource = "-- " + "x".repeat(128 * 1024) + "\n" + source
        val largeDocument = original.copy(components = original.components.map { item ->
            if (item.kind == HdlIpLibrary.VHDL) item.copy(attributes = item.attributes + ("content" to largeSource)) else item
        })
        val largeXml = CircCodec.writeProject(CircuitProject(listOf(largeDocument)), registry)
        val large = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(largeXml, registry)).project
            .mainCircuit.components.single { it.kind == HdlIpLibrary.VHDL }
        assertEquals(largeSource, large.attributes["content"])
        assertFalse(HdlIpLibrary.parse(large).isExecutable)
        assertTrue(HdlIpLibrary.parse(large).diagnostics.any { it.code == "invalid-source" })
    }

    @Test fun companionSamplesEnterMotorAtTheirRequestedTimeAndFailureResetClearThem() {
        val document = document()
        val simulator = CircuitSimulator(registry = registry)
        val stimuli = mapOf("a" to LogicValue.HIGH, "b" to LogicValue.HIGH)
        val initial = simulator.settle(document, inputs = stimuli)
        assertEquals(LogicValue.UNKNOWN, initial.componentValues["y"])
        val time = initial.propagationTime + 5
        val observed = mutableListOf<Pair<Long, LogicValue>>()
        val ready = simulator.settle(document, initial, inputs = stimuli, events = mapOf("hdl" to listOf(
            CompanionHdlStarted("session", 1),
            CompanionHdlRequest("session", 1, 0, time, mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1))),
            CompanionHdlReply("session", 1, 0, time, mapOf("y" to LogicVector.high(1))),
        )), observer = { state, instant -> observed += instant to (state.componentValues["y"] ?: LogicValue.UNKNOWN) })
        assertEquals(time, ready.propagationTime)
        assertEquals(LogicValue.HIGH, ready.componentValues["y"])
        assertTrue(observed.any { it.first < time && it.second == LogicValue.UNKNOWN }, observed.toString())
        assertFalse(observed.any { it.first < time && it.second == LogicValue.HIGH }, observed.toString())
        val failed = simulator.settle(document, ready, inputs = stimuli, events = mapOf("hdl" to listOf(
            CompanionHdlFailed("session", 1, 0, "Companion process failed"))))
        assertEquals(LogicValue.ERROR, failed.componentValues["y"])
        assertTrue(failed.diagnostics.any { it.contains("Companion process failed") })
        val reset = simulator.settle(document, failed, inputs = stimuli, reset = true)
        assertEquals(LogicValue.UNKNOWN, reset.componentValues["y"])
        assertEquals(null, assertIs<HdlIpState>(reset.componentStates["hdl"]).sessionId)
    }

    @Test fun twoSubcircuitInstancesKeepCompanionSessionsIndependent() {
        val ports = listOf(
            ComponentPort("a", GridPoint(-40, 0), PortDirection.INPUT),
            ComponentPort("b", GridPoint(-40, 20), PortDirection.INPUT),
            ComponentPort("y", GridPoint(0, 0), PortDirection.OUTPUT),
        )
        val root = CircuitDocument("main", components = listOf(
            CircuitComponent("one", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child", explicitPorts = ports),
            CircuitComponent("two", ComponentKind.SUBCIRCUIT, GridPoint(100, 240), circuitName = "child", explicitPorts = ports),
            CircuitComponent("oneA", ComponentKind.INPUT, GridPoint(60, 100)),
            CircuitComponent("oneB", ComponentKind.INPUT, GridPoint(60, 120)),
            CircuitComponent("twoA", ComponentKind.INPUT, GridPoint(60, 240)),
            CircuitComponent("twoB", ComponentKind.INPUT, GridPoint(60, 260)),
            CircuitComponent("oneY", ComponentKind.OUTPUT, GridPoint(100, 100)),
            CircuitComponent("twoY", ComponentKind.OUTPUT, GridPoint(100, 240)),
        ))
        val project = CircuitProject(listOf(root, document("child")), "main")
        val simulator = CircuitSimulator(registry = registry)
        val inputs = mapOf("oneA" to LogicValue.HIGH, "oneB" to LogicValue.HIGH,
            "twoA" to LogicValue.LOW, "twoB" to LogicValue.HIGH)
        val initial = simulator.settle(project, inputs = inputs)
        val time = initial.propagationTime + 2
        val ready = simulator.settle(project, initial, inputs = inputs, events = mapOf("one/hdl" to listOf(
            CompanionHdlStarted("one-session", 1),
            CompanionHdlRequest("one-session", 1, 0, time, mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1))),
            CompanionHdlReply("one-session", 1, 0, time, mapOf("y" to LogicVector.high(1))),
        )))
        assertEquals(LogicValue.HIGH, ready.componentValues["oneY"])
        assertEquals(LogicValue.UNKNOWN, ready.componentValues["twoY"])
        assertEquals("one-session", assertIs<HdlIpState>(ready.circuitStates.getValue("one").componentStates["hdl"]).sessionId)
        assertEquals(null, assertIs<HdlIpState>(ready.circuitStates.getValue("two").componentStates["hdl"]).sessionId)
    }

    @Test fun changingInputsBeforeAScheduledCompanionSampleCannotRestoreStaleKnownValues() {
        val base = document()
        val document = base.copy(components = base.components + listOf(
            CircuitComponent("pulse", ComponentKind.INPUT, GridPoint(400, 100)),
            CircuitComponent("inverter", ComponentKind.NOT, GridPoint(450, 100)),
            CircuitComponent("probe", ComponentKind.OUTPUT, GridPoint(500, 100)),
        ), wires = base.wires + listOf(
            WireSegment(GridPoint(400, 100), GridPoint(420, 100)),
            WireSegment(GridPoint(450, 100), GridPoint(500, 100)),
        ))
        val simulator = CircuitSimulator(registry = registry)
        val inputs = mapOf("a" to LogicValue.HIGH, "b" to LogicValue.HIGH, "pulse" to LogicValue.LOW)
        val initial = simulator.settle(document, inputs = inputs)
        val time = initial.propagationTime + 10
        val pending = simulator.stepEvent(document, initial, inputs = inputs + ("pulse" to LogicValue.HIGH),
            events = mapOf("hdl" to listOf(
                CompanionHdlStarted("session", 1),
                CompanionHdlRequest("session", 1, 0, time, mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1))),
                CompanionHdlReply("session", 1, 0, time, mapOf("y" to LogicVector.high(1))),
            )))
        assertTrue(pending.propagationTime < time)
        assertTrue(pending.pendingEvents.any { it.componentId == "hdl" && it.time == time })
        val changed = simulator.settle(document, pending, inputs = inputs + mapOf("a" to LogicValue.LOW, "pulse" to LogicValue.HIGH))
        assertEquals(LogicValue.UNKNOWN, changed.componentValues["y"])
    }
}
