package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSessionControlTest {
    @Test
    fun pendingViewAndForegroundAreDeliveredWhenEditorAttaches() {
        val control = EditorSessionControl()
        val view = WorkspaceViewState(activeCircuitId = "child", selectedComponentIds = setOf("one"))
        control.setActive(false)
        control.setInteractionEnabled(false)
        assertFalse(control.isAttached)
        assertFalse(control.activationAllowed)
        control.importViewState(view)
        var received: WorkspaceViewState? = null
        var active = true
        var interactions = true
        var pauses = 0
        control.attach({ pauses++ }, { active = it }, { view }, { received = it }, setInteractionEnabled = { interactions = it })
        assertTrue(control.isAttached)
        assertFalse(active)
        assertFalse(interactions)
        control.setInteractionEnabled(true)
        assertTrue(interactions)
        assertEquals(view, received)
        assertEquals(view, control.exportViewState())
        control.pause()
        assertEquals(1, pauses)
        control.detach()
        assertFalse(control.isAttached)
        assertEquals(view, control.exportViewState())
        control.pause()
        assertEquals(1, pauses)
    }

    @Test
    fun rejectedDocumentValidityAndExactSourceSurviveDetach() {
        val raw = "<project><missing provider=\"removed\"/></project>"
        val control = EditorSessionControl()
        control.attach({}, {}, { WorkspaceViewState() }, {}, exportXml = { raw }, documentWritable = { false })
        assertEquals(raw, control.exportXml())
        assertEquals(false, control.documentWritable)
        control.detach()
        assertEquals(raw, control.exportXml())
        assertEquals(false, control.documentWritable)
        control.attach({}, {}, { WorkspaceViewState() }, {}, exportXml = { "<project/>" }, documentWritable = { true })
        assertTrue(control.documentWritable == true)
        control.detach()
        control.attach({}, {}, { WorkspaceViewState() }, {}, exportXml = { null })
        assertNull(control.exportXml())
    }

    @Test
    fun importReportSurvivesDetachAndAnActiveNullDoesNotReuseIt() {
        val report = EditorImportReport("2.7.1", 2, emptyList())
        val control = EditorSessionControl()
        control.attach({}, {}, { WorkspaceViewState() }, {}, exportImportReport = { report })
        assertEquals(report, control.exportImportReport())
        control.detach()
        assertEquals(report, control.exportImportReport())
        control.attach({}, {}, { WorkspaceViewState() }, {}, exportImportReport = { null })
        assertNull(control.exportImportReport())
    }

    @Test
    fun projectReplacementIsNotReportedForOrdinaryCircuitEdits() {
        val state = EditorState(null)
        val replacements = mutableListOf<EditorProjectReplacement>()
        state.onWorkspaceProjectReplaced = { replacements += it }
        state.insertComponent(dev.logisim.mobile.core.ComponentKind.NOT, dev.logisim.mobile.core.GridPoint(300, 300))
        assertEquals(0, replacements.size)
        val previousXml = state.exportXml()
        state.newDocument()
        assertEquals(1, replacements.size)
        assertEquals(previousXml, replacements.single().previousXml)
        assertEquals(0L, replacements.single().previousIdentity)
        assertEquals(state.exportXml(), replacements.single().xml)
        state.undo()
        assertEquals(2, replacements.size)
        assertEquals(state.exportXml(), replacements.last().xml)
    }
}
