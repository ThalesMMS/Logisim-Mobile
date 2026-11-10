package dev.logisim.mobile

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile

internal class AndroidAnalysisTableFiles(private val activity: ComponentActivity) : AnalysisTableFiles, DefaultLifecycleObserver {
    private var callback: ((AnalysisTableSelection) -> Unit)? = null
    private var destroyed = false
    private val picker = activity.activityResultRegistry.register(
        "logisim.analysis.table.open", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val selected = result.data?.data
        complete(when {
            result.resultCode != Activity.RESULT_OK -> AnalysisTableSelection.Cancelled
            selected == null || selected.scheme != "content" -> AnalysisTableSelection.Failure(message("android_asset_content_uri"))
            else -> AnalysisTableSelection.Opened(AndroidAnalysisTableInput(activity.contentResolver, selected))
        })
    }

    init { activity.lifecycle.addObserver(this) }

    override fun select(onResult: (AnalysisTableSelection) -> Unit) {
        if (destroyed) return onResult(AnalysisTableSelection.Cancelled)
        if (callback != null) return onResult(AnalysisTableSelection.Failure(message("android_asset_picker_busy")))
        callback = onResult
        try {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (failure: Exception) {
            complete(AnalysisTableSelection.Failure(AndroidAssetStore.notice(failure)))
        }
    }

    override fun createSpoolFile(): AnalysisSpoolFile = analysisFileOperation {
        val path = File.createTempFile("analysis-table-", ".bin", activity.cacheDir)
        val file = try { RandomAccessFile(path, "rw") } catch (failure: Exception) {
            path.delete()
            throw failure
        }
        // An open descriptor remains valid after unlink. A killed editor leaves no staged table behind.
        AndroidAnalysisSpoolFile(file, path.takeUnless { it.delete() })
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        complete(AnalysisTableSelection.Cancelled)
    }

    private fun complete(result: AnalysisTableSelection) {
        val receive = callback
        callback = null
        if (receive != null) receive(result)
        else (result as? AnalysisTableSelection.Opened)?.input?.close()
    }
}

private class AndroidAnalysisTableInput(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : AnalysisTableInput {
    private var stream: InputStream? = null
    override val name: String get() = analysisFileOperation {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: throw FileNotFoundException()
    }

    override fun read(buffer: ByteArray): Int = analysisFileOperation {
        val source = stream ?: (resolver.openInputStream(uri) ?: throw FileNotFoundException()).also { stream = it }
        source.read(buffer)
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }
}

private class AndroidAnalysisSpoolFile(private val file: RandomAccessFile, private val remainingPath: File?) : AnalysisSpoolFile {
    @Synchronized
    override fun write(offset: Int, bytes: ByteArray, count: Int) = analysisFileOperation {
        file.seek(offset.toLong())
        file.write(bytes, 0, count)
    }

    @Synchronized
    override fun read(offset: Int, bytes: ByteArray, count: Int) = analysisFileOperation {
        file.seek(offset.toLong())
        file.readFully(bytes, 0, count)
    }

    @Synchronized
    override fun close() {
        runCatching { file.close() }
        remainingPath?.delete()
    }
}

private inline fun <T> analysisFileOperation(action: () -> T): T = try {
    action()
} catch (failure: Exception) {
    throw LocalizedArgumentException(AndroidAssetStore.notice(failure))
}
