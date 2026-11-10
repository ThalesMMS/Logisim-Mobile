package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualPathCommand
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererDINTest {
    @Test
    fun selectedStyleSurvivesClosingAndReopeningThePreferencesRepository() {
        var saved: String? = null
        val storage = object : PreferencesStorage {
            override fun load() = saved
            override fun save(serialized: String) { saved = serialized }
        }
        for (style in EditorGateShape.entries) {
            assertEquals(PreferencesSaveResult.Success,
                EditorPreferencesRepository(storage).save(EditorPreferences(gateShape = style)))
            assertEquals(style, EditorPreferencesRepository(storage).load().preferences.gateShape)
        }
        for (language in AppLanguage.entries) {
            assertEquals("DIN 40700", message(Messages.PreferencesDin40700).resolve(language))
        }
    }

    @Test
    fun originalDinNandUsesASeparatedOutputLeadAndFilledInversionMark() {
        val component = CircuitComponent("nand", ComponentKind.NAND, GridPoint(0, 0), attributes = mapOf("size" to "50"))
        val group = group(component)
        val body = group.children.first() as VisualPrimitive.Path
        assertEquals(VisualPathCommand.MoveTo(VisualPoint(-60.0, -25.0)), body.commands.first())
        assertEquals(VisualPathCommand.LineTo(VisualPoint(-35.0, 0.0)), body.commands[2])
        assertEquals(VisualPathCommand.Close, body.commands.last())
        assertEquals(listOf(VisualPathCommand.MoveTo(VisualPoint(-35.0, 0.0)),
            VisualPathCommand.LineTo(VisualPoint(0.0, 0.0))), group.children.filterIsInstance<VisualPrimitive.Path>().last().commands)
        val inversion = group.children.filterIsInstance<VisualPrimitive.Ellipse>().single()
        assertEquals(VisualBounds(-39.0, -4.0, -31.0, 4.0), inversion.bounds)
        assertEquals(0xff000000u, inversion.paint.fill)
        assertNull(inversion.paint.stroke)
    }

    @Test
    fun xorAndXnorUseTheOriginalDistinctThreeBarMarks() {
        for ((kind, lines) in listOf(ComponentKind.XOR to 4, ComponentKind.XNOR to 3)) {
            val component = CircuitComponent("xor", kind, GridPoint(0, 0), attributes = mapOf("size" to "50"))
            val group = group(component)
            val marks = group.children.filterIsInstance<VisualPrimitive.Path>().drop(3).dropLast(1)
            assertEquals(lines, marks.size)
            assertEquals(listOf(-5.0, 0.0, 5.0), marks.take(3).map { (it.commands.first() as VisualPathCommand.MoveTo).point.y })
            assertTrue(group.children.filterIsInstance<VisualPrimitive.Ellipse>().isEmpty())
        }
        val not = group(CircuitComponent("not", ComponentKind.NOT, GridPoint(0, 0), attributes = mapOf("size" to "20")))
        assertEquals(VisualPathCommand.MoveTo(VisualPoint(-20.0, -9.0)), (not.children.first() as VisualPrimitive.Path).commands.first())
        assertEquals(VisualBounds(-15.0, -4.0, -7.0, 4.0), not.children.filterIsInstance<VisualPrimitive.Ellipse>().single().bounds)
    }

    @Test
    fun orInputLeadsMeetTheCurvedEdgeAndWideGatesHaveAStraightSection() {
        val or = group(CircuitComponent("or", ComponentKind.OR, GridPoint(0, 0), attributes = mapOf("size" to "50")))
        val leads = or.children.filterIsInstance<VisualPrimitive.Path>().drop(1).take(2)
        assertEquals(VisualPathCommand.LineTo(VisualPoint(-35.0, -20.0)), leads[0].commands.last())
        assertEquals(VisualPathCommand.LineTo(VisualPoint(-35.0, 20.0)), leads[1].commands.last())
        val tall = group(CircuitComponent("or", ComponentKind.OR, GridPoint(0, 0), attributes = mapOf("size" to "50", "inputs" to "16")))
        val body = tall.children.first() as VisualPrimitive.Path
        assertEquals(VisualPathCommand.MoveTo(VisualPoint(-50.0, -85.0)), body.commands.first())
        assertEquals(VisualPathCommand.LineTo(VisualPoint(0.0, 35.0)), body.commands[2])
    }

    @Test
    fun sizesRotationsAndInputInversionsKeepEveryWireOnItsAuthoritativeTerminal() {
        for (kind in listOf(ComponentKind.AND, ComponentKind.NAND, ComponentKind.OR, ComponentKind.NOR, ComponentKind.XOR, ComponentKind.XNOR)) {
            for (size in listOf(30, 50, 70)) for (count in listOf(2, 4, 9, 64)) for (facing in listOf("east", "south", "west", "north")) {
                val component = CircuitComponent("gate", kind, GridPoint(100, 200),
                    attributes = mapOf("size" to "$size", "inputs" to "$count", "facing" to facing, "negate0" to "true"))
                val inputs = component.inputPorts()
                val outputs = component.outputPorts()
                val wires = inputs.map { WireSegment(it.copy(x = it.x - 10), it) }
                val document = CircuitDocument(components = listOf(component), wires = wires)
                val serialized = CircCodec.write(document)
                val drawing = group(component)
                val leads = drawing.children.filterIsInstance<VisualPrimitive.Path>().drop(1).take(count)
                assertEquals(count, leads.size)
                val starts = leads.map { (it.commands.first() as VisualPathCommand.MoveTo).point }
                assertEquals(inputs, starts.map { point -> worldPoint(component, point) })
                val bubble = drawing.children.filterIsInstance<VisualPrimitive.Ellipse>().first()
                assertEquals(starts.first(), VisualPoint(bubble.bounds.left, (bubble.bounds.top + bubble.bounds.bottom) / 2.0))
                assertEquals(listOf(0.0, 90.0, 180.0, 270.0)[listOf("east", "south", "west", "north").indexOf(facing)], drawing.transform.rotationDegrees)
                assertNotNull(gateVisualItem(component, EditorGateShape.IEC))
                assertNull(gateVisualItem(component, EditorGateShape.ANSI))
                assertEquals(inputs, component.inputPorts())
                assertEquals(outputs, component.outputPorts())
                assertEquals(serialized, CircCodec.write(document))
            }
        }
    }

    @Test
    fun dinSceneExportsVectorCurvesRotationAndTheParityRectangle() {
        val component = CircuitComponent("gate", ComponentKind.NAND, GridPoint(100, 200), attributes = mapOf("size" to "50", "facing" to "south"))
        val item = assertNotNull(gateVisualItem(component, EditorGateShape.DIN40700))
        val svg = VisualExport.svg(VisualScene(listOf(item))).decodeToString()
        assertTrue(svg.contains("data-object-id=\"gate\""))
        assertTrue(svg.contains("rotate(90)"))
        assertTrue(svg.contains("M-60 -25 C"))
        assertTrue(svg.contains("<ellipse"))
        for ((kind, symbol) in listOf("ODD_PARITY" to "2k+1", "EVEN_PARITY" to "2k")) {
            val parity = group(CircuitComponent("parity", ComponentKind(kind), GridPoint(0, 0)))
            assertEquals(symbol, parity.children.filterIsInstance<VisualPrimitive.Text>().single().text)
            assertEquals(1, parity.children.filterIsInstance<VisualPrimitive.Rectangle>().size)
        }
        for (kind in listOf(ComponentKind.BUFFER, ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER, ComponentKind.CLOCK)) {
            assertNull(dinGateVisualItem(CircuitComponent("unchanged", kind, GridPoint(0, 0))))
        }
    }

    private fun group(component: CircuitComponent) = assertNotNull(gateVisualItem(component, EditorGateShape.DIN40700))
        .primitives.single() as VisualPrimitive.Group

    private fun worldPoint(component: CircuitComponent, point: VisualPoint): GridPoint {
        val (x, y) = when (component.facing) {
            "south" -> -point.y to point.x
            "west" -> -point.x to -point.y
            "north" -> point.y to -point.x
            else -> point.x to point.y
        }
        return GridPoint(component.position.x + x.toInt(), component.position.y + y.toInt())
    }
}
