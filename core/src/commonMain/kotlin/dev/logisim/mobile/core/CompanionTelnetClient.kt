package dev.logisim.mobile.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A reviewed start request can be retried with its original ID after a transport failure. */
class CompanionTelnetStartPlan internal constructor(
    val plan: CompanionPlan,
    val configuration: TelnetListenConfiguration,
    val bufferSize: Int,
    val requestId: String,
    internal val request: CompanionJobRequest,
) {
    override fun toString(): String = "CompanionTelnetStartPlan(port=${configuration.port}, requestId=$requestId)"
}

data class CompanionTelnetStatus(
    val sessionId: String,
    val localPort: Int,
    val bindAddress: String,
    val telnetMode: Boolean,
    val link: TelnetLinkState,
    val peerGeneration: Long,
    val droppedInputBytes: Long,
    val droppedOutputBytes: Long,
)

data class CompanionTelnetBatch(val status: CompanionTelnetStatus, val bytes: List<Int>) {
    /** The companion already removes Telnet protocol commands; these are application bytes. */
    fun events(previous: TelnetState): List<ComponentEvent> = buildList {
        if (status.link != previous.link) add(TelnetLinkChanged(status.link))
        if (bytes.isNotEmpty()) add(TelnetBytesReceived(bytes))
    }
}

