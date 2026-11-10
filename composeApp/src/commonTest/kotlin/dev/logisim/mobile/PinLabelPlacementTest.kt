package dev.logisim.mobile

import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PinLabelPlacementTest {
    @Test
    fun pinLabelsClearConnectedBusesOnAllFourSides() {
        val terminal = GridPoint(100, 100)
        val endpoints = mapOf(
            "north" to GridPoint(100, 0),
            "south" to GridPoint(100, 200),
            "east" to GridPoint(200, 100),
            "west" to GridPoint(0, 100),
        )
        for (kind in listOf(ComponentKind.INPUT, ComponentKind.OUTPUT)) {
            for ((side, end) in endpoints) {
                val pin = CircuitComponent("pin", kind, terminal, "Bus", attributes = mapOf(
                    "width" to "8", "facing" to side, "labelloc" to side,
                ))
                val document = CircuitDocument(components = listOf(pin), wires = listOf(WireSegment(terminal, end)))
                val simulation = SimulationState(portVectors = mapOf(terminal to LogicVector.low(8)))
                val bus = circuitWireScene(document, simulation, CircuitDrawingStyle()).single().bounds
                val label = assertNotNull(classicComponentLabel(pin, classicComponentBounds(pin)))
                val bounds = label.layoutBounds(30.0, 16.0, 12.0)
                val clear = when (side) {
                    "north", "south" -> bounds.left > bus.right
                    else -> bounds.bottom < bus.top
                }
                assertTrue(clear, "${kind.name} facing $side has label $bounds against bus $bus")
            }
        }
    }

    @Test
    fun inspectorEditsAndSavedLegacyPinsUseTheSameLabelPlacement() {
        val editor = EditorState("""
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <main name="main"/>
              <circuit name="main">
                <wire from="(100,100)" to="(250,100)"/>
                <comp lib="0" loc="(100,100)" name="Pin">
                  <a name="width" val="8"/>
                  <a name="label" val="A"/>
                  <a name="labelloc" val="north"/>
                </comp>
                <comp lib="0" loc="(250,100)" name="Pin">
                  <a name="facing" val="west"/>
                  <a name="output" val="true"/>
                  <a name="width" val="8"/>
                  <a name="label" val="Y"/>
                  <a name="labelloc" val="south"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent())
        val input = editor.document.components.single { it.kind == ComponentKind.INPUT }
        val output = editor.document.components.single { it.kind == ComponentKind.OUTPUT }
        assertEquals("north", input.attributes["labelloc"])
        assertEquals("south", output.attributes["labelloc"])

        for (pin in listOf(input, output)) {
            editor.selectComponent(pin.id)
            val session = PropertyInspectorSession(editor, editor.propertyInspectorContext())
            val field = PropertyEditorModel.fields(editor.componentRegistry, session.context.components)
                .single { it.name == "labelloc" }
            session.open(field)
            val edit = assertNotNull(session.editing)
            edit.value = TextFieldValue(pin.facing)
            assertTrue(session.applyValue(editor, session, edit))
        }

        val reopened = EditorState(editor.exportXml())
        assertEquals(editor.document.wires, reopened.document.wires)
        for (pin in reopened.document.components) {
            assertEquals(pin.facing, pin.attributes["labelloc"])
            val label = assertNotNull(classicComponentLabel(pin, classicComponentBounds(pin)))
            val bounds = label.layoutBounds(12.0, 16.0, 12.0)
            assertTrue(bounds.bottom < 98.0, "${pin.label} still crosses its 4-unit bus at y=100: $bounds")
        }
    }
}
