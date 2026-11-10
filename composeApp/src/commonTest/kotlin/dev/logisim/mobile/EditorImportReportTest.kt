package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorImportReportTest {
    @Test
    fun successfulImportKeepsVersionOriginAndAllTypedMigrationEntries() {
        val xml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"><a name="output" val="false"/></comp></circuit></project>"""
        val parsed = CircCodec.readProject(xml) as CircProjectImportResult.Success
        val state = EditorState(null)
        var replacement: EditorProjectReplacement? = null
        state.onWorkspaceProjectReplaced = { replacement = it }
        assertTrue(state.importXml(xml, LocalizedText.literal("old-project.circ")))
        val report = assertNotNull(state.importReport)
        assertEquals("2.7.1", report.sourceVersion)
        assertEquals(parsed.migrations, report.migrations)
        assertEquals("old-project.circ", report.origin?.resolve(AppLanguage.ENGLISH))
        assertTrue(state.importReportVisible)
        assertEquals(report, assertNotNull(replacement).importReport)
        state.dismissImportReport()
        assertFalse(state.importReportVisible)
        state.showImportReport()
        assertTrue(state.importReportVisible)
    }

    @Test
    fun aWorkspaceRemountKeepsTheOriginalReportAfterDefaultsWereSerialized() {
        val state = EditorState(null)
        val report = EditorImportReport("2.7.1", 1,
            listOf(CircMigration("old-pin-mode", state.document.name, state.document.components.first().id,
                LocalizedText.literal("Old pin mode was migrated."))), LocalizedText.literal("source.circ"))
        val remounted = EditorState(state.exportXml(), initialImportReport = report)
        assertEquals(report, remounted.importReport)
        assertTrue(remounted.importReportVisible)
        val revisited = EditorState(state.exportXml(), initialImportReport = report.copy(showOnOpen = false))
        assertFalse(revisited.importReportVisible)
        assertEquals(report.migrations, revisited.importReport?.migrations)
    }

    @Test
    fun rejectedImportKeepsThePreviouslyAcceptedReport() {
        val state = EditorState(null)
        assertTrue(state.importXml(state.exportXml()))
        val accepted = state.importReport
        assertFalse(state.importXml("<invalid/>"))
        assertEquals(accepted, state.importReport)
        assertNotNull(state.importErrorText)
    }
}
