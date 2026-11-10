package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.round
import kotlin.math.roundToInt

private val exactZoomRange = 5f..1000f
private val zoomDecimal = Regex("[0-9]+([.,][0-9]+)?")

internal fun parseZoomPercent(text: String): Float? {
    val decimal = text.trim().removeSuffix("%").trim()
    if (!zoomDecimal.matches(decimal)) return null
    return decimal.replace(',', '.').toDoubleOrNull()?.takeIf {
        it >= exactZoomRange.start && it <= exactZoomRange.endInclusive
    }?.toFloat()
}

@Composable
internal fun EditorZoomDialog(scale: Float, onApply: (Float) -> Unit, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val currentPercent = scale.toDouble() * 100
    val focus = remember { FocusRequester() }
    var draft by remember {
        val value = (round(currentPercent * 10_000) / 10_000).toString().removeSuffix(".0")
        mutableStateOf(TextFieldValue(value, TextRange(0, value.length)))
    }
    var error by remember { mutableStateOf(false) }
    val percent = parseZoomPercent(draft.text)
    val sliderLabel = strings(Messages.ZoomSlider)

    fun submit() {
        if (percent == null) {
            error = true
            return
        }
        onApply(percent / 100f)
        onDismiss()
    }

    AppAlertDialog(
        modifier = Modifier.editorDialogBounds().exportEditorTestTags().testTag("editor-zoom-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(strings(Messages.ZoomTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        error = false
                    },
                    label = { Text(strings(Messages.ZoomPercent)) },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text(strings(Messages.ZoomPercentRange, "min" to exactZoomRange.start.toInt(),
                            "max" to exactZoomRange.endInclusive.toInt())) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("editor-zoom-percent")
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyUp) submit()
                                true
                            } else false
                        },
                )
                Slider(
                    value = percent ?: currentPercent.toFloat().coerceIn(exactZoomRange),
                    onValueChange = {
                        val value = it.roundToInt().toString()
                        draft = TextFieldValue(value, TextRange(0, value.length))
                        error = false
                    },
                    valueRange = exactZoomRange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderLabel }.testTag("editor-zoom-slider"),
                )
            }
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = ::submit, modifier = Modifier.testTag("editor-zoom-apply")) {
                Text(strings(Messages.ActionApply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
