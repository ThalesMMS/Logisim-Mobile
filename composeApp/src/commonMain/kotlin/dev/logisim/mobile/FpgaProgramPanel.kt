package dev.logisim.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

internal const val FPGA_BITSTREAM_MAX_BYTES = 4 * 1024 * 1024
private val bitstreamExtensions = setOf("bit", "bin", "fs", "sof", "pof", "jed")
private const val CPLD_NONVOLATILE = "cpld-nonvolatile"

private fun bitstreamExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

internal fun cpldProgrammingMode(fileName: String): String? =
    CPLD_NONVOLATILE.takeIf { bitstreamExtension(fileName) in setOf("pof", "jed") }

internal fun CompanionProgramTargetProfile?.acceptsProgrammingFile(fileName: String): Boolean =
    when (val extension = bitstreamExtension(fileName)) {
        "pof" -> this?.let { it.transport == "quartus-cpld" && it.chainPosition != null } == true
        "jed" -> this?.let { it.transport == "ise-cpld" && it.board.equals("MICROLABTLE", ignoreCase = true) && it.chainPosition != null } == true
        else -> extension in bitstreamExtensions && this?.transport !in setOf("quartus-cpld", "ise-cpld")
    }

internal fun isFpgaBitstream(asset: DocumentAsset): Boolean {
    val extension = bitstreamExtension(asset.name)
    val limit = if (extension == "sof") QUARTUS_SOF_MAX_BYTES else FPGA_BITSTREAM_MAX_BYTES
    return extension in bitstreamExtensions && asset.size in 1..limit
}

internal class FpgaImportedBitstream private constructor(val name: String, val artifact: CompanionArtifact) {
    companion object {
        fun read(asset: DocumentAsset): FpgaImportedBitstream {
            require(isFpgaBitstream(asset))
            val name = "imported-bitstream.${bitstreamExtension(asset.name)}"
            val artifact = if (asset.size > FPGA_BITSTREAM_MAX_BYTES) CompanionArtifact.fromQuartusSof(name, asset.bytes)
                else CompanionArtifact.fromBytes(name, asset.bytes)
            return FpgaImportedBitstream(asset.name, artifact)
        }
    }
}

/** A reviewed request and its retry ID remain together across panel recompositions. */
internal class FpgaProgramSession {
    private data class LargeSofRequest(val artifact: CompanionArtifact, val target: String, val profile: CompanionProgramTargetProfile)

    private var largeSofRequest: LargeSofRequest? = null
    private var quartusSofBytes by mutableStateOf<Long?>(null)
    var targets by mutableStateOf<List<String>>(emptyList())
        private set
    var profiles by mutableStateOf<Map<String, CompanionProgramTargetProfile>>(emptyMap())
        private set
    var flashTargets by mutableStateOf<List<String>>(emptyList())
        private set
    var target by mutableStateOf("")
        private set
    var flash by mutableStateOf(false)
        private set
    var reviewedHash: String? = null
    var review by mutableStateOf<CompanionPlan?>(null)
    var request: CompanionJobRequest? = null
    var requestId: String? = null
    var submitAttempted by mutableStateOf(false)
    var job by mutableStateOf<CompanionJob?>(null)
    var outcome by mutableStateOf<FpgaProgramOutcome?>(null)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<LocalizedText?>(null)
    var pollFailed by mutableStateOf(false)
    var pollRevision by mutableIntStateOf(0)
    val active get() = job?.state?.terminal == false
    val locked get() = busy || active || submitAttempted && review != null
    fun selectable(value: String, fileName: String? = null) = value in targets && profiles[value]?.unavailableReason == null &&
        (fileName == null || profiles[value].acceptsProgrammingFile(fileName))

    fun selectable(value: String, artifact: CompanionArtifact): Boolean {
        if (!selectable(value, artifact.name)) return false
        if (artifact.size <= FPGA_BITSTREAM_MAX_BYTES) return true
        val profile = profiles[value] ?: return false
        return bitstreamExtension(artifact.name) == "sof" && artifact.size.toLong() <= (quartusSofBytes ?: 0L) &&
            profile.transport == "quartus" && profile.chainPosition != null && profile.sofPart != null &&
            (artifact.quartusSofPart == null || artifact.quartusSofPart == profile.sofPart)
    }

