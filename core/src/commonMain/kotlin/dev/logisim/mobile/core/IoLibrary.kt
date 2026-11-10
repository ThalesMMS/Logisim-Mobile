package dev.logisim.mobile.core

internal const val ORIGINAL_HEX_DIGIT_ATTRIBUTE = "logisim-mobile-original-hex-digit"

val CircuitComponent.isOriginalHexDigit: Boolean
    get() = attributes[ORIGINAL_HEX_DIGIT_ATTRIBUTE] == "true"

object IoKinds {
    val Button = ComponentKind.BUTTON
    val DipSwitch = ComponentKind("DIP_SWITCH")
    val Joystick = ComponentKind("JOYSTICK")
    val Keyboard = ComponentKind("KEYBOARD")
    val Led = ComponentKind.LED
    val LedBar = ComponentKind("LED_BAR")
    val RgbLed = ComponentKind("RGB_LED")
    val SevenSegment = ComponentKind.SEVEN_SEGMENT_DISPLAY
    val HexDigit = ComponentKind.HEX_DISPLAY
    val DotMatrix = ComponentKind("DOT_MATRIX")
    val Tty = ComponentKind("TTY")
    val MatrixKeypad = ComponentKind("MATRIX_KEYPAD")
}

object IoAttributes {
    val Facing = AttributeKey.direction()
    val LedFacing = AttributeKey.direction(default = Direction.WEST)
    val DipFacing = AttributeKey.direction(default = Direction.NORTH)
    val Press = AttributeKey.choice("press", "active", setOf("active", "passive"))
    val Switches = AttributeKey.int("number", 8, 1..32)
    val Reset = AttributeKey.choice("resetbehavior", "open", setOf("open", "closed", "last"))
    val Positions = AttributeKey("positions", 0uL,
        { it.removePrefix("0x").removePrefix("0X").toULongOrNull(16) }, { it.toString(16) })
    val JoystickBits = AttributeKey.int("bits", 4, 2..5)
    val BufferLength = AttributeKey.int("buflen", 32, 1..256)
    val Trigger = AttributeKey.choice("trigger", "rising", setOf("rising", "falling"))
    val Active = AttributeKey.boolean("active", true)
    val DecimalPoint = AttributeKey.boolean("decimalPoint", true)
    val SegmentInput = AttributeKey.choice("inputmode", "separate", setOf("separate", "bus"))
    val BusSide = AttributeKey.direction("busside", Direction.WEST)
    val MatrixColumns = AttributeKey.int("matrixcols", 5, 1..64)
    val MatrixRows = AttributeKey.int("matrixrows", 7, 1..64)
    val BarColumns = AttributeKey.int("matrixcols", 8, 1..64)
    val BarRows = AttributeKey.int("matrixrows", 1, 1..1)
    val BarHeight = AttributeKey.int("height", 3, 1..8)
    val MatrixInput = AttributeKey.choice("inputtype", "column", setOf("column", "row", "select"))
    val BarInput = AttributeKey.choice("inputtype", "column", setOf("column", "row"))
    val SelectLocation = AttributeKey.choice("selloc", "bl", setOf("bl", "tr"))
    val Persistence = AttributeKey.long("persist", 0, 0..Int.MAX_VALUE.toLong())
    val DotShape = AttributeKey.choice("dotshape", "square", setOf("square", "circle", "clusterSegment"))
    val BarShape = AttributeKey.choice("dotshape", "clusterSegment", setOf("clusterSegment"))
    val TtyRows = AttributeKey.int("rows", 8, 1..48)
    val TtyColumns = AttributeKey.int("cols", 32, 1..120)
    val KeypadMode = AttributeKey.choice("mode", "raw", setOf("raw", "pmod_kypd"))
    val KeypadSize = AttributeKey.choice("size", "normal", setOf("normal", "large"))
    val KeyA = AttributeKey.text("keyALabel", "A")
    val KeyB = AttributeKey.text("keyBLabel", "B")
    val KeyC = AttributeKey.text("keyCLabel", "C")
    val KeyD = AttributeKey.text("keyDLabel", "D")
    val KeyStar = AttributeKey.text("keyStarLabel", "*")
    val KeyHash = AttributeKey.text("keyHashLabel", "#")
    val Label = AttributeKey.text("label")
    val LabelLocation = AttributeKey.choice("labelloc", "east", setOf("east", "west", "north", "south", "center"))
    val LabelFont = AttributeKey.text("labelfont", "SansSerif bold 16")
    val LabelVisible = AttributeKey.boolean("labelvisible", true)
    val LabelColor = colorKey("labelcolor", 0xff000000u)
    val OnColor = colorKey("color", 0xfff00000u)
    val MatrixOnColor = colorKey("color", 0xff00ff00u)
    val OffColor = colorKey("offcolor", 0xffdcdcdcu)
    val LedOffColor = colorKey("offcolor", 0xff404040u)
    val MatrixOffColor = colorKey("offcolor", 0xff808080u)
    val Background = colorKey("bg", 0x00ffffffu)
    val ButtonColor = colorKey("color", 0xffffffffu)
    val JoystickColor = colorKey("color", 0xffff0000u)
    val TtyColor = colorKey("color", 0xff000000u)
    val TtyBackground = colorKey("bg", 0x40000000u)

