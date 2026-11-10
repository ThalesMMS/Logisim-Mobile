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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
    var logs by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf<Pair<HdlImportOptions, CompanionPlan>?>(null) }
    var completed by remember { mutableStateOf<Pair<HdlImportOptions, CompanionJob>?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var jobClient by remember { mutableStateOf<CompanionClient?>(null) }

    fun changed() { planned = null; completed = null; notice = null }
    fun stage(file: HdlInputFile) {
        files = files.filterNot { it.name == file.name } + file
        changed()
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("hdl-import-scroll"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(loc(Messages.HdlToolsImportDescription))
        TextButton(enabled = idle, onClick = {
            format = HdlSourceFormat.entries[(format.ordinal + 1) % HdlSourceFormat.entries.size]; changed()
        }) { Text(loc(Messages.HdlToolsImportFormat, "format" to when (format) {
            HdlSourceFormat.VERILOG -> "Verilog"
            HdlSourceFormat.SYSTEM_VERILOG -> "SystemVerilog"
            HdlSourceFormat.BLIF -> "BLIF"
        })) }
        if (assets != null) OutlinedTextField(pickerDirectory, { pickerDirectory = it },
            label = { Text(loc(Messages.HdlToolsImportPickerDirectory)) }, enabled = idle, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("hdl-import-picker-directory"))
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
                                stage(HdlInputFile(path, asset.bytes.decodeToString(throwOnInvalidSequence = true)))
                            } catch (_: CharacterCodingException) {
                                notice = message(Messages.HdlToolsImportSourceUtf8)
                            } catch (error: IllegalArgumentException) {
                                notice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile))
                            }
                        }
                        is AssetOpenResult.Failure -> notice = result.notice
                        AssetOpenResult.Cancelled -> Unit
                    }
                }
            } catch (error: Exception) {
                picking = false
                notice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile))
            }
        }) { Text(loc(Messages.HdlToolsImportPickFile)) }
        OutlinedTextField(fileName, { fileName = it; changed() }, label = { Text(loc(Messages.HdlToolsImportFileName)) }, enabled = idle,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("hdl-import-file-name"))
        val hdlStyle = rememberHdlEditorStyle()
        OutlinedTextField(source, { source = it; changed() }, label = { Text(loc(Messages.HdlToolsImportSource)) }, enabled = idle,
            minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("hdl-import-source"), visualTransformation = hdlStyle.transformation, colors = hdlStyle.colors(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
        Button(modifier = Modifier.testTag("hdl-import-add-file"), enabled = idle && source.isNotBlank(), onClick = {
            try {
                stage(HdlInputFile(fileName.trim(), source))
                source = ""
            } catch (error: IllegalArgumentException) {
                notice = error.localizedDescription(message(Messages.HdlToolsImportInvalidFile))
            }
        }) { Text(loc(Messages.HdlToolsImportAddFile)) }
        for (file in files) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(modifier = Modifier.weight(1f), enabled = idle,
                onClick = { fileName = file.name; source = file.text; changed() }) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = idle, onClick = { files = files - file; changed() }) { Text(loc(Messages.HdlToolsImportRemoveFile)) }
        }
        OutlinedTextField(top, { top = it; changed() }, label = { Text(loc(Messages.HdlToolsImportTop)) },
            enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(defines, { defines = it; changed() }, label = { Text(loc(Messages.HdlToolsImportDefines)) },
            enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = idle, onClick = {
            undefined = HdlUndefinedPolicy.entries[(undefined.ordinal + 1) % HdlUndefinedPolicy.entries.size]; changed()
        }) { Text(loc(Messages.HdlToolsImportUndefined, "policy" to message(when (undefined) {
            HdlUndefinedPolicy.REJECT -> Messages.HdlToolsImportUndefinedReject
            HdlUndefinedPolicy.ZERO -> Messages.HdlToolsImportUndefinedZero
            HdlUndefinedPolicy.ONE -> Messages.HdlToolsImportUndefinedOne
        }))) }
        val memoryPolicyLabel = if (format == HdlSourceFormat.BLIF || memoryPolicy == HdlMemoryPolicy.EXPAND) {
            loc("hdl_tools_import_memory_expand")
        } else loc(Messages.HdlToolsImportMemoryPolicy)
        TextButton(
            enabled = idle && format != HdlSourceFormat.BLIF,
            onClick = {
                memoryPolicy = if (memoryPolicy == HdlMemoryPolicy.NATIVE) HdlMemoryPolicy.EXPAND else HdlMemoryPolicy.NATIVE
                changed()
            },
        ) { Text(memoryPolicyLabel) }
        Button(modifier = Modifier.testTag("hdl-import-prepare"), enabled = idle && files.isNotEmpty(), onClick = {
            val client = companion()
            if (client == null) notice = message(Messages.HdlToolsImportConnectCompanion) else scope.launch {
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
                    notice = message(Messages.HdlToolsImportReviewPlan)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notice = error.localizedDescription(message(Messages.HdlToolsImportPrepareFailed))
                } finally { busy = false }
            }
        }) { Text(loc(if (busy && jobId == null) Messages.HdlToolsImportPreparing else Messages.HdlToolsImportPrepare)) }
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
                    busy = true; planned = null; notice = message(Messages.HdlToolsImportSynthesizing)
                    try {
                        val job = awaitHdlJob(client, options.request(), approval) { update ->
                            jobId = update.jobId; logs = update.logs.joinToString("\n") { it.text }.takeLast(16_384)
                        }
                        if (job.state == CompanionJobState.SUCCEEDED) {
                            completed = options to job; notice = message(Messages.HdlToolsImportSynthesisComplete)
                        } else notice = job.error?.let {
                            message(Messages.HdlToolsImportProviderError, "code" to it.code, "detail" to LocalizedText.literal(it.message))
                        } ?: message(Messages.HdlToolsImportCancelled)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        notice = error.localizedDescription(message(Messages.HdlToolsImportSynthesisFailed))
                    } finally { busy = false; jobId = null }
                }
            }) { Text(loc(Messages.HdlToolsImportConfirm)) }
        }
        if (busy && jobId != null) TextButton(modifier = Modifier.testTag("hdl-import-cancel"), onClick = {
            val id = jobId ?: return@TextButton
            val client = jobClient ?: return@TextButton
            scope.launch { try { client.cancel(id); notice = message(Messages.HdlToolsImportCancelRequested) } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice = error.localizedDescription(message(Messages.HdlToolsImportCancelFailed))
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
                            notice = imported; latestContext.showNotice(imported); completed = null
                        } else notice = message(Messages.HdlToolsImportProjectChanged)
                        is HdlImportResult.Failure -> notice = LocalizedText.join(result.diagnostics.map {
                            message(Messages.HdlToolsImportDiagnostic, "code" to it.code,
                                "location" to it.module?.let { module -> " [$module${it.cell?.let { cell -> "/$cell" }.orEmpty()}]" }.orEmpty(),
                                "detail" to it.notice)
                        })
                    }
                } finally { busy = false }
            }
        }) { Text(loc(Messages.HdlToolsImportApply)) } }
        notice?.let { SelectionContainer { Text(loc(it)) } }
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
