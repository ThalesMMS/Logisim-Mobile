package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponentStatistics
import dev.logisim.mobile.core.CircuitDiagnostic
import dev.logisim.mobile.core.CircuitDiagnosticAnalyzer
import dev.logisim.mobile.core.CircuitExpansionResult
import dev.logisim.mobile.core.CircuitStatistics
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.DiagnosticCategory
import dev.logisim.mobile.core.DiagnosticReport
import dev.logisim.mobile.core.DiagnosticSeverity
import dev.logisim.mobile.core.DiagnosticTarget
import dev.logisim.mobile.core.ProjectStatisticsSnapshot
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.localization.localizedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

fun diagnosticsEditorTool(registry: ComponentRegistry = ComponentCatalog.registry): EditorTool = EditorTool(
    id = "diagnostics",
    title = message("diagnostic_title").resolve(systemAppLanguage()),
    titleText = message("diagnostic_title"),
) { context ->
    var includeUnconnected by remember { mutableStateOf(true) }
    val latestSimulation by rememberUpdatedState(context.projectSimulation)
    val report by produceState<DiagnosticReport?>(null, context.project, registry, includeUnconnected) {
        value = null
        var analyzed = latestSimulation
        var initial = true
        while (isActive) {
            val state = latestSimulation
            if (initial || state !== analyzed) {
                value = withContext(Dispatchers.Default) {
                    CircuitDiagnosticAnalyzer.analyze(context.project, state, registry, includeUnconnected)
                }
                analyzed = state
                initial = false
            }
            delay(200)
        }
    }
    val statistics by produceState<ProjectStatisticsSnapshot?>(null, context.project, registry) {
        value = null
        value = withContext(Dispatchers.Default) { CircuitStatistics.measure(context.project, registry) }
    }
    val occurrences by produceState<Result<CircuitComponentStatistics>?>(null, context.project, context.document.name, registry) {
        value = null
        value = withContext(Dispatchers.Default) {
            try {
                Result.success(CircuitComponentStatistics.measure(context.project, context.document.name, registry) {
                    ensureActive()
                })
            } catch (error: IllegalArgumentException) {
                Result.failure(error)
            }
        }
    }
    DiagnosticPanel(report, statistics, context.document.id, includeUnconnected,
        onIncludeUnconnectedChange = { includeUnconnected = it },
        onNavigate = { target -> context.navigate(target.editorLocation()) }, occurrences = occurrences, registry = registry)
}

fun DiagnosticTarget.editorLocation(): EditorLocation =
    EditorLocation(circuitId, instancePath, componentIds, wires)

