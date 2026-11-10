package dev.logisim.mobile.fixtures

import dev.logisim.mobile.core.*
import java.nio.file.Files
import java.nio.file.Path

/** Builds the file used by the Android and iOS I/O user-flow scenarios. */
object IoShowcaseFixture {
    fun project(): CircuitProject {
        val parts = mutableListOf<CircuitComponent>()
        val wires = mutableListOf<WireSegment>()
        fun add(label: String, kind: ComponentKind, x: Int, y: Int, attributes: Map<String, String> = emptyMap()) =
            CircuitComponent(label.lowercase(), kind, GridPoint(x, y), label,
                mapOf("labelvisible" to "true") + attributes).also(parts::add)
        fun input(label: String, x: Int, y: Int, width: Int = 1, initial: Int = 0, visible: Boolean = true) =
            add(label, ComponentKind.INPUT, x, y, mapOf("type" to "input", "facing" to "east", "appearance" to "classic",
                "behavior" to "simple", "width" to "$width", "initial" to "$initial", "labelvisible" to "$visible"))
        fun output(label: String, x: Int, y: Int, width: Int = 1) =
            add(label, ComponentKind.OUTPUT, x, y, mapOf("type" to "output", "facing" to "west", "appearance" to "classic", "radix" to "2", "width" to "$width"))
        fun port(component: CircuitComponent, name: String) = component.layout().ports.single { it.name == name }.position
        fun connect(vararg points: GridPoint) {
            points.toList().zipWithNext().forEach { (from, to) ->
                require(from.x == to.x || from.y == to.y) { "Non-orthogonal route: $from to $to" }
                if (from != to) wires += WireSegment(from, to)
            }
        }

        val button = add("IO_BUTTON", IoKinds.Button, 180, 120, mapOf("press" to "active"))
        val led = add("IO_LED_BUTTON", IoKinds.Led, 320, 120, mapOf("active" to "true"))
        val buttonOut = output("OUT_BUTTON", 440, 120)
        connect(port(button, "out"), port(led, "in"), port(buttonOut, "in"))

        val staticLed = add("IO_LED", IoKinds.Led, 580, 120, mapOf("active" to "true"))
        val ledLevel = input("IN_LED_LEVEL", 500, 200)
        val ledOut = output("OUT_LED_LEVEL", 700, 120)
        connect(port(ledLevel, "out"), GridPoint(520, 200), GridPoint(520, 120), port(staticLed, "in"), port(ledOut, "in"))

        val dip = add("IO_DIP", IoKinds.DipSwitch, 120, 280,
            mapOf("facing" to "east", "number" to "8", "resetbehavior" to "last", "positions" to "0"))
        val dip0 = output("OUT_DIP0", 300, 290)
        val dip1 = output("OUT_DIP1", 300, 340)
        connect(port(dip, "switch-0"), port(dip0, "in"))
        connect(port(dip, "switch-1"), GridPoint(220, 300), GridPoint(220, 340), port(dip1, "in"))

        val joystick = add("IO_JOYSTICK", IoKinds.Joystick, 440, 320, mapOf("bits" to "4"))
        val hex = add("IO_HEX", IoKinds.HexDigit, 580, 320,
            mapOf("decimalPoint" to "false", "labelloc" to "north"))
        val joyX = output("OUT_JOY_X", 680, 320, 4)
        val joyY = output("OUT_JOY_Y", 680, 380, 4)
        connect(port(joystick, "x"), port(hex, "data"), port(joyX, "in"))
        connect(port(joystick, "y"), GridPoint(520, 330), GridPoint(520, 380), port(joyY, "in"))

        val rgb = add("IO_RGB", IoKinds.RgbLed, 900, 120, mapOf("active" to "true"))
        val red = input("IN_RGB_R", 780, 120, initial = 1)
        val green = input("IN_RGB_G", 800, 60, initial = 1)
        val blue = input("IN_RGB_B", 800, 180)
        val outRed = output("OUT_RGB_R", 840, 90)
        val outGreen = output("OUT_RGB_G", 1020, 60)
        val outBlue = output("OUT_RGB_B", 1020, 180)
        connect(port(red, "out"), GridPoint(840, 120), port(rgb, "red"))
        connect(GridPoint(840, 120), port(outRed, "in"))
        connect(port(green, "out"), GridPoint(910, 60), port(rgb, "green"))
        connect(GridPoint(910, 60), port(outGreen, "in"))
        connect(port(blue, "out"), GridPoint(910, 180), port(rgb, "blue"))
        connect(GridPoint(910, 180), port(outBlue, "in"))

        val bar = add("IO_LEDBAR", IoKinds.LedBar, 900, 270,
            mapOf("inputtype" to "row", "matrixcols" to "8", "matrixrows" to "1", "height" to "3"))
        val barValue = input("IN_BAR_VALUE", 760, 270, 8, 165)
        val barOut = output("OUT_BAR_VALUE", 840, 230, 8)
        connect(port(barValue, "out"), GridPoint(840, 270), port(bar, "row-0"))
        connect(GridPoint(840, 270), port(barOut, "in"))

        val segments = add("IO_SEVEN_SEGMENT", IoKinds.SevenSegment, 900, 390,
            mapOf("inputmode" to "bus", "decimalPoint" to "true", "busside" to "west", "labelloc" to "north"))
        val segmentValue = input("IN_SEGMENTS", 760, 420, 8, 63)
        val segmentOut = output("OUT_SEGMENTS", 840, 370, 8)
        connect(port(segmentValue, "out"), GridPoint(840, 420), port(segments, "segments"))
        connect(GridPoint(840, 420), port(segmentOut, "in"))

        val keyboard = add("IO_KEYBOARD", IoKinds.Keyboard, 180, 600, mapOf("buflen" to "16", "trigger" to "rising"))
        val tty = add("IO_TTY", IoKinds.Tty, 540, 700, mapOf("rows" to "3", "cols" to "12", "trigger" to "rising"))
        val clock = add("IO_CLOCK", ComponentKind.CLOCK, 100, 700, mapOf("facing" to "east", "highDuration" to "1", "lowDuration" to "1", "phaseOffset" to "0"))
        val clockOut = output("OUT_CLOCK", 700, 700)
        val keyboardData = output("OUT_KEYBOARD_DATA", 500, 610, 7)
        val keyboardAvailable = output("OUT_KEYBOARD_AVAILABLE", 500, 660)
        connect(port(clock, "out"), GridPoint(100, 600), port(keyboard, "clock"))
        connect(port(clock, "out"), port(tty, "clock"), port(clockOut, "in"))
        connect(port(keyboard, "data"), GridPoint(420, 610), GridPoint(420, 690), port(tty, "data"))
        connect(GridPoint(420, 610), port(keyboardData, "in"))
        connect(port(keyboard, "available"), GridPoint(310, 660), port(keyboardAvailable, "in"))
        val read = input("IN_READ_ENABLE", 190, 650, initial = 1)
        val keyboardClear = input("IN_KEYBOARD_CLEAR", 260, 650)
        connect(port(read, "out"), port(keyboard, "readEnable"))
        connect(port(keyboardClear, "out"), GridPoint(200, 650), port(keyboard, "clear"))
        val write = input("IN_WRITE_ENABLE", 640, 760, initial = 1)
        val ttyClear = input("IN_TTY_CLEAR", 700, 820)
        connect(port(write, "out"), GridPoint(550, 760), port(tty, "writeEnable"))
        connect(port(ttyClear, "out"), GridPoint(560, 820), port(tty, "clear"))

        val matrix = add("IO_DOT_MATRIX", IoKinds.DotMatrix, 940, 640,
            mapOf("inputtype" to "row", "matrixrows" to "7", "matrixcols" to "5", "persist" to "0", "dotshape" to "square"))
        listOf(14, 17, 17, 31, 17, 17, 17).forEachIndexed { row, value ->
            val source = input("IN_MATRIX_ROW_$row", 820, 640 + row * 10, 5, value, visible = row == 0)
            if (row == 0) {
                val out = output("OUT_MATRIX_ROW0", 880, 580, 5)
                connect(port(source, "out"), GridPoint(880, 640), port(matrix, "row-$row"))
                connect(GridPoint(880, 640), port(out, "in"))
            } else connect(port(source, "out"), port(matrix, "row-$row"))
        }

        val keypad = add("IO_KEYPAD", IoKinds.MatrixKeypad, 300, 1000,
            mapOf("mode" to "pmod_kypd", "size" to "large"))
        repeat(4) { row ->
            val terminal = port(keypad, "row-$row")
            val scan = input("IN_SCAN_ROW_$row", terminal.x, 1150, initial = if (row == 0) 0 else 1)
            connect(port(scan, "out"), terminal)
            val out = output("OUT_KEYPAD_C$row", 520, port(keypad, "column-$row").y)
            connect(port(keypad, "column-$row"), port(out, "in"))
        }
        return CircuitProject(listOf(CircuitDocument("IO_Showcase", parts, wires)))
    }

