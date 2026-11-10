package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** The adapter increments epoch for each start, including after reset or source edits. */
data class CompanionHdlStarted(val sessionId: String, val epoch: Long) : ComponentEvent {
    init { requireLocalized(sessionId.isNotBlank() && epoch > 0) { message(Messages.HdlIpInvalidStart) } }
}

/** One mobile propagation unit equals one nanosecond in the companion trace. */
data class CompanionHdlRequest(
    val sessionId: String,
    val epoch: Long,
    val sequence: Long,
    val timeNs: Long,
    val inputs: Map<String, LogicVector>,
    val inputRevision: Long? = null,
) : ComponentEvent

data class CompanionHdlReply(
    val sessionId: String,
    val epoch: Long,
    val sequence: Long,
    val timeNs: Long,
    val outputs: Map<String, LogicVector>,
) : ComponentEvent

data class CompanionHdlFailed(
    val sessionId: String,
    val epoch: Long,
    val sequence: Long,
    val notice: LocalizedText,
) : ComponentEvent {
    constructor(sessionId: String, epoch: Long, sequence: Long, message: String) :
        this(sessionId, epoch, sequence, LocalizedText.literal(message.take(4096)))
    val message: String get() = notice.resolve(AppLanguage.ENGLISH)
}

data class CompanionHdlStopped(val sessionId: String, val epoch: Long) : ComponentEvent

data class CompanionHdlUnavailable(val epoch: Long, val notice: LocalizedText) : ComponentEvent {
    constructor(epoch: Long, message: String) : this(epoch, LocalizedText.literal(message.take(4096)))
    val message: String get() = notice.resolve(AppLanguage.ENGLISH)
}

enum class HdlIpStatus { UNAVAILABLE, READY, PENDING, FAILED }

private const val HDL_INPUT_HISTORY_LIMIT = 256

data class HdlIpInputSample(val revision: Long, val inputs: Map<String, LogicVector>)

data class HdlIpState(
    val sourceKey: String,
    val epoch: Long = 0,
    val sessionId: String? = null,
    val sequence: Long = -1,
    val timeNs: Long = 0,
    val requestedTimeNs: Long? = null,
    val inputs: Map<String, LogicVector> = emptyMap(),
    val outputs: Map<String, LogicVector> = emptyMap(),
    val status: HdlIpStatus = HdlIpStatus.UNAVAILABLE,
    val failureNotice: LocalizedText? = null,
    val sampleInputs: Map<String, LogicVector> = emptyMap(),
    val inputRevision: Long = 0,
    val inputHistory: List<HdlIpInputSample> = emptyList(),
) : ComponentState {
    val failure: String? get() = failureNotice?.resolve(AppLanguage.ENGLISH)
}

/** No compiler, network operation or process can run from this evaluator. */
object HdlIpBehavior : ComponentBehavior {
    override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
        HdlIpState(sourceKey(component))

