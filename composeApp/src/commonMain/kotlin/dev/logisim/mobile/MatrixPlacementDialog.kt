package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * Evolution's matrix placement. Spacings step by 10 with − and + (the field still takes typed values), and the base
 * label is checked as it is typed, with the reason under the field and Place disabled while it is refused (X9).
 */
@Composable
internal fun MatrixPlacementDialog(
    placement: PendingMatrixPlacement,
    error: LocalizedText,
    onDismiss: () -> Unit,
    registry: ComponentRegistry = ComponentCatalog.registry,
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
    val labelProblem = remember(placement, registry, label, columns, rows) {
        matrixLabelProblem(placement.document, registry, placement.template.kind, label,
            columns.toIntOrNull() ?: 1, rows.toIntOrNull() ?: 1)
    }

    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings(Messages.MatrixPlaceTitle)) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatrixNumberField(columns, { columns = it; failed = false }, strings(Messages.MatrixPlaceColumns),
                    "matrix-columns", validColumns)
                MatrixNumberField(rows, { rows = it; failed = false }, strings(Messages.MatrixPlaceRows),
                    "matrix-rows", validRows)
                Text(strings(Messages.MatrixPlaceSpacingHint), fontSize = 12.sp)
                val dxLabel = strings(Messages.MatrixPlaceDx, "minimum" to placement.minDx)
                MatrixSpacingField(dx, { dx = it; failed = false }, dxLabel, "matrix-dx", validDx,
                    stepped = { up -> placement.steppedSpacing(dx.toIntOrNull(), placement.minDx, up) },
                    onStep = { up -> dx = placement.steppedSpacing(dx.toIntOrNull(), placement.minDx, up).toString(); failed = false })
                val dyLabel = strings(Messages.MatrixPlaceDy, "minimum" to placement.minDy)
                MatrixSpacingField(dy, { dy = it; failed = false }, dyLabel, "matrix-dy", validDy,
                    stepped = { up -> placement.steppedSpacing(dy.toIntOrNull(), placement.minDy, up) },
                    onStep = { up -> dy = placement.steppedSpacing(dy.toIntOrNull(), placement.minDy, up).toString(); failed = false })
                OutlinedTextField(label, { label = it; failed = false }, singleLine = true,
                    label = { Text(strings(Messages.MatrixPlaceBaseLabel)) },
                    isError = labelProblem != null,
                    supportingText = labelProblem?.let { problem ->
                        { Text(strings(problem), Modifier.testTag("matrix-label-error")
                            .semantics { liveRegion = LiveRegionMode.Polite }) }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("matrix-label"))
                if (failed) Text(strings(error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("matrix-error").semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            TextButton(
                enabled = validColumns && validRows && validDx && validDy && labelProblem == null,
                onClick = { failed = !onApply(columns.toInt(), rows.toInt(), dx.toInt(), dy.toInt(), label) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("matrix-confirm"),
            ) { Text(strings("matrix_place_confirm")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("matrix-cancel")) {
                Text(strings(Messages.ActionCancel))
            }
        },
    )
}

@Composable
private fun MatrixNumberField(value: String, onValueChange: (String) -> Unit, label: String, tag: String, valid: Boolean,
                              modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onValueChange, singleLine = true, label = { Text(label) }, isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(tag))
}

/**
 * A spacing field between − and + buttons that move it by one 10-unit step within its range; a button that would
 * leave the value unchanged (at the minimum or at 990) is disabled.
 */
@Composable
private fun MatrixSpacingField(value: String, onValueChange: (String) -> Unit, label: String, tag: String, valid: Boolean,
                               stepped: (up: Boolean) -> Int, onStep: (up: Boolean) -> Unit) {
    val strings = rememberLocalizer()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(false) }, enabled = stepped(false).toString() != value, modifier = Modifier.size(48.dp).testTag("$tag-minus")
            .semantics { contentDescription = strings("placement_stepper_decrease", "name" to label) }) { Text("−", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {}) }
        MatrixNumberField(value, onValueChange, label, tag, valid, Modifier.weight(1f))
        IconButton(onClick = { onStep(true) }, enabled = stepped(true).toString() != value, modifier = Modifier.size(48.dp).testTag("$tag-plus")
            .semantics { contentDescription = strings("placement_stepper_increase", "name" to label) }) { Text("+", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {}) }
    }
}
