package dev.logisim.mobile

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C145-R1: text pasted in the Project XML dialog is an imported project, not a file that was opened. */
class ImportStatusWordingTest {
    private val versionDefaultsXml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"><a name="output" val="false"/></comp></circuit></project>"""
    private val blank = """<project source="4.1.0" version="1.0"><main name="main"/><circuit name="main"/></project>"""

    @Test
    fun pastedProjectXmlIsAnImportedProjectWithItsAdjustments() {
        val state = EditorState(blank)
        assertTrue(state.importXml(versionDefaultsXml, message(Messages.ProjectXmlTitle)))
        val count = assertNotNull(state.importReport).migrations.size.toLong()
        assertTrue(count > 0)
        val english = state.statusText.resolve(AppLanguage.ENGLISH)
        val portuguese = state.statusText.resolve(AppLanguage.PORTUGUESE)
        assertEquals(LocalizedText("import_report_status_imported", quantity = count).resolve(AppLanguage.ENGLISH), english)
        assertFalse("File" in english || "opened" in english, english)
        assertFalse("Arquivo" in portuguese || "aberto" in portuguese, portuguese)
        assertTrue(state.importReportStatusShown, "Details still explains the adjustments")
    }

    // C145-R1 in the workspace flow: the pasted text opens a new session, whose editor reports the report's origin.
    @Test
    fun aWorkspaceSessionOpenedFromPastedXmlSaysItWasImported() {
        fun openInWorkspace(xml: String): EditorState {
            val state = EditorState(blank)
            var opened: Pair<String, EditorImportReport?>? = null
            state.openWorkspaceSession = { _, text, report -> opened = text to report; true }
            assertTrue(state.importXml(xml, message(Messages.ProjectXmlTitle)))
            val (text, report) = assertNotNull(opened)
            // As App mounts the new session: the editor, then reportOpened with the report's origin.
            return EditorState(text, initialImportReport = report).also { session ->
                assertNotNull(report?.origin).let(session::reportOpened)
            }
        }
        val adjusted = openInWorkspace(versionDefaultsXml)
        val count = assertNotNull(adjusted.importReport).migrations.size.toLong()
        assertEquals(LocalizedText("import_report_status_imported", quantity = count).resolve(AppLanguage.ENGLISH),
            adjusted.statusText.resolve(AppLanguage.ENGLISH))
        assertTrue(adjusted.importReportStatusShown)
        // Without adjustments it is "1 circuit imported", as outside a workspace, not "File Project XML opened."
        val plain = openInWorkspace(blank)
        assertEquals(LocalizedText(Messages.StatusProjectImported, quantity = 1).resolve(AppLanguage.ENGLISH),
            plain.statusText.resolve(AppLanguage.ENGLISH))
        // A file keeps its name.
        val file = EditorState(versionDefaultsXml, initialImportReport = adjusted.importReport?.copy(origin = LocalizedText.literal("old.circ")))
        file.reportOpened(LocalizedText.literal("old.circ"))
        assertEquals(ImportReportFileStatusKey, file.statusText.key)
    }

    // C145-R3: the saved instance state records the announcement, so a recreated editor does not say it again.
    @Test
    fun anAnnouncementIsNotRepeatedAfterRecreation() {
        val key = ImportReportDismissals.key(dev.logisim.mobile.core.WorkspaceProjectId("p1"), versionDefaultsXml)
        val dismissals = ImportReportDismissals()
        val editor = EditorState(versionDefaultsXml)
        assertTrue(editor.importReportStatusShown)
        dismissals.announced(key)
        dismissals.visibilityChanged(key, hasReport = true, visible = editor.importReportVisible)
        val saved = with(ImportReportDismissals.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(dismissals) }
        val restored = ImportReportDismissals.Saver.restore(assertNotNull(saved))!!
        assertTrue(restored.isAnnounced(key))

        val recreated = EditorState(versionDefaultsXml)
        assertTrue(recreated.importReportStatusShown, "without the record the rebuilt editor announces again")
        restored.restore(key, recreated)
        assertFalse(recreated.importReportStatusShown)
        assertFalse(recreated.statusText.key in ImportReportStatusKeys, recreated.statusText.resolve(AppLanguage.ENGLISH))
        assertNotNull(recreated.importReport, "the report stays in the File menu")
        assertFalse(recreated.importReportVisible)
        // Another project was never announced.
        val other = EditorState(versionDefaultsXml)
        restored.restore(ImportReportDismissals.key(dev.logisim.mobile.core.WorkspaceProjectId("p2"), versionDefaultsXml), other)
        assertTrue(other.importReportStatusShown)
    }

    // C145-R3: a file's adjustments give way to "Opened NAME", as when its report has nothing to announce.
    @Test
    fun aForgottenFileAnnouncementSaysTheFileWasOpened() {
        val state = EditorState(blank)
        assertTrue(state.importXml(versionDefaultsXml, LocalizedText.literal("old.circ")))
        state.forgetImportAnnouncement()
        assertEquals(message(Messages.StatusFileOpened, "name" to LocalizedText.literal("old.circ")).resolve(AppLanguage.ENGLISH),
            state.statusText.resolve(AppLanguage.ENGLISH))
    }

    // W3-03: a recreated window forgets a pasted import's adjustments, but still says the project was imported.
    @Test
    fun aForgottenPastedImportAnnouncementSaysTheProjectWasImported() {
        val state = EditorState(blank)
        assertTrue(state.importXml(versionDefaultsXml, message(Messages.ProjectXmlTitle)))
        assertEquals(ImportReportImportedStatusKey, state.statusText.key)
        state.forgetImportAnnouncement()
        assertEquals(LocalizedText(Messages.StatusProjectImported, quantity = state.project.circuits.size.toLong())
            .resolve(AppLanguage.ENGLISH), state.statusText.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun anImportedFileKeepsItsFileWording() {
        val state = EditorState(blank)
        assertTrue(state.importXml(versionDefaultsXml, LocalizedText.literal("old.circ")))
        val count = assertNotNull(state.importReport).migrations.size.toLong()
        assertEquals(LocalizedText("import_report_status_file", quantity = count,
            localizedArguments = mapOf("name" to LocalizedText.literal("old.circ"))).resolve(AppLanguage.ENGLISH),
            state.statusText.resolve(AppLanguage.ENGLISH))
    }
}
