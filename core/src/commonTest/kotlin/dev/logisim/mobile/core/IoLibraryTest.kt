package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IoLibraryTest {
    private fun component(kind: ComponentKind, attributes: Map<String, String> = emptyMap()) =
        CircuitComponent("io", kind, GridPoint(200, 200), attributes = attributes)

    private fun evaluate(
        kind: ComponentKind,
        attributes: Map<String, String> = emptyMap(),
        state: ComponentState = EmptyComponentState,
        inputs: Map<String, LogicVector> = emptyMap(),
        events: List<IoInputEvent> = emptyList(),
        ticks: Long = 0,
    ): ComponentEvaluation {
        val definition = IoLibrary.definition(kind)!!
        val part = component(kind, attributes)
        return definition.behavior!!.evaluate(ComponentEvaluationContext(part, definition.schema.parse(attributes), inputs,
            inputs.keys, state, ticks = ticks, events = events))
    }

    private fun bit(high: Boolean) = if (high) LogicVector.high(1) else LogicVector.low(1)

    @Test
    fun catalogMatchesTheTwelveInteractiveAndDisplayComponents() {
        assertEquals(listOf("Button", "DipSwitch", "Joystick", "Keyboard", "LED", "LedBar", "RGBLED",
            "7-Segment Display", "Hex Digit Display", "DotMatrix", "TTY", "MatrixKeypad"), IoLibrary.definitions.map { it.fileName })
        assertEquals(12, IoLibrary.definitions.map { it.kind }.distinct().size)
        assertTrue(IoLibrary.definitions.all { it.library == "#I/O" })
    }

    @Test
    fun buttonPressReleaseAndCancelledGestureDriveTheConfiguredPolarity() {
        var result = evaluate(IoKinds.Button)
        assertEquals(0uL, result.outputs.getValue("out").toULongOrNull())
        result = evaluate(IoKinds.Button, state = result.state, events = listOf(IoInputEvent.Press(true)))
        assertEquals(1uL, result.outputs.getValue("out").toULongOrNull())
        result = evaluate(IoKinds.Button, state = result.state, events = listOf(IoInputEvent.Cancel))
        assertEquals(0uL, result.outputs.getValue("out").toULongOrNull())
        result = evaluate(IoKinds.Button, mapOf("press" to "passive"), events = listOf(IoInputEvent.Press(true)))
        assertEquals(0uL, result.outputs.getValue("out").toULongOrNull())
        result = evaluate(IoKinds.Button, mapOf("press" to "passive"), result.state, events = listOf(IoInputEvent.Press(false)))
        assertEquals(1uL, result.outputs.getValue("out").toULongOrNull())
    }

    @Test
    fun dipKeepsThirtyTwoIndependentBitsAndPublishesRememberedPositions() {
        val attributes = mapOf("number" to "32", "resetbehavior" to "closed")
        var result = evaluate(IoKinds.DipSwitch, attributes)
        assertEquals(32, result.outputs.size)
        assertTrue(result.outputs.values.all { it.toULongOrNull() == 1uL })
        result = evaluate(IoKinds.DipSwitch, attributes, result.state, events = listOf(IoInputEvent.ToggleSwitch(31)))
        assertEquals(0uL, result.outputs.getValue("switch-31").toULongOrNull())
        assertEquals(1uL, result.outputs.getValue("switch-30").toULongOrNull())
        val remembered = component(IoKinds.DipSwitch, mapOf("resetbehavior" to "last"))
        assertEquals(mapOf("positions" to "7fffffff"), IoLibrary.savedInteractionAttributes(remembered, result.state))
        assertEquals(IoDipState(0x81u), IoLogic.dipInitial(8, "last", 0x181u))
    }

    @Test
    fun dipExportsRememberedPositionsInEvolutionNotation() {
        for ((bits, mask) in listOf(listOf(31, 0) to 0x80000001u, listOf(31, 0, 1, 3) to 0x8000000bu)) {
            val project = importDip("0x0")
            val document = project.mainCircuit
            val dip = document.components.single()
            val state = CircuitSimulator().settle(project, events = mapOf(dip.id to bits.map { IoInputEvent.ToggleSwitch(it) }))
            val remembered = IoLibrary.savedInteractionAttributes(dip, state.componentStates.getValue(dip.id))
            val updated = document.copy(components = listOf(dip.copy(attributes = dip.attributes + remembered)))
            val xml = CircCodec.writeProject(project.withCircuit(updated))

            assertTrue(xml.contains("name=\"positions\" val=\"0x${mask.toString(16)}\""), xml)
            assertDipOutputs(assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project, mask)
        }
    }

    @Test
    fun dipReadsEvolutionNumbersAndPreservesBareHexInLegacyMobileFiles() {
        val evolutionMasks = mapOf("10" to 0xau, "012" to 0xau, "0b1010" to 0xau,
            "-2147483648" to 0x80000000u, "4294967295" to UInt.MAX_VALUE, "0X8000000B" to 0x8000000bu)
        val mobileMasks = mapOf("10" to 0x10u, "80000001" to 0x80000001u, "8000000b" to 0x8000000bu)
        for ((mobile, masks) in listOf(false to evolutionMasks, true to mobileMasks)) {
            for ((raw, mask) in masks) {
                val project = importDip(raw, mobile)
                assertDipOutputs(project, mask)
                val xml = CircCodec.writeProject(project)
                assertTrue(xml.contains("name=\"positions\" val=\"0x${mask.toString(16)}\""), xml)
            }
        }
    }

    private fun importDip(positions: String, mobile: Boolean = false): CircuitProject {
        val identity = if (mobile) " logisim-mobile-id=\"dip\"" else ""
        val xml = """
            <project source="5.1.0-dev" version="1.0">
              <lib name="0" desc="#I/O"/>
              <circuit name="main">
                <comp lib="0" name="DipSwitch" loc="(100,100)"$identity>
                  <a name="number" val="32"/>
                  <a name="resetbehavior" val="last"/>
                  <a name="positions" val="$positions"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent()
        return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
    }

    private fun assertDipOutputs(project: CircuitProject, mask: UInt) {
        val dip = project.mainCircuit.components.single()
        val outputs = CircuitSimulator().settle(project).componentOutputs.getValue(dip.id)
        assertEquals(32, outputs.size)
        repeat(32) { bit ->
            assertEquals(((mask shr bit) and 1u).toULong(), outputs.getValue("switch-$bit").toULongOrNull(), "bit $bit")
        }
    }

    @Test
    fun joystickMatchesDesktopQuantizationAndRecentersOnRelease() {
        for ((width, center, maximum) in listOf(Triple(2, 2uL, 3uL), Triple(3, 4uL, 7uL),
            Triple(4, 8uL, 15uL), Triple(5, 16uL, 31uL))) {
            val attributes = mapOf("bits" to width.toString())
            var result = evaluate(IoKinds.Joystick, attributes, events = listOf(IoInputEvent.MoveJoystick(-100, 100)))
            assertEquals(1uL, result.outputs.getValue("x").toULongOrNull())
            assertEquals(maximum, result.outputs.getValue("y").toULongOrNull())
            result = evaluate(IoKinds.Joystick, attributes, result.state, events = listOf(IoInputEvent.Press(false)))
            assertEquals(center, result.outputs.getValue("x").toULongOrNull())
            assertEquals(center, result.outputs.getValue("y").toULongOrNull())
        }
    }

    @Test
    fun reducingDipSizeDiscardsBitsThatNoLongerHaveSwitches() {
        val small = evaluate(IoKinds.DipSwitch, mapOf("number" to "4"), IoDipState(0x81u))
        assertEquals(IoDipState(1u), small.state)
        val larger = evaluate(IoKinds.DipSwitch, mapOf("number" to "8"), small.state)
        assertEquals(0uL, larger.outputs.getValue("switch-7").toULongOrNull())
    }

    @Test
    fun keyboardEditsQueueAndDequeuesOnlyOnEnabledClockEdges() {
        val attributes = mapOf("buflen" to "4")
        val lowClock = mapOf("clock" to bit(false), "clear" to bit(false), "readEnable" to bit(true))
        var result = evaluate(IoKinds.Keyboard, attributes, inputs = lowClock,
            events = listOf(IoInputEvent.InsertText("ABCDX"), IoInputEvent.EditKeyboard(KeyboardCommand.HOME),
                IoInputEvent.EditKeyboard(KeyboardCommand.DELETE), IoInputEvent.InsertText("é")))
        val state = assertIs<IoKeyboardState>(result.state)
        assertEquals("éBCD", state.buffer)
        assertEquals(1, state.cursor)
        assertEquals(0x69uL, result.outputs.getValue("data").toULongOrNull())
        assertEquals(1uL, result.outputs.getValue("available").toULongOrNull())
        assertEquals(mapOf("data" to 9L, "available" to 11L), result.outputDelays)
        result = evaluate(IoKinds.Keyboard, attributes, result.state, lowClock + ("clock" to bit(true)))
        assertEquals("BCD", assertIs<IoKeyboardState>(result.state).buffer)
        result = evaluate(IoKinds.Keyboard, attributes, result.state, lowClock + ("clock" to bit(true)))
        assertEquals("BCD", assertIs<IoKeyboardState>(result.state).buffer)
        result = evaluate(IoKinds.Keyboard, attributes, result.state, lowClock + ("clear" to bit(true)))
        assertEquals(0uL, result.outputs.getValue("available").toULongOrNull())
        assertEquals(0uL, result.outputs.getValue("data").toULongOrNull())
    }

    @Test
    fun keyboardPreservesTransmittedControlsAndIgnoresOtherIsoControls() {
        val state = IoLogic.editKeyboard(IoKeyboardState(), 32,
            listOf(IoInputEvent.InsertText("A\u0001\b\n\u000cZ")))
        assertEquals("A\b\n\u000cZ", state.buffer)
        assertEquals("A   Z", state.displayText)
        val replaced = IoLogic.editKeyboard(state, 3, listOf(IoInputEvent.ReplaceKeyboardText("Q\u0002RST", 4)))
        assertEquals("QRS", replaced.buffer)
        assertEquals(3, replaced.cursor)
    }

    @Test
    fun ttyWritesOnClockAndSupportsWrapScrollBackspaceAndFormFeed() {
        var state = IoTerminalState(rows = 2, columns = 3)
        for (character in "ABCD\nEF\bG") state = IoLogic.addTerminal(state, character)
        assertEquals(listOf("D", "EG"), state.displayRows)
        assertEquals(1, state.cursorRow)
        assertEquals(2, state.cursorColumn)
        state = IoLogic.addTerminal(state, '\u000c')
        assertEquals(listOf("", ""), state.displayRows)
        val low = mapOf("clock" to bit(false), "writeEnable" to bit(true), "clear" to bit(false), "data" to LogicVector.of(7, 65u))
        var result = evaluate(IoKinds.Tty, mapOf("rows" to "2", "cols" to "3"), inputs = low)
        result = evaluate(IoKinds.Tty, mapOf("rows" to "2", "cols" to "3"), result.state, low + ("clock" to bit(true)))
        assertEquals(listOf("A", ""), assertIs<IoTerminalState>(result.state).displayRows)
        result = evaluate(IoKinds.Tty, mapOf("rows" to "2", "cols" to "3"), result.state, low + ("clock" to bit(true)))
        assertEquals("A", assertIs<IoTerminalState>(result.state).current)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun ttyOneRowAndResizeRetainTheNewestVisibleText() {
        var one = IoTerminalState(rows = 1, columns = 2)
        for (character in "ABC") one = IoLogic.addTerminal(one, character)
        assertEquals(listOf("C"), one.displayRows)
        var several = IoTerminalState(rows = 3, columns = 4)
        for (character in "abcd\nefgh\nijkl") several = IoLogic.addTerminal(several, character)
        assertEquals(listOf("ef", "ij"), IoLogic.resizeTerminal(several, 2, 2).displayRows)
    }

    @Test
    fun keypadRawAndPmodResolveMultiplePressedRowsWithoutLosingConflicts() {
        var state = IoLogic.applyKeypadEvents(IoKeypadState(), listOf(IoInputEvent.KeypadKey(0, 2, true)))
        val rows = listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR)
        assertEquals(listOf(LogicValue.UNKNOWN, LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.UNKNOWN),
            IoLogic.keypadOutputs(state, rows, false).map { it.bit(0) })
        state = IoLogic.applyKeypadEvents(state, listOf(IoInputEvent.KeypadKey(1, 2, true)))
        assertEquals(LogicValue.ERROR, IoLogic.keypadOutputs(state, rows, false)[2].bit(0))
        assertEquals(LogicValue.ERROR, IoLogic.keypadOutputs(state, rows, true)[2].bit(0))
        state = IoLogic.applyKeypadEvents(state, listOf(IoInputEvent.Cancel))
        assertTrue(IoLogic.keypadOutputs(state, rows, true).all { it.bit(0) == LogicValue.HIGH })
    }

    @Test
    fun ledsAndSegmentsHaveExplicitUnknownAndActiveLowBehavior() {
        assertFalse(IoLogic.ledOn(LogicValue.UNKNOWN, false))
        assertTrue(IoLogic.ledOn(LogicValue.LOW, false))
        assertEquals(0xffffff00u, IoLogic.rgbColor(3, true))
        assertEquals(0xff0000ffu, IoLogic.rgbColor(3, false))
        assertEquals(listOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07,
            0x7f, 0x67, 0x77, 0x7c, 0x39, 0x5e, 0x79, 0x71), (0..15).map { IoLogic.hexSegments(LogicVector.of(4, it.toULong())) })
        assertEquals(0, IoLogic.hexSegments(LogicVector.unknown(4)))
        assertEquals(0xff, IoLogic.hexSegments(LogicVector.of(4, 8u), LogicValue.HIGH))
    }

    @Test
    fun hexDisplayUsesImportedGlyphsAndKeepsDecimalPointIndependent() {
        fun read(source: String, data: Int? = null, decimalPoint: Boolean = false): CircuitProject {
            val dataConnection = if (data != null) """
                <comp lib="0" loc="(100,260)" name="Constant">
                  <a name="width" val="4"/><a name="value" val="0x${data.toString(16)}"/>
                </comp>
                <wire from="(100,260)" to="(300,260)"/>
                <wire from="(300,260)" to="(300,300)"/>
            """.trimIndent() else ""
            val pointX = if (source == "2.7.1") 310 else 320
            val decimalPointConnection = if (decimalPoint) """
                <comp lib="0" loc="($pointX,400)" name="Constant"><a name="value" val="0x1"/></comp>
                <wire from="($pointX,300)" to="($pointX,400)"/>
            """.trimIndent() else ""
            val xml = """
                <project source="$source" version="1.0">
                  <lib desc="#Wiring" name="0"/>
                  <lib desc="#I/O" name="1"/>
                  <main name="main"/>
                  <circuit name="main">
                    <comp lib="1" loc="(300,300)" name="Hex Digit Display"/>
                    $dataConnection
                    $decimalPointConnection
                  </circuit>
                </project>
            """.trimIndent()
            return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        }

        fun segments(project: CircuitProject): Int {
            val display = project.mainCircuit.components.single { it.kind == IoKinds.HexDigit }
            return assertIs<IoLampState>(CircuitSimulator().settle(project).componentStates[display.id]).bits
        }

        val floating = read("2.7.1")
        assertEquals(0x40, segments(floating))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(floating))).project
        assertEquals(0x40, segments(reopened))
        assertEquals(0xc0, segments(read("2.7.1", decimalPoint = true)))
        val legacyMasks = listOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07,
            0x7f, 0x67, 0x77, 0x7c, 0x39, 0x5e, 0x79, 0x71)
        assertEquals(legacyMasks, (0..15).map { segments(read("2.7.1", data = it)) })
        assertEquals(0xe7, segments(read("2.7.1", data = 9, decimalPoint = true)))
        assertEquals(0x67, segments(read("5.1.0-dev", data = 9)))
        assertEquals(0xe7, segments(read("5.1.0-dev", data = 9, decimalPoint = true)))
        assertEquals(0, segments(read("5.1.0-dev")))
    }

    @Test
    fun dotMatrixUsesTheOffColorOfItsImportedProfile() {
        fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        fun fixture(source: String, offColor: String? = null): CircuitProject {
            val offColorAttribute = offColor?.let { """<a name="offcolor" val="$it"/>""" }.orEmpty()
            return read("""
                <project source="$source" version="1.0">
                  <lib desc="#Wiring" name="0"/>
                  <lib desc="#I/O" name="1"/>
                  <main name="main"/>
                  <circuit name="main">
                    <comp lib="0" loc="(100,100)" name="Constant"><a name="width" val="2"/><a name="value" val="0x2"/></comp>
                    <comp lib="1" loc="(200,100)" name="DotMatrix">
                      <a name="inputtype" val="row"/><a name="matrixcols" val="2"/><a name="matrixrows" val="1"/>
                      <a name="color" val="#808080"/>$offColorAttribute
                    </comp>
                    <wire from="(100,100)" to="(200,100)"/>
                  </circuit>
                </project>
            """.trimIndent())
        }

        fun check(project: CircuitProject, expectedOffColor: UInt) {
            val display = project.mainCircuit.components.single { it.kind == IoKinds.DotMatrix }
            val attributes = ComponentCatalog.registry.attributes(display)
            assertEquals(0xff808080u, attributes[IoAttributes.MatrixOnColor])
            assertEquals(expectedOffColor, attributes[IoAttributes.MatrixOffColor])
            val state = assertIs<IoMatrixState>(CircuitSimulator().settle(project).componentStates[display.id])
            assertEquals(listOf(LogicValue.HIGH, LogicValue.LOW), state.values)
        }

        val original = fixture("2.7.1")
        val modern = fixture("5.1.0-dev")
        val explicit = fixture("2.7.1", "#123456")
        for ((project, offColor) in listOf(original to 0xff404040u, modern to 0xff808080u, explicit to 0xff123456u)) {
            check(project, offColor)
            for (destination in listOf("2.7.1", "5.1.0-dev")) {
                check(read(CircCodec.writeProject(project.copy(sourceVersion = destination))), offColor)
            }
        }
    }

    @Test
    fun matrixPackingAndPersistenceMatchTheDesktopBitOrder() {
        val empty = IoMatrixState(2, 2)
        val row = IoLogic.matrixUpdate(empty, "row", listOf(LogicVector.of(2, 2u), LogicVector.of(2, 1u)), 3, 2)
        assertEquals(listOf(LogicValue.HIGH, LogicValue.LOW, LogicValue.LOW, LogicValue.HIGH), row.values)
        val column = IoLogic.matrixUpdate(empty, "column", listOf(LogicVector.of(2, 2u), LogicVector.of(2, 1u)), 3, 2)
        assertEquals(row.values, column.values)
        val off = IoLogic.matrixUpdate(row, "row", listOf(LogicVector.low(2), LogicVector.low(2)), 4, 2)
        assertEquals(LogicValue.HIGH, off.visible(0, 0, 5))
        assertEquals(LogicValue.LOW, off.visible(0, 0, 6))
        val select = IoLogic.matrixUpdate(empty, "select", listOf(LogicVector.of(2, 1u), LogicVector.of(2, 2u)), 1, 0)
        assertEquals(listOf(LogicValue.HIGH, LogicValue.LOW, LogicValue.LOW, LogicValue.LOW), select.values)
    }

    @Test
    fun defaultPortCoordinatesWidthsAndNamesAreStable() {
        fun layout(kind: ComponentKind, attributes: Map<String, String> = emptyMap()): ComponentLayout {
            val definition = IoLibrary.definition(kind)!!
            return definition.layout(component(kind, attributes), definition.schema.parse(attributes))
        }
        assertEquals(listOf(GridPoint(200, 200)), layout(IoKinds.Button).ports.map { it.position })
        assertEquals((1..8).map { GridPoint(200 + it * 10, 200) }, layout(IoKinds.DipSwitch).ports.map { it.position })
        assertEquals(listOf(GridPoint(200, 200), GridPoint(200, 210)), layout(IoKinds.Joystick).ports.map { it.position })
        assertEquals(listOf(1, 1, 1, 1, 7), layout(IoKinds.Keyboard).ports.map { it.width })
        assertEquals(listOf(GridPoint(190, 230)), layout(IoKinds.SevenSegment, mapOf("inputmode" to "bus")).ports.map { it.position })
        assertEquals(listOf(GridPoint(200, 200), GridPoint(220, 200)), layout(IoKinds.HexDigit).ports.map { it.position })
        assertEquals(listOf(7, 7, 7, 7, 7), layout(IoKinds.DotMatrix).ports.map { it.width })
        assertEquals(listOf(1, 1, 1, 7), layout(IoKinds.Tty).ports.map { it.width })
        assertEquals(8, layout(IoKinds.MatrixKeypad).ports.size)
    }

    @Test
    fun schemasRejectInvalidGeometryAndAcceptRgbaColorsWithoutLosingAlpha() {
        assertFailsWith<IllegalArgumentException> { IoLibrary.definition(IoKinds.DipSwitch)!!.schema.parse(mapOf("number" to "33")) }
        assertFailsWith<IllegalArgumentException> { IoLibrary.definition(IoKinds.Joystick)!!.schema.parse(mapOf("bits" to "1")) }
        assertFailsWith<IllegalArgumentException> { IoLibrary.definition(IoKinds.Keyboard)!!.schema.parse(mapOf("buflen" to "0")) }
        assertEquals(0x00dcdcdcu, IoAttributes.parseColor("#dcdcdc00"))
        assertEquals(0xff00ff00u, IoAttributes.parseColor("#00ff00"))
    }

    @Test
    fun circuitPointerActionsHaveMatchingReleasesAndRespectKeyGaps() {
        val button = component(IoKinds.Button)
        assertEquals(IoPointerAction(IoInputEvent.Press(true), IoInputEvent.Press(false)),
            IoInteractions.press(button, GridPoint(190, 200)))
        val joystick = component(IoKinds.Joystick)
        assertEquals(IoInputEvent.MoveJoystick(14, -14), IoInteractions.drag(joystick, GridPoint(1000, -1000)))
        val keypad = component(IoKinds.MatrixKeypad)
        assertEquals(IoPointerAction(IoInputEvent.KeypadKey(0, 0, true), IoInputEvent.KeypadKey(0, 0, false)),
            IoInteractions.press(keypad, GridPoint(180, 180)))
        assertEquals(null, IoInteractions.press(keypad, GridPoint(185, 180)))
        val southDip = component(IoKinds.DipSwitch, mapOf("facing" to "south", "number" to "8"))
        assertEquals(IoInputEvent.ToggleSwitch(0), IoInteractions.press(southDip, GridPoint(120, 180))?.press)
    }

    @Test
    fun demoIncludesEveryIoDefinitionAndKeepsUserControlsDistinct() {
        val document = IoDemoCircuits.controls().mainCircuit
        assertEquals(IoLibrary.definitions.map { it.kind }.toSet(), document.components.map { it.kind }.toSet().intersect(IoLibrary.definitions.map { it.kind }.toSet()))
        assertEquals(document.components.size, document.components.map { it.id }.distinct().size)
        assertTrue(document.wires.all { it.from.x == it.to.x || it.from.y == it.to.y })
        assertTrue(document.components.any { it.id == "keyboard" })
        assertTrue(document.components.any { it.id == "tty" })
    }
}
