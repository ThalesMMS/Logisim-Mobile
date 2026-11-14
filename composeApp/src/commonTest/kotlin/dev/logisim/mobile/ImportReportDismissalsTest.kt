package dev.logisim.mobile

import androidx.compose.runtime.saveable.SaverScope
import dev.logisim.mobile.core.WorkspaceProjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportReportDismissalsTest {
    // Converted from a pre-2.6.3 format, so its report opens by itself (C145).
    private val legacyXml = """<project source="2.0.3" version="1.0"><lib name="0" desc="#Base"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"/></circuit></project>"""
    private val versionDefaultsXml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"><a name="output" val="false"/></comp></circuit></project>"""
    private val key = ImportReportDismissals.key(WorkspaceProjectId("p1"), legacyXml)

    /** Mirrors activity recreation: the list goes through the saved-state Saver, the editor is rebuilt from XML. */
    private fun recreate(dismissals: ImportReportDismissals): ImportReportDismissals {
        val saved = with(ImportReportDismissals.Saver) { SaverScope { true }.save(dismissals) }
        // An empty list saves nothing; rememberSaveable then runs its initializer again.
        return saved?.let { ImportReportDismissals.Saver.restore(it) } ?: ImportReportDismissals()
    }

    @Test
    fun aClosedReportStaysClosedAfterRecreationAndRemainsInTheMenu() {
        val dismissals = ImportReportDismissals()
        val editor = EditorState(legacyXml)
        assertTrue(editor.importReportVisible, "a legacy file opens with its import report")
        dismissals.visibilityChanged(key, hasReport = true, visible = true)
        editor.dismissImportReport()
        dismissals.visibilityChanged(key, hasReport = editor.importReport != null, visible = editor.importReportVisible)

        val restored = recreate(dismissals)
        val recreated = EditorState(legacyXml)
        assertTrue(recreated.importReportVisible, "without the saved choice the rebuilt editor shows the report again")
        assertTrue(restored.restore(key, recreated))
        assertFalse(recreated.importReportVisible)
        val report = assertNotNull(recreated.importReport, "the report is still available from the menu")
        assertEquals(editor.importReport?.migrations, report.migrations)
        recreated.showImportReport()
        assertTrue(recreated.importReportVisible)
    }

    @Test
    fun anOpenReportStaysVisibleAfterRecreation() {
        val dismissals = ImportReportDismissals()
        dismissals.visibilityChanged(key, hasReport = true, visible = false)
        // Reopened from the menu before the configuration change.
        dismissals.visibilityChanged(key, hasReport = true, visible = true)
        val recreated = EditorState(legacyXml)
        assertFalse(recreate(dismissals).restore(key, recreated))
        assertTrue(recreated.importReportVisible)
    }

    @Test
    fun aReportThatDoesNotOpenByItselfStaysOpenWhenItWasOpen() {
        val dismissals = ImportReportDismissals()
        // A rebuilt editor whose report does not open on its own (no pending migrations), but is in the menu.
        val report = assertNotNull(EditorState(legacyXml).importReport).consumed()
        val edited = EditorState(legacyXml, initialImportReport = report)
        assertFalse(edited.importReportVisible)
        edited.showImportReport()
        dismissals.visibilityChanged(key, hasReport = true, visible = edited.importReportVisible)
        val recreated = EditorState(legacyXml, initialImportReport = report)
        assertFalse(recreated.importReportVisible)
        assertTrue(recreate(dismissals).restore(key, recreated))
        assertTrue(recreated.importReportVisible)
    }

    // C145: a report that only announced itself in the status line was never open, so recreation leaves it closed.
    @Test
    fun anInformationalReportStaysClosedAfterRecreation() {
        val dismissals = ImportReportDismissals()
        val editor = EditorState(versionDefaultsXml)
        assertFalse(editor.importReportVisible)
        assertTrue(editor.importReportStatusShown)
        dismissals.visibilityChanged(key, hasReport = true, visible = editor.importReportVisible)
        val recreated = EditorState(versionDefaultsXml)
        assertFalse(recreate(dismissals).restore(key, recreated))
        assertFalse(recreated.importReportVisible)
        assertNotNull(recreated.importReport, "still in the File menu")
    }

    @Test
    fun theChoiceIsScopedToItsProject() {
        val dismissals = ImportReportDismissals()
        dismissals.visibilityChanged(key, hasReport = true, visible = false)
        val other = ImportReportDismissals.key(WorkspaceProjectId("p2"), legacyXml)
        val recreated = EditorState(legacyXml)
        assertFalse(recreate(dismissals).restore(other, recreated))
        assertTrue(recreated.importReportVisible)
        assertEquals(ImportReportDismissals.key(null, legacyXml), ImportReportDismissals.key(null, legacyXml))
        assertTrue(ImportReportDismissals.key(null, legacyXml) != key)
    }
}
