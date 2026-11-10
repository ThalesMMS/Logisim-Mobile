package dev.logisim.mobile

import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** #340: Text color and appearance colors in the dark theme follow Evolution d58a61e0. */
class DarkThemeTextColorTest {
    private val registry = ComponentCatalog.registry

    @Test
    fun textColorShowsAndTakesTheDisplayedColorInTheDarkTheme() {
        // TextColorAttribute.toDisplayString: black shows white, the light theme shows the stored value.
        assertEquals("#ffffff", ThemeTextColor.display("#000000", dark = true))
        assertEquals("#000000", ThemeTextColor.display("#000000", dark = false))
        assertEquals("#ffffff80", ThemeTextColor.display("#00000080", dark = true))
        // TextColorChooser.getStoredColor: the typed displayed color stores its inversion.
        assertEquals("#000000", ThemeTextColor.store("#FFFFFF", stored = null, dark = true))
        assertEquals("#ffffff", ThemeTextColor.store("#000000", stored = "#000000", dark = true))
        assertEquals("#00AA11", ThemeTextColor.store("#00AA11", stored = "#000000", dark = false))
        // Unchanged RGB keeps the stored value, which inversion does not round-trip after clipping.
        val clipped = "#ff0000"
        val shown = ThemeTextColor.display(clipped, dark = true)
        assertEquals(clipped, ThemeTextColor.store(shown, stored = clipped, dark = true))
        assertEquals("#ff000040", ThemeTextColor.store("$shown" + "40", stored = clipped, dark = true))
    }

    @Test
    fun attributeTableEditsTheTextColorAsDisplayed() {
        val text = CircuitComponent("t", AnnotationLibrary.Text, GridPoint(0, 0), attributes = mapOf("text" to "x", "color" to "#000000"))
        val dark = PropertyEditorModel.fields(registry, listOf(text), darkTheme = true).single { it.name == "color" }
        assertEquals("#ffffff", dark.value)
        assertEquals("#000000", PropertyEditorModel.fields(registry, listOf(text)).single { it.name == "color" }.value)
        val typedWhite = assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(text), "color", "#ffffff", darkTheme = true))
        assertEquals("#000000", typedWhite.updates["color"])
        // Reproduction of #340: typing white in the dark theme stores black, which the dark canvas draws white.
        val typedBlack = assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(text), "color", "#000000", darkTheme = true))
        assertEquals("#ffffff", typedBlack.updates["color"])
        val light = assertIs<PropertyEditResult.Valid>(PropertyEditorModel.validate(registry, listOf(text), "color", "#ffffff"))
        assertEquals("#ffffff", light.updates["color"])
    }

    @Test
    fun textToolColorIsOneLightReferencePreference() {
        val dark = EditorPreferences(themeMode = EditorThemeMode.DARK)
        // Choosing #FFFFFF in the dark theme stores the light reference black, so new Text shows white.
        val chosen = dark.withRoleColor(dark = true, EditorColor.TEXT_TOOL, ThemeTextColor.invertRgb(0xFFFFFF))
        assertEquals(0x000000, chosen.colors[EditorColor.TEXT_TOOL])
        assertNull(chosen.darkColors[EditorColor.TEXT_TOOL])
        assertEquals("#000000", chosen.creationDefaultsWithTextColor.textColor)
        assertEquals("#ffffff", ThemeTextColor.display(chosen.textToolColor, dark = true))
        // Other colors stay per theme.
        assertEquals(0x123456, dark.withRoleColor(dark = true, EditorColor.STROKE, 0x123456).darkColors[EditorColor.STROKE])
    }

    @Test
    fun aStoredDarkTextToolColorMigratesToTheLightReference() {
        val legacy = EditorPreferencesCodec.write(EditorPreferences(darkColors = mapOf(EditorColor.TEXT_TOOL to 0xFFFFFF, EditorColor.STROKE to 0x123456)))
        val loaded = EditorPreferencesCodec.read(legacy).preferences
        assertEquals(0x000000, loaded.colors[EditorColor.TEXT_TOOL])
        assertEquals(mapOf(EditorColor.STROKE to 0x123456), loaded.darkColors)
        // An existing light value wins over the old dark entry.
        val both = EditorPreferencesCodec.write(EditorPreferences(colors = mapOf(EditorColor.TEXT_TOOL to 0x00AA11), darkColors = mapOf(EditorColor.TEXT_TOOL to 0xFFFFFF)))
        assertEquals(0x00AA11, EditorPreferencesCodec.read(both).preferences.colors[EditorColor.TEXT_TOOL])
    }

    @Test
    fun defaultAppearanceTakesTheComponentColorAndCustomShapesKeepTheirs() {
        val appearance = SymbolAppearance(dev.logisim.mobile.core.SymbolAnchor(GridPoint(0, 0)), emptyList(), listOf(
            SymbolObject.Shape("band", "rect", mapOf("fill" to "#000000", "stroke" to "none")),
            SymbolObject.Shape("body", "rect", mapOf("fill" to "none", "stroke" to "#000000")),
            SymbolObject.Shape("title", "text", mapOf("fill" to "#ffffff"), "Adder"),
            SymbolObject.Shape("accent", "rect", mapOf("fill" to "#ffffff", "stroke" to "#ff0000")),
        ))
        // DefaultEvolutionAppearance in the dark theme: white band and outline, title in 0xFFFFFF ^ COMPONENT_COLOR.
        val themed = appearance.inComponentColor(0xFFFFFF).objects.filterIsInstance<SymbolObject.Shape>().associate { it.id to it.attributes }
        assertEquals("#FFFFFF", themed.getValue("band")["fill"])
        assertEquals("#FFFFFF", themed.getValue("body")["stroke"])
        assertEquals("#000000", themed.getValue("title")["fill"])
        assertEquals(mapOf("fill" to "#ffffff", "stroke" to "#ff0000"), themed.getValue("accent"))
    }
}
