package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
    var selected by remember { mutableStateOf(emptySet<String>()) }

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
                TextButton(enabled = !busy, onClick = ::openSource) { Text(strings("project_merge_choose")) }
                for (circuit in source?.circuits.orEmpty()) {
                    val checked = circuit.name in selected
                    Row(
                        Modifier.toggleable(checked, enabled = !busy, role = Role.Checkbox) {
                            selected = if (it) selected + circuit.name else selected - circuit.name
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, onCheckedChange = null, enabled = !busy)
                        Text(circuit.name)
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
                        state.applyProject(ProjectCircuitMerge.copySelected(state.project, imported, selected))
                        onClose()
                    }
                },
            ) { Text(strings("project_merge_title")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(strings(Messages.ActionClose)) } },
    )
}
