package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.unit.em
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.HdlIpLibrary
import dev.logisim.mobile.core.PlaProgram
import dev.logisim.mobile.core.PlaTableFile
import dev.logisim.mobile.core.TclComponents
import dev.logisim.mobile.core.localization.*
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun TypedPropertyInspector(
    registry: ComponentRegistry,
    components: List<CircuitComponent>,
    session: PropertyInspectorSession,
    presentation: InspectorPresentation,
    onLabel: () -> Unit,
    onLabelFocusChanged: (Boolean) -> Unit,
    onOpenField: (PropertyEditorField) -> Unit,
    onChooseOption: (PropertyEditorField, String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an editor tool by id for a large value; false when it is unavailable, and the text editor opens instead. */
    onOpenEditor: ((String) -> Boolean)? = null,
    /** Empties every selected label when they differ: the explicit "Clear for all N" action (C7). */
    onClearLabels: (() -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    val darkTheme = LocalEditorDarkTheme.current
    val fields = remember(registry, components, darkTheme, session.context.socBuses) {
        PropertyEditorModel.fields(registry, components, darkTheme, session.context.socBuses)
    }
    val unknown = remember(registry, components) { PropertyEditorModel.unregisteredKinds(registry, components) }
    val scope = rememberCoroutineScope()
    // Not keyed on the session: a move or an edit recreates it while the same field keeps the focus, and the next
    // focus loss must still apply the draft the new session took over.
    var labelFocused by remember { mutableStateOf(false) }
    val labelFocus = remember(session, presentation) { FocusRequester() }
    val labelVisibility = remember(session, presentation) { BringIntoViewRequester() }
    val focusRequest = session.focus.focusRequest?.takeIf { it.presentation == presentation }
    LaunchedEffect(session, focusRequest) {
        if (focusRequest != null) {
            withFrameNanos { }
            if (!session.canRequestLabelFocus(focusRequest)) return@LaunchedEffect
            labelFocus.requestFocus()
            labelVisibility.bringIntoView()
            session.focus.completeFocus(focusRequest)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (components.isEmpty()) {
            Text(strings(Messages.InspectorSelectHint), fontSize = 12.sp, color = ChromeColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        } else {
            Text(if (components.size == 1) strings(components.single().kind.localizedLabel()) else strings.plural(Messages.InspectorComponentCount, components.size.toLong()),
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() })
            // Text and Image annotations, wiring parts and other components without a label attribute have no
            // Label row in Evolution's attribute table; a selection mixing them has none either (C9).
            if (session.labelled) OutlinedTextField(session.label, { session.label = it }, label = { Text(strings(Messages.PropertyLabel)) },
                placeholder = { if (session.mixedLabels) Text(strings(Messages.InspectorMixedLabels)) },
                // The hint stays visible while the field is not focused, where the placeholder is hidden (C7).
                supportingText = if (session.mixedLabels) ({ Text(strings(Messages.InspectorMixedLabels)) }) else null,
                // A line height that follows the font keeps the focused field within the 82 dp left
                // above the keyboard in a 568 x 320 dp window at 200% (#239).
                singleLine = true, textStyle = LocalTextStyle.current.copy(lineHeight = 1.25.em),
                // The keyboard's Done key applies the label, like Enter in Evolution's attribute table.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (session.labelChanged) onLabel() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .focusRequester(labelFocus).bringIntoViewRequester(labelVisibility)
                    .onFocusChanged { state ->
                        onLabelFocusChanged(state.isFocused)
                        val lost = labelFocused && !state.isFocused
                        labelFocused = state.isFocused
                        // As Evolution's attribute table stops editing when the focus leaves it, a typed label is
                        // applied on focus loss. A host that is leaving (the sheet closing, a resize moving the
                        // field) cancels this; the sheet applies the draft itself and a resize keeps it (C9, C155).
                        if (lost) scope.launch {
                            withFrameNanos { }
                            if (!labelFocused) session.commitLabelDraft()
                        }
                    }.testTag("inspector-label"),
                trailingIcon = { TextButton(onClick = onLabel, enabled = session.labelChanged, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionApply)) } })
            val clearCount = session.labelClearCount
            if (session.labelled && session.mixedLabels && onClearLabels != null && clearCount > 0) {
                TextButton(onClick = onClearLabels, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)
                    .testTag("inspector-label-clear-all")) {
                    Text(strings.plural("inspector_clear_for_all", clearCount.toLong(), "count" to clearCount))
                }
            }
            if (unknown.isNotEmpty()) Text(strings(Messages.InspectorUnknownKinds, "names" to unknown.joinToString()),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            // Evolution's attribute table: a shaded name column beside the value column.
            HorizontalDivider(Modifier.padding(top = 8.dp), color = ChromeColors.Divider)
            fields.forEach { field ->
                PropertyRow(field, onOpenField, onChooseOption, onOpenEditor?.let { open ->
                    externalPropertyEditor(field, components)?.let { editor -> editor to open }
                })
                HorizontalDivider(color = ChromeColors.Divider)
            }
        }
    }
}

/**
 * One row of the attribute table. Like Evolution's attribute combo boxes, a field with a fixed set of values
 * (option, direction) opens a menu of those values and applies the one chosen; a boolean is a switch. Other
 * values open the typed editor.
 */
@Composable
private fun PropertyRow(
    field: PropertyEditorField,
    onOpenField: (PropertyEditorField) -> Unit,
    onChooseOption: (PropertyEditorField, String) -> Unit,
    /** A large value's dedicated editor and how to open it (C12). */
    external: Pair<ExternalPropertyEditor, (String) -> Boolean>? = null,
) {
    val strings = rememberLocalizer()
    val name = field.label
    val value = strings(field.localizedValue())
    val toggle = !field.readOnly && field.kind == PropertyInputKind.BOOLEAN
    val menu = !field.readOnly && !toggle && field.options.isNotEmpty()
    val opensEditor = !field.readOnly && external != null
    var expanded by remember(field.name) { mutableStateOf(false) }
    val interaction = when {
        // A read-only row is one stop that says its name and value (C14).
        field.readOnly -> Modifier.semantics(mergeDescendants = true) { }
        toggle -> Modifier.toggleable(value = field.value == "true", role = Role.Switch,
            onValueChange = { onChooseOption(field, it.toString()) })
        menu -> Modifier.clickable(role = Role.DropdownList, onClickLabel = strings(Messages.InspectorChooseValue)) { expanded = true }
        // Like Evolution's "(click to edit)" cells: the row opens the memory editor, HDL tool, Tcl panel or image editor.
        opensEditor -> Modifier.clickable(role = Role.Button,
            onClickLabel = strings("inspector_open_tool", "tool" to external!!.first.title)) {
            if (!external.second(external.first.toolId)) onOpenField(field)
        }
        else -> Modifier.clickable(role = Role.Button) { onOpenField(field) }
    }
    Column {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp).then(interaction)
        .testTag("inspector-row-${field.name}")
        .semantics {
            contentDescription = if (field.readOnly) strings(Messages.InspectorReadOnly, "name" to name) else strings(Messages.InspectorEdit, "name" to name)
            stateDescription = value
        },
        verticalAlignment = Alignment.CenterVertically) {
        // Names and values follow the type scale, so they grow with the font size setting (C15).
        Text(strings(name), style = MaterialTheme.typography.bodyMedium, color = ChromeColors.Ink,
            modifier = Modifier.weight(1f).fillMaxHeight().background(ChromeColors.Panel)
                .padding(horizontal = 10.dp, vertical = 10.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(ChromeColors.Divider))
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (field.kind == PropertyInputKind.COLOR) field.value?.let(::hexColor)?.let { ColorSwatch(it, Modifier.padding(end = 8.dp)) }
                Text(value, modifier = Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (field.readOnly) ChromeColors.Muted else ChromeColors.Accent)
                when {
                    toggle -> Switch(checked = field.value == "true", onCheckedChange = null,
                        modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics { })
                    menu -> Text("▾", color = ChromeColors.Accent, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics { })
                    opensEditor -> Text("›", color = ChromeColors.Accent, fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics { })
                }
            }
            if (menu) DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.testTag("inspector-options-${field.name}")) {
                PropertyOptionItems(field, field.value, "inspector-option-${field.name}") { expanded = false; onChooseOption(field, it) }
            }
        }
    }
    if (opensEditor) {
        // The raw text stays reachable for pasting contents, as Evolution's memory editor imports text.
        val description = strings("inspector_edit_as_text_description", "name" to name)
        TextButton(onClick = { onOpenField(field) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
            .testTag("inspector-edit-text-${field.name}").semantics { contentDescription = description }) {
            Text(strings("inspector_edit_as_text"), fontSize = 12.sp)
        }
    }
    }
}

