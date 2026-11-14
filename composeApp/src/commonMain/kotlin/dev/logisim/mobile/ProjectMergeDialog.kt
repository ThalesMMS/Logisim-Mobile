package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ProjectCircuitMerge
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "1 circuit will be copied (selected: 1, dependencies: 0)": plural on the total the merge copies. */
internal fun projectMergeSummary(total: Int, dependencies: Int): LocalizedText = LocalizedText("project_merge_summary",
    arguments = mapOf("selected" to (total - dependencies).toString(), "dependencies" to dependencies.toString()), quantity = total.toLong())

@Composable
internal fun ProjectMergeDialog(state: EditorState, assets: DocumentAssetActions, onClose: () -> Unit) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val identity = remember { state.documentIdentity }
    val revision = remember { state.revision }
    val registry = remember { state.componentRegistry }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
    var source by remember { mutableStateOf<CircuitProject?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // What the merge will copy and rename, as Evolution's merge dialog summarizes it before the merge.
    val plan = remember(source, selected) {
        source?.takeIf { selected.isNotEmpty() }?.let { runCatching { ProjectCircuitMerge.plan(state.project, it, selected) }.getOrNull() }
    }

    fun canMergeIntoCurrentProject() = state.documentWritable && state.interactionsEnabled &&
        state.documentIdentity == identity && state.revision == revision && state.componentRegistry === registry

    fun openSource() {
        busy = true
        notice = null
        try {
            assets.open(listOf("application/octet-stream"), PORTABLE_PROJECT_MAXIMUM_BYTES) { result ->
                if (!scope.isActive) return@open
                when (result) {
                    AssetOpenResult.Cancelled -> busy = false
                    is AssetOpenResult.Failure -> {
                        busy = false
                        notice = result.notice
                    }
                    is AssetOpenResult.Opened -> scope.launch {
                        val imported = withContext(Dispatchers.Default) {
                            readActiveProjectLibrary(result.asset, registry)
                        }
                        busy = false
                        when (imported) {
                            is CircProjectImportResult.Failure -> notice = LocalizedText.join(imported.notices)
                            is CircProjectImportResult.Success -> {
                                source = imported.project
                                sourceName = result.asset.name
                                selected = imported.project.circuits.mapTo(mutableSetOf()) { it.name }
                            }
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            busy = false
            notice = portableProjectNotice(failure, "project_merge_failed")
        }
    }

    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags().testTag("project-merge"),
        onDismissRequest = onClose,
        title = { Text(strings("project_merge_title")) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(strings("project_merge_selection"))
                TextButton(enabled = !busy, onClick = ::openSource, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(strings("project_merge_choose"))
                }
                sourceName?.let { Text(strings("project_merge_file", "name" to it), style = MaterialTheme.typography.bodySmall) }
                val project = source
                for (circuit in project?.circuits.orEmpty()) {
                    val checked = circuit.name in selected
                    val uses = ProjectCircuitMerge.uses(checkNotNull(project), circuit.name)
                    // The whole row toggles, at least 48 dp tall; TalkBack reads one checkbox with what the circuit uses.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project-merge-circuit")
                            .toggleable(checked, enabled = !busy, role = Role.Checkbox) {
                                selected = if (it) selected + circuit.name else selected - circuit.name
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, onCheckedChange = null, enabled = !busy, modifier = Modifier.padding(end = 8.dp))
                        Column {
                            Text(circuit.name)
                            if (uses.isNotEmpty()) Text(strings("project_merge_uses", "names" to uses.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                plan?.let { plan ->
                    Text(strings(projectMergeSummary(plan.included.size, plan.dependencies.size)), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp))
                    if (plan.renames.isNotEmpty()) {
                        Text(strings("project_merge_conflicts"), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                        for ((old, new) in plan.renames) Text(strings("project_merge_rename", "old" to old, "new" to new))
                    }
                }
                if (busy) LinearProgressIndicator()
                notice?.let { Text(strings(it)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    val imported = source
                    if (!canMergeIntoCurrentProject()) {
                        notice = message("portable_request_obsolete")
                    } else if (imported != null) {
                        val merged = ProjectCircuitMerge.plan(state.project, imported, selected)
                        state.applyProject(ProjectCircuitMerge.apply(state.project, imported, merged))
                        // Say what arrived and under which names, since renamed circuits are not what the user picked.
                        val count = merged.included.size.toLong()
                        state.reportStatus(if (merged.renames.isEmpty()) LocalizedText("project_merge_done", quantity = count)
                            else LocalizedText("project_merge_done_renamed", quantity = count, localizedArguments = mapOf("renames" to
                                LocalizedText.join(merged.renames.map { (old, new) -> message("project_merge_rename", "old" to old, "new" to new) }, ", "))))
                        onClose()
                    }
                },
            ) { Text(strings("project_merge_title")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(strings(Messages.ActionClose)) } },
    )
}
