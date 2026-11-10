package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidAtomicDocumentSaveTest {
    private val original = "original".encodeToByteArray()
    private val edited = "edited".encodeToByteArray()
    private val expected = AndroidDocumentStore.digest(original)
    private fun describe(bytes: ByteArray) = DocumentReference("content://test/document/a", "a.circ", true,
        AndroidDocumentStore.digest(bytes), DocumentOpenMode.EDIT_IN_PLACE)

    @Test fun aChangedOrUnversionedOriginalDoesNotReachTheProviderWrite() {
        for (revision in listOf(null, "stale")) {
            val result = verifiedAndroidDocumentSave(revision, original, edited,
                readBack = { error("Unexpected readback") }, replace = { error("Unexpected write") }, describe = ::describe)
            assertEquals(DocumentWriteResult.Conflict(describe(original)), result)
        }
    }

    @Test fun aRetryAfterAnUnacknowledgedCommitDoesNotRewriteTheFile() {
        val recovered = verifiedAndroidDocumentSave(expected, original, edited, readBack = { edited },
            replace = { error("Reply was lost after publication") }, describe = ::describe)
        assertEquals(DocumentWriteResult.Saved(describe(edited)), recovered)
        val retried = verifiedAndroidDocumentSave(expected, edited, edited, readBack = { error("Unexpected readback") },
            replace = { error("Already saved") }, describe = ::describe)
        assertEquals(recovered, retried)
    }

    @Test fun aProviderReplyCannotConfirmUnchangedOrConcurrentBytes() {
        val unchanged = verifiedAndroidDocumentSave(expected, original, edited, readBack = { original },
            replace = { "saved" }, describe = ::describe)
        assertEquals("android_doc_write_unconfirmed", assertIs<DocumentWriteResult.Failure>(unchanged).notice.key)
        val concurrent = "concurrent".encodeToByteArray()
        val conflict = verifiedAndroidDocumentSave(expected, original, edited, readBack = { concurrent },
            replace = { "conflict" }, describe = ::describe)
        assertEquals(DocumentWriteResult.Conflict(describe(concurrent)), conflict)
    }
}
