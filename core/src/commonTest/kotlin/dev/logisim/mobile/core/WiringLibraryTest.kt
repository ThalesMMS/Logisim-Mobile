package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WiringLibraryTest {
    private val registry = ComponentRegistry(WiringLibrary.definitions())
    private fun component(kind: ComponentKind, attributes: Map<String, String> = emptyMap()) =
        registry.create(kind, "device", GridPoint(100, 100), attributes)

    @Test
    fun registryContainsEveryDesktopWiringToolAndDisambiguatesPinDirection() {
        assertEquals(setOf("Splitter", "Pin", "Probe", "Tunnel", "Pull Resistor", "Clock", "POR",
            "Constant", "Power", "Ground", "NoConnect", "Transistor", "Transmission Gate", "Bit Extender"),
            registry.definitions.map { it.fileName }.toSet())
        assertEquals(ComponentKind.INPUT, registry.resolve("#Wiring", "Pin", emptyMap())?.kind)
        assertEquals(ComponentKind.OUTPUT, registry.resolve("#Wiring", "Pin", mapOf("output" to "true"))?.kind)
        assertEquals(ComponentKind.INPUT, registry.resolve("#Wiring", "Pin", mapOf("type" to "input"))?.kind)
        assertTrue(registry.requireDefinition(ComponentKind.SPLITTER).isCombinational)
        assertTrue(registry.requireDefinition(ComponentKind.INPUT).isCombinational)
        assertTrue(!registry.requireDefinition(ComponentKind.CLOCK).isCombinational)
        assertTrue(!registry.requireDefinition(WiringLibrary.PowerOnReset).isCombinational)
    }

    @Test
    fun extenderAndControlledDevicePortsMatchDesktopCoordinates() {
        val extender = component(ComponentKind.BIT_EXTENDER, mapOf("in_width" to "8", "out_width" to "16", "type" to "input"))
        assertEquals(listOf(GridPoint(100, 100), GridPoint(60, 100), GridPoint(80, 80)), registry.layout(extender).ports.map { it.position })
        assertEquals(listOf(16, 8, 1), registry.layout(extender).ports.map { it.width })

        val transistor = component(WiringLibrary.Transistor)
        assertEquals(listOf(GridPoint(100, 100), GridPoint(60, 100), GridPoint(80, 80)), registry.layout(transistor).ports.map { it.position })
        val north = component(WiringLibrary.Transistor, mapOf("facing" to "north"))
        assertEquals(listOf(GridPoint(100, 100), GridPoint(100, 140), GridPoint(120, 120)), registry.layout(north).ports.map { it.position })
        assertEquals(ComponentBounds(100, 100, 120, 140), registry.layout(north).bounds)
        val transmission = component(WiringLibrary.TransmissionGate)
        assertEquals(listOf(GridPoint(100, 100), GridPoint(60, 100), GridPoint(80, 80), GridPoint(80, 120)), registry.layout(transmission).ports.map { it.position })
    }

    @Test
    fun powerConstantAndPullBoundsUseTheirDeclaredFacingDefaults() {
        assertEquals(ComponentBounds(92, 85, 108, 100), registry.layout(component(ComponentKind.POWER)).bounds)
        assertEquals(ComponentBounds(92, 100, 108, 114), registry.layout(component(ComponentKind.GROUND)).bounds)
        assertEquals(ComponentBounds(65, 92, 100, 108), registry.layout(component(ComponentKind.CONSTANT, mapOf("width" to "8"))).bounds)
        val pull = registry.layout(component(WiringLibrary.PullResistor))
        assertEquals(ComponentBounds(92, 56, 118, 100), pull.bounds)
        assertEquals(0, pull.ports.single().width)
        assertEquals(PortDirection.INOUT, pull.ports.single().direction)
    }

    @Test
    fun constantsUsePrefixedHexadecimalValuesAndSignExtendWhenWidthChanges() {
        val constant = component(ComponentKind.CONSTANT, mapOf("width" to "4", "value" to "0xe"))
        val widened = WiringLibrary.reconfigure(constant, mapOf("width" to "8"))
        assertEquals("0xfe", widened.attributes["value"])
        val definition = registry.requireDefinition(constant.kind)
        val result = definition.behavior!!.evaluate(ComponentEvaluationContext(widened,
            registry.attributes(widened), emptyMap(), emptySet()))
        assertEquals(254uL, result.outputs.getValue("out").toULongOrNull())
        val default = component(ComponentKind.CONSTANT, mapOf("width" to "8"))
        val defaultResult = definition.behavior.evaluate(ComponentEvaluationContext(default,
            registry.attributes(default), emptyMap(), emptySet()))
        assertEquals(255uL, defaultResult.outputs.getValue("out").toULongOrNull())
    }

    @Test
    fun constantAndPinInitialValuesMatchDesktopHexLongParsing() {
        val cases = mapOf("10" to 10uL, "0x10" to 16uL, "010" to 8uL, "0b10" to 2uL,
            "-1" to ULong.MAX_VALUE, "-0x8000000000000000" to 0x8000000000000000uL,
            "18446744073709551615" to ULong.MAX_VALUE)
        for ((raw, expected) in cases) for (kind in listOf(ComponentKind.CONSTANT, ComponentKind.INPUT)) {
            val name = if (kind == ComponentKind.CONSTANT) "value" else "initial"
            val device = component(kind, mapOf("width" to "64", name to raw))
            val result = registry.requireDefinition(kind).behavior!!.evaluate(ComponentEvaluationContext(device,
                registry.attributes(device), emptyMap(), emptySet()))
            assertEquals(expected, result.outputs.getValue("out").toULongOrNull(), "$kind $name=$raw")
        }
        for (raw in listOf("ff", "08", "0x", " 10", "10 ", "18446744073709551616", "-9223372036854775809", "-0xffffffffffffffff")) {
            assertFailsWith<IllegalArgumentException>(raw) { component(ComponentKind.CONSTANT, mapOf("value" to raw)) }
            assertFailsWith<IllegalArgumentException>(raw) { component(ComponentKind.INPUT, mapOf("initial" to raw)) }
        }
    }

    @Test
    fun pinsAndProbesUseWidthRadixAndAppearance() {
        assertEquals(ComponentBounds(20, 90, 100, 110), registry.layout(component(ComponentKind.INPUT, mapOf("width" to "8"))).bounds)
        assertEquals(ComponentBounds(-10, 90, 100, 110), registry.layout(component(ComponentKind.INPUT,
            mapOf("width" to "8", "appearance" to "NewPins"))).bounds)
        val probe = component(ComponentKind.PROBE, mapOf("facing" to "north", "appearance" to "NewPins"))
        val observed = WiringLibrary.probeLayout(probe, registry.attributes(probe), 16)
        assertEquals(ComponentBounds(45, 100, 155, 140), observed.bounds)
        assertEquals(0, observed.ports.single().width)
        assertEquals(ComponentBounds(60, 90, 100, 110), registry.layout(component(ComponentKind.CLOCK)).bounds)
    }

    @Test
    fun unknownVisualMetadataSurvivesTypedValidationAndBadElectricalAttributesFail() {
        val pin = component(ComponentKind.INPUT, mapOf("mapinfo" to "KEEP_ME", "width" to "8"))
        assertEquals("KEEP_ME", registry.attributes(pin).raw["mapinfo"])
        assertFailsWith<IllegalArgumentException> { component(ComponentKind.INPUT, mapOf("width" to "0")) }
        assertFailsWith<IllegalArgumentException> { component(WiringLibrary.PowerOnReset, mapOf("PorHighDuration" to "11")) }
    }

    @Test
    fun pinBehaviorUsesInitialTristateAndStrongPull() {
        val pin = component(ComponentKind.INPUT, mapOf("width" to "4", "behavior" to "tristate"))
        val definition = registry.requireDefinition(pin.kind)
        val attrs = registry.attributes(pin)
        val first = definition.behavior!!.evaluate(ComponentEvaluationContext(pin, attrs, emptyMap(), emptySet()))
        assertEquals("????", first.outputs.getValue("out").display)

        val pulled = component(ComponentKind.INPUT, mapOf("width" to "4", "behavior" to "pullup"))
        val mixed = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.ERROR, LogicValue.HIGH))
        val result = definition.behavior.evaluate(ComponentEvaluationContext(pulled, registry.attributes(pulled),
            mapOf("drive" to mixed), emptySet()))
        assertEquals("1E10", result.outputs.getValue("out").display)
    }

    @Test
    fun pullResistorContributesOnlyAWeakDriver() {
        val pull = component(WiringLibrary.PullResistor, mapOf("pull" to "X"))
        val definition = registry.requireDefinition(pull.kind)
        assertNull(definition.behavior)
        assertEquals("E", definition.pullDrivers!!(pull, registry.attributes(pull)).getValue("pin").display)
    }

    @Test
    fun porSchedulesAndRearmsUsingInjectedElapsedMilliseconds() {
        val por = component(WiringLibrary.PowerOnReset)
        val attrs = registry.attributes(por)
        val behavior = registry.requireDefinition(por.kind).behavior!!
        val first = behavior.evaluate(ComponentEvaluationContext(por, attrs, emptyMap(), emptySet(), elapsedTimeMillis = 250))
        assertEquals("1", first.outputs.getValue("out").display)
        assertEquals(2250L, first.nextWakeupMillis)
        val elapsed = behavior.evaluate(ComponentEvaluationContext(por, attrs, emptyMap(), emptySet(), first.state, elapsedTimeMillis = 2250))
        assertEquals("0", elapsed.outputs.getValue("out").display)
        assertNull(elapsed.nextWakeupMillis)
        val restarted = behavior.evaluate(ComponentEvaluationContext(por, attrs, emptyMap(), emptySet(), elapsed.state,
            elapsedTimeMillis = 3000, events = listOf(WiringLibrary.PowerOnResetTriggered)))
        assertEquals("1", restarted.outputs.getValue("out").display)
        assertEquals(5000L, restarted.nextWakeupMillis)
    }

    @Test
    fun allSplitterBranchesPreserveTheirBitAssignments() {
        val splitter = component(ComponentKind.SPLITTER, mapOf("incoming" to "8", "fanout" to "3", "bit0" to "none", "bit25" to "opaque"))
        val ports = registry.layout(splitter).ports
        assertEquals(listOf(8, 2, 3, 2), ports.map { it.width })
        assertTrue(ports.all { it.direction == PortDirection.INOUT })
        assertEquals("opaque", splitter.attributes["bit25"])
    }

    @Test
    fun splitterFanoutEditsPreserveExistingAssignmentsAndWidthEditsRedistribute() {
        val splitter = component(ComponentKind.SPLITTER, mapOf("incoming" to "8", "fanout" to "2"))
        val increased = WiringLibrary.reconfigure(splitter, mapOf("fanout" to "3"))
        assertEquals(listOf(8, 4, 4, 0), registry.layout(increased).ports.map { it.width })
        val reduced = WiringLibrary.reconfigure(increased, mapOf("fanout" to "1"))
        assertEquals(List(8) { 0 }, reduced.splitterBitEnds())
        val resized = WiringLibrary.reconfigure(increased, mapOf("incoming" to "6"))
        assertEquals(listOf(6, 2, 2, 2), registry.layout(resized).ports.map { it.width })
        assertNull(resized.attributes["bit7"])
    }
}
