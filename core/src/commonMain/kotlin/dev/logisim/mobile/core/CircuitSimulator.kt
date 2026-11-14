package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.jvm.JvmOverloads
import kotlin.random.Random

data class ScheduledSimulationOutput(
    val time: Long,
    val instancePath: String,
    val componentId: String,
    val portName: String,
    /** Null disconnects this port from the set of wire drivers. */
    val value: LogicVector?,
    val outputGeneration: String? = null,
)

data class SimulationState(
    val portValues: Map<GridPoint, LogicValue> = emptyMap(),
    val componentValues: Map<String, LogicValue> = emptyMap(),
    val inputs: Map<String, LogicValue> = emptyMap(),
    val memory: Map<String, LogicValue> = emptyMap(),
    val clockLevels: Map<String, LogicValue> = emptyMap(),
    val ticks: Long = 0,
    val oscillating: Boolean = false,
    val portVectors: Map<GridPoint, LogicVector> = emptyMap(),
    val vectorValues: Map<String, LogicVector> = emptyMap(),
    val inputVectors: Map<String, LogicVector> = emptyMap(),
    val memoryVectors: Map<String, LogicVector> = emptyMap(),
    val circuitStates: Map<String, SimulationState> = emptyMap(),
    val circuitName: String = "",
    val instancePath: String = "",
    val diagnostics: List<String> = emptyList(),
    val componentStates: Map<String, ComponentState> = emptyMap(),
    val componentOutputs: Map<String, Map<String, LogicVector>> = emptyMap(),
    val elapsedTimeMillis: Long = 0,
    val nextWakeupMillis: Long? = null,
    val propagationTime: Long = 0,
    val pendingEvents: List<ScheduledSimulationOutput> = emptyList(),
    val notices: List<LocalizedText> = diagnostics.map(LocalizedText::literal),
    val outputGenerations: Map<String, String> = emptyMap(),
    internal val propagationNoiseCountdown: Long = 0,
    /**
     * The components of this circuit (instance) that [notices] are about, for notices that have them: the terminals
     * of a net with mismatched widths, an unsupported or missing subcircuit component, a component's own report.
     * Navigation data only; it never changes a value (C67).
     */
    val noticeSources: Map<LocalizedText, SimulationNoticeSource> = emptyMap(),
)

/** The components a simulation notice is about, in the circuit whose [SimulationState] reports it (C67). */
data class SimulationNoticeSource(val componentIds: List<String>)

private fun MutableMap<LocalizedText, MutableList<String>>.recordNoticeComponents(notice: LocalizedText, componentIds: Collection<String>) {
    val ids = getOrPut(notice) { mutableListOf() }
    componentIds.forEach { if (it !in ids) ids += it }
}

