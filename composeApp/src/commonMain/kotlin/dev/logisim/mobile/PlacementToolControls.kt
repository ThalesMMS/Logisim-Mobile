package dev.logisim.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The facing choices of the placement strip, in compass order. */
private val PlacementFacings = listOf(
    Triple(Direction.NORTH, "↑", Messages.DirectionNorth),
    Triple(Direction.EAST, "→", Messages.DirectionEast),
    Triple(Direction.SOUTH, "↓", Messages.DirectionSouth),
    Triple(Direction.WEST, "←", Messages.DirectionWest),
)

/**
 * Mobile counterpart of Evolution's attribute table for a selected AddTool and of its arrow and rotate keys (C34):
 * while placing, the facing of the next component (north, east, south, west), a quarter turn, and its other
 * attributes. The ghost and every component placed next follow the choice. It floats over the bottom of the canvas,
 * where a placement released on it adds nothing.
 */
@Composable
internal fun PlacementToolStrip(state: EditorState, onAttributes: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberLocalizer()
    val prototype = state.placementPreview() ?: return
    val facing = Direction.parse(prototype.facing)
    val hasFacing = state.placementHasFacing
    val cells = (if (hasFacing) PlacementFacings.size + 1 else 0) + 1
    val title = strings("placement_tool_strip",
        "name" to strings(prototype.circuitName?.let(LocalizedText::literal) ?: prototype.kind.localizedLabel()))
    BoxWithConstraints(modifier.canvasOverlayRegion()) {
        val density = LocalDensity.current
        val cell = with(density) {
            val free = constraints.maxWidth - 4.dp.roundToPx() - 2.dp.roundToPx() * (cells - 1)
            (free / cells).toDp().coerceIn(ChromeTouchTarget, 76.dp)
        }
        // A pane title is announced when the strip appears or changes, without a focus stop of its own before the
        // cells (a contentDescription on this unmerged group would add one).
        Surface(shape = RoundedCornerShape(12.dp), color = ChromeColors.Bar, shadowElevation = 3.dp,
            border = BorderStroke(1.dp, ChromeColors.Divider), modifier = Modifier.semantics { paneTitle = title }) {
            Row(Modifier.testTag("placement-strip").horizontalScroll(rememberScrollState())
                .semantics { isTraversalGroup = true }
                .padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasFacing) {
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((direction, arrow, label) in PlacementFacings) {
                            val selected = facing == direction
                            StripCell(arrow, strings(label), strings("placement_tool_facing", "direction" to strings(label)), cell,
                                Modifier.selectable(selected = selected, role = Role.RadioButton) {
                                    state.updatePlacingTemplate(mapOf("facing" to direction.serialized))
                                }.testTag("placement-facing-${direction.serialized}"), selected = selected)
                        }
                    }
                    StripCell(null, strings(Messages.ComponentMenuRotateRight), strings(Messages.ComponentMenuRotateRight), cell,
                        Modifier.clickable(role = Role.Button) { state.rotatePlacingTemplate() }.testTag("placement-rotate"),
                        icon = EditorIcon.ROTATE_RIGHT)
                }
                StripCell(null, strings("placement_tool_attributes_short"), strings("placement_tool_attributes"), cell,
                    Modifier.clickable(role = Role.Button, onClick = onAttributes).testTag("placement-attributes"),
                    icon = EditorIcon.PROPERTIES)
            }
        }
    }
}

@Composable
private fun StripCell(
    glyph: String?, caption: String, description: String, cell: Dp, activation: Modifier,
    selected: Boolean = false, icon: EditorIcon? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(cell).sizeIn(minWidth = ChromeTouchTarget, minHeight = 56.dp).clip(shape)
            .background(if (selected) ChromeColors.Selected else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, ChromeColors.SelectedBorder, shape) else Modifier)
            .then(activation)
            .semantics { contentDescription = description }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) EditorIconGlyph(icon, Modifier.size(22.dp), tint = ChromeColors.Ink)
        if (glyph != null) Text(glyph, fontSize = 18.sp, color = ChromeColors.Ink, modifier = Modifier.clearAndSetSemantics {})
        Text(caption, fontSize = 10.sp, lineHeight = 1.2.em, color = ChromeColors.Ink, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * The Place tool's attributes, chosen before placing (Evolution's attribute table for a selected AddTool): the same
 * typed fields as the inspector, choices from menus and numbers with − and + buttons, bound to the placement
 * preview. A change applies to the tool at once; nothing in the document changes until a component is placed.
 */
