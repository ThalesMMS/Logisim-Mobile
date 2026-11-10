package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryLayoutTest {
    @Test
    fun classicRomAndRamHaveDesktopPortPositions() {
        val position = GridPoint(100, 200)
        val rom = MemoryLayouts.layout(position, MemoryConfiguration(readOnly = true, classic = true))
        assertEquals(ComponentBounds(100, 200, 340, 340), rom.bounds)
        assertEquals(listOf(GridPoint(100, 210), GridPoint(340, 260)), rom.terminals.map { it.position })
        val ram = MemoryLayouts.layout(position, MemoryConfiguration(classic = true))
        assertEquals(listOf("address0", "dataOut0_0", "dataIn0_0", "outputEnable0", "writeEnable0", "clock0"), ram.terminals.map { it.name })
        assertEquals(listOf(GridPoint(100, 210), GridPoint(340, 290), GridPoint(100, 290),
            GridPoint(100, 260), GridPoint(100, 250), GridPoint(100, 270)), ram.terminals.map { it.position })
    }

    @Test
    fun dualRamAndByteEnablePortsUseBothBanks() {
        val layout = MemoryLayouts.layout(GridPoint(0, 0), MemoryConfiguration(dualPort = true, classic = true,
            dataBits = 16, byteEnables = true, clearPin = true))
        assertEquals(17, layout.terminals.size)
        assertEquals(GridPoint(0, 10), layout.terminals.first { it.name == "address0" }.position)
        assertEquals(GridPoint(0, 160), layout.terminals.first { it.name == "address1" }.position)
        assertEquals(GridPoint(240, 110), layout.terminals.first { it.name == "dataOut0_0" }.position)
        assertEquals(GridPoint(240, 260), layout.terminals.first { it.name == "dataOut1_0" }.position)
        assertEquals(GridPoint(40, 0), layout.terminals.first { it.name == "clear" }.position)
    }

    @Test
    fun allSupportedConfigurationsHaveUniqueNamesAndWidths() {
        for (kind in listOf("ROM", "RAM", "DualRAM")) for (appearance in listOf("classic", "evolution", "logisim_evolution"))
            for (bus in MemoryBus.entries) for (line in listOf("single", "dual", "quad", "octo")) {
                val c = MemoryConfiguration.fromAttributes(kind, mapOf("appearance" to appearance,
                    "databus" to bus.serialized, "enables" to "line", "line" to line, "dataWidth" to "64"))
                val layout = MemoryLayouts.layout(GridPoint(-200, -100), c)
                assertEquals(layout.terminals.size, layout.terminals.map { it.name }.toSet().size)
                assertTrue(layout.terminals.all { it.width in 1..64 })
                assertTrue(layout.bounds.width > 0 && layout.bounds.height > 0)
            }
    }
}
