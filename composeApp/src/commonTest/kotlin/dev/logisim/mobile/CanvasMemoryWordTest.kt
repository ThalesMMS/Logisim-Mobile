package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.MemoryCellsChanged
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasMemoryWordTest {
    @Test
    fun visibleRamWordEditsKeepNeighboringRuntimeValuesAndEnforceWidth() {
        val state = EditorState(null)
        state.newDocument()
        assertTrue(state.insertComponent(ComponentKind.RAM, GridPoint(100, 100)))
        val ram = state.document.components.single()
        val grid = assertNotNull(memoryDisplayGrid(ram, state.componentRegistry)).following(0xff)
        val address = assertNotNull(grid.addressAt(grid.cell(0xff).center))
        assertEquals(0xff, address)
        assertNull(grid.addressAt(grid.bounds.topLeft))
        assertTrue(state.sendComponentEvent(ram.id, MemoryCellsChanged(mapOf(0xfeL to 0x5AuL))))
        val revision = state.revision

        assertNull(state.editMemoryWord(state.accessibilityContext(), ram.id, address, "A5"))
        val error = state.editMemoryWord(state.accessibilityContext(), ram.id, address, "1A5")
        assertEquals(Messages.MemoryErrorValueWidth, error?.key)
        assertEquals(mapOf(0xfeL to 0x5AuL, 0xffL to 0xA5uL), memoryEditorImage(ram, state.simulation, state.componentRegistry).cells)
        assertEquals(revision, state.revision)
    }

    @Test
    fun visibleRomWordEditSurvivesSavingAndIgnoresAnotherDocument() {
        val state = EditorState(null)
        state.newDocument()
        assertTrue(state.insertComponent(ComponentKind.ROM, GridPoint(100, 100)))
        val rom = state.document.components.single()
        val context = state.accessibilityContext()
        val grid = assertNotNull(memoryDisplayGrid(rom, state.componentRegistry)).at(0x80)
        val address = assertNotNull(grid.addressAt(grid.cell(0x82).center))
        assertEquals(0x82, address)

        assertNull(state.editMemoryWord(context, rom.id, address, "A5"))
        val reopened = EditorState(state.exportXml())
        assertEquals(mapOf(0x82L to 0xA5uL), memoryEditorImage(
            reopened.document.components.single(), reopened.simulation, reopened.componentRegistry).cells)

        state.newDocument()
        assertTrue(state.insertComponent(ComponentKind.ROM, GridPoint(100, 100)))
        val otherRom = state.document.components.single()
        assertEquals(Messages.MemoryToolNotApplied, state.editMemoryWord(context, otherRom.id, address, "A5")?.key)
        assertTrue(memoryEditorImage(otherRom, state.simulation, state.componentRegistry).cells.isEmpty())
    }
    @Test
    fun originalRomFollowsHighInputAndRetainsManualWindowUntilTheAddressChanges() {
        val state = EditorState("""
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#Memory" name="1"/>
              <main name="main"/>
              <circuit name="main">
                <wire from="(140,260)" to="(210,260)"/>
                <wire from="(260,300)" to="(260,340)"/>
                <wire from="(350,260)" to="(420,260)"/>
                <comp lib="0" loc="(140,260)" name="Constant"><a name="width" val="8"/><a name="value" val="0x80"/></comp>
                <comp lib="0" loc="(260,340)" name="Constant"><a name="value" val="0x1"/><a name="facing" val="north"/></comp>
                <comp lib="1" loc="(350,260)" name="ROM"><a name="contents">addr/data: 8 8
                 128*00 a5 5a
                </a></comp>
                <comp lib="0" loc="(420,260)" name="Pin"><a name="label" val="Y"/><a name="facing" val="west"/>
                  <a name="output" val="true"/><a name="width" val="8"/></comp>
              </circuit>
            </project>
        """.trimIndent())
        val rom = state.document.components.single { it.kind == ComponentKind.ROM }
        val grid = assertNotNull(memoryDisplayGrid(rom, state.componentRegistry))
        val active = memoryActiveAddress(rom, state.simulation, state.componentRegistry)
        assertEquals(0x80, active)
        val window = MemoryCanvasWindow().update(grid, active)
        val displayed = grid.at(window.firstAddress)
        assertTrue(0x80 in displayed.addresses)
        assertEquals(0x80, displayed.addressAt(displayed.cell(0x80).center))
        assertEquals(0xa5uL, memoryEditorImage(rom, state.simulation, state.componentRegistry)[0x80L])

        val manual = window.copy(firstAddress = 0xa1)
        assertEquals(manual, manual.update(grid, active))
        assertEquals(0xa1, grid.at(manual.firstAddress).addresses.first)
        assertEquals(0xff, grid.at(0xff).addresses.last)
        val changed = manual.update(grid, 0xc0)
        assertTrue(0xc0 in grid.at(changed.firstAddress).addresses)
    }

}
