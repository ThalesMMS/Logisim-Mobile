package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IoStateDescriptionsTest {
    private fun part(kind: ComponentKind, attributes: Map<String, String> = emptyMap()) = CircuitComponent("part", kind, GridPoint(0, 0), attributes = attributes)
    private fun evaluated(component: CircuitComponent, inputs: Map<String, LogicVector> = emptyMap(),
                          events: List<IoInputEvent> = emptyList(), state: ComponentState = EmptyComponentState, ticks: Long = 0): ComponentState {
        val definition = requireNotNull(IoLibrary.definition(component.kind))
        return requireNotNull(definition.behavior).evaluate(ComponentEvaluationContext(component, definition.schema.parse(component.attributes),
            inputs, inputs.keys, state, ticks = ticks, events = events)).state
    }
    private fun text(component: CircuitComponent, state: ComponentState?, ticks: Long = 0, language: AppLanguage = AppLanguage.ENGLISH): String =
        requireNotNull(accessibleIoStateDescription(component, state, ticks)).resolve(language)

    @Test fun allTwelveKindsExposeTheirActualEvaluatedState() {
        val project = IoDemoCircuits.controls()
        val simulation = CircuitSimulator().settle(project)
        val kinds = IoLibrary.definitions.map { it.kind }.toSet()
        val components = project.mainCircuit.components.filter { it.kind in kinds }
        assertEquals(kinds, components.map { it.kind }.toSet())
        for (component in components) for (language in AppLanguage.entries) {
            val description = text(component, simulation.componentStates[component.id], simulation.ticks, language)
            assertFalse(description.contains("unavailable") || description.contains("indisponível"), component.kind.name)
            assertFalse(description.contains("io_state_"), description)
            assertTrue(description.length < 512, component.kind.name)
        }
    }

    @Test fun buttonDipAndJoystickDescribePositionsAndHumanValues() {
        val button = part(IoKinds.Button)
        val pressed = evaluated(button, events = listOf(IoInputEvent.Press(true)))
        assertEquals("pressed", text(button, pressed))
        assertEquals("pressionado", text(button, pressed, language = AppLanguage.PORTUGUESE))
        assertEquals("released", text(button, evaluated(button, events = listOf(IoInputEvent.Cancel), state = pressed)))
        val dip = part(IoKinds.DipSwitch, mapOf("number" to "4"))
        val switches = evaluated(dip, events = listOf(IoInputEvent.ToggleSwitch(1), IoInputEvent.ToggleSwitch(3)))
        assertEquals("Switches: 4; on: 2, 4.", text(dip, switches))
        assertEquals("Chaves: 4; ligadas: 2, 4.", text(dip, switches, language = AppLanguage.PORTUGUESE))
        val joystick = part(IoKinds.Joystick, mapOf("bits" to "4"))
        val moved = evaluated(joystick, events = listOf(IoInputEvent.MoveJoystick(14, -14)))
        assertEquals("Position X 14, Y -14; output X 15, Y 1, 4 bits.", text(joystick, moved))
    }

    @Test fun keyboardNamesPendingControlsAndCursor() {
        val keyboard = part(IoKinds.Keyboard, mapOf("buflen" to "8"))
        val typed = evaluated(keyboard, events = listOf(IoInputEvent.InsertText("A\b\u000c")))
        assertEquals("Buffer 3/8; cursor 4. Text: A[backspace][form feed]", text(keyboard, typed))
        assertEquals("Buffer 3/8; cursor 4. Texto: A[retrocesso][limpar tela]", text(keyboard, typed, language = AppLanguage.PORTUGUESE))
        assertFalse(text(keyboard, typed).any { it.code < 32 })
    }

    @Test fun longTextAndControlsRemainBoundedWithoutSplittingSurrogates() {
        val keyboard = part(IoKinds.Keyboard, mapOf("buflen" to "256"))
        val buffer = "A".repeat(95) + "😀" + "B".repeat(100)
        for (language in AppLanguage.entries) {
            val description = text(keyboard, IoKeyboardState(buffer, buffer.length), language = language)
            assertTrue(description.length < 512)
            assertFalse(description.any { it.isSurrogate() })
            assertTrue(description.contains("101"), description)
            val controls = text(keyboard, IoKeyboardState("\b".repeat(100), 100), language = language)
            assertTrue(controls.length < 512)
            assertTrue(controls.contains("92"), controls)
            assertFalse(controls.any { it.code < 32 })
        }
    }

    @Test fun terminalUsesEvaluatedDisplayRowsDimensionsAndCursor() {
        val terminal = part(IoKinds.Tty, mapOf("rows" to "3", "cols" to "8"))
        var state: ComponentState = EmptyComponentState
        for (character in "ABC\nD") {
            val inputs = mapOf("clock" to LogicVector.low(1), "data" to LogicVector.of(7, character.code.toULong()),
                "writeEnable" to LogicVector.high(1), "clear" to LogicVector.low(1))
            state = evaluated(terminal, inputs, state = state)
            state = evaluated(terminal, inputs + ("clock" to LogicVector.high(1)), state = state)
        }
        assertEquals("Rows: 3; columns: 8; cursor row 2, column 2. Text: ABC[line break]D", text(terminal, state))
        assertEquals("Linhas: 3; colunas: 8; cursor na linha 2, coluna 2. Texto: ABC[quebra de linha]D", text(terminal, state, language = AppLanguage.PORTUGUESE))
    }

    @Test fun aFullTerminalRowDoesNotAnnounceAColumnOutsideItsDisplay() {
        val component = part(IoKinds.Tty, mapOf("rows" to "3", "cols" to "8"))
        val full = IoTerminalState(rows = 3, columns = 8, current = "ABCDEFGH")
        assertEquals("Rows: 3; columns: 8; current row 1 is full. The next printable character starts a new row. Text: ABCDEFGH", text(component, full))
        assertEquals("Linhas: 3; colunas: 8; a linha atual 1 está cheia. O próximo caractere imprimível inicia uma nova linha. Texto: ABCDEFGH", text(component, full, language = AppLanguage.PORTUGUESE))
        assertEquals("Rows: 3; columns: 8; cursor row 2, column 2. Text: ABCDEFGH[line break]I", text(component, IoLogic.addTerminal(full, 'I')))
        assertEquals("ABCDEFGH", full.current)
    }

    @Test fun ledReportsVisiblePolarityAndUnknownInputSeparately() {
        val lowActive = part(IoKinds.Led, mapOf("active" to "false"))
        assertEquals("on; input Low, 0.", text(lowActive, evaluated(lowActive, mapOf("in" to LogicVector.low(1)))))
        assertEquals("off; input 1 undefined bit.", text(lowActive, evaluated(lowActive, mapOf("in" to LogicVector.unknown(1)))))
        assertEquals("off; input 1 conflicting or erroneous bit.", text(lowActive, evaluated(lowActive, mapOf("in" to LogicVector.error(1)))))
    }

    @Test fun rgbDescribesTheVisibleColorForBothPolarities() {
        val expected = listOf("off, black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
        for (active in listOf(true, false)) for (mask in 0..7) {
            val component = part(IoKinds.RgbLed, mapOf("active" to active.toString()))
            val inputs = listOf("red", "green", "blue").mapIndexed { index, name ->
                name to LogicVector.of(1, (mask shr index and 1).toULong())
            }.toMap()
            assertEquals("Visible color: ${expected[if (active) mask else mask xor 7]}.", text(component, evaluated(component, inputs)))
        }
        val component = part(IoKinds.RgbLed)
        assertEquals("Visible color: off, black.", text(component, evaluated(component, mapOf("red" to LogicVector.error(1)))))
    }

    @Test fun hexRecognizesSegmentsInsteadOfTreatingTheMaskAsANibble() {
        val component = part(IoKinds.HexDigit)
        for (number in 0..15) {
            val state = evaluated(component, mapOf("data" to LogicVector.of(4, number.toULong()), "decimalPoint" to LogicVector.low(1)))
            assertEquals("Digit ${number.toString(16).uppercase()}; decimal point off.", text(component, state))
        }
        assertEquals("No segments lit; decimal point off.", text(component, evaluated(component, mapOf("data" to LogicVector.unknown(4)))))
        assertEquals("No segments lit; decimal point on.", text(component, evaluated(component, mapOf("data" to LogicVector.error(4), "decimalPoint" to LogicVector.high(1)))))
        val legacy = part(IoKinds.HexDigit, requireNotNull(IoLibrary.definition(IoKinds.HexDigit)).defaultsForVersion("2.7.1"))
        assertEquals("Digit 9; decimal point on.", text(legacy, evaluated(legacy, mapOf(
            "data" to LogicVector.of(4, 9u), "decimalPoint" to LogicVector.high(1)))))
    }

    @Test fun sevenSegmentNamesLitSegmentsAndRespectsPolarityAndHiddenPoint() {
        val component = part(IoKinds.SevenSegment, mapOf("inputmode" to "bus", "decimalPoint" to "false"))
        assertEquals("Lit segments: middle; decimal point not shown.", text(component, evaluated(component, mapOf("segments" to LogicVector.of(7, 64u)))))
        val lowActive = component.copy(attributes = component.attributes + ("active" to "false"))
        assertEquals("No segments lit; decimal point not shown.", text(lowActive, evaluated(lowActive, mapOf("segments" to LogicVector.of(7, 127u)))))
        val point = part(IoKinds.SevenSegment, mapOf("inputmode" to "bus"))
        assertEquals("Digit 0; decimal point on.", text(point, evaluated(point, mapOf("segments" to LogicVector.of(8, 191u)))))
    }

    @Test fun matrixFollowsInclusiveTickPersistenceAndDoesNotTreatErrorsAsLit() {
        val component = part(IoKinds.DotMatrix, mapOf("matrixrows" to "1", "matrixcols" to "4"))
        val state = IoMatrixState(1, 4, listOf(LogicValue.LOW, LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.ERROR), listOf(5, -1, 5, 5))
        assertEquals("Rows: 1; columns: 4; on: 1; off: 1; unknown: 1; errors: 1. Row 1, on columns: 1.", text(component, state, 5))
        assertEquals("Rows: 1; columns: 4; on: 0; off: 2; unknown: 1; errors: 1. Row 1, on columns: none.", text(component, state, 6))
        assertEquals(listOf(LogicValue.LOW, LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.ERROR), state.values)
    }

    @Test fun matrixDimensionsAndLargePreviewsMatchTheDrawingContract() {
        val component = part(IoKinds.DotMatrix, mapOf("matrixrows" to "2", "matrixcols" to "2"))
        assertEquals("Rows: 2; columns: 2; on: 0; off: 0; unknown: 4; errors: 0. Row 1, on columns: none. Row 2, on columns: none.",
            text(component, IoMatrixState(1, 1, listOf(LogicValue.HIGH))))
        val large = part(IoKinds.DotMatrix, mapOf("matrixrows" to "64", "matrixcols" to "64"))
        val state = IoMatrixState(64, 64, List(4096) { LogicValue.HIGH })
        for (language in AppLanguage.entries) {
            val description = text(large, state, language = language)
            assertTrue(description.length < 512, description)
            assertTrue(description.contains("4096"))
            assertTrue(description.contains("61"))
        }
    }

    @Test fun ledBarAndKeypadUseColumnsAndVisibleKeyLabels() {
        val bar = part(IoKinds.LedBar, mapOf("inputtype" to "row", "matrixcols" to "8"))
        assertEquals("Rows: 1; columns: 8; on: 4; off: 4; unknown: 0; errors: 0. Row 1, on columns: 1, 3, 6, 8.",
            text(bar, evaluated(bar, mapOf("row-0" to LogicVector.of(8, 165u)))))
        val pmod = part(IoKinds.MatrixKeypad, mapOf("mode" to "pmod_kypd"))
        val key = evaluated(pmod, events = listOf(IoInputEvent.KeypadKey(3, 2, true)))
        assertEquals("Pressed keys: E (row 4, column 3).", text(pmod, key))
        assertEquals("Teclas pressionadas: E (linha 4, coluna 3).", text(pmod, key, language = AppLanguage.PORTUGUESE))
        val raw = part(IoKinds.MatrixKeypad, mapOf("keyHashLabel" to "Custom"))
        assertEquals("Pressed keys: Custom (row 4, column 3).", text(raw, evaluated(raw, events = listOf(IoInputEvent.KeypadKey(3, 2, true)))))
        assertEquals("Pressed keys: none.", text(raw, evaluated(raw, events = listOf(IoInputEvent.Cancel), state = key)))
    }

    @Test fun longKeyLabelsAndLargeChordsStayBounded() {
        val component = part(IoKinds.MatrixKeypad, mapOf("keyALabel" to "\u0001".repeat(500), "keyBLabel" to "A".repeat(500)))
        val state = IoKeypadState((0..15).toSet())
        for (language in AppLanguage.entries) {
            val description = text(component, state, language = language)
            assertTrue(description.length < 512, description)
            assertFalse(description.any { it.code < 32 })
            assertTrue(description.contains("…"))
            assertTrue(description.contains("8"))
        }
    }

    @Test fun wrapperUsesItsSuppliedSnapshotAndOtherLibrariesKeepTheirSignal() {
        val matrix = part(IoKinds.DotMatrix, mapOf("matrixrows" to "1", "matrixcols" to "1"))
        val graph = CircuitAccessibilityGraph(CircuitDocument(components = listOf(matrix)), ComponentCatalog.registry)
        val component = requireNotNull(graph.component("part"))
        val state = SimulationState(ticks = 5, propagationTime = 999, elapsedTimeMillis = 999,
            componentStates = mapOf("part" to IoMatrixState(1, 1, listOf(LogicValue.LOW), listOf(5))))
        assertTrue(accessibleComponentValue(component, state).resolve().contains("on: 1; off: 0"))
        assertTrue(accessibleComponentValue(component, state.copy(ticks = 6)).resolve().contains("on: 0; off: 1"))
        val input = part(ComponentKind.INPUT)
        assertNull(accessibleIoStateDescription(input, EmptyComponentState, 0))
        val inputComponent = CircuitAccessibilityGraph(CircuitDocument(components = listOf(input)), ComponentCatalog.registry).component("part")!!
        assertEquals("4 bits, hexadecimal A.", accessibleComponentValue(inputComponent, SimulationState(vectorValues = mapOf("part" to LogicVector.of(4, 10u)))).resolve())
        assertEquals("I/O state is unavailable.", text(part(IoKinds.Button), null))
        assertNotNull(accessibleIoStateDescription(part(IoKinds.Tty), IoTerminalState(), 0))
    }
}
