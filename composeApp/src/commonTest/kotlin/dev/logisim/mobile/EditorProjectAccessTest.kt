package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.DemoCircuits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorProjectAccessTest {
    @Test
    fun aRejectedOriginalIsDistinguishedFromAParsedReadOnlyProject() {
        val original = "<unsupported keep=\"original\"/>"
        val rejected = EditorState(original)
        val supported = EditorState(CircCodec.write(DemoCircuits.andGate()), initialReadOnly = true)
        assertEquals(EditorProjectAccess.PRESERVED_RAW, rejected.projectAccess)
        assertEquals(original, rejected.exportXml())
        assertEquals(EditorProjectAccess.READ_ONLY, supported.projectAccess)
        assertTrue(supported.document.components.isNotEmpty())
    }

    @Test
    fun undoAndRedoPreserveWhetherTheSourceHasAUsableGraph() {
        val state = EditorState("<unsupported/>")
        state.newDocument()
        assertEquals(EditorProjectAccess.EDITABLE, state.projectAccess)
        state.undo()
        assertEquals(EditorProjectAccess.PRESERVED_RAW, state.projectAccess)
        state.redo()
        assertEquals(EditorProjectAccess.EDITABLE, state.projectAccess)
    }

    @Test
    fun undoToAParsedReadOnlyProjectDoesNotMisclassifyItsGraphAsMissing() {
        val state = EditorState(CircCodec.write(DemoCircuits.andGate()), initialReadOnly = true)
        state.newDocument()
        state.undo()
        assertEquals(EditorProjectAccess.READ_ONLY, state.projectAccess)
        assertTrue(state.projectAccess.graphAvailable)
    }
}
