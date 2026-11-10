package dev.logisim.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CompanionTclClient
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.MAX_TCL_INCLUDES
import dev.logisim.mobile.core.TclAutomaticTicks
import dev.logisim.mobile.core.TclComponentEvent
import dev.logisim.mobile.core.TclComponents
import dev.logisim.mobile.core.TclConsoleRequest
import dev.logisim.mobile.core.TclConsoleReply
import dev.logisim.mobile.core.TclExecutionClient
import dev.logisim.mobile.core.TclRuntimeState
import dev.logisim.mobile.core.TclRuntimePhase
import dev.logisim.mobile.core.TclSourceFile
import dev.logisim.mobile.core.TclSubmissionState
import dev.logisim.mobile.core.TclTkFrame
import dev.logisim.mobile.core.TclTkInput
import dev.logisim.mobile.core.validateTclSourceFiles
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

data class TclComponentActions(
    val sendEvent: (String, TclComponentEvent) -> Boolean,
)

private enum class IsolatedTclConsole(val title: String, val consent: String) {
    Timed(Messages.TclTimedTitle, Messages.TclTimedConsent),
    TkPreview(Messages.TclTkPreviewTitle, Messages.TclTkPreviewConsent),
    TkLive(Messages.TclTkLiveTitle, Messages.TclTkLiveConsent),
}

private data class PendingTclConsole(val source: TclSourceFile, val mode: IsolatedTclConsole)

/** Component frames run in the editor only after the user approves a source in the panel. */
fun tclEditorTool(
    client: TclExecutionClient?,
    assets: DocumentAssetActions? = null,
    componentActions: (EditorToolContext) -> TclComponentActions? = { null },
): EditorTool {
    val runtime = TclFrameRuntime(client)
    return EditorTool("tcl", Messages.TclTitle, titleText = message(Messages.TclTitle), runtime = { context ->
        val latest = rememberUpdatedState(context)
        DisposableEffect(runtime) { onDispose { runtime.close() } }
        LaunchedEffect(runtime, context.project, context.projectSimulation, context.documentIdentity,
            context.isForeground, context.pendingSimulationCommands, runtime.revision) {
            runtime.synchronize(latest.value)
        }
    }) { context -> TclPanel(context, client, assets, componentActions(context), runtime) }
}

