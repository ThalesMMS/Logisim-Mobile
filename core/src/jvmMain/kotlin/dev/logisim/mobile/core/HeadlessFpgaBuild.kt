package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import java.io.PrintStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TOKEN_ENV = "LOGISIM_MOBILE_COMPANION_TOKEN"
private val stageName = Regex("[a-z][a-z0-9-]{0,47}")

internal fun buildHeadlessFpga(
    endpoint: String,
    build: FpgaBuildPlan,
    request: CompanionJobRequest,
    retryId: String?,
    stderr: PrintStream,
    language: AppLanguage,
    checkCancellation: () -> Unit,
): List<CompanionArtifact> {
    val connection = fpgaStage("companion", language) {
        val token = System.getenv(TOKEN_ENV) ?: throw HeadlessFpgaError("companion", "Set $TOKEN_ENV before building.")
        CompanionConnection(CompanionEndpoint.parse(endpoint), CompanionToken.parse(token))
    }
    val client = CompanionClient(connection, timeoutMillis = 10_000)
    val requestId = retryId ?: "fpga-${UUID.randomUUID()}"
    return try {
        runBlocking {
            var job: CompanionJob? = null
            suspend fun cancelActiveJob() {
                val active = job?.takeUnless { it.state.terminal } ?: return
                withContext(NonCancellable) {
                    try {
                        withTimeout(10_000) { client.cancel(active.jobId).checkedFor(request, active.jobId) }
                    } catch (_: Exception) {
                        stderr.println("warning=fpga stage=cancel job-id=${active.jobId} Companion cancellation could not be confirmed.")
                    }
                }
            }
            try {
                withTimeout(31 * 60_000L) {
                    checkCancellation()
                    val capabilities = client.capabilities()
                    val operation = capabilities.operations.firstOrNull { it.name == request.operation }
                        ?: throw HeadlessFpgaError("companion", "The host does not implement ${request.operation}.")
                    if (!operation.allowed || !operation.available) {
                        throw HeadlessFpgaError("companion", "The host has not enabled an available ${request.operation} operation.")
                    }
                    val binarySof = build.producesQuartusSof && capabilities.limits.quartusSofBytes != null
                    if (binarySof && !operation.requiresConfirmation) {
                        throw HeadlessFpgaError("companion", "A binary Quartus SOF build must require confirmation.")
                    }
                    val submission = if (binarySof) request.copy(
                        parameters = request.parameters + ("binarySof" to CompanionJsonBoolean(true)),
                    ) else request
                    checkCancellation()
                    val approval = if (operation.requiresConfirmation) {
                        val plan = client.plan(submission)
                        if (!plan.requiresConfirmation) throw HeadlessFpgaError("companion", "The host omitted the required build confirmation.")
                        plan.confirm()
                    } else null
                    checkCancellation()
                    stderr.println("status=fpga-approved operation=${request.operation} request-id=$requestId")
                    job = client.submit(submission, approval, requestId).checkedFor(submission)
                    stderr.println("status=fpga-submitted job-id=${requireNotNull(job).jobId}")
                    var snapshot = requireNotNull(job)
                    while (!snapshot.state.terminal) {
                        checkCancellation()
                        delay(250)
                        checkCancellation()
                        snapshot = client.status(snapshot.jobId).checkedFor(request, snapshot.jobId)
                        job = snapshot
                    }
                    checkCancellation()
                    if (snapshot.state == CompanionJobState.CANCELLED) throw HeadlessCircuitCli.Cancelled()
                    if (snapshot.state != CompanionJobState.SUCCEEDED) {
                        val error = snapshot.error
                        val stage = ((error?.details as? CompanionJsonObject)?.fields?.get("stage") as? CompanionJsonString)?.value
                        throw HeadlessFpgaError(stage?.takeIf(stageName::matches) ?: "build",
                            error?.message?.singleLine() ?: "The companion build failed.")
                    }
                    try {
                        verifiedBitstream(build, snapshot, client, binarySof).also { checkCancellation() }
                    } catch (error: CompanionProtocolException) {
                        throw HeadlessFpgaError("artifact", error.text.resolve(language).singleLine())
                    }
                }
            } catch (error: HeadlessCircuitCli.Cancelled) {
                cancelActiveJob()
                throw error
            } catch (error: CancellationException) {
                cancelActiveJob()
                throw error
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw HeadlessFpgaError("build", "The companion build exceeded the CLI time limit.")
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw HeadlessCircuitCli.Cancelled()
    } catch (error: CompanionProtocolException) {
        throw HeadlessFpgaError("companion", "${error.code.singleLine()}: ${error.text.resolve(language).singleLine()}"
            .replace(connection.token.value, "[redacted]"))
    } catch (error: CompanionTransportException) {
        throw HeadlessFpgaError("companion", error.message.orEmpty())
    }
}

private fun CompanionJob.checkedFor(request: CompanionJobRequest, jobId: String? = null): CompanionJob {
    if (operation != request.operation || jobId != null && this.jobId != jobId) {
        throw HeadlessFpgaError("companion", "The companion returned a different FPGA job.")
    }
    return this
}

internal suspend fun verifiedBitstream(
    build: FpgaBuildPlan,
    job: CompanionJob,
    client: CompanionClient? = null,
    binarySofRequested: Boolean = false,
): List<CompanionArtifact> {
    val (flow, bitstream) = when (build.flow) {
        FpgaFlow.OPEN_ICE40 -> "ice40" to "design.bin"
        FpgaFlow.OPEN_ECP5 -> "ecp5" to "design.bit"
        FpgaFlow.QUARTUS -> "quartus" to "design.${build.board.quartusProgrammingExtension()}"
        FpgaFlow.ISE -> "ise" to if (build.board.isMicrolabtleCpld()) "design.jed" else "design.bit"
        else -> build.flow.name.lowercase() to "design.bit"
    }
    val result = job.result as? CompanionJsonObject ?: throw HeadlessFpgaError("artifact", "Missing FPGA build result.")
    if (!result.boolean("synthesized") || !result.boolean("placedAndRouted") || !result.boolean("bitstreamGenerated") ||
        result.boolean("programmed") || result.boolean("hardwareVerified") || result.string("flow") != flow ||
        result.string("top") != build.topLevelName) {
        throw HeadlessFpgaError("artifact", "The FPGA result does not match the requested bitstream build.")
    }
    val artifactLimit = if (job.operation == "fpga-synthesize") 2 * 1024 * 1024 else FPGA_VENDOR_ARTIFACT_MAX_BYTES
    val values = result.array("artifacts")
    val artifacts = if (values.any { (it as? CompanionJsonObject)?.fields?.get("transport") == CompanionJsonString("binary") }) {
        if (client == null || !binarySofRequested || !build.producesQuartusSof || bitstream != "design.sof" || values.size != 1) {
            throw HeadlessFpgaError("artifact", "The build did not request a binary Quartus SOF.")
        }
        listOf(client.downloadQuartusSof(job, values.single(), build.board.device.part))
    } else values.map { job.artifact(it, artifactLimit) }
    if (artifacts.map { it.name }.distinct().size != artifacts.size || artifacts.none { it.name == bitstream && it.size > 0 }) {
        throw HeadlessFpgaError("artifact", "Missing or duplicate FPGA build artifacts.")
    }
    return artifacts
}

private fun String.singleLine(): String = map { if (it.isISOControl()) ' ' else it }.joinToString("")