    private fun colorKey(name: String, default: UInt) = AttributeKey(name, default, ::parseColor, ::formatColor)

    fun parseColor(raw: String): UInt? {
        val value = raw.removePrefix("#")
        return when (value.length) {
            6 -> value.toUIntOrNull(16)?.let { it or 0xff000000u }
            8 -> value.toUIntOrNull(16)?.let { (it shr 8) or ((it and 0xffu) shl 24) }
            else -> null
        }
    }

    private fun formatColor(argb: UInt): String = if (argb shr 24 == 0xffu)
        "#" + (argb and 0xffffffu).toString(16).padStart(6, '0')
    else "#" + ((argb shl 8) or (argb shr 24)).toString(16).padStart(8, '0')
}

object IoLibrary {
    val definitions: List<ComponentDefinition> = listOf(
        definition(IoKinds.Button, "Button", listOf(IoAttributes.Facing, IoAttributes.Press, IoAttributes.ButtonColor)),
        definition(IoKinds.DipSwitch, "DipSwitch", listOf(IoAttributes.DipFacing, IoAttributes.Switches, IoAttributes.Reset, IoAttributes.Positions)),
        definition(IoKinds.Joystick, "Joystick", listOf(IoAttributes.Facing, IoAttributes.JoystickBits, IoAttributes.JoystickColor, IoAttributes.Background)),
        definition(IoKinds.Keyboard, "Keyboard", listOf(IoAttributes.BufferLength, IoAttributes.Trigger), false),
        definition(IoKinds.Led, "LED", listOf(IoAttributes.LedFacing, IoAttributes.Active, IoAttributes.OnColor, IoAttributes.LedOffColor)),
        definition(IoKinds.LedBar, "LedBar", listOf(IoAttributes.Facing, IoAttributes.BarInput, IoAttributes.BarColumns,
            IoAttributes.BarRows, IoAttributes.BarHeight, IoAttributes.SelectLocation, IoAttributes.Persistence,
            IoAttributes.BarShape, IoAttributes.MatrixOnColor, IoAttributes.MatrixOffColor)),
        definition(IoKinds.RgbLed, "RGBLED", listOf(IoAttributes.LedFacing, IoAttributes.Active)),
        definition(IoKinds.SevenSegment, "7-Segment Display", listOf(IoAttributes.DecimalPoint, IoAttributes.SegmentInput,
            IoAttributes.BusSide, IoAttributes.Active, IoAttributes.OnColor, IoAttributes.OffColor, IoAttributes.Background), false),
        definition(IoKinds.HexDigit, "Hex Digit Display", listOf(IoAttributes.DecimalPoint,
            IoAttributes.OnColor, IoAttributes.OffColor, IoAttributes.Background), false),
        definition(IoKinds.DotMatrix, "DotMatrix", listOf(IoAttributes.MatrixInput, IoAttributes.MatrixColumns,
            IoAttributes.MatrixRows, IoAttributes.SelectLocation, IoAttributes.Persistence, IoAttributes.DotShape,
            IoAttributes.MatrixOnColor, IoAttributes.MatrixOffColor), false),
        definition(IoKinds.Tty, "TTY", listOf(IoAttributes.TtyRows, IoAttributes.TtyColumns, IoAttributes.Trigger,
            IoAttributes.TtyColor, IoAttributes.TtyBackground), false),
        definition(IoKinds.MatrixKeypad, "MatrixKeypad", listOf(IoAttributes.KeypadMode, IoAttributes.KeypadSize,
            IoAttributes.KeyA, IoAttributes.KeyB, IoAttributes.KeyC, IoAttributes.KeyD, IoAttributes.KeyStar, IoAttributes.KeyHash), false),
    )

