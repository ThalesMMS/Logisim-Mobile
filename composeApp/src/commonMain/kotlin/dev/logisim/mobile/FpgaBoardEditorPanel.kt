package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPicture
import dev.logisim.mobile.core.FpgaBoardWriter
import dev.logisim.mobile.core.FpgaPull
import dev.logisim.mobile.core.FpgaRectangle
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FpgaBoardEditorPanel(
    board: FpgaBoardDefinition?,
    assets: DocumentAssetActions,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberLocalizer()
    val scope = rememberCoroutineScope()
    var draft by remember(board) { mutableStateOf(FpgaBoardEditorDraft.from(board)) }
    var replacement by remember(board) { mutableStateOf<BoardImage?>(null) }
    val originalImage by produceState<BoardImage?>(null, board) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val original = board?.originalXml?.let(FpgaBoardPicture::read) ?: return@runCatching null
                BoardImage(original, decodeEmbeddedAnnotationImage(original.imageBytes()))
            }.getOrNull()
        }
    }
    val image = replacement ?: originalImage
    var selected by remember(board) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }

    fun imagePicker() {
        busy = true
        errors = emptyList()
        try {
            assets.open(listOf("image/png", "image/jpeg"), EmbeddedImage.MAX_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> scope.launch {
                        try {
                            replacement = withContext(Dispatchers.Default) { replacementImage(result.asset) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            errors = listOf(error.localizedDescription(message("fpga_editor_invalid_image")))
                        } finally {
                            busy = false
                        }
                    }
                    is AssetOpenResult.Failure -> {
                        errors = listOf(result.notice)
                        busy = false
                    }
                    AssetOpenResult.Cancelled -> busy = false
                }
            }
        } catch (error: Exception) {
            errors = listOf(error.localizedDescription(message("fpga_editor_invalid_image")))
            busy = false
        }
    }

    fun save() {
        val snapshot = draft
        busy = true
        errors = emptyList()
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { FpgaBoardWriter.write(snapshot.board(), image?.picture) }
                when (result) {
                    is FpgaBoardImportResult.Failure -> {
                        errors = result.diagnosticNotices
                        busy = false
                    }
                    is FpgaBoardImportResult.Success -> {
                        val xml = requireNotNull(result.board.originalXml)
                        val asset = DocumentAsset("${result.board.name}.xml", "application/xml", xml.encodeToByteArray())
                        assets.create(asset) { saved ->
                            busy = false
                            when (saved) {
                                AssetActionResult.Completed -> onSaved(xml)
                                is AssetActionResult.Failure -> errors = listOf(saved.notice)
                                AssetActionResult.Cancelled -> Unit
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                busy = false
                errors = listOf(error.localizedDescription(message("fpga_editor_save_failed")))
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text(if (board == null) "fpga_editor_new_board" else "fpga_editor_edit_board"),
            style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ::save) { Text(text("fpga_editor_save_board")) }
            TextButton(enabled = !busy, onClick = onCancel) { Text(text("action_cancel")) }
        }
        errors.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BoardField(text(FpgaBoardField.NAME.label), draft.fields[FpgaBoardField.NAME].orEmpty()) {
                    draft = draft.copy(fields = draft.fields + (FpgaBoardField.NAME to it))
                }
            }
            item {
                OutlinedButton(enabled = !busy, onClick = ::imagePicker, modifier = Modifier.fillMaxWidth()) {
                    Text(text("fpga_editor_choose_image"))
                }
            }
            image?.let { currentImage ->
                item { BoardPreview(currentImage, draft.resources.map { it.rectangle }, selected, text("fpga_editor_picture")) }
            }
            item {
                Text(text("fpga_editor_device"), style = MaterialTheme.typography.titleMedium)
                FpgaChoice(text("fpga_editor_vendor"), draft.vendor.name,
                    FpgaVendor.entries.filterNot { it == FpgaVendor.UNKNOWN }.map { it to it.name }) {
                    draft = draft.copy(vendor = it)
                }
            }
            for (field in deviceFields) item(key = field.name) {
                BoardField(text(field.label), draft.fields[field].orEmpty()) {
                    draft = draft.copy(fields = draft.fields + (field to it))
                }
            }
            item {
                BoardCheck(text("fpga_editor_usbtmc"), draft.usbTmc) { draft = draft.copy(usbTmc = it) }
                PullChoice(text("fpga_editor_unused_pull"), draft.unusedPull, text) { draft = draft.copy(unusedPull = it) }
                HorizontalDivider()
                Text(text("fpga_editor_clock"), style = MaterialTheme.typography.titleMedium)
            }
            for (field in clockFields) item(key = field.name) {
                BoardField(text(field.label), draft.fields[field].orEmpty()) {
                    draft = draft.copy(fields = draft.fields + (field to it))
                }
            }
            item {
                PullChoice(text("fpga_editor_pull"), draft.clockPull, text) { draft = draft.copy(clockPull = it) }
                HorizontalDivider()
                Text(text("fpga_editor_resources"), style = MaterialTheme.typography.titleMedium)
                if (draft.resources.isNotEmpty()) {
                    val labels = draft.resources.mapIndexed { index, resource -> index to "${index + 1}. ${resource.displayLabel}" }
                    FpgaChoice(text("fpga_editor_select_resource"), labels.getOrNull(selected)?.second.orEmpty(), labels) { selected = it }
                }
                OutlinedButton(onClick = {
                    selected = draft.resources.size
                    draft = draft.copy(resources = draft.resources + FpgaResourceEditorDraft())
                }, modifier = Modifier.fillMaxWidth()) { Text(text("fpga_editor_add_resource")) }
            }
            draft.resources.getOrNull(selected)?.let { resource ->
                item(key = "resource-$selected") {
                    ResourceFields(resource, text, onChange = { updated ->
                        draft = draft.copy(resources = draft.resources.mapIndexed { index, current ->
                            if (index == selected) updated else current
                        })
                    }, onRemove = {
                        val remaining = draft.resources.filterIndexed { index, _ -> index != selected }
                        selected = selected.coerceAtMost(remaining.lastIndex).coerceAtLeast(0)
                        draft = draft.copy(resources = remaining)
                    })
                }
            }
        }
    }
}

