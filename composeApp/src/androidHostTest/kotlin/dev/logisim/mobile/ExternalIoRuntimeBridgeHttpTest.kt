package dev.logisim.mobile

import dev.logisim.mobile.core.*
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Exercises the editor sender and bridge against real companion HTTP and a real TCP peer. */
class ExternalIoRuntimeBridgeHttpTest {
    @Test
    fun recreatedSessionPreservesTheFirstReceivedBatchAfterLifecycleCleanup() = runBlocking<Unit> {
        recreatedSession(peerDisconnectsFirst = false)
    }

    @Test
    fun recreatedSessionPreservesTheFirstReceivedBatchAfterPeerDisconnect() = runBlocking<Unit> {
        recreatedSession(peerDisconnectsFirst = true)
    }

    private suspend fun recreatedSession(peerDisconnectsFirst: Boolean) {
        Host().use { host ->
            val port = ServerSocket(0).use { it.localPort }
            val terminal = CircuitComponent("terminal", ExternalIoComponents.Telnet, GridPoint(300, 300),
                attributes = mapOf("port" to "$port", "buflen" to "8"))
            val editor = EditorState(CircCodec.write(CircuitDocument("main", listOf(terminal))))
            val originalXml = editor.exportXml()
            val bridge = ExternalIoRuntimeBridge { host.client }
            fun context() = EditorToolContext(editor.project, editor.document, editor.simulation,
                emptyList(), {}, {}, {}, {}, {}, documentIdentity = editor.documentIdentity)
            fun state() = assertIs<TelnetState>(editor.simulation.componentStates[terminal.id])
            val key = assertNotNull(externalIoInstanceKey(context(), terminal))
            val rounds = mutableListOf<TelnetRuntimeEvents>()
            val sender: (String, ComponentEvent) -> Boolean = { id, event ->
                rounds += assertIs<TelnetRuntimeEvents>(event)
                editor.sendComponentEvent(id, event)
            }
            suspend fun start(requestId: String): ExternalIoTelnetEntry {
                val entry = bridge.entry(key, state(), sender)
                val gateway = CompanionTelnetClient(host.client)
                entry.settings = externalTelnetSettings(terminal, false)
                entry.gateway = gateway
                entry.pending = gateway.prepareStart(TelnetListenConfiguration(port), 8, requestId)
                entry.startAttempted = true
                entry.status = gateway.confirmStart(entry.pending!!)
                return entry
            }
            try {
                val first = start("first-scene-listener")
                Socket("127.0.0.1", port).use { peer ->
                    peer.soTimeout = 3_000
                    peer.getOutputStream().write(byteArrayOf(65))
                    withTimeout(5_000) {
                        while (state().received != listOf(65)) { bridge.synchronize(context()); delay(5) }
                    }
                    if (peerDisconnectsFirst) {
                        // Isolate acknowledgement ordering from server-side TIME_WAIT port reuse.
                        peer.shutdownOutput()
                        withTimeout(5_000) {
                            while (first.status?.link != TelnetLinkState.Listening) { bridge.synchronize(context()); delay(5) }
                        }
                    } else assertTrue(bridge.closeAll())
                    assertEquals(-1, peer.getInputStream().read())
                }
                if (peerDisconnectsFirst) assertTrue(bridge.closeAll())
                assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).use { } }
                val stoppedSequence = state().runtimeSequence
                assertTrue(stoppedSequence > 0)
                assertEquals(TelnetLinkState.Stopped, state().link)
                // The scene discards closed entries after the successful background cleanup.
                assertSame(first, bridge.entries.remove(key))
                rounds.clear()

                val second = start("second-scene-listener")
                assertNotSame(first, second)
                Socket("127.0.0.1", port).use { peer ->
                    peer.soTimeout = 3_000
                    peer.getOutputStream().write(byteArrayOf(66))
                    withTimeout(5_000) {
                        while (rounds.none { round -> round.events.any { it == TelnetBytesReceived(listOf(66)) } }) {
                            bridge.synchronize(context()); delay(5)
                        }
                    }
                    assertEquals(listOf(65, 66), state().received,
                        "The first input batch of a recreated session must survive the instance's previous acknowledgement")
                    assertTrue(rounds.last().sequence > stoppedSequence)
                    assertEquals(originalXml, editor.exportXml())
                    assertTrue(bridge.closeAll())
                    assertEquals(-1, peer.getInputStream().read())
                }
                assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).use { } }
            } finally {
                bridge.closeAll()
            }
        }
    }

    @Test
    fun editorExchangesBytesAndRemovalReleasesAPausedRealListener() = runBlocking<Unit> {
        Host().use { host ->
            val port = ServerSocket(0).use { it.localPort }
            val terminal = CircuitComponent("terminal", ExternalIoComponents.Telnet, GridPoint(300, 300),
                attributes = mapOf("port" to "$port", "buflen" to "8"))
            val pins = ComponentCatalog.registry.layout(terminal).inputs.map {
                CircuitComponent(it.name, ComponentKind.INPUT, it.position,
                    attributes = mapOf("width" to "${it.width}"))
            }
            val editor = EditorState(CircCodec.write(CircuitDocument("main", pins + terminal)))
            assertEquals(setOf("terminal", "clock", "read", "write", "dataIn"), editor.document.components.map { it.id }.toSet())
            val originalXml = editor.exportXml()
            val bridge = ExternalIoRuntimeBridge { host.client }
            fun context() = EditorToolContext(editor.project, editor.document, editor.simulation,
                emptyList(), {}, {}, {}, {}, {}, documentIdentity = editor.documentIdentity)
            val key = assertNotNull(externalIoInstanceKey(context(), terminal))
            val entry = bridge.entry(key, assertIs<TelnetState>(editor.simulation.componentStates[terminal.id]), editor::sendComponentEvent)
            val gateway = CompanionTelnetClient(host.client)
            val plan = gateway.prepareStart(TelnetListenConfiguration(port), 8, "editor-real-listener")
            assertNull(entry.status)
            entry.settings = externalTelnetSettings(terminal, false)
            entry.pending = plan
            entry.gateway = gateway
            entry.startAttempted = true
            entry.status = gateway.confirmStart(plan)
            assertEquals(TelnetLinkState.Listening, entry.status!!.link)
            val peer = Socket("127.0.0.1", port)
            peer.soTimeout = 3_000
            try {
                fun state() = assertIs<TelnetState>(editor.simulation.componentStates[terminal.id])
                peer.getOutputStream().write(byteArrayOf(0, 127, 128.toByte(), 255.toByte()))
                withTimeout(5_000) {
                    while (state().received.size < 4) { bridge.synchronize(context()); delay(5) }
                }
                assertEquals(listOf(0, 127, 128, 255), state().received)
                assertEquals(TelnetLinkState.Connected, state().link)
                assertTrue(state().runtimeSequence > 0)

                editor.setInputValue("clock", 0u)
                editor.setInputValue("write", 1u)
                editor.setInputValue("dataIn", 65u)
                editor.setInputValue("clock", 1u)
                assertEquals(listOf(TelnetPacket(1, listOf(65))), state().pendingOutput)
                bridge.synchronize(context())
                assertEquals(65, peer.getInputStream().read())
                assertEquals(emptyList(), state().pendingOutput)
                assertEquals(originalXml, editor.exportXml(), "Network state must not change document contents")

                // The remote poll completes; its reply is then lost on the client transport.
                host.dropNextPollReply = true
                bridge.synchronize(context())
                assertTrue(entry.paused)
                assertNotNull(entry.status)
                assertEquals(originalXml, editor.exportXml())

                assertTrue(editor.removeComponent(terminal.id))
                bridge.synchronize(context())
                assertNull(entry.status)
                assertNull(entry.pending)
                assertEquals("external_io_telnet_config_changed", entry.notice?.key)
                assertEquals(-1, peer.getInputStream().read(), "Removing the owner must close its connected peer")
                assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).use { } }
            } finally {
                peer.close()
                bridge.closeAll()
            }
        }
    }

    @Test
    fun resizingInputKeepsTheSamePeerButChangingThePortClosesIt() = runBlocking<Unit> {
        Host().use { host ->
            val port = ServerSocket(0).use { it.localPort }
            val terminal = CircuitComponent("terminal", ExternalIoComponents.Telnet, GridPoint(300, 300),
                attributes = mapOf("port" to "$port", "buflen" to "8"))
            val editor = EditorState(CircCodec.write(CircuitDocument("main", listOf(terminal))))
            val bridge = ExternalIoRuntimeBridge { host.client }
            fun context() = EditorToolContext(editor.project, editor.document, editor.simulation,
                emptyList(), {}, {}, {}, {}, {}, documentIdentity = editor.documentIdentity)
            fun state() = assertIs<TelnetState>(editor.simulation.componentStates[terminal.id])
            val entry = bridge.entry(assertNotNull(externalIoInstanceKey(context(), terminal)), state(), editor::sendComponentEvent)
            val gateway = CompanionTelnetClient(host.client)
            entry.gateway = gateway
            entry.settings = externalTelnetSettings(terminal, false)
            entry.pending = gateway.prepareStart(TelnetListenConfiguration(port), 8, "editor-resize-listener")
            entry.startAttempted = true
            entry.status = gateway.confirmStart(entry.pending!!)
            try {
                Socket("127.0.0.1", port).use { peer ->
                    peer.soTimeout = 3_000
                    peer.getOutputStream().write(byteArrayOf(65, 66))
                    withTimeout(5_000) {
                        while (state().received.size != 2) { bridge.synchronize(context()); delay(5) }
                    }
                    val connected = assertNotNull(entry.status)
                    assertEquals(listOf(65, 66), state().received)
                    editor.select(terminal.id)
                    editor.updateSelectedAttribute("buflen", "1")
                    bridge.synchronize(context())
                    assertEquals(connected.sessionId, entry.status?.sessionId)
                    assertEquals(connected.peerGeneration, entry.status?.peerGeneration)
                    assertEquals(TelnetLinkState.Connected, state().link)
                    assertEquals(emptyList(), state().received)

                    peer.getOutputStream().write(byteArrayOf(67, 68))
                    withTimeout(5_000) {
                        while (state().received.isEmpty()) { bridge.synchronize(context()); delay(5) }
                    }
                    assertEquals(listOf(67), state().received)
                    assertEquals(1L, entry.status?.droppedInputBytes)

                    val newPort = if (port == 65_535) port - 1 else port + 1
                    editor.updateSelectedAttribute("port", "$newPort")
                    bridge.synchronize(context())
                    assertNull(entry.status)
                    assertNull(entry.pending)
                    assertEquals(TelnetLinkState.Stopped, state().link)
                    assertEquals(-1, peer.getInputStream().read())
                    assertFailsWith<java.io.IOException> { Socket("127.0.0.1", port).use { } }
                }
            } finally {
                bridge.closeAll()
            }
        }
    }

    private class Host : AutoCloseable {
        private val root = sequenceOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "companion/telnet.py").isFile }
        private val directory = Files.createTempDirectory("editor-telnet-http-").toFile()
        private val process: Process
        var dropNextPollReply = false
        val client: CompanionClient

        init {
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val log = File(directory, "server.log")
            val builder = ProcessBuilder("python3", "-u", "-c", PYTHON_SERVER).directory(root).redirectError(log)
            builder.environment()["LOGISIM_TELNET_TEST_TOKEN"] = token
            builder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            process = builder.start()
            try {
                val reader = process.inputStream.bufferedReader()
                val line = CompletableFuture.supplyAsync { reader.readLine() }.get(10, TimeUnit.SECONDS)
                val port = line?.toIntOrNull() ?: error("Companion fixture did not start: ${log.readText()}")
                val network = createCompanionTransport()
                val transport = object : CompanionTransport {
                    override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                        val response = network.execute(request)
                        val operation = request.body?.let { CompanionJsonCodec.parseObject(it.decodeToString()).fields["operation"] }
                        if (dropNextPollReply && operation == CompanionJsonString("telnet.poll")) {
                            dropNextPollReply = false
                            throw CompanionTransportException("connection-lost")
                        }
                        return response
                    }
                }
                client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:$port"),
                    CompanionToken.parse(token)), transport)
            } catch (failure: Throwable) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
                directory.deleteRecursively()
                throw failure
            }
        }

        override fun close() {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
            directory.deleteRecursively()
        }
    }

    companion object {
        private val PYTHON_SERVER = """
            import os, signal, threading
            from companion.api import OperationRegistry
            from companion.server import Configuration, make_http_server
            from companion.telnet import register
            registry = OperationRegistry()
            register(registry)
            server = make_http_server(Configuration(os.environ['LOGISIM_TELNET_TEST_TOKEN'],
                {'telnet.start', 'telnet.poll', 'telnet.resize', 'telnet.send', 'telnet.stop'}), registry)
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