@Composable
fun DiagnosticPanel(
    report: DiagnosticReport?,
    statistics: ProjectStatisticsSnapshot?,
    selectedCircuitId: String,
    includeUnconnected: Boolean,
    onIncludeUnconnectedChange: (Boolean) -> Unit,
    onNavigate: (DiagnosticTarget) -> Unit,
    modifier: Modifier = Modifier,
    occurrences: Result<CircuitComponentStatistics>? = null,
    registry: ComponentRegistry = ComponentCatalog.registry,
) {
    var category by remember { mutableStateOf<DiagnosticCategory?>(null) }
    var showStatistics by remember { mutableStateOf(true) }
    Column(modifier.fillMaxSize()) {
        Text(localizedString("diagnostic_title"), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeUnconnected, onIncludeUnconnectedChange)
            Text(localizedString("diagnostic_include_warnings"), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { category = null }) {
                Text(localizedString("diagnostic_all"), fontWeight = if (category == null) FontWeight.Bold else FontWeight.Normal)
            }
            DiagnosticCategory.entries.forEach { item ->
                TextButton(onClick = { category = item }) {
                    Text(localizedString(item.messageKey()), fontWeight = if (category == item) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        HorizontalDivider()
        val shown = report?.diagnostics.orEmpty().filter { category == null || it.category == category }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(localizedString(diagnosticReportCountNotice(report?.errors ?: 0, report?.warnings ?: 0)),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (shown.isEmpty()) item {
                Text(localizedString(if (report == null) "diagnostic_running" else "diagnostic_empty"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            }
            items(shown, key = { it.id }) { diagnostic ->
                DiagnosticItem(diagnostic, diagnostic.target.circuitId in report?.topologies.orEmpty()) {
                    onNavigate(diagnostic.target)
                }
            }
            item {
                TextButton(onClick = { showStatistics = !showStatistics }) {
                    Text(localizedString("diagnostic_stats_title"))
                }
                if (showStatistics && statistics != null) StatisticsContent(statistics, selectedCircuitId, occurrences, registry)
            }
        }
    }
}

@Composable
private fun DiagnosticItem(diagnostic: CircuitDiagnostic, navigable: Boolean, onNavigate: () -> Unit) {
    val severity = localizedString(diagnostic.severity.messageKey())
    val category = localizedString(diagnostic.category.messageKey())
    val description = localizedString(diagnostic.notice)
    val color = when (diagnostic.severity) {
        DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        DiagnosticSeverity.INFORMATION -> MaterialTheme.colorScheme.onSurface
    }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.padding(10.dp).semantics(mergeDescendants = true) {
            contentDescription = "$severity. $category. $description"
        }) {
            Text("$severity · $category", color = color, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (navigable) OutlinedButton(onNavigate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(localizedString("diagnostic_go_to_origin"))
            }
        }
    }
}

@Composable
private fun StatisticsContent(
    statistics: ProjectStatisticsSnapshot,
    selectedCircuitId: String,
    occurrences: Result<CircuitComponentStatistics>?,
    registry: ComponentRegistry,
) {
    val circuit = statistics.circuits[selectedCircuitId]
    val componentKinds = remember(registry) {
        registry.definitions.associate { (it.library to it.fileName) to it.kind }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (circuit != null) {
            Text(localizedString("diagnostic_circuit_name", "name" to circuit.circuitName), fontWeight = FontWeight.Bold)
            Text(localizedString("diagnostic_stats_components", "count" to circuit.componentCount))
            Text(localizedString("diagnostic_stats_wires", "count" to circuit.wireSegmentCount))
            Text(localizedString("diagnostic_stats_nets", "count" to circuit.netCount))
            Text(localizedString("diagnostic_stats_terminals", "count" to circuit.terminalCount))
            Text(localizedString("diagnostic_stats_junctions", "count" to circuit.junctionCount))
            if (circuit.hasIncompleteTopology) Text(localizedString("diagnostic_stats_partial",
                "components" to circuit.unresolvedComponentIds.sorted().joinToString(", ")),
                color = MaterialTheme.colorScheme.error)
            if (occurrences?.isFailure == true) {
                Text(localizedString("diagnostic_stats_invalid_hierarchy"), color = MaterialTheme.colorScheme.error)
            }
            occurrences?.getOrNull()?.rows?.forEach { row ->
                val sourceLibrary = row.library
                val library = when {
                    sourceLibrary == null -> localizedString("diagnostic_stats_project_library")
                    row.registeredComponent -> localizedString(statisticsLibraryLabel(sourceLibrary))
                    else -> sourceLibrary
                }
                val kind = sourceLibrary?.let { componentKinds[it to row.name] }
                val name = when {
                    !row.registeredComponent -> row.name
                    sourceLibrary == "#Wiring" && row.name == "Pin" -> localizedString(Messages.DiagnosticStatsPin)
                    kind != null -> localizedString(kind.localizedLabel().copy(fallback = row.name))
                    else -> row.name
                }
                Text(localizedString("diagnostic_stats_component_library", "name" to name, "library" to library),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text(localizedString("diagnostic_stats_occurrences", "local" to row.counts.local,
                    "unique" to row.counts.unique, "recursive" to row.counts.recursive),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(localizedString("diagnostic_stats_project"), fontWeight = FontWeight.Bold)
        Text(localizedString("diagnostic_stats_circuits", "count" to statistics.circuitCount))
        Text(localizedString("diagnostic_stats_components", "count" to statistics.definitionComponentCount))
        when (val expansion = statistics.expansion) {
            is CircuitExpansionResult.Complete -> Text(localizedString("diagnostic_stats_instances", "count" to expansion.statistics.instanceCount))
            is CircuitExpansionResult.Invalid -> Text(localizedString("diagnostic_stats_invalid_hierarchy"), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun statisticsLibraryLabel(library: String): String = when (library) {
    "#FPArithmetic" -> Messages.LibraryFloat
    "#Plexers" -> Messages.LibraryPlexers
    "#TTL" -> Messages.LibraryTtl
    "#Input/Output-Extra" -> Messages.LibraryExtra
    else -> libraryLabel(library)
}

private fun DiagnosticSeverity.messageKey(): String = when (this) {
    DiagnosticSeverity.ERROR -> "diagnostic_error"
    DiagnosticSeverity.WARNING -> "diagnostic_warning"
    DiagnosticSeverity.INFORMATION -> "diagnostic_information"
}

private fun DiagnosticCategory.messageKey(): String = when (this) {
    DiagnosticCategory.ELECTRICAL -> "diagnostic_electrical"
    DiagnosticCategory.DOCUMENT -> "diagnostic_document"
    DiagnosticCategory.EXECUTION -> "diagnostic_execution"
}

internal fun diagnosticReportCountNotice(errors: Int, warnings: Int): LocalizedText =
    message("diagnostic_report_counts",
        "errors" to LocalizedText("diagnostic_error_count", quantity = errors.toLong()),
        "warnings" to LocalizedText("diagnostic_warning_count", quantity = warnings.toLong()))
