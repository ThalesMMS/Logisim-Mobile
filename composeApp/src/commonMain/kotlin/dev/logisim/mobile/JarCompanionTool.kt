package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionInput
import dev.logisim.mobile.core.CompanionJsonBoolean
import dev.logisim.mobile.core.CompanionJobRequest
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionJsonArray
import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionJsonObject
import dev.logisim.mobile.core.CompanionJsonString
import dev.logisim.mobile.core.CompanionPlan
import dev.logisim.mobile.core.ExtensionReferenceScanner
import dev.logisim.mobile.core.JarInstanceTrace
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.jarLiveRequest
import dev.logisim.mobile.core.readJarCircuitInputs
import dev.logisim.mobile.core.readJarInstanceTrace
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val jarCompanionToolId = "jar-companion"
private const val jarOperation = "jar-run"
private const val maxJarFrames = 256
private const val maxJarRequestBytes = 4 * 1024 * 1024

private data class JarExecutionResult(
    val library: String,
    val circuit: String,
    val ports: List<String>,
    val frames: List<String>,
    val project: CompanionArtifact,
    val trace: JarInstanceTrace?,
)

private data class JarLiveReview(val request: CompanionJobRequest, val plan: CompanionPlan,
                                 val inputs: Map<String, LogicVector>)

internal fun jarCompanionTool(assets: DocumentAssetActions, companion: () -> CompanionClient?) =
    EditorTool(jarCompanionToolId, "JAR", titleText = message("jar_tool_title")) { context ->
        val xml = context.extensionDocumentXml?.invoke()
        val client = companion()
        val strings = rememberLocalizer()
        val libraries = remember(xml) {
            xml?.let { runCatching { ExtensionReferenceScanner.inspect(it).jarLibraries.distinctBy { ref -> ref.descriptor } }.getOrNull() }
        }
        when {
            client == null -> Text(strings("jar_tool_needs_companion"), modifier = Modifier.padding(16.dp))
            xml == null || libraries?.size != 1 -> Text(strings("jar_tool_needs_library"), modifier = Modifier.padding(16.dp))
            else -> key(xml, client) { JarCompanionPanel(assets, client, xml, libraries.single().className, context) }
        }
    }

