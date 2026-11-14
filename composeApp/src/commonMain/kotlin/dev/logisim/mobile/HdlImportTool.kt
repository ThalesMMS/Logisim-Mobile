package dev.logisim.mobile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A paired companion is supplied by the host; opening this panel performs no network action. */
fun hdlImportTool(
    companion: () -> CompanionClient?,
    registry: ComponentRegistry = ComponentCatalog.registry,
    assets: DocumentAssetActions? = null,
): EditorTool =
    EditorTool("hdl-import", "Import Verilog / BLIF", titleText = message(Messages.HdlToolsImportTitle)) { context ->
    val loc = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val latestContext by rememberUpdatedState(context)
    var files by remember { mutableStateOf(emptyList<HdlInputFile>()) }
    var fileName by remember { mutableStateOf("design.v") }
    var pickerDirectory by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(HdlSourceFormat.VERILOG) }
    var top by remember { mutableStateOf("") }
    var defines by remember { mutableStateOf("") }
    var undefined by remember { mutableStateOf(HdlUndefinedPolicy.REJECT) }
    var memoryPolicy by remember { mutableStateOf(HdlMemoryPolicy.NATIVE) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val idle = !busy && !picking
    // File errors appear under the file buttons; prepare, synthesis and apply results under the action buttons (#85).
    var fileNotice by remember { mutableStateOf<ToolNotice?>(null) }
    var notice by remember { mutableStateOf<ToolNotice?>(null) }
    var logs by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf<Pair<HdlImportOptions, CompanionPlan>?>(null) }
    var completed by remember { mutableStateOf<Pair<HdlImportOptions, CompanionJob>?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var jobClient by remember { mutableStateOf<CompanionClient?>(null) }

    // The staged file loaded back into the editor: Add updates it without asking, and its row is marked.
    var editing by remember { mutableStateOf<String?>(null) }
    // A staged file another text would replace (from the editor or the picker), and a staged file that would replace
    // unsaved editor text: each waits for a confirmation.
    var replacing by remember { mutableStateOf<Pair<HdlInputFile, Boolean>?>(null) }
    var opening by remember { mutableStateOf<HdlInputFile?>(null) }

    fun changed() { planned = null; completed = null; notice = null; fileNotice = null }
    fun stage(file: HdlInputFile) {
        files = files.filterNot { it.name == file.name } + file
        changed()
    }
    /** Staged from the editor: the editor is cleared and the name moves on, so the next Add does not replace it. */
    fun stageFromEditor(file: HdlInputFile) {
        stage(file)
        source = ""
        editing = null
        fileName = nextHdlImportName(file.name, files.map { it.name })
    }
    /** Staged from the picker: the editor keeps its text, which no longer matches a file it was loaded from. */
    fun stagePicked(file: HdlInputFile) {
        stage(file)
        if (editing == file.name) editing = null
    }
    /** [file] in the editor, marked as the one Add updates. */
    fun load(file: HdlInputFile) {
        fileName = file.name
        source = file.text
        editing = file.name
        changed()
    }

    replacing?.let { (file, fromEditor) ->
        AppAlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text(loc("hdl_tools_import_replace_title", "name" to file.name)) },
            text = { Text(loc(if (fromEditor) "hdl_tools_import_replace_message" else "hdl_tools_import_replace_picked_message")) },
            confirmButton = {
                TextButton(onClick = { replacing = null; if (fromEditor) stageFromEditor(file) else stagePicked(file) },
                    modifier = Modifier.testTag("hdl-import-replace-confirm")) {
                    Text(loc("hdl_tools_import_replace_confirm"))
                }
            },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text(loc(Messages.ActionCancel)) } },
        )
    }
    opening?.let { file ->
        AppAlertDialog(
            onDismissRequest = { opening = null },
            title = { Text(loc("hdl_tools_import_discard_title")) },
            text = { Text(loc("hdl_tools_import_discard_message", "name" to file.name)) },
            confirmButton = {
                TextButton(onClick = { opening = null; load(file) }, modifier = Modifier.testTag("hdl-import-discard-confirm")) {
                    Text(loc("hdl_tools_import_discard_confirm"))
                }
            },
            dismissButton = { TextButton(onClick = { opening = null }) { Text(loc(Messages.ActionCancel)) } },
        )
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("hdl-import-scroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(loc(Messages.HdlToolsImportDescription))
        // Every option is visible and selectable, instead of a button that cycles through hidden values (#80).
        ChoiceRow(loc("hdl_tools_import_format_label"), HdlSourceFormat.entries, format, enabled = idle,
            optionLabel = { hdlSourceFormatName(it) }, optionTag = { "hdl-import-format-${it.name}" }) { format = it; changed() }
        if (assets != null) OutlinedTextField(pickerDirectory, { pickerDirectory = it },
            label = { Text(loc(Messages.HdlToolsImportPickerDirectory)) }, enabled = idle, singleLine = true,
            keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag("hdl-import-picker-directory"))
        if (assets != null) Button(enabled = idle, onClick = {
            picking = true
            try {
                assets.open(listOf("application/octet-stream"), 128 * 1024) { result ->
                    picking = false
                    when (result) {
                        is AssetOpenResult.Opened -> {
                            try {
                                val asset = result.asset
                                val path = pickerDirectory.trim().takeIf { it.isNotEmpty() }
                                    ?.let { "$it/${asset.name}" } ?: asset.name
                                val file = HdlInputFile(path, asset.bytes.decodeToString(throwOnInvalidSequence = true))
                                // A staged file with this name and other text is replaced only after a confirmation.
                                val staged = files.firstOrNull { it.name == file.name }
                                if (staged != null && staged.text != file.text) replacing = file to false else stagePicked(file)
                            } catch (_: CharacterCodingException) {
                                fileNotice = message(Messages.HdlToolsImportSourceUtf8).asError()
                            } catch (error: IllegalArgumentException) {
                                fileNotice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile)).asError()
                            }
                        }
                        is AssetOpenResult.Failure -> fileNotice = result.notice.asError()
                        AssetOpenResult.Cancelled -> Unit
                    }
                }
            } catch (error: Exception) {
                picking = false
                fileNotice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile)).asError()
            }
        }) { Text(loc(Messages.HdlToolsImportPickFile)) }
        OutlinedTextField(fileName, {
            fileName = it
            // Another name no longer edits the loaded file, so its marker goes and Add asks before replacing.
            if (editing != null && it.trim() != editing) editing = null
            changed()
        }, label = { Text(loc(Messages.HdlToolsImportFileName)) }, enabled = idle,
            singleLine = true, keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag("hdl-import-file-name"))
        val hdlStyle = rememberHdlEditorStyle()
        OutlinedTextField(source, { source = it; changed() }, label = { Text(loc(Messages.HdlToolsImportSource)) }, enabled = idle,
            minLines = 5, maxLines = 12, keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag("hdl-import-source"), visualTransformation = hdlStyle.transformation, colors = hdlStyle.colors(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
        Button(modifier = Modifier.testTag("hdl-import-add-file"), enabled = idle && source.isNotBlank(), onClick = {
            try {
                val file = HdlInputFile(fileName.trim(), source)
                val staged = files.firstOrNull { it.name == file.name }
                // Another file's text under the same name is replaced only after a confirmation.
                if (staged != null && staged.text != file.text && editing != file.name) replacing = file to true
                else stageFromEditor(file)
            } catch (error: IllegalArgumentException) {
                fileNotice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile)).asError()
            }
        }) { Text(loc(Messages.HdlToolsImportAddFile)) }
        ToolNoticeText(fileNotice, Modifier.testTag("hdl-import-file-notice"))
        for (file in files) Row(Modifier.fillMaxWidth().testTag("hdl-import-staged-${file.name}"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val open = editing == file.name
            TextButton(modifier = Modifier.weight(1f).semantics {
                if (open) stateDescription = loc("hdl_tools_import_editing")
            }, enabled = idle, onClick = {
                // Text in the editor that no staged file holds is not thrown away without asking.
                val unsaved = source.isNotBlank() && files.none { it.name == fileName.trim() && it.text == source }
                if (unsaved && source != file.text) opening = file else load(file)
            }) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (open) FontWeight.Bold else null)
                if (open) Text(" · " + loc("hdl_tools_import_editing"), maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
            TextButton(enabled = idle, onClick = {
                files = files - file
                if (editing == file.name) editing = null
                changed()
            }) { Text(loc(Messages.HdlToolsImportRemoveFile)) }
        }
        OutlinedTextField(top, { top = it; changed() }, label = { Text(loc(Messages.HdlToolsImportTop)) },
            enabled = idle, singleLine = true, keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(defines, { defines = it; changed() }, label = { Text(loc(Messages.HdlToolsImportDefines)) },
            enabled = idle, singleLine = true, keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth())
        ChoiceRow(loc("hdl_tools_import_undefined_label"), HdlUndefinedPolicy.entries, undefined, enabled = idle,
            // Option labels have their own keys, so translators control the capitalization (#80).
            optionLabel = { policy -> loc(when (policy) {
                HdlUndefinedPolicy.REJECT -> "hdl_tools_import_undefined_reject_option"
                HdlUndefinedPolicy.ZERO -> "hdl_tools_import_undefined_zero_option"
                HdlUndefinedPolicy.ONE -> "hdl_tools_import_undefined_one_option"
            }) },
            optionTag = { "hdl-import-undefined-${it.name}" }) { undefined = it; changed() }
        // BLIF has no memories to keep native, so the choice shows "expand" and is disabled, as before.
        ChoiceRow(loc("hdl_tools_import_memory_label"), HdlMemoryPolicy.entries,
            if (format == HdlSourceFormat.BLIF) HdlMemoryPolicy.EXPAND else memoryPolicy,
            enabled = idle && format != HdlSourceFormat.BLIF,
            optionLabel = { policy -> loc(if (policy == HdlMemoryPolicy.NATIVE) "hdl_tools_import_memory_native_option"
                else "hdl_tools_import_memory_expand_option") },
            optionTag = { "hdl-import-memory-${it.name}" }) { memoryPolicy = it; changed() }
        Button(modifier = Modifier.testTag("hdl-import-prepare"), enabled = idle && files.isNotEmpty(), onClick = {
            val client = companion()
            if (client == null) notice = message(Messages.HdlToolsImportConnectCompanion).asError() else scope.launch {
                busy = true; planned = null; completed = null; logs = ""
                try {
                    val roots = files.filter { when (format) {
                        HdlSourceFormat.BLIF -> it.name.endsWith(".blif", true) || it.name.endsWith(".blifmv", true)
                        else -> it.name.endsWith(".v", true) || it.name.endsWith(".sv", true)
                    } }.map { it.name }
                    val options = HdlImportOptions(files, roots.ifEmpty { files.map { it.name } }, format, top.trim(),
                        defines.split(',').map { it.trim() }.filter { it.isNotEmpty() }, undefined, memoryPolicy)
                    val request = options.request()
                    planned = options to client.plan(request); jobClient = client
                    notice = message(Messages.HdlToolsImportReviewPlan).asResult()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notice = error.localizedDescription(message(Messages.HdlToolsImportPrepareFailed)).asError()
                } finally { busy = false }
            }
        }) { Text(loc(if (busy && jobId == null) Messages.HdlToolsImportPreparing else Messages.HdlToolsImportPrepare)) }
        ToolNoticeText(notice, Modifier.testTag("hdl-import-notice"), selectable = true)
        planned?.let { (options, plan) ->
            HorizontalDivider()
            val inputPaths = plan.parameters.fields["inputPaths"] as? CompanionJsonObject
            Text(loc(Messages.HdlToolsImportPlan, "files" to plan.inputs.joinToString {
                val name = (inputPaths?.fields?.get(it.name) as? CompanionJsonString)?.value ?: it.name
                loc(Messages.HdlToolsImportPlanFile, "name" to name, "size" to it.size)
            }))
            Button(modifier = Modifier.testTag("hdl-import-confirm"), enabled = idle, onClick = {
                val client = jobClient ?: return@Button
                val approval = plan.confirm()
                scope.launch {
                    busy = true; planned = null; notice = message(Messages.HdlToolsImportSynthesizing).asProgress()
                    try {
                        val job = awaitHdlJob(client, options.request(), approval) { update ->
                            jobId = update.jobId; logs = update.logs.joinToString("\n") { it.text }.takeLast(16_384)
                        }
                        if (job.state == CompanionJobState.SUCCEEDED) {
                            completed = options to job; notice = message(Messages.HdlToolsImportSynthesisComplete).asResult()
                        } else notice = job.error?.let {
                            message(Messages.HdlToolsImportProviderError, "code" to it.code, "detail" to LocalizedText.literal(it.message)).asError()
                        } ?: message(Messages.HdlToolsImportCancelled).asResult()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        notice = error.localizedDescription(message(Messages.HdlToolsImportSynthesisFailed)).asError()
                    } finally { busy = false; jobId = null }
                }
            }) { Text(loc(Messages.HdlToolsImportConfirm)) }
        }
        if (busy && jobId != null) TextButton(modifier = Modifier.testTag("hdl-import-cancel"), onClick = {
            val id = jobId ?: return@TextButton
            val client = jobClient ?: return@TextButton
            scope.launch { try { client.cancel(id); notice = message(Messages.HdlToolsImportCancelRequested).asProgress() } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = error.localizedDescription(message(Messages.HdlToolsImportCancelFailed)).asError()
            } }
        }) { Text(loc(Messages.HdlToolsImportCancel)) }
        completed?.let { (options, job) -> Button(modifier = Modifier.testTag("hdl-import-apply"), enabled = idle, onClick = {
            scope.launch {
                busy = true
                val current = latestContext.project
                try {
                    when (val result = withContext(Dispatchers.Default) { job.importHdl(options, current, registry) }) {
                        is HdlImportResult.Success -> if (latestContext.project == current) {
                            latestContext.applyProject(result.project); latestContext.selectCircuit(result.project.mainCircuit.id)
                            val imported = LocalizedText(Messages.HdlToolsImportImportedCount, quantity = result.importedCircuits.size.toLong())
                            notice = imported.asResult(); latestContext.showNotice(imported); completed = null
                        } else notice = message(Messages.HdlToolsImportProjectChanged).asError()
                        is HdlImportResult.Failure -> notice = LocalizedText.join(result.diagnostics.map {
                            message(Messages.HdlToolsImportDiagnostic, "code" to it.code,
                                "location" to it.module?.let { module -> " [$module${it.cell?.let { cell -> "/$cell" }.orEmpty()}]" }.orEmpty(),
                                "detail" to it.notice)
                        }).asError()
                    }
                } finally { busy = false }
            }
        }) { Text(loc(Messages.HdlToolsImportApply)) } }
        if (logs.isNotBlank()) {
            Text(loc(Messages.HdlToolsImportProviderLogs))
            SelectionContainer { Text(logs, fontFamily = FontFamily.Monospace) }
        }
    }
}

