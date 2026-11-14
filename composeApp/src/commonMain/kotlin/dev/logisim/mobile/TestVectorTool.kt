package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.message
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.TestVectorRunSession
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Course vector files are small text tables; larger files are refused by the picker before decoding. */
internal const val VECTOR_FILE_MAX_BYTES = 1024 * 1024

/**
 * Test-vector state kept by the tool while its sheet is closed, as the trace tool keeps its recorder (#59).
 * A different document clears everything so text never leaks between files. Any project change drops the run session,
 * which is bound to one project instance, but keeps the text and the chosen circuit while that circuit still exists.
 */
internal class TestVectorToolState {
    var text by mutableStateOf("")
    var circuitName by mutableStateOf<String?>(null)
    var session by mutableStateOf<TestVectorRunSession?>(null)
    var failure by mutableStateOf<LocalizedText?>(null)
    var notice by mutableStateOf<ToolNotice?>(null)
    var running by mutableStateOf(false)
    var revision by mutableStateOf(0)

    /** True while a vector file request is open; only the request that set it can clear it, see [finishOpen]. */
    var opening by mutableStateOf(false)
        private set
    private var openRequest = 0

    /** The text last opened from or saved to a file. Typed text that differs from it is lost by opening another file. */
    var savedText by mutableStateOf("")
        private set
    val hasUnsavedText: Boolean get() = text.isNotBlank() && text != savedText

    // Snapshot state, like the fields bind() resets: an abandoned composition rolls all of them back together.
    private var boundDocument by mutableStateOf<Long?>(null)
    // Compared by identity, so an equal new instance is stored and later binds stop at the identity check.
    private var boundProject by mutableStateOf<CircuitProject?>(null, referentialEqualityPolicy())
    val documentIdentity: Long? get() = boundDocument

    /** Runs during composition; its reads are not observed, so only the panel's own reads recompose it. */
    fun bind(documentIdentity: Long, project: CircuitProject) = Snapshot.withoutReadObservation {
        val previous = boundDocument
        if (previous != documentIdentity) {
            if (previous != null) {
                text = ""
                savedText = ""
                circuitName = null
                failure = null
                notice = null
                endOpenRequests()
            }
            session = null
            running = false
        } else if (boundProject !== project && boundProject != project) {
            session = null
            running = false
            if (circuitName != null && project.circuits.none { it.name == circuitName }) circuitName = null
        }
        boundDocument = documentIdentity
        boundProject = project
    }

    /** Starts a file request and returns its token. */
    fun beginOpen(): Int {
        opening = true
        notice = null
        return ++openRequest
    }

    /** Ends [request]; a request replaced by a later one, or by a document change, leaves [opening] alone. */
    fun finishOpen(request: Int) {
        if (request == openRequest) opening = false
    }

    private fun endOpenRequests() {
        openRequest++
        opening = false
    }

    /** Replaces the text with a file's contents, which become the saved text. */
    fun opened(contents: String) {
        text = contents
        savedText = contents
        session = null
        failure = null
    }

    fun markSaved(contents: String) {
        savedText = contents
    }

    fun circuit(project: CircuitProject): String =
        circuitName?.takeIf { name -> project.circuits.any { it.name == name } } ?: project.mainCircuitName

    /** Parses the text against the chosen circuit; a failure is shown in the panel and leaves no session. */
    fun load(project: CircuitProject, registry: ComponentRegistry): TestVectorRunSession? = try {
        editorTestVectorSession(project, circuit(project), text, registry).also {
            session = it
            failure = null
            revision++
        }
    } catch (error: IllegalArgumentException) {
        session = null
        failure = error.localizedDescription(message(Messages.VectorInvalid))
        null
    }
}

/** UTF-8 text with an optional byte-order mark, or null when the bytes are not UTF-8. */
internal fun decodeVectorFile(bytes: ByteArray): String? = try {
    bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
} catch (_: CharacterCodingException) {
    null
}

