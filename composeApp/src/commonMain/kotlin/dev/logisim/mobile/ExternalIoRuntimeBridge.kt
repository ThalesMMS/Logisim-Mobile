package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ExternalIoInstanceKey(
    val documentIdentity: Long,
    val circuitId: String,
    val instancePath: String,
    val componentId: String,
)

internal fun externalIoInstanceKey(context: EditorToolContext, component: CircuitComponent): ExternalIoInstanceKey? {
    if (context.simulation.instancePath.isEmpty() && context.document.id != context.project.mainCircuit.id) return null
    return ExternalIoInstanceKey(context.documentIdentity, context.document.id, context.simulation.instancePath, component.id)
}

internal data class ExternalTelnetSettings(val configuration: TelnetListenConfiguration, val bufferSize: Int)

@Composable
internal fun externalIoTelnetEntry(
    bridge: ExternalIoRuntimeBridge,
    key: ExternalIoInstanceKey,
    state: TelnetState,
    sender: (String, ComponentEvent) -> Boolean,
): ExternalIoTelnetEntry = bridge.entry(key, state, sender)

internal fun externalTelnetSettings(component: CircuitComponent, allowLan: Boolean): ExternalTelnetSettings =
    ExternalTelnetSettings(TelnetListenConfiguration(component.attributeInt("port", 8000), allowLan,
        component.attributes["telnetMode"]?.toBooleanStrictOrNull() ?: false), component.attributeInt("buflen", 1024))

internal class ExternalIoTelnetEntry(
    val key: ExternalIoInstanceKey,
    val sender: (String, ComponentEvent) -> Boolean,
    initialSequence: Long,
) {
    var allowLan by mutableStateOf(false)
    var pending by mutableStateOf<CompanionTelnetStartPlan?>(null)
    var status by mutableStateOf<CompanionTelnetStatus?>(null)
    var notice by mutableStateOf<LocalizedText?>(null)
    var busy by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var startAttempted by mutableStateOf(false)
    var cleanupPending by mutableStateOf(false)
    var gateway: CompanionTelnetClient? = null
    var settings: ExternalTelnetSettings? = null
    var resizingTo: Int? = null
    var issuedSequence = initialSequence
    var observedSequence = initialSequence
    var outbound: TelnetPacket? = null
    var pendingSince: TimeMark? = null
    val mutex = Mutex()
}

/** A scene owns this bridge. Only the confirmation action can create a remote listener. */
class ExternalIoRuntimeBridge(private val companion: () -> CompanionClient?) {
    internal val entries = mutableStateMapOf<ExternalIoInstanceKey, ExternalIoTelnetEntry>()
    internal var ready by mutableStateOf(false)
        private set
    private var scope: CoroutineScope? = null
    private var activeClient: CompanionClient? = null
    private var lastIssuedSequence = 0L
    private val runtimeMutex = Mutex()

    internal fun entry(key: ExternalIoInstanceKey, state: TelnetState, sender: (String, ComponentEvent) -> Boolean): ExternalIoTelnetEntry =
        entries.getOrPut(key) { ExternalIoTelnetEntry(key, sender, state.runtimeSequence) }

    @Composable
    fun Runtime(context: EditorToolContext) {
        val latest by rememberUpdatedState(context)
        val client = companion()
        LaunchedEffect(this, client, context.documentIdentity, context.isForeground) {
            runtimeMutex.withLock {
                if (!context.isForeground) return@withLock
                activeClient = client
                scope = this
                withTimeoutOrNull(5_000) { retryOutstandingCleanup() }
                ready = client != null
                try {
                    while (isActive) {
                        synchronize(latest)
                        delay(100)
                    }
                } finally {
                    ready = false
                    scope = null
                    withContext(NonCancellable) {
                        val closed = withTimeoutOrNull(5_000) { closeAll() } == true
                        if (!closed) latest.showNotice(message("external_io_cleanup_failed"))
                    }
                    entries.keys.toList().filter { entries[it]?.cleanupPending != true }.forEach { entries.remove(it) }
                    activeClient = null
                }
            }
        }
    }

    internal fun prepare(entry: ExternalIoTelnetEntry, component: CircuitComponent) = perform(entry) {
        val client = activeClient ?: throw LocalizedArgumentException(message("external_io_telnet_pair"))
        val settings = externalTelnetSettings(component, entry.allowLan)
        val gateway = CompanionTelnetClient(client)
        val nonce = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        entry.pending = gateway.prepareStart(settings.configuration, settings.bufferSize, "telnet-$nonce")
        entry.gateway = gateway
        entry.settings = settings
        entry.startAttempted = false
    }

