package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class TraceSignalReference(val instancePath: String, val componentId: String, val ramAddress: Long? = null) {
    init { requireLocalized(componentId.isNotBlank()) { message(Messages.TraceInvalidReference) } }
    val key: String get() {
        val component = if (instancePath.isEmpty()) componentId else "$instancePath/$componentId"
        // Component IDs forbid slashes, so a double slash cannot be an instance path.
        return ramAddress?.let { "$component//0x${it.toString(16)}" } ?: component
    }
}

data class TraceSignal(
    val reference: TraceSignalReference,
    val label: String,
    val width: Int,
    val labelText: LocalizedText = LocalizedText.literal(label),
    val ramAddressBits: Int? = null,
    val radix: TraceRadix = TraceRadix.BINARY,
    val legacyInstancePath: String = reference.instancePath.replace('/', '.'),
) {
    init { requireLocalized(width in 1..64) { message(Messages.TraceInvalidWidth) } }
}

enum class TraceRadix {
    BINARY, DECIMAL, HEXADECIMAL;

    fun format(value: LogicVector): String = when (this) {
        BINARY -> value.display
        DECIMAL -> value.toULongOrNull()?.toString() ?: value.display
        HEXADECIMAL -> value.hexadecimalDisplay()
    }
}

data class TraceFrame(val time: Long, val values: List<LogicVector?>)

enum class TraceTimeUnit {
    HALF_CYCLES, EVENTS, NANOSECONDS;

    val notice: LocalizedText
        get() = message(when (this) {
            HALF_CYCLES -> Messages.TraceHalfCycles
            EVENTS -> Messages.TraceEvents
            NANOSECONDS -> Messages.TraceNanoseconds
        })
    val label: String get() = notice.resolve()
}

data class TraceWindow(val start: Long, val end: Long) {
    init { requireLocalized(start >= 0 && end > start) { message(Messages.TraceInvalidWindow) } }
    val span: Long get() = end - start

    fun zoom(factor: Double, anchor: Long, limit: Long): TraceWindow {
        requireLocalized(factor.isFinite() && factor > 0) { message(Messages.TraceInvalidZoom) }
        val range = (span.toDouble() / factor).coerceIn(1.0, Long.MAX_VALUE.toDouble()).toLong()
        val ratio = (anchor - start).toDouble() / span
        val proposed = anchor.toDouble() - ratio * range
        val left = proposed.coerceIn(0.0, (limit - range).coerceAtLeast(0).toDouble()).toLong()
        return TraceWindow(left, safeEnd(left, range))
    }

    fun pan(delta: Long, limit: Long): TraceWindow {
        val left = (start.toDouble() + delta).coerceIn(0.0, (limit - span).coerceAtLeast(0).toDouble()).toLong()
        return TraceWindow(left, safeEnd(left, span))
    }

    private fun safeEnd(start: Long, length: Long): Long =
        if (Long.MAX_VALUE - start < length) Long.MAX_VALUE else start + length
}

