package dev.logisim.mobile.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompanionTclClientTest {
    private val source = TclSourceFile("component.tcl", "expr 42")
    private val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8080"), CompanionToken.parse("a".repeat(32)))
    private val id = "b".repeat(32)
    private val capabilities = """{"protocolVersion":1,"operations":[{"name":"tcl-run","allowed":true,"available":true,"missingTools":[],"toolAlternatives":[],"requiresConfirmation":false,"description":"safe Tcl"},{"name":"tcl-step","allowed":true,"available":true,"missingTools":[],"toolAlternatives":[],"requiresConfirmation":false,"description":"safe Tcl"}],"limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":8388608,"timeoutSeconds":30,"processMemoryBytes":536870912,"memoryEnforcement":"rlimit","osSandbox":false}}"""
    private fun job(operation: String, result: String = "null", state: String = "succeeded") =
        """{"jobId":"$id","operation":"$operation","state":"$state","result":$result,"error":null,"logs":[]}"""
    private fun consoleResult(hash: String = source.sha256) =
        """{"mode":"safe-tcl-v1","result":"42","stdout":"olá\n","stderr":"","sourceSha256":"$hash"}"""

    private class Transport(val response: (CompanionHttpRequest) -> String) : CompanionTransport {
        val requests = mutableListOf<CompanionHttpRequest>()
        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            requests += request
            return CompanionHttpResponse(200, response(request).encodeToByteArray(), "application/json")
        }
    }

    @Test
    fun explicitConsoleSendsOnlyTheFixedOperationAndApprovedSourceBytes() = runBlocking<Unit> {
        val transport = Transport { if (it.url.endsWith("capabilities")) capabilities else job("tcl-run", consoleResult()) }
        val client = CompanionTclClient(CompanionClient(connection, transport), "Mac local")
        assertTrue(transport.requests.isEmpty())
        val result = client.runConsole(TclConsoleRequest("request1", source))
        assertEquals("42", result.result)
        assertEquals("olá\n", result.stdout)
        val submitted = CompanionJsonCodec.parseObject(transport.requests.last().body!!.decodeToString())
        assertEquals("tcl-run", submitted.string("operation"))
        assertEquals("component.tcl", submitted.obj("parameters").string("script"))
        assertEquals(source.sha256, (submitted.array("inputs").single() as CompanionJsonObject).string("sha256"))
        assertEquals("request1", submitted.string("requestId"))
    }

    @Test
    fun timedConsoleSubmitsTheSameSourceAndLimitsOnlyWithAnIsolatedPlan() = runBlocking<Unit> {
        val advertised = capabilities.replace("\"tcl-run\"", "\"tcl-timed-run\"")
            .replaceFirst("\"requiresConfirmation\":false", "\"requiresConfirmation\":true")
        val plan = """{"planToken":"reviewed-plan","expiresInSeconds":30,"operation":"tcl-timed-run","parameters":{"script":"component.tcl","timeoutMillis":5000},"inputs":[{"name":"component.tcl","sha256":"${source.sha256}","size":7}],"requiresConfirmation":true,"osSandbox":"macos-seatbelt-v1"}"""
        val request = TclConsoleRequest("timed1", source)
        fun transportFor(response: String) = Transport {
            when {
                it.url.endsWith("/capabilities") -> advertised
                it.url.endsWith("/plans") -> response
                else -> job("tcl-timed-run", consoleResult().replace("safe-tcl-v1", "timed-tcl-v1"))
            }
        }
        val transport = transportFor(plan)
        assertEquals("42", CompanionTclClient(CompanionClient(connection, transport), "Mac local").runApprovedTimedConsole(request).result)
        assertEquals(listOf("capabilities", "plans", "jobs"), transport.requests.map { it.url.substringAfterLast('/') })
        val planned = CompanionJsonCodec.parseObject(transport.requests[1].body!!.decodeToString())
        val submitted = CompanionJsonCodec.parseObject(transport.requests[2].body!!.decodeToString())
        assertEquals(planned.fields, submitted.fields - setOf("planToken", "requestId"))
        assertEquals("reviewed-plan", submitted.string("planToken"))

        for (invalid in listOf(plan.replace(",\"osSandbox\":\"macos-seatbelt-v1\"", ""),
            plan.replace("\"requiresConfirmation\":true", "\"requiresConfirmation\":false"))) {
            val refused = transportFor(invalid)
            assertFailsWith<IllegalStateException> {
                CompanionTclClient(CompanionClient(connection, refused), "Mac local").runApprovedTimedConsole(request)
            }
            assertEquals(listOf("capabilities", "plans"), refused.requests.map { it.url.substringAfterLast('/') })
        }
    }

    @Test
    fun approvedTkConsoleDecodesOnlyTheBoundedPrivatePreview() = runBlocking<Unit> {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val preview = CompanionInput.fromBytes("tk-window.png", png)
        val artifact = CompanionJsonCodec.write(CompanionJsonObject(preview.envelope().fields + ("size" to CompanionJsonNumber(preview.size.toLong()))))
        val advertised = capabilities.replace("\"tcl-run\"", "\"tcl-tk-run\"")
            .replaceFirst("\"requiresConfirmation\":false", "\"requiresConfirmation\":true")
        val plan = """{"planToken":"reviewed-tk","expiresInSeconds":30,"operation":"tcl-tk-run","parameters":{"script":"component.tcl","timeoutMillis":5000},"inputs":[{"name":"component.tcl","sha256":"${source.sha256}","size":7}],"requiresConfirmation":true,"osSandbox":"linux-private-xvfb-v1"}"""
        fun clientFor(previewJson: String) = CompanionTclClient(CompanionClient(connection, Transport {
            when {
                it.url.endsWith("/capabilities") -> advertised
                it.url.endsWith("/plans") -> plan
                else -> job("tcl-tk-run", consoleResult().dropLast(1).replace("safe-tcl-v1", "tk-console-v1") +
                    ",\"windowPreview\":$previewJson}")
            }
        }), "Private Linux display")
        val request = TclConsoleRequest("tk1", source)
        val result = clientFor(artifact).runApprovedTkConsole(request)
        assertEquals(640, result.windowPreview?.width)
        assertEquals(480, result.windowPreview?.height)
        assertTrue(result.windowPreview?.bytes?.contentEquals(png) == true)
        assertFailsWith<CompanionProtocolException> {
            clientFor(artifact.replace(preview.sha256, "0".repeat(64))).runApprovedTkConsole(request)
        }
        assertFailsWith<IllegalArgumentException> {
            clientFor(artifact.replace("tk-window.png", "other.png")).runApprovedTkConsole(request)
        }
    }

    @Test
    fun approvedLiveTkSendsTwoTypedInputsToOneJobAndRejectsLateInput() = runBlocking<Unit> {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val preview = CompanionInput.fromBytes("tk-window.png", png)
        val artifact = CompanionJsonCodec.write(CompanionJsonObject(preview.envelope().fields + ("size" to CompanionJsonNumber(preview.size.toLong()))))
        val advertised = capabilities.replace("\"tcl-run\"", "\"tcl-tk-live\"")
            .replaceFirst("\"requiresConfirmation\":false", "\"requiresConfirmation\":true")
        val plan = """{"planToken":"reviewed-live","expiresInSeconds":30,"operation":"tcl-tk-live","parameters":{"script":"component.tcl","timeoutMillis":20000},"inputs":[{"name":"component.tcl","sha256":"${source.sha256}","size":7}],"requiresConfirmation":true,"osSandbox":"linux-private-xvfb-v1"}"""
        val events = mutableListOf<CompanionJsonObject>()
        val transport = Transport { call ->
            when {
                call.url.endsWith("/capabilities") -> advertised
                call.url.endsWith("/plans") -> plan
                call.url.endsWith("/jobs") -> job("tcl-tk-live", state = "queued")
                call.url.endsWith("/$id/tk-input") -> {
                    val event = CompanionJsonCodec.parseObject(call.body!!.decodeToString())
                    events += event
                    """{"sequence":${event.long("sequence")}}"""
                }
                else -> {
                    val result = consoleResult().dropLast(1).replace("safe-tcl-v1", "tk-live-v1") +
                        ",\"frame\":{\"sequence\":${events.size},\"windowPreview\":$artifact}}"
                    job("tcl-tk-live", result, if (events.size < 2) "running" else "succeeded")
                }
            }
        }
        val frames = Channel<TclTkFrame>(capacity = 3)
        val client = CompanionTclClient(CompanionClient(connection, transport), "Private Linux display")
        withTimeout(5_000) {
            val execution = async { client.runApprovedTkLive(TclConsoleRequest("live1", source)) { frames.trySend(it).getOrThrow() } }
            frames.receive().send(TclTkInput.Tap(19, 28))
            frames.receive().send(TclTkInput.Key('a'))
            val final = frames.receive()
            assertEquals(2, final.sequence)
            assertEquals("42", execution.await().result)
            assertEquals(listOf(1L, 2L), events.map { it.long("sequence") })
            assertEquals(listOf("tap", "key"), events.map { it.string("type") })
            assertEquals(19L, events.first().long("x"))
            assertEquals("a", events.last().string("key"))
            assertFailsWith<IllegalStateException> { final.send(TclTkInput.Control.Return) }
            assertEquals(2, events.size)
        }
    }

    @Test
    fun changedSourceOrIncompatibleModeCannotPublishResults() = runBlocking<Unit> {
        for (result in listOf(consoleResult("0".repeat(64)), consoleResult().replace("safe-tcl-v1", "unrestricted"))) {
            val transport = Transport { if (it.url.endsWith("capabilities")) capabilities else job("tcl-run", result) }
            val client = CompanionTclClient(CompanionClient(connection, transport), "Mac local")
            assertFailsWith<IllegalArgumentException> { client.runConsole(TclConsoleRequest("request1", source)) }
        }
    }

    @Test
    fun stepChecksOutputWidthsAndMapsCanonicalUnknownBits() = runBlocking<Unit> {
        val face = TclInterface(listOf(TclSignalPort("sysclk_i", PortDirection.INPUT, 1), TclSignalPort("out_o", PortDirection.OUTPUT, 4)))
        val request = TclStepRequest("request2", source, face, listOf(TclInputFrame(mapOf("sysclk_i" to LogicVector.unknown(1)))))
        val result = """{"mode":"safe-replay-v1","steps":[{"forced":false,"requestedTick":true,"outputs":{"out_o":"?101"}}],"outputs":{"out_o":"?101"},"delay":1,"stdout":"","stderr":"","sourceSha256":"${source.sha256}"}"""
        val transport = Transport { if (it.url.endsWith("capabilities")) capabilities else job("tcl-step", result) }
        val client = CompanionTclClient(CompanionClient(connection, transport), "Mac local")
        val reply = client.step(request)
        assertEquals("?101", reply.outputs.getValue("out_o").display)
        assertTrue(reply.steps.single().requestedTick)
        val wire = CompanionJsonCodec.parseObject(transport.requests.last().body!!.decodeToString())
        assertEquals("U", (wire.obj("parameters").array("frames").single() as CompanionJsonObject).string("sysclk_i"))
        val badTransport = Transport { if (it.url.endsWith("capabilities")) capabilities else job("tcl-step", result.replace("?101", "101")) }
        assertFailsWith<IllegalArgumentException> {
            CompanionTclClient(CompanionClient(connection, badTransport), "Mac local").step(request)
        }
    }

    @Test
    fun unavailableHostCapabilityPreventsSubmission() = runBlocking<Unit> {
        val transport = Transport { capabilities.replace("\"allowed\":true", "\"allowed\":false") }
        assertFailsWith<IllegalStateException> {
            CompanionTclClient(CompanionClient(connection, transport), "Mac local").runConsole(TclConsoleRequest("request1", source))
        }
        assertEquals(1, transport.requests.size)
        assertEquals("GET", transport.requests.single().method)
    }

    @Test
    fun cancellationDeletesTheActiveRemoteJobAndRejectsLateResults() = runBlocking<Unit> {
        val submitted = CompletableDeferred<Unit>()
        var cancelled = false
        val transport = Transport { request -> when {
            request.url.endsWith("capabilities") -> capabilities
            request.method == "POST" -> { submitted.complete(Unit); job("tcl-run", state = "running") }
            request.method == "DELETE" -> { cancelled = true; job("tcl-run", state = "cancelled") }
            else -> job("tcl-run", state = if (cancelled) "cancelled" else "running")
        } }
        val client = CompanionTclClient(CompanionClient(connection, transport), "Mac local")
        val execution = async { client.runConsole(TclConsoleRequest("request3", source)) }
        submitted.await()
        client.cancel("request3")
        assertFailsWith<CancellationException> { execution.await() }
        assertTrue(cancelled)
        assertTrue(transport.requests.any { it.method == "DELETE" && it.url.endsWith("/jobs/$id") })
    }
}
