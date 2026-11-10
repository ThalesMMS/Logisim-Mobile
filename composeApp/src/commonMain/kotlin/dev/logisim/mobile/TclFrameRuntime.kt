package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.TclComponentEvent
import dev.logisim.mobile.core.TclComponents
import dev.logisim.mobile.core.TclExecutionClient
import dev.logisim.mobile.core.TclRuntimePhase
import dev.logisim.mobile.core.TclRuntimeState
import dev.logisim.mobile.core.TclSourceFile
import dev.logisim.mobile.core.TclStepRequest
import dev.logisim.mobile.core.validateTclSourceFiles
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class TclFrameRuntime(
    private val client: TclExecutionClient?,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private data class Target(
        val identity: Long,
        val circuitId: String,
        val path: String,
        val id: String,
        val kind: ComponentKind,
        val content: String?,
        val filePath: String?,
    ) {
        val eventKey: String get() = if (path.isEmpty()) id else "$path/$id"

        fun component(context: EditorToolContext): CircuitComponent? {
            if (identity != context.documentIdentity) return null
            var circuit = context.project.mainCircuit
            if (path.isNotEmpty()) for (part in path.split('/')) {
                val instance = circuit.components.firstOrNull { it.id == part && it.kind == ComponentKind.SUBCIRCUIT }
                circuit = context.project.circuit(instance?.circuitName ?: return null) ?: return null
            }
            if (circuit.id != circuitId) return null
            return circuit.components.firstOrNull {
                it.id == id && it.kind == kind && it.attributes["content"] == content && it.attributes["filePath"] == filePath
            }
        }

        fun state(context: EditorToolContext): TclRuntimeState? =
            (if (path.isEmpty()) context.projectSimulation else context.projectSimulation.circuitStates[path])
                ?.componentStates?.get(id) as? TclRuntimeState
    }

    private class QueuedTick(val event: TclComponentEvent.ApplyTick, val cancel: () -> Unit)

    private class Approval(
        val source: TclSourceFile,
        val includes: List<TclSourceFile>,
        val id: String,
        var automaticTickAuthorization: String?,
    ) {
        var seen = false
        var submittedFrames = 0
        var work: Job? = null
        var reply: TclComponentEvent? = null
        var queuedTick: QueuedTick? = null

        fun cancelTick() {
            queuedTick?.cancel?.invoke()
            queuedTick = null
        }

        fun cancel() {
            cancelTick()
            work?.cancel()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val approvals = mutableMapOf<Target, Approval>()
    private val session = Random.nextLong().toULong().toString(16)
    private var nextId = 0L
    var revision by mutableStateOf(0L)
        private set

    private fun target(context: EditorToolContext, component: CircuitComponent) =
        Target(context.documentIdentity, context.document.id, context.simulation.instancePath, component.id,
            component.kind, component.attributes["content"], component.attributes["filePath"])

    fun source(context: EditorToolContext, component: CircuitComponent): TclSourceFile? =
        approvals[target(context, component)]?.source

    fun includes(context: EditorToolContext, component: CircuitComponent): List<TclSourceFile> =
        approvals[target(context, component)]?.includes.orEmpty()

    fun automaticTickAuthorization(context: EditorToolContext, component: CircuitComponent): String? =
        approvals[target(context, component)]?.automaticTickAuthorization

    fun revokeAutomaticTicks(context: EditorToolContext, component: CircuitComponent) {
        val target = target(context, component)
        val approval = approvals[target] ?: return
        val authorization = approval.automaticTickAuthorization ?: return
        approval.automaticTickAuthorization = null
        approval.cancelTick()
        send(context, target, TclComponentEvent.RevokeAutomaticTicks(authorization))
        revision++
    }

    fun approve(
        context: EditorToolContext,
        component: CircuitComponent,
        source: TclSourceFile,
        includes: List<TclSourceFile> = emptyList(),
        automaticTickAuthorization: String? = null,
    ) {
        validateTclSourceFiles(source, includes)
        val target = target(context, component)
        requireLocalized(TclComponents.interfaceFor(component).hasDesktopClock) { message(Messages.TclErrorClock) }
        checkLocalized(client?.available == true && target.component(context) != null) { message(Messages.TclErrorInstanceChanged) }
        val approval = Approval(source, includes.toList(), "tcl-$session-${++nextId}", automaticTickAuthorization)
        val enable = TclComponentEvent.Enable(source.sha256, automaticTickAuthorization, approval.id)
        checkLocalized(send(context, target, enable)) { message(Messages.TclErrorInstanceChanged) }
        approvals.put(target, approval)?.cancel()
        revision++
    }

    fun stop(context: EditorToolContext, component: CircuitComponent) {
        val target = target(context, component)
        approvals.remove(target)?.cancel()
        send(context, target, TclComponentEvent.Disable)
        revision++
    }

    fun close() {
        approvals.values.forEach(Approval::cancel)
        scope.cancel()
        approvals.clear()
    }

    fun synchronize(context: EditorToolContext) {
        for ((target, approval) in approvals.toMap()) {
            val component = target.component(context)
            val state = target.state(context)
            if (component == null || approval.seen && (state == null || state.approvalId != approval.id || !state.enabled)) {
                approvals.remove(target)
                approval.cancel()
                continue
            }
            if (state == null || state.approvalId != approval.id || !state.enabled) continue
            approval.seen = true
            if (!context.isForeground) continue
            val reply = approval.reply
            if (reply != null) {
                if (reply is TclComponentEvent.Response && state.completedFrameCount >= approval.submittedFrames) {
                    approval.reply = null
                } else {
                    // Queue acceptance is provisional. Retain the reply until simulation publishes it.
                    if (!context.isComponentEventPending(reply)) send(context, target, reply)
                    continue
                }
            }
            if (queueAutomaticTick(context, target, approval, state)) continue
            if (state.phase == TclRuntimePhase.WAITING && state.completedFrameCount >= approval.submittedFrames) {
                submit(target, component, approval, state)
            }
        }
    }

    private fun queueAutomaticTick(
        context: EditorToolContext, target: Target, approval: Approval, state: TclRuntimeState,
    ): Boolean {
        val authorization = approval.automaticTickAuthorization ?: return false
        if (approval.queuedTick?.let { context.isComponentEventPending(it.event) } == true) return true
        approval.queuedTick = null
        val request = state.tickRequest(authorization)
        if (!request.matches(state) || !send(context, target, request)) return false
        approval.queuedTick = QueuedTick(request) { context.cancelComponentEvent(request) }
        return true
    }

    private fun submit(target: Target, component: CircuitComponent, approval: Approval, state: TclRuntimeState) {
        val host = client ?: return
        val count = state.completedFrameCount + 1
        val generation = state.generation - (state.frames.size - count)
        approval.submittedFrames = count
        approval.work = scope.launch {
            val event = try {
                val request = TclStepRequest("${approval.id}-$count", approval.source, TclComponents.interfaceFor(component),
                    state.frames.take(count), approval.includes)
                val reply = host.step(request)
                requireLocalized(reply.sourceSha256 == approval.source.sha256 && reply.steps.size == count) {
                    message(Messages.TclErrorSourceReply)
                }
                TclComponentEvent.Response(generation, approval.source.sha256, reply.outputs, reply.tickRequestFrames, approval.id, count)
            } catch (failure: Exception) {
                if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
                val notice = failure.localizedDescription(message(Messages.TclErrorExecution))
                TclComponentEvent.Failure(generation, failure.message.orEmpty(), notice, approval.id)
            }
            if (approvals[target] === approval) {
                approval.reply = event
                revision++
            }
        }
    }

    private fun send(context: EditorToolContext, target: Target, event: TclComponentEvent): Boolean =
        context.injectEvents(mapOf(target.eventKey to listOf(event)))
}
