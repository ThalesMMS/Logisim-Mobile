package dev.logisim.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A binary content-URI boundary. No imported URI is converted to a filesystem path. */
internal class AndroidAssetStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun read(uri: Uri, acceptedTypes: List<String>, maximumBytes: Int): DocumentAsset {
        requireContentUri(uri)
        val bytes = readBytes(uri, maximumBytes)
        var name = "import"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column)
            }
        }
        val mime = AndroidAssetFilePolicy.detectedMime(bytes, name, resolver.getType(uri), acceptedTypes)
        if (!AndroidAssetFilePolicy.accepts(mime, acceptedTypes)) throw AssetStorageException(message("android_asset_wrong_type"))
        return DocumentAsset(AndroidAssetFilePolicy.fileName(name, mime), mime, bytes)
    }

    fun writeNew(uri: Uri, asset: DocumentAsset) {
        requireContentUri(uri)
        val bytes = asset.bytes
        if (bytes.size > AndroidAssetFilePolicy.MAXIMUM_BYTES) throw AssetStorageException(message("android_asset_too_large"))
        writeLocks.getOrPut(uri.toString()) { ReentrantLock() }.withLock {
            val existing = readBytes(uri, AndroidAssetFilePolicy.MAXIMUM_BYTES)
            if (existing.contentEquals(bytes)) return
            if (existing.isNotEmpty()) throw AssetStorageException(message("android_asset_destination_conflict"))
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw FileNotFoundException()
            if (!readBytes(uri, AndroidAssetFilePolicy.MAXIMUM_BYTES).contentEquals(bytes)) {
                throw AssetStorageException(message("android_asset_write_unconfirmed"))
            }
        }
    }

    private fun readBytes(uri: Uri, maximumBytes: Int): ByteArray {
        return resolver.openInputStream(uri)?.use { stream ->
            AndroidAssetFilePolicy.readBounded(stream, maximumBytes)
        } ?: throw FileNotFoundException()
    }

    private fun requireContentUri(uri: Uri) {
        if (uri.scheme != "content") throw AssetStorageException(message("android_asset_content_uri"))
    }

    companion object {
        private val writeLocks = ConcurrentHashMap<String, ReentrantLock>()

        fun notice(error: Throwable): LocalizedText = when (error) {
            is AssetStorageException -> error.notice
            is LocalizedArgumentException -> error.text
            is SecurityException -> message("android_asset_revoked")
            is FileNotFoundException -> message("android_asset_missing")
            is OutOfMemoryError -> message("android_asset_memory_limit")
            else -> message("android_asset_failed")
        }
    }
}

internal class AssetStorageException(val notice: LocalizedText) : IOException(notice.resolve())
