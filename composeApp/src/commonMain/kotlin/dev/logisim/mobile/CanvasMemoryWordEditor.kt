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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

internal data class CanvasMemoryWord(val componentId: String, val address: Int)

internal sealed interface CanvasMemoryEdit {
    data class Word(val word: CanvasMemoryWord, val value: String) : CanvasMemoryEdit
    data class Address(val componentId: String, val value: String, val go: (String) -> LocalizedText?) : CanvasMemoryEdit
}

@Composable
internal fun CanvasMemoryWordEditor(
    word: CanvasMemoryWord,
    value: String,
    onEdit: (String) -> LocalizedText?,
    onDismiss: () -> Unit,
) = CanvasMemoryEditorDialog(
    key = word,
    value = value,
    title = message(Messages.MemoryCellTitle, "address" to word.address.toString(16)),
    label = message(Messages.MemoryValueRadix, "base" to 16),
    action = message(Messages.MemoryChange),
    onSubmit = onEdit,
    onDismiss = onDismiss,
)

@Composable
internal fun CanvasMemoryAddressEditor(
    componentId: String,
    address: String,
    onGo: (String) -> LocalizedText?,
    onDismiss: () -> Unit,
) = CanvasMemoryEditorDialog(
    key = componentId,
    value = address,
    title = null,
    label = message(Messages.MemoryStartAddress),
    action = message(Messages.MemoryGo),
    onSubmit = onGo,
    onDismiss = onDismiss,
)

@Composable
private fun CanvasMemoryEditorDialog(
    key: Any,
    value: String,
    title: LocalizedText?,
    label: LocalizedText,
    action: LocalizedText,
    onSubmit: (String) -> LocalizedText?,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val focus = remember(key) { FocusRequester() }
    var draft by remember(key) { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    var error by remember(key) { mutableStateOf<LocalizedText?>(null) }

    fun submit() {
        error = onSubmit(draft.text)
        if (error == null) onDismiss()
    }

    val dialogTitle: (@Composable () -> Unit)? = if (title == null) null else { { Text(strings(title)) } }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = dialogTitle,
        text = {
            OutlinedTextField(
                draft,
                { draft = it; error = null },
                label = { Text(strings(label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { notice -> { Text(strings(notice)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) submit()
                        true
                    } else false
                },
            )
            LaunchedEffect(key) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text(strings(action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.MemoryCancel)) } },
    )
}
