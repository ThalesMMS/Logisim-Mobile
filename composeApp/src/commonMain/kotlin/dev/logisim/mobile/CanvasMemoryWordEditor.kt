package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
    title = message("memory_go_to_address"),
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
) = MemoryWordDialog(key, value, title, label, action, onSubmit, onDismiss)

private val stepPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * Moves the word dialog to a neighbouring address. [step] commits the typed text and returns an error, or null after
 * the caller has moved to the next word (the dialog's `key` changes, so the next value starts fully selected).
 */
internal class MemoryWordSteps(
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
    val step: (offset: Int, text: String) -> LocalizedText?,
)

/**
 * Edits one memory word, shared by the canvas and the memory editor. The value starts fully selected so typing
 * replaces it, and an invalid value keeps the dialog open with the error under the field. With [steps], the IME
 * Next action and the Next/Previous buttons commit the word and move on, like Evolution's hex editor caret.
 */
@Composable
internal fun MemoryWordDialog(
    key: Any,
    value: String,
    title: LocalizedText?,
    label: LocalizedText,
    action: LocalizedText,
    onSubmit: (String) -> LocalizedText?,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    steps: MemoryWordSteps? = null,
) {
    val strings = rememberLocalizer()
    val focus = remember(key) { FocusRequester() }
    var draft by remember(key) { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    var error by remember(key) { mutableStateOf<LocalizedText?>(null) }

    fun submit() {
        error = onSubmit(draft.text)
        if (error == null) onDismiss()
    }
    fun step(offset: Int) {
        val current = steps ?: return
        val enabled = if (offset < 0) current.previousEnabled else current.nextEnabled
        if (enabled) error = current.step(offset, draft.text) else submit()
    }

    val nextAvailable = steps?.nextEnabled == true
    val dialogTitle: (@Composable () -> Unit)? = if (title == null) null else { { Text(strings(title), Modifier.testTag("memory-word-title")) } }
    AppAlertDialog(
        modifier = Modifier.editorDialogBounds(),
        onDismissRequest = onDismiss,
        title = dialogTitle,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    draft,
                    { draft = it; error = null },
                    label = { Text(strings(label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { notice -> { Text(strings(notice)) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = keyboardType,
                        imeAction = if (nextAvailable) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }, onNext = { step(1) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("memory-word-field").onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (event.type == KeyEventType.KeyUp) submit()
                            true
                        } else false
                    },
                )
                if (steps != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step(-1) }, enabled = steps.previousEnabled, contentPadding = stepPadding,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memory-word-previous")) {
                            Text(strings("memory_word_previous"), maxLines = 2)
                        }
                        OutlinedButton(onClick = { step(1) }, enabled = steps.nextEnabled, contentPadding = stepPadding,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memory-word-next")) {
                            Text(strings("memory_word_next"), maxLines = 2)
                        }
                    }
                }
            }
            LaunchedEffect(key) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = ::submit, modifier = Modifier.testTag("memory-word-change")) { Text(strings(action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.MemoryCancel)) } },
    )
}
