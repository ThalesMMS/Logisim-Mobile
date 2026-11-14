package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Arrangement
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
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * The defaults of a project toolbar or mouse-mapping tool, edited with the inspector's pickers (C8): options from a
 * menu of Evolution's names, booleans with a switch, colors from the swatches, fonts from the font chooser, and only
 * the attributes the tool has. The draft keeps the serialized strings the inspector would write, so
 * `ProjectToolbar.withToolAttributes` receives the same updates as typed text would give.
 */
internal class ProjectToolDefaults(
    private val prototype: CircuitComponent,
    private val registry: ComponentRegistry,
    private val darkTheme: Boolean = false,
) {
    private val subcircuit = prototype.kind == ComponentKind.SUBCIRCUIT

    /** The attributes as they were, which a draft value equal to is no change. */
    private val baseline: Map<String, String?> = fieldsOf(prototype).associate { it.name to it.value }

    /** The prototype with the valid part of [draft] applied, whose attributes decide which rows apply (gate negations). */
    private fun shown(draft: Map<String, String>): CircuitComponent {
        val attributes = draft - "label"
        if (subcircuit || attributes.isEmpty()) return prototype
        return runCatching { registry.reconfigure(prototype, attributes) }.getOrDefault(prototype)
    }

    private fun fieldsOf(component: CircuitComponent): List<PropertyEditorField> {
        val label = PropertyEditorField("label", PropertyInputKind.TEXT, component.label)
        if (subcircuit) return listOf(
            label,
            PropertyEditorField("facing", PropertyInputKind.DIRECTION, component.attributes["facing"] ?: "east",
                options = PropertyEditorModel.standardOptions(PropertyInputKind.DIRECTION)),
            PropertyEditorField("labelloc", PropertyInputKind.DIRECTION, component.attributes["labelloc"] ?: "north",
                options = PropertyEditorModel.standardOptions(PropertyInputKind.DIRECTION)),
            PropertyEditorField("labelfont", PropertyInputKind.FONT, component.attributes["labelfont"] ?: "SansSerif bold 16"),
            PropertyEditorField("labelvisible", PropertyInputKind.BOOLEAN, component.attributes["labelvisible"] ?: "true",
                options = PropertyEditorModel.standardOptions(PropertyInputKind.BOOLEAN)),
        )
        // Splitter, Constant, Power, Text and other tools without Evolution's label attribute offer no Label (C8).
        val labelled = registry.definition(component.kind)?.schema?.keys?.any { it.name == "label" } == true
        return (if (labelled) listOf(label) else emptyList()) +
            PropertyEditorModel.fields(registry, listOf(component), darkTheme).filterNot { it.readOnly }
    }

    /** The rows for [draft], each showing its drafted value. */
    fun fields(draft: Map<String, String>): List<PropertyEditorField> =
        fieldsOf(shown(draft)).map { field -> draft[field.name]?.let { field.copy(value = it) } ?: field }

    /** [draft] after setting [name] to [raw]; a value equal to the original one is no change. */
    fun edit(draft: Map<String, String>, name: String, raw: String): Map<String, String> =
        if (name in baseline && baseline[name] == raw) draft - name else draft + (name to raw)

    fun validate(draft: Map<String, String>, name: String, raw: String): PropertyEditResult {
        val field = fields(draft).firstOrNull { it.name == name }
        if (name != "label" && !subcircuit) {
            return PropertyEditorModel.validate(registry, listOf(shown(draft - name)), name, raw, darkTheme)
        }
        val valid = when (field?.kind) {
            PropertyInputKind.DIRECTION -> Direction.parse(raw) != null
            PropertyInputKind.BOOLEAN -> raw.toBooleanStrictOrNull() != null
            PropertyInputKind.FONT -> AnnotationFont.parse(raw) != null
            else -> true
        }
        return if (valid) PropertyEditResult.Valid(mapOf(name to raw))
        // The row's shown name, never the XML attribute name (C3, C150).
        else PropertyEditResult.Invalid(if (field != null) message(Messages.InspectorValueInvalid, "name" to field.label, "raw" to raw)
            else message(Messages.InspectorValueInvalid, "name" to name, "raw" to raw))
    }

    /** Each drafted row that still applies, validated. */
    fun validation(draft: Map<String, String>): Map<String, PropertyEditResult> {
        val names = fields(draft).map { it.name }.toSet()
        return draft.filterKeys { it in names }.mapValues { (name, raw) -> validate(draft, name, raw) }
    }

    /** The serialized updates of a valid [draft], or null while a value is invalid or nothing changed. */
    fun updates(draft: Map<String, String>): Map<String, String>? {
        val results = validation(draft)
        if (results.isEmpty() || results.values.any { it !is PropertyEditResult.Valid }) return null
        return results.values.filterIsInstance<PropertyEditResult.Valid>().flatMap { it.updates.entries }.associate { it.key to it.value }
    }
}

@Composable
internal fun ProjectToolDefaultsDialog(
    prototype: CircuitComponent,
    registry: ComponentRegistry,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val darkTheme = LocalEditorDarkTheme.current
    val model = remember(prototype, registry, darkTheme) { ProjectToolDefaults(prototype, registry, darkTheme) }
    var draft by remember(model) { mutableStateOf(emptyMap<String, String>()) }
    val fields = remember(model, draft) { model.fields(draft) }
    val validation = remember(model, draft) { model.validation(draft) }
    val updates = remember(model, draft) { model.updates(draft) }
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings("project_tool_defaults")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field ->
                    val failure = validation[field.name] as? PropertyEditResult.Invalid
                    ProjectToolDefaultRow(field, failure) { raw -> draft = model.edit(draft, field.name, raw) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = updates != null, onClick = { updates?.let(onApply) }) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}

