package dev.logisim.mobile

import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitNetworks
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.layout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Evolution d58a61e0 AppPreferences defaults that the preferences campaign found different (#337, #338, #339). */
class PreferencesEvolutionDefaultsTest {
    @Test
    fun canvasDefaultsAreEvolutionsLightThemeColors() {
        fun hex(role: EditorColor) = role.defaultColor(false).toArgb() and 0xFFFFFF
        assertEquals(mapOf(
            EditorColor.GRID_DOT to 0x777777, EditorColor.GRID_ZOOMED_DOT to 0xCCCCCC, EditorColor.COMPONENT to 0x000000,
            EditorColor.COMPONENT_GHOST to 0x999999, EditorColor.COMPONENT_ICON to 0x000000, EditorColor.SIGNAL_HIGH to 0x00D200,
            EditorColor.SIGNAL_UNKNOWN to 0x2828FF, EditorColor.SIGNAL_ERROR to 0xC00000, EditorColor.SIGNAL_FLOATING to 0x808080,
        ), listOf(EditorColor.GRID_DOT, EditorColor.GRID_ZOOMED_DOT, EditorColor.COMPONENT, EditorColor.COMPONENT_GHOST,
            EditorColor.COMPONENT_ICON, EditorColor.SIGNAL_HIGH, EditorColor.SIGNAL_UNKNOWN, EditorColor.SIGNAL_ERROR,
            EditorColor.SIGNAL_FLOATING).associateWith(::hex))
        assertEquals(0xBBBBBB, EditorPreferences().effectiveColors(dark = true)[EditorColor.SIGNAL_FLOATING])
        // Placement previews use the ghost colour even when the user chose none.
        assertEquals(0x999999, CircuitDrawingStyle().forPreview().colors[EditorColor.COMPONENT])
        assertEquals(0x123456, CircuitDrawingStyle(colors = mapOf(EditorColor.COMPONENT_GHOST to 0x123456)).forPreview().colors[EditorColor.COMPONENT])
    }

    @Test
    fun onlyWiresWithoutAWidthUseTheNilColor() {
        // An undriven wire into a gate has the gate's width (UNKNOWN); a wire touching no terminal is Value.NIL.
        val gate = CircuitComponent("g", ComponentKind.AND, GridPoint(100, 100))
        val input = gate.layout().inputs.first().position
        val connected = WireSegment(GridPoint(input.x - 30, input.y), input)
        val loose = WireSegment(GridPoint(20, 200), GridPoint(60, 200))
        val widthless = CircuitNetworks(CircuitDocument("main", listOf(gate), listOf(connected, loose))).widthlessPoints()
        assertEquals(setOf(GridPoint(20, 200), GridPoint(60, 200)), widthless)
        val colors = emptyMap<EditorColor, Int>()
        assertEquals(0xFF808080.toInt(), colors.signalColor(null, null, floating = true, CircuitSignalPalette.Classic).toArgb())
        assertEquals(0xFF2828FF.toInt(), colors.signalColor(LogicVector.unknown(1), null, floating = false, CircuitSignalPalette.Classic).toArgb())
        assertEquals(0xFFC00000.toInt(), colors.signalColor(LogicVector.error(1), null, floating = true, CircuitSignalPalette.Classic).toArgb())
    }

    @Test
    fun pinDotsHaveDrawPinMarkerDiameterAndOffset() {
        for ((pin, size) in EditorPinAppearance.entries.zip(listOf(4, 6, 8, 10))) {
            val radius = CircuitDrawingStyle(pinDotDiameter = pin.diameter).terminalRadius
            assertEquals(size.toDouble(), 2 * radius)
            assertEquals(size / 2.0, radius)
        }
    }

    @Test
    fun hdlTypeIsASavedPreferenceThatDefaultsToVhdl() {
        assertEquals(EditorHdlType.VHDL, EditorPreferences().hdlType)
        assertEquals(listOf("VHDL", "Verilog", "None"), EditorHdlType.entries.map { it.key })
        assertEquals(listOf(HdlLanguage.VHDL, HdlLanguage.VERILOG, null), EditorHdlType.entries.map { it.language })
        for (type in EditorHdlType.entries) {
            val written = EditorPreferencesCodec.write(EditorPreferences(hdlType = type))
            assertTrue("fpga.hdlType=${type.key}" in written)
            assertEquals(type, EditorPreferencesCodec.read(written).preferences.hdlType)
        }
        // Settings saved before the preference read as Evolution's default; an unknown value falls back with a notice.
        assertEquals(EditorHdlType.VHDL, EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nshowGrid=true\n").preferences.hdlType)
        val invalid = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nfpga.hdlType=SystemC\n")
        assertEquals(EditorHdlType.VHDL, invalid.preferences.hdlType)
        assertEquals(1, invalid.notices.size)
    }

    @Test
    fun theFirstBuiltInBoardIsSelectedWhenNoValidBoardIsSaved() {
        val storage = MemoryStorage()
        val names = listOf("ALCHITRY_AU_IO", "BASYS3")
        assertEquals("builtin:ALCHITRY_AU_IO", FpgaBoardCatalogStore(storage).load(names).selected)
        assertEquals("builtin:ALCHITRY_AU_IO", FpgaBoardCatalogStore(storage).load().selected) // saved, like SelectedBoard.set
        FpgaBoardCatalogStore(storage).select("builtin:BASYS3")
        assertEquals("builtin:BASYS3", FpgaBoardCatalogStore(storage).load(names).selected)
        FpgaBoardCatalogStore(storage).select("builtin:RETIRED_BOARD")
        assertEquals("builtin:ALCHITRY_AU_IO", FpgaBoardCatalogStore(storage).load(names).selected)
        assertEquals("builtin:ALCHITRY_AU_IO", FpgaBoardCatalogStore().load(names).selected)
        assertNull(FpgaBoardCatalogStore().load(emptyList()).selected)
    }

    @Test
    fun projectTemplatesOfferEmptyPlainAndCustomAndKeepTheInstalledChoice() {
        val storage = MemoryStorage()
        val store = ProjectTemplateStore(storage, ComponentCatalog.registry)
        assertEquals(ProjectTemplateType.PLAIN, store.type())
        assertNull(store.templateXml())
        assertFailsWith<IllegalStateException> { store.select(ProjectTemplateType.CUSTOM) }

        // A template installed before the choice existed keeps being used (custom).
        val custom = """<project source="3.8.0" version="1.0"><circuit name="Starter"/></project>"""
        storage.writeAtomically("default-project-template.circ", custom)
        assertEquals(ProjectTemplateType.CUSTOM, store.type())
        assertEquals(custom, store.templateXml())

        store.select(ProjectTemplateType.EMPTY)
        assertEquals(ProjectTemplateType.EMPTY, ProjectTemplateStore(storage, ComponentCatalog.registry).type())
        assertEquals(custom, store.read()) // the custom file stays installed
        val empty = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(checkNotNull(
            store.templateXml("principal", mapOf("appearance" to "logisim_evolution"))))).project
        assertEquals(listOf("principal"), empty.circuits.map { it.name })
        assertEquals(emptyMap(), empty.libraries)
        assertTrue(empty.mainCircuit.components.isEmpty() && empty.mainCircuit.wires.isEmpty())
        assertEquals("logisim_evolution", empty.mainCircuit.attributes["appearance"])

        store.select(ProjectTemplateType.PLAIN)
        assertNull(store.templateXml())
        store.select(ProjectTemplateType.CUSTOM)
        store.remove()
        assertEquals(ProjectTemplateType.PLAIN, store.type())
        assertNull(store.read())
        store.install(custom.encodeToByteArray())
        assertEquals(ProjectTemplateType.CUSTOM, store.type())
        // A custom choice whose file disappeared reads as plain (TEMPLATE_UNKNOWN falls back to the plain template).
        storage.writeAtomically("default-project-template.circ", "")
        assertEquals(ProjectTemplateType.PLAIN, store.type())
    }

    @Test
    fun anEmptyTemplateProjectHasOneCircuitWithTheCreationDefaults() {
        val state = EditorState(null)
        state.creationDefaults = EditorPreferences().creationDefaults
        state.newEmptyDocument("main")
        assertEquals(listOf("main"), state.project.circuits.map { it.name })
        assertEquals(emptyMap(), state.project.libraries)
        assertEquals("logisim_evolution", state.project.mainCircuit.attributes["appearance"])
        assertEquals(1.0, SimulationProjectOptions.read(state.project).tickFrequencyHz)
    }

    @Test
    fun theDefaultTickFrequencyIsChosenFromEvolutionsSimulateMenuList() {
        // Frame.java stores the chosen MenuSimulate.SUPPORTED_TICK_FREQUENCIES value as TICK_FREQUENCY's default.
        assertTrue(EditorPreferences.DEFAULT_TICK_FREQUENCY_HZ in TickFrequencies.choices)
        assertTrue(TickFrequencies.choices.all { it in TickFrequencies.supported })
        val editor = EditorState(null, defaultTickFrequencyHz = 8.0)
        editor.newDocument()
        assertEquals(8.0, SimulationProjectOptions.read(editor.project).tickFrequencyHz)
        assertTrue("""<a name="simulationFrequency" val="8.0"/>""" in editor.exportXml())
    }

    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun listNames(): List<String> = files.keys.toList()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }
}