@Composable
private fun ResourceFields(resource: FpgaResourceEditorDraft, text: Localizer,
    onChange: (FpgaResourceEditorDraft) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FpgaChoice(text("fpga_editor_resource_type"), resource.type, FpgaBoardWriter.resourceTypes.map { it to it }) {
            val values = if (FpgaResourceEditorDraft.displayAttribute(it) != FpgaResourceEditorDraft.displayAttribute(resource.type))
                resource.fields - FpgaResourceField.DISPLAY else resource.fields
            onChange(resource.copy(type = it, fields = values))
        }
        for (field in FpgaResourceField.entries) {
            if (field == FpgaResourceField.DISPLAY && FpgaResourceEditorDraft.displayAttribute(resource.type) == null) continue
            BoardField(text(field.label), resource.fields[field].orEmpty()) {
                onChange(resource.copy(fields = resource.fields + (field to it)))
            }
        }
        PullChoice(text("fpga_editor_pull"), resource.pull, text) { onChange(resource.copy(pull = it)) }
        BoardCheck(text("fpga_editor_active_low"), resource.activeLow) { onChange(resource.copy(activeLow = it)) }
        TextButton(onClick = onRemove) { Text(text("fpga_editor_remove_resource")) }
    }
}

@Composable
private fun BoardField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun BoardCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange)
        Text(label)
    }
}

@Composable
private fun PullChoice(label: String, current: FpgaPull, text: Localizer, onChange: (FpgaPull) -> Unit) {
    val choices = listOf(FpgaPull.FLOAT to text("fpga_editor_pull_float"), FpgaPull.UP to text("fpga_editor_pull_up"),
        FpgaPull.DOWN to text("fpga_editor_pull_down"))
    FpgaChoice(label, choices.single { it.first == current }.second, choices, onChoose = onChange)
}

private data class BoardImage(val picture: FpgaBoardPicture, val bitmap: ImageBitmap)

@Composable
private fun BoardPreview(image: BoardImage, resources: List<FpgaRectangle?>, selected: Int, description: String) {
    val frame = image.picture
    // Evolution BoardManipulator: defined areas, and the highlighted area being edited.
    val colors = LocalEditorPreferences.current?.preferences?.effectiveColors(LocalEditorDarkTheme.current).orEmpty()
    val selectedColor = colors.fpgaColor(EditorColor.FPGA_DEFINE_HIGHLIGHT)
    val otherColor = colors.fpgaColor(EditorColor.FPGA_DEFINE)
    Canvas(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height)
        .semantics { contentDescription = description }) {
        drawImage(image.bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val scaleX = size.width / frame.width
        val scaleY = size.height / frame.height
        resources.forEachIndexed { index, box ->
            if (box != null && box.width > 0 && box.height > 0) {
                drawRect(if (index == selected) selectedColor else otherColor.copy(alpha = 0.65f),
                    Offset(box.x * scaleX, box.y * scaleY), Size(box.width * scaleX, box.height * scaleY),
                    style = Stroke(width = (if (index == selected) 3 else 1).dp.toPx()))
            }
        }
    }
}

private fun replacementImage(asset: DocumentAsset): BoardImage {
    val bytes = asset.bytes
    EmbeddedImage.fromBytes(bytes, asset.mimeType)
    val input = decodeEmbeddedAnnotationImage(bytes)
    val width = FpgaBoardPicture.MAPPING_WIDTH
    val height = FpgaBoardPicture.MAPPING_HEIGHT
    val bitmap = renderDrawingBitmap(VisualBounds(0.0, 0.0, width.toDouble(), height.toDouble()),
        VisualExportOptions(margin = 0.0)) {
        drawImage(input, dstSize = IntSize(width, height))
    }
    return BoardImage(FpgaBoardPicture.fromEncodedImage(width, height, encodeVisualJpeg(bitmap)), bitmap)
}

private val deviceFields = listOf(FpgaBoardField.FAMILY, FpgaBoardField.PART, FpgaBoardField.PACKAGE,
    FpgaBoardField.SPEED, FpgaBoardField.JTAG, FpgaBoardField.FLASH_NAME, FpgaBoardField.FLASH_POSITION)
private val clockFields = listOf(FpgaBoardField.CLOCK_PIN, FpgaBoardField.FREQUENCY, FpgaBoardField.CLOCK_STANDARD)
