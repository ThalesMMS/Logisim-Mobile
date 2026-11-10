package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.TclComponentEvent
import dev.logisim.mobile.core.TclRuntimeState

data class SimulationObservation(
    val state: SimulationState,
    val propagationTime: Long,
)

/** Compute results and trace samples travel together to the editor dispatcher. */
data class SimulationAdvanceResult(
    val state: SimulationState,
    val observations: List<SimulationObservation>,
)

/**
 * Runs synchronously on the caller's computation dispatcher. No callback publishes editor state.
 * External events are consumed by this invocation and must not be replayed by the next step.
 * Reset restores initial stimuli and elapsed time, while each component decides state retention.
 */
fun advanceSimulation(
    snapshot: SimulationRunSnapshot,
    step: SimulationStep,
    registry: ComponentRegistry = ComponentCatalog.registry,
    events: Map<String, List<ComponentEvent>> = snapshot.events,
    autoPropagating: Boolean = false,
    cancellationCheck: () -> Unit = {},
): SimulationAdvanceResult {
    val simulator = CircuitSimulator(propagationLimit = SimulationProjectOptions.read(snapshot.project).propagationLimit, registry = registry, cancellationCheck = cancellationCheck)
    return observeSimulation { observer ->
        when (step) {
            SimulationStep.TICK, SimulationStep.TICK_CYCLE -> {
                fun tick(previous: SimulationState, events: Map<String, List<ComponentEvent>>) = simulator.settle(
                    project = snapshot.project,
                    previous = previous,
                    inputs = snapshot.inputs,
                    tick = true,
                    vectorInputs = snapshot.vectorInputs,
                    events = events,
                    elapsedTimeMillis = snapshot.elapsedTimeMillis,
                    options = snapshot.options,
                    autoPropagating = autoPropagating,
                    observer = observer,
                )
                val first = tick(snapshot.previous, events)
                if (step == SimulationStep.TICK_CYCLE && !first.oscillating) tick(first, emptyMap()) else first
            }
            SimulationStep.EVENT -> simulator.stepEvent(
                project = snapshot.project,
                previous = snapshot.previous,
                inputs = snapshot.inputs,
                vectorInputs = snapshot.vectorInputs,
                events = events,
                elapsedTimeMillis = snapshot.elapsedTimeMillis,
                options = snapshot.options,
                autoPropagating = autoPropagating,
                observer = observer,
            )
            SimulationStep.RESET -> {
                val resetInputs = snapshot.startupResetIds.associateWith { LogicValue.LOW }
                var reset = simulator.settle(
                project = snapshot.project,
                previous = snapshot.previous,
                elapsedTimeMillis = 0,
                options = snapshot.options,
                reset = true,
                autoPropagating = autoPropagating,
                inputs = resetInputs,
                vectorInputs = snapshot.resetVectorInputs,
                observer = observer,
            )
                if (snapshot.startupResetIds.isNotEmpty()) {
                    reset = simulator.settle(snapshot.project, reset,
                        inputs = resetInputs.mapValues { LogicValue.HIGH },
                        vectorInputs = snapshot.resetVectorInputs.filterKeys { it !in snapshot.startupResetIds }, options = snapshot.options, observer = observer)
                    reset = simulator.settle(snapshot.project, reset, inputs = resetInputs,
                        vectorInputs = snapshot.resetVectorInputs, options = snapshot.options, observer = observer)
                }
                reset
            }
            SimulationStep.SETTLE -> {
                val tickRequest = events.entries.singleOrNull()?.takeIf { it.value.singleOrNull() is TclComponentEvent.ApplyTick }
                if (tickRequest != null && !isCurrentTclTickRequest(snapshot.previous, tickRequest)) {
                    snapshot.previous
                } else {
                    simulator.settle(snapshot.project, snapshot.previous,
                        inputs = snapshot.inputs, vectorInputs = snapshot.vectorInputs, events = events,
                        tick = tickRequest != null, elapsedTimeMillis = snapshot.elapsedTimeMillis, options = snapshot.options,
                        autoPropagating = autoPropagating, observer = observer)
                }
            }
        }
    }
}

private fun isCurrentTclTickRequest(
    previous: SimulationState,
    request: Map.Entry<String, List<ComponentEvent>>,
): Boolean {
    val instancePath = request.key.substringBeforeLast('/', "")
    val instance = if (instancePath.isEmpty()) previous else previous.circuitStates[instancePath]
    val state = instance?.componentStates?.get(request.key.substringAfterLast('/')) as? TclRuntimeState ?: return false
    return (request.value.single() as TclComponentEvent.ApplyTick).matches(state)
}

/**
 * Re-evaluates components at [SimulationRunSnapshot.elapsedTimeMillis] without a clock pulse.
 * Schedule this when a component's nextWakeupMillis is due; event stepping alone does not advance
 * elapsed time.
 */
fun advanceSimulationTime(
    snapshot: SimulationRunSnapshot,
    registry: ComponentRegistry = ComponentCatalog.registry,
    events: Map<String, List<ComponentEvent>> = snapshot.events,
    autoPropagating: Boolean = false,
    cancellationCheck: () -> Unit = {},
): SimulationAdvanceResult {
    val simulator = CircuitSimulator(propagationLimit = SimulationProjectOptions.read(snapshot.project).propagationLimit, registry = registry, cancellationCheck = cancellationCheck)
    return observeSimulation { observer ->
        simulator.settle(
            project = snapshot.project,
            previous = snapshot.previous,
            inputs = snapshot.inputs,
            vectorInputs = snapshot.vectorInputs,
            events = events,
            elapsedTimeMillis = snapshot.elapsedTimeMillis,
            options = snapshot.options,
            autoPropagating = autoPropagating,
            observer = observer,
        )
    }
}

private inline fun observeSimulation(
    compute: ((SimulationState, Long) -> Unit) -> SimulationState,
): SimulationAdvanceResult {
    val observations = mutableListOf<SimulationObservation>()
    val state = compute { observedState, time ->
        observations += SimulationObservation(observedState, time)
    }
    return SimulationAdvanceResult(state, observations.toList())
}
