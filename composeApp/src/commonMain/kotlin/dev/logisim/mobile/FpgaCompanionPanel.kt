package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionJob
import dev.logisim.mobile.core.CompanionJobRequest
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionJsonBoolean
import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionJsonObject
import dev.logisim.mobile.core.CompanionJsonString
import dev.logisim.mobile.core.CompanionPlan
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.FPGA_VENDOR_ARTIFACT_MAX_BYTES
import dev.logisim.mobile.core.FpgaBuildGoal
import dev.logisim.mobile.core.companionBuildGoals
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.companionBuildOperation
import dev.logisim.mobile.core.companionRequest
import dev.logisim.mobile.core.producesQuartusSof
import dev.logisim.mobile.core.quartusProgrammingFilename
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kept by the editor tool so dismissing its UI does not discard a remote job. */
class FpgaCompanionSession {
    private val receiveLock = Mutex()
    internal val program = FpgaProgramSession()
    internal var importedBitstream by mutableStateOf<FpgaImportedBitstream?>(null)
    internal var displayedPlan by mutableStateOf<FpgaBuildPlan?>(null)
    internal var goal by mutableStateOf(FpgaBuildGoal.SYNTHESIS)
    internal var reviewedBuild by mutableStateOf<FpgaBuildPlan?>(null)
    internal var reviewedGoal by mutableStateOf<FpgaBuildGoal?>(null)
    internal var review by mutableStateOf<CompanionPlan?>(null)
    internal var request: CompanionJobRequest? = null
    internal var requestId: String? = null
    internal var submittedRequestId by mutableStateOf<String?>(null)
    internal var job by mutableStateOf<CompanionJob?>(null)
    internal var outcome by mutableStateOf<FpgaCompanionOutcome?>(null)
    internal var artifacts by mutableStateOf<List<CompanionArtifact>>(emptyList())
    internal var busy by mutableStateOf(false)
    internal var notice by mutableStateOf<LocalizedText?>(null)
    internal var pollFailed by mutableStateOf(false)
    internal var pollRevision by mutableStateOf(0)
    internal val active: Boolean get() = job?.state?.terminal == false
    internal val retainsClient: Boolean get() = busy || active || program.locked || importedBitstream != null

    internal fun selectGoal(value: FpgaBuildGoal) {
        if (active || busy || value == goal) return
        goal = value
        review = null
        request = null
        requestId = null
        notice = null
    }

    internal fun goalFor(plan: FpgaBuildPlan): FpgaBuildGoal =
        plan.flow.companionBuildGoals().singleOrNull() ?: goal

