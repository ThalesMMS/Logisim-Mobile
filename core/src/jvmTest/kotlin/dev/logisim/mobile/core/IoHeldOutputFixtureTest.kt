package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Functional values of the small circuit used for the mounted Canvas hold. */
class IoHeldOutputFixtureTest {
    @Test
    fun theHeldProbeAndBothReferencePinsHaveLiteralIndependentValues() {
        val source = sequenceOf(File("examples/io-held-output.circ"), File("../examples/io-held-output.circ"))
            .first { it.isFile }.readText()
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project
        val document = project.mainCircuit
        assertEquals(6, document.components.size)
        assertEquals(3, document.wires.size)
        val button = document.components.single { it.label == "BTN_HELD" }
        assertEquals(IoKinds.Button, button.kind)
        assertEquals(ComponentBounds(120, 110, 140, 130), button.bounds())
        assertEquals(IoPointerAction(IoInputEvent.Press(true), IoInputEvent.Press(false)),
            IoInteractions.press(button, GridPoint(130, 120)))
        for ((label, y) in listOf("OUT_HELD" to 120, "REF_LOW" to 200, "REF_HIGH" to 280)) {
            val pin = document.components.single { it.label == label }
            assertEquals(ComponentKind.OUTPUT, pin.kind)
            assertEquals(1, pin.width)
            assertEquals("2", pin.attributes["radix"])
            assertEquals(ComponentBounds(240, y - 10, 260, y + 10), pin.bounds())
        }
        val simulator = CircuitSimulator()
        var state = simulator.settle(project)
        fun assertOutputs(held: String) {
            for ((label, expected) in mapOf("OUT_HELD" to held, "REF_LOW" to "0", "REF_HIGH" to "1")) {
                val component = document.components.single { it.label == label }
                assertEquals(expected, state.vectorValues.getValue(component.id).display, label)
            }
            assertFalse(state.oscillating)
            assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString())
        }
        fun send(event: IoInputEvent) {
            state = simulator.settle(project, state, events = mapOf(button.id to listOf(event)))
        }
        assertOutputs("0")
        send(IoInputEvent.Press(true))
        assertOutputs("1")
        repeat(3) {
            state = simulator.settle(project, state)
            assertOutputs("1")
        }
        send(IoInputEvent.Press(false))
        assertOutputs("0")
        send(IoInputEvent.Press(true))
        send(IoInputEvent.Cancel)
        assertOutputs("0")
        val xml = CircCodec.writeProject(project)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(xml, CircCodec.writeProject(reopened))
        state = simulator.settle(reopened)
        assertOutputs("0")
    }
}
