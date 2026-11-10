package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun ImportReportDialog(report: EditorImportReport, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val groups = remember(report.migrations) { report.migrations.groupBy { Triple(it.rule, it.circuit, it.notice) }.values.toList() }
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.exportEditorTestTags(), title = { Text(strings("import_report_title")) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).testTag("editor-import-report"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(strings("import_report_source", "version" to report.sourceVersion, "count" to report.circuitCount))
                report.origin?.let { Text(strings("import_report_origin", "origin" to it)) }
            }
            if (groups.isEmpty()) item { Text(strings("import_report_no_changes")) }
            items(groups) { group ->
                val first = group.first()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings("import_report_rule", "code" to first.rule), style = MaterialTheme.typography.labelLarge)
                    Text(strings("import_report_circuit", "name" to first.circuit))
                    val components = group.mapNotNull { it.componentId }.distinct()
                    if (components.isNotEmpty()) Text(strings("import_report_components", "ids" to components.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall)
                    Text(strings(first.notice))
                    if (group.size > 1) Text(strings("import_report_repeated", "count" to group.size), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}