    fun definition(kind: ComponentKind): ComponentDefinition? = definitions.firstOrNull { it.kind == kind }

    private fun definition(kind: ComponentKind, fileName: String, keys: List<AttributeKey<*>>, rotates: Boolean = true): ComponentDefinition {
        val labelLocation = when (kind) {
            IoKinds.Button -> "west"
            IoKinds.LedBar, IoKinds.DotMatrix, IoKinds.MatrixKeypad -> "north"
            else -> "east"
        }
        val schema = AttributeSchema(keys + listOf(IoAttributes.Label,
            AttributeKey.choice("labelloc", labelLocation, setOf("east", "west", "north", "south", "center")),
            IoAttributes.LabelFont, AttributeKey.boolean("labelvisible", kind !in setOf(IoKinds.HexDigit, IoKinds.SevenSegment)), IoAttributes.LabelColor))
        return ComponentDefinition(kind, fileName, "#I/O", schema.defaults, schema,
            { component, attributes -> IoGeometry.layout(component, attributes) }, IoBehavior(kind), rotates,
            defaultsForVersion = { version ->
                if (!CircFormatProfile.parse(version).originalLogisim) emptyMap() else when (kind) {
                    IoKinds.HexDigit -> mapOf(ORIGINAL_HEX_DIGIT_ATTRIBUTE to "true")
                    IoKinds.DotMatrix -> mapOf("offcolor" to "#404040")
                    IoKinds.Button, IoKinds.Led -> mapOf("labelloc" to "center")
                    else -> emptyMap()
                }
            })
    }

    /** DIP 'last' is the only I/O interaction that also changes a saved desktop attribute. */
    fun savedInteractionAttributes(component: CircuitComponent, state: ComponentState): Map<String, String> =
        if (component.kind == IoKinds.DipSwitch && component.attributes["resetbehavior"] == "last" && state is IoDipState)
            mapOf("positions" to state.positions.toString(16)) else emptyMap()

    fun isInteractive(kind: ComponentKind): Boolean = kind in setOf(IoKinds.Button, IoKinds.DipSwitch,
        IoKinds.Joystick, IoKinds.Keyboard, IoKinds.MatrixKeypad)

    private class IoBehavior(private val kind: ComponentKind) : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = when (kind) {
            IoKinds.Button -> IoButtonState()
            IoKinds.DipSwitch -> IoLogic.dipInitial(attributes[IoAttributes.Switches], attributes[IoAttributes.Reset], attributes[IoAttributes.Positions])
            IoKinds.Joystick -> IoJoystickState()
            IoKinds.Keyboard -> IoKeyboardState()
            IoKinds.Tty -> IoTerminalState(attributes[IoAttributes.TtyRows], attributes[IoAttributes.TtyColumns])
            IoKinds.MatrixKeypad -> IoKeypadState()
            IoKinds.DotMatrix -> IoMatrixState(attributes[IoAttributes.MatrixRows], attributes[IoAttributes.MatrixColumns])
            IoKinds.LedBar -> IoMatrixState(1, attributes[IoAttributes.BarColumns])
            IoKinds.Led -> IoLedState()
            else -> IoLampState()
        }