fun testVectorEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    assets: DocumentAssetActions? = null,
): EditorTool {
    val state = TestVectorToolState()
    return EditorTool("test-vectors", "Test vectors", titleText = message(Messages.VectorTitle), requiresProjectGraph = true, content = { context ->
        val localize = rememberLocalizer()
        val scope = rememberCoroutineScope()
        val latest by rememberUpdatedState(context)
        state.bind(context.documentIdentity, context.project)
        var exported by remember { mutableStateOf<String?>(null) }
        fun save(export: TestVectorExport) {
            context.exportProjectGraph {
                if (assets == null) {
                    exported = export.content
                    return@exportProjectGraph
                }
                val asset = DocumentAsset(export.name, export.mimeType, export.content.encodeToByteArray())
                assets.create(asset) { result ->
                    if (!context.isCurrentEditorContext()) return@create
                    when (result) {
                        AssetActionResult.Completed -> message(Messages.StatusFileSaved, "name" to asset.name).let {
                            export.vectorText?.let(state::markSaved)
                            state.notice = it.asResult()
                            context.showNotice(it)
                        }
                        is AssetActionResult.Failure -> { state.notice = result.notice.asError(); context.showNotice(result.notice) }
                        AssetActionResult.Cancelled -> Unit
                    }
                }
            }
        }
        val openFile: (() -> Unit)? = assets?.let { actions -> {
            val identity = context.documentIdentity
            val request = state.beginOpen()
            try {
                actions.open(listOf("text/plain", "application/octet-stream"), VECTOR_FILE_MAX_BYTES) { result ->
                    when (result) {
                        is AssetOpenResult.Opened -> {
                            fun deliver(decoded: String?, sheetClosed: Boolean) {
                                if (state.documentIdentity != identity) return
                                val notice = if (decoded == null) message("vector_file_utf8").asError() else {
                                    state.opened(decoded)
                                    message("vector_file_opened", "name" to result.asset.name).asResult()
                                }
                                state.notice = notice
                                if (decoded != null) state.load(latest.project, registry)
                                // With the sheet closed, the status line says what became of the chosen file, unless the
                                // editor has moved on: bind() only runs while the sheet is composed, so the identity
                                // check above can be stale, and the status line belongs to the document shown now.
                                if (sheetClosed && latest.isCurrentEditorContext()) latest.showNotice(notice.text)
                            }
                            // UNDISPATCHED starts the block even when the sheet closed while the picker was up and its
                            // scope is cancelled, so the finally ends the request instead of leaving every control disabled.
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    deliver(withContext(Dispatchers.Default) { decodeVectorFile(result.asset.bytes) }, sheetClosed = false)
                                } catch (cancelled: CancellationException) {
                                    // The sheet closed before the file was decoded: the file the user chose still reaches
                                    // the tool, decoded here (at most VECTOR_FILE_MAX_BYTES), so reopening shows it.
                                    deliver(decodeVectorFile(result.asset.bytes), sheetClosed = true)
                                    throw cancelled
                                } finally {
                                    state.finishOpen(request)
                                }
                            }
                        }
                        is AssetOpenResult.Failure -> {
                            state.finishOpen(request)
                            if (state.documentIdentity == identity) state.notice = result.notice.asError()
                        }
                        AssetOpenResult.Cancelled -> state.finishOpen(request)
                    }
                }
            } catch (error: Exception) {
                state.finishOpen(request)
                state.notice = error.localizedDescription(message("vector_file_open_failed")).asError()
            }
        } }
        // Opening a file replaces the editor text, so text with changes not saved in a file (typed, or an opened file
        // edited since) is confirmed first.
        var confirmReplace by remember { mutableStateOf(false) }
        val requestOpen: (() -> Unit)? = openFile?.let { open -> { if (state.hasUnsavedText) confirmReplace = true else open() } }
        TestVectorPanel(context.project, state, onExport = ::save, registry = registry, onOpenFile = requestOpen)
        if (confirmReplace && openFile != null) {
            AppAlertDialog(onDismissRequest = { confirmReplace = false },
                title = { Text(localize("vector_replace_title")) },
                text = { Text(localize("vector_replace_text")) },
                confirmButton = {
                    TextButton(onClick = { confirmReplace = false; openFile() }, modifier = Modifier.testTag("vector-replace-confirm")) {
                        Text(localize("vector_replace_confirm"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReplace = false }, modifier = Modifier.testTag("vector-replace-cancel")) {
                        Text(localize(Messages.ActionCancel))
                    }
                })
        }
        exported?.let { text ->
            @Suppress("DEPRECATION")
            val clipboard = LocalClipboardManager.current
            // The dialog covers the sheet: Copy's result is shown in it, as the Memory editor's Save file (C74).
            val sheetNotice = LocalToolSheetNotice.current
            var copiedAfter by remember(text) { mutableStateOf<Long?>(null) }
            AppAlertDialog(onDismissRequest = { exported = null }, title = { Text(localize(Messages.VectorExportTitle)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, onValueChange = {}, readOnly = true, minLines = 5, maxLines = 12)
                    AccessibleEditorNotice(sheetNotice?.takeIf { notice -> copiedAfter?.let { notice.serial > it } == true }?.text,
                        Modifier.testTag("vector-copy-notice"))
                } },
                confirmButton = { TextButton(onClick = {
                    copiedAfter = sheetNotice?.serial ?: 0L
                    context.exportProjectGraph { clipboard.setText(AnnotatedString(text)); context.showNotice(message(Messages.VectorCopied)) }
                }) { Text(localize(Messages.TraceCopy)) } },
                dismissButton = { TextButton(onClick = { exported = null }) { Text(localize(Messages.TraceClose)) } })
        }
    })
}
