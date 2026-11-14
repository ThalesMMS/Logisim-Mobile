package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.logisim.mobile.core.AlignmentAxis
import dev.logisim.mobile.core.AlignmentEdge
import dev.logisim.mobile.core.AnnotationEdits
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.AnnotationVisuals
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LayerMove
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.localization.LocalizedFailure
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireNotNullLocalized
import dev.logisim.mobile.localization.LocalAppLanguage

fun interface AnnotationImagePicker {
    fun pick(onResult: (Result<EmbeddedImage>) -> Unit)
}

fun DocumentAssetActions.annotationImagePicker(): AnnotationImagePicker = AnnotationImagePicker { onResult ->
    open(listOf("image/png", "image/jpeg", "image/gif"), EmbeddedImage.MAX_BYTES) { result ->
        when (result) {
            is AssetOpenResult.Opened -> onResult(runCatching { validateAnnotationImage(EmbeddedImage.fromBytes(result.asset.bytes, result.asset.mimeType)) })
            is AssetOpenResult.Failure -> onResult(Result.failure(dev.logisim.mobile.core.localization.LocalizedArgumentException(result.notice)))
            AssetOpenResult.Cancelled -> Unit
        }
    }
}

fun annotationEditorTool(imagePicker: AnnotationImagePicker? = null): EditorTool =
    EditorTool("annotations", "Text and images", titleText = message("annotation_title")) { context -> AnnotationEditorPanel(context, imagePicker) }

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AnnotationEditorPanel(context: EditorToolContext, imagePicker: AnnotationImagePicker? = null) {
    val language = LocalAppLanguage.current
    fun t(key: String) = message(key).resolve(language)
    val requestIdentity = remember(context.project) { Any() }
    val latestRequestIdentity by rememberUpdatedState(requestIdentity)
    val selected = context.selectedComponents.singleOrNull()?.takeIf(AnnotationLibrary::isAnnotation)
    var text by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("text").orEmpty()) }
    var source by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("image").orEmpty()) }
    // New text and images start at the centre of the circuit in view, so they never land off-screen (C39). Read once,
    // without observing the viewport, so panning does not recompose the panel.
    val centre = remember(context.document, selected?.id) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { context.visibleCenter() }
    }
    val start = centre ?: GridPoint(100, 100)
    var x by remember(context.document, selected?.id, selected?.position) { mutableStateOf((selected?.position?.x ?: start.x).toString()) }
    var y by remember(context.document, selected?.id, selected?.position) { mutableStateOf((selected?.position?.y ?: start.y).toString()) }
    var font by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("font") ?: "SansSerif plain 12") }
    // TextColorAttribute: the dark theme shows and takes the color the canvas draws; the file keeps the light one.
    val darkTheme = LocalEditorDarkTheme.current
    val selectedColor = selected?.attributes?.get("color") ?: "#000000"
    var color by remember(context.document, selected?.id, selected?.attributes, darkTheme) { mutableStateOf(ThemeTextColor.display(selectedColor, darkTheme)) }
    fun storedColor() = ThemeTextColor.store(color, selectedColor, darkTheme)
    var horizontal by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("halign") ?: "left") }
    var vertical by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("valign") ?: "top") }
    var width by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("width") ?: "120") }
    var height by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("height") ?: "80") }
    var scale by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("scale") ?: "fit") }
    var attribution by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("attribution").orEmpty()) }
    var error by remember { mutableStateOf<LocalizedText?>(null) }
    var pickingColor by remember { mutableStateOf(false) }
    val selectedIds = context.selectedComponents.map { it.id }.toSet()
    fun apply(action: () -> CircuitDocument) {
        try {
            context.applyDocument(action())
            error = null
            context.showNotice(message("annotation_saved"))
        } catch (failure: IllegalArgumentException) {
            // Only catalog text: an exception's own message is neither translated nor written for users.
            error = (failure as? LocalizedFailure)?.text ?: message("annotation_failed")
        }
    }
    fun position() = GridPoint(requireNotNullLocalized(x.toIntOrNull()) { message("annotation_invalid_x") },
        requireNotNullLocalized(y.toIntOrNull()) { message("annotation_invalid_y") })

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("annotation_heading"), style = MaterialTheme.typography.titleMedium)
        Text(t("annotation_help"))
        // Scrolled into view and announced when an Insert or Update below fails.
        AccessibleEditorNotice(error, Modifier.testTag("annotation-error"), isError = true)
        // X and Y start at the centre of the view; the canvas Text tool places text where the user taps.
        if (selected == null) Text(t(if (centre != null) "annotation_position_view_hint" else "annotation_position_hint"),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SteppedNumberField("X", x, { x = it }, 10, Modifier.weight(1f).testTag("annotation-x"), isError = x.toIntOrNull() == null)
            SteppedNumberField("Y", y, { y = it }, 10, Modifier.weight(1f).testTag("annotation-y"), isError = y.toIntOrNull() == null)
        }
        OutlinedTextField(text, { text = it }, label = { Text(t("annotation_text")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        // Evolution's font chooser and color chooser instead of typing "SansSerif bold 16" and "#FF0000".
        Text(t("annotation_font"), style = MaterialTheme.typography.labelLarge)
        FontChoiceField(font, { font = it }, tag = "annotation-font")
        // The Text color attribute only holds `#rrggbb[aa]` (AnnotationLibrary's schema, which the simulation checks), so a
        // named color such as "red" never reaches this panel.
        val shownColor = ColorPickerModel.parse(color)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("annotation_text_color"), modifier = Modifier.weight(1f))
            Text(color, style = MaterialTheme.typography.bodySmall, color = ChromeColors.Muted, modifier = Modifier.padding(end = 4.dp))
            ColorSwatchButton(t("annotation_text_color"), shownColor ?: ColorPickerModel(0), { pickingColor = true },
                Modifier.testTag("annotation-color"))
        }
        if (pickingColor) ColorPickerDialog(
            title = t("annotation_text_color"),
            initial = shownColor ?: ColorPickerModel(0),
            default = ColorPickerModel.parse(ThemeTextColor.display("#000000", darkTheme)),
            alphaEnabled = true,
            onPick = { color = it.hex },
            onDismiss = { pickingColor = false },
        )
        Text(t("annotation_alignment_horizontal"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            for ((value, label) in listOf("left" to "annotation_start", "center" to "annotation_center", "right" to "annotation_end")) {
                RadioItem(t(label), horizontal == value, Modifier.testTag("annotation-halign-$value")) { horizontal = value }
            }
        }
        Text(t("annotation_alignment_vertical"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            for ((value, label) in listOf("top" to "annotation_top", "center" to "annotation_center", "base" to "annotation_baseline", "bottom" to "annotation_bottom")) {
                RadioItem(t(label), vertical == value, Modifier.testTag("annotation-valign-$value")) { vertical = value }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply {
                val inserted = AnnotationEdits.insertText(context.document, annotationId(context.document), position(), text,
                    requireNotNullLocalized(AnnotationFont.parse(font)) { message("annotation_invalid_font") }, storedColor())
                AnnotationEdits.edit(inserted, inserted.components.last().id, mapOf("halign" to horizontal, "valign" to vertical))
            } }) { Text(t("annotation_insert_text")) }
            if (selected?.kind == AnnotationLibrary.Text) TextButton(onClick = { apply {
                val edited = AnnotationEdits.edit(context.document, selected.id, mapOf("text" to text, "font" to font, "color" to storedColor(), "halign" to horizontal, "valign" to vertical))
                edited.copy(components = edited.components.map { if (it.id == selected.id) it.copy(position = position()) else it })
            } }) { Text(t("annotation_update_text")) }
        }
        Text(t("annotation_image"), style = MaterialTheme.typography.titleSmall)
        imagePicker?.let { picker ->
            Button(onClick = { val target = requestIdentity; picker.pick { result -> if (latestRequestIdentity === target) result.onSuccess { image ->
                source = image.dataUri
                width = image.width.toString()
                height = image.height.toString()
                error = null
            }.onFailure { error = (it as? LocalizedFailure)?.text ?: message("annotation_image_failed") } } }) { Text(t("annotation_choose_image")) }
        }
        if (imagePicker == null) Text(t("annotation_picker_unavailable"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SteppedNumberField(t("annotation_width"), width, { width = it }, 10, Modifier.weight(1f).testTag("annotation-width"),
                range = 1..EmbeddedImage.MAX_DIMENSION, isError = width.toIntOrNull() == null)
            SteppedNumberField(t("annotation_height"), height, { height = it }, 10, Modifier.weight(1f).testTag("annotation-height"),
                range = 1..EmbeddedImage.MAX_DIMENSION, isError = height.toIntOrNull() == null)
        }
        Text(t("annotation_image_scale"), style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            for ((value, label) in listOf("fit" to t("annotation_fit"), "cover" to t("annotation_cover"), "stretch" to t("annotation_stretch"))) {
                RadioItem(label, scale == value, Modifier.testTag("annotation-scale-$value")) { scale = value }
            }
        }
        OutlinedTextField(attribution, { attribution = it }, label = { Text(t("annotation_attribution")) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply {
                AnnotationEdits.insertImage(context.document, annotationId(context.document), position(), validateAnnotationImage(EmbeddedImage.fromDataUri(source)),
                    requireNotNullLocalized(width.toIntOrNull()) { message("annotation_invalid_width") },
                    requireNotNullLocalized(height.toIntOrNull()) { message("annotation_invalid_height") }, scale, attribution)
            } }) { Text(t("annotation_embed_image")) }
            if (selected?.kind == AnnotationLibrary.Image) TextButton(onClick = { apply {
                validateAnnotationImage(EmbeddedImage.fromDataUri(source))
                val edited = AnnotationEdits.edit(context.document, selected.id, mapOf("image" to source, "width" to width, "height" to height, "scale" to scale, "attribution" to attribution))
                edited.copy(components = edited.components.map { if (it.id == selected.id) it.copy(position = position()) else it })
            } }) { Text(t("annotation_update_image")) }
        }
        selected?.let { AnnotationPreview(it) }
        Text(t("annotation_organize"), style = MaterialTheme.typography.titleSmall)
        for ((axis, label) in listOf(AlignmentAxis.HORIZONTAL to t("annotation_horizontal"), AlignmentAxis.VERTICAL to t("annotation_vertical"))) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(label, modifier = Modifier.padding(top = 14.dp))
                for ((edge, title) in listOf(AlignmentEdge.START to t("annotation_start"), AlignmentEdge.CENTER to t("annotation_center"), AlignmentEdge.END to t("annotation_end"))) {
                    TextButton(enabled = selectedIds.size >= 2, onClick = { apply { AnnotationEdits.align(context.document, selectedIds, axis, edge) } }) { Text(title) }
                }
                TextButton(enabled = selectedIds.size >= 3, onClick = { apply { AnnotationEdits.distribute(context.document, selectedIds, axis) } }) { Text(t("annotation_distribute")) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((move, label) in listOf(LayerMove.BACK to t("annotation_back"), LayerMove.BACKWARD to t("annotation_backward"), LayerMove.FORWARD to t("annotation_forward"), LayerMove.FRONT to t("annotation_front"))) {
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { apply { AnnotationEdits.reorder(context.document, selectedIds, move) } }) { Text(label) }
            }
        }
        Row {
            TextButton(enabled = selectedIds.size >= 2, onClick = { apply { AnnotationEdits.group(context.document, selectedIds, "group-${selectedIds.sorted().joinToString("-")}") } }) { Text(t("annotation_group")) }
            TextButton(enabled = selectedIds.isNotEmpty(), onClick = { apply { AnnotationEdits.group(context.document, selectedIds, null) } }) { Text(t("annotation_ungroup")) }
        }
    }
}

