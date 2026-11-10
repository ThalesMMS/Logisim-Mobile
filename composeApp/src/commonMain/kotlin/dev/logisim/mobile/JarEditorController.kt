package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.CompanionApproval
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionJarLiveSession
import dev.logisim.mobile.core.CompanionJobRequest
import dev.logisim.mobile.core.CompanionJsonBoolean
import dev.logisim.mobile.core.CompanionPlan
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.CompanionInput
import dev.logisim.mobile.core.readJarCircuitInputs
import dev.logisim.mobile.core.JarEditorGeometry
import dev.logisim.mobile.core.JarLiveSample
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class JarEditorLaunch(
    val client: CompanionClient,
    val request: CompanionJobRequest,
    val plan: CompanionPlan,
    val approval: CompanionApproval,
    val xml: String,
    val initial: Map<String, LogicVector>,
)

/** Input gestures are applied to both engines in the same order, with one response in flight. */
internal class JarEditorController private constructor(
    private val worker: CompanionJarLiveSession,
    val geometry: JarEditorGeometry,
    private val isCurrent: () -> Boolean,
    private val onFailure: (LocalizedText) -> Unit,
    private val onClosed: () -> Unit,
) {
    private data class Intent(val pinId: String, val value: ULong?)

    private val pins = geometry.project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.id }
    private val options = SimulationProjectOptions.read(geometry.project)
    private val simulator = CircuitSimulator(propagationLimit = options.propagationLimit, registry = geometry.registry)
    private val events = Channel<Intent>(32)
    private var active = true
    private var processing: Job? = null
    private val simulation = MutableStateFlow(SimulationState())
    val state = simulation.asStateFlow()

    fun input(pinId: String, value: ULong? = null): Boolean =
        active && isCurrent() && pinId in pins && events.trySend(Intent(pinId, value)).isSuccess

    fun stop() {
        if (!active) return
        active = false
        events.close()
        processing?.cancel()
    }

    private fun prepare(previous: SimulationState, vectors: Map<String, LogicVector>, revision: Long): SimulationState =
        simulator.settle(geometry.project, previous, vectorInputs = vectors,
            events = geometry.inputRevisionEvents(geometry.project, revision), options = options.asMap())

    private fun applyReply(previous: SimulationState, revision: Long, reply: JarLiveSample): SimulationState {
        check(active && isCurrent()) { "The approved JAR project is no longer current." }
        return simulator.settle(geometry.project, previous, events = geometry.outputEvents(geometry.project, previous, revision, reply),
            options = options.asMap())
    }

    private fun run(scope: CoroutineScope) {
        processing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val keepAlive = launch {
                try {
                    while (true) {
                        delay(1_500)
                        if (!active || !isCurrent()) {
                            stop()
                            break
                        }
                        withTimeout(3_000) { worker.renew() }
                    }
                } catch (cancelled: CancellationException) {
                    if (!isActive || cancelled !is TimeoutCancellationException) throw cancelled
                    onFailure(message("jar_tool_failed"))
                    stop()
                } catch (error: Exception) {
                    onFailure(error.localizedDescription(message("jar_tool_failed")))
                    stop()
                }
            }
            var revision = 0L
            try {
                for (intent in events) {
                    if (!active || !isCurrent()) break
                    val pin = pins.getValue(intent.pinId)
                    val previous = simulation.value
                    val old = previous.inputVectors.getValue(pin.id)
                    val next = intent.value ?: if (pin.width == 1) {
                        if (old.bit(0) == LogicValue.HIGH) 0uL else 1uL
                    } else (old.toULongOrNull() ?: 0uL) + 1uL
                    val value = LogicVector.of(pin.width, next)
                    if (old == value) continue
                    val inputs = previous.inputVectors + (pin.id to value)
                    val updated = prepare(previous, inputs, ++revision)
                    simulation.value = updated
                    val reply = worker.advanceEditorFrame(geometry, geometry.project, updated, revision)
                    if (!active || !isCurrent()) break
                    simulation.value = applyReply(updated, revision, reply)
                }
            } catch (cancelled: CancellationException) {
                if (!isActive || cancelled !is TimeoutCancellationException) throw cancelled
                onFailure(message("jar_tool_failed"))
            } catch (error: Exception) {
                onFailure(error.localizedDescription(message("jar_tool_failed")))
            } finally {
                active = false
                events.close()
                keepAlive.cancel()
                withContext(NonCancellable) {
                    try {
                        simulation.value = simulator.settle(geometry.project, simulation.value, reset = true, options = options.asMap())
                    } finally {
                        // cancel closes the session locally before the transport call.
                        runCatching { withTimeout(3_000) { worker.cancel() } }
                        onClosed()
                    }
                }
            }
        }
    }

    companion object {
        suspend fun open(scope: CoroutineScope, client: CompanionClient, request: CompanionJobRequest,
                         plan: CompanionPlan, approval: CompanionApproval, xml: String,
                         baseRegistry: ComponentRegistry, initial: Map<String, LogicVector>,
                         isCurrent: () -> Boolean, onFailure: (LocalizedText) -> Unit,
                         onClosed: () -> Unit): JarEditorController {
            check(scope.isActive && isCurrent() && request.parameters["driverTrace"] == CompanionJsonBoolean(true))
            val approvedProject = request.inputs.single { it.name == "project.circ" }
            require(approvedProject.sha256 == CompanionInput.fromBytes("project.circ", xml.encodeToByteArray()).sha256)
            require(readJarCircuitInputs(xml).associate { it.label to it.width } == initial.mapValues { it.value.width })
            val worker = withContext(NonCancellable) {
                withTimeout(5_000) { CompanionJarLiveSession.start(client, request, plan, approval) }
            }
            var installed = false
            try {
                check(scope.isActive && isCurrent())
                val first = worker.advance(0, initial)
                val geometry = worker.resolveEditorGeometry(xml, first, baseRegistry)
                val controller = JarEditorController(worker, geometry, isCurrent, onFailure, onClosed)
                val vectors = controller.pins.mapValues { (_, pin) -> initial.getValue(pin.label) }
                val prepared = controller.prepare(SimulationState(), vectors, revision = 0)
                controller.simulation.value = controller.applyReply(prepared, revision = 0, first)
                check(scope.isActive && isCurrent())
                controller.run(scope)
                installed = true
                return controller
            } finally {
                if (!installed) withContext(NonCancellable) { withTimeoutOrNull(3_000) { runCatching { worker.cancel() } } }
            }
        }
    }
}
