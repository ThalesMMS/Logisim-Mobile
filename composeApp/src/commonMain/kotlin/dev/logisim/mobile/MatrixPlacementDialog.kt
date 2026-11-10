package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun MatrixPlacementDialog(
    placement: PendingMatrixPlacement,
    error: LocalizedText,
    onDismiss: () -> Unit,
    onApply: (columns: Int, rows: Int, dx: Int, dy: Int, label: String) -> Boolean,
) {
    val strings = rememberLocalizer()
    var columns by remember(placement) { mutableStateOf("1") }
    var rows by remember(placement) { mutableStateOf("1") }
    var dx by remember(placement) { mutableStateOf(placement.minDx.toString()) }
    var dy by remember(placement) { mutableStateOf(placement.minDy.toString()) }
    var label by remember(placement) { mutableStateOf(placement.defaultBaseLabel) }
    var failed by remember(placement) { mutableStateOf(false) }
    val validColumns = placement.validSize(columns.toIntOrNull())
    val validRows = placement.validSize(rows.toIntOrNull())
    val validDx = placement.validSpacing(dx.toIntOrNull(), placement.minDx)
    val validDy = placement.validSpacing(dy.toIntOrNull(), placement.minDy)

    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings(Messages.MatrixPlaceTitle)) },
        text = {
            Column(Modifier.heightIn(max = 365.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatrixNumberField(columns, { columns = it; failed = false }, strings(Messages.MatrixPlaceColumns),
                    "matrix-columns", validColumns)
                MatrixNumberField(rows, { rows = it; failed = false }, strings(Messages.MatrixPlaceRows),
                    "matrix-rows", validRows)
                Text(strings(Messages.MatrixPlaceSpacingHint))
                MatrixNumberField(dx, { dx = it; failed = false }, strings(Messages.MatrixPlaceDx, "minimum" to placement.minDx),
                    "matrix-dx", validDx)
                MatrixNumberField(dy, { dy = it; failed = false }, strings(Messages.MatrixPlaceDy, "minimum" to placement.minDy),
                    "matrix-dy", validDy)
                OutlinedTextField(label, { label = it; failed = false }, singleLine = true,
                    label = { Text(strings(Messages.MatrixPlaceBaseLabel)) },
                    modifier = Modifier.fillMaxWidth().testTag("matrix-label"))
                if (failed) Text(strings(error))
            }
        },
        confirmButton = {
            TextButton(
                enabled = validColumns && validRows && validDx && validDy,
                onClick = { failed = !onApply(columns.toInt(), rows.toInt(), dx.toInt(), dy.toInt(), label) },
                modifier = Modifier.testTag("matrix-confirm"),
            ) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("matrix-cancel")) {
                Text(strings(Messages.ActionCancel))
            }
        },
    )
}

@Composable
private fun MatrixNumberField(value: String, onValueChange: (String) -> Unit, label: String, tag: String, valid: Boolean) {
    OutlinedTextField(value, onValueChange, singleLine = true, label = { Text(label) }, isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}
