package dev.logisim.mobile

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedStateException
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDocumentLocalizationTest {
    @Test
    fun queuedNativeFailuresFollowThePresentationLanguage() {
        val result = DocumentOpenResult.Failure(AndroidDocumentStore.notice(SecurityException(), "open"))
        assertEquals("android_doc_revoked", result.notice.key)
        assertEquals("File access was revoked or is read-only. Open it again or use Save As.",
            result.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("O acesso ao arquivo foi revogado ou é somente leitura. Abra-o novamente ou use Salvar como.",
            result.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun providerDetailsRemainLiteralWhileTheActionAndWrapperTranslate() {
        val detail = "Provider {action} → שלום"
        val notice = AndroidDocumentStore.notice(IllegalStateException(detail), "open")
        assertEquals("Could not open the file: $detail.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível abrir o arquivo: $detail.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun localValidationKeepsItsSpecificDiagnosticIdentity() {
        val original = androidDocumentNotice("android_doc_too_large")
        val converted = AndroidDocumentStore.notice(LocalizedStateException(original), "export")
        assertEquals(original, converted)
        assertEquals("android_doc_too_large", DocumentWriteResult.Failure(converted).notice.key)
    }
}