/** A bounded collection of sampled values. Timestamps are supplied by the simulation controller. */
class TraceRecorder(
    signals: List<TraceSignal> = emptyList(),
    val capacity: Int = 10_000,
    val timeUnit: TraceTimeUnit = TraceTimeUnit.EVENTS,
) {
    init {
        requireLocalized(capacity > 0) { message(Messages.TraceInvalidCapacity) }
        requireLocalized(signals.size <= MAX_SIGNALS) { message(Messages.TraceSignalLimit, "limit" to MAX_SIGNALS) }
        requireUnique(signals)
    }

    var signals: List<TraceSignal> = signals.toList()
        private set
    /** Receives changed samples and their propagation status independently of bounded history retention. */
    var onCapture: ((TraceFrame, Boolean) -> Unit)? = null
    private val history = ArrayDeque<TraceFrame>()
    val frames: List<TraceFrame> get() = history.toList()
    var lastCompletedFrame: TraceFrame? = null
        private set
    var paused: Boolean = false
        private set
    var droppedFrames: Long = 0
        private set
    val firstTime: Long? get() = history.firstOrNull()?.time
    val lastTime: Long? get() = history.lastOrNull()?.time
    val retainedValueCount: Int get() = history.size * signals.size
    var cursorA: Long? = null
        private set
    var cursorB: Long? = null
        private set
    val cursorDistance: Long? get() = cursorA?.let { a -> cursorB?.let { b -> if (a > b) a - b else b - a } }

    fun select(signals: List<TraceSignal>) {
        requireLocalized(signals.size <= MAX_SIGNALS) { message(Messages.TraceSignalLimit, "limit" to MAX_SIGNALS) }
        requireUnique(signals)
        if (signals == this.signals) return
        this.signals = signals.toList()
        clear()
    }

    /** Move a selected signal without losing samples or changing their association with signals. */
    fun moveSignal(reference: TraceSignalReference, offset: Int): Boolean {
        val index = signals.indexOfFirst { it.reference == reference }
        val destination = index + offset
        if (index < 0 || destination !in signals.indices || index == destination) return false

        val order = signals.indices.toMutableList()
        order.add(destination, order.removeAt(index))
        signals = order.map { signals[it] }
        for (frameIndex in history.indices) {
            val frame = history[frameIndex]
            history[frameIndex] = frame.copy(values = order.map { frame.values[it] })
        }
        lastCompletedFrame = lastCompletedFrame?.let { frame -> frame.copy(values = order.map { frame.values[it] }) }
        return true
    }

    /** Changing the display base does not change the captured samples. */
    fun setRadix(reference: TraceSignalReference, radix: TraceRadix) {
        signals = signals.map { if (it.reference == reference && it.radix != radix) it.copy(radix = radix) else it }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun clear() {
        history.clear()
        lastCompletedFrame = null
        droppedFrames = 0
        cursorA = null
        cursorB = null
    }

    fun setCursors(first: Long?, second: Long?) {
        requireLocalized(first == null || first >= 0) { message(Messages.TraceNegativeTime) }
        requireLocalized(second == null || second >= 0) { message(Messages.TraceNegativeTime) }
        cursorA = first
        cursorB = second
    }

    fun capture(
        state: SimulationState,
        time: Long = state.propagationTime,
        propagationComplete: Boolean = true,
    ): Boolean {
        requireLocalized(time >= 0) { message(Messages.TraceNegativeTime) }
        if (paused || signals.isEmpty()) return false
        requireLocalized(lastTime == null || time >= lastTime!!) { message(Messages.TraceTimeBackwards) }
        val values = signals.map { signal ->
            val local = if (signal.reference.instancePath.isEmpty()) state
                else state.circuitStates[signal.reference.instancePath]
            val componentId = signal.reference.componentId
            val address = signal.reference.ramAddress
            if (address == null) {
                local?.vectorValues?.get(componentId)?.takeIf { it.width == signal.width }
            } else {
                local?.componentStates?.get(componentId)?.let(MemoryComponents::image)
                    ?.takeIf { address in 0 until it.capacity && it.dataBits == signal.width }
                    ?.let { LogicVector.of(it.dataBits, it[address]) }
            }
        }
        val frame = TraceFrame(time, values)
        val changed = history.lastOrNull() != frame
        val completedChanged = propagationComplete && lastCompletedFrame != frame
        if (propagationComplete) lastCompletedFrame = frame
        if (lastTime == time) history.removeLast()
        history.addLast(frame)
        if (history.size > capacity) {
            history.removeFirst()
            droppedFrames++
        }
        if (changed || completedChanged) onCapture?.invoke(frame, propagationComplete)
        return true
    }

    fun valueAt(signal: TraceSignalReference, time: Long): LogicVector? {
        val index = signals.indexOfFirst { it.reference == signal }
        if (index < 0) return null
        return history.lastOrNull { it.time <= time }?.values?.get(index)
    }

    fun exportCsv(): String = buildString {
        append("time_").append(timeUnit.name.lowercase())
        for (signal in signals) append(',').append(csv("${signal.reference.key}:${signal.label}[${signal.width}]"))
        append('\n')
        for (frame in history) {
            append(frame.time)
            frame.values.forEachIndexed { index, value ->
                append(',').append(value?.let(signals[index].radix::format) ?: "<missing>")
            }
            append('\n')
        }
    }

    /** Export the retained history in signal order, marking time gaps if a uniform grid would be too large. */
    fun exportWaveDrom(displayName: (TraceSignal) -> String = { it.label }): String {
        val timeStep = history.zipWithNext { before, after -> after.time - before.time }.fold(0L, ::gcd)
        val compact = timeStep > 0 && (history.last().time - history.first().time) / timeStep >= MAX_WAVEDROM_PERIODS
        val lanes = signals.mapIndexed { index, signal ->
            val data = mutableListOf<CompanionJsonString>()
            val wave = buildString {
                var previousTime: Long? = null
                var previousValue: LogicVector? = null
                for (frame in history) {
                    val before = previousTime
                    if (before != null) {
                        val periods = (frame.time - before) / timeStep
                        if (compact) {
                            if (periods > 1) append('|')
                        } else {
                            repeat(periods.toInt() - 1) { append('.') }
                        }
                    }
                    val value = frame.values[index]
                    append(when {
                        before != null && previousValue == value -> '.'
                        value == null || !value.isFullyDefined -> 'x'
                        signal.width == 1 -> if (value.value == 0uL) '0' else '1'
                        // Evolution's ChronoPanel writes a bus at its minimum (0) or maximum (all ones) as 0 or 1.
                        value.value == 0uL -> '0'
                        value.value == LogicVector.high(signal.width).value -> '1'
                        else -> {
                            data += CompanionJsonString(signal.radix.format(value))
                            '2'
                        }
                    })
                    previousTime = frame.time
                    previousValue = value
                }
            }
            CompanionJsonObject(buildMap {
                put("name", CompanionJsonString(displayName(signal)))
                put("wave", CompanionJsonString(wave))
                if (data.isNotEmpty()) put("data", CompanionJsonArray(data))
            })
        }
        return CompanionJsonCodec.write(CompanionJsonObject(mapOf("signal" to CompanionJsonArray(lanes))))
    }

    companion object {
        const val MAX_SIGNALS = 256
        private const val MAX_WAVEDROM_PERIODS = 10_000

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        private fun requireUnique(signals: List<TraceSignal>) {
            requireLocalized(signals.map { it.reference }.distinct().size == signals.size) { message(Messages.TraceDuplicateSignal) }
        }
        private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    }
}

object TraceSignals {
    /** Builds one address on demand so even a large RAM stays a single entry in the picker. */
    fun ramCell(signal: TraceSignal, address: Long): TraceSignal? {
        val addressBits = signal.ramAddressBits ?: return null
        if (address !in 0 until (1L shl addressBits)) return null
        val suffix = "[0x${address.toString(16)}]"
        return signal.copy(
            reference = signal.reference.copy(ramAddress = address),
            label = signal.label + suffix,
            labelText = LocalizedText.join(listOf(signal.labelText, LocalizedText.literal(suffix)), separator = ""),
            ramAddressBits = null,
        )
    }

    fun fromProject(project: CircuitProject, registry: ComponentRegistry = ComponentCatalog.registry): List<TraceSignal> {
        val result = mutableListOf<TraceSignal>()
        fun visit(circuit: CircuitDocument, path: String, legacyPath: String, parents: Set<String>) {
            requireLocalized(circuit.id !in parents) { message(Messages.TraceRecursiveProject) }
            for (component in circuit.components) {
                val inputs = component.inputPorts(registry)
                val outputs = component.outputPorts(registry)
                if (inputs.isNotEmpty() || outputs.isNotEmpty()) {
                    requireLocalized(result.size < 10_000) { message(Messages.TraceSelectorLimit) }
                    val observedPort = outputs.firstOrNull() ?: inputs.first()
                    val observedWidth = component.portWidths(registry)[observedPort] ?: component.width
                    result += TraceSignal(TraceSignalReference(path, component.id),
                        component.label.ifBlank { "${component.kind.name} (${component.position.x},${component.position.y})" }, observedWidth,
                        if (component.label.isBlank()) message(Messages.TraceUnnamedSignal, "type" to component.kind.localizedLabel(),
                            "x" to component.position.x, "y" to component.position.y) else LocalizedText.literal(component.label),
                        ramAddressBits = when (component.kind) {
                            ComponentKind.RAM, MemoryComponents.DualRam -> MemoryComponents.configuration(component).addressBits
                            else -> null
                        },
                        legacyInstancePath = legacyPath,
                    )
                }
                if (component.kind == ComponentKind.SUBCIRCUIT) {
                    val child = project.circuit(component.circuitName.orEmpty()) ?: continue
                    val description = component.label.ifEmpty { "${child.name}(${component.position.x},${component.position.y})" }
                    visit(
                        child,
                        if (path.isEmpty()) component.id else "$path/${component.id}",
                        // Logisim 2.7.1 appends a dot after every ancestor and before every ancestor after the first.
                        if (legacyPath.isEmpty()) description else "$legacyPath..$description",
                        parents + circuit.id,
                    )
                }
            }
        }
        visit(project.mainCircuit, "", "", emptySet())
        return result
    }
}

/** Standalone controller for the trace tool; selecting another project resets its history and clock. */
class TraceSession(
    project: CircuitProject,
    signals: List<TraceSignal> = emptyList(),
    capacity: Int = 10_000,
    private val simulator: CircuitSimulator = CircuitSimulator(),
) {
    var project: CircuitProject = project
        private set
    var state: SimulationState = simulator.settle(project)
        private set
    val recorder = TraceRecorder(signals, capacity)

    init { recorder.capture(state) }

    fun advance(inputs: Map<String, LogicVector> = emptyMap()): SimulationState {
        if (recorder.paused) return state
        state = simulator.settle(project, state, tick = true, vectorInputs = inputs,
            observer = { snapshot, time -> recorder.capture(snapshot, time) })
        recorder.capture(state)
        return state
    }

    fun capture(state: SimulationState, time: Long = state.propagationTime) {
        this.state = state
        recorder.capture(state, time)
    }

    fun reset() {
        state = simulator.settle(project)
        val paused = recorder.paused
        recorder.resume()
        recorder.clear()
        recorder.capture(state)
        if (paused) recorder.pause()
    }

    fun changeProject(project: CircuitProject) {
        this.project = project
        recorder.select(emptyList())
        reset()
    }
}
