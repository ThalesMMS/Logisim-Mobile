package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun AnalysisColumnDialog(
    names: List<String>,
    index: Int,
    input: Boolean,
    onDismiss: () -> Unit,
    onApply: (name: String, destination: Int) -> Unit,
) {
    val strings = rememberLocalizer()
    var name by remember(names, index, input) { mutableStateOf(names[index]) }
    var position by remember(names, index, input) { mutableStateOf((index + 1).toString()) }
    val normalized = if (name == names[index]) name else name.trim()
    val duplicate = names.indices.any { it != index && names[it] == normalized }
    val destination = position.toIntOrNull()?.takeIf { it in 1..names.size }?.minus(1)
    val changed = normalized != names[index] || destination != index
    val editMessage = if (input) Messages.AnalysisEditInputColumn else Messages.AnalysisEditOutputColumn
    val duplicateMessage = if (input) Messages.AnalysisUniqueInputs else Messages.AnalysisUniqueOutputs

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings(editMessage, "name" to names[index])) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(strings(Messages.WorkspaceName)) }, isError = duplicate,
                    supportingText = { if (duplicate) Text(strings(duplicateMessage)) })
                if (names.size > 1) {
                    OutlinedTextField(position, { position = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(strings(Messages.AnalysisColumnPosition, "max" to names.size)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = destination == null)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed && normalized.isNotEmpty() && !duplicate && destination != null, onClick = {
                destination?.let { onApply(normalized, it) }
            }) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
