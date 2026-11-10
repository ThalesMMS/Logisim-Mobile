package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.checkLocalized

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Adapts fixed Tcl operations and checks companion grants before every submission. */
class CompanionTclClient(
    private val companion: CompanionClient,
    override val hostLabel: String,
    override val available: Boolean = true,
) : TclExecutionClient {
    private class Active(var jobId: String? = null, var cancelled: Boolean = false)
    private val mutex = Mutex()
    private val active = mutableMapOf<String, Active>()

    override suspend fun runConsole(request: TclConsoleRequest): TclConsoleReply {
        val result = execute(request.requestId, jobRequest("tcl-run", request.source, request.includes,
            mapOf("script" to CompanionJsonString(request.source.name))))
        return consoleReply(result, request.source, "safe-tcl-v1")
    }

    /** Call after the user explicitly approves running this source with timers. */
    suspend fun runApprovedTimedConsole(request: TclConsoleRequest): TclConsoleReply {
        val result = execute(request.requestId, jobRequest("tcl-timed-run", request.source, request.includes,
            mapOf("script" to CompanionJsonString(request.source.name), "timeoutMillis" to CompanionJsonNumber(5_000))),
            requiredSandbox = "macos-seatbelt-v1")
        return consoleReply(result, request.source, "timed-tcl-v1")
    }

    /** Call after the user approves the source and an offscreen preview from the private Linux display. */
    suspend fun runApprovedTkConsole(request: TclConsoleRequest): TclConsoleReply {
        val result = execute(request.requestId, jobRequest("tcl-tk-run", request.source, request.includes,
            mapOf("script" to CompanionJsonString(request.source.name), "timeoutMillis" to CompanionJsonNumber(5_000))),
            requiredSandbox = "linux-private-xvfb-v1")
        val reply = consoleReply(result, request.source, "tk-console-v1")
        return reply.copy(windowPreview = windowPreview(result.obj("windowPreview")))
    }

    /** Call after the user approves this source and its private display input lease. */
    suspend fun runApprovedTkLive(request: TclConsoleRequest, onFrame: (TclTkFrame) -> Unit): TclConsoleReply {
        var previous = -1L
        var latestImage: EmbeddedImage? = null
        val result = execute(request.requestId, jobRequest("tcl-tk-live", request.source, request.includes,
            mapOf("script" to CompanionJsonString(request.source.name), "timeoutMillis" to CompanionJsonNumber(20_000))),
            requiredSandbox = "linux-private-xvfb-v1", pollDelayMillis = 200) { job ->
            val live = job.result as? CompanionJsonObject ?: return@execute
            checkSource(live, request.source, "tk-live-v1")
            val frame = live.obj("frame")
            val sequence = frame.long("sequence")
            if (sequence == previous) return@execute
            requireLocalized(sequence == previous + 1 && sequence in 0..8) { message(Messages.TclTkPreviewInvalid) }
            val image = windowPreview(frame.obj("windowPreview"))
            latestImage = image
            previous = sequence
            val jobId = job.jobId
            onFrame(TclTkFrame(sequence.toInt(), image) { input ->
                checkLocalized(mutex.withLock { active[request.requestId]?.let { !it.cancelled && it.jobId == jobId } == true }) {
                    message(Messages.TclTkInputUnavailable)
                }
                companion.sendTkInput(jobId, sequence.toInt() + 1, input)
            })
        }
        return consoleReply(result, request.source, "tk-live-v1").copy(windowPreview = latestImage)
    }

    private fun windowPreview(value: CompanionJsonObject): EmbeddedImage {
        val artifact = CompanionArtifact.read(value, EmbeddedImage.MAX_BYTES)
        val png = artifact.bytes
        fun intAt(offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (png[offset + index].toInt() and 255) }
        fun tagAt(offset: Int, tag: String) = png.copyOfRange(offset, offset + 4).contentEquals(tag.encodeToByteArray())
        val idatSize = if (png.size >= 58) intAt(33) else 0
        // The worker emits only these three chunks. Reject ancillary data and unexpected pixel formats before decoding.
        requireLocalized(artifact.name == "tk-window.png" && idatSize > 0 && idatSize == png.size - 57 &&
            intAt(8) == 13 && intAt(16) == 640 && intAt(20) == 480 &&
            png.copyOfRange(24, 29).contentEquals(byteArrayOf(8, 2, 0, 0, 0)) && tagAt(37, "IDAT") &&
            intAt(45 + idatSize) == 0 && tagAt(49 + idatSize, "IEND")) { message(Messages.TclTkPreviewInvalid) }
        return EmbeddedImage.fromBytes(png, "image/png")
    }

    private fun consoleReply(result: CompanionJsonObject, source: TclSourceFile, mode: String): TclConsoleReply {
        checkSource(result, source, mode)
        return TclConsoleReply(result.string("result"), result.string("stdout"), result.string("stderr"), result.string("sourceSha256"))
    }

    override suspend fun step(request: TclStepRequest): TclStepReply {
        val parameters = mapOf("script" to CompanionJsonString(request.source.name),
            "ports" to CompanionJsonArray(request.face.ports.map { port -> CompanionJsonObject(mapOf(
                "name" to CompanionJsonString(port.name), "width" to CompanionJsonNumber(port.width.toLong()),
                "direction" to CompanionJsonString(when (port.direction) {
                    PortDirection.INPUT -> "input"; PortDirection.OUTPUT -> "output"; PortDirection.INOUT -> "inout"
                }))) }),
            "frames" to CompanionJsonArray(request.frames.map { frame -> CompanionJsonObject(frame.inputs.mapValues {
                CompanionJsonString(it.value.display.replace('?', 'U'))
            }) }))
        val result = execute(request.requestId, jobRequest("tcl-step", request.source, request.includes, parameters))
        checkSource(result, request.source, "safe-replay-v1")
        requireLocalized(result.long("delay") == 1L) { message(Messages.TclClientErrorReplyDelay) }
        val steps = result.array("steps").map { value ->
            val step = value as? CompanionJsonObject ?: throw CompanionProtocolException("invalid-tcl", message(Messages.TclClientErrorStepObject))
            TclStepObservation(step.boolean("forced"), step.boolean("requestedTick"), outputs(step.obj("outputs"), request.face))
        }
        requireLocalized(steps.size == request.frames.size) { message(Messages.TclClientErrorReplyHistory) }
        val outputs = outputs(result.obj("outputs"), request.face)
        requireLocalized(steps.last().outputs == outputs) { message(Messages.TclClientErrorReplyOutputs) }
        return TclStepReply(steps, outputs, result.string("stdout"), result.string("stderr"), result.string("sourceSha256"))
    }

    override suspend fun cancel(requestId: String) {
        val jobId = mutex.withLock { active[requestId]?.also { it.cancelled = true }?.jobId }
        if (jobId != null) companion.cancel(jobId)
    }

    private suspend fun execute(requestId: String, request: CompanionJobRequest, requiredSandbox: String? = null,
        pollDelayMillis: Long = 50, onUpdate: (CompanionJob) -> Unit = {}): CompanionJsonObject {
        checkLocalized(available) { message(Messages.TclClientErrorHostRequired) }
        val entry = Active()
        mutex.withLock { checkLocalized(requestId !in active) { message(Messages.TclClientErrorRequestActive, "request" to requestId) }; active[requestId] = entry }
        var finished = false
        try {
            return withTimeout(35_000) {
                val capability = companion.capabilities().operations.singleOrNull { it.name == request.operation }
                checkLocalized(capability?.allowed == true && capability.available) { message(Messages.TclClientErrorHostGrant, "operation" to request.operation) }
                val approval = if (requiredSandbox == null) {
                    checkLocalized(!capability.requiresConfirmation) { message(Messages.TclClientErrorPlanRequired) }
                    null
                } else {
                    checkLocalized(capability.requiresConfirmation) { message(Messages.TclIsolatedSandboxRequired) }
                    val plan = companion.plan(request)
                    checkLocalized(plan.requiresConfirmation && plan.osSandbox == requiredSandbox) {
                        message(Messages.TclIsolatedSandboxRequired)
                    }
                    plan.confirm()
                }
                mutex.withLock { if (entry.cancelled) throw CancellationException("Tcl request cancelled.") }
                // Finish the short submission even if the UI cancels, so its returned job can be cancelled by ID.
                var job = withContext(NonCancellable) { withTimeout(5_000) { companion.submit(request, approval, requestId) } }
                mutex.withLock { entry.jobId = job.jobId }
                while (true) {
                    mutex.withLock { if (entry.cancelled) throw CancellationException("Tcl request cancelled.") }
                    requireLocalized(job.operation == request.operation) { message(Messages.TclClientErrorReplyOperation, "actual" to job.operation, "expected" to request.operation) }
                    if (job.state == CompanionJobState.RUNNING || job.state == CompanionJobState.SUCCEEDED) onUpdate(job)
                    if (job.state.terminal) break
                    delay(pollDelayMillis)
                    job = companion.status(job.jobId)
                }
                finished = true
                when (job.state) {
                    CompanionJobState.SUCCEEDED -> job.result as? CompanionJsonObject
                        ?: throw CompanionProtocolException("invalid-tcl", message(Messages.TclClientErrorResultObject))
                    CompanionJobState.CANCELLED -> throw CancellationException("Tcl request cancelled by companion.")
                    else -> {
                        val error = job.error
                        if (error == null) throw CompanionProtocolException("tcl-failed", message(Messages.TclClientErrorExecutorFailed))
                        throw CompanionProtocolException(error.code, error.message)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val jobId = mutex.withLock { active.remove(requestId)?.jobId }
                if (!finished && jobId != null) runCatching { withTimeout(5_000) { companion.cancel(jobId) } }
            }
        }
    }

    private fun jobRequest(operation: String, source: TclSourceFile, includes: List<TclSourceFile>, parameters: Map<String, CompanionJson>) =
        CompanionJobRequest(operation, (listOf(source) + includes).map { CompanionInput.fromBytes(it.name, it.text.encodeToByteArray()) }, parameters)

    private fun checkSource(result: CompanionJsonObject, source: TclSourceFile, mode: String) {
        requireLocalized(result.string("mode") == mode && result.string("sourceSha256") == source.sha256) { message(Messages.TclClientErrorSourceReply) }
    }

    private fun outputs(raw: CompanionJsonObject, face: TclInterface): Map<String, LogicVector> {
        requireLocalized(raw.fields.keys == face.outputs.map { it.name }.toSet()) { message(Messages.TclClientErrorOutputInterface) }
        return face.outputs.associate { port ->
            val bits = raw.string(port.name)
            requireLocalized(bits.length == port.width && bits.all { it in "01?" }) { message(Messages.TclClientErrorOutputBits, "port" to port.name) }
            var value = 0uL
            var unknown = 0uL
            for (bit in bits) {
                value = (value shl 1) or if (bit == '1') 1uL else 0uL
                unknown = (unknown shl 1) or if (bit == '?') 1uL else 0uL
            }
            port.name to LogicVector.fromMasks(port.width, value, unknown, 0uL)
        }
    }
}