@Composable
private fun TclPanel(
    context: EditorToolContext,
    client: TclExecutionClient?,
    assets: DocumentAssetActions?,
    actions: TclComponentActions?,
    runtime: TclFrameRuntime,
) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val submissions = remember { TclSubmissionState() }
    var stdout by remember { mutableStateOf("") }
    var stderr by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var requestCounter by remember { mutableStateOf(0L) }
    val sessionId = remember { Random.nextLong().toULong().toString(16) }
    val component = context.selectedComponents.singleOrNull()?.takeIf {
        it.kind == TclComponents.GENERIC || it.kind == TclComponents.CONSOLE_REDS
    }
    val instance = Triple(context.document.id, context.simulation.instancePath, component?.id)
    val approved = component?.let { runtime.source(context, it) }
    var script by remember(context.documentIdentity, instance) { mutableStateOf(approved?.text ?: "expr {6 * 7}") }
    var fileName by remember(context.documentIdentity, instance) { mutableStateOf(approved?.name ?: "component.tcl") }
    var includes by remember(client, assets, context.documentIdentity, instance) {
        mutableStateOf(component?.let { runtime.includes(context, it) }.orEmpty())
    }
    var pickingSource by remember(client, assets, context.documentIdentity, instance) { mutableStateOf(false) }
    fun stopIfEditingApprovedSource() {
        if (component != null && approved != null) runtime.stop(context, component)
    }
    val isolatedClient = client as? CompanionTclClient
    var pendingConsole by remember(client, context.documentIdentity, context.document.id, context.simulation.instancePath, component?.id) {
        mutableStateOf<PendingTclConsole?>(null)
    }
    var windowPreview by remember(client, context.documentIdentity, context.document.id, context.simulation.instancePath, component?.id) {
        mutableStateOf<EmbeddedImage?>(null)
    }
    var liveFrame by remember { mutableStateOf<TclTkFrame?>(null) }
    var inputForFrame by remember { mutableStateOf<Int?>(null) }
    var keyInput by remember { mutableStateOf("") }
    val state = component?.let { context.simulation.componentStates[it.id] as? TclRuntimeState }
    var autoTicksOnStart by remember(context.documentIdentity, context.document.id, context.simulation.instancePath, component?.id) {
        mutableStateOf(false)
    }
    val activeAuthorization = component?.let { runtime.automaticTickAuthorization(context, it) }
    val pendingAutomaticTick = state != null && activeAuthorization != null &&
        state.tickRequest(activeAuthorization).matches(state)
    fun cancelAutomaticTicks() {
        if (component != null) runtime.revokeAutomaticTicks(context, component)
    }

    fun cancel() {
        cancelAutomaticTicks()
        val requestId = submissions.cancel()
        job?.cancel()
        running = false
        liveFrame = null
        windowPreview = null
        if (client != null && requestId != null) scope.launch {
            runCatching { client.cancel(requestId) }.onFailure { notice = it.localizedDescription(message(Messages.TclErrorCancel)) }
        }
    }
    DisposableEffect(client, assets, context.documentIdentity, context.document.id, context.simulation.instancePath, component?.id) {
        onDispose {
            pickingSource = false
            submissions.cancel()
            job?.cancel()
            running = false
            liveFrame = null
            inputForFrame = null
            notice = null
        }
    }

    fun consoleSource(): TclSourceFile? = runCatching { TclSourceFile(fileName, script) }.getOrElse {
        notice = it.localizedDescription(message(Messages.TclErrorSource))
        null
    }

    fun launchConsole(source: TclSourceFile, execute: suspend (TclConsoleRequest) -> TclConsoleReply) {
        if (running || !context.isCurrentEditorContext()) return
        val requestId = "console-$sessionId-${++requestCounter}"
        val generation = submissions.begin(requestId)
        windowPreview = null
        liveFrame = null
        inputForFrame = null
        keyInput = ""
        running = true
        job = scope.launch {
            try {
                val reply = execute(TclConsoleRequest(requestId, source))
                requireLocalized(reply.sourceSha256 == source.sha256) { message(Messages.TclErrorSourceReply) }
                if (submissions.complete(requestId, generation)) {
                    notice = message(Messages.TclConsoleResult, "result" to reply.result)
                    stdout = reply.stdout
                    stderr = reply.stderr
                    windowPreview = reply.windowPreview
                }
            } catch (failure: CancellationException) {
                if (submissions.complete(requestId, generation)) {
                    liveFrame = null
                    windowPreview = null
                }
                throw failure
            } catch (failure: Exception) {
                if (submissions.complete(requestId, generation)) {
                    liveFrame = null
                    windowPreview = null
                    notice = failure.localizedDescription(message(Messages.TclErrorExecution))
                }
            } finally { if (submissions.generation == generation) running = false }
        }
    }

    fun deliverInput(input: TclTkInput) {
        val frame = liveFrame ?: return
        if (!running || !frame.acceptsInput || inputForFrame == frame.sequence || !context.isCurrentEditorContext()) return
        inputForFrame = frame.sequence
        scope.launch {
            try {
                frame.send(input)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (liveFrame === frame) {
                    inputForFrame = null
                    notice = failure.localizedDescription(message(Messages.TclTkInputUnavailable))
                }
            }
        }
    }

    val toApprove = pendingConsole
    if (isolatedClient != null && toApprove != null) {
        AppAlertDialog(
            onDismissRequest = { pendingConsole = null },
            title = { Text(strings(toApprove.mode.title)) },
            text = { Text(strings(toApprove.mode.consent, "file" to toApprove.source.name, "host" to isolatedClient.hostLabel)) },
            confirmButton = { TextButton(onClick = {
                pendingConsole = null
                when (toApprove.mode) {
                    IsolatedTclConsole.Timed -> launchConsole(toApprove.source, isolatedClient::runApprovedTimedConsole)
                    IsolatedTclConsole.TkPreview -> launchConsole(toApprove.source, isolatedClient::runApprovedTkConsole)
                    IsolatedTclConsole.TkLive -> launchConsole(toApprove.source) { request ->
                        isolatedClient.runApprovedTkLive(request) { frame ->
                            if (submissions.activeRequestId == request.requestId) {
                                liveFrame = frame
                                windowPreview = frame.image
                            }
                        }
                    }
                }
            }) { Text(strings(Messages.TclTimedRun)) } },
            dismissButton = { TextButton(onClick = { pendingConsole = null }) { Text(strings(Messages.TclCancel)) } },
        )
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings(Messages.TclHost, "host" to (client?.hostLabel ?: message(Messages.TclNoCompanion))))
        Text(strings(Messages.TclLimits))
        OutlinedTextField(fileName, { if (it != fileName) stopIfEditingApprovedSource(); fileName = it }, label = { Text(strings(Messages.TclSourceName)) }, enabled = !running && !pickingSource, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(script, { if (it != script) stopIfEditingApprovedSource(); script = it }, label = { Text(strings(Messages.TclSourceLabel)) }, enabled = !running && !pickingSource, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
        Text(strings(Messages.TclConsoleHeading))
        Text(strings(Messages.TclConsoleExplanation))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running && client?.available == true, onClick = {
                val host = client ?: return@Button
                val source = consoleSource() ?: return@Button
                launchConsole(source, host::runConsole)
            }) { Text(strings(Messages.TclConsoleRun)) }
            if (isolatedClient != null) for (mode in IsolatedTclConsole.entries) {
                OutlinedButton(enabled = !running && isolatedClient.available, onClick = {
                    pendingConsole = consoleSource()?.let { PendingTclConsole(it, mode) }
                }) { Text(strings(mode.title)) }
            }
            OutlinedButton(enabled = running, onClick = { cancel() }) { Text(strings(Messages.TclCancel)) }
        }
        windowPreview?.let { preview ->
            val bitmap = remember(preview) { runCatching { decodeEmbeddedAnnotationImage(preview.bytes) }.getOrNull() }
            val frame = liveFrame
            val interactive = running && frame?.acceptsInput == true
            val canSend = interactive && inputForFrame != frame.sequence
            val tapModifier = if (canSend) Modifier.pointerInput(frame.sequence) {
                detectTapGestures { position ->
                    if (size.width > 0 && size.height > 0) deliverInput(TclTkInput.Tap(
                        (position.x * 640 / size.width).toInt().coerceIn(0, 639),
                        (position.y * 480 / size.height).toInt().coerceIn(0, 479),
                    ))
                }
            } else Modifier
            if (bitmap == null) Text(strings(Messages.TclTkPreviewInvalid)) else Image(
                bitmap, contentDescription = strings(Messages.TclTkPreviewDescription),
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).then(tapModifier).testTag("tcl-tk-window-preview"),
            )
            if (interactive && bitmap != null) {
                OutlinedTextField(keyInput, { text ->
                    val value = text.lowercase()
                    if (value.length <= 1 && value.all { it == ' ' || it in 'a'..'z' || it in '0'..'9' }) keyInput = value
                }, enabled = canSend, singleLine = true, label = { Text(strings(Messages.TclTkKey)) })
                OutlinedButton(enabled = canSend && keyInput.length == 1, onClick = {
                    deliverInput(TclTkInput.Key(keyInput.single()))
                    keyInput = ""
                }) { Text(strings(Messages.TclTkSendKey)) }
                OutlinedButton(enabled = canSend, onClick = { deliverInput(TclTkInput.Control.Return) }) {
                    Text(strings(Messages.TclTkSendReturn))
                }
            }
        }
        HorizontalDivider()
        Text(strings(Messages.TclComponentHeading))
        if (component == null) Text(strings(Messages.TclSelectComponent)) else {
            Text(strings(Messages.TclComponentPath, "id" to component.id, "path" to component.attributes["filePath"].orEmpty()))
            val phase = message(when (state?.phase) {
                TclRuntimePhase.WAITING -> Messages.TclPhaseWaiting
                TclRuntimePhase.READY -> Messages.TclPhaseReady
                TclRuntimePhase.FAILED -> Messages.TclPhaseFailed
                TclRuntimePhase.HISTORY_LIMIT -> Messages.TclPhaseHistoryLimit
                else -> Messages.TclPhaseStopped
            })
            Text(strings(Messages.TclComponentState, "phase" to phase, "frames" to (state?.frames?.size ?: 0)))
            state?.notice?.let { Text(strings(it)) } ?: state?.message?.let { Text(it) }
            if (assets != null) OutlinedButton(
                enabled = !running && !pickingSource && includes.size < MAX_TCL_INCLUDES,
                modifier = Modifier.testTag("tcl-attach-source"),
                onClick = {
                    if (running || pickingSource) return@OutlinedButton
                    pickingSource = true
                    try {
                        assets.open(listOf("application/octet-stream"), TclSourceFile.MAX_BYTES) { result ->
                            if (!pickingSource) return@open
                            pickingSource = false
                            when (result) {
                                is AssetOpenResult.Opened -> {
                                    val asset = result.asset
                                    try {
                                        val added = includes + TclSourceFile(
                                            asset.name, asset.bytes.decodeToString(throwOnInvalidSequence = true),
                                        )
                                        validateTclSourceFiles(TclSourceFile(fileName, script), added)
                                        stopIfEditingApprovedSource()
                                        includes = added
                                        notice = null
                                    } catch (_: CharacterCodingException) {
                                        notice = message(Messages.TclAttachmentUtf8)
                                    } catch (error: IllegalArgumentException) {
                                        notice = error.localizedDescription(message(Messages.TclErrorSource))
                                    }
                                }
                                is AssetOpenResult.Failure -> notice = result.notice
                                AssetOpenResult.Cancelled -> Unit
                            }
                        }
                    } catch (error: Exception) {
                        pickingSource = false
                        notice = error.localizedDescription(message(Messages.TclErrorSource))
                    }
                },
            ) { Text(strings(Messages.TclAttachmentAdd)) }
            includes.forEach { source ->
                OutlinedButton(
                    enabled = !running && !pickingSource,
                    modifier = Modifier.testTag("tcl-remove-source-${source.name}"),
                    onClick = {
                        stopIfEditingApprovedSource()
                        includes = includes - source
                        notice = null
                    },
                ) { Text(strings(Messages.TclAttachmentRemove, "name" to source.name)) }
            }
            if (actions == null) Text(strings(Messages.TclNoEvents))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoTicksOnStart, onCheckedChange = { autoTicksOnStart = it },
                    enabled = !running && actions != null && client?.available == true)
                Text(strings("tcl_auto_ticks_on_start", "count" to TclAutomaticTicks.LIMIT))
            }
            Button(enabled = !running && !pickingSource && actions != null && client?.available == true, onClick = {
                if (running || pickingSource) return@Button
                runCatching {
                    val source = TclSourceFile(fileName, script)
                    val newAuthorization = if (autoTicksOnStart) "ticks-$sessionId-${++requestCounter}" else null
                    runtime.approve(context, component, source, includes, newAuthorization)
                    notice = message(Messages.TclSourceApproved)
                }.onFailure { notice = it.localizedDescription(message(Messages.TclErrorStart)) }
            }) { Text(strings(Messages.TclComponentStart)) }
            OutlinedButton(enabled = actions != null, onClick = {
                runtime.stop(context, component)
                notice = null
            }) { Text(strings(Messages.TclComponentStop)) }
            if (activeAuthorization != null) {
                OutlinedButton(onClick = { cancelAutomaticTicks() }) { Text(strings("tcl_auto_ticks_cancel")) }
            }
            if (state?.requestedTick == true) Button(enabled = actions != null && !pendingAutomaticTick, onClick = {
                val request = state.tickRequest()
                if (actions?.sendEvent(component.id, request) != true) {
                    notice = message(Messages.TclErrorInstanceChanged)
                }
            }) { Text(strings(Messages.TclApplyTick, "count" to state.pendingTickRequests)) }
        }
        notice?.let { Text(strings(it)) }
        if (stdout.isNotEmpty()) OutlinedTextField(stdout, {}, label = { Text(strings(Messages.TclStdout)) }, readOnly = true, maxLines = 10, modifier = Modifier.fillMaxWidth())
        if (stderr.isNotEmpty()) OutlinedTextField(stderr, {}, label = { Text(strings(Messages.TclStderr)) }, readOnly = true, maxLines = 10, modifier = Modifier.fillMaxWidth())
    }
}
