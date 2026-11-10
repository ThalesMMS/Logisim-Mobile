package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message

sealed interface TelnetLinkState {
    data object Stopped : TelnetLinkState
    data object Listening : TelnetLinkState
    data object Connected : TelnetLinkState
    data class Failed(val message: String) : TelnetLinkState
}

data class TelnetBytesReceived(val bytes: List<Int>) : ComponentEvent {
    init { require(bytes.all { it in 0..255 }) }
}
data object TelnetInputCleared : ComponentEvent
data class TelnetLinkChanged(val link: TelnetLinkState) : ComponentEvent
data class TelnetOutputAccepted(val packetId: Long) : ComponentEvent
data class TelnetPacket(val id: Long, val bytes: List<Int>)

/** One remote round enters the simulator atomically and acknowledges its instance sequence. */
class TelnetRuntimeEvents(val sequence: Long, events: List<ComponentEvent>) : ComponentEvent {
    val events: List<ComponentEvent> = events.toList()
    init {
        require(sequence > 0)
        require(events.all { it is TelnetBytesReceived || it == TelnetInputCleared || it is TelnetLinkChanged || it is TelnetOutputAccepted })
    }
}

data class TelnetState(
    val received: List<Int> = emptyList(),
    val pendingOutput: List<TelnetPacket> = emptyList(),
    val nextPacketId: Long = 1,
    val lastClock: LogicValue? = null,
    val link: TelnetLinkState = TelnetLinkState.Stopped,
    val runtimeSequence: Long = 0,
) : ComponentState

internal object TelnetBehavior : ComponentBehavior {
    override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = TelnetState()

    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
        var state = context.state as? TelnetState ?: TelnetState()
        val capacity = context.attributes[ExternalIoComponents.TelnetBuffer]
        val expanded = context.events.flatMap { event ->
            if (event is TelnetRuntimeEvents) {
                if (event.sequence <= state.runtimeSequence) emptyList() else {
                    state = state.copy(runtimeSequence = event.sequence)
                    event.events
                }
            } else listOf(event)
        }
        for (event in expanded) {
            state = when (event) {
                is TelnetBytesReceived -> state.copy(received = (state.received + event.bytes).take(capacity))
                TelnetInputCleared -> state.copy(received = emptyList())
                is TelnetLinkChanged -> state.copy(link = event.link)
                is TelnetOutputAccepted -> state.copy(pendingOutput = state.pendingOutput.filter { it.id > event.packetId })
                else -> state
            }
        }
        val clock = context.inputs["clock"]?.bit(0) ?: LogicValue.UNKNOWN
        val edge = if (context.attributes[ExternalIoComponents.Trigger] == "falling")
            state.lastClock == LogicValue.HIGH && clock == LogicValue.LOW
        else state.lastClock == LogicValue.LOW && clock == LogicValue.HIGH
        val read = context.inputs["read"]?.bit(0) == LogicValue.HIGH
        val write = context.inputs["write"]?.bit(0) == LogicValue.HIGH
        var outputFull = false
        // Evolution sets the one-bit Value.UNKNOWN on the eight-bit port, so only bit 0 is unknown.
        val output = if (read) LogicVector.of(8, (state.received.firstOrNull() ?: 255).toULong())
            else LogicVector.unknown(1).resized(8)
        val available = if (state.received.isEmpty()) LogicVector.low(1) else LogicVector.high(1)
        if (edge) {
            if (write && state.link == TelnetLinkState.Connected) {
                if (state.pendingOutput.size >= capacity) outputFull = true else {
                    val byte = context.inputs["dataIn"]?.toULongOrNull()?.toInt()?.and(255) ?: 255
                    state = state.copy(pendingOutput = state.pendingOutput + TelnetPacket(state.nextPacketId, listOf(byte)),
                        nextPacketId = state.nextPacketId + 1)
                }
            }
            if (read && state.received.isNotEmpty()) state = state.copy(received = state.received.drop(1))
        }
        state = state.copy(lastClock = clock)
        val notices = when (val link = state.link) {
            TelnetLinkState.Stopped -> listOf(message("external_io_telnet_stopped_diagnostic"))
            is TelnetLinkState.Failed -> listOf(message("external_io_telnet_failure_diagnostic", "detail" to link.message))
            else -> emptyList()
        } + if (outputFull) listOf(message("external_io_telnet_buffer_diagnostic")) else emptyList()
        return ComponentEvaluation(mapOf("dataOut" to output, "available" to available), state,
            delay = 0, diagnostics = notices.map { it.resolve(AppLanguage.PORTUGUESE) }, notices = notices)
    }
}

data class TelnetListenConfiguration(
    val port: Int,
    val allowLan: Boolean = false,
    val telnetMode: Boolean = false,
    val maximumOutputBytes: Int = 65_536,
) {
    init { require(port in 0..65535 && maximumOutputBytes in 1..16_777_216) }
}

data class TelnetTransportBatch(val bytes: List<Int>, val link: TelnetLinkState, val peerChanged: Boolean = false)

/** Implementations own sockets. The circuit evaluator never opens or closes a network resource. */
interface TelnetTransport {
    val localPort: Int?
    fun listen(configuration: TelnetListenConfiguration): TelnetLinkState
    fun poll(): TelnetTransportBatch
    fun enqueue(bytes: List<Int>): Boolean
    fun close()
}

/** One controller belongs to one circuit instance and requires an explicit start action. */
class TelnetSession(private val transport: TelnetTransport) {
    private var started = false
    private var telnetMode = false
    private var lastPacketId = 0L
    private var decoder = TelnetDecoder()

    fun start(configuration: TelnetListenConfiguration): TelnetLinkChanged {
        transport.close()
        decoder = TelnetDecoder()
        telnetMode = configuration.telnetMode
        lastPacketId = 0
        val link = transport.listen(configuration)
        started = link !is TelnetLinkState.Failed && link != TelnetLinkState.Stopped
        return TelnetLinkChanged(link)
    }

    fun synchronize(state: TelnetState): List<ComponentEvent> {
        if (!started) return emptyList()
        val events = mutableListOf<ComponentEvent>()
        val batch = transport.poll()
        if (batch.link != state.link) events += TelnetLinkChanged(batch.link)
        if (batch.peerChanged) decoder = TelnetDecoder()
        val bytes = if (telnetMode) decoder.decode(batch.bytes) else batch.bytes
        if (bytes.isNotEmpty()) events += TelnetBytesReceived(bytes)
        if (batch.link == TelnetLinkState.Connected) {
            for (packet in state.pendingOutput.filter { it.id > lastPacketId }) {
                val accepted = transport.enqueue(packet.bytes)
                if (!accepted) {
                    events += TelnetLinkChanged(TelnetLinkState.Failed("O buffer de saída está cheio."))
                    break
                }
                lastPacketId = packet.id
            }
            if (lastPacketId > 0 && state.pendingOutput.any { it.id <= lastPacketId }) events += TelnetOutputAccepted(lastPacketId)
        }
        return events
    }

    fun stop(): TelnetLinkChanged {
        transport.close()
        started = false
        return TelnetLinkChanged(TelnetLinkState.Stopped)
    }
}

/** Evolution consumes exactly two wire bytes after IAC, including across socket reads. */
internal class TelnetDecoder {
    private var remainingCommandBytes = 0

    fun decode(bytes: List<Int>): List<Int> = buildList {
        for (byte in bytes) {
            when {
                remainingCommandBytes > 0 -> remainingCommandBytes--
                byte == 255 -> remainingCommandBytes = 2
                else -> add(byte)
            }
        }
    }
}
