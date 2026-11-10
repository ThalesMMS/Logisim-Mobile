package dev.logisim.mobile

import dev.logisim.mobile.core.localization.checkLocalized
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class AndroidDocumentActions(private val activity: ComponentActivity, private val sessionId: String) : DocumentActions, DefaultLifecycleObserver {
    private val app = activity.applicationContext
    private val store = AndroidDocumentStore(app)
    private val preferences = app.getSharedPreferences("document-links", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pendingCreate = AtomicFile(File(app.filesDir, "document-$sessionId-create.pending"))
    private val pendingOpen = AtomicFile(File(app.filesDir, "document-$sessionId-open.pending"))
    private var openHandler: ((DocumentOpenResult) -> Unit)? = null
    private val openResults = ArrayDeque<Pair<DocumentOpenResult, Long?>>()
    private var createCallback: ((DocumentWriteResult) -> Unit)? = null
    private var openingUri: String? = null
    private var openGeneration = 0L

    private val openLauncher = activity.activityResultRegistry.register(
        "logisim.open-document", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            emit(DocumentOpenResult.Cancelled)
        } else {
            val data = result.data
            val uri = data?.data
            if (uri == null) emit(DocumentOpenResult.Failure(androidDocumentNotice("android_doc_provider_no_file")))
            else {
                val mode = pendingMode()
                val flags = if (mode == DocumentOpenMode.EDIT_IN_PLACE) data.flags
                    else data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
                openUri(uri, mode, flags)
            }
        }
    }

    private val createLauncher = activity.activityResultRegistry.register(
        "logisim.create-document", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            pendingCreate.delete()
            deliverWrite(DocumentWriteResult.Cancelled)
        } else {
            val data = result.data!!
            val uri = data.data!!
            val saved = runCatching { JSONObject(pendingCreate.readFully().toString(Charsets.UTF_8)) }.getOrNull()
            if (saved == null) {
                deliverWrite(DocumentWriteResult.Failure(androidDocumentNotice("android_doc_pending_lost")))
            } else {
                finishCreate(saved.put("uri", uri.toString()).put("flags", data.flags).put("phase", "writing"))
            }
        }
    }

    init {
        activity.lifecycle.addObserver(this)
        try {
            if (pendingCreate.baseFile.exists()) {
                val pending = JSONObject(pendingCreate.readFully().toString(Charsets.UTF_8))
                if (pending.optString("phase") == "writing") finishCreate(pending)
            }
            if (pendingOpen.baseFile.exists()) {
                val pending = JSONObject(pendingOpen.readFully().toString(Charsets.UTF_8))
                openUri(Uri.parse(pending.getString("uri")), DocumentOpenMode.valueOf(pending.getString("mode")), pending.optInt("flags"))
            }
        } catch (error: Exception) {
            emit(DocumentOpenResult.Failure(AndroidDocumentStore.notice(error, "open")), openGeneration)
        }
    }

    override val currentReference: DocumentReference?
        get() = preferences.getString("active:$sessionId", null)?.let { raw ->
            runCatching {
                val value = JSONObject(raw)
                DocumentReference(value.getString("id"), value.getString("name"), value.getBoolean("writable"),
                    value.optString("revision").ifEmpty { null }, DocumentOpenMode.valueOf(value.getString("mode")))
            }.getOrNull()
        }

    override fun retain(reference: DocumentReference?) {
        val editor = preferences.edit()
        if (reference == null) editor.remove("active:$sessionId")
        else editor.putString("active:$sessionId", JSONObject().apply {
            put("id", reference.id); put("name", reference.name); put("writable", reference.writable)
            put("revision", reference.revision.orEmpty()); put("mode", reference.mode.name)
        }.toString())
        editor.apply()
    }

    override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) {
        openHandler = handler
        while (openHandler != null && openResults.isNotEmpty()) {
            val (result, generation) = openResults.removeFirst()
            emit(result, generation)
        }
    }

    override fun open(mode: DocumentOpenMode) {
        preferences.edit().putString("pending-mode:$sessionId", mode.name).apply()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_TYPE, "application/xml", "text/xml", "text/plain", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (mode == DocumentOpenMode.EDIT_IN_PLACE) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try { openLauncher.launch(intent) }
        catch (_: ActivityNotFoundException) { emit(DocumentOpenResult.Failure(androidDocumentNotice("android_doc_no_picker"))) }
    }

    override fun reopen(reference: DocumentReference) = openUri(Uri.parse(reference.id), reference.mode, 0)

    override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) {
        if (createCallback != null) {
            onResult(DocumentWriteResult.Failure(androidDocumentNotice("android_doc_export_busy")))
            return
        }
        createCallback = onResult
        try {
            writePending(pendingCreate, JSONObject().put("text", text).put("phase", "picker").toString())
            createLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME_TYPE
                putExtra(Intent.EXTRA_TITLE, fileName(suggestedName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        } catch (error: Exception) {
            pendingCreate.delete()
            deliverWrite(DocumentWriteResult.Failure(AndroidDocumentStore.notice(error, "export")))
        }
    }

    override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
        io.execute { val result = store.write(reference, text); main.post { onResult(result) } }
    }

    override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) {
        io.execute {
            val result = runCatching {
                val folder = File(app.cacheDir, "shared-circuits/${UUID.randomUUID()}")
                checkLocalized(folder.mkdirs()) { androidDocumentNotice("android_doc_share_prepare") }
                val file = File(folder, fileName(suggestedName))
                file.writeText(text, Charsets.UTF_8)
                FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            }
            main.post {
                result.fold(onSuccess = { uri ->
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = MIME_TYPE
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(androidDocumentText("android_doc_label"), uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(send, androidDocumentText("android_doc_share_title")))
                        onResult(DocumentShareResult.Presented)
                    } catch (error: Exception) {
                        onResult(DocumentShareResult.Failure(AndroidDocumentStore.notice(error, "share")))
                    }
                }, onFailure = { onResult(DocumentShareResult.Failure(AndroidDocumentStore.notice(it, "share"))) })
            }
        }
    }

    fun handleIntent(intent: Intent?): Boolean {
        if (intent == null || intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND)) return false
        val uri = if (intent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else intent.data
        val sources = buildList {
            if (uri != null) add(uri)
            intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
        }.distinct()
        if (sources.size != 1) {
            emit(DocumentOpenResult.Failure(androidDocumentNotice("android_doc_one_file")))
            return true
        }
        val source = sources.single()
        val mode = if (intent.action == Intent.ACTION_EDIT) DocumentOpenMode.EDIT_IN_PLACE else DocumentOpenMode.IMPORT_COPY
        openUri(source, mode, intent.flags)
        return true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        openHandler = null
        openGeneration++
        io.shutdown()
    }

    private fun openUri(uri: Uri, mode: DocumentOpenMode, flags: Int) {
        if (openingUri == uri.toString()) return
        if (uri.scheme != "content") {
            emit(DocumentOpenResult.Failure(androidDocumentNotice("android_doc_provider_required")))
            return
        }
        try {
            if (mode == DocumentOpenMode.EDIT_IN_PLACE) store.persistPermission(uri, flags)
            writePending(pendingOpen, JSONObject().put("uri", uri.toString()).put("mode", mode.name).put("flags", flags).toString())
        } catch (error: Exception) {
            emit(DocumentOpenResult.Failure(AndroidDocumentStore.notice(error, "prepare")))
            return
        }
        openingUri = uri.toString()
        // A later request may replace the recovery record before this provider read finishes.
        val generation = ++openGeneration
        io.execute {
            val result = try {
                val content = store.read(uri, mode, flags)
                val notice = if (mode == DocumentOpenMode.EDIT_IN_PLACE && !content.reference.writable)
                    androidDocumentNotice("android_doc_save_as_required") else null
                DocumentOpenResult.Opened(content, notice)
            }
            catch (error: Exception) { DocumentOpenResult.Failure(AndroidDocumentStore.notice(error, "open")) }
            main.post { emit(result, generation) }
        }
    }

    private fun emit(result: DocumentOpenResult, generation: Long? = null) {
        if (generation != null && generation != openGeneration) return
        val handler = openHandler
        if (handler == null) openResults.addLast(result to generation)
        else {
            handler(result)
            if (generation != null && generation == openGeneration) {
                openingUri = null
                pendingOpen.delete()
            }
        }
    }

    private fun deliverWrite(result: DocumentWriteResult) {
        val callback = createCallback
        createCallback = null
        if (callback != null) callback(result)
        else if (result is DocumentWriteResult.Failure) emit(DocumentOpenResult.Failure(result.notice))
        else if (result is DocumentWriteResult.Conflict) {
            emit(DocumentOpenResult.Failure(androidDocumentNotice("android_doc_export_conflict")))
        }
    }

    private fun pendingMode(): DocumentOpenMode = runCatching {
        DocumentOpenMode.valueOf(preferences.getString("pending-mode:$sessionId", null) ?: DocumentOpenMode.IMPORT_COPY.name)
    }.getOrDefault(DocumentOpenMode.IMPORT_COPY)

    private fun writePending(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun finishCreate(operation: JSONObject) {
        try {
            val uri = Uri.parse(operation.getString("uri"))
            val flags = operation.optInt("flags")
            val text = operation.getString("text")
            writePending(pendingCreate, operation.toString())
            store.persistPermission(uri, flags)
            io.execute {
                val written = store.writeNew(uri, text, flags)
                main.post {
                    pendingCreate.delete()
                    deliverWrite(written)
                }
            }
        } catch (error: Exception) {
            deliverWrite(DocumentWriteResult.Failure(AndroidDocumentStore.notice(error, "export")))
        }
    }

    companion object {
        const val MIME_TYPE = "application/x-logisim-circuit"
        fun fileName(input: String): String {
            val name = input.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), "").trim()
                .ifEmpty { androidDocumentText("android_doc_default_name") }.take(180)
            return if (name.endsWith(".circ", ignoreCase = true)) name else "$name.circ"
        }
    }
}
