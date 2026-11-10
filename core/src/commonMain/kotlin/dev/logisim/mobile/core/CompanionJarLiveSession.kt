package dev.logisim.mobile.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val liveJarOperation = "jar-live"
private const val maxEditorRevision = 9_007_199_254_740_991L

fun jarLiveRequest(project: ByteArray, library: ByteArray, libraryClass: String, maxFrames: Int = 16,
                   instanceTrace: Boolean = true, driverTrace: Boolean = false): CompanionJobRequest {
    require(maxFrames in 1..256 && (!driverTrace || instanceTrace))
    return CompanionJobRequest(liveJarOperation, listOf(
        CompanionInput.fromBytes("project.circ", project),
        CompanionInput.fromBytes("library.jar", library),
    ), buildMap {
        put("libraryClass", CompanionJsonString(libraryClass))
        put("maxFrames", CompanionJsonNumber(maxFrames.toLong()))
        put("instanceTrace", CompanionJsonBoolean(instanceTrace))
        if (driverTrace) put("driverTrace", CompanionJsonBoolean(true))
    })
}

class JarLiveSample internal constructor(
    val jobId: String,
    val sequence: Long,
    val inputRevision: Long,
    val outputs: Map<String, LogicVector>,
    val trace: JarInstanceTrace?,
    internal val inputs: Map<String, LogicVector>,
    internal val sessionIdentity: Any,
)

