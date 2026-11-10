package dev.logisim.mobile.core

/** Built-in examples. Their circuit names and labels are ones Evolution accepts, so it reopens them unchanged (#321). */
object DemoCircuits {
    fun andGate(): CircuitDocument = CircuitDocument(
        name = "Minha_primeira_porta_AND",
        components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(100, 100), "A"),
            CircuitComponent("b", ComponentKind.INPUT, GridPoint(100, 200), "B"),
            CircuitComponent("and", ComponentKind.AND, GridPoint(300, 150), "AND"),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(420, 150), "Y"),
        ),
        wires = listOf(
            WireSegment(GridPoint(100, 100), GridPoint(200, 100)),
            WireSegment(GridPoint(200, 100), GridPoint(200, 130)),
            WireSegment(GridPoint(200, 130), GridPoint(250, 130)),
            WireSegment(GridPoint(100, 200), GridPoint(220, 200)),
            WireSegment(GridPoint(220, 200), GridPoint(220, 170)),
            WireSegment(GridPoint(220, 170), GridPoint(250, 170)),
            WireSegment(GridPoint(300, 150), GridPoint(420, 150)),
        ),
    )

    fun flipFlop(): CircuitDocument = CircuitDocument(
        name = "Memoria_flip_flop_D",
        components = listOf(
            CircuitComponent("data", ComponentKind.INPUT, GridPoint(100, 200), "D"),
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 120), "Clock"),
            CircuitComponent("dff", ComponentKind.D_FLIP_FLOP, GridPoint(300, 150), "DFF"),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(420, 150), "Q"),
        ),
        wires = listOf(
            WireSegment(GridPoint(100, 200), GridPoint(220, 200)),
            WireSegment(GridPoint(220, 200), GridPoint(220, 170)),
            WireSegment(GridPoint(220, 170), GridPoint(260, 170)),
            WireSegment(GridPoint(100, 120), GridPoint(240, 120)),
            WireSegment(GridPoint(240, 120), GridPoint(240, 150)),
            WireSegment(GridPoint(240, 150), GridPoint(260, 150)),
            WireSegment(GridPoint(300, 150), GridPoint(420, 150)),
        ),
    )
}
