package dev.logisim.mobile.core

import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompanionTelnetHttpTest {
    @Test
    fun realKmpHttpAndTcpListenerTransferBytesDeduplicatePacketsAndSurviveJobPruning() = isolatedFromForeignPeers(dropFirstPollResponse = true) { fixture ->
        val pending = fixture.client.prepareStart(TelnetListenConfiguration(0), requestId = "real-start")
        val listening = fixture.client.confirmStart(pending)
        assertEquals(TelnetLinkState.Listening, listening.link)
        assertEquals("127.0.0.1", listening.bindAddress)
        Socket("127.0.0.1", listening.localPort).use { peer ->
            peer.soTimeout = 3_000
            peer.getOutputStream().write(byteArrayOf(0, 127, 128.toByte(), 255.toByte(), 10))
            // The server completes this destructive read, but its HTTP response is lost.
            assertFailsWith<CompanionTransportException> { fixture.client.poll(listening.sessionId) }
            val input = mutableListOf<Int>()
            pollUntil { input += fixture.client.poll(listening.sessionId).bytes; input.size >= 5 }
            assertEquals(listOf(0, 127, 128, 255, 10), input)
            assertEquals(TelnetOutputAccepted(1), fixture.client.send(listening.sessionId, TelnetPacket(1, listOf(42, 255))))
            assertEquals(42, peer.getInputStream().read())
            assertEquals(255, peer.getInputStream().read())
            // A retransmission is accepted by packet ID without sending the payload twice.
            assertEquals(TelnetOutputAccepted(1), fixture.client.send(listening.sessionId, TelnetPacket(1, listOf(42, 255))))
            assertEquals("packet-conflict", assertFailsWith<CompanionProtocolException> {
                fixture.client.send(listening.sessionId, TelnetPacket(1, listOf(43)))
            }.code)
            repeat(80) { assertTrue(fixture.client.poll(listening.sessionId).bytes.isEmpty()) }
            assertEquals(TelnetLinkChanged(TelnetLinkState.Stopped), fixture.client.stop(listening.sessionId))
            assertEquals(-1, peer.getInputStream().read())
        }
        assertEquals(TelnetLinkChanged(TelnetLinkState.Stopped), fixture.client.stop(listening.sessionId))
    }

    @Test
    fun telnetIacUsesEvolutionDiscardAndLiteralOutputThroughTheCompanion() = isolatedFromForeignPeers { fixture ->
        val pending = fixture.client.prepareStart(TelnetListenConfiguration(0, telnetMode = true), requestId = "real-telnet")
        val listening = fixture.client.confirmStart(pending)
        Socket("127.0.0.1", listening.localPort).use { peer ->
            peer.soTimeout = 3_000
            fixture.client.poll(listening.sessionId)
            assertEquals(listOf(255, 251, 3, 255, 251, 1), List(6) { peer.getInputStream().read() })
            peer.getOutputStream().write(byteArrayOf(65, 255.toByte()))
            val bytes = mutableListOf<Int>()
            pollUntil { bytes += fixture.client.poll(listening.sessionId).bytes; bytes.isNotEmpty() }
            assertEquals(listOf(65), bytes)
            peer.getOutputStream().write(byteArrayOf(255.toByte()))
            assertTrue(fixture.client.poll(listening.sessionId).bytes.isEmpty())
            peer.getOutputStream().write(byteArrayOf(66, 67))
            pollUntil { bytes += fixture.client.poll(listening.sessionId).bytes; bytes.size >= 2 }
            assertEquals(listOf(65, 67), bytes)
            fixture.client.send(listening.sessionId, TelnetPacket(1, listOf(68, 255)))
            assertEquals(listOf(68, 255), List(2) { peer.getInputStream().read() })
            assertEquals(0, peer.getInputStream().available())
            fixture.client.stop(listening.sessionId)
        }
    }

    /**
     * Any local process can connect to a loopback listener (desktop apps probe new ports with
     * `GET /`), and the companion, like Evolution's TelnetServer, hands the session to the newest
     * client. The listener reports each accepted client through `peerGeneration`; a run that saw a
     * second client was disturbed by the host, so it is repeated on a fresh listener.
     */
    private fun isolatedFromForeignPeers(dropFirstPollResponse: Boolean = false, scenario: suspend (Fixture) -> Unit) = runBlocking {
        val disturbed = mutableListOf<Throwable>()
        while (true) {
            val fixture = Fixture(dropFirstPollResponse)
            try {
                fixture.use { scenario(it) }
                return@runBlocking
            } catch (failure: Throwable) {
                if (fixture.peerGenerations.max() <= 1 || disturbed.size == 4) {
                    disturbed.forEach(failure::addSuppressed)
                    throw failure
                }
                disturbed += failure
            }
        }
    }

    private suspend fun pollUntil(condition: suspend () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    private class Fixture(dropFirstPollResponse: Boolean = false) : AutoCloseable {
        private val root = sequenceOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "companion/telnet.py").isFile }
        private val temporary = Files.createTempDirectory("telnet-http-test-").toFile()
        private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private val process: Process
        val client: CompanionTelnetClient
        /** Every `peerGeneration` the listener reported; above 1 means another client connected. */
        val peerGenerations = java.util.concurrent.ConcurrentLinkedQueue(listOf(0L))
        init {
            val log = File(temporary, "server.log")
            val builder = ProcessBuilder("python3", "-u", "-c", PYTHON_FIXTURE)
                .directory(root).redirectError(log)
            builder.environment()["LOGISIM_TELNET_TEST_TOKEN"] = token
            builder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            process = builder.start()
            try {
                val reader = process.inputStream.bufferedReader()
                val firstLine = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
                val port = firstLine?.toIntOrNull() ?: error("Telnet fixture did not start: ${log.readText()}")
                val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:$port"), CompanionToken.parse(token))
                val network = createCompanionTransport()
                val transport = object : CompanionTransport {
                    var dropped = false
                    override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                        val response = network.execute(request)
                        PEER_GENERATION.findAll(response.body.decodeToString()).forEach {
                            peerGenerations += it.groupValues[1].toLong()
                        }
                        if (dropFirstPollResponse && !dropped && request.body?.let {
                            CompanionJsonCodec.parseObject(it.decodeToString()).fields["operation"] == CompanionJsonString("telnet.poll")
                        } == true) {
                            dropped = true
                            throw CompanionTransportException("connection-lost")
                        }
                        return response
                    }
                }
                // A short job poll keeps each run well inside the delay before host port probes arrive.
                client = CompanionTelnetClient(CompanionClient(connection, transport), pollDelayMillis = 1)
            } catch (failure: Throwable) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
                temporary.deleteRecursively()
                throw failure
            }
        }
        override fun close() {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
            temporary.deleteRecursively()
        }
    }

    companion object {
        private val PEER_GENERATION = Regex(""""peerGeneration"\s*:\s*(\d+)""")
        private val PYTHON_FIXTURE = """
            import os, signal, threading
            from companion.api import OperationRegistry
            from companion.server import Configuration, make_http_server
            from companion.telnet import register
            registry = OperationRegistry()
            register(registry)
            server = make_http_server(Configuration(os.environ['LOGISIM_TELNET_TEST_TOKEN'],
                {'telnet.start', 'telnet.poll', 'telnet.send', 'telnet.stop'}), registry)
            signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
            print(server.server_port, flush=True)
            try:
                server.serve_forever()
            finally:
                server.server_close()
                server.service.close()
        """.trimIndent()
    }
}
