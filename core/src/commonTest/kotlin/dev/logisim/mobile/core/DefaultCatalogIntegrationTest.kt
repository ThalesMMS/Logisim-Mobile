package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultCatalogIntegrationTest {
    private val registry = ComponentCatalog.registry
    private val families = listOf(
        WiringLibrary.definitions(), GatesLibrary.definitions(), ArithmeticComponents.definitions(),
        FloatingPointLibrary.definitions(), PlexersLibrary.definitions, MemoryComponents.definitions(),
        SequentialLibrary.definitions, IoLibrary.definitions, ExtraIoComponents.definitions(),
        ExternalIoComponents.definitions(), TtlComponents.definitions,
    )

    @Test
    fun defaultCatalogRetainsEveryFamilyAndItsDesktopIdentity() {
        val expected = families.flatten()
        assertEquals(expected.map { it.kind }.distinct().size, expected.size)
        for (definition in expected) {
            val active = registry.requireDefinition(definition.kind)
            assertEquals(definition.library, active.library, definition.kind.name)
            assertEquals(definition.fileName, active.fileName, definition.kind.name)
            assertEquals(definition.isCombinational, active.isCombinational, definition.kind.name)
            assertTrue(active.behavior != null || definition.behavior == null, definition.kind.name)
        }
        assertEquals(setOf("#BFH-Praktika", "#Input/Output-Extra"),
            ExtraIoComponents.definitions().map { it.library }.toSet())
        assertTrue(ExternalIoComponents.definitions().all { it.library == "#I/O" })
    }

    @Test
    fun allDefaultComponentsRoundTripWithoutACustomRegistry() {
        val circuits = families.flatten().mapIndexed { index, definition ->
            val component = registry.create(definition.kind, "device", GridPoint(400, 400),
                mapOf("mapinfo" to "keep-$index"), "case_$index")
            CircuitDocument("case_$index", listOf(component))
        }
        val xml = CircCodec.writeProject(CircuitProject(circuits, sourceVersion = "5.1.0-dev"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(circuits.size, reopened.circuits.size)
        for ((before, after) in circuits.zip(reopened.circuits)) {
            val original = before.components.single()
            val restored = after.components.single()
            assertEquals(original.kind, restored.kind)
            assertEquals(original.label, restored.label)
            assertEquals(original.attributes["mapinfo"], restored.attributes["mapinfo"])
            assertEquals(registry.layout(original), registry.layout(restored), original.kind.name)
        }
        for (description in families.flatten().map { it.library }.distinct()) {
            assertTrue("desc=\"$description\"" in xml, description)
        }
        assertFalse("#I/OExtra" in xml)
    }

    @Test
    fun defaultSimulatorRunsNewPlexersAndExtraComponentsAndExternalPinEvents() {
        val mux = registry.create(PlexersLibrary.Multiplexer, "mux", GridPoint(200, 200), mapOf("width" to "8"))
        fun constant(id: String, point: GridPoint, width: Int, value: String) = registry.create(
            ComponentKind.CONSTANT, id, point, mapOf("width" to width.toString(), "value" to value))
        val muxDrivers = registry.layout(mux).inputs.map { terminal -> constant(terminal.name, terminal.position,
            terminal.width, when (terminal.name) { "in0" -> "0x12"; "in1" -> "0xab"; else -> "0x1" }) }
        val simulator = CircuitSimulator()
        val selected = simulator.settle(CircuitDocument(components = listOf(mux) + muxDrivers))
        assertEquals(0xabuL, selected.vectorValues.getValue("mux").toULongOrNull())
        assertFalse(selected.oscillating)

        val converter = registry.create(ExtraIoKinds.BinaryToBcd, "bcd", GridPoint(200, 200))
        val binary = registry.layout(converter).inputs.single()
        val converted = simulator.settle(CircuitDocument(components = listOf(converter,
            constant("binary", binary.position, binary.width, "0x123"))))
        val digits = registry.layout(converter).outputs.map { converted.portVectors.getValue(it.position).toULongOrNull() }
        assertEquals(listOf(1uL, 9uL, 2uL), digits)

        val external = registry.create(ExternalIoComponents.PortIo, "port", GridPoint(200, 200),
            mapOf("direction" to "onlyinput", "number" to "4"))
        val document = CircuitDocument(components = listOf(external))
        val initial = simulator.settle(document)
        val changed = simulator.settle(document, initial,
            events = mapOf("port" to (0..3).map { PortIoSetPin(it, if (it == 2) LogicValue.HIGH else LogicValue.LOW) }))
        assertEquals(4uL, changed.vectorValues.getValue("port").toULongOrNull())
        assertEquals("????", initial.vectorValues.getValue("port").display)
    }

    @Test
    fun genericAttributeEditsUseWiringRulesAndRetainUnknownMetadata() {
        val source = registry.create(ComponentKind.CONSTANT, "constant", GridPoint(100, 100),
            mapOf("width" to "8", "value" to "0x80", "mapinfo" to "keep"))
        val widened = registry.reconfigure(source, mapOf("width" to "16"))
        assertEquals("0xff80", widened.attributes["value"])
        assertEquals("keep", widened.attributes["mapinfo"])
        val explicit = registry.reconfigure(source, mapOf("width" to "16", "value" to "0x7f"))
        assertEquals("0x7f", explicit.attributes["value"])

        val splitter = registry.create(ComponentKind.SPLITTER, "splitter", GridPoint(100, 100),
            mapOf("incoming" to "8", "fanout" to "4", "bit7" to "3", "mapinfo" to "keep"))
        val resized = registry.reconfigure(splitter, mapOf("fanout" to "2"))
        assertEquals("1", resized.attributes["bit7"])
        assertEquals("keep", resized.attributes["mapinfo"])
        assertEquals(3, registry.layout(resized).ports.size)
    }
}
