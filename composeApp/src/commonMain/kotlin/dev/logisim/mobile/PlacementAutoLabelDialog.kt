package dev.logisim.mobile

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun PlacementAutoLabelDialog(initial: String, onApply: (String) -> Boolean, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    var label by remember(initial) { mutableStateOf(initial) }
    val trimmed = label.trim()
    val valid = trimmed.isEmpty() || PlacementLabels.isValid(trimmed)
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings("placement_auto_label")) },
        text = {
            OutlinedTextField(label, { label = it }, Modifier.testTag("placement-auto-label-input"),
                label = { Text(strings("placement_auto_label_start")) },
                supportingText = { Text(strings(if (valid) "placement_auto_label_hint" else "placement_auto_label_invalid")) },
                isError = !valid, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (onApply(trimmed)) onDismiss() }, enabled = valid,
                modifier = Modifier.testTag("placement-auto-label-apply")) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
