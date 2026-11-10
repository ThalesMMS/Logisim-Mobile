@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoPermissionError
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSFileWriteNoPermissionError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToURL

class IosDocumentErrorBoundaryTest {
    @Test
    fun cocoaMissingCodesKeepTypedFilenameAndResolveInBothLanguages() {
        assertEquals(4L, NSFileNoSuchFileError)
        assertEquals(260L, NSFileReadNoSuchFileError)
        for (code in listOf(NSFileNoSuchFileError, NSFileReadNoSuchFileError)) {
            val error = providerError(NSCocoaErrorDomain, code, "Synthetic provider description for code $code")
            val notice = iosDocumentProviderNotice(error, "projeto.circ", fallback)
            assertMissing(notice, "projeto.circ")
        }
    }

    @Test
    fun bracesAndUnicodeInFilenamesRemainLiteralUserData() {
        val name = "ação {name} {reason} 漢字 🧪.circ"
        val error = providerError(NSCocoaErrorDomain, NSFileReadNoSuchFileError, "Synthetic missing-file error")
        assertMissing(iosDocumentProviderNotice(error, name, fallback), name)
    }

    @Test
    fun missingCodeInAnotherDomainKeepsTheProviderDescriptionInGenericContext() {
        for (domain in listOf(NSPOSIXErrorDomain, "dev.logisim.tests.provider", "NSCOCOAErrorDomain")) {
            for (code in listOf(NSFileNoSuchFileError, NSFileReadNoSuchFileError)) {
                val error = providerError(domain, code, "Provider $code: ação negada {name} 漢字")
                assertContextualDescription(iosDocumentProviderNotice(error, "project.circ", fallback), "project.circ", error)
            }
        }
    }

    @Test
    fun cocoaPermissionErrorsKeepGenericContextWithoutTextMatching() {
        for (code in listOf(NSFileReadNoPermissionError, NSFileWriteNoPermissionError)) {
            val error = providerError(NSCocoaErrorDomain, code, "File does not exist: provider permission detail {name}")
            assertContextualDescription(iosDocumentProviderNotice(error, "project.circ", fallback), "project.circ", error)
        }
    }

    @Test
    fun genericContextKeepsBracesAndUnicodeInFilenameAndReasonLiteral() {
        val name = "ação {reason} {name} 漢字 🧪.circ"
        val reason = "Provider detail {name} {reason}: ação negada 漢字 🧪"
        val error = providerError("dev.logisim.tests.provider", NSFileReadNoSuchFileError, reason)
        assertContextualDescription(iosDocumentProviderNotice(error, name, fallback), name, error)
    }

    @Test
    fun noProviderErrorReturnsTheSameTypedFallback() {
        assertSame(fallback, iosDocumentProviderNotice(null, "project.circ", fallback))
    }

    @Test
    fun readingAnAbsentFileReportsTypedMissingWithoutCreatingIt() = withDirectory { directory ->
        val name = "ausente {name} ação.circ"
        val url = NSURL.fileURLWithPath("$directory/$name")
        val error = assertFailsWith<LocalizedStateException> {
            IosDocumentActions({ null }).readDocument(url, DocumentOpenMode.EDIT_IN_PLACE)
        }
        // Foundation exposes the file URL's decomposed filename, which is still literal user data.
        val expectedFilename = "ausente {name} ac\u0327a\u0303o.circ"
        assertEquals(expectedFilename, url.lastPathComponent)
        assertMissing(error.text, expectedFilename)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(assertNotNull(url.path)))
    }

    @Test
    fun savingADeletedBookmarkedFileReportsMissingInsteadOfConflictOrRecreation() = withDirectory { directory ->
        val name = "removido {name} 漢字.circ"
        val url = NSURL.fileURLWithPath("$directory/$name")
        assertTrue(NSString.create(string = "<project>original</project>")
            .writeToURL(url, true, NSUTF8StringEncoding, null))
        val actions = IosDocumentActions({ null })
        val reference = actions.readDocument(url, DocumentOpenMode.EDIT_IN_PLACE).reference
        assertEquals(name, reference.name)
        assertTrue(reference.id.isNotEmpty())
        assertTrue(NSFileManager.defaultManager.removeItemAtURL(url, null))

        val error = assertFailsWith<LocalizedStateException> {
            actions.replaceDocument(reference, "<project>local changes must not recreate the source</project>")
        }
        assertMissing(error.text, name)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(assertNotNull(url.path)),
            "Saving a missing external document must preserve its absence")
    }

    private fun assertMissing(notice: LocalizedText, name: String) {
        assertEquals("ios_document_missing", notice.key)
        assertEquals(mapOf("name" to name), notice.arguments)
        assertEquals("The file $name no longer exists. Open it again from Files or export a new copy.",
            notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O arquivo $name não existe mais. Abra-o novamente pelo Files ou exporte uma nova cópia.",
            notice.resolve(AppLanguage.PORTUGUESE))
    }

    private fun assertContextualDescription(notice: LocalizedText, name: String, error: NSError) {
        assertEquals("ios_document_access_failed", notice.key)
        assertEquals(mapOf("name" to name), notice.arguments)
        assertEquals(mapOf("reason" to LocalizedText.literal(error.localizedDescription)), notice.localizedArguments)
        assertEquals("Could not access $name: ${error.localizedDescription}", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível acessar $name: ${error.localizedDescription}", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals(error.localizedDescription, notice.localizedArguments.getValue("reason").resolve(AppLanguage.ENGLISH))
        assertEquals(error.localizedDescription, notice.localizedArguments.getValue("reason").resolve(AppLanguage.PORTUGUESE))
    }

    private fun providerError(domain: String?, code: Long, description: String): NSError =
        NSError(domain, code, mapOf(NSLocalizedDescriptionKey to description)).also {
            assertEquals(description, it.localizedDescription)
        }

    private fun withDirectory(action: (String) -> Unit) {
        val cache = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val directory = "$cache/LogisimDocumentBoundaryTest-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        try { action(directory) } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    companion object {
        private val fallback = message("ios_provider_unavailable", "name" to "fallback.circ")
    }
}