        override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
            if (kind == IoKinds.DipSwitch && attributes[IoAttributes.Reset] == "last" && state is IoDipState) state
            else initialState(component, attributes)

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val attributes = context.attributes
            val events = context.events.filterIsInstance<IoInputEvent>()
            val state = context.state.takeUnless { it === EmptyComponentState } ?: initialState(context.component, attributes)
            fun input(name: String, width: Int = 1) = context.inputs[name] ?: LogicVector.unknown(width)
            fun bit(name: String) = input(name).bit(0)
            return when (kind) {
                IoKinds.Button -> {
                    var pressed = (state as IoButtonState).pressed
                    for (event in events) when (event) {
                        is IoInputEvent.Press -> pressed = event.pressed
                        IoInputEvent.Cancel -> pressed = false
                        else -> Unit
                    }
                    val next = IoButtonState(pressed)
                    ComponentEvaluation(mapOf("out" to IoLogic.buttonOutput(next, attributes[IoAttributes.Press] == "active")), next)
                }
                IoKinds.DipSwitch -> {
                    val count = attributes[IoAttributes.Switches]
                    var next = IoLogic.resizeDip(state as IoDipState, count)
                    events.filterIsInstance<IoInputEvent.ToggleSwitch>().forEach { next = IoLogic.dipToggle(next, count, it.index) }
                    ComponentEvaluation(IoLogic.dipOutputs(next, count).mapIndexed { index, value -> "switch-$index" to value }.toMap(), next)
                }
                IoKinds.Joystick -> {
                    var next = state as IoJoystickState
                    for (event in events) when (event) {
                        is IoInputEvent.MoveJoystick -> next = IoJoystickState(event.x.coerceIn(-14, 14), event.y.coerceIn(-14, 14))
                        is IoInputEvent.Press -> if (!event.pressed) next = IoJoystickState()
                        IoInputEvent.Cancel -> next = IoJoystickState()
                        else -> Unit
                    }
                    val (x, y) = IoLogic.joystickOutput(next, attributes[IoAttributes.JoystickBits])
                    ComponentEvaluation(mapOf("x" to x, "y" to y), next)
                }
                IoKinds.Keyboard -> {
                    val edited = IoLogic.editKeyboard(state as IoKeyboardState, attributes[IoAttributes.BufferLength], events)
                    val next = IoLogic.stepKeyboard(edited, bit("clock"), bit("readEnable"), bit("clear"), attributes[IoAttributes.Trigger] == "falling")
                    val (data, available) = IoLogic.keyboardOutputs(next)
                    ComponentEvaluation(mapOf("data" to data, "available" to available), next,
                        outputDelays = mapOf("data" to 9L, "available" to 11L))
                }
                IoKinds.Tty -> {
                    val resized = IoLogic.resizeTerminal(state as IoTerminalState, attributes[IoAttributes.TtyRows], attributes[IoAttributes.TtyColumns])
                    val (next, character) = IoLogic.evaluateTerminal(resized, bit("clock"), bit("writeEnable"),
                        bit("clear"), input("data", 7), attributes[IoAttributes.Trigger] == "falling")
                    ComponentEvaluation(emptyMap(), next, terminalCharacter = character)
                }
                IoKinds.MatrixKeypad -> {
                    val next = IoLogic.applyKeypadEvents(state as IoKeypadState, events)
                    val values = IoLogic.keypadOutputs(next, (0..3).map { bit("row-$it") }, attributes[IoAttributes.KeypadMode] == "pmod_kypd")
                    ComponentEvaluation(values.mapIndexed { index, value -> "column-$index" to value }.toMap(), next)
                }
                IoKinds.Led -> ComponentEvaluation(emptyMap(), IoLedState(bit("in")))
                IoKinds.RgbLed -> ComponentEvaluation(emptyMap(), IoLampState(IoLogic.lampMask(listOf(bit("red"), bit("green"), bit("blue")))))
                IoKinds.HexDigit -> {
                    val segments = IoLogic.hexSegments(input("data", 4), bit("decimalPoint"),
                        originalLogisim = context.component.isOriginalHexDigit)
                    ComponentEvaluation(emptyMap(), IoLampState(segments))
                }
                IoKinds.SevenSegment -> {
                    val count = if (attributes[IoAttributes.DecimalPoint]) 8 else 7
                    val bits = if (attributes[IoAttributes.SegmentInput] == "bus") {
                        val value = input("segments", count)
                        (0 until count).map { value.bit(it) }
                    } else (0 until count).map { bit("segment-$it") }
                    ComponentEvaluation(emptyMap(), IoLampState(IoLogic.lampMask(bits)))
                }
                IoKinds.DotMatrix, IoKinds.LedBar -> {
                    val rows = if (kind == IoKinds.LedBar) 1 else attributes[IoAttributes.MatrixRows]
                    val columns = if (kind == IoKinds.LedBar) attributes[IoAttributes.BarColumns] else attributes[IoAttributes.MatrixColumns]
                    val mode = if (kind == IoKinds.LedBar) attributes[IoAttributes.BarInput] else attributes[IoAttributes.MatrixInput]
                    val before = (state as? IoMatrixState)?.takeIf { it.rows == rows && it.columns == columns } ?: IoMatrixState(rows, columns)
                    val inputs = when (mode) {
                        "row" -> (0 until rows).map { input("row-$it", columns) }
                        "select" -> listOf(input("columns", columns), input("rows", rows))
                        else -> (0 until columns).map { input("column-$it", rows) }
                    }
                    ComponentEvaluation(emptyMap(), IoLogic.matrixUpdate(before, mode, inputs, context.ticks, attributes[IoAttributes.Persistence]))
                }
                else -> error("Unknown I/O kind: ${kind.name}")
            }
        }
    }
}

data class IoLedState(val value: LogicValue = LogicValue.UNKNOWN) : ComponentState
