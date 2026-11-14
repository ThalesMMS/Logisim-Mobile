package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.SocAddressSpace
import dev.logisim.mobile.core.SocBusComponentState
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

internal data class SocTracePath(val instances: List<String>, val componentId: String, val localCircuitId: String? = null) {
    val label: String get() = (instances + componentId).joinToString(" / ")
}

/** A bus whose trace can be compared; [components] are those of its circuit, which name its initiators and responders. */
internal data class SocTraceBus(
    val path: SocTracePath,
    val breadcrumb: String,
    val bus: SocAddressSpace,
    val components: List<CircuitComponent> = emptyList(),
)

/**
 * The title of each bus: its readable path ("Main / Board [2] / shared"), never the internal ids of [SocTracePath.label].
 * The local view of an open circuit says so; two that would still read the same are numbered in list order.
 */
internal fun socTraceBusTitles(buses: List<SocTraceBus>): Map<SocTracePath, LocalizedText> {
    val groups = buses.groupBy { it.breadcrumb to (it.path.localCircuitId != null) }
    return buses.associate { entry ->
        val same = groups.getValue(entry.breadcrumb to (entry.path.localCircuitId != null))
        val name = if (same.size < 2) LocalizedText.literal(entry.breadcrumb)
            else message("soc_bus_trace_path_numbered", "name" to entry.breadcrumb, "number" to same.indexOf(entry) + 1)
        entry.path to if (entry.path.localCircuitId == null) name else message("soc_bus_trace_local_view", "name" to name)
    }
}

internal fun socTraceBuses(project: CircuitProject, root: SimulationState): List<SocTraceBus> = buildList {
    addAll(localSocTraceBuses(project.mainCircuit, root, emptyList(), listOf(project.mainCircuit.name)))
    for (row in ProjectNavigation.rows(project)) {
        if (row.kind != ProjectNavigationKind.INSTANCE) continue
        val path = row.target.instancePath
        val state = root.circuitStates[path.joinToString("/")] ?: continue
        val circuit = project.circuit(row.target.circuitName) ?: continue
        addAll(localSocTraceBuses(circuit, state, path, row.breadcrumb))
    }
}

private fun localSocTraceBuses(
    circuit: CircuitDocument,
    state: SimulationState,
    instances: List<String>,
    breadcrumb: List<String>,
    localCircuitId: String? = null,
): List<SocTraceBus> = circuit.components.mapNotNull { component ->
    if (component.kind != SocComponents.BUS) return@mapNotNull null
    val bus = (state.componentStates[component.id] as? SocBusComponentState)?.bus ?: return@mapNotNull null
    SocTraceBus(
        SocTracePath(instances, component.id, localCircuitId),
        (breadcrumb + component.label.ifBlank { bus.id }).joinToString(" / "),
        bus,
        circuit.components,
    )
}

@Composable
internal fun SocTraceComparison(context: EditorToolContext, selectedBusId: String = "") {
    val strings = rememberLocalizer()
    val path = context.activeInstancePath
    val root = context.projectSimulation
    val activeState = if (path.isEmpty()) root else root.circuitStates[path.joinToString("/")]
    val projectBuses = socTraceBuses(context.project, root)
    val localBuses = if (context.simulation === activeState) emptyList()
        else localSocTraceBuses(context.document, context.simulation, path, listOf(context.document.name), context.document.id)
    val buses = projectBuses + localBuses
    if (buses.isEmpty()) return

    val preferred = (if (context.simulation === activeState) projectBuses else localBuses)
        .firstOrNull { it.path.instances == path && it.bus.id == selectedBusId }
    var selection by remember(context.documentIdentity) { mutableStateOf<Set<SocTracePath>?>(null) }
    val selected = selection ?: (listOfNotNull(preferred) + buses).distinctBy { it.path }.take(2).map { it.path }.toSet()
    val compared = buses.filter { it.path in selected }
    val titles = socTraceBusTitles(buses)

    Text(strings("soc_bus_trace_compare"), style = MaterialTheme.typography.titleMedium)
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("soc-trace-bus-selectors"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in buses) {
            FilterChip(
                selected = entry.path in selected,
                onClick = {
                    val available = compared.map { it.path }.toSet()
                    selection = if (entry.path in available) available - entry.path else available + entry.path
                },
                label = { Text(strings(titles.getValue(entry.path))) },
            )
        }
    }

    if (compared.isEmpty()) {
        Text(strings("soc_bus_trace_select"))
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in compared) {
            OutlinedCard(Modifier.width(168.dp).testTag("soc-trace-column")) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings(titles.getValue(entry.path)), style = MaterialTheme.typography.titleSmall)
                    // Initiators and responders by name, as the component chips show them.
                    val names = socComponentNames(entry.components.filter { it.kind in SocComponents.kinds }) {
                        strings("component_${it.kind.name.lowercase()}")
                    }
                    fun who(id: String?) = socBusPartyName(id, names)?.let { strings(it) } ?: strings("soc_bus_trace_unknown")
                    val trace = entry.bus.trace.takeLast(64).asReversed()
                    if (trace.isEmpty()) Text(strings("soc_bus_trace_empty"))
                    for (transaction in trace) {
                        val request = transaction.request
                        val reply = transaction.reply
                        val operation = when {
                            request.read && request.write -> "soc_bus_trace_read_write"
                            request.write -> "soc_bus_trace_write"
                            request.read -> "soc_bus_trace_read"
                            else -> "soc_bus_trace_no_access"
                        }
                        Column {
                            Text(strings("soc_bus_trace_request", "sequence" to transaction.sequence,
                                "initiator" to who(request.initiator),
                                "operation" to strings(operation, "bits" to request.size.bytes * 8),
                                "address" to request.address.traceHex()),
                                fontFamily = FontFamily.Monospace)
                            if (request.write) Text(strings("soc_bus_trace_write_data", "data" to request.writeData.traceHex()),
                                fontFamily = FontFamily.Monospace)
                            val response = reply.error?.notice?.let { strings(it) }
                                ?: if (request.read) reply.data.traceHex() else strings("soc_bus_trace_success")
                            Text(strings("soc_bus_trace_response", "responder" to who(reply.responder),
                                "response" to response), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun UInt.traceHex(): String = toString(16).padStart(8, '0').uppercase()
