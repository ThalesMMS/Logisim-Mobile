package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
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
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.PlaProgram
import dev.logisim.mobile.core.PlaTableFile
import dev.logisim.mobile.core.localization.*
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun TypedPropertyInspector(
    registry: ComponentRegistry,
    components: List<CircuitComponent>,
    session: PropertyInspectorSession,
    presentation: InspectorPresentation,
    onLabel: () -> Unit,
    onLabelFocusChanged: (Boolean) -> Unit,
    onOpenField: (PropertyEditorField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberLocalizer()
    val darkTheme = LocalEditorDarkTheme.current
    val fields = remember(registry, components, darkTheme) { PropertyEditorModel.fields(registry, components, darkTheme) }
    val unknown = remember(registry, components) { PropertyEditorModel.unregisteredKinds(registry, components) }
    val selectedLabels = components.map { it.label }.distinct()
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
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            OutlinedTextField(session.label, { session.label = it }, label = { Text(strings(Messages.PropertyLabel)) },
                placeholder = { if (selectedLabels.size > 1) Text(strings(Messages.InspectorMixedLabels)) },
                // A line height that follows the font keeps the focused field within the 82 dp left
                // above the keyboard in a 568 x 320 dp window at 200% (#239).
                singleLine = true, textStyle = LocalTextStyle.current.copy(lineHeight = 1.25.em),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .focusRequester(labelFocus).bringIntoViewRequester(labelVisibility)
                    .onFocusChanged { onLabelFocusChanged(it.isFocused) },
                trailingIcon = { TextButton(onClick = onLabel, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionApply)) } })
            if (unknown.isNotEmpty()) Text(strings(Messages.InspectorUnknownKinds, "names" to unknown.joinToString()),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            // Evolution's attribute table: a shaded name column beside the value column.
            HorizontalDivider(Modifier.padding(top = 8.dp), color = ChromeColors.Divider)
            fields.forEach { field ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 40.dp)
                    .then(if (!field.readOnly) Modifier.clickable { onOpenField(field) } else Modifier)
                    .semantics { contentDescription = if (field.readOnly) strings(Messages.InspectorReadOnly, "name" to localizedAttributeName(field.name)) else strings(Messages.InspectorEdit, "name" to localizedAttributeName(field.name)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(strings(localizedAttributeName(field.name)), fontSize = 12.sp, color = ChromeColors.Ink,
                        modifier = Modifier.weight(1f).fillMaxHeight().background(ChromeColors.Panel)
                            .padding(horizontal = 10.dp, vertical = 10.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(ChromeColors.Divider))
                    Text(strings(field.localizedValue()), modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 12.sp, maxLines = 2,
                        color = if (field.readOnly) ChromeColors.Muted else ChromeColors.Accent)
                }
                HorizontalDivider(color = ChromeColors.Divider)
            }
        }
    }
}

@Composable
internal fun PropertyInspectorDialog(
    session: PropertyInspectorSession,
    assets: DocumentAssetActions? = null,
    onApply: (PropertyValueEdit) -> Unit,
) {
    session.editing?.let { edit -> key(edit) {
        PropertyValueDialog(session, edit, assets, onDismiss = { session.cancel(edit) }, onApply = { onApply(edit) })
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
) {
    val strings = rememberLocalizer()
    val field = edit.field
    val value = edit.value
    val validation = remember(session, edit, value.text) { session.validation(edit) }
    val title: @Composable () -> Unit = { Text(strings(localizedAttributeName(field.name))) }
    val input: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(value, { edit.value = it }, modifier = modifier,
            minLines = 1, maxLines = if (field.kind == PropertyInputKind.TEXT) 8 else 1,
            isError = validation is PropertyEditResult.Invalid,
            keyboardOptions = KeyboardOptions(keyboardType = when (field.kind) {
                PropertyInputKind.INTEGER -> KeyboardType.Number
                PropertyInputKind.DECIMAL -> KeyboardType.Decimal
                else -> KeyboardType.Text
            }))
    }
    val validationMessage: @Composable () -> Unit = {
        if (validation is PropertyEditResult.Invalid) Text(strings(validation.notice),
            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    val confirmButton: @Composable () -> Unit = {
        TextButton(enabled = validation is PropertyEditResult.Valid, onClick = onApply) { Text(strings(Messages.ActionApply)) }
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
            val choices = when (field.kind) {
                PropertyInputKind.BOOLEAN -> listOf("true" to strings(Messages.InspectorEnabled), "false" to strings(Messages.InspectorDisabled))
                PropertyInputKind.DIRECTION -> listOf("east" to strings(Messages.DirectionEast), "north" to strings(Messages.DirectionNorth), "west" to strings(Messages.DirectionWest), "south" to strings(Messages.DirectionSouth))
                else -> emptyList()
            }
            if (choices.isNotEmpty()) choices.forEach { (raw, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { edit.value = TextFieldValue(raw) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value.text == raw, onClick = { edit.value = TextFieldValue(raw) })
                    Text(label)
                }
            } else input(Modifier.fillMaxWidth())
            val components = session.context.components
            if (assets != null && field.name == "table" && components.isNotEmpty() && components.all { it.kind == GatesLibrary.Pla }) {
                PlaTableFileButtons(components, edit, assets)
            }
            validationMessage()
        }
    }, confirmButton = confirmButton, dismissButton = dismissButton)
}