/** The tool that edits a large value of the single selected component, as Evolution's attribute cells open their editors. */
internal class ExternalPropertyEditor(val toolId: String, val title: LocalizedText)

internal fun externalPropertyEditor(field: PropertyEditorField, components: List<CircuitComponent>): ExternalPropertyEditor? {
    if (field.kind != PropertyInputKind.EXTERNAL || field.readOnly) return null
    val component = components.singleOrNull() ?: return null
    return when {
        field.name == "contents" && isMemoryEditorComponent(component) -> ExternalPropertyEditor("memory-editor", message("memory_tool_title"))
        field.name == "content" && (component.kind == HdlIpLibrary.VHDL || component.kind == HdlIpLibrary.BLIF) ->
            ExternalPropertyEditor("hdl-simulation", message(Messages.HdlToolsSimTitle))
        field.name == "content" && (component.kind == TclComponents.GENERIC || component.kind == TclComponents.CONSOLE_REDS) ->
            ExternalPropertyEditor("tcl", message(Messages.TclTitle))
        field.name == "image" && component.kind == AnnotationLibrary.Image -> ExternalPropertyEditor("annotations", message("annotation_title"))
        else -> null
    }
}

@Composable
internal fun PropertyInspectorDialog(
    session: PropertyInspectorSession,
    assets: DocumentAssetActions? = null,
    /** Empties a mixed value for every selected component: the explicit "Clear for all N" action (C7). */
    onClear: ((PropertyValueEdit) -> Unit)? = null,
    onApply: (PropertyValueEdit) -> Unit,
) {
    session.editing?.let { edit -> key(edit) {
        PropertyValueDialog(session, edit, assets, onDismiss = { session.cancel(edit) }, onApply = { onApply(edit) },
            onClear = onClear?.let { clear -> { clear(edit) } })
    } }
}