internal suspend fun awaitHdlJob(
    client: CompanionClient,
    request: CompanionJobRequest,
    approval: CompanionApproval? = null,
    onUpdate: (CompanionJob) -> Unit = {},
): CompanionJob {
    var job = client.submit(request, approval)
    onUpdate(job)
    while (!job.state.terminal) {
        delay(200)
        job = client.status(job.jobId)
        onUpdate(job)
    }
    return job
}

/**
 * The name the editor proposes after Add: the same path and extension with the next free number (design.v →
 * design2.v → design3.v), so the root-file filter (.v, .sv, .blif) still recognizes it.
 */
internal fun nextHdlImportName(name: String, used: Collection<String>): String {
    val slash = name.lastIndexOf('/')
    val dot = name.lastIndexOf('.').takeIf { it > slash + 1 } ?: name.length
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    val base = stem.substring(0, slash + 1) + stem.substring(slash + 1).trimEnd { it.isDigit() }.ifEmpty { stem.substring(slash + 1) }
    return generateSequence(2) { it + 1 }.map { "$base$it$extension" }.first { it !in used }
}

internal fun hdlSourceFormatName(format: HdlSourceFormat): String = when (format) {
    HdlSourceFormat.VERILOG -> "Verilog"
    HdlSourceFormat.SYSTEM_VERILOG -> "SystemVerilog"
    HdlSourceFormat.BLIF -> "BLIF"
}
