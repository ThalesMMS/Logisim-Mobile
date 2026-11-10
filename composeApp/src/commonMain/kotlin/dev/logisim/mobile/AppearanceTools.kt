package dev.logisim.mobile

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.math.roundToInt

fun appearanceEditorTool(imagePicker: AnnotationImagePicker? = null): EditorTool {
    val clipboard = mutableStateOf(emptyList<SymbolObject.Shape>())
    return EditorTool("appearance", "Symbol appearance", titleText = message("appearance_title")) {
        AppearanceEditorPanel(it, clipboard, imagePicker)
    }
}

private data class AppearanceDraftSnapshot(
    val appearance: SymbolAppearance,
    val shapes: Set<String>,
    val marker: String?,
    val clipboard: List<SymbolObject.Shape>? = null,
)

@Composable
private fun AppearanceEditorPanel(context: EditorToolContext, clipboardState: MutableState<List<SymbolObject.Shape>>, imagePicker: AnnotationImagePicker?) {
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
    var draft by remember(context.document) { mutableStateOf(loaded.getOrNull()) }
    var selectedShapes by remember(context.document) { mutableStateOf(emptySet<String>()) }
    var selectedMarker by remember(context.document) { mutableStateOf<String?>(null) }
    var multiSelect by remember(context.document) { mutableStateOf(false) }
    var clipboard by clipboardState
    val history = remember(context.document) { mutableStateListOf<AppearanceDraftSnapshot>() }
    var error by remember(context.document) { mutableStateOf(loaded.exceptionOrNull()?.message) }
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
            context.showNotice(message("appearance_saved"))
            error = null
        }
    }
    AppearancePanelLayout(t("appearance_apply"), appearance != null, error, onApply = { commit() }) {
        Text(t("appearance_title"), style = MaterialTheme.typography.titleMedium)
        Text(t("appearance_help"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { commit(classic = true) }) { Text(t("appearance_classic")) }
            TextButton(onClick = { edit(SymbolAppearance.classic(context.document), emptySet(), null) }) { Text(t("appearance_reset_draft")) }
        }
        if (appearance == null) return@AppearancePanelLayout
        if (appearance.objects.any { it is SymbolObject.Opaque }) Text(t("appearance_opaque"))
        // AppearanceView's grid toggle, persisted as APPEARANCE_SHOW_GRID.
        val preferenceAccess = LocalEditorPreferences.current
        var localGrid by remember { mutableStateOf(true) }
        val showGrid = preferenceAccess?.preferences?.appearanceShowGrid ?: localGrid
        Row(Modifier.fillMaxWidth().toggleable(showGrid, role = Role.Switch) { value ->
            if (preferenceAccess != null) preferenceAccess.onChange(preferenceAccess.preferences.copy(appearanceShowGrid = value))
            else localGrid = value
        }.testTag("appearance-show-grid"), verticalAlignment = Alignment.CenterVertically) {
            Text(t("preferences_appearance_show_grid"), modifier = Modifier.weight(1f))
            Switch(showGrid, onCheckedChange = null)
        }
        SymbolPreview(appearance, context.document, context.simulation, selectedShapes, selectedMarker, multiSelect, showGrid, onSelection = { shapes, marker ->
            selectedShapes = shapes
            selectedMarker = marker
        }, onChange = { draft = it }, onMoveFinished = { checkpoint(it) })
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
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { selectMarker("anchor") }) { Text(t("appearance_anchor")) }
            appearance.objects.filterIsInstance<SymbolObject.Led>().forEach { led ->
                TextButton(onClick = { selectMarker(led.id) }) {
                    Text((if (selectedMarker == led.id) "✓ " else "") + t("appearance_led_state") + " (${led.position.x}, ${led.position.y})")
                }
            }
            appearance.objects.filterIsInstance<SymbolObject.Shape>().forEach { shape ->
                val selected = shape.id in selectedShapes
                Box(Modifier.heightIn(min = 48.dp).testTag("appearance-shape-${shape.id}")
                    .selectable(selected, role = Role.Button, onClick = { selectShape(shape.id) })
                    .padding(horizontal = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(if (selected) "✓ ${shape.id}" else shape.id)
                }
            }
        }
        shapes.singleOrNull()?.let { shape ->
            if (shape.tag == "image" && imagePicker != null) TextButton(
                onClick = { pickImage(shape.id) },
                modifier = Modifier.testTag("appearance-replace-image"),
            ) { Text(t("annotation_choose_image")) }
            ShapeProperties(shape, onApply = { attributes, text ->
                attempt {
                    val edited = appearance.editShape(shape.id, attributes, text)
                    edited.validate(context.document)
                    edit(edited)
                }
            }, onRemove = { edit(appearance.removeShape(shape.id), emptySet(), null) }, onLayer = { forward ->
                val objects = appearance.objects.toMutableList()
                val index = objects.indexOf(shape)
                val destination = (index + if (forward) 1 else -1).coerceIn(0, objects.lastIndex)
                if (index != destination) {
                    objects.removeAt(index)
                    objects.add(destination, shape)
                    edit(appearance.copy(objects = objects))
                }
            })
        }
        appearance.objects.filterIsInstance<SymbolObject.Led>().singleOrNull { it.id == selectedMarker }?.let { led ->
            LedProperties(led, onChange = { edited ->
                edit(appearance.copy(objects = appearance.objects.map { if (it == led) edited else it }))
            }, onRemove = {
                edit(appearance.copy(objects = appearance.objects - led), emptySet(), null)
            })
        }
        Text(t("appearance_ports"), style = MaterialTheme.typography.titleSmall)
        for (port in appearance.ports) {
            val pin = context.document.components.single { it.id == port.pinId }
            CoordinateEditor(pin.label.ifBlank { pin.id }, port.position, selectedMarker == "port-${pin.id}", onSelect = { selectMarker("port-${pin.id}") }) { point ->
                edit(appearance.movePort(pin.id, point))
            }
        }
        Text(t("appearance_anchor"), style = MaterialTheme.typography.titleSmall)
        CoordinateEditor(t("appearance_anchor"), appearance.anchor.position, selectedMarker == "anchor", onSelect = { selectMarker("anchor") }) {
            edit(appearance.copy(anchor = appearance.anchor.copy(position = it)))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (facing in listOf("east", "north", "west", "south")) {
                TextButton(onClick = { edit(appearance.copy(anchor = appearance.anchor.copy(facing = facing))) }) {
                    Text((if (appearance.anchor.facing == facing) "✓ " else "") + t("appearance_$facing"))
                }
            }
        }
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
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun LedProperties(led: SymbolObject.Led, onChange: (SymbolObject.Led) -> Unit, onRemove: () -> Unit) {
    val language = LocalAppLanguage.current
    var x by remember(led) { mutableStateOf(led.position.x.toString()) }
    var y by remember(led) { mutableStateOf(led.position.y.toString()) }
    var radius by remember(led) { mutableStateOf(led.radius.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(x, { x = it }, Modifier.weight(1f), label = { Text("X") }, singleLine = true)
        OutlinedTextField(y, { y = it }, Modifier.weight(1f), label = { Text("Y") }, singleLine = true)
        OutlinedTextField(radius, { radius = it }, Modifier.weight(1f),
            label = { Text(message("appearance_led_radius").resolve(language)) }, singleLine = true)
    }
    val point = x.toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }?.let { px ->
        y.toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }?.let { py -> GridPoint(px, py) }
    }
    val size = radius.toIntOrNull()?.takeIf { it in 1..20 }
    Row {
        Button(enabled = point != null && size != null, onClick = {
            if (point != null && size != null) onChange(led.copy(position = point, radius = size))
        }) { Text(message("appearance_update_object").resolve(language)) }
        TextButton(onClick = onRemove) { Text(message("appearance_remove_object").resolve(language)) }
    }
}

