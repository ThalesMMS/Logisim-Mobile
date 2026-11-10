package dev.logisim.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.print.PrintManager
import android.util.AtomicFile
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors

/** Binary SAF operations are separate from editable .circ documents and never replace an original. */
class AndroidDocumentAssetActions(
    private val activity: ComponentActivity,
    sessionId: String,
    private val onRecoveredResult: (AssetActionResult) -> Unit = { result ->
        if (result is AssetActionResult.Failure) Log.e("LogisimAssets", result.notice.resolve())
    },
) : DocumentAssetActions, DefaultLifecycleObserver {
    override val traceFiles: TraceFileActions = AndroidTraceFileActions(activity)
    override val analysisTables: AnalysisTableFiles = AndroidAnalysisTableFiles(activity)
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val store = AndroidAssetStore(app)
    private val sessionKey = sessionId
    private val pendingOpen: AtomicFile
    private val pendingCreate: AtomicFile
    private var openCallback: ((AssetOpenResult) -> Unit)? = null
    private var deferredOpen: AssetOpenResult.Opened? = null
    private var createCallback: ((AssetActionResult) -> Unit)? = null
    private var batch: BatchCreate? = null
    @Volatile private var destroyed = false

    override val supportsBatchCreate: Boolean get() = true

    private data class BatchCreate(val assets: List<DocumentAsset>, val onResult: (AssetActionResult) -> Unit)


    override val supportsPrint: Boolean
        get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING) &&
            activity.getSystemService(Context.PRINT_SERVICE) is PrintManager

    private val openLauncher = activity.activityResultRegistry.register(
        "logisim.asset.open", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            pendingOpen.delete()
            deliverOpen(AssetOpenResult.Cancelled)
        } else {
            val uri = result.data?.data
            val operation = readOperation(pendingOpen)
            if (uri == null || operation == null) {
                pendingOpen.delete()
                deliverOpen(AssetOpenResult.Failure(message("android_asset_selection_restarted")))
            } else if (openCallback == null) {
                pendingOpen.delete()
                recovered(AssetActionResult.Failure(message("android_asset_selection_restarted")))
            } else {
                try {
                    val accepted = operation.getJSONArray("types").let { values -> List(values.length()) { values.getString(it) } }
                    val limit = operation.getInt("limit")
                    writeOperation(pendingOpen, operation.put("phase", "reading"))
                    io.execute {
                        val opened = try { AssetOpenResult.Opened(store.read(uri, accepted, limit)) }
                        catch (error: Exception) { AssetOpenResult.Failure(AndroidAssetStore.notice(error)) }
                        catch (_: OutOfMemoryError) { AssetOpenResult.Failure(message("android_asset_memory_limit")) }
                        main.post {
                            if (!destroyed) {
                                if (opened is AssetOpenResult.Opened) {
                                    deferredOpen = opened
                                    deliverOpenWhenResumed()
                                } else {
                                    pendingOpen.delete()
                                    deliverOpen(opened)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    pendingOpen.delete()
                    deliverOpen(AssetOpenResult.Failure(AndroidAssetStore.notice(error)))
                }
            }
        }
    }

    private val createLauncher = activity.activityResultRegistry.register(
        "logisim.asset.create", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            clearCreate()
            deliverCreate(AssetActionResult.Cancelled)
        } else if (result.data?.data == null) {
            clearCreate()
            deliverCreate(AssetActionResult.Failure(message("android_asset_provider_no_file")))
        } else {
            val operation = readOperation(pendingCreate)
            if (operation == null) {
                clearCreate()
                deliverCreate(AssetActionResult.Failure(message("android_asset_export_lost")))
            } else finishCreate(operation.put("uri", result.data!!.data.toString()).put("phase", "writing"))
        }
    }

    private val batchLauncher = activity.activityResultRegistry.register(
        "logisim.asset.batch", activity, ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = batch
        val tree = result.data?.data
        when {
            pending == null -> if (result.resultCode == Activity.RESULT_OK) {
                recovered(AssetActionResult.Failure(message("android_asset_export_lost")))
            } else Unit
            result.resultCode != Activity.RESULT_OK -> completeBatch(pending, AssetActionResult.Cancelled)
            tree == null || tree.scheme != "content" || !DocumentsContract.isTreeUri(tree) ->
                completeBatch(pending, AssetActionResult.Failure(message("android_directory_invalid_selection")))
            else -> io.execute {
                val written = try { saveBatch(tree, pending.assets) }
                catch (error: Exception) { AssetActionResult.Failure(AndroidAssetStore.notice(error)) }
                catch (error: OutOfMemoryError) { AssetActionResult.Failure(AndroidAssetStore.notice(error)) }
                main.post { if (!destroyed) completeBatch(pending, written) }
            }
        }
    }

    init {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid asset session identifier" }
        pendingOpen = AtomicFile(File(app.filesDir, "asset-$sessionId-open.json"))
        pendingCreate = AtomicFile(File(app.filesDir, "asset-$sessionId-create.json"))
        activity.lifecycle.addObserver(this)
        val operation = readOperation(pendingCreate)
        if (operation?.optString("phase") == "writing") main.post { finishCreate(operation) }
        else if (pendingCreate.baseFile.exists() && operation?.optString("phase") != "picker") {
            clearCreate()
            main.post { recovered(AssetActionResult.Failure(message("android_asset_export_lost"))) }
        }
        if (pendingOpen.baseFile.exists() && readOperation(pendingOpen)?.optString("phase") != "picker") {
            pendingOpen.delete()
            main.post { recovered(AssetActionResult.Failure(message("android_asset_selection_restarted"))) }
        }
    }

    override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) {
        main.post {
            val types = mimeTypes.mapNotNull(AndroidAssetFilePolicy::mime).distinct()
            when {
                destroyed -> onResult(AssetOpenResult.Cancelled)
                openCallback != null || pendingOpen.baseFile.exists() -> onResult(AssetOpenResult.Failure(message("android_asset_picker_busy")))
                types.isEmpty() || mimeTypes.size > 32 || mimeTypes.any { AndroidAssetFilePolicy.mime(it) == null } -> onResult(AssetOpenResult.Failure(message("android_asset_invalid_type")))
                maximumBytes !in 1..AndroidAssetFilePolicy.MAXIMUM_BYTES -> onResult(AssetOpenResult.Failure(message("android_asset_invalid_limit")))
                else -> {
                    openCallback = onResult
                    try {
                        writeOperation(pendingOpen, JSONObject().put("types", JSONArray(types)).put("limit", maximumBytes).put("phase", "picker"))
                        val pickerTypes = AndroidAssetFilePolicy.pickerTypes(types)
                        openLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = if (pickerTypes.size == 1) pickerTypes.single() else "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, pickerTypes.toTypedArray())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (error: Exception) {
                        pendingOpen.delete()
                        deliverOpen(AssetOpenResult.Failure(AndroidAssetStore.notice(error)))
                    }
                }
            }
        }
    }

    override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
        main.post {
            when {
                destroyed -> onResult(AssetActionResult.Cancelled)
                batch != null || createCallback != null || pendingCreate.baseFile.exists() -> onResult(AssetActionResult.Failure(message("android_asset_picker_busy")))
                !validAsset(asset) -> onResult(AssetActionResult.Failure(message("android_asset_invalid_payload")))
                else -> {
                    createCallback = onResult
                    val operation = JSONObject().put("name", AndroidAssetFilePolicy.fileName(asset.name, asset.mimeType)).put("mime", asset.mimeType)
                        .put("payload", UUID.randomUUID().toString()).put("phase", "preparing")
                    try {
                        writeOperation(pendingCreate, operation)
                    } catch (error: Exception) {
                        clearCreate()
                        deliverCreate(AssetActionResult.Failure(AndroidAssetStore.notice(error)))
                        return@post
                    }
                    io.execute {
                        val prepared = runCatching {
                            if (destroyed) throw AssetStorageException(message("android_asset_export_lost"))
                            writeAtomic(payloadFile(operation), asset.bytes)
                        }
                        main.post {
                            if (!destroyed) prepared.fold(onSuccess = {
                                try {
                                    writeOperation(pendingCreate, operation.put("phase", "picker"))
                                    createLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = AndroidAssetFilePolicy.createPickerType(operation.getString("name"), asset.mimeType)
                                        putExtra(Intent.EXTRA_TITLE, AndroidAssetFilePolicy.fileName(asset.name, asset.mimeType))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    })
                                } catch (error: Exception) {
                                    clearCreate()
                                    deliverCreate(AssetActionResult.Failure(AndroidAssetStore.notice(error)))
                                }
                            }, onFailure = { error ->
                                clearCreate()
                                deliverCreate(AssetActionResult.Failure(AndroidAssetStore.notice(error)))
                            })
                            else runCatching { payloadFile(operation).delete() }
                        }
                    }
                }
            }
        }
    }

    override fun createBatch(assets: List<DocumentAsset>, onResult: (AssetActionResult) -> Unit) {
        main.post {
            when {
                destroyed -> onResult(AssetActionResult.Cancelled)
                batch != null || createCallback != null || pendingCreate.baseFile.exists() ->
                    onResult(AssetActionResult.Failure(message("android_asset_picker_busy")))
                assets.isEmpty() || assets.any { !validAsset(it) } ->
                    onResult(AssetActionResult.Failure(message("android_asset_invalid_payload")))
                assets.sumOf { it.size.toLong() } > MAXIMUM_IMAGE_BATCH_BYTES ->
                    onResult(AssetActionResult.Failure(message("visual_export_batch_size")))
                else -> {
                    val request = BatchCreate(assets.toList(), onResult)
                    batch = request
                    try {
                        batchLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        })
                    } catch (error: Exception) {
                        completeBatch(request, AssetActionResult.Failure(AndroidAssetStore.notice(error)))
                    }
                }
            }
        }
    }

    private fun completeBatch(request: BatchCreate, result: AssetActionResult) {
        if (batch !== request) return
        batch = null
        request.onResult(result)
    }

    private fun saveBatch(tree: Uri, assets: List<DocumentAsset>): AssetActionResult {
        val resolver = app.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val failed = mutableListOf<LocalizedText>()
        var saved = 0
        for (asset in assets) {
            if (destroyed) return AssetActionResult.Cancelled
            try {
                val name = AndroidAssetFilePolicy.fileName(asset.name, asset.mimeType)
                val created = DocumentsContract.createDocument(resolver, root, asset.mimeType, name)
                    ?: throw FileNotFoundException()
                check(created.scheme == "content" && created.authority == tree.authority)
                val documentId = DocumentsContract.getDocumentId(created)
                check(documentId != rootId)
                val scoped = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                try {
                    store.writeNew(scoped, asset)
                } catch (error: Exception) {
                    if (error !is AssetStorageException || error.notice.key != "android_asset_destination_conflict") {
                        runCatching { DocumentsContract.deleteDocument(resolver, scoped) }
                    }
                    throw error
                }
                saved++
            } catch (error: Exception) {
                failed += message("visual_export_batch_file_error", "name" to asset.name,
                    "reason" to AndroidAssetStore.notice(error))
            }
        }
        return if (failed.isEmpty()) AssetActionResult.Completed else AssetActionResult.Failure(
            message("visual_export_batch_partial", "count" to saved, "errors" to LocalizedText.join(failed)))
    }

    override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
        prepareTemporary(asset, "shared-circuits", onResult) { file ->
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = asset.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, message("android_asset_share_title").resolve(systemAppLanguage())))
        }
    }

    override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) {
        if (!supportsPrint || asset.mimeType != "application/pdf") {
            main.post { onResult(AssetActionResult.Failure(message("android_asset_print_unavailable"))) }
            return
        }
        prepareTemporary(asset, "print-assets", onResult) { file ->
            val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(file.name, AndroidPdfPrintAdapter(app, file, file.name) {
                file.delete()
                file.parentFile?.delete()
            }, null)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        // Let editor lifecycle observers reactivate the simulation before delivering the asset.
        main.post { deliverOpenWhenResumed() }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        deferredOpen = null
        openCallback = null
        createCallback = null
        batch?.let { completeBatch(it, AssetActionResult.Cancelled) }
        io.shutdown()
    }

    private fun prepareTemporary(asset: DocumentAsset, directory: String, onResult: (AssetActionResult) -> Unit, present: (File) -> Unit) {
        main.post {
            if (destroyed) onResult(AssetActionResult.Cancelled)
            else if (!validAsset(asset)) onResult(AssetActionResult.Failure(message("android_asset_invalid_payload")))
            else io.execute {
                val prepared = runCatching {
                    val folder = File(app.cacheDir, "$directory/${UUID.randomUUID()}")
                    if (!folder.mkdirs()) throw AssetStorageException(message("android_asset_failed"))
                    File(folder, AndroidAssetFilePolicy.fileName(asset.name, asset.mimeType)).apply { writeBytes(asset.bytes) }
                }
                main.post {
                    if (!destroyed) prepared.fold(onSuccess = { file ->
                        try { present(file); onResult(AssetActionResult.Completed) }
                        catch (error: Exception) { file.delete(); file.parentFile?.delete(); onResult(AssetActionResult.Failure(AndroidAssetStore.notice(error))) }
                    }, onFailure = { onResult(AssetActionResult.Failure(AndroidAssetStore.notice(it))) })
                    else prepared.getOrNull()?.let { file -> file.delete(); file.parentFile?.delete() }
                }
            }
        }
    }

    private fun finishCreate(operation: JSONObject) {
        if (destroyed || readOperation(pendingCreate)?.optString("payload") != operation.optString("payload")) return
        try {
            writeOperation(pendingCreate, operation)
            io.execute {
                val result = try {
                    val payload = payloadFile(operation)
                    if (payload.baseFile.length() > AndroidAssetFilePolicy.MAXIMUM_BYTES) {
                        throw AssetStorageException(message("android_asset_too_large"))
                    }
                    val asset = DocumentAsset(operation.getString("name"), operation.getString("mime"), payload.readFully())
                    if (!validAsset(asset)) throw AssetStorageException(message("android_asset_invalid_payload"))
                    store.writeNew(Uri.parse(operation.getString("uri")), asset)
                    AssetActionResult.Completed
                } catch (error: Exception) { AssetActionResult.Failure(AndroidAssetStore.notice(error)) }
                catch (_: OutOfMemoryError) { AssetActionResult.Failure(message("android_asset_memory_limit")) }
                main.post {
                    if (!destroyed && readOperation(pendingCreate)?.optString("payload") == operation.optString("payload")) {
                        clearCreate()
                        deliverCreate(result)
                    }
                }
            }
        } catch (error: Exception) {
            clearCreate()
            deliverCreate(AssetActionResult.Failure(AndroidAssetStore.notice(error)))
        }
    }

    private fun validAsset(asset: DocumentAsset): Boolean = asset.size <= AndroidAssetFilePolicy.MAXIMUM_BYTES &&
        AndroidAssetFilePolicy.mime(asset.mimeType) == asset.mimeType && !asset.mimeType.contains('*')

    private fun deliverOpenWhenResumed() {
        if (destroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val result = deferredOpen ?: return
        deferredOpen = null
        pendingOpen.delete()
        deliverOpen(result)
    }

    private fun deliverOpen(result: AssetOpenResult) {
        val callback = openCallback
        openCallback = null
        if (callback != null) callback(result)
        else if (result is AssetOpenResult.Failure) recovered(AssetActionResult.Failure(result.notice))
    }

    private fun deliverCreate(result: AssetActionResult) {
        val callback = createCallback
        createCallback = null
        if (callback != null) callback(result) else recovered(result)
    }

    private fun recovered(result: AssetActionResult) { if (!destroyed) onRecoveredResult(result) }
    private fun clearCreate() {
        val operation = readOperation(pendingCreate)
        pendingCreate.delete()
        if (operation != null) runCatching { payloadFile(operation).delete() }
    }
    private fun payloadFile(operation: JSONObject): AtomicFile {
        val token = UUID.fromString(operation.getString("payload")).toString()
        return AtomicFile(File(app.filesDir, "asset-$sessionKey-$token.bin"))
    }
    private fun readOperation(file: AtomicFile): JSONObject? = runCatching {
        if (file.baseFile.length() > MAXIMUM_METADATA_BYTES) throw AssetStorageException(message("android_asset_failed"))
        JSONObject(file.readFully().decodeToString())
    }.getOrNull()
    private fun writeOperation(file: AtomicFile, value: JSONObject) {
        val encoded = value.toString()
        if (encoded.length > MAXIMUM_METADATA_BYTES) throw AssetStorageException(message("android_asset_failed"))
        val bytes = encoded.toByteArray()
        if (bytes.size > MAXIMUM_METADATA_BYTES) throw AssetStorageException(message("android_asset_failed"))
        writeAtomic(file, bytes)
    }
    private fun writeAtomic(file: AtomicFile, value: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(value); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    private companion object {
        const val MAXIMUM_METADATA_BYTES = 32 * 1024
    }
}
