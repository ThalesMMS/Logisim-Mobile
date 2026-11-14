package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitEditor
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PortDirection
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitAccessibilityModelTest {
    private val kind = ComponentKind("TEST_ACCESSIBLE_PIN")
    private val registry = ComponentRegistry(listOf(
        ComponentDefinition(kind, "Test pin", "#Test", layout = { component, _ -> ComponentLayout(
            listOf(ComponentTerminal("data", component.position,
                if (component.attributes["direction"] == "in") PortDirection.INPUT else PortDirection.OUTPUT,
                component.attributes["width"]?.toInt() ?: 1, "Data")),
            ComponentBounds(component.position.x - 5, component.position.y - 5, component.position.x + 5, component.position.y + 5)) }),
        ComponentDefinition(ComponentKind.TUNNEL, "Tunnel", "#Wiring", layout = { component, _ -> ComponentLayout(
            listOf(ComponentTerminal("bus", component.position, PortDirection.INOUT, component.width)),
            ComponentBounds(component.position.x - 5, component.position.y - 5, component.position.x + 5, component.position.y + 5)) }),
    ))

    private fun pin(id: String, x: Int, y: Int, width: Int = 1, label: String = "", input: Boolean = false) =
        CircuitComponent(id, kind, GridPoint(x, y), label, mapOf("width" to width.toString(), "direction" to if (input) "in" else "out"))

    @Test
    fun usesStableTerminalNamesAndDisambiguatesDuplicateOrUnnamedComponents() {
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = listOf(
            pin("later", 50, 30, label = "A"), pin("first", 20, 10, label = "A"), pin("same-position", 20, 10, label = "A"), pin("unnamed", 10, 10),
        )), registry)
        assertEquals(listOf("unnamed", "first", "same-position", "later"), graph.components.map { it.component.id })
        assertEquals(4, graph.components.map { it.name }.distinct().size)
        assertEquals("A (20, 10), first", graph.component("first")!!.name)
        assertEquals("Data", graph.ports.getValue(AccessiblePortId("first", "data")).label)
        assertEquals(listOf(AccessiblePortId("same-position", "data")), graph.ports.getValue(AccessiblePortId("first", "data")).connectedPorts)
    }

    @Test
    fun wiresAreDescribedByTheTerminalsTheyConnect() {
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = listOf(
            pin("a", 0, 0, label = "A"), pin("b", 40, 0, label = "B", input = true), pin("c", 0, 40, label = "C"),
        ), wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(20, 0)), WireSegment(GridPoint(20, 0), GridPoint(40, 0)),
            WireSegment(GridPoint(100, 100), GridPoint(120, 100)))), registry)
        assertEquals(listOf(AccessiblePortId("a", "data"), AccessiblePortId("b", "data")), graph.wirePorts(1))
        assertEquals("Connects A, Data; B, Data.", graph.wireConnectionText(0, AppLanguage.ENGLISH).resolve(AppLanguage.ENGLISH))
        assertEquals("Connects A, Data and 1 more.", graph.wireConnectionText(0, AppLanguage.ENGLISH, limit = 1).resolve(AppLanguage.ENGLISH))
        assertEquals("Not connected.", graph.wireConnectionText(2, AppLanguage.ENGLISH).resolve(AppLanguage.ENGLISH))
        assertTrue(graph.wirePorts(99).isEmpty())
    }

    @Test
    fun unmarkedCrossingsAreSeparateButAnEndpointIntroducesAJunction() {
        val horizontal = WireSegment(GridPoint(0, 50), GridPoint(100, 50))
        val vertical = WireSegment(GridPoint(50, 0), GridPoint(50, 100))
        val document = CircuitDocument(components = listOf(pin("h", 0, 50), pin("v", 50, 0)), wires = listOf(horizontal, vertical))
        val graph = CircuitAccessibilityGraph(document, registry)
        assertTrue(graph.ports.getValue(AccessiblePortId("h", "data")).connectedPorts.isEmpty())
        assertEquals(listOf(0), graph.ports.getValue(AccessiblePortId("h", "data")).wireIndices)
        val branched = CircuitAccessibilityGraph(document.copy(wires = document.wires + WireSegment(GridPoint(50, 50), GridPoint(70, 50))), registry)
        assertEquals(listOf(AccessiblePortId("v", "data")), branched.ports.getValue(AccessiblePortId("h", "data")).connectedPorts)
        assertEquals(listOf(0, 1, 2), branched.ports.getValue(AccessiblePortId("h", "data")).wireIndices)
    }

    @Test
    fun namesTunnelsAndKeepsDifferentLabelsApart() {
        val document = CircuitDocument(components = listOf(
            pin("source", 0, 0), pin("sink", 500, 0, input = true), pin("unrelated", 900, 0),
            CircuitComponent("t1", ComponentKind.TUNNEL, GridPoint(0, 0), "BUS"),
            CircuitComponent("t2", ComponentKind.TUNNEL, GridPoint(500, 0), "BUS"),
            CircuitComponent("t3", ComponentKind.TUNNEL, GridPoint(900, 0), "OTHER"),
        ))
        val graph = CircuitAccessibilityGraph(document, registry)
        val connected = graph.ports.getValue(AccessiblePortId("source", "data")).connectedPorts
        assertTrue(AccessiblePortId("sink", "data") in connected)
        assertFalse(AccessiblePortId("unrelated", "data") in connected)
        assertTrue(graph.connectedPortName(AccessiblePortId("t2", "bus"))!!.startsWith("BUS"))
    }

    @Test
    fun connectionsListReachesAProbeAcrossTunnelsWithAsciiPadding() {
        val width = mapOf("width" to "4")
        val source = CircuitComponent("source", ComponentKind.INPUT, GridPoint(0, 0), "Source", width)
        val near = CircuitComponent("near", ComponentKind.TUNNEL, GridPoint(100, 0), "BUS", width)
        val far = near.copy(id = "far", position = GridPoint(300, 40), label = " BUS ")
        val probe = CircuitComponent("probe", ComponentKind.PROBE, GridPoint(400, 40), "Probe")
        val isolated = listOf(
            near.copy(id = "case", position = GridPoint(0, 80), label = "bus"),
            near.copy(id = "empty", position = GridPoint(100, 80), label = ""),
            near.copy(id = "blank", position = GridPoint(200, 80), label = " \t "),
        )
        val document = CircuitDocument(
            components = listOf(source, near, far, probe) + isolated,
            wires = listOf(WireSegment(source.position, near.position), WireSegment(far.position, probe.position)),
        )
        val graph = CircuitAccessibilityGraph(document, ComponentCatalog.registry)
        val sourcePort = graph.ports.getValue(AccessiblePortId("source", "out"))
        val probePort = graph.ports.getValue(AccessiblePortId("probe", "in"))
        assertEquals(setOf(AccessiblePortId("near", "pin"), AccessiblePortId("far", "pin"), probePort.id),
            sourcePort.connectedPorts.toSet())
        assertTrue(sourcePort.id in probePort.connectedPorts)
        assertEquals(listOf(0, 1), sourcePort.wireIndices)
        assertEquals(listOf(0, 1), probePort.wireIndices)
        assertEquals(" BUS ", graph.component("far")!!.name)
        for (tunnel in isolated) {
            val port = graph.ports.getValue(AccessiblePortId(tunnel.id, "pin"))
            assertTrue(port.connectedPorts.isEmpty(), tunnel.id)
            assertTrue(port.wireIndices.isEmpty(), tunnel.id)
        }

        val simulation = CircuitSimulator().settle(document, vectorInputs = mapOf(source.id to LogicVector.of(4, 0xauL)))
        assertEquals("1010", probePort.signal(simulation)?.display)
    }

    @Test
    fun searchesEveryTermAcrossLabelsKindsIdsAndTerminalNames() {
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = listOf(
            pin("cpu-a", 0, 0, label = "Acumulador"), pin("cpu-b", 100, 0, label = "Entrada B"),
        )), registry)
        assertEquals(listOf("cpu-a"), graph.search("ACUM data").map { it.component.id })
        assertEquals(listOf("cpu-b"), graph.search("cpu-b Entrada").map { it.component.id })
        assertTrue(graph.search("Acumulador inexistente").isEmpty())
    }

    @Test
    fun largeSharedNetsRemainNavigableAndCanvasNodesStayBounded() {
        val count = 4_000
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = (0 until count).map { pin("p$it", it * 10, 0) },
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint((count - 1) * 10, 0)))), registry)
        val first = graph.ports.getValue(AccessiblePortId("p0", "data"))
        assertEquals(count - 1, first.connectedPorts.size)
        assertEquals(AccessiblePortId("p1", "data"), first.connectedPorts.first())
        assertEquals(AccessiblePortId("p3999", "data"), first.connectedPorts.last())
        assertEquals(250, accessibilityVisibleComponents(graph, CircuitAccessibilityViewport(1f, 10f, 10f, 100_000, 100), maximumNodes = 250).size)
        assertEquals(listOf("p20"), accessibilityVisibleComponents(graph, CircuitAccessibilityViewport(1f, 10f, 10f, 100_000, 100), setOf("p20"), 250).map { it.component.id })
        val nodes = accessibilityOverlayNodes(graph, CircuitAccessibilityViewport(1f, 10f, 10f, 100_000, 100), setOf("p3999"))
        assertEquals(250, nodes.components.size + nodes.ports.size)
        assertTrue(nodes.components.any { it.component.id == "p3999" })
        assertEquals(listOf(AccessiblePortId("p3999", "data")), nodes.ports.map { it.id })
    }

    @Test
    fun valuesDistinguishLowHighUnknownErrorAndPartialConflictsInBothLanguages() {
        assertEquals("Low, 0.", accessibleSignal(LogicVector.low(1)).resolve(AppLanguage.ENGLISH))
        assertEquals("Alto, 1.", accessibleSignal(LogicVector.high(1)).resolve(AppLanguage.PORTUGUESE))
        assertEquals("8 bits, hexadecimal 08.", accessibleSignal(LogicVector.of(8, 8uL)).resolve(AppLanguage.PORTUGUESE))
        assertEquals("8 undefined bits.", accessibleSignal(LogicVector.unknown(8)).resolve(AppLanguage.ENGLISH))
        assertEquals("8 bits conflitantes ou com erro.", accessibleSignal(LogicVector.error(8)).resolve(AppLanguage.PORTUGUESE))
        val partial = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR))
        assertEquals("4 bits. 1 error bit. 1 undefined bit. Binary E?10.", accessibleSignal(partial).resolve(AppLanguage.ENGLISH))
        assertNotEquals(accessibleSignal(LogicVector.unknown(1)), accessibleSignal(LogicVector.error(1)))
    }

    @Test
    fun inferredWidthUsesTheObservedVectorAndNeverCallsItZeroBits() {
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = listOf(pin("probe", 0, 0, width = 0))), registry)
        val port = graph.ports.getValue(AccessiblePortId("probe", "data"))
        assertEquals("Output Data, inferred width. No observed value.", port.description(SimulationState()).resolve(AppLanguage.ENGLISH))
        val simulation = SimulationState(portVectors = mapOf(GridPoint(0, 0) to LogicVector.of(12, 0xabcuL)))
        assertEquals("Output Data, 12 bits. 12 bits, hexadecimal ABC.", port.description(simulation).resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun connectionSessionUsesRealWireEditingAndHonorsEditorRejections() {
        val document = CircuitDocument(components = listOf(pin("out", 0, 0, width = 8), pin("in", 100, 50, width = 8, input = true), pin("wrong", 200, 0, width = 4)))
        val editor = CircuitEditor(document, registry)
        val graph = CircuitAccessibilityGraph(document, registry)
        val first = AccessibleConnectionSession().choose(graph, AccessiblePortId("out", "data")) { _, _ -> error("Should not connect before destination") }
        assertEquals(AccessiblePortId("out", "data"), first.session.start)
        val wrong = first.session.choose(graph, AccessiblePortId("wrong", "data")) { _, _ -> false }
        assertFalse(wrong.connected)
        assertEquals(document, editor.document)
        val result = first.session.choose(graph, AccessiblePortId("in", "data")) { from, to -> editor.connect(from, to); true }
        assertTrue(result.connected)
        assertNull(result.session.start)
        assertEquals(listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0)), WireSegment(GridPoint(100, 0), GridPoint(100, 50))), editor.document.wires)
        editor.undo()
        assertEquals(document, editor.document)
        // The editor owns width validation; the accessible route can also create a diagnostic fixture.
        val conflict = first.session.choose(graph, AccessiblePortId("wrong", "data")) { from, to -> editor.connect(from, to); true }
        assertTrue(conflict.connected)
        assertEquals(listOf(WireSegment(GridPoint(0, 0), GridPoint(200, 0))), editor.document.wires)
    }

    @Test
    fun removedOrRepeatedTerminalCancelsWithoutConnectingWrongCoordinates() {
        val document = CircuitDocument(components = listOf(pin("out", 0, 0), pin("in", 100, 0)))
        val graph = CircuitAccessibilityGraph(document, registry)
        val session = AccessibleConnectionSession().choose(graph, AccessiblePortId("out", "data")) { _, _ ->
            error("Starting a connection must not edit")
        }.session
        val cancelled = session.choose(graph, AccessiblePortId("out", "data")) { _, _ -> error("Cancel must not connect") }
        assertEquals("a11y_explorer_connection_cancelled", cancelled.notice.key)
        val removed = CircuitAccessibilityGraph(document.copy(components = listOf(document.components.last())), registry)
        val result = session.choose(removed, AccessiblePortId("in", "data")) { _, _ -> error("Stale terminal must not connect") }
        assertEquals("a11y_explorer_connection_missing", result.notice.key)
        assertNull(result.session.start)
    }

    @Test
    fun readerRouteCanBuildConnectAndSimulateAnAndGateWithoutDragging() {
        val types = ComponentCatalog.registry
        val editor = CircuitEditor(CircuitDocument(), types)
        val a = editor.add(ComponentKind.INPUT, GridPoint(0, 0))
        val b = editor.add(ComponentKind.INPUT, GridPoint(0, 40))
        val gate = editor.add(ComponentKind.AND, GridPoint(150, 20))
        val output = editor.add(ComponentKind.OUTPUT, GridPoint(200, 20))
        fun connect(fromComponent: String, fromPort: String, toComponent: String, toPort: String) {
            val graph = CircuitAccessibilityGraph(editor.document, types)
            val first = AccessibleConnectionSession().choose(graph, AccessiblePortId(fromComponent, fromPort)) { _, _ -> false }
            assertTrue(first.session.choose(graph, AccessiblePortId(toComponent, toPort)) { from, to -> editor.connect(from, to); true }.connected)
        }
        connect(a, "out", gate, "in0")
        connect(b, "out", gate, "in1")
        connect(gate, "out", output, "in")
        assertEquals(setOf(
            WireSegment(GridPoint(0, 0), GridPoint(100, 0)),
            WireSegment(GridPoint(0, 40), GridPoint(100, 40)),
            WireSegment(GridPoint(150, 20), GridPoint(200, 20)),
        ), editor.document.wires.toSet())
        val graph = CircuitAccessibilityGraph(editor.document, types)
        assertEquals(listOf(AccessiblePortId(gate, "in0")), graph.ports.getValue(AccessiblePortId(a, "out")).connectedPorts)
        assertEquals(listOf(AccessiblePortId(gate, "in1")), graph.ports.getValue(AccessiblePortId(b, "out")).connectedPorts)
        assertEquals(listOf(AccessiblePortId(output, "in")), graph.ports.getValue(AccessiblePortId(gate, "out")).connectedPorts)
        val simulator = CircuitSimulator(registry = types)
        for ((inputs, expected) in listOf(
            mapOf(a to LogicValue.LOW, b to LogicValue.LOW) to LogicValue.LOW,
            mapOf(a to LogicValue.HIGH, b to LogicValue.LOW) to LogicValue.LOW,
            mapOf(a to LogicValue.HIGH, b to LogicValue.HIGH) to LogicValue.HIGH,
            mapOf(a to LogicValue.LOW, b to LogicValue.HIGH) to LogicValue.LOW,
        )) {
            val simulation = simulator.settle(editor.document, inputs = inputs)
            assertEquals(expected, simulation.componentValues[output])
            assertTrue(simulation.diagnostics.isEmpty(), simulation.diagnostics.joinToString())
            assertFalse(simulation.oscillating)
            assertEquals(accessibleSignal(LogicVector.fromBit(expected)), accessibleSignal(graph.component(output)!!.signal(simulation)))
        }
    }

    @Test
    fun explorerCommandsUseTheEditorsRegistryHistoryAndInputSimulation() {
        val state = EditorState(null, ComponentCatalog.registry)
        state.newDocument()
        fun actions() = editorCircuitAccessibilityActions(EditorToolContext(
            project = state.project, document = state.document, simulation = state.simulation,
            selectedComponents = state.document.components.filter { it.id in state.selectedIds },
            applyProject = state::applyProject, applyDocument = state::applyDocument,
            selectCircuit = state::selectCircuit, selectComponent = state::selectComponent,
            showStatus = { state.reportStatus(it) },
            connect = state::connectTerminals, insertComponent = state::insertComponent,
            activateComponent = state::activateComponent,
            removeComponent = state::removeComponent,
            selectWire = { state.selectWire(it) }, removeWire = state::removeWire,
        ))
        fun insert(kind: ComponentKind, point: GridPoint): String {
            assertTrue(actions().insertComponent(kind, point))
            return state.selectedId!!
        }
        val a = insert(ComponentKind.INPUT, GridPoint(0, 0))
        val b = insert(ComponentKind.INPUT, GridPoint(0, 40))
        val gate = insert(ComponentKind.AND, GridPoint(150, 20))
        val output = insert(ComponentKind.OUTPUT, GridPoint(200, 20))
        for ((start, destination) in listOf(
            AccessiblePortId(a, "out") to AccessiblePortId(gate, "in0"),
            AccessiblePortId(b, "out") to AccessiblePortId(gate, "in1"),
            AccessiblePortId(gate, "out") to AccessiblePortId(output, "in"),
        )) {
            val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry)
            val session = AccessibleConnectionSession().choose(graph, start, actions().connect).session
            assertTrue(session.choose(graph, destination, actions().connect).connected)
        }
        val connected = state.document
        assertEquals(3, connected.wires.size)
        state.undo()
        assertEquals(2, state.document.wires.size)
        state.redo()
        assertEquals(connected, state.document)
        for ((desired, expected) in listOf(
            (LogicValue.LOW to LogicValue.LOW) to LogicValue.LOW,
            (LogicValue.HIGH to LogicValue.LOW) to LogicValue.LOW,
            (LogicValue.HIGH to LogicValue.HIGH) to LogicValue.HIGH,
            (LogicValue.LOW to LogicValue.HIGH) to LogicValue.LOW,
        )) {
            for ((id, value) in listOf(a to desired.first, b to desired.second)) {
                if (state.simulation.componentValues[id] != value) assertTrue(actions().activateComponent(id))
            }
            assertEquals(expected, state.simulation.componentValues[output])
            assertTrue(state.simulation.diagnostics.isEmpty(), state.simulation.diagnostics.joinToString())
        }
    }

    @Test
    fun insertionCoordinatesAreValidatedBeforeEditing() {
        assertEquals(GridPoint(-100, 20), accessibleInsertionPoint("-100", "20"))
        assertNull(accessibleInsertionPoint("abc", "20"))
        // Off-grid coordinates snap to the nearest grid point instead of being refused (X1).
        assertEquals(GridPoint(20, 20), accessibleInsertionPoint("21", "20"))
        assertNull(accessibleInsertionPoint("2147483648", "0"))
    }

    @Test
    fun automaticAnnouncementsAreThrottledButExplicitActionsAreNotDelayed() {
        val gate = AccessibilityAnnouncementGate(1_500)
        val first = message("a11y_explorer_connection_created")
        val second = message("a11y_explorer_connection_failed")
        assertEquals(first, gate.accept(first, 0, false))
        assertNull(gate.accept(first, 5_000, false))
        assertNull(gate.accept(second, 100, false))
        assertEquals(second, gate.accept(second, 200, true))
        assertEquals(first, gate.accept(first, 1_700, false))
    }
}