    override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
        HdlIpState(sourceKey(component), epoch = ((state as? HdlIpState)?.epoch ?: 0) + 1)

    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
        val design = HdlIpLibrary.parse(context.component)
        val outputPorts = design.ports.filter { it.direction != PortDirection.INPUT }
        val inputPorts = design.ports.filter { it.direction != PortDirection.OUTPUT }
        val key = sourceKey(context.component)
        val old = context.state as? HdlIpState
        var state = if (old == null) HdlIpState(key) else if (old.sourceKey == key) old
            else HdlIpState(key, epoch = old.epoch + 1)
        val notices = design.diagnostics.map { message(Messages.HdlIpDiagnostic, "code" to it.code, "detail" to it.notice) }.toMutableList()
        val currentInputs = inputPorts.associate { it.name to (context.inputs[it.name] ?: LogicVector.unknown(it.width)) }
        state = observeInputs(state, currentInputs)
        if (state.status == HdlIpStatus.PENDING && state.inputs != currentInputs) {
            state = state.copy(requestedTimeNs = null, outputs = emptyMap())
        }
        if (!design.isExecutable) {
            state = state.copy(sessionId = null, outputs = emptyMap(), status = HdlIpStatus.FAILED,
                requestedTimeNs = null, failureNotice = message(Messages.HdlIpUnexecutable))
        } else {
            for (event in context.events) when (event) {
                is CompanionHdlStarted -> if (event.epoch > state.epoch) {
                    state = observeInputs(HdlIpState(key, epoch = event.epoch, sessionId = event.sessionId,
                        status = HdlIpStatus.READY), currentInputs)
                }
                is CompanionHdlRequest -> if (matches(state, event.sessionId, event.epoch) && event.sequence > state.sequence &&
                    (event.inputRevision == null || event.inputRevision == state.inputRevision) &&
                    event.timeNs > (state.requestedTimeNs ?: state.timeNs) && event.inputs == currentInputs && validValues(event.inputs, inputPorts)) {
                    state = state.copy(sequence = event.sequence, requestedTimeNs = event.timeNs,
                        inputs = event.inputs.toMap(), outputs = emptyMap(), status = HdlIpStatus.PENDING, failureNotice = null)
                }
                is CompanionHdlReply -> if (matches(state, event.sessionId, event.epoch) && state.status == HdlIpStatus.PENDING &&
                    event.sequence == state.sequence && event.timeNs == state.requestedTimeNs && state.inputs == currentInputs) {
                    state = if (validValues(event.outputs, outputPorts)) state.copy(timeNs = event.timeNs,
                        requestedTimeNs = null, outputs = event.outputs.toMap(), status = HdlIpStatus.READY)
                    else state.copy(requestedTimeNs = null, outputs = emptyMap(), status = HdlIpStatus.FAILED,
                        failureNotice = message(Messages.HdlIpIncompatibleReply))
                }
                is CompanionHdlFailed -> if (matches(state, event.sessionId, event.epoch) && event.sequence == state.sequence) {
                    state = state.copy(requestedTimeNs = null, outputs = emptyMap(), status = HdlIpStatus.FAILED,
                        failureNotice = event.notice.takeUnless { it.resolve().isBlank() } ?: message(Messages.HdlIpFailed))
                }
                is CompanionHdlStopped -> if (matches(state, event.sessionId, event.epoch)) {
                    state = HdlIpState(key, epoch = state.epoch + 1)
                }
                is CompanionHdlUnavailable -> if (event.epoch > state.epoch) {
                    state = HdlIpState(key, epoch = event.epoch, status = HdlIpStatus.FAILED,
                        failureNotice = event.notice.takeUnless { it.resolve().isBlank() } ?: message(Messages.HdlIpUnavailable))
                }
                else -> Unit
            }
            if (state.inputs != currentInputs && state.outputs.isNotEmpty()) {
                state = state.copy(outputs = emptyMap(), requestedTimeNs = null, status = HdlIpStatus.PENDING)
            }
        }
        state.failureNotice?.let(notices::add)
        if (state.status == HdlIpStatus.UNAVAILABLE) notices += message(Messages.HdlIpDisconnected)
        if (state.status == HdlIpStatus.PENDING) notices += message(Messages.HdlIpPending)
        val outputs = outputPorts.associate { port -> port.name to when (state.status) {
            HdlIpStatus.FAILED -> LogicVector.error(port.width)
            HdlIpStatus.READY -> state.outputs[port.name] ?: LogicVector.unknown(port.width)
            else -> LogicVector.unknown(port.width)
        } }
        val delay = if (state.status == HdlIpStatus.READY) (state.timeNs - context.eventTime).coerceAtLeast(0) else 0
        // Input or session changes invalidate a previously scheduled future sample.
        return ComponentEvaluation(outputs = outputs,
            state = observeInputs(state, currentInputs), delay = delay,
            diagnostics = notices.map { it.resolve(AppLanguage.ENGLISH) }, notices = notices,
            outputGeneration = "${state.epoch}:${state.sequence}:${state.status}")
    }

    // The editor can observe several simulator evaluations as a single state update.
    // Keep a bounded, session-local history and let the consumer detect revision gaps.
    private fun observeInputs(state: HdlIpState, inputs: Map<String, LogicVector>): HdlIpState {
        if (state.sampleInputs == inputs) return state
        val revision = state.inputRevision + 1
        val history = if (state.sessionId == null) emptyList()
            else state.inputHistory.takeLast(HDL_INPUT_HISTORY_LIMIT - 1) + HdlIpInputSample(revision, inputs)
        return state.copy(sampleInputs = inputs, inputRevision = revision, inputHistory = history)
    }

    private fun matches(state: HdlIpState, sessionId: String, epoch: Long) =
        state.sessionId == sessionId && state.epoch == epoch

    private fun validValues(values: Map<String, LogicVector>, ports: List<HdlIpPort>) =
        values.keys == ports.map { it.name }.toSet() && ports.all { values[it.name]?.width == it.width }

    private fun sourceKey(component: CircuitComponent): String = buildString {
        append(component.kind.name)
        for ((name, value) in component.attributes.filterKeys { it == "content" || it.startsWith("generic.") || it.startsWith("vhdl_") }.entries.sortedBy { it.key }) {
            append('|'); append(name.length); append(':'); append(name); append(value.length); append(':'); append(value)
        }
    }
}
