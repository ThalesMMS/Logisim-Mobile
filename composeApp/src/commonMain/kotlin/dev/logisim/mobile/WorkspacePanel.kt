package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.WorkspaceCatalogResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo

@Composable
internal fun WorkspaceRecentFiles(files: List<WorkspaceRecentFile>, onDismiss: () -> Unit, onOpen: (WorkspaceRecentFile) -> Unit) {
    val strings = rememberLocalizer()
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(strings(Messages.WorkspaceOpenRecent)) }, text = {
        if (files.isEmpty()) Text(strings(Messages.WorkspaceNoRecentFiles))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(files) { file ->
                TextButton(onClick = { onOpen(file) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(file.name)
                        val kind = when (file) {
                            is WorkspaceRecentFile.Original -> Messages.WorkspaceRecentOriginal
                            is WorkspaceRecentFile.Copy -> Messages.WorkspaceRecentCopy
                        }
                        Text(strings(kind), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

/** The owner supplies a fresh catalog after each action and keeps unsaved edits outside this panel. */
@Composable
fun WorkspacePanel(
    catalog: WorkspaceCatalogResult,
    onOpen: (WorkspaceProjectId) -> Unit,
    onCreate: (String) -> Unit,
    onDuplicate: (WorkspaceProjectId, Long) -> Unit,
    onRename: (WorkspaceProjectId, Long, String) -> Unit,
    onDelete: (WorkspaceProjectId, Long) -> Unit,
    onRestore: (WorkspaceProjectId, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberLocalizer()
    var showDeleted by remember { mutableStateOf(false) }
    var nameTarget by remember { mutableStateOf<WorkspaceProjectInfo?>(null) }
    var createName by remember { mutableStateOf(false) }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings(Messages.WorkspaceProjects), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { createName = true }, enabled = !catalog.readOnly) { Text(strings(Messages.WorkspaceNewProject)) }
        }
        catalog.notices.forEach { Text(strings(it), color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(showDeleted, onCheckedChange = { showDeleted = it },
                modifier = Modifier.semantics { contentDescription = strings(Messages.WorkspaceA11yShowTrash) })
            Text(strings(Messages.WorkspaceShowTrash))
        }
        val projects = catalog.projects.filter { it.deleted == showDeleted }.sortedByDescending { it.updatedAtMillis }
        if (projects.isEmpty()) Text(if (showDeleted) strings(Messages.WorkspaceTrashEmpty) else strings(Messages.WorkspaceEmpty))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(projects, key = { it.id.value }) { project ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(strings(Messages.WorkspaceRevision, "revision" to project.revision, "id" to project.id.value.take(8)), style = MaterialTheme.typography.bodySmall)
                    if (catalog.activeProjectId == project.id) Text(strings(Messages.WorkspaceOpenProject), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (project.deleted) {
                            TextButton(onClick = { onRestore(project.id, project.revision) }, enabled = !catalog.readOnly) { Text(strings(Messages.WorkspaceRestore)) }
                        } else {
                            TextButton(onClick = { onOpen(project.id) }) { Text(strings(Messages.ActionOpen)) }
                            TextButton(onClick = { onDuplicate(project.id, project.revision) }, enabled = !catalog.readOnly) { Text(strings(Messages.ActionDuplicate)) }
                            TextButton(onClick = { nameTarget = project }, enabled = !catalog.readOnly) { Text(strings(Messages.ActionRename)) }
                        }
                    }
                    if (!project.deleted) TextButton(onClick = { onDelete(project.id, project.revision) }, enabled = !catalog.readOnly) {
                        Text(strings(Messages.WorkspaceMoveToTrash))
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (createName || nameTarget != null) {
        val target = nameTarget
        var name by remember(target?.id, createName) { mutableStateOf(target?.name.orEmpty()) }
        AppAlertDialog(onDismissRequest = { createName = false; nameTarget = null },
            title = { Text(if (target == null) strings(Messages.WorkspaceNewProject) else strings(Messages.WorkspaceRenameProject)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(strings(Messages.WorkspaceName)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) },
            confirmButton = { TextButton(onClick = {
                if (target == null) onCreate(name) else onRename(target.id, target.revision, name)
                createName = false; nameTarget = null
            }, enabled = name.isNotBlank() && name.length <= 200) { Text(strings(Messages.ActionSave)) } },
            dismissButton = { TextButton(onClick = { createName = false; nameTarget = null }) { Text(strings(Messages.ActionCancel)) } })
    }
}
