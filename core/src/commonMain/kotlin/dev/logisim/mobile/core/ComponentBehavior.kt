package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

/** State and events belong to one runtime instance and are never serialized into a circuit. */
interface ComponentState

/** A stateful driver may release terminals independently of its four-state logic values. */
internal interface ComponentOutputReleases : ComponentState {
    val releasedOutputs: Set<String>
}
object EmptyComponentState : ComponentState
interface ComponentEvent

/** Session replies may carry a path and must be evaluated only at that address. */
internal interface AddressedComponentEvent : ComponentEvent {
    val eventAddress: String
}

data class ComponentEvaluationContext(
    val component: CircuitComponent,
    val attributes: AttributeValues,
    val inputs: Map<String, LogicVector>,
    val connectedInputs: Set<String>,
    val state: ComponentState = EmptyComponentState,
    val ticks: Long = 0,
    val eventTime: Long = 0,
    val elapsedTimeMillis: Long = 0,
    val options: Map<String, String> = emptyMap(),
    val events: List<ComponentEvent> = emptyList(),
    val autoPropagating: Boolean = false,
    val peers: List<CircuitComponent> = emptyList(),
    val peerStates: Map<String, ComponentState> = emptyMap(),
)

data class ComponentEvaluation(
    val outputs: Map<String, LogicVector>,
    val state: ComponentState = EmptyComponentState,
    val delay: Long = 1,
    val nextWakeupMillis: Long? = null,
    val diagnostics: List<String> = emptyList(),
    val outputDelays: Map<String, Long> = emptyMap(),
    val notices: List<LocalizedText> = diagnostics.map(LocalizedText::literal),
    val peerStateUpdates: Map<String, ComponentState> = emptyMap(),
    /** Generation transitions discard older scheduled outputs; the default null preserves transport. */
    val outputGeneration: String? = null,
    internal val terminalCharacter: Char? = null,
    /** Outputs set again by this evaluation, as Evolution does on some paths; null uses the component kind rule. */
    internal val repeatedOutputs: Set<String>? = null,
) {
    init { requireLocalized(delay >= 0 && outputDelays.values.all { it >= 0 }) { message(Messages.RuntimeComponentDelayInvalid) } }
}

interface ComponentBehavior {
    val usesPeerStates: Boolean get() = false

    fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
        EmptyComponentState

    fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
        initialState(component, attributes)

    fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation
}