    fun clear() {
        target = ""
        flash = false
        clearReview()
    }

    private fun clearReview() {
        review = null
        request = null
        largeSofRequest = null
        requestId = null
        submitAttempted = false
        reviewedHash = null
        job = null
        outcome = null
        notice = null
    }

    fun selectTarget(value: String) {
        if (locked || value == target) return
        target = value
        flash = false
        clearReview()
    }

    fun selectFlash(value: Boolean) {
        if (locked || value == flash || value && target !in flashTargets) return
        flash = value
        clearReview()
    }

    suspend fun refresh(client: CompanionClient) {
        val capabilities = client.capabilities()
        quartusSofBytes = capabilities.limits.quartusSofBytes
        val capability = capabilities.operations.singleOrNull { it.name == "fpga-program" }
        val approved = capability?.takeIf { it.allowed && it.requiresConfirmation }
        profiles = approved?.programTargetProfiles.orEmpty()
        targets = approved?.programTargets.orEmpty().filter { approved?.available == true || it in profiles }
        flashTargets = approved?.flashProgramTargets.orEmpty().filter { selectable(it) }
        if (!active && !submitAttempted && target.isNotEmpty() && (!selectable(target) || flash && target !in flashTargets)) {
            if (!selectable(target)) target = ""
            flash = false
            clearReview()
        }
    }

    suspend fun prepare(client: CompanionClient, artifact: CompanionArtifact) {
        check(!active && !(submitAttempted && review != null))
        val selectedTarget = target
        val selectedFlash = flash
        job = null
        outcome = null
        refresh(client)
        if (selectedFlash && selectedTarget !in flashTargets)
            throw CompanionProtocolException("fpga-flash-not-allowed", "The host no longer approves flash for this board and cable.")
        require(selectable(selectedTarget, artifact) && artifact.size > 0)
        val largeSof = if (artifact.size > FPGA_BITSTREAM_MAX_BYTES) {
            require(!selectedFlash)
            LargeSofRequest(artifact, selectedTarget, profiles.getValue(selectedTarget))
        } else null
        val next = if (largeSof == null) {
            val parameters = mutableMapOf<String, CompanionJson>("target" to CompanionJsonString(selectedTarget),
                "bitstream" to CompanionJsonString(artifact.name), "flash" to CompanionJsonBoolean(selectedFlash))
            profiles[selectedTarget]?.let { parameters["profile"] = CompanionJsonString(it.fingerprint) }
            CompanionJobRequest("fpga-program", listOf(CompanionInput.fromBytes(artifact.name, artifact.bytes)), parameters)
        } else null
        val plan = if (largeSof != null) client.planLargeQuartusSof(largeSof.artifact, largeSof.target, largeSof.profile)
            else client.plan(requireNotNull(next))
        if (!plan.requiresConfirmation) throw CompanionProtocolException("invalid-plan", "Programming must require confirmation.")
        request = next
        largeSofRequest = largeSof
        review = plan
        reviewedHash = artifact.sha256
        submitAttempted = false
        requestId = "fpga-program-" + Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    suspend fun submit(client: CompanionClient) {
        val plan = requireNotNull(review)
        val approval = plan.confirm()
        val id = requireNotNull(requestId)
        val largeSof = largeSofRequest
        submitAttempted = true
        val submitted = try {
            if (largeSof != null) {
                client.submitLargeQuartusSof(largeSof.artifact, largeSof.target, largeSof.profile, approval, id)
            } else client.submit(requireNotNull(request), approval, id)
        } catch (failure: CompanionProtocolException) {
            if (failure.httpStatus == 400 &&
                (failure.code == "confirmation-required" || failure.code == "fpga-part-mismatch")) {
                clearReview()
            }
            throw failure
        }
        receive(submitted)
        review = null
        submitAttempted = false
    }

    suspend fun synchronize(client: CompanionClient, id: String) = receive(client.status(id), id)

    suspend fun cancel(client: CompanionClient, id: String) = receive(client.cancel(id), id)

    fun receive(snapshot: CompanionJob, expectedId: String? = null) {
        if (snapshot.operation != "fpga-program" || expectedId != null && snapshot.jobId != expectedId)
            throw CompanionProtocolException("invalid-fpga-job", "The companion returned a different programming job.")
        if (job?.jobId == snapshot.jobId && job?.state?.terminal == true && !snapshot.state.terminal) return
        if (snapshot.state == CompanionJobState.SUCCEEDED) {
            val result = snapshot.result as? CompanionJsonObject ?: throw CompanionProtocolException("invalid-fpga-result", "Missing programming result.")
            val expectedTarget = largeSofRequest?.target
                ?: (request?.parameters?.get("target") as? CompanionJsonString)?.value ?: target
            val expectedFlash = largeSofRequest == null && ((request?.parameters?.get("flash") as? CompanionJsonBoolean)?.value ?: flash)
            val expectedMode = request?.inputs?.singleOrNull()?.name?.let(::cpldProgrammingMode)?.let(::CompanionJsonString)
            if (!result.boolean("programmerReportedSuccess") || result.boolean("hardwareVerified") ||
                result.string("target") != expectedTarget || result.boolean("flash") != expectedFlash ||
                result.fields["programmingMode"] != expectedMode)
                throw CompanionProtocolException("invalid-fpga-result", "The programming result contradicts the reviewed request.")
            outcome = FpgaProgramOutcome(true, false, expectedFlash)
        } else outcome = null
        job = snapshot
    }
}

internal data class FpgaProgramOutcome(val programmerReportedSuccess: Boolean, val hardwareVerified: Boolean, val flash: Boolean)

@Composable
internal fun FpgaImportedProgramPanel(
    client: CompanionClient,
    bitstream: FpgaImportedBitstream,
    session: FpgaProgramSession,
    onBack: () -> Unit,
) {
    val text = rememberLocalizer()
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !session.locked, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text("fpga_companion_back"))
            }
            Text(text("fpga_program_imported_name", "name" to bitstream.name))
        }
        item { FpgaProgramPanel(client, bitstream.artifact, session) }
    }
}

