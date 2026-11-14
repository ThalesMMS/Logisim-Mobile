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
        assertEquals(report, assertNotNull(replacement).importReport)
        // Version defaults only record how the file was read: the status line says so and offers Details (C145).
        assertEquals(setOf("version-defaults"), report.migrations.map { it.rule }.toSet())
        assertFalse(state.importReportVisible)
        assertFalse(report.showOnOpen)
        assertTrue(report.announceOnOpen)
        assertEquals(importReportStatus(report, LocalizedText.literal("old-project.circ")), state.statusText)
        assertTrue(state.importReportStatusShown)
        state.showImportReport()
        assertTrue(state.importReportVisible)
        assertFalse(state.importReportStatusShown)
        state.dismissImportReport()
        assertFalse(state.importReportVisible)
        assertEquals(report.consumed(), state.importReport)
        assertTrue(state.importReportStatusShown, "Details stays beside the status it explains")
        state.showImportReport()
        assertTrue(state.importReportVisible)
    }

    private fun migration(rule: String) = CircMigration(rule, "main", null, LocalizedText.literal(rule))
    private fun reportFor(vararg rules: String): EditorImportReport {
        val project = (CircCodec.readProject(EditorState(null).exportXml()) as CircProjectImportResult.Success).project
        return EditorImportReport.from(CircProjectImportResult.Success(project, rules.map(::migration)))
    }

    // C145: only adjustments that keep what the file meant stay out of the way; everything else opens the report.
    @Test
    fun informationalMigrationsAnnounceAndTheOthersOpenTheReport() {
        for (rules in listOf(listOf("version-defaults"), listOf("simulation-noise-preserved"),
            listOf("version-defaults", "simulation-noise-preserved"))) {
            val report = reportFor(*rules.toTypedArray())
            assertTrue(report.informational, "$rules")
            assertFalse(report.showOnOpen, "$rules")
            assertTrue(report.announceOnOpen, "$rules")
        }
        for (rule in listOf("floating-point-library", "legacy-project-converted", "evolution-label-renamed",
            "evolution-circuit-renamed", "a-rule-from-a-newer-build")) {
            for (report in listOf(reportFor(rule), reportFor("version-defaults", rule))) {
                assertFalse(report.informational, rule)
                assertTrue(report.showOnOpen, rule)
                assertFalse(report.announceOnOpen, rule)
            }
        }
        val clean = reportFor()
        assertFalse(clean.showOnOpen)
        assertFalse(clean.announceOnOpen)
        val consumed = reportFor("version-defaults").consumed()
        assertFalse(consumed.showOnOpen || consumed.announceOnOpen)
        assertEquals(reportFor("version-defaults").migrations, consumed.migrations, "The migration data is kept")
    }

    // C145: each bundled fixture that only needs version defaults opens without the report; the codec's migrations and
    // the XML it preserved are the same as before.
    @Test
    fun aVersionDefaultsFileOpensWithAStatusAndKeepsItsXml() {
        val xml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><lib name="1" desc="#Gates"/><main name="main"/><circuit name="main"><comp lib="1" name="AND Gate" loc="(200,120)"/><comp lib="0" name="Pin" loc="(100,100)"/></circuit></project>"""
        val parsed = CircCodec.readProject(xml) as CircProjectImportResult.Success
        val state = EditorState(xml)
        val report = assertNotNull(state.importReport)
        assertEquals(parsed.migrations, report.migrations)
        assertFalse(state.importReportVisible)
        assertEquals(LocalizedText("import_report_status", quantity = parsed.migrations.size.toLong()), state.statusText)
        assertEquals("Opened with ${parsed.migrations.size} compatibility adjustments.", state.statusText.resolve(AppLanguage.ENGLISH))
        assertEquals(CircCodec.writeProject(parsed.project), state.exportXml())
    }

    // C145: a workspace mount restores the saved view after the editor opened; that is not news, so the adjustments
    // status and its Details stay. A real circuit change still replaces them.
    @Test
    fun restoringTheWorkspaceViewKeepsTheAdjustmentsStatus() {
        val xml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"/></circuit></project>"""
        val state = EditorState(xml)
        val status = state.statusText
        assertEquals("import_report_status", status.key)
        state.importViewState(WorkspaceViewState())
        assertEquals(status, state.statusText)
        assertTrue(state.importReportStatusShown)
        state.changeMode(EditorMode.SELECT)
        assertFalse(state.importReportStatusShown, "Details leaves with the status it explains")
        assertNotNull(state.importReport, "The File menu still opens the report")
    }

    // C145: the status names a file only for the report that file produced.
    @Test
    fun aFileStatusNamesTheFileOfItsOwnReport() {
        val xml = """<project source="2.7.1" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)"/></circuit></project>"""
        val state = EditorState(null)
        assertTrue(state.importXml(xml, LocalizedText.literal("a.circ")))
        state.reportOpened(LocalizedText.literal("a.circ"))
        assertEquals("import_report_status_file", state.statusText.key)
        assertTrue(state.statusText.resolve(AppLanguage.ENGLISH).startsWith("File a.circ opened with "), state.statusText.resolve(AppLanguage.ENGLISH))
        state.reportOpened(LocalizedText.literal("b.circ"))
        assertEquals(message(Messages.StatusFileOpened, "name" to LocalizedText.literal("b.circ")), state.statusText)
        assertFalse(state.importReportStatusShown)
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
