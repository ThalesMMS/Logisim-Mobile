package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.*
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #334: default labels, Text and component bodies stay readable on the dark canvas. */
class DarkCanvasContrastTest {
    private val darkCanvas = Color(0xFF000000.toInt() or DarkEditorColors.getValue(EditorColor.CANVAS))

    @Test fun defaultLabelsOfTheAndExampleReachTextContrastOnBothCanvases() {
        val labelled = DemoCircuits.andGate().components.filter { it.label.isNotBlank() }
        assertTrue(labelled.size >= 3)
        for (component in labelled) {
            val bounds = classicComponentBounds(component)
            val dark = assertNotNull(classicComponentLabel(component, bounds, CircuitDrawingStyle(darkTheme = true).defaultLabelColor))
            assertEquals(DarkDefaultLabelColor.toArgb().toUInt(), dark.color, component.label)
            assertTrue(contrast(Color(dark.color.toInt()), darkCanvas) >= 4.5, "${component.label} on the dark canvas")
            val light = assertNotNull(classicComponentLabel(component, bounds))
            assertTrue(contrast(Color(light.color.toInt()), Color.White) >= 4.5, "${component.label} on the light canvas")
        }
    }

    @Test fun lightDefaultLabelColorsFollowTheThemeAndExplicitColorsAreKept() {
        for (value in listOf(null, "#000000", "#0000ff", "#0000FFff")) {
            assertEquals(DarkDefaultLabelColor, themedLabelColor(value, DarkDefaultLabelColor), value)
        }
        assertEquals(DefaultLabelColor, themedLabelColor(null))
        assertEquals(Color(0xFF000000), themedLabelColor("#000000"))
        assertEquals(Color(0xFFFF8800), themedLabelColor("#ff8800", DarkDefaultLabelColor))
        assertEquals(Color(0xFFFF8800), themedLabelColor("#ff8800"))
    }

    @Test fun textAnnotationsShowTheLumaInvertedColorLikeEvolution() {
        assertEquals(0xffffffffu, luminanceInvertedColor(0xff000000u))
        assertEquals(0xff000000u, luminanceInvertedColor(0xffffffffu))
        // Equal RGB offsets keep the hue: pure blue becomes a light blue, alpha kept.
        assertEquals(0x80dadaffu, luminanceInvertedColor(0x800000ffu))
        val text = CircuitComponent("t", AnnotationLibrary.Text, GridPoint(0, 0), attributes = mapOf("text" to "note", "color" to "#000000"))
        val item = AnnotationVisuals.item(text).withThemeTextColors()
        val primitive = item.primitives.filterIsInstance<VisualPrimitive.Text>().single()
        assertTrue(contrast(Color(primitive.color.toInt()), darkCanvas) >= 4.5)
    }

    @Test fun bodiesUseTheCanvasInTheDarkThemeAndWhiteOtherwise() {
        val dark = CircuitDrawingStyle(colors = DarkEditorColors, darkTheme = true)
        assertEquals(darkCanvas, dark.paper)
        assertEquals(Color.White, CircuitDrawingStyle().paper)
        assertEquals(Color.White, dark.copy(printerView = true).paper)
        val ink = Color(0xFF000000.toInt() or DarkEditorColors.getValue(EditorColor.COMPONENT))
        assertTrue(contrast(ink, dark.paper) >= 4.5)
        val and = CircuitComponent("g", ComponentKind.AND, GridPoint(100, 100))
        for (shape in listOf(EditorGateShape.IEC, EditorGateShape.DIN40700)) {
            val item = assertNotNull(gateVisualItem(and, shape, ink = ink.toArgb().toUInt(), paper = dark.paper.toArgb().toUInt()))
            val fills = item.primitives.flatMap { it.paints() }.mapNotNull { it.fill }
            assertTrue(fills.isNotEmpty() && fills.all { it == dark.paper.toArgb().toUInt() }, "$shape fills $fills")
        }
    }

    private fun VisualPrimitive.paints(): List<VisualPaint> = when (this) {
        is VisualPrimitive.Group -> children.flatMap { it.paints() }
        is VisualPrimitive.Path -> listOf(paint)
        is VisualPrimitive.Rectangle -> listOf(paint)
        is VisualPrimitive.Ellipse -> listOf(paint)
        else -> emptyList()
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = if (channel <= 0.03928f) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