    internal suspend fun prepare(plan: FpgaBuildPlan, client: CompanionClient) {
        val selectedGoal = goalFor(plan)
        val request = plan.companionRequest(selectedGoal)
        val binarySof = plan.producesQuartusSof && client.capabilities().limits.quartusSofBytes != null
        val nextRequest = if (binarySof) {
            request.copy(parameters = request.parameters + ("binarySof" to CompanionJsonBoolean(true)))
        } else request
        val nextReview = client.plan(nextRequest)
        if (binarySof && !nextReview.requiresConfirmation)
            throw CompanionProtocolException("invalid-plan", "A binary Quartus SOF build must require confirmation.")
        this.request = nextRequest
        review = nextReview
        reviewedBuild = plan
        reviewedGoal = selectedGoal
        requestId = "fpga-" + Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    internal suspend fun receive(snapshot: CompanionJob, expectedId: String? = null, client: CompanionClient? = null) = receiveLock.withLock {
        val expectedOperation = request?.operation ?: job?.operation ?: "fpga-synthesize"
        if (snapshot.operation != expectedOperation || expectedId != null && snapshot.jobId != expectedId)
            throw CompanionProtocolException("invalid-fpga-job", "The companion returned a different FPGA job.")
        if (job?.jobId == snapshot.jobId && job?.state?.terminal == true) return@withLock
        if (snapshot.state == CompanionJobState.SUCCEEDED) {
            outcome = null
            artifacts = emptyList()
            val result = snapshot.result as? CompanionJsonObject
                ?: throw CompanionProtocolException("invalid-fpga-result", "The companion returned no FPGA result object.")
            val nextOutcome = FpgaCompanionOutcome(result.boolean("synthesized"), result.boolean("placedAndRouted"), result.boolean("bitstreamGenerated"),
                result.boolean("programmed"), result.boolean("hardwareVerified"))
            if (!nextOutcome.synthesized || nextOutcome.programmed || nextOutcome.hardwareVerified ||
                nextOutcome.bitstreamGenerated && !nextOutcome.placedAndRouted ||
                snapshot.operation != "fpga-synthesize" && !nextOutcome.bitstreamGenerated)
                throw CompanionProtocolException("invalid-fpga-result", "The synthesis result contradicts the operation contract.")
            val maxArtifactBytes = if (snapshot.operation == "fpga-synthesize") 2 * 1024 * 1024 else FPGA_VENDOR_ARTIFACT_MAX_BYTES
            val expectedVendorArtifact = when (snapshot.operation) {
                "fpga-ise-cpld-build" -> "design.jed"
                "fpga-ise-build" -> "design.bit"
                "fpga-quartus-build" -> request?.quartusProgrammingFilename() ?: "design.sof"
                else -> null
            }
            val values = result.array("artifacts")
            val nextArtifacts = if (values.any { (it as? CompanionJsonObject)?.fields?.get("transport") == CompanionJsonString("binary") }) {
                val build = reviewedBuild
                if (client == null || build == null || !build.producesQuartusSof || expectedVendorArtifact != "design.sof" ||
                    request?.parameters?.get("binarySof") != CompanionJsonBoolean(true) || values.size != 1) {
                    throw CompanionProtocolException("invalid-artifact", "The build did not request a binary Quartus SOF.")
                }
                if (result.string("flow") != "quartus" || result.string("top") != build.topLevelName) {
                    throw CompanionProtocolException("invalid-fpga-result", "The binary SOF does not match the reviewed build.")
                }
                listOf(client.downloadQuartusSof(snapshot, values.single(), build.board.device.part))
            } else values.map { snapshot.artifact(it, maxArtifactBytes) }
            if (nextArtifacts.isEmpty() || nextArtifacts.map { it.name }.distinct().size != nextArtifacts.size)
                throw CompanionProtocolException("invalid-artifact", "The companion returned missing or duplicate artifacts.")
            if (expectedVendorArtifact != null && nextArtifacts.singleOrNull()?.name != expectedVendorArtifact)
                throw CompanionProtocolException("invalid-artifact", "The companion did not return the reviewed board's programming file.")
            outcome = nextOutcome
            artifacts = nextArtifacts
        }
        job = snapshot
    }
}

internal data class FpgaCompanionOutcome(
    val synthesized: Boolean,
    val placedAndRouted: Boolean,
    val bitstreamGenerated: Boolean,
    val programmed: Boolean,
    val hardwareVerified: Boolean,
)

@Composable
fun FpgaCompanionPanel(
    plan: FpgaBuildPlan,
    client: CompanionClient,
    onBack: () -> Unit,
    onArtifacts: ((List<CompanionArtifact>) -> Unit)? = null,
    modifier: Modifier = Modifier,
    session: FpgaCompanionSession = remember { FpgaCompanionSession() },
) {
    val text = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val currentJob = session.job
    val reviewed = session.review
    val selectedGoal = session.goalFor(plan)
    val reviewMatches = session.reviewedBuild == plan && session.reviewedGoal == selectedGoal
    val jedecBuild = plan.flow.companionBuildOperation(plan.board) == "fpga-ise-cpld-build"

    fun perform(action: suspend () -> Unit) {
        if (session.busy) return
        session.busy = true
        session.notice = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                session.notice = fpgaCompanionFailure(failure)
            } finally {
                session.busy = false
            }
        }
    }

    LaunchedEffect(client, currentJob?.jobId, session.pollRevision) {
        val id = currentJob?.jobId ?: return@LaunchedEffect
        session.pollFailed = false
        while (session.job?.jobId == id && session.active) {
            delay(500)
            try {
                session.receive(client.status(id), id, client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                session.notice = fpgaCompanionFailure(failure)
                session.pollFailed = true
                break
            }
        }
    }

    LazyColumn(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text("fpga_companion_title"), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                TextButton(onClick = onBack, enabled = !session.busy && !session.program.locked, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("fpga_companion_back"))
                }
                Text(text("fpga_companion_build", "board" to plan.board.name, "top" to plan.topLevelName))
                Text(text("fpga_companion_prepare_explanation"))
                for (goal in plan.flow.companionBuildGoals()) {
                    OutlinedButton(onClick = { session.selectGoal(goal) }, enabled = !session.busy && !session.active && !session.program.locked,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        val label = text(when {
                            goal == FpgaBuildGoal.SYNTHESIS -> "fpga_companion_goal_synthesis"
                            jedecBuild -> "fpga_companion_goal_jedec"
                            else -> "fpga_companion_goal_bitstream"
                        })
                        Text(if (selectedGoal == goal) text("fpga_companion_selected_goal", "goal" to label) else label)
                    }
                }
                Button(onClick = { perform { session.prepare(plan, client) } }, enabled = !session.busy && !session.active && !session.program.locked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_companion_prepare"))
                }
                if (session.active) Text(text("fpga_companion_active_job"))
                session.notice?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
                if (session.busy) Text(text("fpga_companion_contacting"))
            }
        }
        if (reviewed != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(text("fpga_companion_review"), style = MaterialTheme.typography.titleMedium)
                    Text(text("fpga_companion_operation", "operation" to reviewed.operation))
                    SelectionContainer { Text(CompanionJsonCodec.write(reviewed.parameters), fontFamily = FontFamily.Monospace) }
                    Text(text("fpga_companion_expiry", "seconds" to reviewed.expiresInSeconds))
                    if (!reviewMatches) Text(text("fpga_companion_stale_review"), color = MaterialTheme.colorScheme.error)
                }
            }
            items(reviewed.inputs, key = { "input-${it.name}" }) { input ->
                SelectionContainer {
                    Text(text("fpga_companion_input_digest", "name" to input.name, "size" to input.size, "sha256" to input.sha256),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Button(enabled = reviewMatches && !session.busy && !session.active && !session.program.locked && session.requestId != session.submittedRequestId,
                    onClick = {
                        val request = session.request ?: return@Button
                        perform {
                            val approval = reviewed.confirm()
                            val submitted = client.submit(request, approval, session.requestId)
                            session.outcome = null
                            session.artifacts = emptyList()
                            session.program.clear()
                            session.receive(submitted, client = client)
                            session.submittedRequestId = session.requestId
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_companion_execute"))
                }
            }
        }
        if (currentJob != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(text("fpga_companion_job", "id" to currentJob.jobId), style = MaterialTheme.typography.titleSmall)
                    Text(text("fpga_companion_job_status", "state" to companionJobState(currentJob.state, text)))
                    currentJob.error?.let { error ->
                        Text(text("fpga_companion_error_detail", "code" to error.code, "detail" to error.message),
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (!currentJob.state.terminal) OutlinedButton(enabled = !session.busy, onClick = {
                        perform { session.receive(client.cancel(currentJob.jobId), currentJob.jobId, client) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("fpga_companion_cancel")) }
                    if (session.pollFailed) OutlinedButton(onClick = { session.pollRevision++ }, enabled = !session.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("fpga_companion_retry_status")) }
                    session.outcome?.let { outcome ->
                        FpgaOutcomeFlag("fpga_companion_synthesized", outcome.synthesized, text)
                        FpgaOutcomeFlag("fpga_companion_placed_and_routed", outcome.placedAndRouted, text)
                        FpgaOutcomeFlag(if (currentJob.operation == "fpga-ise-cpld-build") "fpga_companion_jedec_generated"
                            else "fpga_companion_bitstream_generated", outcome.bitstreamGenerated, text)
                        FpgaOutcomeFlag("fpga_companion_programmed", outcome.programmed, text)
                        FpgaOutcomeFlag("fpga_companion_hardware_verified", outcome.hardwareVerified, text)
                    }
                    if (session.artifacts.isNotEmpty()) Text(text("fpga_companion_validated_artifacts"), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(session.artifacts, key = { "artifact-${it.name}" }) { artifact ->
                SelectionContainer {
                    Text(text("fpga_companion_artifact_digest", "name" to artifact.name, "size" to artifact.size, "sha256" to artifact.sha256),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.artifacts.isNotEmpty() && onArtifacts != null) item {
                Button(onClick = { perform { onArtifacts(session.artifacts) } }, enabled = !session.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("fpga_companion_export_artifacts")) }
            }
            if (currentJob.logs.isNotEmpty()) {
                item { Text(text("fpga_companion_logs"), style = MaterialTheme.typography.titleMedium) }
                items(currentJob.logs.takeLast(40)) { log ->
                    SelectionContainer {
                        Text(text("fpga_companion_log_entry", "stream" to log.stream, "detail" to log.text.takeLast(8192)),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(text("fpga_companion_log_limits"), style = MaterialTheme.typography.bodySmall) }
            }
        }
        val bitstream = session.artifacts.singleOrNull {
            when (currentJob?.operation) {
                "fpga-synthesize" -> it.name.substringAfterLast('.', "").lowercase() in setOf("bit", "bin", "fs", "sof")
                "fpga-quartus-build" -> it.name == "design.pof" || it.quartusSofPart != null
                "fpga-ise-cpld-build" -> it.name == "design.jed"
                else -> false
            }
        }
        if (session.outcome?.bitstreamGenerated == true && bitstream != null) {
            item { FpgaProgramPanel(client, bitstream, session.program) }
        }
    }
}

@Composable
private fun FpgaOutcomeFlag(key: String, value: Boolean, text: Localizer) {
    Text(text("fpga_companion_outcome_flag", "label" to text(key), "value" to text(if (value) "fpga_companion_yes" else "fpga_companion_no")))
}

private fun companionJobState(state: CompanionJobState, text: Localizer): String = text(when (state) {
    CompanionJobState.QUEUED -> "fpga_companion_state_queued"
    CompanionJobState.RUNNING -> "fpga_companion_state_running"
    CompanionJobState.SUCCEEDED -> "fpga_companion_state_succeeded"
    CompanionJobState.FAILED -> "fpga_companion_state_failed"
    CompanionJobState.CANCELLED -> "fpga_companion_state_cancelled"
})

private fun fpgaCompanionFailure(failure: Exception): LocalizedText = when (failure) {
    is LocalizedArgumentException -> failure.text
    is CompanionProtocolException -> message("fpga_companion_error_detail", "code" to failure.code, "detail" to failure.message)
    is CompanionTransportException -> message("fpga_companion_transport_error", "code" to failure.code)
    else -> message("fpga_companion_action_error", "detail" to (failure.message ?: failure::class.simpleName.orEmpty()))
}
