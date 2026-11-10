package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentBehavior
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentEvaluation
import dev.logisim.mobile.core.ComponentEvaluationContext
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryEditResult
import dev.logisim.mobile.core.MemoryEditorModel
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.MemoryImageReplaced
import dev.logisim.mobile.core.PortDirection
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BuiltInEditorToolsTest {
    private val memoryRegistry get() = ComponentCatalog.registry.withDefinitions(MemoryComponents.definitions())

    @Test
    fun builtInFactoriesExposeWorkingToolsWithoutCallerExtensions() {
        val tools = createBuiltInEditorTools(memoryRegistry)
        assertEquals(listOf("combinational-analysis", "trace", "test-vectors", "memory-editor", "accessibility", "diagnostics", "hdl-export"), tools.map { it.id })
        assertNotNull(tools.single { it.id == "trace" }.onSimulation)
    }

    @Test
    fun extensionsReplaceDefaultsAndDuplicateExtensionIdsKeepTheFirst() {
        val a = tool("analysis")
        val trace = tool("trace")
        val replacement = tool("trace")
        val custom = tool("custom")
        val merged = mergeEditorTools(listOf(a, trace), listOf(replacement, tool("trace"), custom))
        assertEquals(listOf("analysis", "trace", "custom"), merged.map { it.id })
        assertSame(a, merged[0])
        assertSame(replacement, merged[1])
        assertSame(custom, merged[2])
    }

    @Test
    fun traceSelectorsUseTheInjectedRegistryForDynamicComponentPorts() {
        val kind = ComponentKind("CUSTOM_TRACE_OUTPUT")
        val definition = ComponentDefinition(kind, "CustomTraceOutput", "#Test", layout = { component, _ ->
            ComponentLayout(listOf(ComponentTerminal("value", component.position, PortDirection.OUTPUT, 8)),
                ComponentBounds(component.position.x - 10, component.position.y - 10, component.position.x + 10, component.position.y + 10))
        })
        val project = CircuitProject(listOf(CircuitDocument("main", listOf(CircuitComponent("custom", kind, GridPoint(20, 20))))))
        val signals = TraceSignals.fromProject(project, ComponentRegistry(listOf(definition)))
        assertEquals(1, signals.size)
        assertEquals(8, signals.single().width)
        assertEquals("custom", signals.single().reference.componentId)
    }

    @Test
    fun vectorPanelSessionUsesTheSameDynamicRegistryAsTheEditor() {
        val kind = ComponentKind("CUSTOM_VECTOR_NOT")
        val definition = ComponentDefinition(kind, "CustomVectorNot", "#Test", layout = { component, _ ->
            ComponentLayout(listOf(
                ComponentTerminal("in", GridPoint(component.position.x - 20, component.position.y), PortDirection.INPUT),
                ComponentTerminal("out", component.position, PortDirection.OUTPUT),
            ), ComponentBounds(component.position.x - 20, component.position.y - 10, component.position.x, component.position.y + 10))
        }, behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(
                mapOf("out" to (context.inputs["in"] ?: LogicVector.unknown(1)).inverted()),
            )
        })
        val registry = ComponentCatalog.registry.withAdditional(listOf(definition))
        val document = CircuitDocument("main", listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "A"),
            CircuitComponent("custom", kind, GridPoint(20, 0)),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(20, 0), "Y"),
        ))
        val session = editorTestVectorSession(CircuitProject(listOf(document)), "main", "A Y\n0 1\n1 0", registry)
        val rows = session.run()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.passed }, rows.joinToString { it.differences.toString() })
        assertEquals(listOf(1uL, 0uL), rows.map { it.observed[1].toULongOrNull() })
    }

    @Test
    fun romEditorAppliesContentsThroughTheDocumentHistoryCallback() {
        val rom = memory("rom", ComponentKind.ROM)
        val document = CircuitDocument("main", listOf(rom))
        var applied: CircuitDocument? = null
        val events = mutableListOf<ComponentEvent>()
        val context = context(document, onApply = { applied = it }, onEvent = { _, event -> events += event; true })
        val image = MemoryImage(2, 8, mapOf(1L to 42uL))
        assertTrue(applyMemoryEditorImage(context, rom.id, image))
        val output = assertNotNull(applied)
        assertEquals("ROM label", output.components.single().label)
        val decoded = assertIs<MemoryImageImportResult.Success>(MemoryImageCodec.decodeCirc(output.components.single().attributes.getValue("contents")))
        assertEquals(42uL, decoded.image[1])
        assertTrue(events.isEmpty())
    }

    @Test
    fun ramEditorDispatchesAnEventWithoutChangingTheProject() {
        val ram = memory("ram", ComponentKind.RAM)
        val document = CircuitDocument("main", listOf(ram))
        var applied: CircuitDocument? = null
        val events = mutableListOf<Pair<String, ComponentEvent>>()
        val context = context(document, onApply = { applied = it }, onEvent = { id, event -> events += id to event; true })
        val image = MemoryImage(2, 8, mapOf(3L to 255uL))
        assertTrue(applyMemoryEditorImage(context, ram.id, image))
        assertNull(applied)
        assertEquals("ram", events.single().first)
        assertEquals(image, assertIs<MemoryImageReplaced>(events.single().second).image)
        assertFalse("contents" in ram.attributes)
    }

    @Test
    fun memoryEditorRejectsWrongDimensionsBeforeAnyEditOrEvent() {
        val document = CircuitDocument("main", listOf(memory("ram", ComponentKind.RAM)))
        var changed = false
        val context = context(document, onApply = { changed = true }, onEvent = { _, _ -> changed = true; true })
        assertFailsWith<IllegalArgumentException> { applyMemoryEditorImage(context, "ram", MemoryImage(3, 16)) }
        assertFalse(changed)
    }

    @Test
    fun memoryEditorReadsRealRuntimeImagesAndDoesNotInventMissingRamState() {
        val ram = memory("ram", ComponentKind.RAM)
        val document = CircuitDocument("main", listOf(ram))
        val simulator = CircuitSimulator(registry = memoryRegistry)
        var state = simulator.settle(document)
        val image = MemoryImage(2, 8, mapOf(2L to 91uL))
        state = simulator.settle(document, state, events = mapOf("ram" to listOf(MemoryImageReplaced(image))))
        assertEquals(91uL, memoryEditorImage(ram, state, memoryRegistry)[2])
        assertFailsWith<IllegalArgumentException> { memoryEditorImage(ram, SimulationState(), memoryRegistry) }
    }

    @Test
    fun clearContentsZerosTheEntireSparseImageInTheEditorAndSimulator() {
        val lastAddress = 0x1ffffL
        val contents = MemoryImage(17, 8, mapOf(0L to 42uL, lastAddress to 255uL))
        val simulator = CircuitSimulator(registry = memoryRegistry)

        for (kind in listOf(ComponentKind.ROM, ComponentKind.RAM)) {
            val base = memory("memory", kind)
            val attributes = base.attributes + ("addrWidth" to "17")
            val component = base.copy(attributes = if (kind == ComponentKind.ROM) {
                attributes + ("contents" to MemoryImageCodec.encodeCirc(contents))
            } else {
                attributes
            })
            var document = CircuitDocument("main", listOf(component))
            var simulation = simulator.settle(document, events = if (kind == ComponentKind.RAM) {
                mapOf(component.id to listOf(MemoryImageReplaced(contents)))
            } else {
                emptyMap()
            })
            val editor = MemoryEditorModel(memoryEditorImage(component, simulation, memoryRegistry))
            assertEquals(42uL, editor.cells().first().value)
            val lastPage = assertIs<MemoryEditResult.Success>(editor.navigate("1ffff")).model
            assertEquals(255uL, lastPage.cells().last().value)

            val cleared = lastPage.clearContents()
            assertEquals(0uL, cleared.cells().last().value)
            assertEquals(emptyMap(), cleared.image.nonZeroWords)
            assertEquals(contents, memoryEditorImage(component, simulation, memoryRegistry))

            val context = context(document, onApply = {
                document = it
                simulation = simulator.settle(document, simulation)
            }, onEvent = { id, event ->
                assertTrue(assertIs<MemoryImageReplaced>(event).clearLineOverflow)
                simulation = simulator.settle(document, simulation, events = mapOf(id to listOf(event)))
                true
            })
            assertTrue(applyMemoryEditorImage(context, component.id, cleared.image, clearLineOverflow = true))
            val actual = memoryEditorImage(document.components.single(), simulation, memoryRegistry)
            assertEquals(MemoryImage(17, 8), actual)
            assertEquals(0uL, actual[0])
            assertEquals(0uL, actual[lastAddress])
        }
    }

    private fun tool(id: String) = EditorTool(id, id) { }
    private fun memory(id: String, kind: ComponentKind) = CircuitComponent(id, kind, GridPoint(0, 0), "ROM label",
        attributes = mapOf("addrWidth" to "2", "dataWidth" to "8", "appearance" to "classic"))

    private fun context(
        document: CircuitDocument,
        onApply: (CircuitDocument) -> Unit,
        onEvent: (String, ComponentEvent) -> Boolean,
    ) = EditorToolContext(
        project = CircuitProject(listOf(document)), document = document, simulation = SimulationState(), selectedComponents = document.components,
        applyProject = {}, applyDocument = onApply, selectCircuit = {}, selectComponent = {}, showStatus = {}, sendComponentEvent = onEvent,
    )
}
