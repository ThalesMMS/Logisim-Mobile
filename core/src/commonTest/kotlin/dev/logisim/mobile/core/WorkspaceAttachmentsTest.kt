package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkspaceAttachmentsTest {
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val header = "LOGISIM-WORKSPACE-ATTACHMENT\n1\n"

    @Test
    fun attachmentCopiesTheInputAndEveryReturnedByteArray() {
        val original = "abc".encodeToByteArray()
        val attachment = WorkspaceAttachment("input.bin", "application/octet-stream", original)
        val reference = WorkspaceAttachmentBlobs.references(mapOf("input" to attachment)).getValue("input")
        val hashCode = attachment.hashCode()
        original[0] = 0
        attachment.bytes[1] = 0
        assertContentEquals("abc".encodeToByteArray(), attachment.bytes)
        assertEquals(3L, attachment.size)
        assertEquals(hashCode, attachment.hashCode())
        assertEquals(abcHash, reference.sha256)
        assertEquals(WorkspaceAttachment("input.bin", "application/octet-stream", "abc".encodeToByteArray()), attachment)
    }

    @Test
    fun referencesAreSortedSnapshotsWithLiteralSizeAndHash() {
        val attachments = linkedMapOf(
            "second" to WorkspaceAttachment("second.bin", "application/octet-stream", byteArrayOf()),
            "first" to WorkspaceAttachment("first.txt", "text/plain", "abc".encodeToByteArray()),
        )
        val references = WorkspaceAttachmentBlobs.references(attachments)
        attachments.clear()
        assertEquals(listOf("first", "second"), references.keys.toList())
        assertEquals(WorkspaceAttachmentReference("first.txt", "text/plain", 3, abcHash), references["first"])
        assertEquals(WorkspaceAttachmentReference("second.bin", "application/octet-stream", 0, emptyHash), references["second"])
        WorkspaceAttachmentBlobs.validateReferences(references)
    }

    @Test
    fun theBlobContainsOnlyBytesAndReusesItsStorageNameAcrossMetadataChanges() {
        val first = WorkspaceAttachment("first.bin", "application/octet-stream", "abc".encodeToByteArray())
        val second = WorkspaceAttachment("renamed.txt", "text/plain", "abc".encodeToByteArray())
        val references = WorkspaceAttachmentBlobs.references(mapOf("first" to first, "second" to second))
        val encoded = WorkspaceAttachmentBlobs.encode(first)
        assertEquals(header + "YWJj", encoded)
        assertEquals(encoded, WorkspaceAttachmentBlobs.encode(second))
        assertEquals("attachment-$abcHash.workspace", WorkspaceAttachmentBlobs.storageName(references.getValue("first")))
        assertEquals(WorkspaceAttachmentBlobs.storageName(references.getValue("first")),
            WorkspaceAttachmentBlobs.storageName(references.getValue("second")))
        assertEquals(second, WorkspaceAttachmentBlobs.decode(encoded, references.getValue("second")))
    }

    @Test
    fun emptyAttachmentsRoundTripWithTheEmptySha256Digest() {
        val attachment = WorkspaceAttachment("empty.bin", "application/octet-stream", byteArrayOf())
        val reference = WorkspaceAttachmentReference(attachment.name, attachment.mimeType, 0, emptyHash)
        assertEquals(header, WorkspaceAttachmentBlobs.encode(attachment))
        val decoded = assertNotNull(WorkspaceAttachmentBlobs.decode(header, reference))
        assertEquals(attachment, decoded)
        assertContentEquals(byteArrayOf(), decoded.bytes)
    }

    @Test
    fun decodingRejectsUnknownVersionsMalformedOrNoncanonicalBase64AndChangedBytes() {
        val attachment = WorkspaceAttachment("zero.bin", "application/octet-stream", byteArrayOf(0))
        val reference = WorkspaceAttachmentBlobs.references(mapOf("zero" to attachment)).getValue("zero")
        val encoded = WorkspaceAttachmentBlobs.encode(attachment)
        assertEquals(header + "AA==", encoded)
        for (raw in listOf(encoded.replace("\n1\n", "\n2\n"), encoded.replace("LOGISIM", "UNKNOWN"),
            header + "AA", header + "AB==", header + "AA==\n", header + "AA=A", header + "!!!!", header + "AQ==")) {
            assertNull(WorkspaceAttachmentBlobs.decode(raw, reference), raw)
        }
    }

    @Test
    fun decodingRejectsSizeDigestAndDescriptorMismatches() {
        val reference = WorkspaceAttachmentReference("input.bin", "application/octet-stream", 3, abcHash)
        for (invalid in listOf(reference.copy(size = -1), reference.copy(size = 2), reference.copy(size = Long.MAX_VALUE),
            reference.copy(size = WorkspaceAttachmentBlobs.MAX_BYTES.toLong() + 1), reference.copy(sha256 = "0".repeat(64)),
            reference.copy(sha256 = abcHash.uppercase()), reference.copy(sha256 = "../$abcHash"),
            reference.copy(name = ""), reference.copy(mimeType = "text/plain\n"))) {
            assertNull(WorkspaceAttachmentBlobs.decode(header + "YWJj", invalid))
        }
        assertNull(WorkspaceAttachmentBlobs.decode(header + "A".repeat(4096), reference.copy(size = 0)))
    }

    @Test
    fun countAndTotalSizeLimitsApplyToAttachmentsAndSavedReferences() {
        val empty = WorkspaceAttachment("empty.bin", "application/octet-stream", byteArrayOf())
        val maximumEntries = (0 until 32).associate { "attachment-$it" to empty }
        assertEquals(32, WorkspaceAttachmentBlobs.references(maximumEntries).size)
        assertLimit { WorkspaceAttachmentBlobs.references(maximumEntries + ("one-more" to empty)) }
        val reference = WorkspaceAttachmentReference("input.bin", "application/octet-stream", 0, emptyHash)
        WorkspaceAttachmentBlobs.validateReferences(mapOf("limit" to reference.copy(size = 64L * 1024 * 1024)))
        assertLimit { WorkspaceAttachmentBlobs.validateReferences((0 until 33).associate { "attachment-$it" to reference }) }
        assertLimit { WorkspaceAttachmentBlobs.validateReferences(mapOf(
            "large" to reference.copy(size = 64L * 1024 * 1024), "extra" to reference.copy(size = 1))) }
        val repeated = WorkspaceAttachment("same.bin", "application/octet-stream", ByteArray(4 * 1024 * 1024))
        assertLimit { WorkspaceAttachmentBlobs.references((0 until 17).associate { "attachment-$it" to repeated }) }
    }

    @Test
    fun invalidKeysAndMetadataCannotBeWrittenOrUsedAsStorageNames() {
        val attachment = WorkspaceAttachment("input.bin", "application/octet-stream", byteArrayOf())
        val reference = WorkspaceAttachmentReference(attachment.name, attachment.mimeType, 0, emptyHash)
        WorkspaceAttachmentBlobs.references(mapOf("x".repeat(200) to attachment))
        for (key in listOf("", " ", "x".repeat(201), "a\nb", "a\u007fb", "a\u0085b")) {
            assertInvalid { WorkspaceAttachmentBlobs.references(mapOf(key to attachment)) }
            assertInvalid { WorkspaceAttachmentBlobs.validateReferences(mapOf(key to reference)) }
        }
        for (name in listOf("", " ", "x".repeat(4097), "name\n")) {
            assertInvalid { WorkspaceAttachment(name, "application/octet-stream", byteArrayOf()) }
            assertInvalid { WorkspaceAttachmentBlobs.validateReferences(mapOf("input" to reference.copy(name = name))) }
        }
        for (mimeType in listOf("", " ", "x".repeat(201), "text/plain\r")) {
            assertInvalid { WorkspaceAttachment("input.bin", mimeType, byteArrayOf()) }
            assertInvalid { WorkspaceAttachmentBlobs.validateReferences(mapOf("input" to reference.copy(mimeType = mimeType))) }
        }
        for (hash in listOf("../payload", "f".repeat(63), "F".repeat(64), "g".repeat(64))) {
            assertInvalid { WorkspaceAttachmentBlobs.storageName(reference.copy(sha256 = hash)) }
            assertInvalid { WorkspaceAttachmentBlobs.validateReferences(mapOf("input" to reference.copy(sha256 = hash))) }
        }
        assertInvalid { WorkspaceAttachmentBlobs.validateReferences(mapOf("input" to reference.copy(size = Long.MAX_VALUE))) }
    }

    private fun assertLimit(action: () -> Unit) {
        assertEquals("workspace_attachment_limit", assertFailsWith<LocalizedArgumentException>(block = action).text.key)
    }

    private fun assertInvalid(action: () -> Unit) {
        assertEquals("workspace_attachment_invalid", assertFailsWith<LocalizedArgumentException>(block = action).text.key)
    }
}