@Composable
internal fun PlacementToolAttributesSheet(state: EditorState, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val prototype = state.placementPreview()
    if (prototype == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val darkTheme = LocalEditorDarkTheme.current
    val registry = state.componentRegistry
    val fields = remember(prototype, registry, darkTheme) { placementToolFields(prototype, registry, darkTheme) }
    val name = prototype.circuitName?.let(LocalizedText::literal) ?: prototype.kind.localizedLabel()
    val title = strings("placement_tool_attributes_title", "name" to strings(name))
    // The pane title TalkBack announces when the sheet opens, instead of the generic "Bottom sheet" (C159).
    EditorModalSheet(onDismissRequest = onDismiss, modifier = Modifier.exportEditorTestTags().testTag("placement-attributes-sheet"),
        containerColor = ChromeColors.Paper, title = title) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(strings("placement_tool_attributes_hint"), fontSize = 12.sp, color = ChromeColors.Muted)
            fields.forEach { field ->
                val apply = { raw: String -> state.updatePlacingTemplate(mapOf(field.name to raw)) }
                when {
                    field.options.isNotEmpty() -> PropertyOptionField(field, field.value,
                        Modifier.fillMaxWidth().testTag("placement-attr-${field.name}")) { apply(it) }
                    field.kind == PropertyInputKind.INTEGER -> IntegerStepperField(field, prototype, registry, darkTheme, apply)
                    else -> TypedToolField(field, prototype, registry, darkTheme, apply)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                .testTag("placement-attributes-done")) { Text(strings(Messages.ActionClose)) }
        }
    }
}

/** The editable attributes of the Place tool's [prototype]; a subcircuit offers its appearance attributes. */
internal fun placementToolFields(prototype: CircuitComponent, registry: ComponentRegistry, darkTheme: Boolean): List<PropertyEditorField> =
    if (prototype.kind == ComponentKind.SUBCIRCUIT) listOf(
        PropertyEditorField("facing", PropertyInputKind.DIRECTION, prototype.attributes["facing"] ?: "east",
            options = PropertyEditorModel.standardOptions(PropertyInputKind.DIRECTION)),
        PropertyEditorField("labelloc", PropertyInputKind.DIRECTION, prototype.attributes["labelloc"] ?: "north",
            options = PropertyEditorModel.standardOptions(PropertyInputKind.DIRECTION)),
        PropertyEditorField("labelvisible", PropertyInputKind.BOOLEAN, prototype.attributes["labelvisible"] ?: "true",
            options = PropertyEditorModel.standardOptions(PropertyInputKind.BOOLEAN)),
    ) else PropertyEditorModel.fields(registry, listOf(prototype), darkTheme).filterNot { it.readOnly }

/**
 * The keyboard of a Place tool field: the inspector's (letters for signs, units such as "4.7k" and hex digits, the
 * digit pad only for non-negative whole numbers), always applying on Done, since the sheet's fields are single lines.
 */
internal fun placementKeyboardOptions(field: PropertyEditorField): KeyboardOptions =
    keyboardOptionsFor(field).copy(imeAction = ImeAction.Done)

/**
 * What the inspector would store for [raw] in the Place tool's [field]: its validation, whose [PropertyEditResult.Valid]
 * update is the canonical value ("4.7k" is stored as 4700, as Evolution's EngineeringAttribute writes it).
 */
private fun placementValidation(
    field: PropertyEditorField, prototype: CircuitComponent, registry: ComponentRegistry, darkTheme: Boolean, raw: String,
): PropertyEditResult = PropertyEditorModel.validate(registry, listOf(prototype), field.name, raw, darkTheme)

private fun PropertyEditResult.storedValue(field: PropertyEditorField): String? =
    (this as? PropertyEditResult.Valid)?.updates?.get(field.name)

