package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GatesLibraryTest {
    private val registry get() = ComponentRegistry(GatesLibrary.definitions())

    private fun evaluate(
        kind: ComponentKind,
        inputs: List<LogicVector?>,
        attributes: Map<String, String> = emptyMap(),
        errorIfUndefined: Boolean = false,
    ): ComponentEvaluation {
        val definition = registry.requireDefinition(kind)
        val component = registry.create(kind, "gate", GridPoint(100, 100), attributes)
        val values = registry.attributes(component)
        val ports = registry.layout(component).inputs
        val supplied = ports.mapIndexedNotNull { index, port -> inputs.getOrNull(index)?.let { port.name to it } }.toMap()
        return definition.behavior!!.evaluate(ComponentEvaluationContext(
            component, values, supplied, supplied.keys,
            options = mapOf("gateUndefined" to if (errorIfUndefined) "error" else "ignore"),
        ))
    }

    private fun output(kind: ComponentKind, values: List<LogicVector?>, attrs: Map<String, String> = emptyMap(), error: Boolean = false) =
        evaluate(kind, values, attrs, error).outputs.getValue("out")

    private fun bit(value: Int) = LogicVector.of(1, value.toULong())

    @Test
    fun allMultiInputGatesFollowTheirThreeInputTruthTables() {
        val cases = listOf(
            ComponentKind.AND to listOf(0, 0, 0, 0, 0, 0, 0, 1),
            ComponentKind.OR to listOf(0, 1, 1, 1, 1, 1, 1, 1),
            ComponentKind.NAND to listOf(1, 1, 1, 1, 1, 1, 1, 0),
            ComponentKind.NOR to listOf(1, 0, 0, 0, 0, 0, 0, 0),
            ComponentKind.XOR to listOf(0, 1, 1, 0, 1, 0, 0, 0),
            ComponentKind.XNOR to listOf(1, 0, 0, 1, 0, 1, 1, 1),
            GatesLibrary.OddParity to listOf(0, 1, 1, 0, 1, 0, 0, 1),
            GatesLibrary.EvenParity to listOf(1, 0, 0, 1, 0, 1, 1, 0),
        )
        for ((kind, expected) in cases) {
            val actual = (0..7).map { value ->
                output(kind, List(3) { bit(value shr it and 1) }, mapOf("inputs" to "3")).toULongOrNull()!!.toInt()
            }
            assertEquals(expected, actual, kind.name)
        }
        assertEquals(1uL, output(ComponentKind.XOR, List(3) { bit(1) },
            mapOf("inputs" to "3", "xor" to "odd")).toULongOrNull())
    }

    @Test
    fun gatesResolveControllingBitsWithoutHidingUnresolvedBits() {
        val a = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR))
        val b = LogicVector.fromBits(listOf(LogicValue.UNKNOWN, LogicValue.HIGH, LogicValue.HIGH, LogicValue.LOW))
        val attrs = mapOf("width" to "4")
        assertEquals("0E10", output(ComponentKind.AND, listOf(a, b), attrs).display)
        assertEquals("E11E", output(ComponentKind.OR, listOf(a, b), attrs).display)
        assertEquals("EE0E", output(ComponentKind.XOR, listOf(a, b), attrs).display)
        assertEquals(ULong.MAX_VALUE, output(ComponentKind.OR,
            listOf(LogicVector.of(64, 0xaaaaaaaaaaaaaaaauL), LogicVector.of(64, 0x5555555555555555uL)),
            mapOf("width" to "64")).toULongOrNull())
    }

    @Test
    fun physicallyMissingInputsFollowTheProjectUndefinedPolicy() {
        assertEquals("1", output(ComponentKind.AND, listOf(bit(1), null)).display)
        assertEquals("E", output(ComponentKind.AND, listOf(bit(0), null), error = true).display)
        assertEquals("E", output(ComponentKind.OR, listOf(null, null)).display)
        assertEquals("?", output(GatesLibrary.OddParity, listOf(LogicVector.unknown(1), null)).display)
        assertEquals("E", output(ComponentKind.XOR, listOf(LogicVector.unknown(1), null)).display)
    }

    @Test
    fun eachNegatedInputAndThreeStateOutputIsAppliedIndependently() {
        assertEquals("0", output(ComponentKind.AND, listOf(bit(1), bit(1)), mapOf("negate0" to "true")).display)
        assertEquals("?", output(ComponentKind.OR, listOf(bit(1), bit(0)), mapOf("out" to "0Z")).display)
        assertEquals("?", output(ComponentKind.AND, listOf(bit(1), bit(0)), mapOf("out" to "Z1")).display)
        val allHigh = List(64) { bit(1) }
        assertEquals("0", output(ComponentKind.AND, allHigh,
            mapOf("inputs" to "64", "negate63" to "true")).display)
    }

    @Test
    fun buffersAndInvertersHaveTheDesktopUndefinedAndEnablePolicies() {
        val unknown = LogicVector.unknown(4)
        val attrs = mapOf("width" to "4")
        assertEquals("????", output(ComponentKind.BUFFER, listOf(unknown), attrs).display)
        assertEquals("EEEE", output(ComponentKind.BUFFER, listOf(unknown), attrs, error = true).display)
        assertEquals("EEEE", output(ComponentKind.NOT, listOf(unknown), attrs).display)
        assertEquals("????", output(ComponentKind.CONTROLLED_BUFFER, listOf(unknown, bit(1)), attrs, error = true).display)
        assertEquals("????", output(ComponentKind.CONTROLLED_INVERTER, listOf(bit(1).resize(4), bit(0)), attrs).display)
        assertEquals("EEEE", output(ComponentKind.CONTROLLED_BUFFER, listOf(unknown, LogicVector.unknown(1)), attrs, error = true).display)
        assertEquals("????", output(ComponentKind.CONTROLLED_BUFFER, listOf(LogicVector.error(4), bit(0)), attrs).display)
        assertEquals("1110", output(ComponentKind.CONTROLLED_INVERTER,
            listOf(LogicVector.of(4, 1uL), bit(1)), attrs).display)
        assertEquals("1", output(ComponentKind.BUFFER, listOf(bit(1)), mapOf("out" to "0Z"), error = true).display)
    }

    @Test
    fun controlledGatesRetainTheirUndefinedEnableBehaviorWhenReopened() {
        fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        val profiles = mapOf("2.7.1" to "EEEE", "5.1.0-dev" to "????")
        for ((source, expected) in profiles) {
            val project = read("""
                <project source="$source" version="1.0">
                  <lib name="0" desc="#Wiring"/><lib name="1" desc="#Gates"/>
                  <main name="main"/><circuit name="main">
                    <comp loc="(200,100)" name="Controlled Buffer" lib="1"><a name="width" val="4"/></comp>
                    <comp loc="(200,200)" name="Controlled Inverter" lib="1"><a name="width" val="4"/></comp>
                    <wire from="(180,210)" to="(150,210)"/>
                  </circuit>
                </project>
            """.trimIndent())
            val reopened = profiles.keys.map { destination ->
                read(CircCodec.writeProject(project.copy(sourceVersion = destination)))
            }
            for (candidate in listOf(project) + reopened) {
                val ports = CircuitSimulator().settle(candidate).portVectors
                assertEquals(expected, ports.getValue(GridPoint(200, 100)).display)
                assertEquals(expected, ports.getValue(GridPoint(200, 200)).display)
            }
        }
    }

    @Test
    fun layoutRetainsDesktopPinOrderInAllFourDirections() {
        val east = registry.create(ComponentKind.XNOR, "g", GridPoint(100, 100),
            mapOf("inputs" to "4", "size" to "70", "negate3" to "true", "width" to "8"))
        val eastPorts = registry.layout(east)
        assertEquals(listOf(GridPoint(10, 70), GridPoint(10, 90), GridPoint(10, 110), GridPoint(0, 130)),
            eastPorts.inputs.map { it.position })
        assertEquals(ComponentBounds(0, 65, 100, 135), eastPorts.bounds)
        assertTrue(eastPorts.ports.all { it.width == 8 })
        val west = registry.layout(east.copy(attributes = east.attributes + ("facing" to "west")))
        assertEquals(listOf(GridPoint(190, 70), GridPoint(190, 90), GridPoint(190, 110), GridPoint(200, 130)), west.inputs.map { it.position })
        val north = registry.layout(east.copy(attributes = east.attributes + ("facing" to "north")))
        assertEquals(GridPoint(70, 190), north.inputs.first().position)
        val south = registry.layout(east.copy(attributes = east.attributes + ("facing" to "south")))
        assertEquals(GridPoint(70, 10), south.inputs.first().position)
    }

    @Test
    fun controlledAndUnaryPortsRespectSizeAndControlSide() {
        val component = registry.create(ComponentKind.CONTROLLED_INVERTER, "g", GridPoint(100, 100),
            mapOf("size" to "30", "control" to "left"))
        assertEquals(listOf(GridPoint(70, 100), GridPoint(80, 90)), registry.layout(component).inputs.map { it.position })
        assertEquals(ComponentBounds(70, 90, 100, 110), registry.layout(component).bounds)
        val narrow = registry.create(ComponentKind.NOT, "g", GridPoint(100, 100), mapOf("size" to "20"))
        assertEquals(GridPoint(80, 100), registry.layout(narrow).inputs.single().position)
        assertFailsWith<IllegalArgumentException> { registry.create(ComponentKind.AND, "g", GridPoint(0, 0), mapOf("size" to "40")) }
        assertFailsWith<IllegalArgumentException> { registry.create(ComponentKind.AND, "g", GridPoint(0, 0), mapOf("inputs" to "1")) }
    }

    @Test
    fun plaUsesFirstMatchingRowAndTheDesktopUndefinedInputConversion() {
        val program = PlaProgram.parse("10x 01 # first\n111 10 # second\nxxx 11 # fallback")
        assertEquals(listOf(1uL, 1uL, 2uL, 2uL), listOf(4uL, 5uL, 7uL, ULong.MAX_VALUE).map {
            program.evaluate(LogicVector.of(3, it)).toULongOrNull()
        })
        assertEquals(2uL, program.evaluate(LogicVector.unknown(3)).toULongOrNull())
        assertEquals(2uL, program.evaluate(LogicVector.error(3)).toULongOrNull())
        val priority = PlaProgram.parse("xxx 01\n111 10")
        assertEquals(1uL, priority.evaluate(LogicVector.of(3, 7uL)).toULongOrNull())
        val flexible = PlaProgram.parse("| 1 | x || 0 | 1 | # hi")
        assertEquals("1x 01 # hi\n", flexible.serialize())
        assertEquals("01", flexible.evaluate(LogicVector.of(2, 2uL)).display)
        assertEquals("0001", flexible.resized(3, 4).evaluate(LogicVector.of(3, 2uL)).display)
    }

    @Test
    fun allThirteenDefinitionsEvaluateWithOneTimeUnitDelay() {
        assertEquals(listOf("NOT Gate", "Buffer", "AND Gate", "OR Gate", "NAND Gate", "NOR Gate",
            "XOR Gate", "XNOR Gate", "Odd Parity", "Even Parity", "Controlled Buffer", "Controlled Inverter", "PLA"),
            registry.definitions.map { it.fileName })
        for (definition in registry.definitions) {
            val attrs = if (definition.kind == GatesLibrary.Pla) mapOf("table" to "11 10") else emptyMap()
            val component = registry.create(definition.kind, "g", GridPoint(0, 0), attrs)
            val values = registry.attributes(component)
            val ports = registry.layout(component).inputs
            val input = ports.associate { it.name to LogicVector.high(it.width) }
            val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component, values, input, input.keys))
            assertEquals(1, result.outputs.size, definition.fileName)
            assertEquals(1L, result.delay, definition.fileName)
            assertEquals(true, result.outputs.getValue("out").isFullyDefined, definition.fileName)
        }
    }

    @Test fun plaWidthEditsResizeTheTableAndFilesTakeWidthsFromIt() {
        val pla = registry.create(GatesLibrary.Pla, "pla", GridPoint(100, 100), mapOf("table" to "01 1\n10 0\n"))
        val wider = registry.reconfigure(pla, mapOf("out_width" to "3"))
        assertEquals("01 001\n10 000\n", wider.attributes["table"])
        assertEquals(3, registry.layout(wider).outputs.single().width)

        fun reopened(attributes: String): Pair<Int, Int> {
            val xml = """<project source="3.8.0" version="1.0"><lib desc="#Gates" name="0"/><main name="main"/>
                <circuit name="main"><comp lib="0" loc="(100,100)" name="PLA">$attributes</comp></circuit></project>"""
            val component = (CircCodec.readProject(xml) as CircProjectImportResult.Success).project.mainCircuit.components.single()
            val layout = ComponentCatalog.registry.layout(component)
            return layout.inputs.single().width to layout.outputs.single().width
        }
        // Evolution's PlaTable parse defines the widths; an empty table reads as 2x2.
        assertEquals(2 to 2, reopened("""<a name="out_width" val="1"/>"""))
        assertEquals(2 to 3, reopened("""<a name="out_width" val="1"/><a name="table" val="01 001&#10;10 000&#10;"/>"""))
    }
}