/** The job ID is the session epoch. Apply a sample only to the editor revision that requested it. */
class CompanionJarLiveSession private constructor(
    private val client: CompanionClient,
    val jobId: String,
    private val maxFrames: Long,
    private val instanceTrace: Boolean,
    private val driverTrace: Boolean,
    private val libraryClass: String,
    private val projectSha: String,
    private val librarySha: String,
) {
    private val stepLock = Mutex()
    private val closed = MutableStateFlow(false)
    private val sessionIdentity = Any()
    private var sequence = 0L
    private var lastRevision = -1L
    private var pending: Pair<Long, Map<String, LogicVector>>? = null

    suspend fun advance(revision: Long, inputs: Map<String, LogicVector>): JarLiveSample = stepLock.withLock {
        check(!closed.value)
        require(revision in 0..maxEditorRevision && revision > lastRevision && sequence < maxFrames)
        val next = revision to inputs.toMap()
        require(pending == null || pending == next) { "Retry the same pending JAR input or cancel its worker." }
        pending = next
        withTimeout(8_000) {
            val ready = await { it.live != null }.live ?: error("Missing JAR input interface.")
            require(ready.jarPortWidths("input") == next.second.mapValues { it.value.width })
            client.sendJobInput(jobId, CompanionJsonObject(mapOf(
                "sequence" to CompanionJsonNumber(sequence),
                "revision" to CompanionJsonNumber(revision),
                "inputs" to CompanionJsonObject(next.second.mapValues { CompanionJsonString(it.value.display) }),
            )), sequence)
            val receipt = await { job -> job.live?.let { live ->
                if ("sequence" !in live.fields) false else {
                    val seen = live.long("sequence")
                    require(seen <= sequence) { "The live JAR worker skipped an input sequence." }
                    seen == sequence
                }
            } == true }.live ?: error("Missing JAR step receipt.")
            require(receipt.long("revision") == revision)
            val outputWidths = receipt.jarPortWidths("output")
            val signals = receipt.obj("outputs").fields
            require(signals.keys == outputWidths.keys)
            val outputs = signals.mapValues { (pin, value) -> value.readJarSignal(outputWidths.getValue(pin)) }
            val trace = if (instanceTrace) receipt.obj("instanceTrace").readJarInstanceTrace(1, expectDriverTrace = driverTrace) else null
            check(!closed.value)
            val sample = JarLiveSample(jobId, sequence, revision, outputs, trace, next.second, sessionIdentity)
            sequence++
            lastRevision = revision
            pending = null
            sample
        }
    }

    fun resolveEditorGeometry(xml: String, sample: JarLiveSample, baseRegistry: ComponentRegistry): JarEditorGeometry {
        check(!closed.value && sample.jobId == jobId && sample.sessionIdentity === sessionIdentity)
        require(companionSha256(xml.encodeToByteArray()) == projectSha) {
            "The editor project differs from the approved JAR project."
        }
        val trace = requireNotNull(sample.trace) { "The approved JAR worker did not report instance geometry." }
        return resolveJarEditorGeometry(xml, libraryClass, trace, baseRegistry, sessionIdentity) { !closed.value }
    }

    suspend fun advanceEditorFrame(geometry: JarEditorGeometry, project: CircuitProject,
                                            root: SimulationState, revision: Long): JarLiveSample {
        check(geometry.isOwnedBy(sessionIdentity))
        val frame = geometry.captureInputFrame(project, root, revision)
        return advance(frame.revision, frame.values)
    }

    /** Renew an idle worker while the editor still owns this session. */
    suspend fun renew() {
        readJob().also { if (it.state.terminal) throw retire(it) }
    }

    suspend fun finish(): CompanionJob = stepLock.withLock {
        check(!closed.value && pending == null && sequence > 0)
        withTimeout(8_000) {
            val current = readJob()
            if (current.state == CompanionJobState.SUCCEEDED) return@withTimeout complete(current)
            if (current.state.terminal) throw retire(current)
            try {
                client.sendJobInput(jobId, CompanionJsonObject(mapOf("sequence" to CompanionJsonNumber(sequence),
                    "finish" to CompanionJsonBoolean(true))), sequence)
            } catch (failure: CompanionProtocolException) {
                if (failure.code != "live-input-unavailable") throw failure
                val finished = readJob()
                if (finished.state != CompanionJobState.SUCCEEDED) throw failure
                return@withTimeout complete(finished)
            }
            complete(await { it.state == CompanionJobState.SUCCEEDED })
        }
    }

    suspend fun cancel() {
        closed.value = true
        client.cancel(jobId)
    }

    private fun complete(job: CompanionJob): CompanionJob {
        check(!closed.value)
        val result = job.result as? CompanionJsonObject ?: error("Missing JAR execution result.")
        require(result.string("libraryClass") == libraryClass && result.string("librarySha256") == librarySha)
        require(job.artifact(result.obj("project")).sha256 == projectSha)
        closed.value = true
        return job
    }

    private suspend fun readJob(): CompanionJob {
        check(!closed.value)
        val job = client.status(jobId)
        require(job.jobId == jobId && job.operation == liveJarOperation)
        check(!closed.value)
        return job
    }

    private suspend fun await(complete: (CompanionJob) -> Boolean): CompanionJob {
        while (true) {
            val job = readJob()
            if (complete(job)) return job
            if (job.state.terminal) throw retire(job)
            delay(100)
        }
    }

    private fun retire(job: CompanionJob): CompanionProtocolException {
        closed.value = true
        return CompanionProtocolException(job.error?.code ?: "jar-live-ended",
            job.error?.message ?: "The live JAR worker has ended.")
    }

    companion object {
        suspend fun start(client: CompanionClient, request: CompanionJobRequest, plan: CompanionPlan,
                          approval: CompanionApproval): CompanionJarLiveSession {
            require(request.operation == liveJarOperation && plan.operation == liveJarOperation &&
                plan.requiresConfirmation && !plan.osSandbox.isNullOrBlank())
            val parameters = CompanionJsonObject(request.parameters)
            val frames = parameters.long("maxFrames").also { require(it in 1..256) }
            val trace = parameters.fields["instanceTrace"]?.let { parameters.boolean("instanceTrace") } ?: false
            val drivers = parameters.fields["driverTrace"]?.let { parameters.boolean("driverTrace") } ?: false
            require(!drivers || trace)
            val uploads = request.inputs.associate { it.name to it.sha256 }
            require(uploads.keys == setOf("project.circ", "library.jar"))
            val className = parameters.string("libraryClass")
            val job = client.submit(request, approval)
            require(job.operation == liveJarOperation)
            return CompanionJarLiveSession(client, job.jobId, frames, trace, drivers, className,
                uploads.getValue("project.circ"), uploads.getValue("library.jar"))
        }
    }
}

private fun CompanionJsonObject.jarPortWidths(direction: String): Map<String, Int> {
    val ports = array("ports").map { it as? CompanionJsonObject ?: error("Invalid JAR pin.") }
    require(ports.map { it.string("name") }.distinct().size == ports.size)
    return ports.filter { it.string("direction") == direction }.associate {
        it.string("name") to it.long("width").also { width -> require(width in 1..64) }.toInt()
    }
}
