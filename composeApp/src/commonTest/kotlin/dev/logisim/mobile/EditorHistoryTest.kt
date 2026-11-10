package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorHistoryTest {
    @Test
    fun clearingBothHistoryStacksPreservesTheCurrentProjectAndSimulation() {
        val state = EditorState(null)
        assertTrue(state.createCircuit("first"))
        assertTrue(state.createCircuit("second"))
        assertTrue(state.createCircuit("third"))
        state.undo()
        assertTrue(state.canUndo)
        assertTrue(state.canRedo)

        val xml = state.exportXml()
        val project = state.project
        val simulation = state.rootSimulation
        val revision = state.revision
        state.clearHistory()

        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
        state.undo()
        state.redo()
        assertSame(project, state.project)
        assertSame(simulation, state.rootSimulation)
        assertEquals(revision, state.revision)
        assertEquals(xml, state.exportXml())
    }
}
