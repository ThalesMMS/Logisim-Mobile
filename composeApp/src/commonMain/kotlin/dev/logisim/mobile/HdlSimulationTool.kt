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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.*
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Approval and source edits belong to the panel. An approved session follows the editor simulation. */
fun hdlSimulationTool(companion: () -> CompanionClient?): EditorTool {
    val runtime = HdlToolRuntime()
    return EditorTool("hdl-simulation", "HDL-IP co-simulation", titleText = message(Messages.HdlToolsSimTitle),
        runtime = { context ->
            val latestContext = rememberUpdatedState(context)
            val client = companion()
            DisposableEffect(runtime) {
                runtime.latestContext = { latestContext.value }
                onDispose { runtime.close() }
            }
            val session = runtime.session
            val pending = runtime.pending
            val state = (session?.target ?: pending?.target)?.state(context)
            LaunchedEffect(runtime, session, pending, state, runtime.busy, context.project, context.documentIdentity,
                context.selectedComponents, context.isForeground, client) {
                runtime.synchronize(context, client)
            }
        }
    ) { context -> runtime.Panel(context, companion) }
}

@Composable
private fun HdlToolRuntime.Panel(context: EditorToolContext, companion: () -> CompanionClient?) {
        val loc = rememberLocalizer()
        val selected = context.selectedComponents.singleOrNull()?.takeIf(::isHdlIpComponent)
        val sharedDefinitions = remember(context.project.extraXml) { context.project.sharedVhdlDefinitions() }
        val shared = selected?.let { context.project.sharedVhdlDefinition(it) }
        val selectedKey = selected?.let { HdlToolTarget.from(context, it) }
        val design = remember(selectedKey) { selected?.let { runCatching { HdlIpLibrary.parse(it) }.getOrNull() } }
        val currentSource = selected?.attributes?.get("content") ?: design?.source
        var backend by remember(selected?.id, selected?.kind) {
            mutableStateOf(if (selected?.kind == HdlIpLibrary.BLIF) HdlIpBackend.BLIF else HdlIpBackend.VHDL)
        }
        var source by remember(selectedKey) {
            mutableStateOf(currentSource ?: hdlExample(backend))
        }
        var generics by remember(selectedKey) {
            mutableStateOf(selected?.let(::hdlGenericText).orEmpty())
        }
        var advance by remember { mutableStateOf("1") }
        val engineState = selected?.let { context.simulation.componentStates[it.id] as? HdlIpState }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("hdl-sim-scroll"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(loc(Messages.HdlToolsSimDescription))
            if (selected == null) {
                Text(loc(Messages.HdlToolsSimSelectComponent))
                ChoiceRow(loc("hdl_tools_sim_backend_label"), HdlIpBackend.entries, backend, enabled = !busy,
                    optionLabel = { it.name }, optionTag = { "hdl-sim-backend-${it.name}" }) { choice ->
                    if (session == null) invalidate()
                    backend = choice
                    source = hdlExample(choice)
                    generics = ""
                }
            } else {
                Text("${selected.label.ifBlank { design?.topLevel.orEmpty().ifBlank { loc(selected.kind.localizedLabel()) } }} · ${design?.backend?.name.orEmpty()}")
                if (shared != null) Text(loc("shared_vhdl_edit", "name" to shared.name))
            }
            val insertableDefinitions = if (selected == null) sharedDefinitions else listOfNotNull(shared)
            for (definition in insertableDefinitions) TextButton(enabled = !busy && context.projectExports.access.writable, onClick = {
                val ctx = latestContext()
                val current = ctx.project.sharedVhdlDefinitions().firstOrNull { it.name == definition.name }
                if (current != null) {
                    val attributes = selected?.attributes?.filterKeys(::isHdlGenericAttribute).orEmpty()
                    invalidate()
                    insertSharedVhdl(ctx, ctx.project, current, attributes)
                }
            }) { Text(loc("shared_vhdl_insert", "name" to definition.name)) }
            val hdlStyle = rememberHdlEditorStyle()
            OutlinedTextField(source, {
                if (source != it) {
                    // Shown while typing but neither announced nor scrolled to, so the caret and the IME stay put (#85).
                    if (session?.target == selectedKey || session == null) invalidate(message(Messages.HdlToolsSimSourceChanged), NoticeAnchor.SOURCE, NoticeKind.PROGRESS)
                    source = it
                }
            }, label = { Text(loc(Messages.HdlToolsSimSourceLabel)) }, enabled = !busy, minLines = 6, maxLines = 14,
                keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag("hdl-sim-source"), visualTransformation = hdlStyle.transformation, colors = hdlStyle.colors(), textStyle = TextStyle(fontFamily = FontFamily.Monospace))
            fun changeGenerics(text: String) {
                if (generics != text) {
                    if (session?.target == selectedKey || session == null) invalidate(message(Messages.HdlToolsSimParametersChanged), NoticeAnchor.SOURCE, NoticeKind.PROGRESS)
                    generics = text
                }
            }
            // BLIF has no generics (Evolution ignores them), so its parameters are not shown at all. A VHDL entity
            // gets one field per declared generic; the NAME=value text stays for what the fields cannot show.
            val blif = if (selected != null) selected.kind == HdlIpLibrary.BLIF else backend == HdlIpBackend.BLIF
            val declared = remember(source, blif) { if (blif) null else HdlIpDeclarations.vhdl(source) }
            val declarations = declared?.genericDeclarations.orEmpty()
            val unparsed = declared?.diagnostics.orEmpty().any { it.code == "unsupported-generic" || it.code == "unterminated-declaration" }
            var genericsAsText by remember(selectedKey) { mutableStateOf(false) }
            if (!blif && declarations.isNotEmpty() && !unparsed && !genericsAsText) {
                HdlGenericFields(declarations, generics, enabled = !busy, onText = ::changeGenerics) { genericsAsText = true }
            } else if (!blif && (declarations.isNotEmpty() || unparsed || generics.isNotBlank())) {
                OutlinedTextField(generics, ::changeGenerics, label = { Text(loc(Messages.HdlToolsSimGenericsLabel)) }, enabled = !busy,
                    minLines = 1, maxLines = 5, keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag("hdl-sim-generics"), textStyle = TextStyle(fontFamily = FontFamily.Monospace))
                if (declarations.isNotEmpty() && !unparsed) TextButton(enabled = !busy, onClick = { genericsAsText = false }) {
                    Text(loc("hdl_tools_sim_generics_as_fields"))
                }
            }
            val dirty = selected == null || source != currentSource ||
                runCatching { hdlGenericAttributes(generics) }.getOrNull() !=
                runCatching { hdlGenericAttributes(hdlGenericText(selected)) }.getOrNull()
            Button(modifier = Modifier.testTag("hdl-sim-apply"), enabled = !busy && source.isNotBlank() && dirty, onClick = {
                try {
                    requireLocalized(source.encodeToByteArray().size <= 128 * 1024 && '\u0000' !in source) { message(Messages.HdlToolsSimSourceLimit) }
                    val attributes = hdlGenericAttributes(generics)
                    val ctx = latestContext()
                    if (session?.target == selectedKey || session == null) invalidate()
                    if (selected != null) {
                        val actual = ctx.document.components.firstOrNull { it.id == selected.id }
                            ?: throw LocalizedArgumentException(message(Messages.HdlToolsSimComponentRemoved))
                        requireLocalized(selectedKey == HdlToolTarget.from(ctx, actual)) { message(Messages.HdlToolsSimEditChanged) }
                        val definition = ctx.project.sharedVhdlDefinition(actual)
                        val changed = actual.copy(attributes = actual.attributes.filterKeys { !isHdlGenericAttribute(it) } + attributes + ("content" to source))
                        if (definition == null) {
                            ctx.applyDocument(ctx.document.copy(components = ctx.document.components.map { if (it.id == actual.id) changed else it }))
                        } else {
                            val project = ctx.project.updateSharedVhdlSource(definition.name, source)
                            val document = project.circuits.first { it.id == ctx.document.id }
                            ctx.applyProject(project.withCircuit(document.copy(components = document.components.map {
                                if (it.id == actual.id) changed else it
                            })))
                        }
                        ctx.selectComponent(changed.id)
                    } else {
                        var number = 1
                        while (ctx.document.components.any { it.id == "hdl-ip-$number" }) number++
                        val added = CircuitComponent("hdl-ip-$number", if (backend == HdlIpBackend.VHDL) HdlIpLibrary.VHDL else HdlIpLibrary.BLIF,
                            GridPoint(80, (ctx.document.components.maxOfOrNull { it.position.y } ?: 0) + 160),
                            attributes = attributes + ("content" to source))
                        ctx.applyDocument(ctx.document.copy(components = ctx.document.components + added))
                        ctx.selectComponent(added.id)
                    }
                    val saved = if (shared == null) message(Messages.HdlToolsSimSourceSaved) else message("shared_vhdl_saved")
                    report(saved, anchor = NoticeAnchor.SOURCE)
                    ctx.showNotice(saved)
                } catch (error: IllegalArgumentException) {
                    report(error.localizedDescription(message(Messages.HdlToolsSimEditFailed)), NoticeKind.ERROR, NoticeAnchor.SOURCE)
                }
            }) { Text(if (selected == null) loc(Messages.HdlToolsSimInsert) else loc(Messages.HdlToolsSimApplySource)) }
            if (selected == null && backend == HdlIpBackend.VHDL) {
                TextButton(enabled = !busy && context.projectExports.access.writable && source.isNotBlank(), onClick = {
                    try {
                        val ctx = latestContext()
                        val (project, definition) = ctx.project.addSharedVhdlDefinition(source)
                        invalidate()
                        insertSharedVhdl(ctx, project, definition, hdlGenericAttributes(generics))
                    } catch (error: IllegalArgumentException) {
                        report(error.localizedDescription(message(Messages.HdlToolsSimEditFailed)), NoticeKind.ERROR, NoticeAnchor.SOURCE)
                    }
                }) { Text(loc("shared_vhdl_create")) }
            }
            HdlRuntimeNotice(NoticeAnchor.SOURCE)

            design?.let { parsed ->
                for (diagnostic in parsed.diagnostics) Text(loc(Messages.HdlToolsSimDiagnostic, "code" to diagnostic.code, "detail" to diagnostic.notice))
                if (parsed.ports.isNotEmpty()) SelectionContainer {
                    val ports = parsed.ports.joinToString("\n") { port ->
                        val direction = when (port.direction) {
                            PortDirection.INPUT -> Messages.HdlToolsSimInput
                            PortDirection.OUTPUT -> Messages.HdlToolsSimOutput
                            PortDirection.INOUT -> "hdl_tools_sim_inout"
                        }
                        loc(Messages.HdlToolsSimPort, "name" to port.name, "direction" to message(direction),
                            "bits" to LocalizedText(Messages.HdlToolsSimBits, quantity = port.width.toLong()))
                    }
                    Text(ports)
                }
            }
            HorizontalDivider()
            val status = when (engineState?.status) {
                HdlIpStatus.UNAVAILABLE -> message(Messages.HdlToolsSimDisconnected)
                HdlIpStatus.READY -> message(Messages.HdlToolsSimReady)
                HdlIpStatus.PENDING -> message(Messages.HdlToolsSimPending)
                HdlIpStatus.FAILED -> message(Messages.HdlToolsSimFailed)
                // BLIF runs in the Mobile simulator, as in Evolution; only VHDL uses a companion session.
                null -> if (design?.backend == HdlIpBackend.BLIF && design.isExecutable) message(Messages.HdlToolsSimReady)
                    else message(Messages.HdlToolsSimNoComponent)
            }
            Text(loc(Messages.HdlToolsSimStatus, "status" to status, "time" to context.projectSimulation.propagationTime, "sessionTime" to (session?.timeNs ?: 0)))
            engineState?.failureNotice?.let { Text(loc(it)) }
            Button(modifier = Modifier.testTag("hdl-sim-prepare"), enabled = !busy && selected != null && !dirty && design?.isExecutable == true &&
                design.backend == HdlIpBackend.VHDL && session == null &&
                selectedKey?.matches(context) == true && context.hdlProjectActive(), onClick = {
                val component = selected ?: return@Button
                val parsed = design ?: return@Button
                val target = HdlToolTarget.from(latestContext(), component)
                val client = companion()
                if (client == null) fail(target, message(Messages.HdlToolsSimConnectCompanion)) else {
                    val generation = work.generation
                    pending = HdlPendingTarget(target, client, setOf(target.state(latestContext())?.epoch ?: 0))
                    busy = true
                    logs = ""
                    work.launch {
                        try {
                            val request = parsed.companionRequest(start = true)
                            val plan = client.plan(request)
                            if (current(generation)) {
                                planned = HdlToolPlan(target, parsed, client, request, plan)
                                report(message(Messages.HdlToolsSimReviewStart))
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (current(generation)) fail(target, error.localizedDescription(message(Messages.HdlToolsSimPrepareFailed)))
                        } finally { if (current(generation)) busy = false }
                    }
                }
            }) { Text(loc(Messages.HdlToolsSimPrepare)) }
            planned?.let { prepared ->
                Text(loc(Messages.HdlToolsSimPlan, "backend" to prepared.design.backend.name, "top" to prepared.design.topLevel,
                    "bytes" to prepared.design.source.encodeToByteArray().size,
                    "ports" to LocalizedText(Messages.HdlToolsSimPortCount, quantity = prepared.design.ports.size.toLong()),
                    "parameters" to prepared.design.parameters.entries.joinToString { "${it.key}=${it.value}" }))
                Button(modifier = Modifier.testTag("hdl-sim-confirm"), enabled = !busy, onClick = {
                    val generation = work.generation
                    val approval = prepared.plan.confirm()
                    planned = null
                    busy = true
                    report(message(Messages.HdlToolsSimStarting), NoticeKind.PROGRESS)
                    work.launch {
                        var opened: HdlToolSession? = null
                        try {
                            val response = execute(prepared.client, prepared.request, generation, approval)
                            val result = response.hdlSample(emptyMap())
                            val binding = HdlToolSession(prepared.target, prepared.client, result.sessionId,
                                (prepared.target.state(latestContext())?.epoch ?: 0) + 1, result.sourceSha256)
                            opened = binding
                            requireLocalized(result.timeNs == 0L && result.sourceSha256 == hdlSourceDigest(prepared.design.source)) { message(Messages.HdlToolsSimInvalidStart) }
                            if (!current(generation) || !prepared.target.matches(latestContext())) cancelRemote(binding)
                            else {
                                val waiting = pending
                                requireLocalized(waiting?.target == prepared.target &&
                                    (prepared.target.state(latestContext())?.epoch ?: 0) in waiting.allowedEpochs) {
                                    message(Messages.HdlToolsSimResetDuringStart)
                                }
                                pending = null
                                session = binding
                                requireLocalized(send(binding.target, CompanionHdlStarted(binding.id, binding.epoch))) { message(Messages.HdlToolsSimStartRejected) }
                                report(message(Messages.HdlToolsSimStarted))
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (current(generation)) fail(prepared.target, error.localizedDescription(message(Messages.HdlToolsSimStartFailed)), opened)
                            else opened?.let(::cancelRemote)
                        } finally { if (current(generation)) busy = false }
                    }
                }) { Text(loc(Messages.HdlToolsSimConfirmStart)) }
            }

            OutlinedTextField(advance, { advance = it }, label = { Text(loc(Messages.HdlToolsSimAdvanceLabel)) }, enabled = !busy,
                singleLine = true, keyboardOptions = FieldKeyboards.Number, modifier = Modifier.fillMaxWidth().testTag("hdl-sim-advance"))
            Button(modifier = Modifier.testTag("hdl-sim-step"), enabled = !busy && session?.target == selectedKey && selectedKey != null && !dirty, onClick = {
                val interval = advance.trim().toLongOrNull()
                if (interval == null || interval !in 1..1_000_000) report(message(Messages.HdlToolsSimAdvanceRange), NoticeKind.ERROR)
                else sample(interval)
            }) { Text(loc(Messages.HdlToolsSimSample)) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.testTag("hdl-sim-reset"), enabled = !busy && session != null && context.hdlProjectActive(), onClick = {
                    val binding = session ?: return@TextButton
                    val generation = work.generation
                    session = null
                    pending = HdlPendingTarget(binding.target, binding.client, setOf(binding.epoch, binding.epoch + 1), resetting = true)
                    send(binding.target, CompanionHdlStopped(binding.id, binding.epoch))
                    busy = true
                    report(message(Messages.HdlToolsSimResetting), NoticeKind.PROGRESS)
                    work.launch {
                        try {
                            val result = execute(binding.client, hdlSessionRequest(binding.id, reset = true), generation)
                                .hdlSample(emptyMap(), binding.id)
                            requireLocalized(result.timeNs == 0L && result.sourceSha256 == binding.digest) { message(Messages.HdlToolsSimInvalidReset) }
                            if (current(generation) && binding.target.matches(latestContext())) {
                                val waiting = pending
                                requireLocalized(waiting?.target == binding.target &&
                                    (binding.target.state(latestContext())?.epoch ?: 0) in waiting.allowedEpochs) {
                                    message(Messages.HdlToolsSimResetDuringReset)
                                }
                                val epoch = maxOf(binding.epoch + 1, binding.target.state(latestContext())?.epoch ?: 0) + 1
                                val reset = binding.copy(epoch = epoch, timeNs = 0, seenInEngine = false,
                                    sampledRevision = null, sentRevision = 0)
                                pending = null
                                session = reset
                                requireLocalized(send(reset.target, CompanionHdlStarted(reset.id, reset.epoch))) { message(Messages.HdlToolsSimResetRejected) }
                                report(message(Messages.HdlToolsSimResetComplete))
                            } else cancelRemote(binding)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (current(generation)) fail(binding.target, error.localizedDescription(message(Messages.HdlToolsSimResetFailed)), binding)
                        } finally { if (current(generation)) busy = false }
                    }
                }) { Text(loc(Messages.HdlToolsSimReset)) }
                TextButton(modifier = Modifier.testTag("hdl-sim-stop"), enabled = session != null || busy || planned != null, onClick = {
                    invalidate(message(Messages.HdlToolsSimStopped))
                }) { Text(loc(Messages.HdlToolsSimCancel)) }
            }
            // Session results sit right under Sample / Reset instead of at the end of the panel (#85).
            if (busy) Text(loc(Messages.HdlToolsSimWaiting))
            HdlRuntimeNotice(NoticeAnchor.SESSION)
            if (selected != null && design?.isExecutable == true) {
                val inputValues = engineState?.sampleInputs.orEmpty()
                val inout = design.ports.filter { it.direction == PortDirection.INOUT }.map { it.name }.toSet()
                val inputDirection = loc(Messages.HdlToolsSimInput)
                val outputDirection = loc(Messages.HdlToolsSimOutput)
                fun sampleLine(name: String, value: LogicVector, direction: String): String {
                    val label = if (name in inout) "$name ($direction)" else name
                    return "$label = ${hdlVectorText(value)}"
                }
                SelectionContainer {
                    Text((inputValues.map { sampleLine(it.key, it.value, inputDirection) } +
                        engineState?.outputs.orEmpty().map { sampleLine(it.key, it.value, outputDirection) }).joinToString("\n"),
                        fontFamily = FontFamily.Monospace)
                }
            }
            if (logs.isNotBlank()) {
                Text(loc(Messages.HdlToolsSimLogs))
                SelectionContainer { Text(logs, fontFamily = FontFamily.Monospace) }
            }
        }
}

private fun insertSharedVhdl(context: EditorToolContext, project: CircuitProject, definition: SharedVhdlDefinition,
                             generics: Map<String, String> = emptyMap()) {
    var number = 1
    while (context.document.components.any { it.id == "hdl-ip-$number" }) number++
    val position = GridPoint(300, (context.document.components.maxOfOrNull { it.position.y } ?: 0) + 160)
    val component = definition.instance("hdl-ip-$number", position, generics)
    context.applyProject(project.withCircuit(context.document.copy(components = context.document.components + component)))
    context.selectComponent(component.id)
    context.showNotice(message("shared_vhdl_saved"))
}

internal data class HdlToolTarget(
    val documentIdentity: Long, val circuitId: String, val instancePath: String, val componentId: String,
    val kind: ComponentKind, val attributes: Map<String, String>,
) {
    val eventKey: String get() = if (instancePath.isEmpty()) componentId else "$instancePath/$componentId"
    fun component(context: EditorToolContext): CircuitComponent? {
        if (context.documentIdentity != documentIdentity) return null
        var circuit = context.project.mainCircuit
        if (instancePath.isNotEmpty()) for (id in instancePath.split('/')) {
            val child = circuit.components.firstOrNull { it.id == id && it.kind == ComponentKind.SUBCIRCUIT }
            circuit = context.project.circuit(child?.circuitName ?: return null) ?: return null
        }
        if (circuit.id != circuitId) return null
        return circuit.components.firstOrNull { it.id == componentId }
    }
    fun matches(context: EditorToolContext): Boolean =
        component(context)?.let { it.kind == kind && sourceAttributes(it) == attributes } == true
    fun state(context: EditorToolContext): HdlIpState? =
        (if (instancePath.isEmpty()) context.projectSimulation else context.projectSimulation.circuitStates[instancePath])
            ?.takeIf { context.documentIdentity == documentIdentity }
            ?.componentStates?.get(componentId) as? HdlIpState

    companion object {
        fun from(context: EditorToolContext, component: CircuitComponent) = HdlToolTarget(
            context.documentIdentity, context.document.id, context.simulation.instancePath, component.id, component.kind, sourceAttributes(component))
        private fun sourceAttributes(component: CircuitComponent) = component.attributes.filterKeys { it == "content" || isHdlGenericAttribute(it) }
    }
}

internal data class HdlToolSession(
    val target: HdlToolTarget, val client: CompanionClient, val id: String, val epoch: Long,
    val digest: String, val timeNs: Long = 0, val seenInEngine: Boolean = false,
    val sampledRevision: Long? = null,
    val sentRevision: Long = 0,
)
/** Source edits report under the source buttons; session work under Sample / Reset. */
internal enum class NoticeAnchor { SOURCE, SESSION }

internal data class HdlPendingTarget(
    val target: HdlToolTarget, val client: CompanionClient, val allowedEpochs: Set<Long>, val resetting: Boolean = false,
)
internal data class HdlToolPlan(val target: HdlToolTarget, val design: HdlIpDesign, val client: CompanionClient,
                               val request: CompanionJobRequest, val plan: CompanionPlan)

internal class HdlToolRuntime(dispatcher: CoroutineDispatcher = Dispatchers.Main) {
    val work = HdlSessionWork(dispatcher)
    lateinit var latestContext: () -> EditorToolContext
    var notice by mutableStateOf<LocalizedText?>(null)
    var noticeKind by mutableStateOf(NoticeKind.RESULT)
    var noticeAnchor by mutableStateOf(NoticeAnchor.SESSION)

    fun report(text: LocalizedText?, kind: NoticeKind = NoticeKind.RESULT, anchor: NoticeAnchor = NoticeAnchor.SESSION) {
        notice = text
        noticeKind = kind
        noticeAnchor = anchor
    }

    /** Both anchors stay composed and only the current one shows the notice, so moving it still reveals it. */
    @Composable
    fun HdlRuntimeNotice(anchor: NoticeAnchor) = ToolNoticeText(notice?.takeIf { noticeAnchor == anchor }?.let { ToolNotice(it, noticeKind) },
        Modifier.testTag("hdl-sim-notice"), selectable = true)
    var logs by mutableStateOf("")
    var busy by mutableStateOf(false)
    var planned by mutableStateOf<HdlToolPlan?>(null)
    var pending by mutableStateOf<HdlPendingTarget?>(null)
    var session by mutableStateOf<HdlToolSession?>(null)

    fun current(generation: Long) = !work.closed && work.generation == generation

    fun send(target: HdlToolTarget, vararg events: ComponentEvent): Boolean {
        val context = latestContext()
        return target.matches(context) && context.injectEvents(mapOf(target.eventKey to events.toList()))
    }

    fun cancelRemote(binding: HdlToolSession) {
        work.launch { runCatching { awaitHdlJob(binding.client, hdlSessionRequest(binding.id, reset = false)) } }
    }

    fun invalidate(reason: LocalizedText? = null, anchor: NoticeAnchor = NoticeAnchor.SESSION, kind: NoticeKind = NoticeKind.RESULT) {
        val previous = session
        work.invalidate()
        session = null
        pending = null
        planned = null
        busy = false
        report(reason, kind, anchor)
        previous?.let {
            send(it.target, CompanionHdlStopped(it.id, it.epoch))
            cancelRemote(it)
        }
    }

    fun fail(target: HdlToolTarget, reason: LocalizedText, binding: HdlToolSession? = session, sequence: Long? = null) {
        val state = target.state(latestContext())
        val event = if (binding != null && sequence != null) CompanionHdlFailed(binding.id, binding.epoch, sequence, reason)
            else CompanionHdlUnavailable((state?.epoch ?: 0) + 1, reason)
        send(target, event)
        binding?.let(::cancelRemote)
        session = null
        pending = null
        planned = null
        report(reason, NoticeKind.ERROR)
    }

    suspend fun execute(client: CompanionClient, request: CompanionJobRequest, generation: Long,
                        approval: CompanionApproval? = null): CompanionJob = try {
        awaitHdlJob(client, request, approval) { update ->
            work.track(generation, client, update)
            if (current(generation)) logs = update.logs.joinToString("\n") { it.text }.takeLast(16_384)
        }.also { requireLocalized(it.operation == request.operation) { message(Messages.HdlToolsSimWrongOperation) } }
    } finally {
        work.jobs.remove(generation)
    }

    fun synchronize(context: EditorToolContext, client: CompanionClient?) {
        pending?.let { waiting ->
            val epoch = waiting.target.state(context)?.epoch ?: 0
            val selected = context.selectedComponents.singleOrNull()?.let { HdlToolTarget.from(context, it) }
            if (client !== waiting.client || !waiting.target.matches(context) || epoch !in waiting.allowedEpochs ||
                (!waiting.resetting && selected != waiting.target)) {
                invalidate(message(Messages.HdlToolsSimPendingChanged))
            }
        }
        val binding = session ?: return
        val state = binding.target.state(context)
        when {
            client !== binding.client || !binding.target.matches(context) ->
                invalidate(message(Messages.HdlToolsSimSelectionChanged))
            state != null && state.epoch > binding.epoch ->
                invalidate(message(Messages.HdlToolsSimEngineReset))
            (binding.seenInEngine || state?.epoch == binding.epoch) &&
                (state?.sessionId != binding.id || state.epoch != binding.epoch) ->
                invalidate(message(Messages.HdlToolsSimEngineSessionChanged))
            state?.sessionId == binding.id && state.epoch == binding.epoch -> {
                if (!binding.seenInEngine) session = binding.copy(seenInEngine = true)
                if (context.isForeground && context.hdlProjectActive() && !busy &&
                    state.sampleInputs.isNotEmpty() && state.inputRevision != binding.sampledRevision) {
                    sample(interval = 1, automatic = true)
                }
            }
        }
    }

    fun sample(interval: Long, automatic: Boolean = false) {
        val binding = session ?: return
        val context = latestContext()
        val component = binding.target.component(context) ?: return
        val state = binding.target.state(context)
        if (busy || !context.isForeground || !context.hdlProjectActive() ||
            state?.sessionId != binding.id || state.epoch != binding.epoch) return
        val nextInput = state.inputHistory.firstOrNull { it.revision > binding.sentRevision }
        if (nextInput != null && nextInput.revision != binding.sentRevision + 1) {
            fail(binding.target, message(Messages.HdlToolsSimInputHistoryLost), binding, state.sequence)
            return
        }
        val inputRevision = nextInput?.revision ?: state.inputRevision
        val inputs = nextInput?.inputs ?: state.sampleInputs
        val base = maxOf(context.projectSimulation.propagationTime, binding.timeNs)
        if (base > 1_000_000 - interval) {
            val reason = message(Messages.HdlToolsSimTimeLimit)
            if (automatic) fail(binding.target, reason, binding, state.sequence) else report(reason, NoticeKind.ERROR)
            return
        }
        val targetTime = base + interval
        val sequence = state.sequence + 1
        val event = CompanionHdlRequest(binding.id, binding.epoch, sequence, targetTime, inputs, inputRevision)
        val ports = HdlIpLibrary.parse(component).ports
        val inout = ports.filter { it.direction == PortDirection.INOUT }.map { it.name }.toSet()
        val outputs = ports.filter { it.direction != PortDirection.INPUT }.associate { it.name to it.width }
        val generation = work.generation
        busy = true
        report(message(Messages.HdlToolsSimWaitingSample, "time" to targetTime), NoticeKind.PROGRESS)
        work.launch {
            try {
                val result = execute(binding.client, hdlStepRequest(binding.id, event.inputs, targetTime - binding.timeNs, inout), generation)
                    .hdlSample(outputs, binding.id)
                requireLocalized(result.sourceSha256 == binding.digest && result.timeNs == targetTime) {
                    message(Messages.HdlToolsSimInvalidSample)
                }
                if (!current(generation)) return@launch
                // The remote trace advanced even when a newer local input makes its sample obsolete.
                session = binding.copy(timeNs = targetTime, seenInEngine = true, sentRevision = inputRevision)
                val now = latestContext()
                val nowState = binding.target.state(now)
                if (nowState?.sessionId != binding.id || nowState.epoch != binding.epoch ||
                    nowState.inputRevision != event.inputRevision || !binding.target.matches(now) ||
                    !now.isForeground || !now.hdlProjectActive()) return@launch
                requireLocalized(send(binding.target, event,
                    CompanionHdlReply(binding.id, binding.epoch, sequence, targetTime, result.outputs))) {
                    message(Messages.HdlToolsSimReplyRejected)
                }
                // The editor queues stimuli. Wait for application before considering the input sampled.
                val applied = withTimeoutOrNull(5_000) {
                    snapshotFlow { binding.target.state(latestContext())?.takeIf { current(generation) } }
                        .first { latest -> latest == null || latest.sessionId != binding.id || latest.epoch != binding.epoch ||
                            latest.inputRevision != event.inputRevision || latest.sequence >= sequence }
                }
                if (!current(generation)) return@launch
                if (applied?.sessionId == binding.id && applied.epoch == binding.epoch &&
                    applied.inputRevision == event.inputRevision && applied.sequence == sequence && applied.timeNs == targetTime) {
                    session = session?.copy(sampledRevision = event.inputRevision)
                    // Automatic samples follow every input change; only a requested sample is announced.
                    report(message(Messages.HdlToolsSimSampleReceived, "time" to targetTime),
                        if (automatic) NoticeKind.PROGRESS else NoticeKind.RESULT)
                } else if (applied == null) {
                    val latest = latestContext()
                    val latestState = binding.target.state(latest)
                    if (latest.isForeground && latest.hdlProjectActive() && latestState?.sessionId == binding.id &&
                        latestState.epoch == binding.epoch && latestState.inputRevision == event.inputRevision) {
                        fail(binding.target, message(Messages.HdlToolsSimReplyRejected), binding, latestState.sequence)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(generation)) {
                    fail(binding.target, error.localizedDescription(message(Messages.HdlToolsSimSampleFailed)), binding, state.sequence)
                }
            } finally {
                if (current(generation)) busy = false
            }
        }
    }

    fun close() {
        session?.let { send(it.target, CompanionHdlStopped(it.id, it.epoch)) }
        work.close(session)
    }
}

private fun EditorToolContext.hdlProjectActive(): Boolean =
    simulation.instancePath.isEmpty() || projectSimulation.circuitStates[simulation.instancePath] === simulation

/** Network work finishes cleanup even if the editor closes during a submit response. */
internal class HdlSessionWork(dispatcher: CoroutineDispatcher) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val jobs = mutableMapOf<Long, Pair<CompanionClient, String>>()
    private val workers = mutableSetOf<Job>()
    private val cancellationSent = mutableSetOf<String>()
    var generation = 0L
    var closed = false

    fun launch(block: suspend () -> Unit) {
        val worker = scope.launch(start = CoroutineStart.LAZY) { block() }
        workers += worker
        worker.invokeOnCompletion { workers -= worker }
        worker.start()
    }

    fun track(token: Long, client: CompanionClient, job: CompanionJob) {
        jobs[token] = client to job.jobId
        if ((closed || token != generation) && !job.state.terminal) cancelJob(client, job.jobId)
    }

    private fun cancelJob(client: CompanionClient, id: String) {
        if (cancellationSent.add(id)) launch { runCatching { client.cancel(id) } }
    }

    fun invalidate() {
        generation++
        jobs.values.toList().forEach { (client, id) -> cancelJob(client, id) }
    }

    fun close(session: HdlToolSession?) {
        closed = true
        invalidate()
        session?.let { launch { runCatching { awaitHdlJob(it.client, hdlSessionRequest(it.id, reset = false)) } } }
        scope.launch {
            withTimeoutOrNull(65_000) { while (workers.isNotEmpty()) workers.toList().joinAll() }
            scope.cancel()
        }
    }
}

private fun isHdlIpComponent(component: CircuitComponent) = component.kind == HdlIpLibrary.VHDL || component.kind == HdlIpLibrary.BLIF
private fun hdlSourceDigest(source: String) = CompanionInput.fromBytes("source.hdl", source.encodeToByteArray()).sha256
private fun hdlVectorText(vector: LogicVector) = (vector.width - 1 downTo 0).joinToString("") { vector.bit(it).display }
internal fun hdlGenericAttributes(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (line in text.lineSequence().filter { it.isNotBlank() }) {
        val name = line.substringBefore('=').trim()
        val value = line.substringAfter('=', "").trim()
        requireLocalized('=' in line && Regex("[A-Za-z](?:[A-Za-z0-9]|_(?=[A-Za-z0-9]))*").matches(name) && value.isNotEmpty()) {
            message(Messages.HdlToolsSimInvalidGeneric)
        }
        requireLocalized(result.keys.none { it.removePrefix("generic.").equals(name, true) }) { message(Messages.HdlToolsSimDuplicateGeneric, "name" to name) }
        result["generic.$name"] = value
    }
    return result
}

private fun isHdlGenericAttribute(name: String) = name.startsWith("generic.") || name.startsWith("vhdl_")
private fun hdlGenericText(component: CircuitComponent): String = component.attributes.entries
    .filter { isHdlGenericAttribute(it.key) }
    .map { (if (it.key.startsWith("generic.")) it.key.removePrefix("generic.") else it.key.removePrefix("vhdl_")) to it.value }
    .distinctBy { it.first.lowercase() to it.second }
    .sortedBy { it.first.lowercase() }
    .joinToString("\n") { "${it.first}=${it.second}" }

private fun hdlExample(backend: HdlIpBackend): String = when (backend) {
    HdlIpBackend.VHDL -> """library ieee;
use ieee.std_logic_1164.all;
entity and_gate is
  port(a, b: in std_logic; y: out std_logic);
end entity;
architecture rtl of and_gate is
begin
  y <= a and b;
end architecture;
"""
    HdlIpBackend.BLIF -> """.model and_gate
.inputs a b
.outputs y
.subckt AND A=a B=b Y=y
.end
"""
}
