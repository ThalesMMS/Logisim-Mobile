package dev.logisim.mobile

import dev.logisim.mobile.core.CircXmlEncoding
import dev.logisim.mobile.core.localization.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** ContentResolver boundary; no filesystem path is accepted from another application. */
class AndroidDocumentStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun read(uri: Uri, mode: DocumentOpenMode, grantedFlags: Int = 0): DocumentContent {
        requireLocalized(uri.scheme == "content") { androidDocumentNotice("android_doc_content_uri") }
        val bytes = readBytes(uri)
        val text = CircXmlEncoding.decode(bytes)
        return DocumentContent(describe(uri, mode, grantedFlags, bytes), text)
    }

    fun persistPermission(uri: Uri, flags: Int) {
        val permissions = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (permissions == 0 || flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        try {
            resolver.takePersistableUriPermission(uri, permissions)
        } catch (_: SecurityException) {
            // Some share providers grant access for this session only.
        }
    }

    fun write(reference: DocumentReference, text: String): DocumentWriteResult {
        if (!reference.writable || reference.mode != DocumentOpenMode.EDIT_IN_PLACE) {
            return DocumentWriteResult.Failure(androidDocumentNotice("android_doc_read_only"))
        }
        val uri = Uri.parse(reference.id)
        return try {
            requireLocalized(uri.scheme == "content") { androidDocumentNotice("android_doc_invalid_reference") }
            checkLocalized(hasReadWriteGrant(uri)) { androidDocumentNotice("android_doc_revoked") }
            val bytes = text.toByteArray(Charsets.UTF_8)
            requireLocalized(bytes.size <= MAX_BYTES) { androidDocumentNotice("android_doc_too_large") }
            val original = readBytes(uri)
            val current = describe(uri, reference.mode, 0, original)
            checkLocalized(current.writable) { androidDocumentNotice("android_doc_save_as_required") }
            verifiedAndroidDocumentSave(reference.revision, original, bytes, readBack = { readBytes(uri) },
                replace = { expected -> replaceAtomically(uri, expected, bytes) }, describe = { observed ->
                    checkLocalized(hasReadWriteGrant(uri)) { androidDocumentNotice("android_doc_revoked") }
                    current.copy(revision = digest(observed))
                })
        } catch (error: Exception) {
            DocumentWriteResult.Failure(notice(error, "save"))
        }
    }

    fun writeNew(uri: Uri, text: String, grantedFlags: Int): DocumentWriteResult = try {
        requireLocalized(uri.scheme == "content") { androidDocumentNotice("android_doc_invalid_reference") }
        val bytes = text.toByteArray(Charsets.UTF_8)
        requireLocalized(bytes.size <= MAX_BYTES) { androidDocumentNotice("android_doc_too_large") }
        val previous = readBytes(uri)
        when {
            previous.contentEquals(bytes) -> DocumentWriteResult.Saved(
                describe(uri, DocumentOpenMode.EDIT_IN_PLACE, grantedFlags, previous))
            previous.isNotEmpty() -> DocumentWriteResult.Conflict(
                describe(uri, DocumentOpenMode.EDIT_IN_PLACE, grantedFlags, previous))
            else -> {
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw LocalizedStateException(androidDocumentNotice("android_doc_no_write_stream"))
                val stored = readBytes(uri)
                checkLocalized(stored.contentEquals(bytes)) { androidDocumentNotice("android_doc_write_unconfirmed") }
                DocumentWriteResult.Saved(describe(uri, DocumentOpenMode.EDIT_IN_PLACE,
                    grantedFlags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, stored))
            }
        }
    } catch (error: Exception) {
        DocumentWriteResult.Failure(notice(error, "export"))
    }

    private fun describe(uri: Uri, mode: DocumentOpenMode, grantedFlags: Int, bytes: ByteArray): DocumentReference {
        var name = androidDocumentText("android_doc_default_name") + ".circ"
        var providerAllowsWrite = true
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
            }
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                val flagsColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (cursor.moveToFirst() && flagsColumn >= 0 && !cursor.isNull(flagsColumn)) {
                    providerAllowsWrite = cursor.getInt(flagsColumn) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
                }
            }
        }
        val permission = grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 ||
            context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return DocumentReference(uri.toString(), name.ifBlank { androidDocumentText("android_doc_default_name") + ".circ" },
            mode == DocumentOpenMode.EDIT_IN_PLACE && permission && providerAllowsWrite && supportsAtomicReplacement(uri),
            digest(bytes), mode)
    }

    private fun hasReadWriteGrant(uri: Uri): Boolean = context.checkUriPermission(uri, android.os.Process.myPid(),
        android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

    private fun supportsAtomicReplacement(uri: Uri): Boolean = hasReadWriteGrant(uri) && runCatching {
        resolver.call(uri, ATOMIC_REPLACE_CAPABILITIES, uri.toString(), null)?.getInt("version") == 1
    }.getOrDefault(false)

    private fun replaceAtomically(uri: Uri, expectedRevision: String, bytes: ByteArray): String? {
        val payload = File.createTempFile("document-save-", ".circ", context.cacheDir)
        try {
            payload.writeBytes(bytes)
            ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                payload.delete() // The read-only descriptor keeps the payload alive for the provider.
                return resolver.call(uri, ATOMIC_REPLACE, uri.toString(), Bundle().apply {
                    putString("expectedSha256", expectedRevision)
                    putInt("byteCount", bytes.size)
                    putParcelable("payload", descriptor)
                })?.getString("result")
            }
        } finally { payload.delete() }
    }

    private fun readBytes(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            requireLocalized(output.size() + read <= MAX_BYTES) { androidDocumentNotice("android_doc_too_large") }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw LocalizedStateException(androidDocumentNotice("android_doc_read_unavailable"))

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val ATOMIC_REPLACE_CAPABILITIES = "org.logisim.documents.atomic-replace-capabilities"
        const val ATOMIC_REPLACE = "org.logisim.documents.atomic-replace-v1"
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun message(error: Exception, action: String): String = notice(error, action).resolve(systemAppLanguage())

        fun notice(error: Throwable, action: String): LocalizedText = when (error) {
            is LocalizedArgumentException -> error.text
            is LocalizedStateException -> error.text
            is SecurityException -> androidDocumentNotice("android_doc_revoked")
            is FileNotFoundException -> androidDocumentNotice("android_doc_missing")
            is java.nio.charset.CharacterCodingException -> androidDocumentNotice("android_doc_invalid_utf8")
            else -> androidDocumentNotice("android_doc_failure", "action" to androidDocumentNotice("android_action_$action"), "reason" to error.localizedDescription(androidDocumentNotice("android_doc_provider_error")))
        }
    }
}

/** The provider owns the atomic compare-and-replace. A successful response still requires byte readback. */
internal fun verifiedAndroidDocumentSave(
    expectedRevision: String?, original: ByteArray, desired: ByteArray,
    readBack: () -> ByteArray, replace: (String) -> String?, describe: (ByteArray) -> DocumentReference,
): DocumentWriteResult {
    if (expectedRevision == null) return DocumentWriteResult.Conflict(describe(original))
    // A previous request may have committed before its callback or local checkpoint completed.
    if (original.contentEquals(desired)) return DocumentWriteResult.Saved(describe(original))
    if (AndroidDocumentStore.digest(original) != expectedRevision) return DocumentWriteResult.Conflict(describe(original))

    val replacement = runCatching { replace(expectedRevision) }
    val stored = readBack()
    if (stored.contentEquals(desired)) return DocumentWriteResult.Saved(describe(stored))
    val response = replacement.getOrThrow()
    if (response == "conflict" || !stored.contentEquals(original)) return DocumentWriteResult.Conflict(describe(stored))
    return DocumentWriteResult.Failure(androidDocumentNotice("android_doc_write_unconfirmed"))
}