/** Suspend RPC for explicitly controlled listeners; construction never contacts the host. */
class CompanionTelnetClient(
    private val companion: CompanionClient,
    private val operationTimeoutMillis: Long = 30_000,
    private val pollDelayMillis: Long = 50,
) {
    private class PollCursor(var nextId: Long = 1, var maximumBytes: Int? = null, var exhausted: Boolean = false)
    private val pollMutex = Mutex()
    private val polls = mutableMapOf<String, PollCursor>()

    init {
        require(operationTimeoutMillis in 1..120_000)
        require(pollDelayMillis in 1..5_000)
    }

    suspend fun prepareStart(
        configuration: TelnetListenConfiguration,
        bufferSize: Int = 1024,
        requestId: String,
    ): CompanionTelnetStartPlan {
        require(bufferSize in 1..16_777_216)
        require(!configuration.telnetMode || configuration.maximumOutputBytes >= 6)
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(requestId))
        val capability = companion.capabilities().operations.singleOrNull { it.name == "telnet.start" }
        if (capability?.allowed != true || !capability.available) {
            throw CompanionProtocolException("telnet-unavailable", "The companion has not enabled an available Telnet listener.")
        }
        if (!capability.requiresConfirmation) {
            throw CompanionProtocolException("confirmation-required", "The companion must require a confirmed plan before starting Telnet.")
        }
        val request = CompanionJobRequest("telnet.start", parameters = mapOf(
            "port" to CompanionJsonNumber(configuration.port.toLong()),
            "allowLan" to CompanionJsonBoolean(configuration.allowLan),
            "telnetMode" to CompanionJsonBoolean(configuration.telnetMode),
            "maximumOutputBytes" to CompanionJsonNumber(configuration.maximumOutputBytes.toLong()),
            "bufferSize" to CompanionJsonNumber(bufferSize.toLong()),
            // The listener deduplicates independently of retained job snapshots.
            "requestId" to CompanionJsonString(requestId),
        ))
        val plan = companion.plan(request)
        if (!plan.requiresConfirmation) {
            throw CompanionProtocolException("invalid-plan", "The Telnet plan omitted its required confirmation.")
        }
        return CompanionTelnetStartPlan(plan, configuration, bufferSize, requestId, request)
    }

    /** Invoke from the confirmation action, retaining [pending] until the result is known. */
    suspend fun confirmStart(pending: CompanionTelnetStartPlan): CompanionTelnetStatus {
        var knownJob: String? = null
        try {
            val status = status(execute(pending.request, pending.plan.confirm(), pending.requestId,
                cancelOnAbandon = false, onSubmitted = { knownJob = it }))
            val configuration = pending.configuration
            if (status.telnetMode != configuration.telnetMode ||
                status.bindAddress != (if (configuration.allowLan) "0.0.0.0" else "127.0.0.1") ||
                configuration.port != 0 && status.localPort != configuration.port) {
                withContext(NonCancellable) {
                    runCatching { withTimeout(5_000) { stop(status.sessionId) } }
                }
                throw CompanionProtocolException("invalid-telnet", "The listener does not match the confirmed configuration.")
            }
            return status
        } catch (cancelled: CancellationException) {
            // Recover the same start rather than cancelling a job after it acquired a listener.
            withContext(NonCancellable) {
                val stopped = runCatching {
                    withTimeout(5_000) {
                        val started = status(execute(pending.request, pending.plan.confirm(), pending.requestId,
                            cancelOnAbandon = false, onSubmitted = { knownJob = it }))
                        stop(started.sessionId)
                    }
                }.isSuccess
                val unfinishedJob = knownJob
                if (!stopped && unfinishedJob != null) {
                    runCatching { withTimeout(5_000) { companion.cancel(unfinishedJob) } }
                }
            }
            throw cancelled
        }
    }

    suspend fun poll(sessionId: String, maximumBytes: Int = 4096): CompanionTelnetBatch {
        validateSession(sessionId)
        require(maximumBytes in 0..65_536)
        return pollMutex.withLock {
            val cursor = polls.getOrPut(sessionId) { PollCursor() }
            if (cursor.exhausted) throw CompanionProtocolException("poll-limit", "The listener exhausted its poll sequence.")
            val reservedMaximum = cursor.maximumBytes ?: maximumBytes.also { cursor.maximumBytes = it }
            // A lost response retries this per-session poll; no global job receipt is needed.
            val result = execute(CompanionJobRequest("telnet.poll", parameters = mapOf(
                "sessionId" to CompanionJsonString(sessionId),
                "pollId" to CompanionJsonNumber(cursor.nextId),
                "maximumBytes" to CompanionJsonNumber(reservedMaximum.toLong()),
            )))
            val status = status(result, sessionId)
            if (result.long("pollId") != cursor.nextId) {
                throw CompanionProtocolException("invalid-telnet", "The listener returned a different poll sequence.")
            }
            val bytes = result.array("bytes").map { value ->
                val number = (value as? CompanionJsonNumber)?.value?.toIntOrNull()
                if (number == null || number !in 0..255) {
                    throw CompanionProtocolException("invalid-telnet", "The listener returned an invalid byte.")
                }
                number
            }
            if (bytes.size > reservedMaximum) throw CompanionProtocolException("invalid-telnet", "The listener exceeded the requested byte count.")
            cursor.maximumBytes = null
            if (cursor.nextId == Long.MAX_VALUE) cursor.exhausted = true else cursor.nextId++
            CompanionTelnetBatch(status, bytes)
        }
    }

    suspend fun resizeInput(sessionId: String, bufferSize: Int, resizeId: Long): CompanionTelnetStatus {
        validateSession(sessionId)
        require(bufferSize in 1..16_777_216 && resizeId > 0)
        val result = execute(CompanionJobRequest("telnet.resize", parameters = mapOf(
            "sessionId" to CompanionJsonString(sessionId),
            "resizeId" to CompanionJsonNumber(resizeId),
            "bufferSize" to CompanionJsonNumber(bufferSize.toLong()),
        )))
        if (result.long("resizeId") != resizeId || result.long("bufferSize") != bufferSize.toLong()) {
            throw CompanionProtocolException("invalid-telnet", "The listener acknowledged a different buffer resize.")
        }
        return status(result, sessionId)
    }

    /** A packet leaves circuit state only after this method validates its acceptance ID. */
    suspend fun send(sessionId: String, packet: TelnetPacket): TelnetOutputAccepted {
        validateSession(sessionId)
        require(packet.id > 0 && packet.bytes.size <= 65_536 && packet.bytes.all { it in 0..255 })
        val result = execute(CompanionJobRequest("telnet.send", parameters = mapOf(
            "sessionId" to CompanionJsonString(sessionId),
            "packetId" to CompanionJsonNumber(packet.id),
            "bytes" to CompanionJsonArray(packet.bytes.map { CompanionJsonNumber(it.toLong()) }),
        )))
        status(result, sessionId)
        result.boolean("duplicate")
        if (result.long("acceptedPacketId") != packet.id) {
            throw CompanionProtocolException("invalid-telnet", "The listener acknowledged a different output packet.")
        }
        return TelnetOutputAccepted(packet.id)
    }

    suspend fun stop(sessionId: String): TelnetLinkChanged {
        validateSession(sessionId)
        val result = execute(CompanionJobRequest("telnet.stop", parameters = mapOf("sessionId" to CompanionJsonString(sessionId))))
        if (result.string("sessionId") != sessionId || result.string("link") != "stopped") {
            throw CompanionProtocolException("invalid-telnet", "The listener did not confirm the requested stop.")
        }
        result.boolean("alreadyStopped")
        pollMutex.withLock { polls.remove(sessionId) }
        return TelnetLinkChanged(TelnetLinkState.Stopped)
    }

    private suspend fun execute(
        request: CompanionJobRequest,
        approval: CompanionApproval? = null,
        requestId: String? = null,
        cancelOnAbandon: Boolean = true,
        onSubmitted: (String) -> Unit = {},
    ): CompanionJsonObject {
        var activeJob: String? = null
        var terminal = false
        try {
            return withTimeout(operationTimeoutMillis) {
                // Obtain the job ID even when a cancellation races the short submission.
                var job = withContext(NonCancellable) {
                    withTimeout(minOf(5_000, operationTimeoutMillis)) {
                        companion.submit(request, approval, requestId).also {
                            activeJob = it.jobId
                            onSubmitted(it.jobId)
                        }
                    }
                }
                while (true) {
                    if (job.operation != request.operation) {
                        throw CompanionProtocolException("invalid-telnet", "The companion returned a different operation.")
                    }
                    if (job.state.terminal) break
                    delay(pollDelayMillis)
                    job = companion.status(job.jobId)
                }
                terminal = true
                when (job.state) {
                    CompanionJobState.SUCCEEDED -> job.result as? CompanionJsonObject
                        ?: throw CompanionProtocolException("invalid-telnet", "Expected a Telnet result object.")
                    CompanionJobState.CANCELLED -> throw CancellationException("The Telnet job was cancelled.")
                    else -> throw CompanionProtocolException(job.error?.code ?: "telnet-failed",
                        job.error?.message ?: "The companion Telnet operation failed.", details = job.error?.details ?: CompanionJsonNull)
                }
            }
        } finally {
            val unfinishedJob = activeJob
            if (cancelOnAbandon && !terminal && unfinishedJob != null) withContext(NonCancellable) {
                runCatching { withTimeout(5_000) { companion.cancel(unfinishedJob) } }
            }
        }
    }

    private fun status(result: CompanionJsonObject, expectedSession: String? = null): CompanionTelnetStatus {
        val sessionId = result.string("sessionId")
        validateSession(sessionId)
        val port = result.long("localPort")
        val bindAddress = result.string("bindAddress")
        val peerGeneration = result.long("peerGeneration")
        val droppedInput = result.long("droppedInputBytes")
        val droppedOutput = result.long("droppedOutputBytes")
        val lease = (result.fields["leaseSeconds"] as? CompanionJsonNumber)?.value?.toDoubleOrNull()
        val link = when (result.string("link")) {
            "listening" -> TelnetLinkState.Listening
            "connected" -> TelnetLinkState.Connected
            else -> throw CompanionProtocolException("invalid-telnet", "The listener returned an unknown link state.")
        }
        if (expectedSession != null && sessionId != expectedSession || port !in 1L..65535L ||
            bindAddress !in setOf("127.0.0.1", "0.0.0.0") || peerGeneration < 0 || droppedInput < 0 || droppedOutput < 0 || lease == null || lease <= 0) {
            throw CompanionProtocolException("invalid-telnet", "The listener returned invalid session metadata.")
        }
        return CompanionTelnetStatus(sessionId, port.toInt(), bindAddress, result.boolean("telnetMode"), link,
            peerGeneration, droppedInput, droppedOutput)
    }

    private fun validateSession(sessionId: String) {
        if (!Regex("[A-Za-z0-9_-]{20,64}").matches(sessionId)) {
            throw CompanionProtocolException("invalid-telnet", "The listener session identifier is invalid.")
        }
    }
}
