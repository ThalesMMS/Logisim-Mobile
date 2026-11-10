package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitNetworksTest {
    @Test
    fun crossingWithoutEndpointDoesNotJoinWires() {
        val left = pin("left", 0, 50)
        val top = pin("top", 50, 0)
        val right = pin("right", 100, 50)
        val bottom = pin("bottom", 50, 100)
        val networks = CircuitNetworks(CircuitDocument(components = listOf(left, top, right, bottom),
            wires = listOf(WireSegment(left.position, right.position),
                WireSegment(top.position, bottom.position))))
        val values = networks.resolve(mapOf(left.position to LogicVector.high(1),
            top.position to LogicVector.low(1)))
        assertEquals(LogicValue.HIGH, values.getValue(right.position).bit(0))
        assertEquals(LogicValue.LOW, values.getValue(bottom.position).bit(0))
    }

    @Test
    fun endpointOnAnotherWireCreatesJunction() {
        val left = pin("left", 0, 50)
        val right = pin("right", 100, 50)
        val bottom = pin("bottom", 50, 100)
        val networks = CircuitNetworks(CircuitDocument(components = listOf(left, right, bottom),
            wires = listOf(WireSegment(left.position, right.position),
                WireSegment(GridPoint(50, 50), bottom.position))))
        val values = networks.resolve(mapOf(left.position to LogicVector.high(1)))
        assertEquals(LogicValue.HIGH, values.getValue(bottom.position).bit(0))
        assertTrue(networks.isConnected(bottom.position))
    }

    @Test
    fun terminalAtCrossingCreatesJunction() {
        val left = pin("left", 0, 50)
        val junction = pin("junction", 50, 50)
        val bottom = pin("bottom", 50, 100)
        val networks = CircuitNetworks(CircuitDocument(components = listOf(left, junction, bottom),
            wires = listOf(WireSegment(left.position, GridPoint(100, 50)),
                WireSegment(GridPoint(50, 0), bottom.position))))
        val values = networks.resolve(mapOf(left.position to LogicVector.high(1)))
        assertEquals(LogicValue.HIGH, values.getValue(bottom.position).bit(0))
    }

    @Test
    fun tunnelsConnectByLabelIgnoringAsciiPadding() {
        val first = CircuitComponent("t1", ComponentKind.TUNNEL, GridPoint(0, 0), "BUS",
            attributes = mapOf("width" to "8"))
        val second = first.copy(id = "t2", position = GridPoint(200, 0))
        val padded = first.copy(id = "padded", position = GridPoint(300, 0), label = "\t BUS \r")
        val blank = first.copy(id = "blank", position = GridPoint(400, 0), label = "")
        val isolated = listOf(
            blank.copy(id = "empty", position = GridPoint(500, 0)),
            blank.copy(id = "spaces", position = GridPoint(600, 0), label = " \t "),
            first.copy(id = "case", position = GridPoint(700, 0), label = "bus"),
            first.copy(id = "internal", position = GridPoint(800, 0), label = "B US"),
            first.copy(id = "nonAscii", position = GridPoint(900, 0), label = "\u00a0BUS\u00a0"),
        )
        val networks = CircuitNetworks(CircuitDocument(components = listOf(first, second, padded, blank) + isolated))
        val values = networks.resolve(mapOf(first.position to LogicVector.of(8, 0xa5uL),
            blank.position to LogicVector.of(8, 0xffuL)))
        assertEquals(0xa5uL, values.getValue(second.position).toULongOrNull())
        assertEquals(0xa5uL, values.getValue(padded.position).toULongOrNull())
        assertTrue(networks.isConnected(padded.position))
        for (tunnel in isolated) {
            assertEquals(LogicVector.unknown(8), values.getValue(tunnel.position), tunnel.id)
            assertFalse(networks.isConnected(tunnel.position), tunnel.id)
        }
    }

    @Test
    fun splitBusMapsBitsBothWays() {
        val splitter = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(100, 100),
            attributes = mapOf("incoming" to "8", "fanout" to "2"))
        val networks = CircuitNetworks(CircuitDocument(components = listOf(splitter)))
        val split = networks.resolve(mapOf(GridPoint(100, 100) to LogicVector.of(8, 0xa5uL)))
        assertEquals(5uL, split.getValue(GridPoint(120, 80)).toULongOrNull())
        assertEquals(10uL, split.getValue(GridPoint(120, 90)).toULongOrNull())
        val combined = networks.resolve(mapOf(GridPoint(120, 80) to LogicVector.of(4, 3uL),
            GridPoint(120, 90) to LogicVector.of(4, 12uL)))
        assertEquals(0xc3uL, combined.getValue(GridPoint(100, 100)).toULongOrNull())
    }

    @Test
    fun splitterMapsNonconsecutiveBitsAndLeavesDisconnectedBitsFloating() {
        val splitter = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(100, 100),
            attributes = mapOf("incoming" to "4", "fanout" to "2", "appear" to "right",
                "bit0" to "1", "bit1" to "none", "bit2" to "0", "bit3" to "1"))
        val networks = CircuitNetworks(CircuitDocument(components = listOf(splitter)))
        val values = networks.resolve(mapOf(GridPoint(120, 110) to LogicVector.high(1),
            GridPoint(120, 120) to LogicVector.of(2, 2uL)))
        val input = values.getValue(GridPoint(100, 100))
        assertEquals(listOf(LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.HIGH, LogicValue.HIGH),
            (0..3).map(input::bit))
    }

    @Test
    fun conflictsRemainLocalToEachBitAcrossSplitter() {
        val splitter = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(100, 100),
            attributes = mapOf("incoming" to "4", "fanout" to "4"))
        val networks = CircuitNetworks(CircuitDocument(components = listOf(splitter)))
        val values = networks.resolve(mapOf(GridPoint(100, 100) to LogicVector.of(4, 5uL),
            GridPoint(120, 60) to LogicVector.low(1)))
        assertEquals(listOf(LogicValue.ERROR, LogicValue.LOW, LogicValue.HIGH, LogicValue.LOW),
            (0..3).map(values.getValue(GridPoint(100, 100))::bit))
    }

    @Test
    fun conflictingWidthsAreDiagnosedAndDoNotSilentlyTruncate() {
        val wide = pin("wide", 0, 0, 8)
        val narrow = pin("narrow", 100, 0, 4)
        val networks = CircuitNetworks(CircuitDocument(components = listOf(wide, narrow),
            wires = listOf(WireSegment(wide.position, narrow.position))))
        assertTrue(networks.diagnostics.single().contains("4 / 8"))
        val values = networks.resolve(mapOf(wide.position to LogicVector.of(8, 0xafuL)))
        assertEquals(List(8) { LogicValue.ERROR }, (0..7).map(values.getValue(wide.position)::bit))
    }

    @Test
    fun unconnectedTerminalIsFloatingAndUnused() {
        val input = pin("in", 0, 0, 8)
        val networks = CircuitNetworks(CircuitDocument(components = listOf(input)))
        assertFalse(networks.isConnected(input.position))
        assertEquals(8, networks.widthAt(input.position))
        assertEquals(List(8) { LogicValue.UNKNOWN },
            (0..7).map(networks.resolve(emptyMap()).getValue(input.position)::bit))
    }

    private fun pin(id: String, x: Int, y: Int, width: Int = 1) =
        CircuitComponent(id, ComponentKind.INPUT, GridPoint(x, y), attributes = mapOf("width" to width.toString()))
}
