package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.DemoCircuits
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorInteractionGateTest {
    @Test
    fun aPendingCatalogTransactionCannotChangeTheDocumentOrHistory() {
        val state = EditorState(null)
        state.newDocument()
        assertTrue(state.insertComponent(ComponentKind.INPUT, GridPoint(100, 100)))
        val xml = state.exportXml()
        val revision = state.revision
        val identity = state.documentIdentity
        state.setInteractionEnabled(false)

        assertFalse(state.insertComponent(ComponentKind.NOT, GridPoint(200, 100)))
        state.selectAll()
        state.removeSelected()
        state.newDocument()
        state.loadDemo()
        state.loadSap1()
        assertFalse(state.importXml(CircCodec.write(DemoCircuits.andGate())))
        state.undo()
        state.redo()
        assertEquals(xml, state.exportXml())
        assertEquals(revision, state.revision)
        assertEquals(identity, state.documentIdentity)
        assertTrue(state.documentWritable)

        state.setInteractionEnabled(true)
        state.undo()
        assertTrue(state.document.components.isEmpty())
        state.redo()
        assertEquals(xml, state.exportXml())
    }

    @Test
    fun cancellingATransactionDoesNotUnlockARejectedOriginal() {
        val original = "<rejected keep=\"original\"/>"
        val state = EditorState(original)
        state.setInteractionEnabled(false)
        state.newDocument()
        assertEquals(original, state.exportXml())
        state.setInteractionEnabled(true)
        assertFalse(state.documentWritable)
        assertFalse(state.insertComponent(ComponentKind.INPUT, GridPoint(0, 0)))
        assertEquals(original, state.exportXml())
        state.newDocument()
        assertTrue(state.documentWritable)
    }
}
