package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.math.roundToInt

fun appearanceEditorTool(imagePicker: AnnotationImagePicker? = null): EditorTool = appearanceEditorTool(imagePicker, AppearanceDraftHolder())

/**
 * The draft outlives the sheet: Back, the scrim or Close keep unapplied edits for the next opening (C96). The editor
 * passes a [drafts] holder it keeps across rebuilding its tool list, so a preference change does not drop the draft.
 */
internal fun appearanceEditorTool(imagePicker: AnnotationImagePicker?, drafts: AppearanceDraftHolder): EditorTool =
    EditorTool("appearance", "Symbol appearance", titleText = message("appearance_title")) {
        AppearanceEditorPanel(it, drafts.clipboard, drafts, imagePicker)
    }

internal data class AppearanceDraftSnapshot(
    val appearance: SymbolAppearance,
    val shapes: Set<String>,
    val marker: String?,
    val clipboard: List<SymbolObject.Shape>? = null,
)

/**
 * The appearance draft of one circuit of one open document, kept outside the sheet's composition. Its baseline is the
 * appearance loaded when editing began, so a theme switch (which recolors the loaded default shape) and selection or
 * clipboard changes are not unapplied changes. It is cleared by Apply, Discard or another document or circuit.
 *
 * Every field is snapshot state: [sync] runs during composition, so a discarded composition discards the key together
 * with the draft it was written with.
 */
internal class AppearanceDraftHolder {
    private var key by mutableStateOf<Pair<Long, String>?>(null)
    private var document by mutableStateOf<CircuitDocument?>(null)
    /** Copied shapes; unlike the draft, they stay when another circuit is edited. */
    val clipboard = mutableStateOf(emptyList<SymbolObject.Shape>())
    var baseline by mutableStateOf<SymbolAppearance?>(null)
        private set
    var draft by mutableStateOf<SymbolAppearance?>(null)
    var selectedShapes by mutableStateOf(emptySet<String>())
    var selectedMarker by mutableStateOf<String?>(null)
    var multiSelect by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val history = mutableStateListOf<AppearanceDraftSnapshot>()
    /** The last color of each paint ("shape-id/fill"), restored when the paint is turned back on. */
    val paints = mutableStateMapOf<String, String>()

    val dirty: Boolean get() = draft != null && draft != baseline

    /** Keeps the draft of the same circuit when it has unapplied changes or nothing changed; otherwise starts from [loaded]. */
    fun sync(identity: Long, document: CircuitDocument, loaded: Result<SymbolAppearance>) {
        val next = identity to document.id
        if (key == next && (dirty || (this.document == document && loaded.getOrNull() == baseline))) return
        reset(next, document, loaded)
    }

    fun discard(identity: Long, document: CircuitDocument, loaded: Result<SymbolAppearance>) = reset(identity to document.id, document, loaded)

    /** Forgets the draft after it was applied; the next composition loads the applied appearance. */
    fun clear() {
        key = null
        document = null
    }

    private fun reset(next: Pair<Long, String>, document: CircuitDocument, loaded: Result<SymbolAppearance>) {
        key = next
        this.document = document
        baseline = loaded.getOrNull()
        draft = baseline
        selectedShapes = emptySet()
        selectedMarker = null
        multiSelect = false
        error = loaded.exceptionOrNull()?.message
        history.clear()
        paints.clear()
    }
}

