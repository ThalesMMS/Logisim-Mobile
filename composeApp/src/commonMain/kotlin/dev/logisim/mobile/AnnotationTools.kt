package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import dev.logisim.mobile.core.localization.message
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
fun AnnotationEditorPanel(context: EditorToolContext, imagePicker: AnnotationImagePicker? = null) {
    val language = LocalAppLanguage.current
    fun t(key: String) = message(key).resolve(language)
    val requestIdentity = remember(context.project) { Any() }
    val latestRequestIdentity by rememberUpdatedState(requestIdentity)
    val selected = context.selectedComponents.singleOrNull()?.takeIf(AnnotationLibrary::isAnnotation)
    var text by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("text").orEmpty()) }
    var source by remember(context.document, selected?.id, selected?.attributes) { mutableStateOf(selected?.attributes?.get("image").orEmpty()) }
    var x by remember(context.document, selected?.id, selected?.position) { mutableStateOf((selected?.position?.x ?: 100).toString()) }
    var y by remember(context.document, selected?.id, selected?.position) { mutableStateOf((selected?.position?.y ?: 100).toString()) }
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
    var error by remember { mutableStateOf<String?>(null) }
    val selectedIds = context.selectedComponents.map { it.id }.toSet()
    fun apply(action: () -> CircuitDocument) {
        try {
            context.applyDocument(action())
            error = null
            context.showNotice(message("annotation_saved"))
        } catch (failure: IllegalArgumentException) {
            error = (failure as? dev.logisim.mobile.core.localization.LocalizedArgumentException)?.text?.resolve(language) ?: failure.message ?: t("annotation_failed")
        }
    }
    fun position() = GridPoint(requireNotNull(x.toIntOrNull()) { t("annotation_invalid_x") }, requireNotNull(y.toIntOrNull()) { t("annotation_invalid_y") })

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("annotation_heading"), style = MaterialTheme.typography.titleMedium)
        Text(t("annotation_help"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x, { x = it }, label = { Text("X") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(y, { y = it }, label = { Text("Y") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(text, { text = it }, label = { Text(t("annotation_text")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(font, { font = it }, label = { Text(t("annotation_font")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(color, { color = it }, label = { Text(t("annotation_color")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((value, label) in listOf("left" to "annotation_start", "center" to "annotation_center", "right" to "annotation_end")) {
                TextButton(onClick = { horizontal = value }) { Text((if (horizontal == value) "✓ " else "") + t(label)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((value, label) in listOf("top" to "annotation_top", "center" to "annotation_center", "base" to "annotation_baseline", "bottom" to "annotation_bottom")) {
                TextButton(onClick = { vertical = value }) { Text((if (vertical == value) "✓ " else "") + t(label)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply {
                val inserted = AnnotationEdits.insertText(context.document, annotationId(context.document), position(), text,
                    requireNotNull(AnnotationFont.parse(font)) { t("annotation_invalid_font") }, storedColor())
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
            }.onFailure { error = (it as? dev.logisim.mobile.core.localization.LocalizedArgumentException)?.text?.resolve(language) ?: it.message ?: t("annotation_image_failed") } } }) { Text(t("annotation_choose_image")) }
        }
        if (imagePicker == null) Text(t("annotation_picker_unavailable"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(width, { width = it }, label = { Text(t("annotation_width")) }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(height, { height = it }, label = { Text(t("annotation_height")) }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((value, label) in listOf("fit" to t("annotation_fit"), "cover" to t("annotation_cover"), "stretch" to t("annotation_stretch"))) {
                TextButton(onClick = { scale = value }) { Text(if (scale == value) "✓ $label" else label) }
            }
        }
        OutlinedTextField(attribution, { attribution = it }, label = { Text(t("annotation_attribution")) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply {
                AnnotationEdits.insertImage(context.document, annotationId(context.document), position(), validateAnnotationImage(EmbeddedImage.fromDataUri(source)),
                    requireNotNull(width.toIntOrNull()) { t("annotation_invalid_width") }, requireNotNull(height.toIntOrNull()) { t("annotation_invalid_height") }, scale, attribution)
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
