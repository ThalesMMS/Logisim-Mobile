package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RejectedInitialDocumentTest {
    private val original = "<not-a-circuit keep=\"all original content\"/>\n"

    @Test
    fun aValidProjectWithAnUnavailableSourceCanBeViewedButNotEdited() {
        val xml = CircCodec.write(DemoCircuits.andGate())
        val state = EditorState(xml, initialReadOnly = true)
        assertFalse(state.documentWritable)
        assertTrue(state.document.components.isNotEmpty())
        state.selectAll()
        state.removeSelected()
        state.applyProject(CircuitProject(listOf(CircuitDocument("replacement"))))
        assertEquals(xml, state.exportXml())
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)
        state.newDocument()
        assertTrue(state.documentWritable)
        state.undo()
        assertFalse(state.documentWritable)
        assertEquals(xml, state.exportXml())
        assertTrue(state.document.components.isNotEmpty())
    }

    @Test
    fun rejectedInitialXmlIsPreservedExactlyInsteadOfExportingADemo() {
        val state = EditorState(original)
        assertFalse(state.documentWritable)
        assertTrue(state.document.components.isEmpty())
        state.place(ComponentKind.NOT)
        state.tap(GridPoint(100, 100))
        assertFalse(state.insertComponent(ComponentKind.INPUT, GridPoint(0, 0)))
        assertFalse(state.createCircuit("should-not-exist"))
        state.applyProject(CircuitProject(listOf(DemoCircuits.andGate())))
        assertEquals(original, state.exportXml())
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)
    }

    @Test
    fun explicitNewUnlocksEditingAndUndoRestoresTheRejectedOriginal() {
        val state = EditorState(original)
        state.newDocument()
        assertTrue(state.documentWritable)
        assertTrue(state.exportXml().startsWith("<?xml"))
        assertTrue(state.canUndo)
        state.undo()
        assertFalse(state.documentWritable)
        assertEquals(original, state.exportXml())
        state.redo()
        assertTrue(state.documentWritable)
    }

    @Test
    fun rejectedReplacementKeepsRawOriginalUntilAValidImportIsAccepted() {
        val state = EditorState(original)
        assertFalse(state.importXml("<still-invalid/>"))
        assertEquals(original, state.exportXml())
        assertFalse(state.documentWritable)
        assertTrue(state.importXml(CircCodec.write(DemoCircuits.andGate())))
        assertTrue(state.documentWritable)
        state.undo()
        assertFalse(state.documentWritable)
        assertEquals(original, state.exportXml())
    }
}