@Composable
internal fun FpgaProgramPanel(client: CompanionClient, artifact: CompanionArtifact, session: FpgaProgramSession) {
    val text = rememberLocalizer()
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit) {
        if (session.busy) return
        session.busy = true
        session.notice = null
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { session.notice = message("fpga_program_failure", "detail" to (failure.message ?: failure::class.simpleName.orEmpty())) }
            finally { session.busy = false }
        }
    }
    LaunchedEffect(client) { perform { session.refresh(client) } }
    val targets = session.targets
    val cpld = cpldProgrammingMode(artifact.name) != null
    val largeSof = artifact.size > FPGA_BITSTREAM_MAX_BYTES
    val job = session.job
    LaunchedEffect(client, job?.jobId, session.pollRevision) {
        val id = job?.jobId ?: return@LaunchedEffect
        session.pollFailed = false
        while (session.job?.jobId == id && session.active) {
            delay(500)
            try { session.synchronize(client, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                session.notice = message("fpga_program_failure", "detail" to (failure.message ?: ""))
                session.pollFailed = true
                break
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(text("fpga_program_title"), style = MaterialTheme.typography.titleMedium)
        Text(text(if (cpld) "fpga_program_cpld_help" else "fpga_program_help"))
        SelectionContainer { Text(text("fpga_companion_input_digest", "name" to artifact.name, "size" to artifact.size, "sha256" to artifact.sha256)) }
        if (targets.isEmpty() || largeSof && targets.none { session.selectable(it, artifact) }) Text(text("fpga_program_no_targets"))
        targets.forEach { target ->
            val profile = session.profiles[target]
            OutlinedButton(onClick = { session.selectTarget(target) }, enabled = !session.locked && session.selectable(target, artifact),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column {
                    Text(if (session.target == target) text("fpga_program_selected", "target" to target) else target)
                    if (profile != null) FpgaTargetIdentity(profile, text)
                }
            }
            profile?.unavailableReason?.let { reason ->
                Text(text("fpga_program_target_unavailable", "reason" to reason), color = MaterialTheme.colorScheme.error)
            }
        }
        if (!cpld && session.selectable(session.target, artifact)) {
            Text(text("fpga_program_mode"))
            if (!largeSof && session.target in session.flashTargets) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !session.flash, onClick = { session.selectFlash(false) },
                        enabled = !session.locked, label = { Text(text("fpga_program_mode_ram")) })
                    FilterChip(selected = session.flash, onClick = { session.selectFlash(true) },
                        enabled = !session.locked, label = { Text(text("fpga_program_mode_flash")) })
                }
            } else Text(text("fpga_program_mode_ram"))
        }
        Button(onClick = { perform { session.prepare(client, artifact) } },
            enabled = session.selectable(session.target, artifact) && (!largeSof || !session.flash) && !session.locked && session.review == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("fpga_program_prepare")) }
        val reviewed = session.review
        if (reviewed != null) {
            val reviewedFlash = reviewed.parameters.boolean("flash")
            val reviewedTarget = reviewed.parameters.string("target")
            val profile = session.profiles[reviewedTarget]
            val reviewedProfile = reviewed.parameters.fields["profile"] as? CompanionJsonString
            Text(text("fpga_program_review"))
            Text(text("fpga_program_review_target", "target" to reviewedTarget))
            if (profile != null && reviewedProfile?.value == profile.fingerprint) {
                FpgaTargetIdentity(profile, text)
                if (reviewedFlash) {
                    profile.flashDevice?.let { Text(text("fpga_program_flash_device", "device" to it)) }
                    profile.flashChainPosition?.let { Text(text("fpga_program_flash_chain_position", "position" to it)) }
                }
            }
            if (!cpld) Text(text(if (reviewedFlash) "fpga_program_mode_flash" else "fpga_program_mode_ram"))
            reviewed.inputs.forEach { input -> SelectionContainer {
                Text(text("fpga_companion_input_digest", "name" to input.name, "size" to input.size, "sha256" to input.sha256))
            } }
            if (!session.submitAttempted) OutlinedButton(onClick = { session.clear() }, enabled = !session.busy) { Text(text("fpga_program_discard")) }
            Button(onClick = { perform {
                session.submit(client)
            } }, enabled = !session.busy && !session.active && session.selectable(reviewedTarget, artifact) &&
                    (!reviewedFlash || reviewedTarget in session.flashTargets) && reviewedProfile?.value == profile?.fingerprint &&
                    session.reviewedHash == artifact.sha256,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(text(when {
                    cpld -> "fpga_program_cpld_confirm"
                    reviewedFlash -> "fpga_program_confirm_flash"
                    else -> "fpga_program_confirm"
                }))
            }
        }
        job?.let { current ->
            Text(text("fpga_companion_job", "id" to current.jobId))
            Text(current.state.wireName)
            current.error?.let { Text(text("fpga_companion_error_detail", "code" to it.code, "detail" to it.message), color = MaterialTheme.colorScheme.error) }
            if (!current.state.terminal) OutlinedButton(onClick = { perform { session.cancel(client, current.jobId) } }, enabled = !session.busy) { Text(text("fpga_companion_cancel")) }
            if (session.pollFailed) OutlinedButton(onClick = { session.pollRevision++ }, enabled = !session.busy) { Text(text("fpga_companion_retry_status")) }
            current.logs.takeLast(40).forEach { SelectionContainer { Text(text("fpga_companion_log_entry", "stream" to it.stream, "detail" to it.text.takeLast(8192))) } }
        }
        session.outcome?.let {
            Text(text("fpga_program_reported", "value" to text(if (it.programmerReportedSuccess) "fpga_companion_yes" else "fpga_companion_no")))
            Text(text(if (it.flash) "fpga_program_flash_unverified" else "fpga_program_hardware_unverified"))
        }
        session.notice?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FpgaTargetIdentity(profile: CompanionProgramTargetProfile, text: Localizer) {
    val board = profile.part?.let { "${profile.board} ($it)" } ?: profile.board
    Text(text("fpga_program_target_profile", "board" to board, "cable" to profile.cable,
        "transport" to profile.transport))
    profile.chainPosition?.let { Text(text("fpga_program_chain_position", "position" to it)) }
    profile.sofPart?.let { Text("${text("fpga_editor_part")} $it") }
}