@Composable
private fun ShapeProperties(shape: SymbolObject.Shape, onApply: (Map<String, String>, String) -> Unit, onRemove: () -> Unit, onLayer: (Boolean) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String) = message(key).resolve(language)
    var attributes by remember(shape) { mutableStateOf(shape.attributes) }
    var text by remember(shape) { mutableStateOf(shape.text) }
    val initialPoints = remember(shape) { AppearanceEdits.controlPoints(shape) }
    var points by remember(shape) {
        mutableStateOf(initialPoints?.map { it.x.toString() to it.y.toString() }.orEmpty())
    }
    val geometry = if (initialPoints != null) emptyList() else when (shape.tag) {
        "rect" -> listOf("x", "y", "width", "height", "rx")
        "image" -> listOf("x", "y", "width", "height")
        "ellipse" -> listOf("cx", "cy", "rx", "ry")
        "line" -> listOf("x1", "y1", "x2", "y2")
        "text" -> listOf("x", "y", "font-family", "font-size")
        "path" -> listOf("d")
        else -> listOf("points")
    }
    val parsedPoints = points.map { (x, y) ->
        val px = x.toIntOrNull()
        val py = y.toIntOrNull()
        if (px != null && py != null && px in -1_000_000..1_000_000 && py in -1_000_000..1_000_000) GridPoint(px, py) else null
    }
    if (initialPoints != null) {
        val polygon = shape.tag == "polygon"
        val curveLabels = listOf("appearance_curve_start", "appearance_curve_control", "appearance_curve_end")
        for ((index, point) in points.withIndex()) {
            val label = if (polygon) message("appearance_vertex", "index" to index + 1).resolve(language) else t(curveLabels[index])
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun update(value: Pair<String, String>) {
                    points = points.toMutableList().also { it[index] = value }
                }
                OutlinedTextField(point.first, { update(it to point.second) }, Modifier.weight(1f), label = { Text("$label X") },
                    singleLine = true, isError = parsedPoints[index] == null)
                OutlinedTextField(point.second, { update(point.first to it) }, Modifier.weight(1f), label = { Text("$label Y") },
                    singleLine = true, isError = parsedPoints[index] == null)
            }
            if (polygon && points.size > 3) {
                TextButton(onClick = { points = points.toMutableList().also { it.removeAt(index) } }) {
                    Text(message("appearance_remove_vertex", "index" to index + 1).resolve(language))
                }
            }
        }
        if (polygon) {
            TextButton(onClick = { points = points + points.last() }) { Text(t("appearance_add_vertex")) }
        }
    }
    val keys = geometry + when (shape.tag) {
        "image" -> emptyList()
        "text" -> listOf("fill", "fill-opacity")
        else -> listOf("fill", "fill-opacity", "stroke", "stroke-opacity", "stroke-width")
    }
    val defaults = mapOf("rx" to "0", "ry" to "0", "stroke-width" to "1", "fill" to SymbolVisuals.defaultFill(shape.tag), "stroke" to SymbolVisuals.defaultStroke(shape.tag), "fill-opacity" to "1", "stroke-opacity" to "1", "font-family" to "SansSerif", "font-size" to "12")
    for (pair in keys.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (key in pair) Column(Modifier.weight(1f)) {
            val raw = attributes[key] ?: defaults[key].orEmpty()
            val paintField = key == "fill" || key == "stroke"
            if (paintField) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}
                .toggleable(value = raw != "none", role = Role.Checkbox, onValueChange = { enabled -> attributes = attributes + (key to if (enabled) "#000000" else "none") }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(raw != "none", onCheckedChange = null)
                Text(t(if (key == "fill") "appearance_fill_enabled" else "appearance_stroke_enabled"))
            }
            OutlinedTextField(if (paintField && raw == "none") "" else raw, { value ->
                attributes = attributes + (key to value)
                if (key == "rx" && shape.tag == "rect") attributes = attributes + ("ry" to value)
            }, Modifier.fillMaxWidth().testTag("appearance-object-$key"), enabled = !paintField || raw != "none", label = { Text(t("appearance_property_" + key.replace('-', '_'))) }, singleLine = true)
        }
    }
    if (shape.tag == "image") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (scale in listOf("fit", "cover", "stretch")) {
                val selected = (attributes["scale"] ?: "fit") == scale
                FilterChip(selected = selected, onClick = { attributes = attributes + ("scale" to scale) },
                    label = { Text(t("annotation_$scale")) })
            }
        }
    }
    if (shape.tag == "text") {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(t("annotation_text")) })
        Row {
            TextButton(onClick = { attributes = attributes + ("font-weight" to if (attributes["font-weight"] == "bold") "normal" else "bold") }) { Text((if (attributes["font-weight"] == "bold") "✓ " else "") + t("appearance_bold")) }
            TextButton(onClick = { attributes = attributes + ("font-style" to if (attributes["font-style"] == "italic") "normal" else "italic") }) { Text((if (attributes["font-style"] == "italic") "✓ " else "") + t("appearance_italic")) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Button(enabled = parsedPoints.none { it == null }, onClick = {
            val geometryUpdates = if (initialPoints == null) emptyMap()
                else AppearanceEdits.controlPointAttributes(shape.tag, parsedPoints.filterNotNull())
            onApply(attributes + geometryUpdates, text)
        }) { Text(t("appearance_update_object")) }
        TextButton(onClick = onRemove) { Text(t("appearance_remove_object")) }
        TextButton(onClick = { onLayer(false) }) { Text(t("annotation_backward")) }
        TextButton(onClick = { onLayer(true) }) { Text(t("annotation_forward")) }
    }
}

@Composable
private fun CoordinateEditor(label: String, position: GridPoint, selected: Boolean, onSelect: () -> Unit, onChange: (GridPoint) -> Unit) {
    var x by remember(position) { mutableStateOf(position.x.toString()) }
    var y by remember(position) { mutableStateOf(position.y.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) { Text((if (selected) "✓ " else "") + label) }
        OutlinedTextField(x, { x = it; val value = it.toIntOrNull(); if (value != null && value in -1_000_000..1_000_000) onChange(GridPoint(value, position.y)) }, Modifier.weight(1f), label = { Text("X") }, singleLine = true)
        OutlinedTextField(y, { y = it; val value = it.toIntOrNull(); if (value != null && value in -1_000_000..1_000_000) onChange(GridPoint(position.x, value)) }, Modifier.weight(1f), label = { Text("Y") }, singleLine = true)
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
    Canvas(Modifier.fillMaxWidth().height(260.dp).background(previewBackground).border(1.dp, Color.Gray)
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
