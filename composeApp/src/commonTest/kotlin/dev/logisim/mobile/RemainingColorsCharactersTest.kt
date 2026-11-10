package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitTopology
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.widthIncompatibilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemainingColorsCharactersTest {
    private fun rgb(color: Color) = (color.value shr 32).toLong().toInt() and 0xFFFFFF

    @Test
    fun newColorRolesUseEvolutionLightAndDarkDefaults() {
        val light = mapOf(
            EditorColor.TEXT_TOOL to 0x000000, EditorColor.STROKE to 0xFF00FF, EditorColor.WIDTH_ERROR to 0xFF7B00,
            EditorColor.WIDTH_ERROR_CAPTION to 0x550000, EditorColor.WIDTH_ERROR_HIGHLIGHT to 0xFFFF00,
            EditorColor.WIDTH_ERROR_BACKGROUND to 0xFFE6D2, EditorColor.CLOCK_FREQUENCY to 0xFF00B4,
            EditorColor.KMAP_CELL_TEXT to 0x0000FF, EditorColor.TABLE_CURSOR to 0xFFFFFF,
            EditorColor.TABLE_HIGHLIGHT to 0xFFFFC0, EditorColor.TABLE_SELECTION to 0xC0C0FF,
            EditorColor.KMAP1 to 0x800000, EditorColor.KMAP8 to 0xFFFF19, EditorColor.KMAP16 to 0xF032E6,
        )
        for ((role, value) in light) assertEquals(value, rgb(emptyMap<EditorColor, Int>().themeColor(role)), role.name)
        val dark = EditorPreferences().effectiveColors(dark = true)
        assertEquals(mapOf(EditorColor.STROKE to 0xFF88FF, EditorColor.KMAP_CELL_TEXT to 0xFFFFFF,
            EditorColor.TABLE_CURSOR to 0x666666, EditorColor.TABLE_HIGHLIGHT to 0x7A6A00, EditorColor.TABLE_SELECTION to 0x323C78),
            dark.filterKeys { it in light })
        // Width error and clock colors keep the light defaults in the dark theme, like ThemeColorPreference.
        assertEquals(0xFF7B00, rgb(dark.themeColor(EditorColor.WIDTH_ERROR)))
        assertEquals(16, EditorColor.kmapGroups.toSet().size)
    }

    @Test
    fun colorsAndValueCharactersPersistAndOlderPreferencesMigrate() {
        val characters = ValueCharacters('T', 'F', 'Z', '!', 'X')
        val preferences = EditorPreferences(valueCharacters = characters,
            colors = mapOf(EditorColor.WIDTH_ERROR to 0x123456, EditorColor.KMAP12 to 0x654321),
            darkColors = mapOf(EditorColor.TABLE_SELECTION to 0x010203))
        val encoded = EditorPreferencesCodec.write(preferences)
        assertTrue("color.widthError=%23123456" in encoded && "darkColor.tableSelection=%23010203" in encoded, encoded)
        assertTrue("valueChar.error=%21" in encoded && "valueChar.unknown=Z" in encoded, encoded)
        val read = EditorPreferencesCodec.read(encoded)
        assertEquals(preferences, read.preferences)
        assertEquals(emptyList(), read.notices)

        // A file from before these preferences keeps its values and gets Evolution's defaults.
        val older = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nsignalRadix=HEXADECIMAL\ncolor.signalHigh=%2300FF00\n")
        assertEquals(ValueCharacters.Default, older.preferences.valueCharacters)
        assertEquals(mapOf(EditorColor.SIGNAL_HIGH to 0x00FF00), older.preferences.colors)
        assertEquals(EditorSignalRadix.HEXADECIMAL, older.preferences.signalRadix)
        assertEquals(emptyList(), older.notices)
        assertEquals(1, EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n1\ngrid=false\n").migratedFromVersion)

        // A character outside the SymbolChooser options falls back with a notice.
        val invalid = EditorPreferencesCodec.read("LOGISIM-EDITOR-PREFERENCES\n2\nvalueChar.true=Y\nvalueChar.false=F\n")
        assertEquals(ValueCharacters(falseChar = 'F'), invalid.preferences.valueCharacters)
        assertEquals(1, invalid.notices.size)
    }

    @Test
    fun shownValuesFollowTheCharacters() {
        val unknown = LogicVector.unknown(4)
        val custom = ValueCharacters(unknownChar = 'Z', errorChar = 'X')
        assertEquals("UUUU", formatEditorSignal(unknown, EditorSignalRadix.BINARY))
        assertEquals("0xZ", formatEditorSignal(unknown, EditorSignalRadix.HEXADECIMAL, custom))
        assertEquals("ZZZZ", formattedSignal(unknown, "16", custom))
        assertEquals(listOf("ZZZZ", "Z"), wirePokeReadings(unknown, WirePokeRadix.BINARY, WirePokeRadix.SIGNED_DECIMAL, custom))
        assertEquals(listOf("z"), wirePokeReadings(unknown, WirePokeRadix.HEXADECIMAL, null, custom).map { it.lowercase() })
        // Defined values are unaffected.
        assertEquals("00101010", formatEditorSignal(LogicVector.of(8, 42uL), EditorSignalRadix.BINARY, custom))
    }

    @Test
    fun widthErrorsPaintWiresAndCaptionsInTheirColorsAndPokedWiresUseTheStroke() {
        val a = CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8"))
        val b = CircuitComponent("b", ComponentKind.OUTPUT, GridPoint(100, 0), attributes = mapOf("width" to "4"))
        val wire = WireSegment(a.position, b.position)
        val document = CircuitDocument("mismatch", listOf(a, b), listOf(wire))
        val conflicts = CircuitTopology.elaborate(document).widthIncompatibilities()
        val colors = mapOf(EditorColor.WIDTH_ERROR to 0x112233, EditorColor.WIDTH_ERROR_CAPTION to 0x445566,
            EditorColor.WIDTH_ERROR_BACKGROUND to 0x778899)
        val style = CircuitDrawingStyle(colors = colors, widthErrorPoints = conflicts.flatMap { it.netPoints }.toSet())
        val stroke = (circuitWireScene(document, SimulationState(), style).first().primitives.single() as VisualPrimitive.Path).paint.stroke
        assertEquals(0xFF112233u, stroke)
        // Two points, tied widths: no highlight ring, background outline under each caption.
        val scene = widthIncompatibilityScene(conflicts, style)
        assertEquals(2, scene.size)
        val captions = scene.first().primitives.filterIsInstance<VisualPrimitive.Text>()
        assertEquals(setOf("8", "4"), scene.map { item -> item.primitives.filterIsInstance<VisualPrimitive.Text>().last().text }.toSet())
        assertEquals(0xFF445566u, captions.last().color)
        assertTrue(captions.dropLast(1).all { it.color == 0xFF778899u })
        val rings = scene.first().primitives.filterIsInstance<VisualPrimitive.Ellipse>()
        assertEquals(listOf(0xFF112233u), rings.map { it.paint.stroke })
        // The ring and caption keep Evolution's defaults when no color is chosen.
        val defaults = widthIncompatibilityScene(conflicts, CircuitDrawingStyle())
        assertEquals(0xFFFF7B00u, defaults.first().primitives.filterIsInstance<VisualPrimitive.Ellipse>().single().paint.stroke)
    }

    @Test
    fun textToolColorAndKarnaughGroupFillsFollowThePreferences() {
        // TEXT_TOOL_COLOR is one preference (#340): both themes create Text with the stored light reference.
        val preferences = EditorPreferences(colors = mapOf(EditorColor.TEXT_TOOL to 0x00AA11))
        assertEquals("#00aa11", preferences.textToolColor)
        assertEquals("#00aa11", preferences.creationDefaultsWithTextColor.textColor)
        assertEquals("#000000", EditorPreferences().textToolColor)
        // KMapGroupInfo.adjustColor: alpha 128 in the light theme, brightened with alpha 180 in the dark one.
        val light = karnaughGroupFill(Color(0xFF800000), dark = false)
        assertEquals(128, kotlin.math.round(light.alpha * 255).toInt())
        val dark = karnaughGroupFill(Color(0xFF000000), dark = true)
        assertEquals(180, kotlin.math.round(dark.alpha * 255).toInt())
        assertEquals(114, kotlin.math.round(dark.red * 255).toInt())
    }
}
