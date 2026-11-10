package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComponentGeometryTest {
    private fun component(kind: ComponentKind, vararg attrs: Pair<String, String>) =
        CircuitComponent("component", kind, GridPoint(100, 100), attributes = mapOf(*attrs))

    @Test fun preservesExistingGateAndFlipFlopPorts() {
        assertEquals(listOf(GridPoint(50, 80), GridPoint(50, 120)), component(ComponentKind.AND).inputPorts())
        assertEquals(listOf(GridPoint(60, 120), GridPoint(60, 100), GridPoint(90, 130), GridPoint(70, 130)), component(ComponentKind.D_FLIP_FLOP).inputPorts())
        assertEquals(listOf(GridPoint(100, 100), GridPoint(100, 120)), component(ComponentKind.D_FLIP_FLOP).outputPorts())
    }

    @Test fun gateFacingKeepsDesktopInputOrdering() {
        assertEquals(listOf(GridPoint(150, 80), GridPoint(150, 120)), component(ComponentKind.AND, "facing" to "west").inputPorts())
        assertEquals(listOf(GridPoint(80, 150), GridPoint(120, 150)), component(ComponentKind.AND, "facing" to "north").inputPorts())
        assertEquals(listOf(GridPoint(90, 70), GridPoint(110, 70)), component(ComponentKind.AND, "size" to "30", "facing" to "south").inputPorts())
    }

    @Test fun splitterUsesDesktopBranchOrderAndMixedWidths() {
        val split = component(ComponentKind.SPLITTER, "incoming" to "8", "bit1" to "0", "bit2" to "0", "bit3" to "0", "bit4" to "1", "bit5" to "1", "bit6" to "1", "bit7" to "1", "appear" to "right")
        assertEquals(listOf(GridPoint(100, 100), GridPoint(120, 110), GridPoint(120, 120)), split.splitterPorts())
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), split.splitterBitEnds())
        assertEquals(mapOf(GridPoint(100, 100) to 8, GridPoint(120, 110) to 4, GridPoint(120, 120) to 4), split.portWidths())
        val north = component(ComponentKind.SPLITTER, "incoming" to "3", "fanout" to "3", "facing" to "north", "appear" to "right")
        assertEquals(listOf(GridPoint(100, 100), GridPoint(130, 80), GridPoint(120, 80), GridPoint(110, 80)), north.splitterPorts())
    }

    @Test fun memoryAndArithmeticSeparateDataFromControls() {
        val register = component(ComponentKind.REGISTER)
        assertEquals(listOf(GridPoint(70, 100), GridPoint(80, 120), GridPoint(70, 110), GridPoint(90, 120)), register.inputPorts())
        assertEquals(listOf(8, 1, 1, 1), register.inputPorts().map { register.portWidth(it) })
        val adder = component(ComponentKind.ADDER)
        assertEquals(listOf(8, 8, 1), adder.inputPorts().map { adder.portWidth(it) })
        assertEquals(listOf(8, 1), adder.outputPorts().map { adder.portWidth(it) })
        val rom = component(ComponentKind.ROM, "addrWidth" to "4", "dataWidth" to "12")
        assertEquals(listOf(GridPoint(100, 110)), rom.inputPorts())
        assertEquals(listOf(GridPoint(340, 160)), rom.outputPorts())
        assertEquals(4, rom.portWidth(rom.inputPorts().single()))
        assertEquals(12, rom.portWidth(rom.outputPorts().single()))
        assertEquals(ComponentBounds(100, 100, 340, 320), rom.bounds())
        assertEquals(ComponentBounds(100, 100, 340, 240),
            rom.copy(attributes = rom.attributes + ("appearance" to "classic")).bounds())
    }

    @Test fun explicitAppearanceSharesRotatedPortsAndBounds() {
        val c = component(ComponentKind.SUBCIRCUIT, "facing" to "north").copy(
            explicitPorts = listOf(ComponentPort("pin", GridPoint(-20, 10), PortDirection.INPUT, 4)),
            explicitBounds = ComponentBounds(-20, 0, 10, 30),
        )
        assertEquals(listOf(GridPoint(110, 120)), c.inputPorts())
        assertEquals(4, c.portWidth(GridPoint(110, 120)))
        assertEquals(ComponentBounds(100, 90, 130, 120), c.bounds())
    }

    @Test fun pinBoundsKeepTheBusReadable() {
        assertEquals(ComponentBounds(20, 90, 100, 110), component(ComponentKind.OUTPUT, "width" to "8").bounds())
        assertEquals(ComponentBounds(60, 100, 140, 120), component(ComponentKind.OUTPUT, "width" to "8", "facing" to "north").bounds())
    }

    @Test fun catalogKeepsPinDirectionAndMemoryDefaults() {
        assertEquals(ComponentKind.OUTPUT, ComponentCatalog.definition("Pin", mapOf("output" to "true"))?.kind)
        assertEquals("classic", ComponentCatalog.definition(ComponentKind.REGISTER)?.defaultAttributes?.get("appearance"))
        assertFailsWith<IllegalArgumentException> { ComponentPort("bad", GridPoint(0, 0), PortDirection.INPUT, 65) }
    }
}
