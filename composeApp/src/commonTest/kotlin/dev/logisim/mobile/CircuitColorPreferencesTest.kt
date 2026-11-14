package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitNetworks
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.WireSegment
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CircuitColorPreferencesTest {
    @Test
    fun savesEveryRoleAndPreservesValidSettingsWhenOneColorIsCorrupt() {
        val colors = EditorColor.entries.associateWith { 0x104000 + it.ordinal }
        val preferences = EditorPreferences(showGrid = false, colors = colors)
        val encoded = EditorPreferencesCodec.write(preferences)
        assertEquals(preferences, EditorPreferencesCodec.read(encoded).preferences)

        val badColor = EditorPreferencesCodec.read(encoded.replace("color.canvas=%23104000", "color.canvas=invalid"))
        assertEquals(preferences.copy(colors = colors - EditorColor.CANVAS), badColor.preferences)
        assertEquals(1, badColor.notices.size)
        assertTrue(badColor.canPersist)
        assertEquals(emptyMap(), EditorPreferencesCodec.read(EditorPreferencesCodec.write(EditorPreferences())).preferences.colors)
    }

    @Test
    fun rendersDistinctColorsForFloatingUnknownDrivenAndMultibitWires() {
        val wires = (0..5).map { row -> WireSegment(GridPoint(50, row * 30), GridPoint(120, row * 30)) }
        val components = wires.dropLast(1).mapIndexed { index, wire ->
            CircuitComponent("source$index", ComponentKind.INPUT, wire.from)
        }
        val document = CircuitDocument("main", components, wires)
        val floating = CircuitNetworks(document).widthlessPoints()
        val simulation = SimulationState(portVectors = wires.map { it.from }.zip(listOf(
            LogicVector.fromBit(LogicValue.HIGH), LogicVector.fromBit(LogicValue.LOW),
            LogicVector.unknown(1), LogicVector.fromBit(LogicValue.ERROR),
            LogicVector.unknown(4), LogicVector.unknown(1),
        )).toMap())
        val colors = mapOf(
            EditorColor.SIGNAL_HIGH to 0xCC11EE, EditorColor.SIGNAL_LOW to 0x11FF22,
            EditorColor.SIGNAL_UNKNOWN to 0x2211DD, EditorColor.SIGNAL_ERROR to 0xFF2244,
            EditorColor.SIGNAL_BUS to 0x220022, EditorColor.SIGNAL_FLOATING to 0xCC9922,
        )
        val style = CircuitDrawingStyle(highContrastSignals = true, colors = colors, floatingPoints = floating)
        fun strokes(style: CircuitDrawingStyle) = circuitWireScene(document, simulation, style).map {
            (it.primitives.single() as VisualPrimitive.Path).paint.stroke
        }
        assertEquals(listOf(0xFFCC11EEu, 0xFF11FF22u, 0xFF2211DDu, 0xFFFF2244u, 0xFF220022u, 0xFFCC9922u), strokes(style))
        assertEquals(List(6) { 0xFF000000u }, strokes(style.copy(printerView = true)))
    }

    @Test
    fun colorRowsCoverEveryRoleOnce() {
        val listed = CanvasColorChoice.groups.flatMap { it.second } + EditorColor.kmapGroups
        assertEquals(EditorColor.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }

    @Test
    fun pickedColorsAreStoredAsBeforeInBothThemes() {
        val defaults = EditorPreferences()
        // Light: the pick is stored directly; dark: in the dark map, over Evolution's DARK_* default.
        assertEquals(mapOf(EditorColor.SIGNAL_HIGH to 0xff8800), CanvasColorChoice.pick(defaults, false, EditorColor.SIGNAL_HIGH, 0xff8800).colors)
        val dark = CanvasColorChoice.pick(defaults, true, EditorColor.SIGNAL_HIGH, 0xff8800)
        assertEquals(mapOf(EditorColor.SIGNAL_HIGH to 0xff8800), dark.darkColors)
        assertEquals(emptyMap(), dark.colors)
        assertEquals(0x00FF00, CanvasColorChoice.shown(defaults, true, EditorColor.SIGNAL_HIGH))
        assertEquals(CircuitSignalPalette.HighContrast.high.toArgb() and 0xFFFFFF,
            CanvasColorChoice.shown(defaults.copy(highContrastSignals = true), false, EditorColor.SIGNAL_HIGH))
        assertTrue(CanvasColorChoice.customized(dark, true, EditorColor.SIGNAL_HIGH))
        assertEquals(defaults, CanvasColorChoice.restore(dark, true, EditorColor.SIGNAL_HIGH))
    }

    @Test
    fun pickingTheColorADefaultRoleShowsStoresNothing() {
        val defaults = EditorPreferences()
        for (dark in listOf(false, true)) for (role in listOf(EditorColor.SIGNAL_HIGH, EditorColor.TEXT_TOOL, EditorColor.KMAP3)) {
            // The Current swatch and an unchanged Apply pick the shown color: the role keeps following its default.
            assertSame(defaults, CanvasColorChoice.pick(defaults, dark, role, CanvasColorChoice.shown(defaults, dark, role)))
        }
        // A default signal color picked unchanged still follows the high-contrast palette afterwards.
        val high = CanvasColorChoice.shown(defaults, false, EditorColor.SIGNAL_HIGH)
        val picked = CanvasColorChoice.pick(defaults, false, EditorColor.SIGNAL_HIGH, high)
        assertEquals(CircuitSignalPalette.HighContrast.high.toArgb() and 0xFFFFFF,
            CanvasColorChoice.shown(picked.copy(highContrastSignals = true), false, EditorColor.SIGNAL_HIGH))
        // A customized role picked unchanged keeps its value.
        val custom = defaults.copy(colors = mapOf(EditorColor.SIGNAL_HIGH to 0x123456))
        assertEquals(custom, CanvasColorChoice.pick(custom, false, EditorColor.SIGNAL_HIGH, 0x123456))
    }

    @Test
    fun theDarkTextToolColorStoresTheLightReference() {
        val defaults = EditorPreferences()
        // Light theme: the picked value is the stored TEXT_TOOL_COLOR.
        assertEquals(0x336699, CanvasColorChoice.pick(defaults, false, EditorColor.TEXT_TOOL, 0x336699).colors[EditorColor.TEXT_TOOL])
        // Dark theme: the picker shows the luma inversion and stores the inversion of the pick, in the shared light map (#340).
        val dark = CanvasColorChoice.pick(defaults, true, EditorColor.TEXT_TOOL, 0xffff00)
        assertEquals(ThemeTextColor.invertRgb(0xffff00), dark.colors[EditorColor.TEXT_TOOL])
        assertEquals(emptyMap(), dark.darkColors)
        assertEquals(ThemeTextColor.invertRgb(0x000000), CanvasColorChoice.shown(defaults, true, EditorColor.TEXT_TOOL))
        // Picking the color already shown keeps the stored reference, which inversion may not reproduce exactly.
        val stored = defaults.copy(colors = mapOf(EditorColor.TEXT_TOOL to 0x123457))
        val shown = CanvasColorChoice.shown(stored, true, EditorColor.TEXT_TOOL)
        assertEquals(0x123457, CanvasColorChoice.pick(stored, true, EditorColor.TEXT_TOOL, shown).colors[EditorColor.TEXT_TOOL])
        // The stored format is unchanged: color.textTool holds the light RGB.
        assertEquals(stored, EditorPreferencesCodec.read(EditorPreferencesCodec.write(stored)).preferences)
    }
}
