package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Attachment contents cannot change after being included in a workspace snapshot. */
class WorkspaceAttachment(val name: String, val mimeType: String, bytes: ByteArray) {
    init {
        requireLocalized(validAttachmentMetadata(name, mimeType)) { invalidAttachment(name, name) }
        requireLocalized(bytes.size <= WorkspaceAttachmentBlobs.MAX_BYTES) { message("workspace_attachment_limit") }
    }

    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    val size: Long get() = content.size.toLong()
    internal val sha256: String by lazy { ExtensionDigest.sha256(content) }

    @OptIn(ExperimentalEncodingApi::class)
    internal fun encodedContent(): String = Base64.Default.encode(content)

    override fun equals(other: Any?): Boolean = this === other || other is WorkspaceAttachment &&
        name == other.name && mimeType == other.mimeType && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + mimeType.hashCode()) + content.contentHashCode()

    override fun toString(): String = "WorkspaceAttachment(name=$name, mimeType=$mimeType, bytes=$size)"
}

data class WorkspaceAttachmentReference(val name: String, val mimeType: String, val size: Long, val sha256: String)

sealed interface WorkspaceAttachmentReadResult {
    data class Success(val attachment: WorkspaceAttachment) : WorkspaceAttachmentReadResult
    data object Missing : WorkspaceAttachmentReadResult
    data class Failure(val notice: LocalizedText) : WorkspaceAttachmentReadResult {
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

@OptIn(ExperimentalEncodingApi::class)
internal object WorkspaceAttachmentBlobs {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_ATTACHMENTS = 32
    private const val HEADER = "LOGISIM-WORKSPACE-ATTACHMENT\n1\n"
    private val maxRawLength = HEADER.length.toLong() + ((MAX_BYTES.toLong() + 2) / 3) * 4
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun storageName(reference: WorkspaceAttachmentReference): String {
        requireLocalized(validReference(reference)) { invalidAttachment(reference.name, reference.name) }
        return "attachment-${reference.sha256}.workspace"
    }

    /** Names and MIME types belong to project descriptors, so equal bytes share one blob. */
    fun encode(attachment: WorkspaceAttachment): String = HEADER + attachment.encodedContent()

    fun decode(raw: String, reference: WorkspaceAttachmentReference): WorkspaceAttachment? {
        if (raw.length.toLong() > maxRawLength || !validReference(reference)) return null
        val encodedLength = ((reference.size + 2) / 3) * 4
        if (raw.length.toLong() != HEADER.length + encodedLength || !raw.startsWith(HEADER)) return null
        val encoded = raw.substring(HEADER.length)
        val bytes = try { Base64.Default.decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size.toLong() != reference.size || Base64.Default.encode(bytes) != encoded ||
            ExtensionDigest.sha256(bytes) != reference.sha256) return null
        return WorkspaceAttachment(reference.name, reference.mimeType, bytes)
    }

    fun references(attachments: Map<String, WorkspaceAttachment>): Map<String, WorkspaceAttachmentReference> {
        requireLocalized(attachments.size <= MAX_ATTACHMENTS) { message("workspace_attachment_limit") }
        var total = 0L
        for ((key, attachment) in attachments) {
            requireLocalized(validAttachmentKey(key)) { invalidAttachment(key, attachment.name) }
            total += attachment.size
            requireLocalized(total <= MAX_BYTES) { message("workspace_attachment_limit") }
        }
        return buildMap {
            for ((key, attachment) in attachments.entries.sortedBy { it.key }) {
                put(key, WorkspaceAttachmentReference(attachment.name, attachment.mimeType, attachment.size, attachment.sha256))
            }
        }
    }

    fun validateReferences(references: Map<String, WorkspaceAttachmentReference>) {
        requireLocalized(references.size <= MAX_ATTACHMENTS) { message("workspace_attachment_limit") }
        var total = 0L
        for ((key, reference) in references) {
            requireLocalized(validAttachmentKey(key) && validReference(reference)) { invalidAttachment(key, reference.name) }
            total += reference.size
            requireLocalized(total <= MAX_BYTES) { message("workspace_attachment_limit") }
        }
    }

    private fun validReference(reference: WorkspaceAttachmentReference): Boolean =
        validAttachmentMetadata(reference.name, reference.mimeType) && reference.size in 0..MAX_BYTES.toLong() &&
            sha256Pattern.matches(reference.sha256)
}

private fun validAttachmentKey(key: String): Boolean =
    key.length <= 200 && key.isNotBlank() && key.none(Char::isISOControl)

private fun validAttachmentMetadata(name: String, mimeType: String): Boolean =
    name.length <= 4096 && name.isNotBlank() && name.none(Char::isISOControl) &&
        mimeType.length <= 200 && mimeType.isNotBlank() && mimeType.none(Char::isISOControl)

private fun invalidAttachment(key: String, name: String): LocalizedText =
    message("workspace_attachment_invalid", "key" to key, "name" to name)
