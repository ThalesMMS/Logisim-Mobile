package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelnetRuntimeTest {
    private val definition = ExternalIoComponents.definitions().single { it.kind == ExternalIoComponents.Telnet }
    private val component = CircuitComponent("telnet", ExternalIoComponents.Telnet, GridPoint(0, 0))

    private fun evaluate(state: ComponentState = TelnetState(), inputs: Map<String, LogicVector> = emptyMap(),
                         events: List<ComponentEvent> = emptyList(), attributes: Map<String, String> = emptyMap()) =
        definition.behavior!!.evaluate(ComponentEvaluationContext(component, definition.schema.parse(attributes),
            inputs, inputs.keys, state = state, events = events))

    @Test
    fun importingATelnetComponentDoesNotOpenAnyNetworkResource() {
        val transport = RecordingTransport()
        val session = TelnetSession(transport)
        val initial = evaluate()
        assertEquals("0", initial.outputs.getValue("available").display)
        // Evolution drives the one-bit Value.UNKNOWN on the eight-bit port while reading is disabled.
        assertEquals("0000000?", initial.outputs.getValue("dataOut").display)
        assertTrue(initial.diagnostics.single().contains("parado"))
        assertEquals(emptyList(), session.synchronize(initial.state as TelnetState))
        assertEquals(0, transport.starts)
    }

    @Test
    fun receivedBytesAreReadAndConsumedAtTheSelectedClockEdge() {
        val receive = evaluate(events = listOf(TelnetLinkChanged(TelnetLinkState.Connected),
            TelnetBytesReceived(listOf(65, 66, 67))), inputs = mapOf("clock" to LogicVector.low(1)))
        assertEquals("1", receive.outputs.getValue("available").display)
        val first = evaluate(receive.state, mapOf("read" to LogicVector.high(1), "clock" to LogicVector.high(1)))
        assertEquals(65uL, first.outputs.getValue("dataOut").toULongOrNull())
        assertEquals(listOf(66, 67), (first.state as TelnetState).received)
        val sameEdge = evaluate(first.state, mapOf("read" to LogicVector.high(1), "clock" to LogicVector.high(1)))
        assertEquals(66uL, sameEdge.outputs.getValue("dataOut").toULongOrNull())
        assertEquals(listOf(66, 67), (sameEdge.state as TelnetState).received)
    }

    @Test
    fun receiveBufferDropsNewBytesWhenFullLikeTheDesktop() {
        val result = evaluate(events = listOf(TelnetBytesReceived(listOf(1, 2, 3, 4))), attributes = mapOf("buflen" to "2"))
        assertEquals(listOf(1, 2), (result.state as TelnetState).received)
    }

    @Test
    fun writesProduceOnePacketPerEdgeAndAcknowledgementsPreventReplay() {
        val transport = RecordingTransport()
        val session = TelnetSession(transport)
        val connected = session.start(TelnetListenConfiguration(8000))
        transport.next = TelnetTransportBatch(emptyList(), TelnetLinkState.Connected)
        val low = evaluate(events = listOf(connected, TelnetLinkChanged(TelnetLinkState.Connected)),
            inputs = mapOf("clock" to LogicVector.low(1)))
        val high = evaluate(low.state, mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1),
            "dataIn" to LogicVector.of(8, 90u)))
        val state = high.state as TelnetState
        assertEquals(listOf(90), state.pendingOutput.single().bytes)
        val acknowledged = session.synchronize(state)
        session.synchronize(state)
        assertEquals(listOf(listOf(90)), transport.sent)
        val after = evaluate(state, mapOf("clock" to LogicVector.high(1)), acknowledged)
        assertEquals(emptyList(), (after.state as TelnetState).pendingOutput)
        session.stop()
        assertTrue(transport.closed)
    }

    @Test
    fun initialHighClockIsNotARisingEdgeAndPendingWritesAreBounded() {
        val connected = TelnetState(link = TelnetLinkState.Connected)
        val high = mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1),
            "dataIn" to LogicVector.of(8, 65u))
        val initial = evaluate(connected, high, attributes = mapOf("buflen" to "1"))
        assertEquals(emptyList(), (initial.state as TelnetState).pendingOutput)
        val low = evaluate(initial.state, high + ("clock" to LogicVector.low(1)), attributes = mapOf("buflen" to "1"))
        val first = evaluate(low.state, high, attributes = mapOf("buflen" to "1"))
        assertEquals(listOf(65), (first.state as TelnetState).pendingOutput.single().bytes)
        val lowAgain = evaluate(first.state, high + ("clock" to LogicVector.low(1)), attributes = mapOf("buflen" to "1"))
        val second = evaluate(lowAgain.state, high, attributes = mapOf("buflen" to "1"))
        assertEquals(1, (second.state as TelnetState).pendingOutput.size)
        assertTrue(second.diagnostics.single().contains("buffer de saída está cheio"))
    }

    @Test
    fun fullTransportQueueDoesNotAcknowledgeUnsentPackets() {
        val transport = RecordingTransport()
        val session = TelnetSession(transport)
        session.start(TelnetListenConfiguration(8000))
        transport.next = TelnetTransportBatch(emptyList(), TelnetLinkState.Connected)
        transport.accepts = false
        val events = session.synchronize(TelnetState(pendingOutput = listOf(TelnetPacket(1, listOf(65))),
            link = TelnetLinkState.Connected))
        assertTrue(events.single() is TelnetLinkChanged)
        assertTrue((events.single() as TelnetLinkChanged).link is TelnetLinkState.Failed)
        assertEquals(emptyList(), transport.sent)
    }

    @Test
    fun telnetIacDiscardsTwoBytesAcrossReadsAndTransmitsLiteralBytes() {
        for (command in listOf(0, 240, 250, 251, 252, 253, 254, 255)) {
            val wire = listOf(65, 255, command, 66, 67)
            for (split in 0..wire.size) {
                val decoder = TelnetDecoder()
                assertEquals(listOf(65, 67), decoder.decode(wire.take(split)) + decoder.decode(wire.drop(split)))
            }
            val decoder = TelnetDecoder()
            assertEquals(listOf(65, 67), wire.flatMap { decoder.decode(listOf(it)) + decoder.decode(emptyList()) })
        }
        assertEquals(listOf(65, 2, 68), TelnetDecoder().decode(listOf(65, 255, 250, 1, 2, 255, 240, 67, 68)))
        val transport = RecordingTransport()
        val session = TelnetSession(transport)
        session.start(TelnetListenConfiguration(8000, telnetMode = true))
        transport.next = TelnetTransportBatch(emptyList(), TelnetLinkState.Connected)
        session.synchronize(TelnetState(pendingOutput = listOf(TelnetPacket(1, listOf(255)))))
        assertEquals(listOf(listOf(255)), transport.sent)
    }

    @Test
    fun replacingATelnetPeerDiscardsThePreviousIncompleteIac() {
        val transport = RecordingTransport()
        val session = TelnetSession(transport)
        session.start(TelnetListenConfiguration(8000, telnetMode = true))
        val state = TelnetState(link = TelnetLinkState.Connected)
        transport.next = TelnetTransportBatch(listOf(255), TelnetLinkState.Connected, peerChanged = true)
        assertEquals(emptyList(), session.synchronize(state))
        transport.next = TelnetTransportBatch(listOf(65), TelnetLinkState.Connected, peerChanged = true)
        assertEquals(listOf(TelnetBytesReceived(listOf(65))), session.synchronize(state))
    }

    private class RecordingTransport : TelnetTransport {
        var starts = 0
        var closed = false
        var accepts = true
        val sent = mutableListOf<List<Int>>()
        var next = TelnetTransportBatch(emptyList(), TelnetLinkState.Listening)
        override val localPort: Int? = null
        override fun listen(configuration: TelnetListenConfiguration): TelnetLinkState {
            starts++
            closed = false
            return TelnetLinkState.Listening
        }
        override fun poll(): TelnetTransportBatch = next
        override fun enqueue(bytes: List<Int>): Boolean {
            if (!accepts) return false
            sent += bytes
            return true
        }
        override fun close() { closed = true }
    }
}
