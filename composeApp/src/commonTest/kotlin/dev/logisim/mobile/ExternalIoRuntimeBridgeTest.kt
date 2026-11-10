package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class ExternalIoRuntimeBridgeTest {
    private val terminal = CircuitComponent("terminal", ExternalIoComponents.Telnet, GridPoint(0, 0),
        attributes = mapOf("port" to "8000", "buflen" to "4"))
    private val child = CircuitDocument("child", listOf(terminal))
    private val main = CircuitDocument("main", listOf("left", "right").map {
        CircuitComponent(it, ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "child")
    })
    private val project = CircuitProject(listOf(main, child), "main")

    private fun context(path: String = "left", state: TelnetState = TelnetState()): EditorToolContext {
        val local = SimulationState(instancePath = path, componentStates = mapOf(terminal.id to state))
        return EditorToolContext(project, child, local, listOf(terminal), {}, {}, {}, {}, {},
            projectSimulation = SimulationState(circuitStates = mapOf(path to local,
                "right" to SimulationState(instancePath = "right", componentStates = mapOf(terminal.id to TelnetState())))),
            documentIdentity = 12)
    }

    private fun setup(state: TelnetState = TelnetState()): Triple<ExternalIoRuntimeBridge, ExternalIoTelnetEntry, FakeHost> {
        val host = FakeHost()
        val bridge = ExternalIoRuntimeBridge { host.client }
        val key = externalIoInstanceKey(context(state = state), terminal)!!
        val entry = bridge.entry(key, state) { _, event -> host.events += event; true }
        entry.settings = externalTelnetSettings(terminal, false)
        entry.gateway = CompanionTelnetClient(host.client)
        entry.status = CompanionTelnetStatus(host.session, 8000, "127.0.0.1", false, TelnetLinkState.Listening, 0, 0, 0)
        return Triple(bridge, entry, host)
    }

    @Test
    fun factoryIsInertAndUsesOneLocalizedTool() {
        var calls = 0
        val tools = externalIoEditorTools { calls++; null }
        assertEquals(listOf("external-io"), tools.map { it.id })
        assertEquals(0, calls)
        assertEquals("External I/O", tools.single().titleText.resolve(AppLanguage.ENGLISH))
        assertEquals("E/S externa", tools.single().titleText.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun instanceKeysAndLookupsKeepSiblingStateSeparate() {
        val left = context(state = TelnetState(received = listOf(65)))
        val leftKey = externalIoInstanceKey(left, terminal)!!
        val rightKey = leftKey.copy(instancePath = "right")
        assertNotEquals(leftKey, rightKey)
        assertEquals(listOf(65), assertIs<TelnetState>(externalIoState(left, leftKey)).received)
        assertEquals(emptyList(), assertIs<TelnetState>(externalIoState(left, rightKey)).received)
        assertNull(externalIoComponent(left.copy(documentIdentity = 99), leftKey))
        assertNull(externalIoInstanceKey(left.copy(simulation = left.simulation.copy(instancePath = "")), terminal))
    }

    @Test
    fun remoteRoundPublishesOneAtomicEventForItsInstanceAndWaitsForTheSimulator() = runBlocking {
        val state = TelnetState(pendingOutput = listOf(TelnetPacket(1, listOf(65)), TelnetPacket(2, listOf(66))))
        val (bridge, entry, host) = setup(state)
        bridge.synchronize(context(state = state))
        val round = assertIs<TelnetRuntimeEvents>(host.events.single())
        assertEquals(1L, round.sequence)
        assertEquals(listOf(TelnetLinkChanged(TelnetLinkState.Connected), TelnetBytesReceived(listOf(65)),
            TelnetOutputAccepted(2)), round.events)
        assertEquals(listOf(65, 66), host.sent)
        assertEquals(1L, entry.issuedSequence)
        bridge.synchronize(context(state = state))
        assertEquals(1, host.pollLimits.size)
        assertEquals(1, host.events.size)
    }

    @Test
    fun peerDisconnectDuringSendKeepsPollingAndRetriesThePacketAfterReconnect() = runBlocking {
        val packet = TelnetPacket(1, listOf(66))
        val state = TelnetState(link = TelnetLinkState.Connected, pendingOutput = listOf(packet))
        val (bridge, entry, host) = setup(state)
        host.disconnectDuringSend = true

        bridge.synchronize(context(state = state))
        val received = assertIs<TelnetRuntimeEvents>(host.events.single())
        assertEquals(listOf(TelnetBytesReceived(listOf(65))), received.events)
        assertFalse(entry.paused)
        assertEquals(emptyList(), host.sent)

        bridge.synchronize(context(state = state.copy(received = listOf(65), runtimeSequence = received.sequence)))
        val disconnected = assertIs<TelnetRuntimeEvents>(host.events.last())
        assertEquals(listOf(TelnetLinkChanged(TelnetLinkState.Listening)), disconnected.events)
        assertEquals(TelnetLinkState.Listening, entry.status?.link)

        host.peerConnected = true
        bridge.synchronize(context(state = state.copy(link = TelnetLinkState.Listening, received = listOf(65),
            runtimeSequence = disconnected.sequence)))
        val reconnected = assertIs<TelnetRuntimeEvents>(host.events.last())
        assertEquals(listOf(TelnetLinkChanged(TelnetLinkState.Connected), TelnetBytesReceived(listOf(65)),
            TelnetOutputAccepted(packet.id)), reconnected.events)
        assertEquals(packet.bytes, host.sent)
    }

    @Test
    fun recreatedEntryPublishesAfterTheSequenceAlreadyAppliedByItsInstance() = runBlocking {
        val state = TelnetState(runtimeSequence = 7)
        val (bridge, _, host) = setup(state)
        bridge.synchronize(context(state = state))
        val round = assertIs<TelnetRuntimeEvents>(host.events.single())
        assertEquals(8L, round.sequence)
        assertEquals(listOf(TelnetLinkChanged(TelnetLinkState.Connected), TelnetBytesReceived(listOf(65))), round.events)
    }

    @Test
    fun acceptedRoundsKeepTheirOrderWhenCleanupRecreatesAnEntryBeforeTheEditorAppliesThem() = runBlocking {
        val previous = TelnetState(runtimeSequence = 7)
        val (bridge, first, host) = setup(previous)
        val listening = first.status!!
        bridge.synchronize(context(state = previous))
        assertTrue(bridge.closeAll())
        assertSame(first, bridge.entries.remove(first.key))

        val second = bridge.entry(first.key, previous) { _, event -> host.events += event; true }
        second.gateway = CompanionTelnetClient(host.client)
        second.settings = first.settings
        second.status = listening
        host.listeners = 1
        bridge.synchronize(context(state = previous))

        val definition = ComponentCatalog.registry.definition(ExternalIoComponents.Telnet)!!
        val attributes = definition.schema.parse(terminal.attributes)
        val delivered = definition.behavior!!.evaluate(ComponentEvaluationContext(terminal, attributes,
            emptyMap(), emptySet(), previous, events = host.events)).state
        assertEquals(listOf(65, 65), assertIs<TelnetState>(delivered).received,
            "Accepted but not yet applied rounds must not suppress the new session's first batch")
        assertEquals(listOf(8L, 9L, 10L), host.events.map { assertIs<TelnetRuntimeEvents>(it).sequence })
    }

    @Test
    fun fullCircuitInputUsesStatusOnlyPollAndDoesNotConsumeBytes() = runBlocking {
        val state = TelnetState(received = listOf(10, 11, 12, 13), link = TelnetLinkState.Connected)
        val (bridge, _, host) = setup(state)
        bridge.synchronize(context(state = state))
        assertEquals(listOf(0L), host.pollLimits)
        assertEquals(emptyList(), host.events)
    }

    @Test
    fun lostResetAcknowledgementEventuallyStopsTheRemoteResource() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.issuedSequence = 1
        entry.pendingSince = TimeSource.Monotonic.markNow() - 6.seconds
        bridge.synchronize(context())
        assertEquals(0, host.listeners)
        assertNull(entry.status)
        assertEquals("external_io_event_timeout", entry.notice?.key)
    }

    @Test
    fun changedInstanceStopsItsListenerInsteadOfSendingIntoAnotherCircuit() = runBlocking {
        val (bridge, entry, host) = setup()
        bridge.synchronize(context().copy(project = project.copy(circuits = listOf(main.copy(components = emptyList()), child))))
        assertNull(entry.status)
        assertEquals(0, host.listeners)
        assertEquals("external_io_telnet_config_changed", entry.notice?.key)
    }

    @Test
    fun removingAnInstanceStillClosesItsListenerWhileNetworkPollingIsPaused() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.paused = true
        bridge.synchronize(context().copy(project = project.copy(circuits = listOf(main.copy(components = emptyList()), child))))
        assertEquals(0, host.listeners)
        assertNull(entry.status)
        assertEquals("external_io_telnet_config_changed", entry.notice?.key)
    }

    @Test
    fun changingConfigurationStillClosesItsListenerWhileNetworkPollingIsPaused() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.paused = true
        val changed = child.copy(components = listOf(terminal.copy(attributes = terminal.attributes + ("port" to "8001"))))
        bridge.synchronize(context().copy(project = project.copy(circuits = listOf(main, changed))))
        assertEquals(0, host.listeners)
        assertNull(entry.status)
        assertEquals("external_io_telnet_config_changed", entry.notice?.key)
    }

    @Test
    fun simulationResetStillClosesItsListenerWhileNetworkPollingIsPaused() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.paused = true
        entry.observedSequence = 2
        bridge.synchronize(context(state = TelnetState(runtimeSequence = 0)))
        assertEquals(0, host.listeners)
        assertNull(entry.status)
        assertEquals("external_io_simulation_reset", entry.notice?.key)
    }

    @Test
    fun removalResolvesAndClosesAnUncertainStartInsteadOfLosingItsRecoveryIdentity() = runBlocking {
        val (bridge, entry, host) = setup()
        val pending = entry.gateway!!.prepareStart(TelnetListenConfiguration(8000), 4, "removed-uncertain-start")
        entry.pending = pending
        entry.status = null
        entry.startAttempted = true
        entry.paused = true
        host.failStartReply = true
        assertFailsWith<CompanionTransportException> { entry.gateway!!.confirmStart(pending) }
        assertEquals(1, host.listeners)

        bridge.synchronize(context().copy(project = project.copy(circuits = listOf(main.copy(components = emptyList()), child))))
        assertEquals(0, host.listeners)
        assertEquals(listOf("removed-uncertain-start", "removed-uncertain-start"), host.startIds)
        assertNull(entry.pending)
        assertFalse(entry.startAttempted)
    }

    @Test
    fun unchangedPausedSessionDoesNotResumeNetworkPollingOrConsumeInput() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.paused = true
        bridge.synchronize(context())
        assertEquals(1, host.listeners)
        assertTrue(entry.paused)
        assertEquals(emptyList(), host.pollLimits)
        assertEquals(emptyList(), host.events)
    }

    @Test
    fun removingAPreparedButUnconfirmedPlanDoesNotStartAListenerForCleanup() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.pending = entry.gateway!!.prepareStart(TelnetListenConfiguration(8000), 4, "unconfirmed-start")
        entry.status = null
        host.listeners = 0
        bridge.synchronize(context().copy(project = project.copy(circuits = listOf(main.copy(components = emptyList()), child))))
        assertEquals(0, host.listeners)
        assertEquals(emptyList(), host.startIds)
        assertEquals(emptyList(), host.pollLimits)
        assertFalse(entry.startAttempted)
    }

    @Test
    fun shutdownResolvesTheSameUncertainStartBeforeDiscardingItsIdentity() = runBlocking {
        val (bridge, entry, host) = setup()
        val gateway = entry.gateway!!
        val pending = gateway.prepareStart(TelnetListenConfiguration(8000), 4, "unique-start-1")
        entry.pending = pending
        entry.status = null
        entry.startAttempted = true
        host.failStartReply = true
        assertFailsWith<CompanionTransportException> { gateway.confirmStart(pending) }
        assertEquals(1, host.listeners)
        bridge.stopEntry(entry)
        assertEquals(0, host.listeners)
        assertEquals(listOf("unique-start-1", "unique-start-1"), host.startIds)
        assertNull(entry.pending)
        assertFalse(entry.startAttempted)
    }

    @Test
    fun rejectedStartReleasesThePlanAndAllowsASeparateStart() = runBlocking {
        val (bridge, entry, host) = setup()
        val gateway = entry.gateway!!
        val configuration = TelnetListenConfiguration(8000)
        entry.pending = gateway.prepareStart(configuration, 4, "occupied-port")
        entry.status = null
        host.listeners = 0
        host.rejectStart = true

        assertNull(bridge.resolveStart(entry))
        assertEquals("external_io_protocol_error", entry.notice?.key)
        assertNull(entry.pending)
        assertFalse(entry.startAttempted)
        assertTrue(bridge.closeAll())
        assertEquals(listOf("occupied-port"), host.startIds)

        host.rejectStart = false
        entry.pending = gateway.prepareStart(configuration, 4, "released-port")
        assertEquals(TelnetLinkState.Listening, bridge.resolveStart(entry)?.link)
        assertEquals(listOf("occupied-port", "released-port"), host.startIds)
        assertTrue(bridge.closeAll())
    }

    @Test
    fun failedCleanupKeepsItsPendingRecoveryAndCanBeRetried() = runBlocking {
        val (bridge, entry, host) = setup()
        entry.cleanupPending = true
        host.failStop = true
        bridge.retryOutstandingCleanup()
        assertTrue(entry.cleanupPending)
        assertSame(entry, bridge.entries[entry.key])
        host.failStop = false
        bridge.retryOutstandingCleanup()
        assertFalse(entry.cleanupPending)
        assertFalse(bridge.entries.containsKey(entry.key))
        assertEquals(0, host.listeners)
    }

    @Test
    fun timedOutShutdownRetainsEverySessionForALaterCleanupRetry() = runBlocking {
        val (bridge, left, host) = setup()
        val right = bridge.entry(left.key.copy(instancePath = "right"), TelnetState()) { _, event -> host.events += event; true }
        right.gateway = CompanionTelnetClient(host.client)
        right.settings = left.settings
        val rightSession = "zyxwvutsrqponmlkjihgfedcb"
        right.status = left.status!!.copy(sessionId = rightSession)
        host.listeners = 2
        host.stopBarrier = CompletableDeferred()

        val cleanup = async { bridge.closeAll() }
        host.stopStarted.await()
        assertNull(withTimeoutOrNull(10) { cleanup.await() })
        cleanup.cancelAndJoin()
        assertEquals(1, host.stopCalls)
        assertEquals(2, host.listeners)
        assertTrue(left.cleanupPending)
        assertTrue(right.cleanupPending)
        assertEquals(setOf(left.key, right.key), bridge.entries.keys.toSet())

        host.stopBarrier!!.complete(Unit)
        bridge.retryOutstandingCleanup()
        assertEquals(setOf(host.session, rightSession), host.stoppedSessions)
        assertEquals(0, host.listeners)
        assertTrue(bridge.entries.isEmpty())
        assertNull(left.status)
        assertNull(right.status)
    }

    private class FakeHost : CompanionTransport {
        val session = "abcdefghijklmnopqrstuvwx"
        val events = mutableListOf<ComponentEvent>()
        val pollLimits = mutableListOf<Long>()
        val sent = mutableListOf<Int>()
        val startIds = mutableListOf<String>()
        var listeners = 1
        var rejectStart = false
        var failStartReply = false
        var failStop = false
        var peerConnected = true
        var disconnectDuringSend = false
        var stopBarrier: CompletableDeferred<Unit>? = null
        val stopStarted = CompletableDeferred<Unit>()
        var stopCalls = 0
        val stoppedSessions = mutableSetOf<String>()
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8001"),
            CompanionToken.parse("synthetic-test-token-with-more-than32chars")), this)

        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            val body = request.body?.let { CompanionJsonCodec.parseObject(it.decodeToString()) }
            if (request.url.endsWith("/capabilities")) return response("""
                {"protocolVersion":1,"operations":[{"name":"telnet.start","allowed":true,"available":true,
                "missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"Telnet"}],
                "limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":2097152,"timeoutSeconds":60,
                "processMemoryBytes":2147483648,"memoryEnforcement":"supervised-process-group","osSandbox":false}}
            """)
            val operation = body!!.string("operation")
            val parameters = body.obj("parameters")
            if (request.url.endsWith("/plans")) return response("""{"operation":"$operation","parameters":${CompanionJsonCodec.write(parameters)},
                "inputs":[],"requiresConfirmation":true,"expiresInSeconds":60,"planToken":"synthetic-plan-token"}""")
            val status = """"sessionId":"$session","localPort":8000,"bindAddress":"127.0.0.1","telnetMode":false,
                "peerGeneration":1,"droppedInputBytes":0,"droppedOutputBytes":0,"leaseSeconds":300"""
            val result = when (operation) {
                "telnet.start" -> {
                    startIds += body.string("requestId")
                    if (rejectStart) return response("""
                        {"jobId":"${"a".repeat(32)}","operation":"telnet.start","state":"failed","result":null,
                        "error":{"code":"listen-failed","message":"Address already in use","details":null},"logs":[]}
                    """)
                    listeners = 1
                    if (failStartReply) { failStartReply = false; throw CompanionTransportException("connection-lost") }
                    """{$status,"link":"listening","created":false}"""
                }
                "telnet.poll" -> {
                    val maximum = parameters.long("maximumBytes")
                    pollLimits += maximum
                    val link = if (peerConnected) "connected" else "listening"
                    val bytes = if (peerConnected && maximum > 0) "[65]" else "[]"
                    """{$status,"link":"$link","bytes":$bytes,"pollId":${parameters.long("pollId")}}"""
                }
                "telnet.send" -> {
                    if (disconnectDuringSend) {
                        disconnectDuringSend = false
                        peerConnected = false
                        return response("""
                            {"jobId":"${"a".repeat(32)}","operation":"telnet.send","state":"failed","result":null,
                            "error":{"code":"not-connected","message":"No connected peer","details":null},"logs":[]}
                        """)
                    }
                    sent += parameters.array("bytes").map { (it as CompanionJsonNumber).value.toInt() }
                    """{$status,"link":"connected","duplicate":false,"acceptedPacketId":${parameters.long("packetId")}}"""
                }
                "telnet.stop" -> {
                    if (failStop) throw CompanionTransportException("connection-lost")
                    stopCalls++
                    stopStarted.complete(Unit)
                    stopBarrier?.await()
                    val stopped = parameters.string("sessionId")
                    if (stoppedSessions.add(stopped)) listeners--
                    """{"sessionId":"$stopped","link":"stopped","alreadyStopped":false}"""
                }
                else -> error(operation)
            }
            return response("""{"jobId":"${"a".repeat(32)}","operation":"$operation","state":"succeeded","result":$result,"error":null,"logs":[]}""")
        }
        private fun response(value: String) = CompanionHttpResponse(200, value.trimIndent().encodeToByteArray(), "application/json")
    }
}