@Composable
private fun AnnotationPreview(component: dev.logisim.mobile.core.CircuitComponent) {
    val scene = remember(component) { runCatching { VisualScene(listOf(AnnotationVisuals.item(component))) }.getOrNull() } ?: return
    val measurer = rememberTextMeasurer()
    val decoder = rememberAnnotationImageCache()
    val previewDescription = message("annotation_preview").resolve(LocalAppLanguage.current)
    val imageFailure = remember(scene) {
        runCatching { scene.items.single().primitives.filterIsInstance<dev.logisim.mobile.core.VisualPrimitive.Image>().forEach { decoder.decode(it) } }.exceptionOrNull()
    }
    if (imageFailure != null) {
        Text(message("annotation_image_decode").resolve(LocalAppLanguage.current), color = MaterialTheme.colorScheme.error)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(Color.White).border(1.dp, Color.LightGray).semantics { contentDescription = previewDescription }) {
        val bounds = scene.items.single().bounds
        val zoom = minOf((size.width - 24) / maxOf(1f, bounds.width.toFloat()), (size.height - 24) / maxOf(1f, bounds.height.toFloat())).coerceAtMost(3f)
        withTransform({ translate(size.width / 2, size.height / 2); scale(zoom, zoom, androidx.compose.ui.geometry.Offset.Zero); translate(-(bounds.left + bounds.width / 2).toFloat(), -(bounds.top + bounds.height / 2).toFloat()) }) {
            drawVisualScene(scene, measurer, decoder)
        }
    }
}

private fun annotationId(document: CircuitDocument): String {
    val used = document.components.map { it.id }.toSet()
    var number = 1
    while ("annotation-$number" in used) number++
    return "annotation-$number"
}