    internal fun confirm(entry: ExternalIoTelnetEntry) = perform(entry) {
        val gateway = entry.gateway ?: return@perform
        val status = resolveStart(entry) ?: return@perform
        entry.paused = false
        if (!publish(entry, listOf(TelnetLinkChanged(status.link)))) {
            gateway.stop(status.sessionId)
            entry.status = null
            entry.notice = message("external_io_event_rejected")
        }
    }

    internal fun stop(entry: ExternalIoTelnetEntry) = perform(entry) {
        stopEntry(entry)
        entry.notice = message("external_io_telnet_stopped_notice")
    }

    internal fun resume(entry: ExternalIoTelnetEntry) {
        entry.paused = false
        entry.notice = null
    }

    internal fun retryCleanup() {
        scope?.launch { withTimeoutOrNull(5_000) { retryOutstandingCleanup() } }
    }

    internal suspend fun retryOutstandingCleanup() {
        for (entry in entries.values.toList().filter { it.cleanupPending }) {
            try {
                entry.mutex.withLock { stopEntry(entry) }
                entries.remove(entry.key)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { entry.notice = message("external_io_cleanup_failed") }
        }
    }

    private fun perform(entry: ExternalIoTelnetEntry, action: suspend () -> Unit) {
        val hostScope = scope ?: return
        if (entry.busy) return
        entry.busy = true
        entry.notice = null
        hostScope.launch {
            var locked = false
            try {
                entry.mutex.lock()
                locked = true
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.notice = externalIoFailure(failure)
            } finally {
                if (locked) entry.mutex.unlock()
                entry.busy = false
            }
        }
    }

    internal suspend fun synchronize(context: EditorToolContext) {
        for (entry in entries.values.toList()) {
            val resourceMayExist = entry.status != null || (entry.startAttempted && entry.pending != null)
            if (!resourceMayExist || entry.busy || entry.cleanupPending || !entry.mutex.tryLock()) continue
            try {
                val component = externalIoComponent(context, entry.key)
                val settings = entry.settings
                val requested = component?.takeIf { it.kind == ExternalIoComponents.Telnet }
                    ?.let { externalTelnetSettings(it, entry.allowLan) }
                if (settings == null || requested == null || requested.configuration != settings.configuration ||
                    (entry.status == null && requested.bufferSize != settings.bufferSize)) {
                    stopEntry(entry)
                    entry.notice = message("external_io_telnet_config_changed")
                    continue
                }
                val state = externalIoState(context, entry.key) as? TelnetState ?: continue
                if (state.runtimeSequence < entry.observedSequence) {
                    stopEntry(entry)
                    entry.notice = message("external_io_simulation_reset")
                    continue
                }
                entry.observedSequence = state.runtimeSequence
                // Pausing remote polls must not pause ownership checks or uncertain-start cleanup.
                if (entry.status == null || entry.paused) continue
                // Do not drain another remote input batch until this instance applied the last one.
                if (state.runtimeSequence < entry.issuedSequence) {
                    if ((entry.pendingSince?.elapsedNow()?.inWholeMilliseconds ?: 0L) >= 5_000) {
                        stopEntry(entry)
                        entry.notice = message("external_io_event_timeout")
                    }
                    continue
                }
                entry.pendingSince = null
                val gateway = entry.gateway ?: continue
                val status = entry.status ?: continue
                if (entry.resizingTo != null || requested.bufferSize != settings.bufferSize) {
                    resizeInput(entry, gateway, status.sessionId, requested.bufferSize)
                    continue
                }
                val maximum = (settings.bufferSize - state.received.size).coerceIn(0, 4096)
                val batch = gateway.poll(status.sessionId, maximum)
                entry.status = batch.status
                val events = batch.events(state).toMutableList()
                try {
                    if (batch.status.link == TelnetLinkState.Connected) {
                        val packet = entry.outbound ?: state.pendingOutput.takeWhileBytes(65_536)?.also { entry.outbound = it }
                        if (packet != null) {
                            try {
                                events += gateway.send(status.sessionId, packet)
                                entry.outbound = null
                            } catch (failure: CompanionProtocolException) {
                                if (failure.httpStatus != null || failure.code != "not-connected") throw failure
                                // A peer can disconnect between poll and send. The next poll
                                // observes the listener again and the packet remains pending.
                            }
                        }
                    }
                } finally {
                    if (events.isNotEmpty() && !publish(entry, events)) {
                        stopEntry(entry)
                        entry.notice = message("external_io_event_rejected")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.paused = true
                entry.notice = externalIoFailure(failure)
            } finally {
                entry.mutex.unlock()
            }
        }
    }

    private suspend fun resizeInput(entry: ExternalIoTelnetEntry, gateway: CompanionTelnetClient, sessionId: String, size: Int) {
        // Retry the original request if the result was lost, even if the property changed again.
        val target = entry.resizingTo ?: size.also { entry.resizingTo = it }
        val status = try {
            gateway.resizeInput(sessionId, target, entry.issuedSequence + 1)
        } catch (failure: CompanionProtocolException) {
            if (failure.code in setOf("invalid-parameter", "resource-limit", "unknown-operation", "operation-disabled")) {
                entry.resizingTo = null
            }
            throw failure
        }
        entry.resizingTo = null
        entry.settings = entry.settings?.copy(bufferSize = target)
        entry.status = status
        if (!publish(entry, listOf(TelnetInputCleared, TelnetLinkChanged(status.link)))) {
            stopEntry(entry)
            entry.notice = message("external_io_event_rejected")
        }
    }

    private fun publish(entry: ExternalIoTelnetEntry, events: List<ComponentEvent>): Boolean {
        val next = maxOf(lastIssuedSequence, entry.issuedSequence) + 1
        if (!entry.sender(entry.key.componentId, TelnetRuntimeEvents(next, events))) return false
        lastIssuedSequence = next
        entry.issuedSequence = next
        entry.pendingSince = TimeSource.Monotonic.markNow()
        return true
    }

    internal suspend fun resolveStart(entry: ExternalIoTelnetEntry): CompanionTelnetStatus? {
        val pending = entry.pending ?: return null
        val gateway = entry.gateway ?: return null
        entry.startAttempted = true
        return try {
            gateway.confirmStart(pending).also { entry.status = it }
        } catch (failure: CompanionProtocolException) {
            // These terminal job errors come from validation or bind, before the host opens a listener.
            // Keep the recovery identity for transport failures and ambiguous job errors.
            if (failure.httpStatus != null || failure.code !in setOf("listen-failed", "lan-disabled", "resource-limit", "invalid-parameter")) {
                throw failure
            }
            entry.pending = null
            entry.startAttempted = false
            entry.notice = externalIoFailure(failure)
            null
        }
    }

    internal suspend fun stopEntry(entry: ExternalIoTelnetEntry) {
        // A confirmed start may have succeeded before its response was lost.
        // Resolve that same request before discarding the only recovery identifier.
        val status = entry.status ?: if (entry.startAttempted) resolveStart(entry) else null
        if (status != null) entry.gateway?.stop(status.sessionId)
        if (status != null) publish(entry, listOf(TelnetLinkChanged(TelnetLinkState.Stopped)))
        entry.status = null
        entry.pending = null
        entry.startAttempted = false
        entry.outbound = null
        entry.resizingTo = null
        entry.paused = false
        entry.cleanupPending = false
    }

    internal suspend fun closeAll(): Boolean {
        val closing = entries.values.toList()
        closing.forEach { it.cleanupPending = true }
        var closed = true
        for (entry in closing) {
            try { entry.mutex.withLock { stopEntry(entry) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { closed = false }
        }
        return closed
    }
}

private fun List<TelnetPacket>.takeWhileBytes(maximum: Int): TelnetPacket? {
    if (isEmpty()) return null
    val bytes = mutableListOf<Int>()
    var lastId = 0L
    for (packet in this) {
        if (bytes.size + packet.bytes.size > maximum) break
        bytes += packet.bytes
        lastId = packet.id
    }
    return if (lastId == 0L) null else TelnetPacket(lastId, bytes)
}

internal fun externalIoComponent(context: EditorToolContext, key: ExternalIoInstanceKey): CircuitComponent? {
    if (key.documentIdentity != context.documentIdentity) return null
    var circuit = context.project.mainCircuit
    if (key.instancePath.isNotEmpty()) for (id in key.instancePath.split('/')) {
        val instance = circuit.components.singleOrNull { it.id == id } ?: return null
        circuit = context.project.circuit(instance.circuitName ?: return null) ?: return null
    }
    if (circuit.id != key.circuitId) return null
    return circuit.components.singleOrNull { it.id == key.componentId }
}

internal fun externalIoState(context: EditorToolContext, key: ExternalIoInstanceKey): ComponentState? {
    if (externalIoComponent(context, key) == null) return null
    val simulation = if (key.instancePath.isEmpty()) context.projectSimulation
        else context.projectSimulation.circuitStates[key.instancePath] ?: return null
    return simulation.componentStates[key.componentId]
}

internal fun externalIoFailure(failure: Exception): LocalizedText = when (failure) {
    is LocalizedArgumentException -> failure.text
    is CompanionProtocolException -> message("external_io_protocol_error", "code" to failure.code, "detail" to failure.message)
    is CompanionTransportException -> message("external_io_transport_error", "code" to failure.code)
    else -> message("external_io_action_error", "detail" to (failure.message ?: failure::class.simpleName.orEmpty()))
}
