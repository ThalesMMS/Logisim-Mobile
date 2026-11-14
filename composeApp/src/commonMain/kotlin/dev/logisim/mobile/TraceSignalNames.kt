package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalPicker
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.Localizer

/** A trace signal as the picker, timeline, table and order dialog show it, and as TalkBack reads it. */
internal data class TraceSignalName(
    /** The signal's own name, such as "A", distinct among the signals of its instance. */
    val label: String,
    /** The instance path from the main circuit down, such as "main › Sub1 (300,100)". */
    val instance: String,
    /** Whether the signal sits inside a subcircuit instance. */
    val nested: Boolean,
) {
    /** A row's title: the label alone in the main circuit, else the label and its instance, such as "A · main › Sub1". */
    val title: String get() = if (nested) "$label · $instance" else label
}

/**
 * Names of trace signals that never show internal ids (C64, C160). Sibling instances that share a label carry their
 * location ([TraceSignalPicker.instanceNames]); signals of one instance that share a label carry theirs, and an
 * ordinal if even that is shared, so every row, move button and TalkBack description tells its signal apart.
 * [TraceSignalReference.key] stays the identity of the selection.
 */
internal class TraceSignalNames(
    private val mainName: String,
    private val paths: Map<String, List<String>>,
    private val labels: Map<TraceSignalReference, String>,
    private val localize: Localizer,
) {
    /** The human path of an instance, from the main circuit down. */
    fun path(instancePath: String): List<String> =
        paths[instancePath] ?: (listOf(mainName) + instancePath.split('/').filter { it.isNotEmpty() })

    fun of(signal: TraceSignal): TraceSignalName = TraceSignalName(
        labels[signal.reference] ?: localize(signal.labelText),
        traceInstancePath(path(signal.reference.instancePath)),
        signal.reference.instancePath.isNotEmpty(),
    )
}

/** Names for every signal of [signals], which should hold all the project's signals so names do not change with a selection. */
internal fun traceSignalNames(project: CircuitProject, signals: List<TraceSignal>, localize: Localizer): TraceSignalNames {
    val located = runCatching { TraceSignalPicker.instanceNames(project) }.getOrDefault(emptyMap())
    return TraceSignalNames(project.mainCircuitName, numberedInstances(located, localize), signalLabels(signals, localize), localize)
}

/** Sibling instances whose names are still shared, stacked on one point with one label, numbered in a stable order. */
private fun numberedInstances(paths: Map<String, List<String>>, localize: Localizer): Map<String, List<String>> {
    fun parent(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""
    val ordinals = paths.keys.filter { it.isNotEmpty() }
        .groupBy { parent(it) to paths.getValue(it).last() }
        .filterValues { it.size > 1 }
        .flatMap { (_, siblings) -> siblings.sorted().mapIndexed { index, path -> path to index + 1 } }
        .toMap()
    if (ordinals.isEmpty()) return paths
    val result = linkedMapOf<String, List<String>>()
    for (path in paths.keys.sortedBy { key -> if (key.isEmpty()) 0 else key.count { it == '/' } + 1 }) {
        val names = paths.getValue(path)
        if (path.isEmpty()) { result[path] = names; continue }
        val own = ordinals[path]?.let { localize(Messages.TraceSignalNumbered, "signal" to names.last(), "number" to it) } ?: names.last()
        result[path] = (result[parent(path)] ?: names.dropLast(1)) + own
    }
    return paths.keys.associateWith { result.getValue(it) }
}

/** Labels shared inside one instance gain the component's location, then an ordinal that reordering keeps. */
private fun signalLabels(signals: List<TraceSignal>, localize: Localizer): Map<TraceSignalReference, String> {
    val unique = signals.distinctBy { it.reference }
    val plain = unique.associate { it.reference to localize(it.labelText) }
    val shared = plain.entries.groupBy { it.key.instancePath to it.value }.filterValues { it.size > 1 }.keys
    val labels = unique.associate { signal ->
        val label = plain.getValue(signal.reference)
        signal.reference to if ((signal.reference.instancePath to label) in shared) localize(signal.locatedLabelText) else label
    }
    val ordinals = labels.entries.groupBy { it.key.instancePath to it.value }.filterValues { it.size > 1 }.values
        .flatMap { entries -> entries.map { it.key }.sortedBy { it.key }.mapIndexed { index, reference -> reference to index + 1 } }
        .toMap()
    return labels.mapValues { (reference, label) ->
        ordinals[reference]?.let { localize(Messages.TraceSignalNumbered, "signal" to label, "number" to it) } ?: label
    }
}
