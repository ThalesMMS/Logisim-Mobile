package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage

/**
 * The bits of a multi-bit input pin, most significant first, each with its current value: choosing one toggles it
 * (Evolution's PinPoker on that bit). The accessible counterpart of tapping one bit on the canvas (X0).
 */
@Composable
internal fun InputBitChooserDialog(
    name: LocalizedText,
    width: Int,
    value: LogicVector?,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val bits = (width - 1 downTo 0).toList()
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags().testTag("input-bit-chooser"),
        onDismissRequest = onDismiss,
        title = { Text(message("a11y_input_choose_bit", "name" to name).resolve(language)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(bits, key = { it }) { bit ->
                    val current = value?.takeIf { it.width == width }?.bit(bit)?.symbol() ?: "?"
                    TextButton(onClick = { onToggle(bit) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("input-bit-$bit")) {
                        Text(message("a11y_input_bit", "bit" to bit, "value" to current).resolve(language))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(message("a11y_explorer_close").resolve(language)) } },
    )
}
