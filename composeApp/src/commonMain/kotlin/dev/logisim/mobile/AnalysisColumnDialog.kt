package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * One truth-table column (VariableTab for a single variable): rename it, move it with Evolution's Raise, Lower,
 * Raise to top and Lower to bottom (or by position), insert a new variable next to it, or delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnalysisColumnDialog(
    names: List<String>,
    index: Int,
    input: Boolean,
    onDismiss: () -> Unit,
    onApply: (name: String, destination: Int) -> Unit,
    canInsert: Boolean = false,
    canDelete: Boolean = false,
    onInsert: (after: Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
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
    val at = destination ?: index

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings(editMessage, "name" to names[index])) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(strings(Messages.WorkspaceName)) }, isError = duplicate,
                    supportingText = { if (duplicate) Text(strings(duplicateMessage)) })
                if (names.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        MoveButton(MoveGlyph.FIRST, strings("analysis_move_first"), enabled = at > 0) { position = "1" }
                        MoveButton(MoveGlyph.LEFT, strings("analysis_move_left"), enabled = at > 0) { position = at.toString() }
                        MoveButton(MoveGlyph.RIGHT, strings("analysis_move_right"), enabled = at < names.lastIndex) { position = (at + 2).toString() }
                        MoveButton(MoveGlyph.LAST, strings("analysis_move_last"), enabled = at < names.lastIndex) { position = names.size.toString() }
                    }
                    OutlinedTextField(position, { position = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(strings(Messages.AnalysisColumnPosition, "max" to names.size)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = destination == null)
                }
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = canInsert, onClick = { onInsert(false) }) {
                        Text(strings(if (input) "analysis_add_input_before" else "analysis_add_output_before"))
                    }
                    TextButton(enabled = canInsert, onClick = { onInsert(true) }) {
                        Text(strings(if (input) "analysis_add_input_after" else "analysis_add_output_after"))
                    }
                    TextButton(enabled = canDelete, onClick = onDelete) {
                        Text(strings(if (input) "analysis_delete_input" else "analysis_delete_output"),
                            color = if (canDelete) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
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

private enum class MoveGlyph { FIRST, LEFT, RIGHT, LAST }

/** A 48 dp move control; the arrow points the way the column goes, with a bar for the first or last position. */
@Composable
private fun MoveButton(glyph: MoveGlyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        val ink = LocalContentColor.current.let { if (enabled) it else it.copy(alpha = 0.38f) }
        Canvas(Modifier.size(22.dp).clearAndSetSemantics {}) {
            val unit = size.minDimension / 16f
            translate((size.width - unit * 16f) / 2f, (size.height - unit * 16f) / 2f) {
                scale(unit, unit, pivot = Offset.Zero) {
                    val mirrored = glyph == MoveGlyph.RIGHT || glyph == MoveGlyph.LAST
                    scale(if (mirrored) -1f else 1f, 1f, pivot = Offset(8f, 8f)) {
                        val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val bar = glyph == MoveGlyph.FIRST || glyph == MoveGlyph.LAST
                        val x = if (bar) 2f else 0f
                        drawPath(Path().apply { moveTo(10f + x, 3f); lineTo(5f + x, 8f); lineTo(10f + x, 13f) }, ink, style = stroke)
                        if (bar) drawLine(ink, Offset(3f, 3f), Offset(3f, 13f), 1.8f, StrokeCap.Round)
                    }
                }
            }
        }
    }
}
