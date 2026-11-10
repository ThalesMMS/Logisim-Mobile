package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodecLocalizationTest {
    @Test
    fun malformedXmlRetainsAnIdentityAndArgumentsAcrossLanguages() {
        val failure = assertIs<CircProjectImportResult.Failure>(
            CircCodec.readProject("<project><circuit name=\"main\"></project>"))
        val notice = failure.notices.single()
        assertEquals(Messages.CodecXmlClosingInvalid, notice.key)
        assertEquals("Incorrect XML closing element: project, expected circuit.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Fechamento XML incorreto: project, esperado circuit.", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(listOf(notice.resolve(AppLanguage.PORTUGUESE)), failure.diagnostics)
    }

    @Test
    fun unresolvedLibrariesPreserveExternalPathsAsLiteralArguments() {
        val path = "library/{name} → שלום.circ"
        val xml = "<project><lib name=\"1\" desc=\"file#$path\"/><circuit name=\"main\"/></project>"
        val failure = assertIs<CircLibraryImportResult.Failure>(
            CircLibraryCodec.read(xml, "main.circ", CircuitLibraryResolver { _, _ -> null }))
        val notice = failure.notices.single()
        assertEquals(Messages.CodecLibraryReferenceMissing, notice.key)
        assertEquals("Library is missing: $path, referenced by main.circ.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Biblioteca ausente: $path, referenciada por main.circ.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun archiveFailuresAreTranslatedAfterParsingHasFinished() {
        val failure = assertIs<CircBundleImportResult.Failure>(CircBundleCodec.read(byteArrayOf(1, 2)))
        assertEquals(Messages.CodecZipDirectoryMissing, failure.notices.single().key)
        assertEquals("ZIP directory was not found.", failure.notices.single().resolve(AppLanguage.ENGLISH))
        assertEquals("Diretório ZIP não encontrado.", failure.notices.single().resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun migrationDescriptionsKeepRulesAndXmlIndependentOfPresentation() {
        val xml = """<project source="3.6.1"><lib name="0" desc="#Memory"/><circuit name="main"><comp lib="0" name="ROM" loc="(0,0)"/></circuit></project>"""
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))
        val migration = imported.migrations.single()
        assertEquals(Messages.CodecMigrationImplicitAttributes, migration.notice.key)
        assertTrue(migration.notice.resolve(AppLanguage.ENGLISH).contains("source version 3.6.1"))
        assertTrue(migration.notice.resolve(AppLanguage.PORTUGUESE).contains("origem 3.6.1"))
        val exported = CircCodec.writeProject(imported.project)
        assertTrue(exported.contains("source=\"3.6.1\""))
        assertTrue(!exported.contains("appearance"))
    }
}
