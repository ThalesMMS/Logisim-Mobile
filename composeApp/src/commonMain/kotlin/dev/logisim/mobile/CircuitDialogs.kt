package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.EvolutionNames
import dev.logisim.mobile.core.followsEvolutionNames
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * Add circuit… (an empty [id]) or Rename circuit for [project], shared by the circuit list, the header picker and the
 * Project menu. The name is focused and selected so typing starts at once, and the keyboard's Done confirms it.
 */
@Composable
internal fun CircuitNameDialog(
    project: CircuitProject,
    id: String,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val initial = project.circuits.firstOrNull { it.id == id }?.name.orEmpty()
    var name by remember(id) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val normalizedName = name.text.trim()
    val duplicate = project.circuits.any { it.id != id && it.name == normalizedName }
    // Evolution's CircuitAttributes refuses these names (#321); an unchanged name stays acceptable.
    val problem = if (normalizedName == initial || !project.followsEvolutionNames()) null
        else EvolutionNames.circuitNameProblem(normalizedName, project.circuits.firstOrNull { it.id == id })
    val valid = normalizedName.isNotEmpty() && !duplicate && problem == null
    fun submit() {
        if (!valid) return
        if (id.isEmpty()) onCreate(normalizedName) else onRename(id, normalizedName)
        onDismiss()
    }
    val focus = remember(id) { FocusRequester() }
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("circuit-name-dialog"),
        title = { Text(if (id.isEmpty()) strings("project_add_circuit") else strings(Messages.ActionRenameCircuit)) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(strings(Messages.WorkspaceName)) }, singleLine = true,
                isError = duplicate || problem != null, supportingText = {
                    if (duplicate) Text(strings(Messages.ErrorCircuitNameExists)) else problem?.let { Text(strings(it)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("circuit-name-field"))
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = valid, onClick = ::submit, modifier = Modifier.testTag("circuit-name-confirm")) {
            Text(strings(if (id.isEmpty()) "action_create" else Messages.ActionRename))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } })
}

/**
 * Why the circuit [id] of [project] cannot be removed, as Evolution's ProjectCircuitActions.doRemoveCircuit explains:
 * the only circuit first (circuitRemoveLastError), since no other can become main, then the main circuit and a circuit
 * used in another.
 */
internal fun circuitRemovalProblem(project: CircuitProject, id: String): LocalizedText? {
    val circuit = project.circuits.firstOrNull { it.id == id } ?: return null
    return when {
        project.circuits.size == 1 -> message("circuit_remove_last_error")
        circuit.name == project.mainCircuitName -> message("circuit_remove_main_error", "name" to circuit.name)
        project.circuits.any { other -> other.components.any { it.circuitName == circuit.name } } ->
            message("circuit_remove_used_error", "name" to circuit.name)
        else -> null
    }
}

/**
 * Remove circuit…: a circuit that can go is confirmed first; the main circuit and one used as a subcircuit get the
 * reason instead of a silently disabled item.
 */
@Composable
internal fun CircuitRemoveDialog(project: CircuitProject, id: String, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val circuit = project.circuits.firstOrNull { it.id == id }
    if (circuit == null) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    val problem = circuitRemovalProblem(project, id)
    if (problem != null) {
        AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("circuit-remove-blocked"),
            title = { Text(strings("circuit_remove_error_title")) }, text = { Text(strings(problem)) },
            confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("circuit-remove-blocked-close")) {
                Text(strings(Messages.ActionClose))
            } })
    } else {
        AppAlertDialog(onDismissRequest = onDismiss, title = { Text(strings(Messages.ActionDeleteCircuit)) },
            text = { Text(strings(Messages.ConfirmDeleteCircuit, "name" to circuit.name)) },
            confirmButton = { TextButton(onClick = { onRemove(id); onDismiss() }, modifier = Modifier.testTag("circuit-remove-confirm")) {
                Text(strings(Messages.ActionDelete))
            } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } })
    }
}