@Composable
private fun JarCompanionPanel(
    assets: DocumentAssetActions,
    client: CompanionClient,
    xml: String,
    libraryClass: String,
    context: EditorToolContext,
) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    var jar by remember { mutableStateOf<DocumentAsset?>(null) }
    val inputs = remember(xml) { runCatching { readJarCircuitInputs(xml) }.getOrNull() }
    val projectInput = remember(xml) {
        val bytes = xml.encodeToByteArray()
        if (bytes.size < maxJarRequestBytes) CompanionInput.fromBytes("project.circ", bytes) else null
    }
    if (projectInput == null) {
        Text(strings("jar_tool_project_size"), modifier = Modifier.padding(16.dp))
        return
    }
    val initialValues = remember(inputs) { inputs.orEmpty().associate { it.label to "0".repeat(it.width) } }
    var values by remember(initialValues) { mutableStateOf(initialValues) }
    var steps by remember { mutableStateOf(emptyList<Map<String, String>>()) }
    var advanced by remember(inputs) { mutableStateOf(inputs == null) }
    var frames by remember { mutableStateOf("") }
    var traceInstances by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Pair<CompanionJobRequest, CompanionPlan>?>(null) }
    var liveReview by remember { mutableStateOf<JarLiveReview?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<LocalizedText?>(null) }
    var result by remember { mutableStateOf<JarExecutionResult?>(null) }

    fun perform(action: suspend () -> Unit) {
        status = null
        running = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                if (!isActive || cancelled !is TimeoutCancellationException) {
                    status = message("jar_tool_cancelled")
                    throw cancelled
                }
                status = message("jar_tool_failed")
            } catch (error: Exception) {
                status = error.localizedDescription(message("jar_tool_failed"))
            } finally {
                running = null
            }
        }
    }

    fun invalidateReview() {
        review = null
        liveReview = null
        result = null
    }

    val readyToRecord = inputs.orEmpty().all { values.getValue(it.label).length == it.width }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings("jar_tool_class", "class" to libraryClass))
        Button(modifier = Modifier.testTag("jar-choose-library"), enabled = running == null, onClick = {
            assets.open(listOf("application/java-archive", "application/octet-stream"), maxJarRequestBytes - projectInput.size) { opened ->
                when (opened) {
                    is AssetOpenResult.Opened -> if (opened.asset.name.endsWith(".jar", ignoreCase = true)) {
                        jar = opened.asset
                        invalidateReview()
                        status = null
                    } else status = message("jar_tool_pick_error")
                    is AssetOpenResult.Failure -> status = opened.notice
                    AssetOpenResult.Cancelled -> Unit
                }
            }
        }) { Text(strings("jar_tool_pick")) }
        jar?.let { Text(it.name) }
        if (inputs != null) {
            TextButton(enabled = running == null, onClick = {
                advanced = !advanced
                invalidateReview()
            }) { Text(strings(if (advanced) "jar_tool_use_controls" else "jar_tool_use_json")) }
        }
        if (advanced) {
            Text(strings("jar_tool_frames_help"))
            OutlinedTextField(frames, {
                frames = it
                invalidateReview()
            }, modifier = Modifier.fillMaxWidth().testTag("jar-json-steps"), enabled = running == null,
                minLines = 3, maxLines = 8, label = { Text(strings("jar_tool_frames")) })
        } else {
            Text(strings("jar_tool_controls_help"))
            inputs.orEmpty().forEachIndexed { index, pin ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(values.getValue(pin.label), { bits ->
                        if (bits.length <= pin.width && bits.all { it in "01?E" }) {
                            values = values + (pin.label to bits)
                            invalidateReview()
                        }
                    }, modifier = Modifier.weight(1f).testTag("jar-input-$index"), enabled = running == null,
                        singleLine = true, label = { Text(pin.label) })
                    if (pin.width == 1) {
                        TextButton(
                            modifier = Modifier.testTag("jar-pulse-$index"),
                            enabled = running == null && readyToRecord && steps.size <= maxJarFrames - 3,
                            onClick = {
                                steps = steps + listOf("0", "1", "0").map { values + (pin.label to it) }
                                values = values + (pin.label to "0")
                                invalidateReview()
                            },
                        ) { Text(strings("jar_tool_pulse", "pin" to pin.label)) }
                    }
                }
            }
            Text(strings("jar_tool_step_count", "count" to steps.size))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("jar-record-step"),
                    enabled = running == null && readyToRecord && steps.size < maxJarFrames,
                    onClick = { steps = steps + listOf(values); invalidateReview() },
                ) { Text(strings("jar_tool_record_step")) }
                TextButton(enabled = running == null && steps.isNotEmpty(), onClick = {
                    steps = emptyList()
                    values = initialValues
                    invalidateReview()
                }) { Text(strings("jar_tool_clear_steps")) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = traceInstances, onCheckedChange = {
                traceInstances = it
                invalidateReview()
            }, enabled = running == null, modifier = Modifier.testTag("jar-trace-instances"))
            Text(strings("jar_tool_trace_instances"))
        }
        val canReview = if (advanced) frames.isNotBlank() else steps.isNotEmpty()
        Button(modifier = Modifier.testTag("jar-review"), enabled = jar != null && canReview && running == null, onClick = {
            val selected = jar ?: return@Button
            invalidateReview()
            perform {
                val inputFrames = if (advanced) {
                    CompanionJsonCodec.parse(frames) as? CompanionJsonArray
                        ?: throw IllegalArgumentException(strings("jar_tool_frames_help"))
                } else CompanionJsonArray(steps.map { step -> CompanionJsonObject(step.mapValues { CompanionJsonString(it.value) }) })
                val request = CompanionJobRequest(jarOperation, listOf(
                    projectInput,
                    CompanionInput.fromBytes("library.jar", selected.bytes),
                ), buildMap {
                    put("libraryClass", CompanionJsonString(libraryClass))
                    put("frames", inputFrames)
                    if (traceInstances) put("instanceTrace", CompanionJsonBoolean(true))
                })
                val capability = client.capabilities().operations.firstOrNull { it.name == jarOperation }
                require(capability?.allowed == true && capability.available && capability.requiresConfirmation) {
                    strings("jar_tool_host_denied")
                }
                val plan = client.plan(request)
                require(plan.requiresConfirmation && !plan.osSandbox.isNullOrBlank()) {
                    strings("jar_tool_host_denied")
                }
                review = request to plan
            }
        }) { Text(strings("jar_tool_review")) }
        val startLive = context.startJarEditor
        if (startLive != null && !advanced && inputs != null) {
            TextButton(modifier = Modifier.testTag("jar-review-live"), enabled = jar != null && readyToRecord && running == null,
                onClick = {
                    val selected = jar ?: return@TextButton
                    invalidateReview()
                    perform {
                        val initial = values.mapValues { (_, bits) -> LogicVector.fromBits(bits.reversed().map {
                            when (it) {
                                '0' -> LogicValue.LOW
                                '1' -> LogicValue.HIGH
                                '?' -> LogicValue.UNKNOWN
                                'E' -> LogicValue.ERROR
                                else -> error("Invalid JAR input bit.")
                            }
                        }) }
                        val request = jarLiveRequest(xml.encodeToByteArray(), selected.bytes, libraryClass,
                            maxFrames = maxJarFrames, driverTrace = true)
                        val capability = client.capabilities().operations.firstOrNull { it.name == request.operation }
                        require(capability?.allowed == true && capability.available && capability.requiresConfirmation) {
                            strings("jar_tool_host_denied")
                        }
                        val plan = client.plan(request)
                        require(plan.requiresConfirmation && !plan.osSandbox.isNullOrBlank()) { strings("jar_tool_host_denied") }
                        liveReview = JarLiveReview(request, plan, initial)
                    }
                }) { Text(strings("jar_tool_live_review")) }
            liveReview?.let { (request, plan, initial) ->
                Text(strings("jar_tool_approval"))
                plan.inputs.forEach { Text("${it.name}  ${it.sha256}") }
                Text(strings("jar_tool_sandbox", "name" to plan.osSandbox.orEmpty()))
                Text(strings("jar_tool_live_help", "count" to maxJarFrames))
                if (initial.isNotEmpty()) Text(initial.entries.joinToString("  ") { (pin, value) -> "$pin=${value.display}" })
                Button(modifier = Modifier.testTag("jar-approve-live"), enabled = running == null, onClick = {
                    val approval = plan.confirm()
                    liveReview = null
                    perform { startLive(JarEditorLaunch(client, request, plan, approval, xml, initial)) }
                }) { Text(strings("jar_tool_live_run")) }
            }
        }
        review?.let { (request, plan) ->
            Text(strings("jar_tool_approval"))
            plan.inputs.forEach { Text("${it.name}  ${it.sha256}") }
            Text(strings("jar_tool_sandbox", "name" to plan.osSandbox.orEmpty()))
            val approvedSteps = request.parameters.getValue("frames") as CompanionJsonArray
            Text(strings("jar_tool_step_count", "count" to approvedSteps.values.size))
            OutlinedTextField(
                value = CompanionJsonCodec.write(approvedSteps),
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                maxLines = 5,
                label = { Text(strings("jar_tool_frames")) },
            )
            Button(modifier = Modifier.testTag("jar-approve"), enabled = running == null, onClick = {
                val approval = plan.confirm()
                review = null
                perform {
                    var submittedId: String? = null
                    var completed = false
                    try {
                        val response = withTimeout(45_000) {
                            var job = withContext(NonCancellable) {
                                withTimeout(5_000) { client.submit(request, approval) }
                                    .also { submittedId = it.jobId }
                            }
                            while (!job.state.terminal) {
                                delay(100)
                                job = withTimeout(3_500) { client.status(job.jobId) }
                                require(job.jobId == submittedId && job.operation == jarOperation)
                            }
                            job
                        }
                        completed = true
                        require(response.state == CompanionJobState.SUCCEEDED && response.operation == jarOperation) {
                            response.error?.let { "${it.code}: ${it.message}" } ?: strings("jar_tool_failed")
                        }
                        val reply = response.result as? CompanionJsonObject
                            ?: throw IllegalArgumentException(strings("jar_tool_failed"))
                        val project = response.artifact(reply.obj("project"))
                        require(reply.string("libraryClass") == libraryClass &&
                            reply.string("librarySha256") == request.inputs[1].sha256 &&
                            project.sha256 == request.inputs[0].sha256) { strings("jar_tool_failed") }
                        val ports = reply.array("ports").map { raw ->
                            val port = raw as CompanionJsonObject
                            val direction = when (port.string("direction")) {
                                "input" -> strings("jar_tool_input")
                                "output" -> strings("jar_tool_output")
                                else -> throw IllegalArgumentException(strings("jar_tool_failed"))
                            }
                            strings("jar_tool_port", "name" to port.string("name"),
                                "direction" to direction, "width" to port.long("width"))
                        }
                        val observations = reply.array("frames").also { require(it.size == approvedSteps.values.size) }
                            .mapIndexed { index, frame ->
                                val fields = (frame as CompanionJsonObject).fields
                                "${index + 1}. " + fields.entries.joinToString("  ") { (pin, value) ->
                                    "$pin=${(value as CompanionJsonString).value}"
                                }
                            }
                        val trace = if (request.parameters["instanceTrace"] == CompanionJsonBoolean(true)) {
                            reply.obj("instanceTrace").readJarInstanceTrace(approvedSteps.values.size)
                        } else null
                        result = JarExecutionResult(reply.string("libraryId"), reply.string("circuit"),
                            ports, observations, project, trace)
                    } finally {
                        if (!completed) submittedId?.let { id ->
                            withContext(NonCancellable) { runCatching { withTimeout(3_000) { client.cancel(id) } } }
                        }
                    }
                }
            }) { Text(strings("jar_tool_run")) }
        }
        if (running != null) {
            Button(modifier = Modifier.testTag("jar-cancel"), onClick = { running?.cancel() }) { Text(strings("jar_tool_cancel")) }
        }
        status?.let { Text(strings(it)) }
        result?.let { reply ->
            Text(strings("jar_tool_results", "library" to reply.library, "circuit" to reply.circuit))
            Text(reply.ports.joinToString("\n"))
            Text(reply.frames.joinToString("\n"))
            reply.trace?.let { trace ->
                Text(strings("jar_tool_trace_title"))
                trace.instances.forEach { instance ->
                    val path = instance.path.joinToString(" / ") { "(${it.x}, ${it.y})" }
                    val values = trace.frames.last().getValue(instance.id)
                    val ports = instance.layout.ports.zip(values).joinToString("  ") { (port, value) ->
                        "${port.name}=${value.display}"
                    }
                    Text(listOf(instance.circuit, path, "${instance.factory} (${instance.location.x}, ${instance.location.y})", ports)
                        .filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
            Button(modifier = Modifier.testTag("jar-save-copy"), onClick = {
                if (context.isCurrentEditorContext()) {
                    val project = reply.project
                    assets.create(DocumentAsset(project.name, "application/octet-stream", project.bytes)) { result ->
                        if (result is AssetActionResult.Failure) status = result.notice
                    }
                }
            }) { Text(strings("jar_tool_save")) }
        }
    }
}