/** Evolution PlaTable.EditorDialog Export and Import: the table as a text file, checked against the PLA widths. */
internal class PlaTableFileActions(private val components: List<CircuitComponent>, private val assets: DocumentAssetActions) {
    private fun width(name: String) = components.map { it.attributes[name]?.toIntOrNull() ?: 2 }.distinct().singleOrNull()
    val inputWidth: Int? = width("in_width")
    val outputWidth: Int? = width("out_width")
    val canImport: Boolean get() = inputWidth != null && outputWidth != null

    /** Saves the table being edited, like Evolution's Export of the dialog's working copy. */
    fun export(table: String, onFailure: (String, LocalizedText) -> Unit) {
        val file = PlaTableFile.write(PlaProgram.parse(table))
        assets.create(DocumentAsset(PlaTableFile.fileName(components.first().label), PlaTableFile.MIME_TYPE,
            file.encodeToByteArray())) { result ->
            if (result is AssetActionResult.Failure) onFailure("pla_save_error_title", result.notice)
        }
    }

    /** Replaces the edited table only when the file has the PLA's widths. */
    fun import(onLoaded: (String) -> Unit, onFailure: (String, LocalizedText) -> Unit) {
        val inputs = inputWidth ?: return
        val outputs = outputWidth ?: return
        // Files providers may advertise .txt as generic data. The codec validates the content.
        assets.open(listOf(PlaTableFile.MIME_TYPE, "application/octet-stream"), PlaTableFile.MAX_BYTES) { result ->
            when (result) {
                is AssetOpenResult.Opened -> try {
                    onLoaded(PlaTableFile.read(result.asset.bytes, inputs, outputs).serialize())
                } catch (error: LocalizedArgumentException) {
                    onFailure("pla_load_error_title", error.text)
                }
                is AssetOpenResult.Failure -> onFailure("pla_load_error_title", result.notice)
                AssetOpenResult.Cancelled -> Unit
            }
        }
    }
}

