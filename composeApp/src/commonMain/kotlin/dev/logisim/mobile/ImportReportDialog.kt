package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * Each group leads with what changed, then its circuit and the components it touched by label, kind and position
 * (five at most, then "+N more"), with Show to select them in the circuit. The rule's kind is a small line in the
 * app language; internal rule codes and component IDs are not shown.
 */
@Composable
internal fun ImportReportDialog(
    report: EditorImportReport,
    onDismiss: () -> Unit,
    project: CircuitProject? = null,
    onReveal: ((circuit: String, componentIds: List<String>) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    val groups = remember(report.migrations, project) { importReportGroups(report, project) }
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.exportEditorTestTags(), title = { Text(strings("import_report_title")) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).testTag("editor-import-report"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(strings("import_report_source", "version" to report.sourceVersion, "count" to report.circuitCount))
                report.origin?.let { Text(strings("import_report_origin", "origin" to it)) }
            }
            if (groups.isEmpty()) item { Text(strings("import_report_no_changes")) }
            items(groups) { group ->
                Column(Modifier.testTag("import-report-rule-${group.rule}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(group.notice), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(strings("import_report_circuit", "name" to group.circuit))
                    for (component in group.components) Text(strings(component), style = MaterialTheme.typography.bodySmall)
                    if (group.more > 0) Text(strings.plural("import_report_more", group.more.toLong()), style = MaterialTheme.typography.bodySmall)
                    if (group.repeated > 1) Text(strings("import_report_repeated", "count" to group.repeated), style = MaterialTheme.typography.bodySmall)
                    Text(strings(group.title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onReveal != null && group.componentIds.isNotEmpty()) {
                        val label = strings("import_report_show_a11y", "circuit" to group.circuit)
                        TextButton(onClick = { onReveal(group.circuit, group.componentIds) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                            Text(strings("import_report_show"))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

/**
 * Details beside "Opened with N compatibility adjustments" in the status line (C145): a file whose adjustments only
 * record how it was read opens without the report, and this opens it. The status line itself is the polite live
 * region, so the action is not announced again. It leaves with the status; the File menu item stays.
 */
@Composable
internal fun ImportReportStatusAction(state: EditorState) {
    // While the simulation is unsettled the status line says so instead, and Details would explain the wrong text.
    if (!state.importReportStatusShown || state.simulation.oscillating) return
    val strings = rememberLocalizer()
    val description = strings("import_report_details_a11y")
    TextButton(onClick = state::showImportReport, contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("import-report-status-details")
            .semantics { contentDescription = description }) {
        Text(strings("import_report_details"), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