/** One default, edited the way the inspector edits that kind of value. */
@Composable
private fun ProjectToolDefaultRow(field: PropertyEditorField, failure: PropertyEditResult.Invalid?, onChange: (String) -> Unit) {
    val strings = rememberLocalizer()
    val tag = "project-tool-default-${field.name}"
    when {
        field.kind == PropertyInputKind.BOOLEAN -> {
            val checked = field.value == "true"
            val value = strings(field.localizedValue())
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch) { onChange(it.toString()) }
                .semantics { stateDescription = value }.testTag(tag),
                verticalAlignment = Alignment.CenterVertically) {
                Text(strings(field.label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        // Values from a fixed set are chosen, as in the inspector, instead of typed.
        field.options.isNotEmpty() -> PropertyOptionField(field, field.value, Modifier.fillMaxWidth().testTag(tag), onChange)
        field.kind == PropertyInputKind.COLOR || field.kind == PropertyInputKind.FONT -> {
            var open by remember(field.name) { mutableStateOf(false) }
            val raw = field.value.orEmpty()
            val name = strings(field.label)
            val shown = strings(field.localizedValue())
            val problem = failure?.let { strings(it.notice) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    // The read-only field only draws the value; the overlay is the row's one TalkBack stop (C8, C14).
                    OutlinedTextField(value = shown, onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text(name) }, isError = failure != null,
                        leadingIcon = if (field.kind == PropertyInputKind.COLOR) hexColor(raw)?.let { color -> { ColorSwatch(color) } } else null,
                        trailingIcon = { Text(if (open) "▴" else "▾", color = ChromeColors.Accent) },
                        supportingText = problem?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
                    // The overlay takes the tap so the field opens its chooser instead of a keyboard, and says the
                    // field's name and value in one announcement.
                    Box(Modifier.matchParentSize()
                        .clickable(role = Role.Button, onClickLabel = strings(Messages.InspectorChooseValue)) { open = !open }
                        .semantics {
                            contentDescription = name
                            stateDescription = shown
                            problem?.let { error(it) }
                        }
                        .testTag(tag))
                }
                if (open) {
                    if (field.kind == PropertyInputKind.COLOR) {
                        ColorPalette(raw, onChange)
                        OutlinedTextField(raw, onChange, singleLine = true, label = { Text(strings(field.label)) },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth().testTag("$tag-text"))
                    // The same family, style and size chooser as the inspector, with its size presets (C11).
                    } else FontChoiceField(raw, onChange, tag = tag)
                }
            }
        }
        else -> {
            val multiline = field.kind == PropertyInputKind.EXTERNAL
            OutlinedTextField(
                value = field.value.orEmpty(),
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().testTag(tag),
                label = { Text(strings(field.label)) },
                supportingText = failure?.let { { Text(strings(it.notice), color = MaterialTheme.colorScheme.error) } }
                    ?: field.hint?.let { hint -> { Text(strings(hint)) } },
                isError = failure != null,
                singleLine = !multiline,
                maxLines = if (multiline) 6 else 1,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = when {
                    field.kind == PropertyInputKind.INTEGER -> KeyboardType.Number
                    field.kind == PropertyInputKind.DECIMAL -> KeyboardType.Decimal
                    field.hint != null -> KeyboardType.Ascii
                    else -> KeyboardType.Text
                }),
            )
        }
    }
}

/** A read-only field showing the chosen option; tapping it opens the menu of [field]'s options. */
@Composable
internal fun PropertyOptionField(field: PropertyEditorField, value: String?, modifier: Modifier = Modifier, onChoose: (String) -> Unit) {
    val strings = rememberLocalizer()
    var expanded by remember(field.name) { mutableStateOf(false) }
    val name = strings(field.label)
    val shown = strings(field.copy(value = value).localizedValue())
    Box(modifier) {
        // The read-only field only draws the value; the overlay is the row's one TalkBack stop (C8, C14).
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(name) },
            trailingIcon = { Text("▾", color = ChromeColors.Accent) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        // The overlay takes the tap so the field opens the menu instead of a keyboard, and says the field's name and
        // value in one announcement.
        Box(Modifier.matchParentSize().clickable(role = Role.DropdownList,
            onClickLabel = strings(Messages.InspectorChooseValue)) { expanded = true }
            .semantics {
                contentDescription = name
                stateDescription = shown
            })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PropertyOptionItems(field, value, "option-${field.name}") { expanded = false; onChoose(it) }
        }
    }
}

/**
 * The options of [field] as one group of radio items, the chosen one checked: TalkBack reads each with its name, its
 * state and its position in the group (C14).
 */
@Composable
internal fun PropertyOptionItems(field: PropertyEditorField, value: String?, tagPrefix: String, onChoose: (String) -> Unit) {
    val strings = rememberLocalizer()
    Column(Modifier.selectableGroup()) {
        field.options.forEach { option ->
            val chosen = option.value == value
            DropdownMenuItem(
                text = { Text(strings(option.label), fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = { Text(if (chosen) "✓" else "", modifier = Modifier.width(20.dp).clearAndSetSemantics { }) },
                onClick = { onChoose(option.value) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-${option.value}")
                    .semantics { role = Role.RadioButton; selected = chosen },
            )
        }
    }
}
