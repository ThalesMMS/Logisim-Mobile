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
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
