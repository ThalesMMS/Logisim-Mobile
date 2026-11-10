package dev.logisim.mobile.core

/** A document fixture for the same input, display, and text flows on Android and iOS. */
object IoDemoCircuits {
    fun controls(): CircuitProject {
        val parts = mutableListOf<CircuitComponent>()
        val wires = mutableListOf<WireSegment>()
        fun add(id: String, kind: ComponentKind, x: Int, y: Int, label: String = "", attributes: Map<String, String> = emptyMap()): CircuitComponent =
            CircuitComponent(id, kind, GridPoint(x, y), label, attributes).also(parts::add)
        fun connect(vararg points: GridPoint) {
            points.toList().zipWithNext().filter { it.first != it.second }.forEach { (from, to) -> wires += WireSegment(from, to) }
        }
        fun input(id: String, x: Int, y: Int, width: Int = 1, initial: String = "0", label: String = id) =
            add(id, ComponentKind.INPUT, x, y, label, mapOf("width" to "$width", "initial" to initial))

        add("button", IoKinds.Button, 140, 80, "Button")
        add("led", IoKinds.Led, 240, 80, "LED")
        connect(GridPoint(140, 80), GridPoint(240, 80))

        add("dip", IoKinds.DipSwitch, 100, 180, "DIP", mapOf("resetbehavior" to "last"))
        add("joystick", IoKinds.Joystick, 360, 200, "Joystick")
        add("hex-x", IoKinds.HexDigit, 440, 200, "X", mapOf("decimalPoint" to "false"))
        add("hex-y", IoKinds.HexDigit, 490, 210, "Y", mapOf("decimalPoint" to "false"))
        connect(GridPoint(360, 200), GridPoint(440, 200))
        connect(GridPoint(360, 210), GridPoint(490, 210))

        add("rgb", IoKinds.RgbLed, 600, 80, "RGB")
        input("red", 520, 80, initial = "1", label = "R")
        input("green", 550, 50, initial = "1", label = "G")
        input("blue", 550, 110, label = "B")
        connect(GridPoint(520, 80), GridPoint(600, 80))
        connect(GridPoint(550, 50), GridPoint(610, 50), GridPoint(610, 70))
        connect(GridPoint(550, 110), GridPoint(610, 110), GridPoint(610, 90))

        input("bar-value", 450, 180, 8, "165", "Bar")
        add("bar", IoKinds.LedBar, 540, 180, "LED bar", mapOf("inputtype" to "row"))
        connect(GridPoint(450, 180), GridPoint(540, 180))

        input("segments", 510, 330, 8, "63", "Segments")
        add("segments-display", IoKinds.SevenSegment, 600, 300, "7 segment", mapOf("inputmode" to "bus"))
        connect(GridPoint(510, 330), GridPoint(590, 330))

        add("keyboard", IoKinds.Keyboard, 100, 400, "Keyboard")
        add("tty", IoKinds.Tty, 330, 500, "TTY", mapOf("rows" to "6", "cols" to "24"))
        connect(GridPoint(240, 410), GridPoint(280, 410), GridPoint(280, 490), GridPoint(330, 490))
        add("clock", ComponentKind.CLOCK, 60, 460, "Clock")
        connect(GridPoint(60, 460), GridPoint(60, 400), GridPoint(100, 400))
        connect(GridPoint(60, 460), GridPoint(60, 500), GridPoint(330, 500))
        input("read-enable", 80, 420, initial = "1", label = "Read")
        connect(GridPoint(80, 420), GridPoint(110, 420), GridPoint(110, 410))
        input("keyboard-clear", 120, 450, label = "Clear keyboard")
        connect(GridPoint(120, 450), GridPoint(120, 410))
        input("write-enable", 400, 550, initial = "1", label = "Write")
        connect(GridPoint(400, 550), GridPoint(340, 550), GridPoint(340, 510))
        input("tty-clear", 300, 540, label = "Clear TTY")
        connect(GridPoint(300, 540), GridPoint(350, 540), GridPoint(350, 510))

        add("matrix", IoKinds.DotMatrix, 600, 450, "Dot matrix", mapOf("inputtype" to "row"))
        listOf(14, 17, 17, 31, 17, 17, 17).forEachIndexed { row, pattern ->
            input("matrix-row-$row", 550, 450 + row * 10, 5, pattern.toString(), "Row $row")
            connect(GridPoint(550, 450 + row * 10), GridPoint(600, 450 + row * 10))
        }

        add("keypad", IoKinds.MatrixKeypad, 160, 650, "Keypad", mapOf("mode" to "pmod_kypd"))
        repeat(4) { row ->
            val x = 140 + row * 10
            input("scan-row-$row", x, 730, initial = if (row == 0) "0" else "1", label = "R$row")
            connect(GridPoint(x, 730), GridPoint(x, 680))
            val y = 630 + row * 10
            add("keypad-column-$row", ComponentKind.OUTPUT, 250, y, "C$row")
            connect(GridPoint(190, y), GridPoint(250, y))
        }
        return CircuitProject(listOf(CircuitDocument("I/O controls", parts, wires)))
    }
}
