package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.WorkspaceCatalogResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.time.Clock

/** Each entry opens on tap; a trailing button removes one whose file moved or is no longer wanted. */
@Composable
internal fun WorkspaceRecentFiles(
    files: List<WorkspaceRecentFile>, onDismiss: () -> Unit, onOpen: (WorkspaceRecentFile) -> Unit,
    onRemove: ((WorkspaceRecentFile) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(strings(Messages.WorkspaceOpenRecent)) }, text = {
        if (files.isEmpty()) Text(strings(Messages.WorkspaceNoRecentFiles))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(files) { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpen(file) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(file.name)
                            val kind = when (file) {
                                is WorkspaceRecentFile.Original -> Messages.WorkspaceRecentOriginal
                                is WorkspaceRecentFile.Copy -> Messages.WorkspaceRecentCopy
                            }
                            Text(strings(kind), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (onRemove != null) {
                        val remove = strings("workspace_remove_recent", "name" to file.name)
                        IconButton(onClick = { onRemove(file) }, modifier = Modifier.size(48.dp).semantics { contentDescription = remove }) {
                            Text("×", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

/** Mirrors the catalog's name check (WorkspaceCatalogRepository.checkedName), so the dialog explains a rejection first. */
internal fun validWorkspaceProjectName(name: String): Boolean = name.trim().let { trimmed ->
    trimmed.isNotEmpty() && trimmed.length <= 200 && trimmed.none { it.code < 32 }
}

/**
 * The owner supplies a fresh catalog after each action and keeps unsaved edits outside this panel.
 * [onCreate] and [onRename] report `null` once the catalog accepted the name, or the notice explaining the rejection.
 */
@Composable
fun WorkspacePanel(
    catalog: WorkspaceCatalogResult,
    onOpen: (WorkspaceProjectId) -> Unit,
    onCreate: (String, (LocalizedText?) -> Unit) -> Unit,
    onDuplicate: (WorkspaceProjectId, Long) -> Unit,
    onRename: (WorkspaceProjectId, Long, String, (LocalizedText?) -> Unit) -> Unit,
    onDelete: (WorkspaceProjectId, Long) -> Unit,
    onRestore: (WorkspaceProjectId, Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Deletes a trashed project for good, after the panel's own confirmation. */
    onPurge: ((WorkspaceProjectId, Long) -> Unit)? = null,
    /** Deletes every trashed project for good, after the panel's own confirmation. */
    onEmptyTrash: (() -> Unit)? = null,
    /** What New project offers as the name ("Untitled", "Untitled 2"...). */
    newProjectName: String = "",
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val strings = rememberLocalizer()
    var showDeleted by remember { mutableStateOf(false) }
    var nameTarget by remember { mutableStateOf<WorkspaceProjectInfo?>(null) }
    var createName by remember { mutableStateOf(false) }
    var purgeTarget by remember { mutableStateOf<WorkspaceProjectInfo?>(null) }
    var emptyingTrash by remember { mutableStateOf(false) }
    val now = remember(catalog) { nowMillis() }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings(Messages.WorkspaceProjects), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            Button(onClick = { createName = true }, enabled = !catalog.readOnly) { Text(strings(Messages.WorkspaceNewProject)) }
        }
        val projects = catalog.projects.filter { it.deleted == showDeleted }.sortedByDescending { it.updatedAtMillis }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The label toggles too, and TalkBack reads one checkbox named "Show trash".
            Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("workspace-show-trash")
                .toggleable(value = showDeleted, role = Role.Checkbox, onValueChange = { showDeleted = it })
                .padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(showDeleted, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                Text(strings(Messages.WorkspaceShowTrash))
            }
            if (showDeleted && projects.isNotEmpty() && onEmptyTrash != null) {
                TextButton(onClick = { emptyingTrash = true }, enabled = !catalog.readOnly,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-empty-trash")) {
                    Text(strings("workspace_empty_trash"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (projects.isEmpty()) Text(if (showDeleted) strings(Messages.WorkspaceTrashEmpty) else strings(Messages.WorkspaceEmpty))
        val sharedNames = projects.groupingBy { it.name.trim().lowercase() }.eachCount().filterValues { it > 1 }.keys
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(projects, key = { it.id.value }) { project ->
                WorkspaceProjectRow(project, active = catalog.activeProjectId == project.id, readOnly = catalog.readOnly,
                    detail = strings(projectEditedText(project, now, project.name.trim().lowercase() in sharedNames)),
                    onOpen = { onOpen(project.id) }, onDuplicate = { onDuplicate(project.id, project.revision) },
                    onRename = { nameTarget = project }, onDelete = { onDelete(project.id, project.revision) },
                    onRestore = { onRestore(project.id, project.revision) },
                    onPurge = onPurge?.let { { purgeTarget = project } })
                HorizontalDivider()
            }
        }
    }
    if (createName || nameTarget != null) {
        val target = nameTarget
        WorkspaceNameDialog(target, newProjectName, onDismiss = { createName = false; nameTarget = null }) { name, done ->
            if (target == null) onCreate(name, done) else onRename(target.id, target.revision, name, done)
        }
    }
    purgeTarget?.let { project ->
        PermanentDeletionDialog(strings("workspace_delete_permanently_title", "name" to project.name),
            strings("workspace_delete_permanently_explanation"), onDismiss = { purgeTarget = null }) {
            purgeTarget = null
            onPurge?.invoke(project.id, project.revision)
        }
    }
    if (emptyingTrash) {
        val count = catalog.projects.count { it.deleted }.toLong()
        PermanentDeletionDialog(strings("workspace_empty_trash_title"), strings.plural("workspace_empty_trash_explanation", count),
            confirm = strings("workspace_empty_trash"), onDismiss = { emptyingTrash = false }) {
            emptyingTrash = false
            onEmptyTrash?.invoke()
        }
    }
}

/** States that the deletion cannot be undone; the destructive action is never the default and Cancel is always there. */
@Composable
private fun PermanentDeletionDialog(title: String, explanation: String, confirm: String? = null,
                                    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = rememberLocalizer()
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("workspace-delete-dialog"),
        title = { Text(title) }, text = { Text(explanation) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-delete-confirm")) {
                Text(confirm ?: strings("workspace_delete_permanently"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        })
}

/** A tap opens the project; the secondary actions sit in one labelled overflow menu instead of a row of buttons. */
@Composable
private fun WorkspaceProjectRow(
    project: WorkspaceProjectInfo, active: Boolean, readOnly: Boolean, detail: String,
    onOpen: () -> Unit, onDuplicate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit,
    onPurge: (() -> Unit)?,
) {
    val strings = rememberLocalizer()
    val opens = if (project.deleted) Modifier else Modifier.testTag("workspace-project-${project.id.value}")
        .clickable(onClickLabel = strings("workspace_open_named", "name" to project.name), role = Role.Button, onClick = onOpen)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(opens), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (active) Text(strings("workspace_currently_open"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        if (project.deleted) {
            TextButton(onClick = onRestore, enabled = !readOnly, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Messages.WorkspaceRestore))
            }
            if (onPurge != null) {
                var menu by remember { mutableStateOf(false) }
                val more = strings("workspace_more_actions_named", "name" to project.name)
                Box {
                    IconButton(onClick = { menu = true }, enabled = !readOnly,
                        modifier = Modifier.size(48.dp).testTag("workspace-project-actions-${project.id.value}")
                            .semantics { contentDescription = more }) {
                        EditorIconGlyph(EditorIcon.MORE, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, enabled = !readOnly)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(strings("workspace_delete_permanently"), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onPurge() },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-project-delete-permanently"))
                    }
                }
            }
        } else {
            var menu by remember { mutableStateOf(false) }
            val more = strings("workspace_more_actions_named", "name" to project.name)
            Box {
                IconButton(onClick = { menu = true }, enabled = !readOnly,
                    modifier = Modifier.size(48.dp).testTag("workspace-project-actions-${project.id.value}")
                        .semantics { contentDescription = more }) {
                    EditorIconGlyph(EditorIcon.MORE, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface, enabled = !readOnly)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(strings(Messages.ActionDuplicate)) }, onClick = { menu = false; onDuplicate() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-project-duplicate"))
                    DropdownMenuItem(text = { Text(strings(Messages.ActionRename)) }, onClick = { menu = false; onRename() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-project-rename"))
                    DropdownMenuItem(text = { Text(strings(Messages.WorkspaceMoveToTrash)) }, onClick = { menu = false; onDelete() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-project-trash"))
                }
            }
        }
    }
}

/**
 * New project (empty) or Rename (the current name, all selected so typing replaces it). Done on the keyboard submits a
 * valid name, and the dialog stays open with the catalog's reason when it rejects one.
 */
@Composable
private fun WorkspaceNameDialog(
    target: WorkspaceProjectInfo?,
    newName: String,
    onDismiss: () -> Unit,
    onSubmit: (String, (LocalizedText?) -> Unit) -> Unit,
) {
    val strings = rememberLocalizer()
    val initial = target?.name ?: newName
    var value by remember(target?.id) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var edited by remember(target?.id) { mutableStateOf(false) }
    var pending by remember(target?.id) { mutableStateOf(false) }
    var rejection by remember(target?.id) { mutableStateOf<LocalizedText?>(null) }
    val focus = remember { FocusRequester() }
    val valid = validWorkspaceProjectName(value.text)
    fun submit() {
        if (!valid || pending) return
        pending = true
        rejection = null
        onSubmit(value.text) { failure ->
            pending = false
            if (failure == null) onDismiss() else rejection = failure
        }
    }
    val error =rejection ?: if (edited && !valid) LocalizedText(Messages.WorkspaceErrorName) else null
    AppAlertDialog(onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(if (target == null) strings(Messages.WorkspaceNewProject) else strings(Messages.WorkspaceRenameProject)) },
        text = {
            OutlinedTextField(value, { next -> if (next.text != value.text) { edited = true; rejection = null }; value = next },
                label = { Text(strings(Messages.WorkspaceName)) }, singleLine = true, isError = error != null,
                supportingText = error?.let { notice -> { Text(strings(notice)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus).testTag("workspace-name-field"))
            // In the dialog's own composition, once the field exists: no tap needed before typing.
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = valid && !pending) {
                Text(if (target == null) strings("workspace_create") else strings(Messages.ActionRename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !pending) { Text(strings(Messages.ActionCancel)) } })
}
