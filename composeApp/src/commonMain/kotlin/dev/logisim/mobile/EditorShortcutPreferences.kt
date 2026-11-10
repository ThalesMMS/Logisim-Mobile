package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun EditorShortcutPreferences(
    preferences: EditorPreferences,
    onChange: (EditorPreferences) -> Unit,
) {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorShortcut?>(null) }
    val scheme = preferences.shortcutScheme
    val overrides = preferences.shortcutOverrides
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("preferences-shortcuts")) {
        Text(strings(if (expanded) Messages.PreferencesHideShortcuts else Messages.PreferencesConfigureShortcuts))
    }
    if (expanded) {
        EditorShortcut.entries.forEach { command ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preferences-shortcut-${command.name}")
                    .clickable { editing = command },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings(command.messageKey, *command.messageArguments), Modifier.weight(1f))
                Text(effectiveEditorShortcut(command, overrides).label(scheme), color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(
            onClick = { onChange(preferences.copy(shortcutOverrides = emptyMap())) },
            enabled = overrides.isNotEmpty(),
            modifier = Modifier.testTag("preferences-shortcuts-restore"),
        ) {
            Text(strings(Messages.PreferencesRestoreShortcuts))
        }
    }
    editing?.let { command ->
        val current = effectiveEditorShortcut(command, overrides)
        var key by remember(command) { mutableStateOf(current.key) }
        var shift by remember(command) { mutableStateOf(current.shift) }
        val primary = command.defaultBinding.primary
        val keyAndShift = if (shift) "Shift+${key.trim().uppercase()}" else key.trim().uppercase()
        val candidate = if (key.isBlank()) EditorShortcutBinding.Unassigned.copy(primary = primary)
            else EditorShortcutBinding.parse(if (primary) keyAndShift else "Plain+$keyAndShift")
        val conflict = candidate?.let {
            editorShortcutConflict(command, it, overrides)?.let { other -> strings(other.messageKey, *other.messageArguments) }
                ?: it.reservedCanvasAction()?.let { reserved -> strings(reserved) }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(strings(command.messageKey, *command.messageArguments)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth().testTag("preferences-shortcut-key").onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (!primary && !event.isPlainOnlyShortcutKey()) return@onPreviewKeyEvent false
                            val pressed = event.editorShortcutBinding(scheme, allowPlain = !primary) ?: return@onPreviewKeyEvent false
                            if (pressed.primary != primary) return@onPreviewKeyEvent false
                            key = pressed.key
                            shift = pressed.shift
                            true
                        },
                        singleLine = true,
                        label = { Text(strings(Messages.PreferencesShortcutLetter)) },
                        isError = candidate == null || conflict != null,
                        supportingText = {
                            Text(
                                if (conflict == null) strings(Messages.PreferencesShortcutInputHelp)
                                else strings(Messages.PreferencesShortcutConflict, "action" to conflict),
                            )
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preferences-shortcut-shift")
                            .toggleable(shift, role = Role.Switch, onValueChange = { shift = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(strings(Messages.PreferencesShortcutShift), Modifier.weight(1f))
                        Switch(shift, onCheckedChange = null)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = candidate != null && conflict == null,
                    onClick = {
                        if (candidate != null && conflict == null) {
                            val updated = if (candidate == command.defaultBinding) overrides - command
                            else overrides + (command to candidate)
                            onChange(preferences.copy(shortcutOverrides = updated))
                        }
                        editing = null
                    },
                ) {
                    Text(strings(Messages.ActionApply))
                }
            },
            dismissButton = {
                Row {
                    // An empty key unassigns the command, as Evolution allows for toolbar tools 11–14.
                    TextButton(onClick = { key = ""; shift = false }, enabled = key.isNotBlank(),
                        modifier = Modifier.testTag("preferences-shortcut-clear")) {
                        Text(strings(Messages.PreferencesShortcutClear))
                    }
                    TextButton(onClick = { editing = null }) { Text(strings(Messages.ActionCancel)) }
                }
            },
        )
    }
}
