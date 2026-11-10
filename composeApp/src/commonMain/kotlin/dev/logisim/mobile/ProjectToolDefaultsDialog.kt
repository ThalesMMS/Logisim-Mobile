package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedAttributeName
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun ProjectToolDefaultsDialog(
    prototype: CircuitComponent,
    registry: ComponentRegistry,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val subcircuit = prototype.kind == ComponentKind.SUBCIRCUIT
    val darkTheme = LocalEditorDarkTheme.current
    val fields = remember(prototype, registry, darkTheme) {
        val label = PropertyEditorField("label", PropertyInputKind.TEXT, prototype.label)
        if (subcircuit) listOf(
            label,
            PropertyEditorField("facing", PropertyInputKind.DIRECTION, prototype.attributes["facing"] ?: "east"),
            PropertyEditorField("labelloc", PropertyInputKind.DIRECTION, prototype.attributes["labelloc"] ?: "north"),
            PropertyEditorField("labelfont", PropertyInputKind.TEXT, prototype.attributes["labelfont"] ?: "SansSerif bold 16"),
            PropertyEditorField("labelvisible", PropertyInputKind.BOOLEAN, prototype.attributes["labelvisible"] ?: "true"),
        ) else listOf(label) + PropertyEditorModel.fields(registry, listOf(prototype), darkTheme).filterNot { it.readOnly }
    }
    var draft by remember(fields) { mutableStateOf(emptyMap<String, String>()) }
    fun validate(field: PropertyEditorField, raw: String): PropertyEditResult {
        if (field.name != "label" && !subcircuit) {
            return PropertyEditorModel.validate(registry, listOf(prototype), field.name, raw, darkTheme)
        }
        val valid = when (field.kind) {
            PropertyInputKind.DIRECTION -> Direction.parse(raw) != null
            PropertyInputKind.BOOLEAN -> raw.toBooleanStrictOrNull() != null
            else -> true
        }
        return if (valid) PropertyEditResult.Valid(mapOf(field.name to raw))
        else PropertyEditResult.Invalid(message(Messages.InspectorValueInvalid, "name" to field.name, "raw" to raw))
    }
    val validation = fields.mapNotNull { field -> draft[field.name]?.let { field.name to validate(field, it) } }.toMap()
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings("project_tool_defaults")) },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field ->
                    val failure = validation[field.name] as? PropertyEditResult.Invalid
                    OutlinedTextField(
                        value = draft[field.name] ?: field.value.orEmpty(),
                        onValueChange = { raw ->
                            draft = if (raw == field.value) draft - field.name else draft + (field.name to raw)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("project-tool-default-${field.name}"),
                        label = { Text(strings(localizedAttributeName(field.name))) },
                        supportingText = failure?.let { { Text(strings(it.notice), color = MaterialTheme.colorScheme.error) } },
                        isError = failure != null,
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isNotEmpty() && validation.values.all { it is PropertyEditResult.Valid }, onClick = {
                onApply(validation.values.filterIsInstance<PropertyEditResult.Valid>().flatMap { it.updates.entries }
                    .associate { it.key to it.value })
            }) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
