package dev.logisim.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Requests a directory explicitly. This adapter never expands a single-document permission. */
class AndroidDirectoryAssetActions(
    private val activity: ComponentActivity,
    private val onRecoveredResult: (DirectoryAssetOpenResult) -> Unit = { result ->
        if (result is DirectoryAssetOpenResult.Failure) Log.e("LogisimDirectory", result.notice.resolve())
    },
) : DirectoryAssetActions, DefaultLifecycleObserver {
    private val application = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var callback: ((DirectoryAssetOpenResult) -> Unit)? = null
    private var destroyed = false

    private val launcher = activity.activityResultRegistry.register(
        "logisim.directory.open", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) deliver(DirectoryAssetOpenResult.Cancelled)
        else if (callback == null) deliver(DirectoryAssetOpenResult.Failure(message("android_directory_selection_restarted")))
        else {
            val uri = result.data?.data
            if (uri == null || uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) {
                deliver(DirectoryAssetOpenResult.Failure(message("android_directory_invalid_selection")))
            } else io.execute {
                val opened = try { DirectoryAssetOpenResult.Opened(AndroidDirectoryAssetGrant(application, uri)) }
                catch (error: Exception) { DirectoryAssetOpenResult.Failure(directoryNotice(error)) }
                main.post {
                    if (destroyed) (opened as? DirectoryAssetOpenResult.Opened)?.grant?.release()
                    else deliver(opened)
                }
            }
        }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun open(onResult: (DirectoryAssetOpenResult) -> Unit) {
        main.post {
            when {
                destroyed -> onResult(DirectoryAssetOpenResult.Cancelled)
                callback != null -> onResult(DirectoryAssetOpenResult.Failure(message("android_directory_picker_busy")))
                else -> {
                    callback = onResult
                    try {
                        launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (error: Exception) { deliver(DirectoryAssetOpenResult.Failure(directoryNotice(error))) }
                }
            }
        }
    }

    private fun deliver(result: DirectoryAssetOpenResult) {
        val current = callback
        callback = null
        if (current != null) current(result)
        else if (result is DirectoryAssetOpenResult.Failure) onRecoveredResult(result)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        val pending = callback
        callback = null
        pending?.invoke(DirectoryAssetOpenResult.Cancelled)
        io.shutdown()
    }
}

/** Application-scoped provider reader. No activity or open cursor is retained by the grant. */
private class AndroidDirectoryAssetGrant(context: Context, private val treeUri: Uri) : DirectoryAssetGrant {
    private val application = context.applicationContext
    private val resolver = application.contentResolver
    private val released = AtomicBoolean()
    private val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri = documentUri(rootId)
    private val tree: AndroidDirectoryTree

    override val id: String = treeUri.toString()
    override val name: String

    init {
        if (treeUri.authority.isNullOrBlank() || rootId.isBlank() ||
            application.checkUriPermission(rootUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw LocalizedArgumentException(message("android_directory_revoked"))
        }
        val cursor = resolver.query(rootUri, PROJECTION, null, null, null)
            ?: throw LocalizedArgumentException(message("android_directory_unavailable"))
        name = cursor.use {
            if (!it.moveToFirst() || it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)) != rootId ||
                it.getString(it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)) != Document.MIME_TYPE_DIR) {
                throw LocalizedArgumentException(message("android_directory_invalid_selection"))
            }
            val flags = documentFlags(it)
            if (flags and Document.FLAG_VIRTUAL_DOCUMENT != 0) throw LocalizedArgumentException(message("android_directory_virtual"))
            val displayName = it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
                ?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim()?.take(180)?.takeIf(String::isNotBlank)
                ?: rootId.take(180)
            if (it.moveToNext()) throw LocalizedArgumentException(message("android_directory_unverifiable"))
            displayName
        }
        tree = AndroidDirectoryTree(rootId, object : AndroidDirectoryAccess {
            override fun children(parentId: String, maximumEntries: Int): List<AndroidDirectoryEntry> {
                active()
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId).also {
                    verifyTreeIdentity(it, parentId)
                }
                val children = resolver.query(uri, PROJECTION, null, null, null)
                    ?: throw LocalizedArgumentException(message("android_directory_unavailable"))
                return children.use { rows ->
                    val identifiers = rows.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                    val names = rows.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                    val types = rows.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                    val values = mutableListOf<AndroidDirectoryEntry>()
                    while (rows.moveToNext()) {
                        active()
                        if (values.size >= maximumEntries) throw LocalizedArgumentException(message("android_directory_too_many_entries"))
                        val documentId = rows.getString(identifiers)
                            ?: throw LocalizedArgumentException(message("android_directory_unverifiable"))
                        val name = rows.getString(names)
                            ?: throw LocalizedArgumentException(message("android_directory_invalid_path"))
                        val mimeType = rows.getString(types)
                            ?: throw LocalizedArgumentException(message("android_directory_unverifiable"))
                        values += AndroidDirectoryEntry(documentId, name, mimeType == Document.MIME_TYPE_DIR,
                            documentFlags(rows) and Document.FLAG_VIRTUAL_DOCUMENT != 0)
                    }
                    active()
                    values
                }
            }

            override fun contained(documentId: String): Boolean {
                active()
                return try {
                    // DocumentsProvider's final tree-query handler enforces ancestry on API 21+.
                    val proof = resolver.query(documentUri(documentId),
                        arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_FLAGS), null, null, null)
                        ?: throw FileNotFoundException()
                    proof.use { rows ->
                        if (!rows.moveToFirst()) throw FileNotFoundException()
                        val returnedId = rows.getString(rows.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
                        if (returnedId != documentId) throw LocalizedArgumentException(message("android_directory_unverifiable"))
                        if (documentFlags(rows) and Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                            throw LocalizedArgumentException(message("android_directory_virtual"))
                        }
                        if (rows.moveToNext()) throw LocalizedArgumentException(message("android_directory_unverifiable"))
                        active()
                        true
                    }
                } catch (_: UnsupportedOperationException) {
                    throw LocalizedArgumentException(message("android_directory_unverifiable"))
                }
            }

            override fun read(documentId: String, maximumBytes: Int): DirectoryAssetContent? {
                active()
                return try {
                    if (!contained(documentId)) throw LocalizedArgumentException(message("android_directory_unverifiable"))
                    val uri = documentUri(documentId)
                    val bytes = resolver.openInputStream(uri)?.use { AndroidAssetFilePolicy.readBounded(it, maximumBytes) }
                        ?: return null
                    active()
                    DirectoryAssetContent(bytes, MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) })
                } catch (_: FileNotFoundException) { null }
            }
        })
    }

    override fun circuitFiles(maximumEntries: Int): List<DirectoryCircuitFile> = guarded { tree.circuitFiles(maximumEntries) }
    override fun read(relativePath: String, maximumBytes: Int): DirectoryAssetContent? {
        active()
        return try { tree.read(relativePath, maximumBytes).also { active() } }
        catch (_: FileNotFoundException) { active(); null }
        catch (error: Exception) { throw LocalizedArgumentException(directoryNotice(error)) }
    }

    override fun release() { released.set(true) }

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).also { verifyTreeIdentity(it, documentId) }

    private fun verifyTreeIdentity(uri: Uri, documentId: String) {
        if (uri.scheme != "content" || uri.authority != treeUri.authority || !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != rootId || DocumentsContract.getDocumentId(uri) != documentId) {
            throw LocalizedArgumentException(message("android_directory_unverifiable"))
        }
    }

    private fun active() {
        if (released.get()) throw LocalizedArgumentException(message("android_directory_released"))
    }

    private inline fun <T> guarded(action: () -> T): T {
        active()
        return try { action().also { active() } }
        catch (error: Exception) { throw LocalizedArgumentException(directoryNotice(error)) }
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
    }
}

private fun directoryNotice(error: Throwable): LocalizedText = when (error) {
    is LocalizedArgumentException -> error.text
    is AssetStorageException -> if (error.notice.key == "android_asset_too_large") message("android_directory_too_large") else error.notice
    is SecurityException -> message("android_directory_revoked")
    is FileNotFoundException -> message("android_directory_unavailable")
    is UnsupportedOperationException -> message("android_directory_unverifiable")
    else -> message("android_directory_unavailable")
}

private fun documentFlags(row: Cursor): Int {
    val column = row.getColumnIndex(Document.COLUMN_FLAGS)
    if (column < 0 || row.isNull(column)) throw LocalizedArgumentException(message("android_directory_unverifiable"))
    return row.getInt(column)
}
