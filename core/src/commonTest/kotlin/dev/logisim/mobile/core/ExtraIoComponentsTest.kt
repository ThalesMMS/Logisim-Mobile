package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtraIoComponentsTest {
    private val registry = ComponentRegistry(ExtraIoComponents.definitions())

    private fun component(kind: ComponentKind, attributes: Map<String, String> = emptyMap()) =
        registry.create(kind, "test", GridPoint(100, 200), attributes)

    private fun evaluate(component: CircuitComponent, inputs: Map<String, LogicVector> = emptyMap(),
                         state: ComponentState = EmptyComponentState, events: List<ComponentEvent> = emptyList(),
                         time: Long = 0, active: Boolean = false): ComponentEvaluation =
        registry.requireDefinition(component.kind).behavior!!.evaluate(ComponentEvaluationContext(
            component, registry.attributes(component), inputs, inputs.keys, state,
            elapsedTimeMillis = time, events = events, autoPropagating = active))

    @Test
    fun registryContainsOnlyTheTenActiveDesktopEntries() {
        assertEquals(10, registry.definitions.size)
        assertEquals(setOf("Binary_to_BCD_converter", "BCD_to_7_Segment_decoder", "Switch", "Buzzer", "Slider",
            "Digital Oscilloscope", "PlaRom", "TwoWaySwitch", "TWOPINLED", "NE555"), registry.definitions.map { it.fileName }.toSet())
        assertTrue(registry.definitions.none { it.fileName == "ProgrammableGenerator" })
        registry.definitions.forEach { definition ->
            assertEquals(definition, registry.resolve(definition.library, definition.fileName, emptyMap()))
            registry.layout(component(definition.kind))
        }
    }

    @Test
    fun bfhPortOrderAndLocationsMatchTheDesktop() {
        val converter = component(ExtraIoKinds.BinaryToBcd, mapOf("binvalue" to "9"))
        val layout = registry.layout(converter)
        assertEquals(listOf("binary", "digit0", "digit1", "digit2"), layout.ports.map { it.name })
        assertEquals(listOf(GridPoint(70, 200), GridPoint(220, 180), GridPoint(160, 180), GridPoint(100, 180)), layout.ports.map { it.position })
        assertEquals(listOf(9, 4, 4, 4), layout.ports.map { it.width })
        assertEquals(ComponentBounds(70, 180, 250, 220), layout.bounds)
        val display = registry.layout(component(ExtraIoKinds.BcdToSevenSegment))
        assertEquals(GridPoint(110, 280), display.ports.single { it.name == "bcd" }.position)
        assertEquals(listOf("segmenta", "segmentb", "segmentc", "segmentd", "segmente", "segmentf", "segmentg", "bcd"), display.ports.map { it.name })
    }

    @Test
    fun switchPortsAndEventsKeepTheBusWidth() {
        val switch = component(ExtraIoKinds.Switch, mapOf("width" to "12", "facing" to "north"))
        val layout = registry.layout(switch)
        assertEquals(GridPoint(100, 220), layout.inputs.single().position)
        assertEquals(12, layout.outputs.single().width)
        val input = mapOf("in" to LogicVector.of(12, 0xabcu))
        val open = evaluate(switch, input)
        assertEquals(LogicVector.unknown(12), open.outputs.getValue("out"))
        val closed = evaluate(switch, input, open.state, listOf(ExtraIoEvent.Toggle))
        assertEquals(LogicVector.of(12, 0xabcu), closed.outputs.getValue("out"))
        assertEquals(closed, evaluate(switch, input, closed.state))
    }

    @Test
    fun twoWaySwitchPreservesTheDesktopNorthSouthPortConventions() {
        val switch = component(ExtraIoKinds.TwoWaySwitch, mapOf("mode" to "2in1out", "facing" to "south", "width" to "4"))
        val ports = registry.layout(switch).ports
        assertEquals(listOf(GridPoint(100, 180), GridPoint(90, 220), GridPoint(110, 220)), ports.map { it.position })
        assertEquals(listOf(PortDirection.OUTPUT, PortDirection.INPUT, PortDirection.INPUT), ports.map { it.direction })
        val input = mapOf("branch0" to LogicVector.of(4, 3u), "branch1" to LogicVector.of(4, 12u))
        assertEquals(3uL, evaluate(switch, input).outputs.getValue("common").toULongOrNull())
        assertEquals(12uL, evaluate(switch, input, events = listOf(ExtraIoEvent.Toggle)).outputs.getValue("common").toULongOrNull())
    }

    @Test
    fun sliderUsesItsDeclaredDirectionAtCreationAndOnInteraction() {
        val slider = component(ExtraIoKinds.Slider, mapOf("Direction" to "right_to_left", "width" to "4"))
        val initial = registry.requireDefinition(slider.kind).behavior!!.initialState(slider, registry.attributes(slider))
        assertEquals(255, assertIs<ExtraSliderState>(initial).position)
        assertEquals(0uL, evaluate(slider, state = initial).outputs.getValue("out").toULongOrNull())
        assertEquals(15uL, evaluate(slider, state = initial, events = listOf(ExtraIoEvent.MoveSlider(0))).outputs.getValue("out").toULongOrNull())
    }

    @Test
    fun oscilloscopeExposesCaptureAndClearStateWithoutOutputDrivers() {
        val scope = component(ExtraIoKinds.Oscilloscope, mapOf("inputs" to "1", "nState" to "4"))
        var result = evaluate(scope, mapOf("clock" to LogicVector.low(1), "in0" to LogicVector.high(1)))
        result = evaluate(scope, mapOf("clock" to LogicVector.high(1), "in0" to LogicVector.high(1)), result.state)
        assertTrue(result.outputs.isEmpty())
        assertEquals(listOf(listOf(LogicValue.HIGH, LogicValue.HIGH)), assertIs<ExtraOscilloscopeState>(result.state).samples)
        result = evaluate(scope, mapOf("clock" to LogicVector.low(1)), result.state, listOf(ExtraIoEvent.ClearTrace))
        val cleared = assertIs<ExtraOscilloscopeState>(result.state)
        assertTrue(cleared.samples.isEmpty())
        assertEquals(LogicValue.UNKNOWN, cleared.lastClock)
    }

    @Test
    fun ne555EngineeringValuesUseTheDesktopUnitsAndWholeNumberLimits() {
        val valid = listOf(
            ("71.66 kΩ" to "10 µF") to (71660 to 10),
            ("1kOhM" to "1e-5 F") to (1000 to 10),
            ("10meg" to "1000 μF") to (10_000_000 to 1000),
            ("1.e3" to ".01mF") to (1000 to 10),
            ("71660.0" to "10000 nF") to (71660 to 10),
        )
        for ((raw, expected) in valid) {
            val timer = component(ExtraIoKinds.Ne555, mapOf("r1" to raw.first, "c1" to raw.second))
            val values = registry.attributes(timer)
            assertEquals(expected, values[ExtraIoAttributes.Resistance] to values[ExtraIoAttributes.Capacitance])
        }
        val invalid = listOf(
            "r1" to "999Ω", "r1" to "10.1M", "r1" to "1.0001k",
            "r1" to "1K", "r1" to "71,66k", "r1" to "1000F", "r1" to "1e2147483647",
            "c1" to "9u", "c1" to "1001u", "c1" to "10.5µF", "c1" to "10uf", "c1" to "10kΩ",
            "c1" to "10.0000000000000000001u",
        )
        for ((name, raw) in invalid) {
            assertFailsWith<IllegalArgumentException>("Accepted $name=$raw") {
                component(ExtraIoKinds.Ne555, mapOf(name to raw))
            }
        }
    }

    @Test
    fun ne555CircuitRoundTripWritesDecimalOhmsAndMicrofarads() {
        val xml = """
            <project source="5.1.0" version="1.0">
              <lib desc="#Input/Output-Extra" name="0"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(100,100)" name="NE555">
                  <a name="r1" val="71.66k"/>
                  <a name="c1" val="47u"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent()
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry))
        val timer = imported.project.mainCircuit.components.single()
        assertEquals("71660", timer.attributes["r1"])
        assertEquals("47", timer.attributes["c1"])

        val edited = timer.copy(attributes = timer.attributes + mapOf("r1" to "100k", "c1" to "68 µF"))
        val project = imported.project.withCircuit(imported.project.mainCircuit.copy(components = listOf(edited)))
        val encoded = CircCodec.writeProject(project, registry)
        assertTrue("name=\"r1\" val=\"100000\"" in encoded)
        assertTrue("name=\"c1\" val=\"68\"" in encoded)
        val readback = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(encoded, registry))
        val attributes = registry.attributes(readback.project.mainCircuit.components.single())
        assertEquals(100000, attributes[ExtraIoAttributes.Resistance])
        assertEquals(68, attributes[ExtraIoAttributes.Capacitance])
    }

    @Test
    fun timerSchedulesOnlyWhileAutomaticPropagationIsActive() {
        val timer = component(ExtraIoKinds.Ne555)
        val initial = evaluate(timer, time = 0)
        assertEquals(null, initial.nextWakeupMillis)
        val active = evaluate(timer, state = initial.state, time = 10, active = true)
        assertEquals(20L, active.nextWakeupMillis)
        assertEquals(0L, active.delay)
        assertEquals(1uL, active.outputs.getValue("out").toULongOrNull())
    }

    @Test
    fun buzzerReportsNativeAudioParametersAsInstanceState() {
        val buzzer = component(ExtraIoKinds.Buzzer)
        assertEquals(ComponentBounds(100, 180, 140, 220), registry.layout(buzzer).bounds)
        val result = evaluate(buzzer, mapOf("frequency" to LogicVector.of(14, 440u), "enable" to LogicVector.high(1),
            "volume" to LogicVector.of(7, 127u), "duty" to LogicVector.of(8, 128u)))
        val tone = assertIs<BuzzerTone>(result.state)
        assertTrue(tone.audible)
        assertEquals(440, tone.frequencyHz)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun parameterValidationRunsBeforeLayoutOrEvaluation() {
        assertFailsWith<IllegalArgumentException> { component(ExtraIoKinds.BinaryToBcd, mapOf("binvalue" to "14")) }
        assertFailsWith<IllegalArgumentException> { component(ExtraIoKinds.Slider, mapOf("width" to "9")) }
        assertFailsWith<IllegalArgumentException> { component(ExtraIoKinds.Ne555, mapOf("r1" to "0")) }
        assertFailsWith<IllegalArgumentException> { registry.layout(component(ExtraIoKinds.PlaRom, mapOf("Contents" to "2*9999"))) }
    }
}
