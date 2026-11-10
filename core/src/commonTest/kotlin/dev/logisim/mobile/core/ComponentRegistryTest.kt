package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComponentRegistryTest {
    private val kind = ComponentKind("EXAMPLE_BUFFER")
    private val width = AttributeKey.int("width", 8, 1..64)
    private val facing = AttributeKey.direction()
    private val schema = AttributeSchema(listOf(width, facing))
    private val definition = ComponentDefinition(
        kind, "Example Buffer", "#Example", schema = schema,
        layout = { component, attributes ->
            val dataWidth = attributes[width]
            val input = component.relativePoint(GridPoint(-20, 0))
            ComponentLayout(
                listOf(ComponentTerminal("in", input, PortDirection.INPUT, dataWidth),
                    ComponentTerminal("out", component.position, PortDirection.OUTPUT, dataWidth)),
                ComponentBounds(minOf(input.x, component.position.x) - 5, minOf(input.y, component.position.y) - 5,
                    maxOf(input.x, component.position.x) + 5, maxOf(input.y, component.position.y) + 5),
            )
        },
        isCombinational = true,
    )
    private val registry = ComponentCatalog.registry.withAdditional(listOf(definition))

    @Test
    fun registryAddsTypesWithoutChangingBuiltinsOrMutatingItsSource() {
        assertEquals("Example Buffer", registry.requireDefinition(kind).fileName)
        assertEquals(null, ComponentCatalog.registry.definition(kind))
        assertTrue(registry.definitions.any { it.kind == ComponentKind.AND })
        assertEquals(ComponentKind.INPUT, registry.resolve("#Wiring", "Pin", emptyMap())?.kind)
        assertEquals(ComponentKind.OUTPUT, registry.resolve("#Wiring", "Pin", mapOf("type" to "output"))?.kind)
        assertEquals(null, registry.resolve("#DifferentLibrary", "Example Buffer", emptyMap()))
        assertFailsWith<IllegalArgumentException> { registry.withAdditional(listOf(definition)) }
    }

    @Test
    fun schemaAppliesTypedDefaultsAndPreservesUnknownFields() {
        val attributes = schema.parse(mapOf("future-style" to "blue"))
        assertEquals(8, attributes[width])
        assertEquals(Direction.EAST, attributes[facing])
        assertEquals(mapOf("future-style" to "blue"), attributes.raw)
        assertFailsWith<IllegalArgumentException> { schema.parse(mapOf("width" to "65")) }
        assertFailsWith<IllegalArgumentException> { schema.parse(mapOf("facing" to "upwards")) }
    }

    @Test
    fun externalTypeRoundTripsItsFileIdentityAttributesAndPorts() {
        val component = registry.create(kind, "example", GridPoint(100, 100),
            mapOf("width" to "16", "facing" to "north", "future-style" to "blue"))
        val project = CircuitProject(listOf(CircuitDocument("main", listOf(component))))
        val xml = CircCodec.writeProject(project, registry)
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        val actual = imported.mainCircuit.components.single()
        assertEquals(kind, actual.kind)
        assertEquals("blue", actual.attributes["future-style"])
        assertEquals(16, registry.attributes(actual)[width])
        assertEquals(component.layout(registry), actual.layout(registry))
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(xml))
    }

    @Test
    fun attributeChangesReconnectStableNamedPortsAndRejectInvalidValuesAtomically() {
        val component = registry.create(kind, "example", GridPoint(100, 100), mapOf("width" to "8"))
        val editor = CircuitEditor(CircuitDocument("main", listOf(component), listOf(
            WireSegment(GridPoint(40, 100), GridPoint(80, 100)))), registry)
        editor.updateComponent("example") { registry.reconfigure(it, mapOf("facing" to "south")) }
        assertTrue(editor.document.wires.any { it.contains(GridPoint(100, 80)) })
        val rotated = editor.document
        assertFailsWith<IllegalArgumentException> {
            editor.updateComponent("example") { it.copy(attributes = it.attributes + ("width" to "0")) }
        }
        assertEquals(rotated, editor.document)
        editor.undo()
        assertEquals(component, editor.document.components.single())
    }

    @Test
    fun existingComponentsKeepTheirPortGeometryForEveryDirection() {
        val originals = listOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.AND, ComponentKind.OR,
            ComponentKind.XOR, ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR, ComponentKind.NOT,
            ComponentKind.BUFFER, ComponentKind.CLOCK, ComponentKind.D_FLIP_FLOP)
        for (kind in originals) for (direction in Direction.entries) {
            val component = CircuitComponent("old", kind, GridPoint(200, 100),
                attributes = mapOf("facing" to direction.serialized))
            assertEquals(component.legacyInputPorts(), component.inputPorts(), "$kind $direction inputs")
            assertEquals(component.legacyOutputPorts(), component.outputPorts(), "$kind $direction outputs")
            assertEquals(component.legacyPortWidths(), component.portWidths(), "$kind $direction widths")
        }
    }

    @Test
    fun unregisteredTypesNeverAcquireFallbackGateGeometry() {
        val component = CircuitComponent("unknown", ComponentKind("UNREGISTERED"), GridPoint(0, 0))
        assertFailsWith<IllegalArgumentException> { component.inputPorts() }
        assertFailsWith<IllegalArgumentException> { component.bounds() }
    }

    @Test
    fun groupAttributeChangesValidateBeforePublishingOneHistoryEntry() {
        val first = registry.create(kind, "first", GridPoint(100, 100))
        val second = registry.create(kind, "second", GridPoint(200, 100))
        val original = CircuitDocument(components = listOf(first, second))
        val editor = CircuitEditor(original, registry)
        assertFailsWith<IllegalArgumentException> {
            editor.updateComponents(mapOf(
                "first" to first.copy(attributes = mapOf("width" to "16")),
                "second" to second.copy(attributes = mapOf("width" to "0")),
            ))
        }
        assertEquals(original, editor.document)
        editor.updateComponents(mapOf(
            "first" to first.copy(attributes = mapOf("width" to "16")),
            "second" to second.copy(attributes = mapOf("width" to "16")),
        ))
        assertTrue(editor.document.components.all { registry.attributes(it)[width] == 16 })
        editor.undo()
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test
    fun weakPullsUseInferredBusWidthAndNeverOverrideStrongDrivers() {
        val pullKind = ComponentKind("EXAMPLE_PULL")
        val pull = ComponentDefinition(pullKind, "Pull", "#Example", layout = { component, _ ->
            ComponentLayout(listOf(ComponentTerminal("line", component.position, PortDirection.INOUT, 0)),
                ComponentBounds(component.position.x, component.position.y, component.position.x + 10, component.position.y + 10))
        }, pullDrivers = { _, _ -> mapOf("line" to LogicVector.high(1)) })
        val withPull = registry.withAdditional(listOf(pull))
        val position = GridPoint(100, 100)
        val document = CircuitDocument(components = listOf(
            CircuitComponent("pull", pullKind, position),
            CircuitComponent("sink", ComponentKind.OUTPUT, position, attributes = mapOf("width" to "8")),
        ))
        val network = CircuitNetworks(document, withPull)
        assertTrue(network.diagnostics.isEmpty())
        assertEquals(8, network.widthAt(position))
        assertEquals(255uL, network.resolve(emptyMap()).getValue(position).toULongOrNull())
        assertEquals(0uL, network.resolve(mapOf(position to LogicVector.low(8))).getValue(position).toULongOrNull())
        assertFalse(network.resolve(emptyMap()).getValue(position).isError)
    }

    @Test
    fun topologyExportsConnectedBitsWithoutJoiningDifferentBusBits() {
        val from = GridPoint(100, 100)
        val to = GridPoint(200, 100)
        val document = CircuitDocument(components = listOf(
            CircuitComponent("source", ComponentKind.INPUT, from, attributes = mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, to, attributes = mapOf("width" to "8")),
        ), wires = listOf(WireSegment(from, to)))
        val topology = CircuitNetworks(document).bitNetworks()
        assertEquals(topology.getValue(from), topology.getValue(to))
        assertEquals(8, topology.getValue(from).distinct().size)
        assertEquals(null, CircuitNetworks(document).bitNetwork(from, 8))
    }
}
