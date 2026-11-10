package dev.logisim.mobile.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionTelnetClientTest {
    private val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8080"), CompanionToken.parse("a".repeat(32)))
    private val session = "s".repeat(32)
    private val jobId = "b".repeat(32)
    private val capabilities = """{"protocolVersion":1,"operations":[{"name":"telnet.start","allowed":true,"available":true,"missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"listener"}],"limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":2097152,"timeoutSeconds":30,"processMemoryBytes":2147483648,"memoryEnforcement":"supervised-process-group","osSandbox":false}}"""
    private fun status(id: String = session, link: String = "listening", extra: String = "") =
        """{"sessionId":"$id","link":"$link","localPort":8000,"bindAddress":"127.0.0.1","telnetMode":false,"peerGeneration":0,"droppedInputBytes":0,"droppedOutputBytes":0,"leaseSeconds":300$extra}"""
    private fun job(operation: String, result: String = "null", state: String = "succeeded", error: String = "null") =
        """{"jobId":"$jobId","operation":"$operation","state":"$state","result":$result,"error":$error,"logs":[]}"""

    private class Transport(val respond: (CompanionHttpRequest) -> String) : CompanionTransport {
        val requests = mutableListOf<CompanionHttpRequest>()
        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            requests += request
            return CompanionHttpResponse(200, respond(request).encodeToByteArray(), "application/json")
        }
    }

    private fun body(request: CompanionHttpRequest) = CompanionJsonCodec.parseObject(request.body!!.decodeToString())
    private fun startTransport(confirm: Boolean = true, result: () -> String = { status() }) = Transport { request ->
        when {
            request.url.endsWith("capabilities") -> capabilities
            request.url.endsWith("plans") -> {
                val wire = body(request)
                CompanionJsonCodec.write(CompanionJsonObject(mapOf(
                    "operation" to CompanionJsonString("telnet.start"), "parameters" to wire.obj("parameters"),
                    "inputs" to CompanionJsonArray(emptyList()), "requiresConfirmation" to CompanionJsonBoolean(confirm),
                    "planToken" to CompanionJsonString("synthetic-plan"), "expiresInSeconds" to CompanionJsonNumber(60L),
                )))
            }
            else -> job("telnet.start", result())
        }
    }

    @Test
    fun preparingDoesNotSubmitAndConfirmationReusesTheSameStartIdentity() = runBlocking<Unit> {
        val transport = startTransport()
        val client = CompanionTelnetClient(CompanionClient(connection, transport))
        assertTrue(transport.requests.isEmpty())
        val pending = client.prepareStart(TelnetListenConfiguration(8000), bufferSize = 64, requestId = "start-1")
        assertEquals(listOf("GET", "POST"), transport.requests.map { it.method })
        assertTrue(transport.requests.last().url.endsWith("/plans"))
        assertFalse(pending.plan.parameters.boolean("allowLan"))
        assertEquals(TelnetLinkState.Listening, client.confirmStart(pending).link)
        client.confirmStart(pending)
        val submissions = transport.requests.filter { it.url.endsWith("/jobs") }
        assertEquals(2, submissions.size)
        submissions.forEach {
            val wire = body(it)
            assertEquals("start-1", wire.string("requestId"))
            assertEquals("start-1", wire.obj("parameters").string("requestId"))
            assertEquals("synthetic-plan", wire.string("planToken"))
            assertEquals(64L, wire.obj("parameters").long("bufferSize"))
        }
        assertEquals(CompanionJsonCodec.write(body(submissions[0])), CompanionJsonCodec.write(body(submissions[1])))
    }

    @Test
    fun pollReturnsApplicationByteEventsWithoutCreatingRetryReceipts() = runBlocking<Unit> {
        val transport = Transport { job("telnet.poll", status(link = "connected", extra = ",\"pollId\":1,\"bytes\":[0,128,255]")) }
        val client = CompanionTelnetClient(CompanionClient(connection, transport))
        val batch = client.poll(session, 3)
        assertEquals(listOf(TelnetLinkChanged(TelnetLinkState.Connected), TelnetBytesReceived(listOf(0, 128, 255))), batch.events(TelnetState()))
        val wire = body(transport.requests.single())
        assertFalse("requestId" in wire.fields)
        assertEquals(3L, wire.obj("parameters").long("maximumBytes"))
        assertEquals(1L, wire.obj("parameters").long("pollId"))
    }

    @Test
    fun outputIsAcknowledgedOnlyForTheExactAcceptedPacket() = runBlocking<Unit> {
        val packet = TelnetPacket(4_294_967_297L, listOf(1, 255))
        val transport = Transport { job("telnet.send", status(link = "connected", extra = ",\"acceptedPacketId\":4294967297,\"duplicate\":false")) }
        val client = CompanionTelnetClient(CompanionClient(connection, transport))
        assertEquals(TelnetOutputAccepted(packet.id), client.send(session, packet))
        val wire = body(transport.requests.single())
        assertFalse("requestId" in wire.fields)
        assertEquals(packet.id, wire.obj("parameters").long("packetId"))
        val wrong = Transport { job("telnet.send", status(link = "connected", extra = ",\"acceptedPacketId\":2,\"duplicate\":false")) }
        assertEquals("invalid-telnet", assertFailsWith<CompanionProtocolException> {
            CompanionTelnetClient(CompanionClient(connection, wrong)).send(session, packet)
        }.code)
        assertFailsWith<IllegalArgumentException> { client.send(session, TelnetPacket(0, listOf(1))) }
        assertFailsWith<IllegalArgumentException> { client.send(session, TelnetPacket(2, listOf(256))) }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun foreignSessionsInvalidBytesAndOversizedPollsCannotPublishEvents() = runBlocking<Unit> {
        for (result in listOf(
            status(id = "x".repeat(32), extra = ",\"pollId\":1,\"bytes\":[1]"),
            status(extra = ",\"pollId\":1,\"bytes\":[256]"),
            status(extra = ",\"pollId\":2,\"bytes\":[1]"),
            status(extra = ",\"pollId\":1,\"bytes\":[1,2]"),
            status(link = "unknown", extra = ",\"pollId\":1,\"bytes\":[1]"),
        )) {
            val transport = Transport { job("telnet.poll", result) }
            assertEquals("invalid-telnet", assertFailsWith<CompanionProtocolException> {
                CompanionTelnetClient(CompanionClient(connection, transport)).poll(session, 1)
            }.code)
        }
    }

    @Test
    fun serverRejectionPreservesTheErrorCodeAndCannotAcknowledgeOutput() = runBlocking<Unit> {
        val transport = Transport { job("telnet.send", state = "failed", error = """{"code":"buffer-full","message":"queue full","details":{"retry":true}}""") }
        val error = assertFailsWith<CompanionProtocolException> {
            CompanionTelnetClient(CompanionClient(connection, transport)).send(session, TelnetPacket(1, listOf(42)))
        }
        assertEquals("buffer-full", error.code)
        assertTrue((error.details as CompanionJsonObject).boolean("retry"))
    }

    @Test
    fun unsafeOrUnconfirmedStartPlansCannotSubmitAListenerJob() = runBlocking<Unit> {
        val denied = Transport { capabilities.replace("\"allowed\":true", "\"allowed\":false") }
        assertEquals("telnet-unavailable", assertFailsWith<CompanionProtocolException> {
            CompanionTelnetClient(CompanionClient(connection, denied)).prepareStart(TelnetListenConfiguration(8000), requestId = "start-1")
        }.code)
        assertEquals(1, denied.requests.size)
        val unconfirmed = startTransport(confirm = false)
        assertEquals("invalid-plan", assertFailsWith<CompanionProtocolException> {
            CompanionTelnetClient(CompanionClient(connection, unconfirmed)).prepareStart(TelnetListenConfiguration(8000), requestId = "start-1")
        }.code)
        assertTrue(unconfirmed.requests.none { it.url.endsWith("/jobs") })
    }

    @Test
    fun stopRequiresTheRequestedSessionAndStoppedState() = runBlocking<Unit> {
        val transport = Transport { job("telnet.stop", """{"sessionId":"$session","link":"stopped","alreadyStopped":false}""") }
        assertEquals(TelnetLinkChanged(TelnetLinkState.Stopped), CompanionTelnetClient(CompanionClient(connection, transport)).stop(session))
        val wrong = Transport { job("telnet.stop", """{"sessionId":"$session","link":"listening","alreadyStopped":false}""") }
        assertFailsWith<CompanionProtocolException> { CompanionTelnetClient(CompanionClient(connection, wrong)).stop(session) }
    }

    @Test
    fun cancellationStopsWaitingAndRequestsCancellationOfTheRemoteJob() = runBlocking<Unit> {
        val waiting = CompletableDeferred<Unit>()
        val transport = Transport { request ->
            when (request.method) {
                "DELETE" -> job("telnet.poll", state = "cancelled")
                "GET" -> { waiting.complete(Unit); job("telnet.poll", state = "running") }
                else -> job("telnet.poll", state = "queued")
            }
        }
        val client = CompanionTelnetClient(CompanionClient(connection, transport), pollDelayMillis = 1)
        val execution = async { client.poll(session) }
        waiting.await()
        execution.cancelAndJoin()
        assertTrue(transport.requests.any { it.method == "DELETE" && it.url.endsWith("/jobs/$jobId") })
    }
    @Test
    fun cancellingAConfirmedStartRecoversItsOriginalJobAndStopsTheAcquiredListener() = runBlocking<Unit> {
        val waiting = CompletableDeferred<Unit>()
        val template = startTransport()
        var starts = 0
        val transport = Transport { request ->
            when {
                request.method == "GET" && request.url.endsWith("/jobs/$jobId") -> {
                    waiting.complete(Unit)
                    job("telnet.start", state = "running")
                }
                request.method == "POST" && request.url.endsWith("/jobs") -> {
                    if (body(request).string("operation") == "telnet.start") {
                        starts++
                        if (starts == 1) job("telnet.start", state = "running") else job("telnet.start", status())
                    } else job("telnet.stop", """{"sessionId":"$session","link":"stopped","alreadyStopped":false}""")
                }
                else -> template.respond(request)
            }
        }
        val client = CompanionTelnetClient(CompanionClient(connection, transport), pollDelayMillis = 1)
        val pending = client.prepareStart(TelnetListenConfiguration(8000), requestId = "cancelled-start")
        val execution = async { client.confirmStart(pending) }
        waiting.await()
        execution.cancelAndJoin()
        assertEquals(2, starts)
        val submissions = transport.requests.filter { it.method == "POST" && it.url.endsWith("/jobs") }.map(::body)
        assertEquals(listOf("telnet.start", "telnet.start", "telnet.stop"), submissions.map { it.string("operation") })
        assertEquals(listOf("cancelled-start", "cancelled-start"), submissions.take(2).map { it.string("requestId") })
        assertTrue(transport.requests.none { it.method == "DELETE" })
    }

    @Test
    fun aLostPollResponseReusesItsSequenceAndCapacityUntilTheResponseIsValidated() = runBlocking<Unit> {
        var calls = 0
        val transport = Transport { request ->
            calls++
            if (calls == 1) throw CompanionTransportException("connection-lost")
            val id = body(request).obj("parameters").long("pollId")
            val bytes = if (id == 1L) "[65,255]" else "[]"
            job("telnet.poll", status(extra = ",\"pollId\":$id,\"bytes\":$bytes"))
        }
        val client = CompanionTelnetClient(CompanionClient(connection, transport))
        assertFailsWith<CompanionTransportException> { client.poll(session, 2) }
        assertEquals(listOf(65, 255), client.poll(session, 0).bytes)
        assertTrue(client.poll(session, 0).bytes.isEmpty())
        val parameters = transport.requests.map { body(it).obj("parameters") }
        assertEquals(listOf(1L, 1L, 2L), parameters.map { it.long("pollId") })
        assertEquals(listOf(2L, 2L, 0L), parameters.map { it.long("maximumBytes") })
        assertTrue(transport.requests.all { "requestId" !in body(it).fields })
    }

    @Test
    fun anUnfinishedStartIsCancelledIfBoundedRecoveryCannotObtainItsListener() = runBlocking<Unit> {
        val template = startTransport()
        var cancelled = false
        val transport = Transport { request ->
            when {
                request.method == "DELETE" -> { cancelled = true; job("telnet.start", state = "cancelled") }
                request.method == "GET" && request.url.endsWith("/jobs/$jobId") -> job("telnet.start", state = "running")
                request.method == "POST" && request.url.endsWith("/jobs") -> job("telnet.start", state = "queued")
                else -> template.respond(request)
            }
        }
        val client = CompanionTelnetClient(CompanionClient(connection, transport), operationTimeoutMillis = 20, pollDelayMillis = 1)
        val pending = client.prepareStart(TelnetListenConfiguration(8000), requestId = "queued-start")
        assertFailsWith<TimeoutCancellationException> { client.confirmStart(pending) }
        assertTrue(cancelled)
    }

}
