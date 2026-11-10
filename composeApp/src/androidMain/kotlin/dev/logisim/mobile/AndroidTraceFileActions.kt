package dev.logisim.mobile

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import java.util.concurrent.Executors

/** SAF providers must supply a seekable append destination; unsupported providers fail visibly. */
class AndroidTraceFileActions(private val activity: ComponentActivity) : TraceFileActions, DefaultLifecycleObserver {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var callback: ((TraceFileOpenResult) -> Unit)? = null
    private val writers = mutableSetOf<TraceFileWriter>()
    private var opening = false
    private var appendSelection = false
    private var destinationName = "signals.tsv"
    private var destroyed = false
    private val picker = activity.activityResultRegistry.register("logisim.trace.file", activity,
        ActivityResultContracts.StartActivityForResult()) { result ->
        val receive = callback
        val appending = appendSelection
        val suggestedName = destinationName
        callback = null
        val uri = result.data?.data
        if (receive != null) {
            if (result.resultCode != Activity.RESULT_OK || uri == null) receive(TraceFileOpenResult.Cancelled)
            else {
                opening = true
                io.execute {
                    val opened = runCatching {
                        val resolver = activity.contentResolver
                        val descriptor = resolver.openFileDescriptor(uri, if (appending) "wa" else "w")
                            ?: error("Missing writable descriptor")
                        try {
                            // Verify append positioning before handing the destination to the recorder.
                            if (appending) Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_END)
                            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: suggestedName
                            AndroidTraceFileWriter(name, ParcelFileDescriptor.AutoCloseOutputStream(descriptor),
                                execute = { io.execute(it) }, onMain = { main.post(it) }, onClosed = {
                                    writers.remove(it)
                                    finishDestroy()
                                })
                        } catch (error: Throwable) {
                            descriptor.close()
                            throw error
                        }
                    }
                    main.post {
                        opening = false
                        opened.fold({ writer ->
                            if (destroyed) writer.close {} else {
                                writers += writer
                                receive(TraceFileOpenResult.Opened(writer))
                            }
                        }, { error -> receive(TraceFileOpenResult.Failure(error.localizedDescription(message("trace_file_provider_failed")))) })
                        finishDestroy()
                    }
                }
            }
        }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun select(append: Boolean, onResult: (TraceFileOpenResult) -> Unit) =
        selectDestination(append, "signals.tsv", "text/tab-separated-values", onResult)

    override fun create(name: String, mimeType: String, onResult: (TraceFileOpenResult) -> Unit) =
        selectDestination(false, name, mimeType, onResult)

    private fun selectDestination(append: Boolean, name: String, mimeType: String, onResult: (TraceFileOpenResult) -> Unit) {
        if (callback != null) { onResult(TraceFileOpenResult.Failure(message("trace_file_picker_busy"))); return }
        if (destroyed) { onResult(TraceFileOpenResult.Cancelled); return }
        callback = onResult
        appendSelection = append
        destinationName = name
        try {
            picker.launch(Intent(if (append) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (append) "*/*" else mimeType
                if (!append) putExtra(Intent.EXTRA_TITLE, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
        } catch (error: Exception) {
            callback = null
            onResult(TraceFileOpenResult.Failure(error.localizedDescription(message("trace_file_provider_failed"))))
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        callback?.invoke(TraceFileOpenResult.Cancelled)
        callback = null
        writers.toList().forEach { it.close {} }
        finishDestroy()
    }

    private fun finishDestroy() {
        if (destroyed && !opening && writers.isEmpty()) io.shutdown()
    }
}
