package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import dev.logisim.mobile.core.FpgaGenerationOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FpgaPreferencesTest {
    @Test
    fun persistsFpgaOptionsAndKeepsEvolutionDefaultsForOlderPreferences() {
        val options = FpgaGenerationOptions(vhdlKeywordsUpperCase = false, suppressGatedClockWarnings = true,
            suppressOpenPinWarnings = true)
        val preferences = EditorPreferences(fpga = options, colors = mapOf(EditorColor.FPGA_MAPPED to 0x123456))
        val encoded = EditorPreferencesCodec.write(preferences)
        assertTrue("fpga.vhdlKeywordsUpperCase=false" in encoded && "color.fpgaMapped=%23123456" in encoded, encoded)
        assertEquals(preferences, EditorPreferencesCodec.read(encoded).preferences)

        // A file saved before these options existed keeps its values and gets Evolution's defaults.
        val older = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nshowGrid=false\ncolor.canvas=%23EEEEEE\n")
        assertEquals(FpgaGenerationOptions(), older.preferences.fpga)
        assertEquals(mapOf(EditorColor.CANVAS to 0xEEEEEE), older.preferences.colors)
        assertEquals(false, older.preferences.showGrid)
        assertEquals(emptyList(), older.notices)
    }

    @Test
    fun fpgaColorsDefaultToEvolutionAndRestoreByRemovingTheOverride() {
        val expected = mapOf(
            EditorColor.FPGA_DEFINE to 0xFF0000, EditorColor.FPGA_DEFINE_HIGHLIGHT to 0x00FF00,
            EditorColor.FPGA_DEFINE_RESIZE to 0x00FFFF, EditorColor.FPGA_DEFINE_MOVE to 0xFF00FF,
            EditorColor.FPGA_MAPPED to 0x005000, EditorColor.FPGA_SELECTED_MAPPED to 0xFF0000,
            EditorColor.FPGA_SELECTABLE_MAPPED to 0x00A000, EditorColor.FPGA_SELECT to 0x0000FF,
        )
        for ((role, rgb) in expected) assertEquals(Color(0xFF000000.toInt() or rgb), emptyMap<EditorColor, Int>().fpgaColor(role))
        val custom = mapOf(EditorColor.FPGA_SELECT to 0x112233)
        assertEquals(Color(0xFF112233), custom.fpgaColor(EditorColor.FPGA_SELECT))
        assertEquals(Color(0xFF0000FF), (custom - EditorColor.FPGA_SELECT).fpgaColor(EditorColor.FPGA_SELECT))
    }
}