/**
 * A whole number between − and + buttons; a typed value applies as soon as it is valid, stored as the inspector
 * stores it, while the field keeps the text as typed. A refused value says why under the field. A button whose next
 * value the attribute refuses (a width below 1 or above 64, for example) is disabled rather than doing nothing.
 */
@Composable
private fun IntegerStepperField(
    field: PropertyEditorField, prototype: CircuitComponent, registry: ComponentRegistry, darkTheme: Boolean,
    apply: (String) -> Boolean,
) {
    val strings = rememberLocalizer()
    fun stored(raw: String) = placementValidation(field, prototype, registry, darkTheme, raw).storedValue(field)
    var draft by remember(field.name) { mutableStateOf(field.value.orEmpty()) }
    // A value set elsewhere (a step, a reopened sheet) shows, unless the typed text already means it ("4.7k" for 4700).
    LaunchedEffect(field.value) { if (stored(draft) != field.value) draft = field.value.orEmpty() }
    val result = placementValidation(field, prototype, registry, darkTheme, draft)
    val invalid = result as? PropertyEditResult.Invalid
    val label = strings(field.label)
    fun next(delta: Int) = ((stored(draft)?.toIntOrNull() ?: field.value?.toIntOrNull() ?: 0) + delta).toString()
    fun step(delta: Int) {
        val next = next(delta)
        stored(next)?.let { value -> draft = next; apply(value) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { step(-1) }, enabled = stored(next(-1)) != null, modifier = Modifier.size(48.dp).testTag("placement-attr-${field.name}-minus")
            .semantics { contentDescription = strings("placement_stepper_decrease", "name" to label) }) {
            Text("−", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
        }
        OutlinedTextField(draft, { next -> draft = next; stored(next)?.let(apply) }, singleLine = true,
            label = { Text(label) }, isError = invalid != null,
            supportingText = invalid?.let { { Text(strings(it.notice)) } }
                ?: field.hint?.let { hint -> { Text(strings(hint), fontSize = 11.sp) } },
            keyboardOptions = placementKeyboardOptions(field),
            modifier = Modifier.weight(1f).testTag("placement-attr-${field.name}"))
        IconButton(onClick = { step(1) }, enabled = stored(next(1)) != null, modifier = Modifier.size(48.dp).testTag("placement-attr-${field.name}-plus")
            .semantics { contentDescription = strings("placement_stepper_increase", "name" to label) }) {
            Text("+", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/**
 * Typed text (a hexadecimal value, a color, a font): applied with the keyboard's Done key or Apply, when valid, as the
 * value the inspector would store.
 * Follow-up: colors and fonts should reuse the wave-1 color and font choosers once W2-08's TypedPropertyInspector
 * rework lands; this sheet keeps typed entry for them until then so the two packages do not edit the same chooser.
 */
@Composable
private fun TypedToolField(
    field: PropertyEditorField, prototype: CircuitComponent, registry: ComponentRegistry, darkTheme: Boolean,
    apply: (String) -> Boolean,
) {
    val strings = rememberLocalizer()
    var draft by remember(field.name, field.value) { mutableStateOf(field.value.orEmpty()) }
    val result = placementValidation(field, prototype, registry, darkTheme, draft)
    // The value the inspector would store, not the typed text.
    val stored = result.storedValue(field)
    val canApply = stored != null && draft != field.value.orEmpty()
    OutlinedTextField(draft, { draft = it }, singleLine = true, label = { Text(strings(field.label)) },
        isError = result is PropertyEditResult.Invalid,
        supportingText = (result as? PropertyEditResult.Invalid)?.let { { Text(strings(it.notice)) } }
            ?: field.hint?.let { hint -> { Text(strings(hint), fontSize = 11.sp) } },
        keyboardOptions = placementKeyboardOptions(field),
        keyboardActions = KeyboardActions(onDone = { if (canApply) stored?.let(apply) }),
        trailingIcon = { TextButton(onClick = { stored?.let(apply) }, enabled = canApply, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(strings(Messages.ActionApply))
        } },
        modifier = Modifier.fillMaxWidth().testTag("placement-attr-${field.name}"))
}