    fun targets(project: CircuitProject): String {
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun point(value: GridPoint) = "[${value.x},${value.y}]"
        fun attributes(value: Map<String, String>) = value.toSortedMap().entries.joinToString(",", "{", "}") { quote(it.key) + ":" + quote(it.value) }
        val targets = project.mainCircuit.components.sortedBy { it.label }.joinToString(",\n") { component ->
            val layout = component.layout()
            val bounds = layout.bounds
            val ports = layout.ports.joinToString(",") { port ->
                "{\"name\":${quote(port.name)},\"at\":${point(port.position)},\"direction\":${quote(port.direction.name)},\"width\":${port.width}}"
            }
            "    {\"label\":${quote(component.label)},\"kind\":${quote(component.kind.name)},\"anchor\":${point(component.position)},\"bounds\":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}],\"attributes\":${attributes(component.attributes)},\"ports\":[$ports]}"
        }
        return """{
  "formatVersion":1,
  "fixture":"examples/io-showcase.circ",
  "circuit":"IO_Showcase",
  "lookup":"label",
  "scope":"fixture targets only; native UI execution is required separately",
  "pointerPoints":[
    {"name":"button-center","label":"IO_BUTTON","at":[170,120]},
    {"name":"dip-switch0","label":"IO_DIP","at":[100,290]},
    {"name":"dip-switch1","label":"IO_DIP","at":[100,300]},
    {"name":"joystick-center","label":"IO_JOYSTICK","at":[425,325]},
    {"name":"joystick-top-right","label":"IO_JOYSTICK","at":[439,311]},
    {"name":"keypad-r0-c0","label":"IO_KEYPAD","at":[258,958]},
    {"name":"keypad-r0-c2","label":"IO_KEYPAD","at":[314,958]},
    {"name":"keypad-r3-c3","label":"IO_KEYPAD","at":[342,1042]}
  ],
  "targets":[
$targets
  ]
}
"""
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass an output directory" }
        val root = Path.of(args[0])
        Files.createDirectories(root)
        val project = project()
        Files.writeString(root.resolve("io-showcase.circ"), CircCodec.writeProject(project))
        Files.writeString(root.resolve("io-showcase.targets.json"), targets(project))
        println("Generated ${project.mainCircuit.components.size} components and ${project.mainCircuit.wires.size} wires")
    }
}