/** Schedules output events using component delays and independent per-instance state. */
class CircuitSimulator internal constructor(
    private val noiseSource: Random,
    private val propagationLimit: Int = 256,
    private val registry: ComponentRegistry = ComponentCatalog.registry,
    private val cancellationCheck: () -> Unit = {},
) {
    @JvmOverloads
    constructor(
        propagationLimit: Int = 256,
        registry: ComponentRegistry = ComponentCatalog.registry,
        cancellationCheck: () -> Unit = {},
    ) : this(Random.Default, propagationLimit, registry, cancellationCheck)

    init { require(propagationLimit > 0) }

    @kotlin.jvm.JvmOverloads
    fun settle(
        document: CircuitDocument,
        previous: SimulationState = SimulationState(),
        inputs: Map<String, LogicValue> = emptyMap(),
        tick: Boolean = false,
        vectorInputs: Map<String, LogicVector> = emptyMap(),
        events: Map<String, List<ComponentEvent>> = emptyMap(),
        elapsedTimeMillis: Long = previous.elapsedTimeMillis,
        options: Map<String, String> = emptyMap(),
        reset: Boolean = false,
        autoPropagating: Boolean = false,
        observer: ((SimulationState, Long) -> Unit)? = null,
    ): SimulationState = settle(CircuitProject(listOf(document), document.name), previous, inputs, tick,
        vectorInputs, events, elapsedTimeMillis, options, reset, autoPropagating, observer)

    @kotlin.jvm.JvmOverloads
    fun settle(
        project: CircuitProject,
        previous: SimulationState = SimulationState(),
        inputs: Map<String, LogicValue> = emptyMap(),
        tick: Boolean = false,
        vectorInputs: Map<String, LogicVector> = emptyMap(),
        events: Map<String, List<ComponentEvent>> = emptyMap(),
        elapsedTimeMillis: Long = previous.elapsedTimeMillis,
        options: Map<String, String> = emptyMap(),
        reset: Boolean = false,
        autoPropagating: Boolean = false,
        observer: ((SimulationState, Long) -> Unit)? = null,
    ): SimulationState = run(project, previous, inputs, tick, vectorInputs, events, elapsedTimeMillis,
        options, reset, autoPropagating, observer, false)

    @kotlin.jvm.JvmOverloads
    fun stepEvent(
        document: CircuitDocument,
        previous: SimulationState = SimulationState(),
        inputs: Map<String, LogicValue> = emptyMap(),
        vectorInputs: Map<String, LogicVector> = emptyMap(),
        events: Map<String, List<ComponentEvent>> = emptyMap(),
        elapsedTimeMillis: Long = previous.elapsedTimeMillis,
        options: Map<String, String> = emptyMap(),
        reset: Boolean = false,
        autoPropagating: Boolean = false,
        observer: ((SimulationState, Long) -> Unit)? = null,
    ): SimulationState = stepEvent(CircuitProject(listOf(document), document.name), previous, inputs,
        vectorInputs, events, elapsedTimeMillis, options, reset, autoPropagating, observer)

    @kotlin.jvm.JvmOverloads
    fun stepEvent(
        project: CircuitProject,
        previous: SimulationState = SimulationState(),
        inputs: Map<String, LogicValue> = emptyMap(),
        vectorInputs: Map<String, LogicVector> = emptyMap(),
        events: Map<String, List<ComponentEvent>> = emptyMap(),
        elapsedTimeMillis: Long = previous.elapsedTimeMillis,
        options: Map<String, String> = emptyMap(),
        reset: Boolean = false,
        autoPropagating: Boolean = false,
        observer: ((SimulationState, Long) -> Unit)? = null,
    ): SimulationState = run(project, previous, inputs, false, vectorInputs, events, elapsedTimeMillis,
        options, reset, autoPropagating, observer, true)

    internal fun settleTerminal(
        project: CircuitProject,
        previous: SimulationState,
        tick: Boolean,
        events: Map<String, List<ComponentEvent>> = emptyMap(),
        options: Map<String, String>,
        onTerminalWrite: (Char) -> Unit,
    ): SimulationState = run(project, previous, emptyMap(), tick, emptyMap(), events, previous.elapsedTimeMillis,
        options, false, false, null, false, onTerminalWrite)

    private fun run(
        project: CircuitProject, previous: SimulationState, inputs: Map<String, LogicValue>, tick: Boolean,
        vectorInputs: Map<String, LogicVector>, events: Map<String, List<ComponentEvent>>, elapsedTimeMillis: Long,
        options: Map<String, String>, reset: Boolean, autoPropagating: Boolean,
        observer: ((SimulationState, Long) -> Unit)?, singleEvent: Boolean,
        onTerminalWrite: ((Char) -> Unit)? = null,
    ): SimulationState {
        requireLocalized(elapsedTimeMillis >= 0) { LocalizedText("core_simulation_elapsed_nonnegative", fallback = "O tempo decorrido não pode ser negativo.") }
        val instances = mutableListOf<Instance>()
        val notices = linkedSetOf<LocalizedText>()
        // The components each elaboration notice is about, by the circuit that holds them. A notice raised inside a
        // subcircuit is about that subcircuit's component in every circuit above it, which is where it can be shown.
        val elaborationSources = mutableMapOf<String, MutableMap<LocalizedText, MutableList<String>>>()
        fun located(document: CircuitDocument, notice: LocalizedText, component: CircuitComponent, found: MutableSet<LocalizedText>) {
            notices += notice
            found += notice
            elaborationSources.getOrPut(document.name) { mutableMapOf() }.recordNoticeComponents(notice, listOf(component.id))
        }
        fun elaborate(document: CircuitDocument, path: String, ancestry: List<String>, found: MutableSet<LocalizedText>): Instance {
            val old = if (path.isEmpty()) previous else previous.circuitStates[path] ?: SimulationState()
            val instance = Instance(path, document, CircuitNetworks(document, registry), old, registry, options, reset)
            instances += instance
            for (component in document.components) {
                if (component.kind in unsupportedKinds && registry.definition(component.kind)?.behavior == null) {
                    located(document, message(Messages.CoreSimulationUnsupportedComponent,
                        "kind" to component.kind.name, "id" to component.id, "circuit" to document.name), component, found)
                }
                if (component.kind != ComponentKind.SUBCIRCUIT) continue
                val child = project.circuits.firstOrNull { it.name == component.circuitName }
                if (child == null || child.name in ancestry) {
                    located(document, if (child == null) message(Messages.ErrorNamedSubcircuitMissing, "name" to component.circuitName)
                        else message(Messages.ErrorRecursiveCircuit, "name" to child.name), component, found)
                    continue
                }
                val childPath = if (path.isEmpty()) component.id else "$path/${component.id}"
                val inside = linkedSetOf<LocalizedText>()
                instance.children += Child(component, elaborate(child, childPath, ancestry + child.name, inside),
                    project.subcircuitPortBindings(component))
                inside.forEach { located(document, it, component, found) }
            }
            return instance
        }
        val root = elaborate(project.mainCircuit, "", listOf(project.mainCircuit.name), linkedSetOf())
        val byPath = instances.associateBy { it.path }
        val supplied = inputs.mapValues { LogicVector.fromBit(it.value) } + vectorInputs
        instances.forEach { it.initialize(if (it === root) supplied else emptyMap(), tick) }
        val ticks = if (reset) 0 else previous.ticks + if (tick) 1 else 0
        var time = if (reset) 0 else previous.propagationTime
        val randomDelay = SimulationProjectOptions.randomDelay(project)
        val delayScale = if (randomDelay <= 1) 1L else 1L shl (Int.SIZE_BITS - (randomDelay - 1).countLeadingZeroBits())
        var noiseCountdown = previous.propagationNoiseCountdown
        fun eventTime(requested: Long, kind: ComponentKind): Long {
            // Evolution schedules a zero or negative component delay one unit later.
            val delay = if (requested <= 0 && ComponentCatalog.registry.definition(kind) != null) 1L else requested
            if (delayScale == 1L) return time + delay
            val scaledDelay = delay.coerceAtLeast(1) * delayScale
            if (kind == ComponentKind.SUBCIRCUIT) return time + scaledDelay
            if (noiseCountdown > 0) {
                noiseCountdown--
                return time + scaledDelay
            }
            noiseCountdown = noiseSource.nextLong(delayScale)
            return time + scaledDelay + 1
        }
        fun staleGeneration(event: ScheduledSimulationOutput): Boolean = event.outputGeneration != null &&
            event.outputGeneration != byPath[event.instancePath]?.outputGenerations?.get(event.componentId)
        val pending = if (reset) mutableListOf() else previous.pendingEvents.filter { event ->
            val instance = byPath[event.instancePath]
            instance?.outputTerminals(event.componentId)?.any { it.name == event.portName } == true && !staleGeneration(event)
        }.toMutableList()
        val targets = instances.associateWith { instance -> instance.outputTargets().mapValues { it.value.toMutableMap() }.toMutableMap() }
        for (event in pending.sortedBy { it.time }) {
            targets.getValue(byPath.getValue(event.instancePath)).getOrPut(event.componentId) { mutableMapOf() }[event.portName] = event.value
        }
        fun updateOutputGeneration(instance: Instance, componentId: String, generation: String?): Boolean {
            if (instance.outputGenerations[componentId] == generation) return false
            pending.removeAll { it.instancePath == instance.path && it.componentId == componentId && it.outputGeneration != generation }
            targets.getValue(instance)[componentId] = instance.outputTargets(componentId).toMutableMap()
            if (generation == null) instance.outputGenerations.remove(componentId)
            else instance.outputGenerations[componentId] = generation
            return true
        }
        // Evolution re-propagates a component whenever a value at one of its inputs changes. Most components then
        // set every output again, so an unchanged output still occupies the event queue for its delay.
        val observedPorts = mutableMapOf<Pair<String, String>, List<LogicVector?>>()
        fun portsChanged(instance: Instance, componentId: String, positions: List<GridPoint>): Boolean {
            val key = instance.path to componentId
            val current = positions.map { instance.values[it] }
            val last = observedPorts[key] ?: if (reset) null else positions.map { instance.previous.portVectors[it] }
            observedPorts[key] = current
            return last != current
        }
        // The child Pin drives an input crossing and can receive random noise; the subcircuit drives an output crossing.
        fun scheduleBoundary(instance: Instance, componentId: String, terminal: String, value: LogicVector,
                             cause: ComponentKind, repeat: Boolean = false) {
            val target = targets.getValue(instance).getOrPut(componentId) { mutableMapOf() }
            if (target[terminal] == value && !repeat) return
            target[terminal] = value
            pending += ScheduledSimulationOutput(eventTime(1, cause), instance.path, componentId, terminal, value)
        }
        // Evolution drives a child input pin and a parent subcircuit output one step after the opposite side changes.
        fun scheduleBoundaries() {
            instances.forEach { it.resolve() }
            for (parent in instances) for (child in parent.children) {
                val outputs = mutableMapOf<GridPoint, LogicVector>()
                for ((outside, pin) in child.bindings) {
                    if (pin.kind == ComponentKind.INPUT) {
                        val terminal = child.instance.outputTerminals(pin.id).firstOrNull() ?: continue
                        scheduleBoundary(child.instance, pin.id, terminal.name,
                            parent.values[outside] ?: LogicVector.unknown(pin.width), ComponentKind.INPUT)
                    } else outputs[outside] = child.instance.values[pin.position] ?: LogicVector.unknown(pin.width)
                }
                val layout = parent.layouts.getValue(child.component.id)
                val childOutputs = child.bindings.filter { it.second.kind != ComponentKind.INPUT }.map { it.second.position }
                val changed = portsChanged(parent, child.component.id, layout.inputs.map { it.position }) or
                    portsChanged(child.instance, "\u0000outputs", childOutputs)
                for (terminal in parent.outputTerminals(child.component.id)) {
                    scheduleBoundary(parent, child.component.id, terminal.name,
                        outputs[terminal.position] ?: LogicVector.unknown(parent.widthOf(terminal)), ComponentKind.SUBCIRCUIT, changed)
                }
            }
        }
        var consumedExternal = false
        var appliedTimestamps = 0
        var settled = false
        var oscillating = false
        var lastObserved: Long? = null
        fun snapshot(): SimulationState {
            pending.removeAll(::staleGeneration)
            val states = instances.associate { instance ->
                val shown = (notices + instance.networks.notices + instance.componentDiagnostics.values.flatten()).distinct()
                val sources = mutableMapOf<LocalizedText, MutableList<String>>()
                elaborationSources[instance.document.name]?.forEach { (notice, ids) -> sources.recordNoticeComponents(notice, ids) }
                instance.networks.noticeComponents.forEach { (notice, ids) -> sources.recordNoticeComponents(notice, ids) }
                instance.componentDiagnostics.forEach { (id, reported) -> reported.forEach { sources.recordNoticeComponents(it, listOf(id)) } }
                instance.path to instance.snapshot(ticks, oscillating, shown, elapsedTimeMillis, time,
                    sources.filterKeys { it in shown }.mapValues { SimulationNoticeSource(it.value.toList()) })
            }
            val wakeup = states.values.mapNotNull { it.nextWakeupMillis }.minOrNull()
            return states.getValue("").copy(circuitStates = states.filterKeys { it.isNotEmpty() },
                pendingEvents = pending.sortedBy { it.time }, nextWakeupMillis = wakeup,
                propagationNoiseCountdown = noiseCountdown)
        }
        for (round in 0 until propagationLimit) {
            cancellationCheck()
            scheduleBoundaries()
            val memories = instances.associateWith { instance ->
                instance.document.components.filter { it.kind in storageKinds && !instance.hasBehavior(it) }
                    .associate { it.id to capture(instance, it) }
            }
            instances.forEach { it.memory.putAll(memories.getValue(it)) }
            var peersChanged = false
            for (instance in instances) for (component in instance.document.components) {
                cancellationCheck()
                val definition = registry.definition(component.kind)
                val behavior = definition?.behavior
                if (behavior != null) {
                    val namedInputs = instance.namedInputs(component, excludeOwnInout = behavior === HdlIpBehavior).toMutableMap()
                    if (component.kind == ComponentKind.INPUT) {
                        if (instance === root) instance.stimuli[component.id]?.let { namedInputs["drive"] = it }
                        else namedInputs["drive"] = targets.getValue(instance)[component.id]?.values?.firstOrNull()
                            ?: instance.outputs[component.id]?.firstOrNull()
                            ?: LogicVector.unknown(instance.componentWidth(component))
                    } else instance.stimuli[component.id]?.let { namedInputs["drive"] = it }
                    val key = if (instance.path.isEmpty()) component.id else "${instance.path}/${component.id}"
                    val result = behavior.evaluate(ComponentEvaluationContext(component, registry.attributes(component),
                        namedInputs, instance.connectedInputNames(component),
                        instance.states[component.id] ?: EmptyComponentState, ticks, time, elapsedTimeMillis,
                        options, if (consumedExternal) emptyList() else events[key].orEmpty().filter {
                            it !is AddressedComponentEvent || it.eventAddress == key
                        }, autoPropagating,
                        if (behavior.usesPeerStates) instance.document.components else emptyList(),
                        if (behavior.usesPeerStates) instance.states.toMap() else emptyMap()))
                    requireLocalized((behavior.usesPeerStates || result.peerStateUpdates.isEmpty()) &&
                        result.peerStateUpdates.keys.all { it in instance.states }) {
                        LocalizedText("core_simulation_state_scope", fallback =
                            "A atualização de estado deve referenciar componentes registrados da mesma instância.")
                    }
                    for ((id, state) in result.peerStateUpdates) {
                        if (id != component.id && instance.states[id] != state) peersChanged = true
                        instance.states[id] = state
                    }
                    instance.states[component.id] = result.state
                    if (onTerminalWrite != null) result.terminalCharacter?.let(onTerminalWrite)
                    instance.componentDiagnostics[component.id] =
                        (instance.componentDiagnostics[component.id].orEmpty() + result.notices).distinct()
                    instance.wakeups[component.id] = result.nextWakeupMillis
                    val terminals = instance.outputTerminals(component.id)
                    val released = (result.state as? ComponentOutputReleases)?.releasedOutputs.orEmpty()
                    requireLocalized((result.outputs.keys + released).all { name -> terminals.any { it.name == name } } &&
                        result.outputs.keys.none { it in released }) {
                        LocalizedText("core_simulation_unknown_output", mapOf("kind" to component.kind.name), fallback = "Saída desconhecida para ${component.kind.name}.")
                    }
                    val generationChanged = updateOutputGeneration(instance, component.id, result.outputGeneration)
                    val propagated = portsChanged(instance, component.id, instance.layouts.getValue(component.id).inputs.map { it.position })
                    val repeatKind = repeatsOutputs(component.kind)
                    for (terminal in terminals) {
                        val repeat = propagated && (result.repeatedOutputs?.contains(terminal.name) ?: repeatKind)
                        if (terminal.name !in result.outputs && terminal.name !in released) continue
                        val width = instance.widthOf(terminal)
                        val output = result.outputs[terminal.name]?.let { value ->
                            if (value.width == width) value else LogicVector.error(width)
                        }
                        val target = targets.getValue(instance).getOrPut(component.id) { mutableMapOf() }
                        if (target[terminal.name] != output || generationChanged && result.outputGeneration != null ||
                            repeat && output != null) {
                            target[terminal.name] = output
                            pending += ScheduledSimulationOutput(eventTime(result.outputDelays[terminal.name] ?: result.delay, component.kind),
                                instance.path, component.id, terminal.name, output, result.outputGeneration)
                        }
                    }
                } else {
                    updateOutputGeneration(instance, component.id, null)
                    val desired = if (component.kind in storageKinds) instance.storageOutputs(component)
                        else SimulationComponents.combinational(component, instance.inputsFor(component),
                            if (options["gateUndefined"] == "error") instance.inputsFor(component) else instance.connectedInputsFor(component))
                            ?: continue
                    val terminals = instance.outputTerminals(component.id)
                    val repeat = repeatsOutputs(component.kind) && portsChanged(instance, component.id,
                        instance.layouts.getValue(component.id).inputs.map { it.position })
                    desired.forEachIndexed { index, value ->
                        val terminal = terminals.getOrNull(index) ?: return@forEachIndexed
                        val target = targets.getValue(instance).getOrPut(component.id) { mutableMapOf() }
                        if (target[terminal.name] != value || repeat) {
                            target[terminal.name] = value
                            pending += ScheduledSimulationOutput(eventTime(delay(component), component.kind), instance.path, component.id, terminal.name, value)
                        }
                    }
                }
            }
            consumedExternal = true
            pending.removeAll(::staleGeneration)
            // A transaction can update a slave that was evaluated earlier in document order.
            // Revisit its outputs before consuming delayed events or declaring the circuit settled.
            if (peersChanged) continue
            val next = pending.minOfOrNull { it.time }
            if (next != time && lastObserved != time) {
                observer?.invoke(snapshot(), time)
                lastObserved = time
            }
            if (next == null) { settled = true; break }
            if (singleEvent && appliedTimestamps >= 1 && next > time) { settled = true; break }
            // Evolution's Propagator.propagate runs stepInternal for iterations 1 until simlimit and
            // declares oscillation on iteration simlimit without stepping: the last round only checks
            // whether events remain, so an unsettled circuit freezes after simlimit - 1 timestamps.
            if (round == propagationLimit - 1) break
            val ready = pending.filter { it.time == next }
            pending.removeAll { it.time == next }
            if (next > time) appliedTimestamps++
            time = next
            for (event in ready) byPath.getValue(event.instancePath).applyOutput(event)
        }
        if (!settled) oscillating = true
        if (oscillating) notices += message(Messages.ErrorSimulationUnsettled)
        instances.forEach { it.resolve() }
        return snapshot()
    }

    private fun delay(component: CircuitComponent): Long = when (component.kind) {
        ComponentKind.D_FLIP_FLOP -> 5L
        ComponentKind.REGISTER, ComponentKind.COUNTER -> 8L
        ComponentKind.ROM -> 10L
        ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> component.width + 2L
        else -> 1L
    }

    private fun capture(instance: Instance, component: CircuitComponent): LogicVector {
        val ports = instance.inputsFor(component)
        fun bit(index: Int) = ports.getOrNull(index)?.bit(0) ?: LogicValue.UNKNOWN
        val old = instance.memory[component.id] ?: LogicVector.low(component.width)
        val clock = bit(1)
        val oldClock = instance.clocks[component.id] ?: LogicValue.LOW
        instance.clocks[component.id] = clock
        val triggered = when (component.attributes["trigger"]) {
            "falling" -> oldClock == LogicValue.HIGH && clock == LogicValue.LOW
            "high" -> clock == LogicValue.HIGH
            "low" -> clock == LogicValue.LOW
            else -> oldClock == LogicValue.LOW && clock == LogicValue.HIGH
        }
        return when (component.kind) {
            ComponentKind.D_FLIP_FLOP -> when {
                bit(2) == LogicValue.HIGH -> LogicVector.low(1)
                bit(3) == LogicValue.HIGH -> LogicVector.high(1)
                triggered && bit(0) in listOf(LogicValue.LOW, LogicValue.HIGH) -> ports[0].resized(1)
                else -> old
            }
            ComponentKind.REGISTER -> when {
                bit(3) == LogicValue.HIGH -> LogicVector.low(component.width)
                triggered && bit(2) != LogicValue.LOW -> ports[0].resized(component.width)
                else -> old
            }
            ComponentKind.COUNTER -> {
                val width = component.width
                val value = old.toULongOrNull() ?: 0uL
                val maximum = SequentialLibrary.counterMaximum(component.attributes, width)
                val up = bit(5) != LogicValue.LOW
                val terminal = if (up) value == maximum else value == 0uL
                when {
                    bit(3) == LogicValue.HIGH -> LogicVector.low(width)
                    !triggered -> old
                    bit(2) == LogicValue.HIGH -> ports[0].resized(width)
                    bit(4) == LogicValue.LOW -> old
                    terminal -> when (component.attributes["ongoal"] ?: component.attributes["onGoal"]) {
                        "stay" -> old
                        "load" -> ports[0].resized(width)
                        "continue" -> LogicVector.of(width, if (up) value + 1uL else value - 1uL)
                        else -> LogicVector.of(width, if (up) 0uL else maximum)
                    }
                    else -> LogicVector.of(width, if (up) value + 1uL else value - 1uL)
                }
            }
            else -> old
        }
    }

    private class Instance(
        val path: String,
        val document: CircuitDocument,
        val networks: CircuitNetworks,
        val previous: SimulationState,
        val registry: ComponentRegistry,
        val options: Map<String, String>,
        val reset: Boolean,
    ) {
        val children = mutableListOf<Child>()
        val memory = mutableMapOf<String, LogicVector>()
        val clocks = if (reset) mutableMapOf() else previous.clockLevels.toMutableMap()
        val stimuli = mutableMapOf<String, LogicVector>()
        val states = mutableMapOf<String, ComponentState>()
        val componentDiagnostics = mutableMapOf<String, List<LocalizedText>>()
        val wakeups = mutableMapOf<String, Long?>()
        val layouts = document.components.associate { it.id to it.layout(registry) }
        val outputGenerations = if (reset) mutableMapOf() else previous.outputGenerations.filterKeys { id ->
            document.components.any { it.id == id && hasBehavior(it) }
        }.toMutableMap()
        var outputs = emptyMap<String, List<LogicVector?>>()
        var values = emptyMap<GridPoint, LogicVector>()

        fun hasBehavior(component: CircuitComponent): Boolean = registry.definition(component.kind)?.behavior != null
        fun widthOf(terminal: ComponentTerminal): Int = if (terminal.width > 0) terminal.width else networks.widthAt(terminal.position)
        fun componentWidth(component: CircuitComponent): Int {
            val layout = layouts.getValue(component.id)
            return (layout.outputs.firstOrNull() ?: layout.inputs.firstOrNull())?.let(::widthOf) ?: 1
        }
        fun outputTerminals(id: String): List<ComponentTerminal> = layouts[id]?.outputs.orEmpty()

        fun initialize(supplied: Map<String, LogicVector>, tick: Boolean) {
            for (component in document.components) {
                val width = componentWidth(component)
                val behavior = registry.definition(component.kind)?.behavior
                if (behavior != null) {
                    val old = previous.componentStates[component.id]
                    states[component.id] = when {
                        old != null && reset -> behavior.resetState(component, registry.attributes(component), old)
                        old != null -> old
                        else -> behavior.initialState(component, registry.attributes(component))
                    }
                } else if (component.kind in storageKinds) {
                    memory[component.id] = if (reset) LogicVector.low(width) else previous.memoryVectors[component.id]
                        ?: previous.memory[component.id]?.let { LogicVector.fromBit(it).resized(width) }
                        ?: if (options["memoryStartupUnknown"] == "true") LogicVector.unknown(width) else LogicVector.low(width)
                }
                if ((behavior == null && (component.kind == ComponentKind.INPUT || component.kind == ComponentKind.BUTTON)) ||
                    component.id in supplied || (!reset && component.id in previous.inputVectors)) {
                    stimuli[component.id] = supplied[component.id]?.resized(width)
                        ?: (if (!reset) previous.inputVectors[component.id] else null)
                        ?: LogicVector.of(width, parseUnsigned(component.attributes["initial"]) ?: 0uL)
                }
            }
            outputs = document.components.associate { component ->
                val terminals = outputTerminals(component.id)
                component.id to when {
                    hasBehavior(component) -> terminals.mapIndexed { index, terminal ->
                        val previousPorts = previous.componentOutputs[component.id]
                        when {
                            reset -> LogicVector.unknown(widthOf(terminal))
                            // A simulator snapshot contains a component key even when every port is released.
                            previousPorts != null -> previousPorts[terminal.name]
                            else -> previous.portVectors[terminal.position]
                                ?: if (index == 0) previous.vectorValues[component.id]
                                    ?: previous.componentValues[component.id]?.let { LogicVector.fromBit(it).resized(widthOf(terminal)) }
                                    ?: LogicVector.unknown(widthOf(terminal))
                                else LogicVector.unknown(widthOf(terminal))
                        }
                    }
                    component.kind == ComponentKind.INPUT || component.kind == ComponentKind.BUTTON -> listOf(stimuli.getValue(component.id))
                    component.kind == ComponentKind.CLOCK -> {
                        val explicit = supplied[component.id]
                        val high = component.attributeInt("highDuration", 1).coerceAtLeast(1)
                        val low = component.attributeInt("lowDuration", 1).coerceAtLeast(1)
                        val old = if (reset) LogicVector.low(1) else previous.vectorValues[component.id]
                            ?: previous.componentValues[component.id]?.let(LogicVector::fromBit) ?: LogicVector.low(1)
                        val level = if (!tick || reset) old else {
                            val phase = component.attributeInt("phaseOffset", 0)
                            val elapsed = (previous.ticks + 1 + phase) % (high + low)
                            LogicVector.fromBit(if (elapsed in 1..high.toLong()) LogicValue.HIGH else LogicValue.LOW)
                        }
                        listOf(explicit?.resized(1) ?: level)
                    }
                    component.kind in storageKinds -> storageOutputs(component)
                    else -> terminals.mapIndexed { index, terminal ->
                        if (reset) LogicVector.unknown(widthOf(terminal)) else
                            previous.componentOutputs[component.id]?.get(terminal.name)
                                ?: previous.portVectors[terminal.position] ?: if (index == 0)
                                    previous.vectorValues[component.id]
                                        ?: previous.componentValues[component.id]?.let { LogicVector.fromBit(it).resized(widthOf(terminal)) }
                                        ?: LogicVector.unknown(widthOf(terminal))
                                else LogicVector.unknown(widthOf(terminal))
                    }
                }
            }
        }

        fun applyOutput(event: ScheduledSimulationOutput) {
            if (event.outputGeneration != null && event.outputGeneration != outputGenerations[event.componentId]) return
            val terminals = outputTerminals(event.componentId)
            val index = terminals.indexOfFirst { it.name == event.portName }
            if (index < 0) return
            val next = outputs[event.componentId].orEmpty().toMutableList()
            while (next.size < terminals.size) next += LogicVector.unknown(widthOf(terminals[next.size]))
            next[index] = event.value
            outputs = outputs + (event.componentId to next)
        }

        fun outputTargets(componentId: String): Map<String, LogicVector?> = outputTerminals(componentId).mapIndexed { index, terminal ->
            terminal.name to outputs[componentId]?.getOrNull(index)
        }.toMap()

        fun outputTargets(): Map<String, Map<String, LogicVector?>> =
            document.components.associate { it.id to outputTargets(it.id) }

        fun namedOutputs(): Map<String, Map<String, LogicVector>> = document.components.associate { component ->
            component.id to outputTerminals(component.id).mapIndexedNotNull { index, terminal ->
                outputs[component.id]?.getOrNull(index)?.let { terminal.name to it }
            }.toMap()
        }

        fun storageOutputs(component: CircuitComponent): List<LogicVector> {
            val value = memory.getValue(component.id)
            return when (component.kind) {
                ComponentKind.D_FLIP_FLOP -> listOf(value, value.inverted())
                ComponentKind.COUNTER -> {
                    val maximum = SequentialLibrary.counterMaximum(component.attributes, component.width)
                    val ports = inputsFor(component)
                    val up = ports.getOrNull(5)?.bit(0) != LogicValue.LOW
                    val goal = if (up) maximum else 0uL
                    val carry = when {
                        ports.getOrNull(3)?.bit(0) == LogicValue.HIGH -> LogicValue.LOW
                        value.toULongOrNull() == goal -> LogicValue.HIGH
                        else -> LogicValue.LOW
                    }
                    listOf(value, LogicVector.fromBit(carry))
                }
                else -> listOf(value)
            }
        }

        fun namedInputs(component: CircuitComponent, excludeOwnInout: Boolean): Map<String, LogicVector> {
            val terminals = layouts.getValue(component.id).inputs
            val external = if (excludeOwnInout && terminals.any { it.direction == PortDirection.INOUT }) {
                resolvedValues(excludeInoutOf = component.id)
            } else values
            return terminals.associate { terminal ->
                val sampled = if (terminal.direction == PortDirection.INOUT) external else values
                terminal.name to (sampled[terminal.position] ?: LogicVector.unknown(widthOf(terminal)))
            }
        }

        fun connectedInputNames(component: CircuitComponent): Set<String> = layouts.getValue(component.id).inputs
            .filter { networks.isConnected(it.position) }.map { it.name }.toSet()

        fun inputsFor(component: CircuitComponent): List<LogicVector> =
            layouts.getValue(component.id).inputs.mapIndexed { index, terminal ->
                val value = values[terminal.position] ?: LogicVector.unknown(widthOf(terminal))
                if (component.attributes["negate$index"] == "true") value.inverted() else value
            }

        fun connectedInputsFor(component: CircuitComponent): List<LogicVector> {
            val inputs = inputsFor(component)
            return layouts.getValue(component.id).inputs.mapIndexedNotNull { index, terminal ->
                if (networks.isConnected(terminal.position)) inputs[index] else null
            }
        }

        fun resolve() {
            values = resolvedValues()
        }

        private fun resolvedValues(excludeInoutOf: String? = null): Map<GridPoint, LogicVector> {
            val drivers = mutableMapOf<GridPoint, MutableList<LogicVector>>()
            for (component in document.components) {
                val output = outputs[component.id].orEmpty()
                for ((index, terminal) in outputTerminals(component.id).withIndex()) {
                    if (component.id == excludeInoutOf && terminal.direction == PortDirection.INOUT) continue
                    output.getOrNull(index)?.let { drivers.getOrPut(terminal.position) { mutableListOf() } += it }
                }
            }
            return networks.resolve(drivers.mapValues { (point, values) ->
                LogicVector.resolve(values, networks.widthAt(point))
            }, rejectWeak = excludeInoutOf != null)
        }

        fun snapshot(ticks: Long, oscillating: Boolean, notices: List<LocalizedText>, elapsed: Long, time: Long,
                     sources: Map<LocalizedText, SimulationNoticeSource> = emptyMap()): SimulationState {
            val componentValues = document.components.associate { component ->
                val layout = layouts.getValue(component.id)
                val passive = !hasBehavior(component) && layout.outputs.all { it.direction == PortDirection.INOUT }
                val value = if (outputTerminals(component.id).isEmpty() || passive) layout.inputs.firstOrNull()?.let { values[it.position] }
                    else outputs[component.id]?.firstOrNull()
                component.id to (value ?: LogicVector.unknown(componentWidth(component)))
            }
            return SimulationState(portValues = values.mapValues { it.value.bit(0) },
                componentValues = componentValues.mapValues { it.value.bit(0) }, inputs = stimuli.mapValues { it.value.bit(0) },
                memory = memory.mapValues { it.value.bit(0) }, clockLevels = clocks.toMap(), ticks = ticks,
                oscillating = oscillating, portVectors = values, vectorValues = componentValues,
                inputVectors = stimuli.toMap(), memoryVectors = memory.toMap(), circuitName = document.name,
                instancePath = path, diagnostics = notices.map { it.resolve(AppLanguage.PORTUGUESE) }, notices = notices, componentStates = states.toMap(),
                componentOutputs = namedOutputs(), elapsedTimeMillis = elapsed,
                nextWakeupMillis = wakeups.values.filterNotNull().minOrNull(), propagationTime = time,
                outputGenerations = outputGenerations.toMap(), noticeSources = sources)
        }
    }

    private data class Child(val component: CircuitComponent, val instance: Instance,
        val bindings: List<Pair<GridPoint, CircuitComponent>>)

    companion object {
        private val storageKinds = setOf(ComponentKind.D_FLIP_FLOP, ComponentKind.REGISTER, ComponentKind.COUNTER)
        /** Evolution Pin and Clock set an output only when it changes, RAM and DualRAM report their own paths,
         *  and SoC, Tcl and NE555 outputs come from their runtime state. Other catalog components set all outputs. */
        private val conditionalKinds = setOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.CLOCK,
            ComponentKind.RAM, MemoryComponents.DualRam, ExtraIoKinds.Ne555,
            SocComponents.BUS, SocComponents.MEMORY, SocComponents.PIO, SocComponents.VGA, SocComponents.DMA,
            SocComponents.UART, SocComponents.RV32, SocComponents.NIOS2, TclComponents.CONSOLE_REDS, TclComponents.GENERIC)
        private fun repeatsOutputs(kind: ComponentKind): Boolean =
            kind !in conditionalKinds && ComponentCatalog.registry.definition(kind) != null
        private val unsupportedKinds = setOf(ComponentKind.RAM, ComponentKind.MULTIPLEXER, ComponentKind.DECODER, ComponentKind.BIT_EXTENDER)
        private fun parseUnsigned(text: String?): ULong? = text?.let {
            if (it.startsWith("0x", true)) it.drop(2).toULongOrNull(16) else it.toULongOrNull()
        }
    }
}