private enum class AppearanceConfirmation { CLASSIC, RESET }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AppearanceEditorPanel(
    context: EditorToolContext,
    clipboardState: MutableState<List<SymbolObject.Shape>>,
    drafts: AppearanceDraftHolder,
    imagePicker: AnnotationImagePicker?,
) {
    val language = LocalAppLanguage.current
    fun t(key: String) = message(key).resolve(language)
    // Like Evolution's CircuitAppearance, a custom appearance starts from the default one in the current
    // COMPONENT_COLOR, so one begun in the dark theme stores its light outlines (#340).
    val darkTheme = LocalEditorDarkTheme.current
    val componentColor = LocalEditorPreferences.current?.preferences?.effectiveColors(darkTheme)?.get(EditorColor.COMPONENT)
        ?: DarkEditorColors[EditorColor.COMPONENT]?.takeIf { darkTheme }
    val loaded = remember(context.document, componentColor) { runCatching {
        val appearance = SymbolAppearance.read(context.document)
        if (componentColor != null && !SymbolAppearance.isCustom(context.document)) appearance.inComponentColor(componentColor) else appearance
    } }
    drafts.sync(context.documentIdentity, context.document, loaded)
    // Reopened with a draft that was never applied: say so and offer to apply or discard it.
    var resumed by remember(drafts) { mutableStateOf(drafts.dirty) }
    var confirmation by remember(drafts) { mutableStateOf<AppearanceConfirmation?>(null) }
    var draft by drafts::draft
    var selectedShapes by drafts::selectedShapes
    var selectedMarker by drafts::selectedMarker
    var multiSelect by drafts::multiSelect
    var clipboard by clipboardState
    val history = drafts.history
    var error by drafts::error
    val appearance = draft
    fun selectMarker(marker: String?) {
        selectedShapes = emptySet()
        selectedMarker = marker
    }
    fun selectShape(id: String) {
        selectedMarker = null
        selectedShapes = if (!multiSelect) setOf(id)
            else if (id in selectedShapes) selectedShapes - id else selectedShapes + id
    }
    fun checkpoint(original: SymbolAppearance, previousClipboard: List<SymbolObject.Shape>? = null) {
        if (draft == original && previousClipboard == null) return
        if (history.size == 100) history.removeAt(0)
        history += AppearanceDraftSnapshot(original, selectedShapes, selectedMarker, previousClipboard)
    }
    // Like Evolution's attribute table, a field writes the draft as it is typed. Undo stays coarse: the first change of
    // a field's focus session (or of one -/+ press) is one checkpoint, and every other edit is its own step.
    val fieldSession = remember(drafts) { AppearanceFieldSession() }
    fun edit(
        next: SymbolAppearance,
        shapes: Set<String> = selectedShapes,
        marker: String? = selectedMarker,
        previousClipboard: List<SymbolObject.Shape>? = null,
    ) {
        val previous = draft
        draft = next
        if (previous != null) checkpoint(previous, previousClipboard)
        selectedShapes = shapes
        selectedMarker = marker
        error = null
        fieldSession.recorded = false
    }
    fun liveEdit(next: SymbolAppearance) {
        val previous = draft ?: return
        if (next == previous) return
        draft = next
        if (!fieldSession.recorded) {
            checkpoint(previous)
            fieldSession.recorded = true
        }
        error = null
    }
    /** Replaces a shape's attributes and text; false, leaving the draft as it was, when the result is invalid. */
    fun editShape(id: String, attributes: Map<String, String>, text: String, live: Boolean): Boolean {
        val current = draft ?: return false
        val edited = try {
            current.copy(objects = current.objects.map {
                if (it is SymbolObject.Shape && it.id == id) it.copy(attributes = attributes, text = text) else it
            }).also { it.validate(context.document) }
        } catch (failure: IllegalArgumentException) {
            if (!live) error = (failure as? LocalizedArgumentException)?.text?.resolve(language) ?: failure.message
            return false
        }
        if (live) liveEdit(edited) else edit(edited)
        return true
    }
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: IllegalArgumentException) {
            error = (failure as? dev.logisim.mobile.core.localization.LocalizedArgumentException)?.text?.resolve(language) ?: failure.message
        }
    }
    val requestIdentity = remember(context.document) { Any() }
    val latestRequestIdentity by rememberUpdatedState(requestIdentity)
    val latestContext by rememberUpdatedState(context)
    // The project Apply handed to the editor. The editor ignores it on a read-only document or after another document
    // took over, so the draft is forgotten only once the next composition shows the applied project (R04-1).
    var pendingApply by remember(drafts) { mutableStateOf<Pair<Long, CircuitProject>?>(null) }
    LaunchedEffect(pendingApply) {
        val (identity, expected) = pendingApply ?: return@LaunchedEffect
        pendingApply = null
        val current = latestContext
        if (current.project === expected || current.project == expected) {
            drafts.clear()
            resumed = false
            current.showNotice(message("appearance_saved"))
            error = null
        } else if (current.documentIdentity == identity) {
            error = t("appearance_not_applied")
        }
    }

    fun pickImage(shapeId: String? = null) {
        val target = requestIdentity
        imagePicker?.pick { result ->
            if (latestRequestIdentity !== target) return@pick
            result.onSuccess { image ->
                val current = draft ?: return@onSuccess
                if (shapeId == null) {
                    val id = current.nextShapeId()
                    val shape = SymbolObject.Shape(id, "image", mapOf(
                        "x" to current.anchor.position.x.toString(), "y" to (current.anchor.position.y + 10).coerceAtMost(1_000_000).toString(),
                        "width" to image.width.toString(), "height" to image.height.toString(), "scale" to "fit",
                        "href" to image.dataUri,
                    ))
                    edit(current.addShape(shape), setOf(id), null)
                } else {
                    edit(current.copy(objects = current.objects.map { obj ->
                        if (obj is SymbolObject.Shape && obj.id == shapeId)
                            obj.copy(attributes = obj.attributes - "xlink:href" + ("href" to image.dataUri))
                        else obj
                    }))
                }
                error = null
            }.onFailure { failure ->
                error = (failure as? LocalizedArgumentException)?.text?.resolve(language) ?: t("annotation_image_failed")
            }
        }
    }

    fun commit(classic: Boolean = false) {
        attempt {
            val edited = if (classic) AppearanceEdits.useClassic(context.project, context.document.name)
                else AppearanceEdits.apply(context.project, context.document.name, requireNotNull(draft))
            if (!classic && draft?.objects?.any { it is SymbolObject.Shape && it.tag == "image" } == true) {
                requireLocalized(CircCodec.writeProject(edited).encodeToByteArray().size <= CircBundleCodec.MAX_FILE_BYTES) {
                    message(Messages.ErrorXmlSize)
                }
            }
            context.applyProject(edited)
            // The next composition confirms it and loads the new appearance as the baseline. A failure keeps the draft.
            pendingApply = context.documentIdentity to edited
        }
    }
    fun resetDraft() = edit(SymbolAppearance.classic(context.document), emptySet(), null)
    // AppearanceView's grid toggle, persisted as APPEARANCE_SHOW_GRID.
    val preferenceAccess = LocalEditorPreferences.current
    var localGrid by remember { mutableStateOf(true) }
    val showGrid = preferenceAccess?.preferences?.appearanceShowGrid ?: localGrid
    // The preview stays above the scrolling properties, as Evolution's canvas stays beside its attribute table.
    val preview: (@Composable (Boolean) -> Unit)? = appearance?.let { shown -> { keyboardVisible ->
        SymbolPreview(shown, context.document, context.simulation, selectedShapes, selectedMarker, multiSelect, showGrid,
            height = if (keyboardVisible) 120.dp else 180.dp, onSelection = { shapes, marker ->
                selectedShapes = shapes
                selectedMarker = marker
            }, onChange = { draft = it }, onMoveFinished = { checkpoint(it); fieldSession.recorded = false })
    } }
    AppearancePanelLayout(t("appearance_apply"), appearance != null, error, onApply = { commit() }, preview = preview) {
        Text(t("appearance_title"), style = MaterialTheme.typography.titleMedium)
        Text(t("appearance_help"))
        if (resumed && drafts.dirty) Column(Modifier.fillMaxWidth().background(ChromeColors.Selected, RoundedCornerShape(8.dp))
            .border(1.dp, ChromeColors.SelectedBorder, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("appearance-unapplied-banner")) {
            Text(t("appearance_unapplied"), modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { commit() }, modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-unapplied-apply")) {
                    Text(t("action_apply"))
                }
                TextButton(onClick = {
                    drafts.discard(context.documentIdentity, context.document, loaded)
                    resumed = false
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-unapplied-discard")) { Text(t("confirm_discard")) }
            }
        }
        // Both replace the draft, so they ask first when it holds unapplied changes.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (drafts.dirty) confirmation = AppearanceConfirmation.CLASSIC else commit(classic = true) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-use-classic")) { Text(t("appearance_classic")) }
            TextButton(onClick = { if (drafts.dirty) confirmation = AppearanceConfirmation.RESET else resetDraft() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-reset-draft")) { Text(t("appearance_reset_draft")) }
        }
        confirmation?.let { pending ->
            ConfirmActionDialog(
                title = t("appearance_discard_title"),
                text = t(if (pending == AppearanceConfirmation.CLASSIC) "appearance_classic_confirm" else "appearance_reset_confirm"),
                confirmLabel = t(if (pending == AppearanceConfirmation.CLASSIC) "appearance_classic" else "appearance_reset_draft"),
                onConfirm = { if (pending == AppearanceConfirmation.CLASSIC) commit(classic = true) else resetDraft() },
                onDismiss = { confirmation = null },
            )
        }
        if (appearance == null) return@AppearancePanelLayout
        if (appearance.objects.any { it is SymbolObject.Opaque }) Text(t("appearance_opaque"))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(showGrid, role = Role.Switch) { value ->
            if (preferenceAccess != null) preferenceAccess.onChange(preferenceAccess.preferences.copy(appearanceShowGrid = value))
            else localGrid = value
        }.testTag("appearance-show-grid"), verticalAlignment = Alignment.CenterVertically) {
            Text(t("preferences_appearance_show_grid"), modifier = Modifier.weight(1f))
            Switch(showGrid, onCheckedChange = null)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (imagePicker != null) TextButton(onClick = { pickImage() }, modifier = Modifier.testTag("appearance-add-image")) {
                Text("+ ${t("annotation_image")}")
            }
            var addingLed by remember(context.document.id) { mutableStateOf(false) }
            val leds = context.document.components.filter { it.kind == IoKinds.Led }.groupBy { it.position }
                .values.filter { it.size == 1 }.map { it.single() }
            Box {
                TextButton(enabled = leds.isNotEmpty(), onClick = { addingLed = true }) { Text("+ ${t("appearance_led_state")}") }
                DropdownMenu(expanded = addingLed, onDismissRequest = { addingLed = false }) {
                    for (led in leds) DropdownMenuItem(
                        text = { Text(led.label.ifBlank { t("appearance_led_state") } + " (${led.position.x}, ${led.position.y})") },
                        onClick = {
                            addingLed = false
                            val used = appearance.objects.filterIsInstance<SymbolObject.Led>().map { it.id }.toSet() +
                                appearance.objects.filterIsInstance<SymbolObject.Shape>().map { it.id }
                            val id = generateSequence(1) { it + 1 }.map { "led-$it" }.first { it !in used }
                            val bounds = appearance.relativeBounds()
                            val point = GridPoint(appearance.anchor.position.x + (bounds.left + bounds.right) / 2 - 5,
                                appearance.anchor.position.y + (bounds.top + bounds.bottom) / 2 - 5)
                            edit(appearance.copy(objects = appearance.objects + SymbolObject.Led(id, led.id, point)), emptySet(), id)
                        },
                    )
                }
            }
            for ((tag, title) in listOf(
                "rect" to "appearance_rectangle", "ellipse" to "appearance_ellipse", "line" to "appearance_line",
                "path" to "appearance_quadratic_curve", "polygon" to "appearance_polygon", "text" to "annotation_text",
            )) {
                TextButton(onClick = {
                    val id = appearance.nextShapeId()
                    val attributes = when (tag) {
                        "rect" -> mapOf("x" to "0", "y" to "0", "width" to "40", "height" to "30", "fill" to "none", "stroke" to "#000000")
                        "ellipse" -> mapOf("cx" to "20", "cy" to "15", "rx" to "20", "ry" to "15", "fill" to "none", "stroke" to "#000000")
                        "line" -> mapOf("x1" to "0", "y1" to "0", "x2" to "40", "y2" to "30", "fill" to "none", "stroke" to "#000000")
                        "path", "polygon" -> AppearanceEdits.controlPointAttributes(
                            tag, listOf(GridPoint(0, 30), GridPoint(20, 0), GridPoint(40, 30)),
                        ) + mapOf("fill" to "none", "stroke" to "#000000")
                        else -> mapOf("x" to "10", "y" to "20", "font-family" to "SansSerif", "font-size" to "12", "fill" to "#000000", "stroke" to "none")
                    }
                    edit(appearance.addShape(SymbolObject.Shape(id, tag, attributes, if (tag == "text") context.document.name else "")), setOf(id), null)
                }) { Text("+ ${t(title)}") }
            }
        }
        Text(t("appearance_objects"), style = MaterialTheme.typography.titleSmall)
        val shapes = AppearanceSelectionEdits.shapes(appearance, selectedShapes)
        fun insert(source: List<SymbolObject.Shape>, duplicate: Boolean) = attempt {
            val (next, ids) = AppearanceSelectionEdits.insert(appearance, source, duplicate)
            next.validate(context.document)
            edit(next, ids, null)
            if (ids.size > 1) multiSelect = true
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 48.dp).testTag("appearance-multi-select")
                .toggleable(multiSelect, role = Role.Checkbox, onValueChange = {
                    multiSelect = it
                    if (!it) selectedShapes = selectedShapes.lastOrNull()?.let { id -> setOf(id) }.orEmpty()
                }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(multiSelect, onCheckedChange = null)
                Text(t("appearance_multi_select"), Modifier.padding(end = 8.dp))
            }
            TextButton(enabled = appearance.objects.any { it is SymbolObject.Shape }, onClick = {
                multiSelect = true
                selectedShapes = appearance.objects.filterIsInstance<SymbolObject.Shape>().mapTo(linkedSetOf()) { it.id }
                selectedMarker = null
            }) { Text(t("action_select_all")) }
            TextButton(enabled = history.isNotEmpty(), onClick = {
                val previous = history.removeAt(history.lastIndex)
                draft = previous.appearance
                selectedShapes = previous.shapes
                selectedMarker = previous.marker
                previous.clipboard?.let { clipboard = it }
                if (previous.shapes.size > 1) multiSelect = true
                error = null
            }, modifier = Modifier.testTag("appearance-undo-draft")) { Text(t("appearance_undo_draft")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = shapes.isNotEmpty(), onClick = {
                edit(AppearanceSelectionEdits.remove(appearance, selectedShapes), emptySet(), null, previousClipboard = clipboard)
                clipboard = shapes
            }) { Text(t("action_cut")) }
            TextButton(enabled = shapes.isNotEmpty(), onClick = {
                if (clipboard != shapes) {
                    checkpoint(appearance, previousClipboard = clipboard)
                    clipboard = shapes
                }
            }) { Text(t("action_copy")) }
            TextButton(enabled = clipboard.isNotEmpty(), onClick = { insert(clipboard, duplicate = false) }) { Text(t("action_paste")) }
            TextButton(enabled = shapes.isNotEmpty(), onClick = { insert(shapes, duplicate = true) }) { Text(t("action_duplicate")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = shapes.isNotEmpty(), onClick = {
                edit(AppearanceSelectionEdits.layer(appearance, selectedShapes, front = false))
            }) { Text(t("annotation_back")) }
            TextButton(enabled = shapes.isNotEmpty(), onClick = {
                edit(AppearanceSelectionEdits.layer(appearance, selectedShapes, front = true))
            }) { Text(t("annotation_front")) }
        }
        // The objects to edit: markers are one choice (radio), shapes toggle in multi-select (checkbox).
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectableItem(t("appearance_anchor"), selectedMarker == "anchor", Role.RadioButton, Modifier.testTag("appearance-select-anchor")) {
                selectMarker("anchor")
            }
            // Readable names (C101): "Rectangle 1", "Text “ALU”", the LED's label; ids stay in the file and test tags.
            val names = AppearanceObjectNames.of(appearance, context.document)
            appearance.objects.filterIsInstance<SymbolObject.Led>().forEach { led ->
                SelectableItem(names.getValue(led.id).resolve(language), selectedMarker == led.id, Role.RadioButton,
                    Modifier.testTag("appearance-select-${led.id}")) { selectMarker(led.id) }
            }
            appearance.objects.filterIsInstance<SymbolObject.Shape>().forEach { shape ->
                SelectableItem(names.getValue(shape.id).resolve(language), shape.id in selectedShapes, if (multiSelect) Role.Checkbox else Role.RadioButton,
                    Modifier.testTag("appearance-shape-${shape.id}")) { selectShape(shape.id) }
            }
        }
        shapes.singleOrNull()?.let { shape -> key(shape.id) {
            if (shape.tag == "image" && imagePicker != null) TextButton(
                onClick = { pickImage(shape.id) },
                modifier = Modifier.testTag("appearance-replace-image"),
            ) { Text(t("annotation_choose_image")) }
            // Bold and italic write "normal" only over a style the loaded shape had.
            val loaded = drafts.baseline?.objects?.firstOrNull { it is SymbolObject.Shape && it.id == shape.id } as? SymbolObject.Shape
            ShapeProperties(shape, loaded?.attributes ?: shape.attributes, drafts.paints,
                onEdit = { attributes, text, live -> editShape(shape.id, attributes, text, live) },
                onFieldSession = { fieldSession.recorded = false },
                onRemove = { edit(appearance.removeShape(shape.id), emptySet(), null) }, onLayer = { forward ->
                val objects = appearance.objects.toMutableList()
                val index = objects.indexOf(shape)
                val destination = (index + if (forward) 1 else -1).coerceIn(0, objects.lastIndex)
                if (index != destination) {
                    objects.removeAt(index)
                    objects.add(destination, shape)
                    edit(appearance.copy(objects = objects))
                }
            })
        } }
        appearance.objects.filterIsInstance<SymbolObject.Led>().singleOrNull { it.id == selectedMarker }?.let { led -> key(led.id) {
            LedProperties(led, onLive = { edited ->
                val current = draft ?: return@LedProperties false
                val next = current.copy(objects = current.objects.map { if (it is SymbolObject.Led && it.id == led.id) edited else it })
                try { next.validate(context.document) } catch (_: IllegalArgumentException) { return@LedProperties false }
                liveEdit(next)
                true
            }, onFieldSession = { fieldSession.recorded = false }, onRemove = {
                edit(appearance.copy(objects = appearance.objects - led), emptySet(), null)
            })
        } }
        Text(t("appearance_ports"), style = MaterialTheme.typography.titleSmall)
        for (port in appearance.ports) key(port.pinId) {
            CoordinateEditor(AppearanceObjectNames.port(port, context.document).resolve(language), port.position,
                selectedMarker == "port-${port.pinId}", "appearance-port-${port.pinId}",
                onSelect = { selectMarker("port-${port.pinId}") }, onFieldSession = { fieldSession.recorded = false }) { point ->
                val current = draft ?: return@CoordinateEditor
                liveEdit(current.movePort(port.pinId, point))
            }
        }
        Text(t("appearance_anchor"), style = MaterialTheme.typography.titleSmall)
        CoordinateEditor(t("appearance_anchor"), appearance.anchor.position, selectedMarker == "anchor", "appearance-anchor",
            onSelect = { selectMarker("anchor") }, onFieldSession = { fieldSession.recorded = false }) {
            val current = draft ?: return@CoordinateEditor
            liveEdit(current.copy(anchor = current.anchor.copy(position = it)))
        }
        // AppearanceAnchor.FACING ("Appearance Facing"): one of four directions, in Evolution's Direction.cardinals order.
        Text(t("appearance_facing"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((facing, arrow) in listOf("east" to "→", "north" to "↑", "west" to "←", "south" to "↓")) {
                val chosen = appearance.anchor.facing == facing
                Row(Modifier.heightIn(min = 48.dp).selectable(chosen, role = Role.RadioButton) {
                    if (!chosen) edit(appearance.copy(anchor = appearance.anchor.copy(facing = facing)))
                }.testTag("appearance-facing-$facing").padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = null)
                    Text("$arrow ${t("appearance_$facing")}", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/**
 * A choice shown by its highlight and its selection semantics, not by a check-mark prefix. The whole 48 dp box takes the
 * tap; the outline is drawn inside its vertical inset.
 */
@Composable
private fun SelectableItem(label: String, selected: Boolean, role: Role, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .selectable(selected, role = role, onClick = onClick)
        .padding(vertical = 4.dp)
        .background(if (selected) ChromeColors.Selected else Color.Transparent, shape)
        .border(1.dp, if (selected) ChromeColors.SelectedBorder else ChromeColors.Divider, shape)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
    }
}

/**
 * What the appearance editor calls its objects and ports (C101). Evolution names a shape by its type
 * (DrawingAttributeSet's display names, e.g. "Rectangle"); here a 1-based number per type tells several apart, a text
 * shape shows its content and an LED its circuit LED's label. The ids ("shape-1", "led-2", "c12") are only written to
 * the file and used as test tags.
 */
internal object AppearanceObjectNames {
    private const val TEXT_LENGTH = 16

    /** The message key of a shape's type. */
    fun typeKey(shape: SymbolObject.Shape): String = when (shape.tag) {
        "rect" -> "appearance_rectangle"
        "ellipse" -> "appearance_ellipse"
        "line" -> "appearance_line"
        "path" -> if (AppearanceEdits.controlPoints(shape) != null) "appearance_quadratic_curve" else "appearance_path"
        "polygon" -> "appearance_polygon"
        "polyline" -> "appearance_polyline"
        "text" -> "annotation_text"
        "image" -> "annotation_image"
        else -> "appearance_shape_other"
    }

    /** The name of every shape and LED of [appearance], by object id. */
    fun of(appearance: SymbolAppearance, circuit: CircuitDocument): Map<String, LocalizedText> {
        val counts = mutableMapOf<String, Int>()
        return appearance.objects.mapNotNull { obj ->
            when (obj) {
                is SymbolObject.Shape -> {
                    val type = typeKey(obj)
                    val number = (counts[type] ?: 0) + 1
                    counts[type] = number
                    val content = obj.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    obj.id to if (obj.tag == "text" && content != null) message("appearance_shape_text",
                        "text" to if (content.length > TEXT_LENGTH) content.take(TEXT_LENGTH - 1) + "…" else content)
                    else message("appearance_shape_name", "type" to message(type), "number" to number)
                }
                is SymbolObject.Led -> {
                    val number = (counts["led"] ?: 0) + 1
                    counts["led"] = number
                    val label = circuit.components.firstOrNull { it.id == obj.sourceId }?.label?.takeIf(String::isNotBlank)
                    obj.id to (label?.let(LocalizedText::literal)
                        ?: message("appearance_shape_name", "type" to message("appearance_led_state"), "number" to number))
                }
                is SymbolObject.Opaque -> null
            }
        }.toMap()
    }

    /** A port's pin: its label, else its direction, position and width ("Input pin (20, 30)"). */
    fun port(port: SymbolPort, circuit: CircuitDocument): LocalizedText {
        val pin = circuit.components.firstOrNull { it.id == port.pinId } ?: return message("appearance_port_missing")
        if (pin.label.isNotBlank()) return LocalizedText.literal(pin.label)
        val output = pin.kind == ComponentKind.OUTPUT
        return if (pin.width > 1) message(if (output) "appearance_port_output_bits" else "appearance_port_input_bits",
            "x" to pin.position.x, "y" to pin.position.y, "width" to pin.width)
            else message(if (output) "appearance_port_output" else "appearance_port_input", "x" to pin.position.x, "y" to pin.position.y)
    }
}

private fun SymbolAppearance.nextShapeId(): String {
    val used = objects.filterIsInstance<SymbolObject.Shape>().mapTo(mutableSetOf()) { it.id }
    var number = 1
    while ("shape-$number" in used) number++
    return "shape-$number"
}

@Composable
private fun AppearancePanelLayout(
    applyLabel: String,
    applyEnabled: Boolean,
    error: String?,
    onApply: () -> Unit,
    preview: (@Composable (keyboardVisible: Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxWidth().heightIn(max = 820.dp)) {
        if (!keyboardVisible) {
            Button(onClick = onApply, enabled = applyEnabled,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("appearance-apply")) {
                Text(applyLabel)
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        preview?.let { Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) { it(keyboardVisible) } }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun LedProperties(led: SymbolObject.Led, onLive: (SymbolObject.Led) -> Boolean, onFieldSession: () -> Unit, onRemove: () -> Unit) {
    val language = LocalAppLanguage.current
    fun coordinate(raw: String) = raw.trim().toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveNumberField("X", led.position.x.toString(), { raw ->
            coordinate(raw)?.let { onLive(led.copy(position = GridPoint(it, led.position.y))) } == true
        }, onFieldSession, 10, Modifier.weight(1f).testTag("appearance-led-x"))
        LiveNumberField("Y", led.position.y.toString(), { raw ->
            coordinate(raw)?.let { onLive(led.copy(position = GridPoint(led.position.x, it))) } == true
        }, onFieldSession, 10, Modifier.weight(1f).testTag("appearance-led-y"))
    }
    LiveNumberField(message("appearance_led_radius").resolve(language), led.radius.toString(), { raw ->
        raw.trim().toIntOrNull()?.takeIf { it in 1..20 }?.let { onLive(led.copy(radius = it)) } == true
    }, onFieldSession, 1, Modifier.fillMaxWidth().testTag("appearance-led-radius"), range = 1..20)
    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(message("appearance_remove_object").resolve(language)) }
}

/** Whether a field's first change since it gained focus (or since the last discrete edit) already took a checkpoint. */
internal class AppearanceFieldSession {
    var recorded = false
}

/**
 * The text of a live field. While it has focus it is the user's own, so partial input ("-", "1.") and the cursor
 * survive the draft changing under it; otherwise it shows the draft. An external change (undo) replaces it.
 */
private class LiveFieldText(initial: String) {
    var focused by mutableStateOf(false)
    var text by mutableStateOf(initial)
    var rejected by mutableStateOf(false)
    var written: String? = null

    fun shown(value: String) = if (focused) text else value

    fun focus(value: String, isFocused: Boolean, onSession: () -> Unit) {
        if (isFocused && !focused) {
            text = value
            rejected = false
            onSession()
        }
        focused = isFocused
    }

    /** Keeps what was typed; [apply] writes it to the draft when it is valid and reports whether it did. */
    fun type(typed: String, apply: (String) -> Boolean) {
        text = typed
        rejected = !apply(typed)
        if (!rejected) written = typed.trim()
    }

    fun sync(value: String) {
        if (focused && value != written && value != text.trim()) {
            text = value
            rejected = false
        }
    }
}

@Composable
private fun rememberLiveFieldText(value: String): LiveFieldText {
    val field = remember { LiveFieldText(value) }
    LaunchedEffect(value) { field.sync(value) }
    return field
}

/** A [SteppedNumberField] that writes every valid value to the draft at once ([onValue] reports whether it took it). */
@Composable
private fun LiveNumberField(
    label: String,
    value: String,
    onValue: (String) -> Boolean,
    onFieldSession: () -> Unit,
    step: Int,
    modifier: Modifier = Modifier,
    range: IntRange = -1_000_000..1_000_000,
) {
    val field = rememberLiveFieldText(value)
    SteppedNumberField(label, field.shown(value), { typed -> field.type(typed) { it.trim().toDoubleOrNull()?.isFinite() == true && onValue(it) } },
        step, modifier.onFocusChanged { field.focus(value, it.isFocused, onFieldSession) }, range = range,
        isError = field.focused && field.rejected, onStep = onFieldSession)
}

/** A text field written to the draft as it is typed, like [LiveNumberField]. */
@Composable
private fun LiveTextField(
    label: String,
    value: String,
    onValue: (String) -> Boolean,
    onFieldSession: () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val field = rememberLiveFieldText(value)
    OutlinedTextField(field.shown(value), { typed -> field.type(typed, onValue) },
        modifier.onFocusChanged { field.focus(value, it.isFocused, onFieldSession) },
        label = { Text(label) }, singleLine = singleLine, isError = field.focused && field.rejected)
}

/**
 * A whole-number field on the number pad with −/+ buttons: the iOS number pad and Android's unsigned number keyboard
 * have no minus sign, and appearance coordinates may be negative.
 */
@Composable
internal fun SteppedNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    step: Int,
    modifier: Modifier = Modifier,
    range: IntRange = -1_000_000..1_000_000,
    isError: Boolean = false,
    /** Called before each -/+ press, which is its own edit. */
    onStep: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    fun shift(delta: Int) {
        val current = value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.roundToInt() ?: return
        val next = (current + delta).coerceIn(range)
        if (next.toString() != value) {
            onStep()
            onValueChange(next.toString())
        }
    }
    @Composable fun stepButton(symbol: String, key: String, delta: Int) {
        val description = message(key, "label" to label, "step" to step).resolve(language)
        // A full 48 dp target: on the number pads these are the only way to a negative value.
        Box(Modifier.size(48.dp).clickable(role = Role.Button) { shift(delta) }.semantics { contentDescription = description },
            contentAlignment = Alignment.Center) { Text(symbol, style = MaterialTheme.typography.titleMedium) }
    }
    OutlinedTextField(value, onValueChange, modifier, label = { Text(label, maxLines = 1) }, singleLine = true, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        leadingIcon = { stepButton("−", "appearance_step_decrease", -step) },
        trailingIcon = { stepButton("+", "appearance_step_increase", step) })
}

/**
 * The editable shape attributes as Evolution's DrawAttr presents them: colors with alpha (fill/stroke-opacity), stroke width
 * 1–8, font family and size, bold and italic, and text alignment. Each writes its SVG attribute only when the user changes
 * it, so an imported appearance is written back byte for byte.
 */
internal object AppearanceShapeControls {
    val STROKE_WIDTHS: List<String> = (1..8).map(Int::toString)

    enum class HorizontalAlign(val svg: String, val label: String) {
        LEFT("start", "appearance_align_left"), CENTER("middle", "appearance_align_center"), RIGHT("end", "appearance_align_right"),
    }

    enum class VerticalAlign(val svg: String, val label: String) {
        TOP("top", "appearance_align_top"), MIDDLE("central", "appearance_align_middle"),
        BASELINE("alphabetic", "appearance_align_baseline"), BOTTOM("bottom", "appearance_align_bottom"),
    }

    /** A loaded width outside 1–8 or not a whole number (1.5) is offered as one more entry instead of being coerced. */
    fun strokeWidths(current: String?): List<String> = if (current == null || current in STROKE_WIDTHS) STROKE_WIDTHS else STROKE_WIDTHS + current

    fun strokeWidth(attributes: Map<String, String>): String = attributes["stroke-width"] ?: "1"

    fun withStrokeWidth(attributes: Map<String, String>, width: String): Map<String, String> =
        if (strokeWidth(attributes) == width) attributes else attributes + ("stroke-width" to width)

    /** SymbolVisuals draws a missing or unknown text-anchor centered, like SvgReader. */
    fun horizontal(attributes: Map<String, String>): HorizontalAlign =
        HorizontalAlign.entries.firstOrNull { it.svg == attributes["text-anchor"] } ?: HorizontalAlign.CENTER

    fun withHorizontal(attributes: Map<String, String>, align: HorizontalAlign): Map<String, String> =
        if (horizontal(attributes) == align) attributes else attributes + ("text-anchor" to align.svg)

    fun vertical(attributes: Map<String, String>): VerticalAlign =
        VerticalAlign.entries.firstOrNull { it.svg == attributes["dominant-baseline"] } ?: VerticalAlign.MIDDLE

    fun withVertical(attributes: Map<String, String>, align: VerticalAlign): Map<String, String> =
        if (vertical(attributes) == align) attributes else attributes + ("dominant-baseline" to align.svg)

    fun fontFamily(attributes: Map<String, String>): String = attributes["font-family"]?.takeIf(String::isNotBlank) ?: "SansSerif"

    fun withFontFamily(attributes: Map<String, String>, family: String): Map<String, String> =
        if (fontFamily(attributes) == family) attributes else attributes + ("font-family" to family)

    fun fontSize(attributes: Map<String, String>): Int =
        attributes["font-size"]?.toDoubleOrNull()?.roundToInt()?.coerceIn(FontChoice.SIZE_RANGE) ?: 12

    fun withFontSize(attributes: Map<String, String>, size: Int): Map<String, String> =
        if (attributes["font-size"] == size.toString()) attributes else attributes + ("font-size" to size.toString())

    /** Bold or italic on writes "bold"/"italic"; off removes an attribute the shape did not have, else writes "normal". */
    fun withStyle(attributes: Map<String, String>, original: Map<String, String>, key: String, on: Boolean): Map<String, String> {
        val value = if (key == "font-weight") "bold" else "italic"
        return when {
            on -> attributes + (key to value)
            key !in original -> attributes - key
            else -> attributes + (key to "normal")
        }
    }

    private val NAMED_COLORS = mapOf("black" to 0x000000, "white" to 0xffffff, "gray" to 0x808080, "red" to 0xff0000,
        "green" to 0x008000, "blue" to 0x0000ff)

    /** The paint and its opacity as one color with alpha; null for "none" or an unreadable paint. */
    fun paint(attributes: Map<String, String>, key: String, tag: String): ColorPickerModel? {
        val raw = attributes[key] ?: defaultPaint(key, tag)
        val rgb = ColorPickerModel.parse(raw)?.rgb ?: NAMED_COLORS[raw.lowercase()] ?: return null
        return ColorPickerModel(rgb, alpha(attributes["$key-opacity"]))
    }

    fun defaultPaint(key: String, tag: String): String = if (key == "fill") SymbolVisuals.defaultFill(tag) else SymbolVisuals.defaultStroke(tag)

    /** The alpha of an opacity attribute; null (opaque, `#rrggbb`) when the shape has none. */
    fun alpha(opacity: String?): Int? = opacity?.replace(',', '.')?.toDoubleOrNull()?.takeIf(Double::isFinite)
        ?.let { (it.coerceIn(0.0, 1.0) * 255).roundToInt() }

    /** Opacity with at most two decimals: 1, 0.5, 0.25. */
    fun opacity(alpha: Int): String {
        val hundredths = (alpha.coerceIn(0, 255) * 100.0 / 255.0).roundToInt()
        return when {
            hundredths >= 100 -> "1"
            hundredths <= 0 -> "0"
            hundredths % 10 == 0 -> "0.${hundredths / 10}"
            else -> "0." + hundredths.toString().padStart(2, '0')
        }
    }

    /** Writes the color only when its RGB changed and the opacity only when the alpha slider moved. */
    fun withPaint(attributes: Map<String, String>, key: String, shown: ColorPickerModel, picked: ColorPickerModel): Map<String, String> {
        var next = attributes
        if (picked.rgb != shown.rgb) next = next + (key to picked.rgbHex)
        val alpha = picked.alpha ?: 0xFF
        if (alpha != (shown.alpha ?: 0xFF)) next = next + ("$key-opacity" to opacity(alpha))
        return next
    }

    /** Turning a paint on restores its last color, else the other paint's, else the shape's default ink. */
    fun withPaintEnabled(attributes: Map<String, String>, key: String, tag: String, on: Boolean, remembered: String?): Map<String, String> {
        if (!on) return attributes + (key to "none")
        val other = attributes[if (key == "fill") "stroke" else "fill"]?.takeIf { ColorPickerModel.parse(it) != null }
        val ink = listOf(SymbolVisuals.defaultFill(tag), SymbolVisuals.defaultStroke(tag)).first { it != "none" }
        return attributes + (key to (remembered ?: other ?: ink))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ShapeProperties(
    shape: SymbolObject.Shape,
    /** The shape's attributes when editing began: bold and italic off remove a style it did not have. */
    original: Map<String, String>,
    paints: MutableMap<String, String>,
    /** Writes the shape's attributes and text to the draft, live (one undo step per field) or as its own step. */
    onEdit: (Map<String, String>, String, Boolean) -> Boolean,
    onFieldSession: () -> Unit,
    onRemove: () -> Unit,
    onLayer: (Boolean) -> Unit,
) {
    val language = LocalAppLanguage.current
    fun t(key: String) = message(key).resolve(language)
    val attributes = shape.attributes
    val text = shape.text
    fun live(updated: Map<String, String>, content: String = text) = onEdit(updated, content, true)
    fun change(updated: Map<String, String>, content: String = text) { onEdit(updated, content, false) }
    fun coordinate(raw: String) = raw.trim().toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }
    val points = AppearanceEdits.controlPoints(shape)
    val geometry = if (points != null) emptyList() else when (shape.tag) {
        "rect" -> listOf("x", "y", "width", "height", "rx")
        "image" -> listOf("x", "y", "width", "height")
        "ellipse" -> listOf("cx", "cy", "rx", "ry")
        "line" -> listOf("x1", "y1", "x2", "y2")
        "text" -> listOf("x", "y")
        "path" -> listOf("d")
        else -> listOf("points")
    }
    if (points != null) {
        val polygon = shape.tag == "polygon"
        val curveLabels = listOf("appearance_curve_start", "appearance_curve_control", "appearance_curve_end")
        fun withPoints(next: List<GridPoint>) = attributes + AppearanceEdits.controlPointAttributes(shape.tag, next)
        for ((index, point) in points.withIndex()) key(index) {
            val label = if (polygon) message("appearance_vertex", "index" to index + 1).resolve(language) else t(curveLabels[index])
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun moved(next: GridPoint) = live(withPoints(points.toMutableList().also { it[index] = next }))
                LiveNumberField("$label X", point.x.toString(), { raw -> coordinate(raw)?.let { moved(GridPoint(it, point.y)) } == true },
                    onFieldSession, 10, Modifier.weight(1f))
                LiveNumberField("$label Y", point.y.toString(), { raw -> coordinate(raw)?.let { moved(GridPoint(point.x, it)) } == true },
                    onFieldSession, 10, Modifier.weight(1f))
            }
            if (polygon && points.size > 3) {
                TextButton(onClick = { change(withPoints(points.toMutableList().also { it.removeAt(index) })) },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(message("appearance_remove_vertex", "index" to index + 1).resolve(language))
                }
            }
        }
        if (polygon) {
            TextButton(onClick = { change(withPoints(points + points.last())) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(t("appearance_add_vertex"))
            }
        }
    }
    val defaults = mapOf("rx" to "0", "ry" to "0")
    for (pair in geometry.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in pair) {
            val raw = attributes[name] ?: defaults[name].orEmpty()
            // Evolution labels the rectangle's rx "Corner Radius"; ry follows it. An ellipse keeps its two radii.
            val label = if (name == "rx" && shape.tag == "rect") t("appearance_property_corner_radius")
                else t("appearance_property_" + name.replace('-', '_'))
            fun updated(value: String): Map<String, String> {
                val next = attributes + (name to value)
                return if (name == "rx" && shape.tag == "rect") next + ("ry" to value) else next
            }
            if (name == "d" || name == "points") LiveTextField(label, raw, { live(updated(it.trim())) }, onFieldSession,
                Modifier.weight(1f).testTag("appearance-object-$name"))
            else LiveNumberField(label, raw, { live(updated(it.trim())) }, onFieldSession, 10,
                Modifier.weight(1f).testTag("appearance-object-$name"))
        }
    }
    if (shape.tag != "image") {
        for (paint in if (shape.tag == "text") listOf("fill") else listOf("fill", "stroke")) {
            PaintControl(shape, paint, attributes, paints) { change(it) }
        }
    }
    if (shape.tag != "image" && shape.tag != "text") {
        var widths by remember { mutableStateOf(false) }
        val current = AppearanceShapeControls.strokeWidth(attributes)
        Box {
            ChoiceField(t("appearance_property_stroke_width"), current, onClick = { widths = true }, tag = "appearance-object-stroke-width")
            DropdownMenu(expanded = widths, onDismissRequest = { widths = false }) {
                AppearanceShapeControls.strokeWidths(attributes["stroke-width"]).forEach { width ->
                    DropdownMenuItem(text = { Text(width) }, leadingIcon = { SelectedMark(width == current) }, onClick = {
                        widths = false
                        change(AppearanceShapeControls.withStrokeWidth(attributes, width))
                    }, modifier = Modifier.heightIn(min = 48.dp).semantics { selected = width == current }.testTag("appearance-stroke-width-$width"))
                }
            }
        }
    }
    if (shape.tag == "image") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (scale in listOf("fit", "cover", "stretch")) {
                val selected = (attributes["scale"] ?: "fit") == scale
                FilterChip(selected = selected, onClick = { if (!selected) change(attributes + ("scale" to scale)) },
                    label = { Text(t("annotation_$scale")) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    if (shape.tag == "text") {
        LiveTextField(t("annotation_text"), text, { content -> live(attributes, content) }, onFieldSession,
            Modifier.fillMaxWidth().testTag("appearance-object-text"), singleLine = false)
        FontFamilyMenu(t("appearance_property_font_family"), AppearanceShapeControls.fontFamily(attributes), {
            change(AppearanceShapeControls.withFontFamily(attributes, it))
        }, tag = "appearance-object-font-family")
        FontSizeStepper(t("appearance_property_font_size"), AppearanceShapeControls.fontSize(attributes),
            attributes["font-size"] ?: "12", { change(AppearanceShapeControls.withFontSize(attributes, it)) }, tag = "appearance-object-font-size")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((style, label) in listOf("font-weight" to "appearance_bold", "font-style" to "appearance_italic")) {
                val on = attributes[style] == if (style == "font-weight") "bold" else "italic"
                FilterChip(selected = on, onClick = { change(AppearanceShapeControls.withStyle(attributes, original, style, !on)) },
                    label = { Text(t(label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-object-$style"))
            }
        }
        val horizontal = AppearanceShapeControls.horizontal(attributes)
        Text(t("appearance_halign"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            AppearanceShapeControls.HorizontalAlign.entries.forEach { align ->
                RadioItem(t(align.label), align == horizontal, Modifier.testTag("appearance-halign-${align.svg}")) {
                    change(AppearanceShapeControls.withHorizontal(attributes, align))
                }
            }
        }
        val vertical = AppearanceShapeControls.vertical(attributes)
        Text(t("appearance_valign"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            AppearanceShapeControls.VerticalAlign.entries.forEach { align ->
                RadioItem(t(align.label), align == vertical, Modifier.testTag("appearance-valign-${align.svg}")) {
                    change(AppearanceShapeControls.withVertical(attributes, align))
                }
            }
        }
    }
    // Every change above is already in the draft, so the object has no Update button: only its removal and layer.
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-remove-object")) {
            Text(t("appearance_remove_object"))
        }
        TextButton(onClick = { onLayer(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("annotation_backward")) }
        TextButton(onClick = { onLayer(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(t("annotation_forward")) }
    }
}

/** A radio choice with its label: selectable(role = RadioButton), so TalkBack says "selected". */
@Composable
internal fun RadioItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton) { if (!selected) onClick() }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Fill or outline: on/off, and a swatch opening the color picker whose alpha is the paint's opacity. */
@Composable
private fun PaintControl(shape: SymbolObject.Shape, key: String, attributes: Map<String, String>, paints: MutableMap<String, String>,
                         onChange: (Map<String, String>) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(name: String) = message(name).resolve(language)
    val raw = attributes[key] ?: AppearanceShapeControls.defaultPaint(key, shape.tag)
    val enabled = raw != "none"
    val memory = "${shape.id}/$key"
    val color = AppearanceShapeControls.paint(attributes, key, shape.tag)
    var picking by remember(shape.id, key) { mutableStateOf(false) }
    val name = t("appearance_property_$key")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(value = enabled, role = Role.Checkbox, onValueChange = { on ->
            if (!on && enabled) paints[memory] = raw
            onChange(AppearanceShapeControls.withPaintEnabled(attributes, key, shape.tag, on, paints[memory]))
        }).testTag("appearance-object-$key-enabled"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(enabled, onCheckedChange = null)
            Text(t(if (key == "fill") "appearance_fill_enabled" else "appearance_stroke_enabled"))
        }
        if (enabled) {
            Text(color?.hex ?: raw, style = MaterialTheme.typography.bodySmall, color = ChromeColors.Muted, modifier = Modifier.padding(end = 4.dp))
            ColorSwatchButton(name, color ?: ColorPickerModel(0), onClick = { picking = true },
                modifier = Modifier.testTag("appearance-object-$key"), enabled = color != null)
        }
    }
    if (picking && color != null) {
        val ink = AppearanceShapeControls.withPaintEnabled(emptyMap(), key, shape.tag, true, null)[key]
        ColorPickerDialog(
            title = name,
            initial = color,
            default = ink?.let(ColorPickerModel::parse)?.copy(alpha = 0xFF),
            alphaEnabled = true,
            onPick = { picked -> onChange(AppearanceShapeControls.withPaint(attributes, key, color, picked)) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CoordinateEditor(
    label: String,
    position: GridPoint,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
    onFieldSession: () -> Unit,
    onChange: (GridPoint) -> Unit,
) {
    fun parse(raw: String) = raw.trim().toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }
    Column(Modifier.fillMaxWidth()) {
        // Selecting the port or anchor highlights it in the preview, as a radio choice among the markers.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (selected) ChromeColors.Selected else Color.Transparent, RoundedCornerShape(8.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onSelect).testTag("$tag-select"),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, modifier = Modifier.padding(start = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveNumberField("X", position.x.toString(), { raw -> parse(raw)?.let { onChange(GridPoint(it, position.y)) } != null },
                onFieldSession, 10, Modifier.weight(1f).testTag("$tag-x"))
            LiveNumberField("Y", position.y.toString(), { raw -> parse(raw)?.let { onChange(GridPoint(position.x, it)) } != null },
                onFieldSession, 10, Modifier.weight(1f).testTag("$tag-y"))
        }
    }
}

@Composable
private fun SymbolPreview(
    appearance: SymbolAppearance,
    circuit: CircuitDocument,
    simulation: SimulationState,
    selectedShapes: Set<String>,
    selectedMarker: String?,
    multiSelect: Boolean,
    showGrid: Boolean,
    height: Dp,
    onSelection: (Set<String>, String?) -> Unit,
    onChange: (SymbolAppearance) -> Unit,
    onMoveFinished: (SymbolAppearance) -> Unit,
) {
    val scene = remember(appearance, circuit, simulation) {
        runCatching { SymbolVisuals.symbol(appearance, handles = true, circuit = circuit, simulation = simulation) }.getOrNull()
    } ?: return
    val measurer = rememberTextMeasurer()
    val images = rememberAnnotationImageCache()
    var viewport by remember { mutableStateOf(PreviewViewport(1f, Offset.Zero)) }
    var dragViewport by remember { mutableStateOf<PreviewViewport?>(null) }
    val latestAppearance by rememberUpdatedState(appearance)
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnSelection by rememberUpdatedState(onSelection)
    val latestOnMoveFinished by rememberUpdatedState(onMoveFinished)
    val latestShapes by rememberUpdatedState(selectedShapes)
    val latestMarker by rememberUpdatedState(selectedMarker)
    val latestMultiSelect by rememberUpdatedState(multiSelect)
    val targetRadius = with(LocalDensity.current) { 24.dp.toPx() }
    val latestTargetRadius by rememberUpdatedState(targetRadius)
    val previewDescription = message("appearance_preview").resolve(LocalAppLanguage.current)
    fun hit(position: Offset): String? = appearancePreviewHit(
        latestAppearance, position, latestViewport.origin, latestViewport.zoom, latestTargetRadius, latestMarker)
    fun isMarker(id: String): Boolean = id == "anchor" || latestAppearance.ports.any { id == "port-${it.pinId}" } ||
        latestAppearance.objects.any { it is SymbolObject.Led && it.id == id }
    // The appearance grid uses the canvas GRID_DOT_COLOR of the theme in use.
    val previewDark = LocalEditorDarkTheme.current
    val gridDot = (LocalEditorPreferences.current?.preferences?.effectiveColors(previewDark) ?: if (previewDark) DarkEditorColors else emptyMap())
        .color(EditorColor.GRID_DOT, EditorColor.GRID_DOT.defaultColor(false))
    val previewBackground = if (LocalEditorDarkTheme.current) Color(0xFF2B2B2B) else Color.White
    Canvas(Modifier.fillMaxWidth().height(height).background(previewBackground).border(1.dp, Color.Gray)
        .testTag("appearance-preview")
        .semantics { contentDescription = previewDescription }
        .pointerInput(Unit) {
            detectTapGestures { position ->
                val id = hit(position)
                when {
                    id == null || isMarker(id) -> latestOnSelection(emptySet(), id)
                    !latestMultiSelect -> latestOnSelection(setOf(id), null)
                    id in latestShapes -> latestOnSelection(latestShapes - id, null)
                    else -> latestOnSelection(latestShapes + id, null)
                }
            }
        }
        .pointerInput(Unit) {
            var target: String? = null
            var movingShapes = emptySet<String>()
            var original: SymbolAppearance? = null
            var total = Offset.Zero
            detectDragGestures(onDragStart = {
                val id = hit(it)
                target = id
                original = latestAppearance
                total = Offset.Zero
                dragViewport = latestViewport
                movingShapes = when {
                    id == null || isMarker(id) -> emptySet()
                    id in latestShapes -> latestShapes
                    else -> setOf(id)
                }
                latestOnSelection(movingShapes, id?.takeIf(::isMarker))
            }, onDragEnd = {
                original?.let { latestOnMoveFinished(it) }
                dragViewport = null
            }, onDragCancel = {
                original?.let { latestOnChange(it) }
                dragViewport = null
            }, onDrag = { change, delta ->
                val id = target ?: return@detectDragGestures
                change.consume(); total += delta
                val start = original ?: return@detectDragGestures
                val zoom = (dragViewport ?: latestViewport).zoom
                val dx = (total.x / zoom).roundToInt(); val dy = (total.y / zoom).roundToInt()
                val updated = when {
                    id == "anchor" -> start.copy(anchor = start.anchor.copy(position = GridPoint(start.anchor.position.x + dx, start.anchor.position.y + dy)))
                    start.ports.any { "port-${it.pinId}" == id } -> { val port = start.ports.single { "port-${it.pinId}" == id }; start.movePort(port.pinId, GridPoint(port.position.x + dx, port.position.y + dy)) }
                    else -> start.copy(objects = start.objects.map {
                        when {
                            it is SymbolObject.Shape && it.id in movingShapes -> AppearanceEdits.translate(it, dx, dy)
                            it is SymbolObject.Led && it.id == id -> it.copy(position = GridPoint(it.position.x + dx, it.position.y + dy))
                            else -> it
                        }
                    })
                }
                latestOnChange(updated)
            })
        }) {
        val bounds = scene.items.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: VisualBounds(0.0, 0.0, 40.0, 40.0)
        val zoom = dragViewport?.zoom ?: minOf((size.width - 48) / maxOf(40f, bounds.width.toFloat()), (size.height - 48) / maxOf(40f, bounds.height.toFloat())).coerceAtMost(4f)
        val origin = dragViewport?.origin ?: Offset(size.width / 2 - (bounds.left + bounds.width / 2).toFloat() * zoom, size.height / 2 - (bounds.top + bounds.height / 2).toFloat() * zoom)
        viewport = PreviewViewport(zoom, origin)
        if (showGrid) appearanceGridPoints(origin, zoom, size.width, size.height).forEach { drawCircle(gridDot, 1f, it) }
        withTransform({ translate(origin.x, origin.y); scale(zoom, zoom, Offset.Zero) }) {
            drawVisualScene(scene, measurer, images)
            scene.items.filter { it.id == selectedMarker || it.id in selectedShapes }.forEach { item ->
                val box = item.bounds
                drawRect(Color(0xff1479d0), Offset(box.left.toFloat(), box.top.toFloat()), androidx.compose.ui.geometry.Size(box.width.toFloat(), box.height.toFloat()), style = androidx.compose.ui.graphics.drawscope.Stroke(1f / zoom))
            }
        }
    }
}

private data class PreviewViewport(val zoom: Float, val origin: Offset)

/** Screen positions of the 10-unit appearance grid dots, skipped when they would be closer than 4 px. */
internal fun appearanceGridPoints(origin: Offset, zoom: Float, width: Float, height: Float): List<Offset> {
    val spacing = 10f * zoom
    if (spacing < 4f || !spacing.isFinite()) return emptyList()
    val firstX = origin.x - kotlin.math.floor(origin.x / spacing) * spacing
    val firstY = origin.y - kotlin.math.floor(origin.y / spacing) * spacing
    val columns = ((width - firstX) / spacing).toInt() + 1
    val rows = ((height - firstY) / spacing).toInt() + 1
    return buildList { for (row in 0 until rows) for (column in 0 until columns) add(Offset(firstX + column * spacing, firstY + row * spacing)) }
}