@Composable
private fun PlaTableFileButtons(components: List<CircuitComponent>, edit: PropertyValueEdit, assets: DocumentAssetActions) {
    val strings = rememberLocalizer()
    val actions = remember(components, assets) { PlaTableFileActions(components, assets) }
    var failure by remember(edit) { mutableStateOf<Pair<String, LocalizedText>?>(null) }
    val fail = { title: String, notice: LocalizedText -> failure = title to notice }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { failure = null; actions.export(edit.value.text, fail) },
            modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("pla_export_button")) }
        TextButton(enabled = actions.canImport, onClick = {
            failure = null
            actions.import({ edit.value = TextFieldValue(it) }, fail)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("pla_import_button")) }
    }
    failure?.let { (title, notice) ->
        Text(strings(title), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(strings(notice), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable
private fun PropertyValueDialog(
    session: PropertyInspectorSession,
    edit: PropertyValueEdit,
    assets: DocumentAssetActions?,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    val field = edit.field
    val value = edit.value
    // A long value (memory contents, a data URI) is checked once typing pauses, away from the UI thread: validation
    // decodes and reconfigures every selected component (C12).
    val text = value.text
    val immediate = if (text.length <= DEFERRED_VALIDATION_LENGTH) remember(session, edit, text) { session.validation(edit) } else null
    var deferred by remember(session, edit) { mutableStateOf<Pair<String, PropertyEditResult>?>(null) }
    if (immediate == null) LaunchedEffect(session, edit, text) {
        delay(DEFERRED_VALIDATION_DELAY_MS)
        deferred = text to withContext(Dispatchers.Default) { session.validation(edit, text) }
    }
    val validation = immediate ?: deferred?.takeIf { it.first == text }?.second
    val title: @Composable () -> Unit = { Text(strings(field.label)) }
    val canApply = validation is PropertyEditResult.Valid && !(field.mixed && !edit.changed)
    val focus = remember(edit) { FocusRequester() }
    val multiline = field.multiline
    val input: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(value, { edit.value = it }, modifier = modifier.focusRequester(focus).testTag("inspector-value-field"),
            minLines = 1, maxLines = if (multiline) 8 else 1, singleLine = !multiline,
            isError = validation is PropertyEditResult.Invalid,
            placeholder = { if (field.mixed) Text(strings(Messages.InspectorMixedValues)) },
            supportingText = field.hint?.let { hint -> { Text(strings(hint), fontSize = 11.sp) } },
            // Signs, exponents, hex digits and units need letters; only non-negative whole numbers get the digit pad.
            keyboardOptions = keyboardOptionsFor(field),
            keyboardActions = KeyboardActions(onDone = { if (canApply) onApply() }))
        // Common values in one row under the field, so the field and the buttons keep their place (C6).
        PropertyValuePresets(field, value.text) { edit.value = TextFieldValue(it, TextRange(0, it.length)) }
        // The field opens focused with its value selected, so typing replaces it.
        LaunchedEffect(focus) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    }
    val validationMessage: @Composable () -> Unit = {
        if (validation is PropertyEditResult.Invalid) Text(strings(validation.notice),
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        else if (validation == null) Text(strings("inspector_validating"), color = ChromeColors.Muted, fontSize = 12.sp)
    }
    val confirmButton: @Composable () -> Unit = {
        TextButton(enabled = canApply, onClick = onApply) { Text(strings(Messages.ActionApply)) }
    }
    val dismissButton: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) }
    }
    if (field.kind == PropertyInputKind.INTEGER || field.kind == PropertyInputKind.DECIMAL) {
        NumericPropertyDialog(onDismiss, title, input, validationMessage, confirmButton, dismissButton)
        return
    }
    AppAlertDialog(onDismissRequest = onDismiss, title = title, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val choices = field.options.map { it.value to strings(it.label) }
            if (choices.isNotEmpty() && field.mixed) Text(strings(Messages.InspectorMixedValues), color = ChromeColors.Muted, fontSize = 12.sp)
            if (field.kind == PropertyInputKind.COLOR) ColorPalette(value.text) { edit.value = TextFieldValue(it, TextRange(it.length)) }
            if (field.kind == PropertyInputKind.FONT) FontChooser(value.text) { edit.value = TextFieldValue(it, TextRange(it.length)) }
            // Each choice is one radio stop in one group, read with its name and position (C14).
            if (choices.isNotEmpty()) Column(Modifier.selectableGroup()) { choices.forEach { (raw, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value.text == raw, role = Role.RadioButton) {
                    edit.value = TextFieldValue(raw)
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value.text == raw, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            } } else if (field.kind != PropertyInputKind.FONT || AnnotationFontParse(value.text) == null) input(Modifier.fillMaxWidth())
            val components = session.context.components
            if (assets != null && field.name == "table" && components.isNotEmpty() && components.all { it.kind == GatesLibrary.Pla }) {
                PlaTableFileButtons(components, edit, assets)
            }
            validationMessage()
            val clearCount = remember(edit) { session.clearForAllCount(edit) }
            if (onClear != null && clearCount != null) {
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("inspector-value-clear-all")) {
                    Text(strings.plural("inspector_clear_for_all", clearCount.toLong(), "count" to clearCount))
                }
            }
        }
    }, confirmButton = confirmButton, dismissButton = dismissButton)
}

/** Values longer than this are validated after a pause in typing, on a background thread. */
private const val DEFERRED_VALIDATION_LENGTH = 4_096
private const val DEFERRED_VALIDATION_DELAY_MS = 250L

