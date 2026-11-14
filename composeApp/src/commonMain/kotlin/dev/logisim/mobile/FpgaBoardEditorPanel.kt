package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.EmbeddedImage
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FpgaBoardEditorPanel(
    session: FpgaBoardEditorSession,
    assets: DocumentAssetActions,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = rememberLocalizer()
    val scope = rememberCoroutineScope()
    val board = session.board
    val draft = session.draft
    val originalImage by produceState<BoardImage?>(null, board) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val original = board?.originalXml?.let(FpgaBoardPicture::read) ?: return@runCatching null
                BoardImage(original, decodeEmbeddedAnnotationImage(original.imageBytes()))
            }.getOrNull()
        }
    }
    val image = session.replacement ?: originalImage
    val selected = session.selected
    var busy by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }
    /** A rectangle drawn on the picture, waiting for its resource type. */
    var drawn by remember { mutableStateOf<FpgaRectangle?>(null) }
    val listState = rememberLazyListState()
    val pictureWidth = image?.picture?.width ?: FpgaBoardPicture.MAPPING_WIDTH
    val pictureHeight = image?.picture?.height ?: FpgaBoardPicture.MAPPING_HEIGHT
    // Live checks of the writer's rules; a value not entered yet is marked only after a Save attempt.
    val problems = remember(draft, pictureWidth, pictureHeight) { draft.validate(pictureWidth, pictureHeight) }
    val shown = problems.filter { session.showAllErrors || !it.blank }
    fun fieldError(field: Enum<*>, resource: Int? = null, pin: Int? = null): String? =
        shown.firstOrNull { it.field == field && it.resourceIndex == resource && (pin == null || it.pin == pin) }?.let { text(it.text) }
    val resourcesWithErrors = shown.mapNotNullTo(mutableSetOf()) { it.resourceIndex }
    // Item keys in list order, so Save can scroll to the field or resource with a problem.
    val keys = buildList {
        add("name"); add("image")
        if (image != null) add("preview")
        add("device"); deviceFields.forEach { add(it.name) }; add("device-options")
        clockFields.forEach { add(it.name) }; add("clock-options"); add("resources")
        if (draft.resources.getOrNull(selected) != null) add("resource")
    }
    fun reveal(problem: FpgaEditorFieldError) {
        problem.resourceIndex?.let { session.selected = it }
        val key = if (problem.resourceIndex != null) "resource" else when (val field = problem.field) {
            FpgaBoardField.NAME -> "name"
            in deviceFields -> (field as FpgaBoardField).name
            in clockFields -> (field as FpgaBoardField).name
            null -> "resources"
            else -> "clock-options"
        }
        scope.launch { keys.indexOf(key).takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) } }
    }

    fun imagePicker() {
        busy = true
        errors = emptyList()
        try {
            assets.open(listOf("image/png", "image/jpeg"), EmbeddedImage.MAX_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> scope.launch {
                        try {
                            session.replacement = withContext(Dispatchers.Default) { replacementImage(result.asset) }
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
        // Every problem is marked first, and the first one is brought into view; the writer stays the final check.
        val found = snapshot.validate(pictureWidth, pictureHeight)
        if (found.isNotEmpty()) {
            session.showAllErrors = true
            errors = listOf(LocalizedText("fpga_editor_save_blocked", emptyMap(), found.size.toLong()))
            reveal(found.firstOrNull { it.resourceIndex != null } ?: found.first())
            return
        }
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
                                AssetActionResult.Completed -> {
                                    // The saved board replaces the draft; the next edit starts from it.
                                    session.close()
                                    onSaved(xml)
                                }
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

    if (session.confirmingDiscard) {
        AppAlertDialog(onDismissRequest = { session.confirmingDiscard = false },
            title = { Text(text("fpga_editor_discard_title")) },
            text = { Text(text("fpga_editor_discard_text")) },
            confirmButton = {
                TextButton(onClick = { session.close() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("fpga_editor_discard"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { session.confirmingDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("fpga_editor_keep_editing"))
                }
            })
    }

    drawn?.let { rectangle ->
        // Evolution asks for the type of a region drawn on the board (IoComponentSelector).
        AppAlertDialog(onDismissRequest = { drawn = null },
            title = { Text(text("fpga_editor_new_resource_type")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FpgaBoardWriter.resourceTypes.forEach { type ->
                        TextButton(onClick = { session.addResource(rectangle, type); drawn = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fpga-editor-new-type-$type")) {
                            Text(text(FpgaLabels.resourceType(type)), Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { drawn = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(text("action_cancel")) }
            })
    }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text(if (board == null) "fpga_editor_new_board" else "fpga_editor_edit_board"),
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ::save, modifier = Modifier.heightIn(min = 48.dp)) { Text(text("fpga_editor_save_board")) }
            TextButton(enabled = !busy, onClick = session::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(text("action_cancel")) }
        }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            errors.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
            // After a Save attempt, the first problems are listed by the resource names of the menu below.
            if (session.showAllErrors) shown.take(4).forEach { problem ->
                val line = problem.resourceIndex?.let { index ->
                    draft.resources.getOrNull(index)?.let { text(message("fpga_editor_resource_error", "resource" to it.menuName(index), "detail" to problem.text)) }
                } ?: text(problem.text)
                TextButton(onClick = { reveal(problem) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(line, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "name") {
                BoardField(text(FpgaBoardField.NAME.label), draft.fields[FpgaBoardField.NAME].orEmpty(), fieldError(FpgaBoardField.NAME)) {
                    session.edit(draft.copy(fields = draft.fields + (FpgaBoardField.NAME to it)))
                }
            }
            item(key = "image") {
                OutlinedButton(enabled = !busy, onClick = ::imagePicker, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_editor_choose_image"))
                }
            }
            image?.let { currentImage ->
                item(key = "preview") {
                    BoardPreviewSection(currentImage, draft, selected, text,
                        onSelect = { session.selected = it },
                        onCreate = { drawn = it },
                        onChange = session::changeRectangle,
                        undo = session.rectangleUndo != null, onUndo = session::undoRectangle)
                }
            }
            item(key = "device") {
                Text(text("fpga_editor_device"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                val vendors = FpgaVendor.entries.filterNot { it == FpgaVendor.UNKNOWN }
                FpgaChoice(text("fpga_editor_vendor"), text(FpgaLabels.vendor(draft.vendor)),
                    vendors.map { it to text(FpgaLabels.vendor(it)) }, selectedValue = draft.vendor, testTag = "fpga-editor-vendor") {
                    session.edit(draft.copy(vendor = it))
                }
            }
            for (field in deviceFields) item(key = field.name) {
                BoardField(text(field.label), draft.fields[field].orEmpty(), fieldError(field), field.keyboardType()) {
                    session.edit(draft.copy(fields = draft.fields + (field to it)))
                }
            }
            item(key = "device-options") {
                BoardCheck(text("fpga_editor_usbtmc"), draft.usbTmc) { session.edit(draft.copy(usbTmc = it)) }
                PullChoice(text("fpga_editor_unused_pull"), draft.unusedPull, text) { session.edit(draft.copy(unusedPull = it)) }
                HorizontalDivider()
                Text(text("fpga_editor_clock"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            for (field in clockFields) item(key = field.name) {
                if (field == FpgaBoardField.FREQUENCY) {
                    // Typed in Hz, kHz or MHz as in Evolution's dialog; the board still stores whole hertz.
                    BoardField(text(field.label), draft.fields[field].orEmpty(), fieldError(field), field.keyboardType()) {
                        session.edit(draft.copy(fields = draft.fields + (field to it)))
                    }
                    FpgaSegmentedChoice(text("fpga_editor_frequency_unit"), draft.frequencyUnit,
                        FpgaFrequencyUnit.entries.map { it to it.symbol }) { session.edit(draft.copy(frequencyUnit = it)) }
                    draft.frequencyHz?.let { Text(text("fpga_editor_frequency_hz", "frequency" to it), style = MaterialTheme.typography.bodySmall) }
                } else {
                    BoardField(text(field.label), draft.fields[field].orEmpty(), fieldError(field), field.keyboardType()) {
                        session.edit(draft.copy(fields = draft.fields + (field to it)))
                    }
                }
            }
            item(key = "clock-options") {
                OptionChoice(text(FpgaBoardField.CLOCK_STANDARD.label), draft.fields[FpgaBoardField.CLOCK_STANDARD].orEmpty(),
                    FpgaBoardChoices.ioStandard(draft.fields[FpgaBoardField.CLOCK_STANDARD].orEmpty()), text, "fpga-editor-clock-standard") {
                    session.edit(draft.copy(fields = draft.fields + (FpgaBoardField.CLOCK_STANDARD to it)))
                }
                PullChoice(text("fpga_editor_pull"), draft.clockPull, text) { session.edit(draft.copy(clockPull = it)) }
            }
            item(key = "resources") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(text("fpga_editor_resources"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    shown.firstOrNull { it.resourceIndex == null && it.field == null }?.let {
                        Text(text(it.text), color = MaterialTheme.colorScheme.error)
                    }
                    if (draft.resources.isNotEmpty()) {
                        // Numbered from 1, as messages name them; a resource with problems says so in the menu.
                        val labels = draft.resources.mapIndexed { index, resource ->
                            val name = text(resource.menuName(index))
                            index to if (index in resourcesWithErrors) text("fpga_editor_resource_has_errors", "resource" to name) else name
                        }
                        FpgaChoice(text("fpga_editor_select_resource"), labels.getOrNull(selected)?.second.orEmpty(), labels,
                            selectedValue = selected, testTag = "fpga-editor-resource") { session.selected = it }
                    }
                    OutlinedButton(onClick = session::addResource, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_editor_add_resource"))
                    }
                    session.removed?.let {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(text("fpga_editor_resource_removed"), Modifier.weight(1f))
                                TextButton(onClick = session::undoRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(text("fpga_editor_undo"))
                                }
                            }
                        }
                    }
                }
            }
            draft.resources.getOrNull(selected)?.let { resource ->
                item(key = "resource") {
                    ResourceFields(resource, selected, text, { field, pin -> fieldError(field, selected, pin) },
                        onChange = session::editResource, onRemove = session::removeResource)
                }
            }
        }
    }
}

@Composable
private fun ResourceFields(
    resource: FpgaResourceEditorDraft,
    index: Int,
    text: Localizer,
    fieldError: (FpgaResourceField, Int?) -> String?,
    onChange: (FpgaResourceEditorDraft) -> Unit,
    onRemove: () -> Unit,
) {
    fun field(field: FpgaResourceField) = resource.fields[field].orEmpty()
    fun change(field: FpgaResourceField, value: String) = onChange(resource.copy(fields = resource.fields + (field to value)))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text(resource.menuName(index)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val typeChoices = FpgaBoardWriter.resourceTypes.map { it to text(FpgaLabels.resourceType(it)) }
        // A board file's own spelling (such as "LED") stays selected and is written back unchanged.
        val currentType = FpgaBoardWriter.resourceTypes.firstOrNull { it.equals(resource.type, ignoreCase = true) }
        FpgaChoice(text("fpga_editor_resource_type"), text(FpgaLabels.resourceType(resource.type)), typeChoices,
            selectedValue = currentType, testTag = "fpga-editor-type") { chosen ->
            if (!chosen.equals(resource.type, ignoreCase = true)) onChange(resource.withType(chosen))
        }
        BoardField(text(FpgaResourceField.LABEL.label), field(FpgaResourceField.LABEL), null) { change(FpgaResourceField.LABEL, it) }
        // The exact position, as four whole numbers; the picture above places it by touch.
        Text(text("fpga_editor_exact_position"), style = MaterialTheme.typography.labelLarge)
        val parts = resource.rectangleParts
        val rectangleError = fieldError(FpgaResourceField.RECTANGLE, null)
        val labels = listOf("fpga_editor_rect_x", "fpga_editor_rect_y", "fpga_editor_rect_width", "fpga_editor_rect_height")
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 0..1) {
                    val part = row * 2 + column
                    OutlinedTextField(parts[part], { onChange(resource.withRectanglePart(part, it)) },
                        modifier = Modifier.weight(1f).testTag("fpga-editor-rect-$part"), label = { Text(text(labels[part])) },
                        singleLine = true, isError = rectangleError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = FpgaResourceField.RECTANGLE.keyboardType()))
                }
            }
        }
        rectangleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (FpgaResourceEditorDraft.displayAttribute(resource.type) != null) {
            DisplaySettings(resource, text) { change(FpgaResourceField.DISPLAY, it) }
            fieldError(FpgaResourceField.DISPLAY, null)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        for (entry in FpgaPinSchema.pinFields) {
            PinSection(resource, entry, text, fieldError, onChange)
        }
        OptionChoice(text(FpgaResourceField.STANDARD.label), field(FpgaResourceField.STANDARD),
            FpgaBoardChoices.ioStandard(field(FpgaResourceField.STANDARD)), text, "fpga-editor-standard") {
            change(FpgaResourceField.STANDARD, it)
        }
        if (resource.showsDrive) {
            OptionChoice(text(FpgaResourceField.DRIVE.label), field(FpgaResourceField.DRIVE),
                FpgaBoardChoices.drive(field(FpgaResourceField.DRIVE)), text, "fpga-editor-drive") { change(FpgaResourceField.DRIVE, it) }
            fieldError(FpgaResourceField.DRIVE, null)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (resource.showsRotation) {
            OptionChoice(text("fpga_editor_orientation"), field(FpgaResourceField.ROTATION),
                FpgaBoardChoices.rotation(resource.type, field(FpgaResourceField.ROTATION)), text, "fpga-editor-rotation") {
                change(FpgaResourceField.ROTATION, it)
            }
            fieldError(FpgaResourceField.ROTATION, null)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("fpga-editor-rotation-error"))
            }
        }
        // Evolution shows the pull for input types and the activity level for types outside IN_OUT_COMPONENT_SET.
        if (resource.showsPull) PullChoice(text("fpga_editor_pull"), resource.pull, text) { onChange(resource.copy(pull = it)) }
        if (resource.showsActivity) BoardCheck(text("fpga_editor_active_low"), resource.activeLow) { onChange(resource.copy(activeLow = it)) }
        TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text("fpga_editor_remove_resource"), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** One direction's pins: a labelled field per pin as Evolution's pin table, or the free list of a custom board. */
@Composable
private fun PinSection(
    resource: FpgaResourceEditorDraft,
    field: FpgaResourceField,
    text: Localizer,
    fieldError: (FpgaResourceField, Int?) -> String?,
    onChange: (FpgaResourceEditorDraft) -> Unit,
) {
    val heading = text(when (field) {
        FpgaResourceField.INPUT -> "fpga_editor_section_inputs"
        FpgaResourceField.OUTPUT -> "fpga_editor_section_outputs"
        else -> "fpga_editor_section_bidirectional"
    })
    when (val layout = resource.pinLayout(field)) {
        FpgaPinLayout.Hidden -> Unit
        is FpgaPinLayout.FreeList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(resource.fields[field].orEmpty(), { onChange(resource.copy(fields = resource.fields + (field to it))) },
                modifier = Modifier.fillMaxWidth(), label = { Text(text(field.label)) }, singleLine = true,
                isError = fieldError(field, null) != null,
                supportingText = {
                    Text(fieldError(field, null) ?: layout.expected?.let { text.plural("fpga_editor_pin_count_mismatch", it.toLong()) }
                        ?: text("fpga_editor_pins_not_used"))
                })
            val expected = layout.expected
            if (expected != null && resource.pinEntries(field).size > expected) {
                TextButton(onClick = { onChange(resource.truncatedPins(field, expected)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text.plural("fpga_editor_keep_first_pins", expected.toLong()))
                }
            }
        }
        is FpgaPinLayout.PerPin -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            resource.requiredDisplayPins?.takeIf { field == FpgaResourceField.OUTPUT }?.let { required ->
                Text(text.plural("fpga_editor_display_pins", required.toLong()), style = MaterialTheme.typography.bodySmall)
            }
            if (layout.configurable) {
                FpgaChoice(text("fpga_editor_pin_count"), layout.count.toString(), FpgaPinSchema.counts.map { it to it.toString() },
                    selectedValue = layout.count, testTag = "fpga-editor-count-${field.name.lowercase()}") {
                    onChange(resource.withPinCount(field, it))
                }
            }
            val entries = resource.pinEntries(field)
            for (pin in 0 until layout.count) {
                val error = fieldError(field, pin)
                OutlinedTextField(entries.getOrElse(pin) { "" }, { onChange(resource.withPin(field, pin, layout.count, it)) },
                    modifier = Modifier.fillMaxWidth().testTag("fpga-editor-pin-${field.name.lowercase()}-$pin"),
                    label = { Text(text(layout.label(pin, layout.count))) }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } })
            }
            if (resource.requiredDisplayPins != null) {
                fieldError(field, null)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Evolution's array and scanning display settings, as structured choices instead of the raw "rows,columns,Mode". */
@Composable
private fun DisplaySettings(resource: FpgaResourceEditorDraft, text: Localizer, onChange: (String) -> Unit) {
    val sevenSegment = resource.type.equals("SevenSegmentScanning", ignoreCase = true)
    val settings = FpgaDisplaySettings.parse(resource.fields[FpgaResourceField.DISPLAY].orEmpty())
    fun update(next: FpgaDisplaySettings) = onChange(next.encoded)
    OptionChoice(text("fpga_editor_array_mode"), settings.mode,
        FpgaBoardChoices.modes(if (sevenSegment) FpgaBoardChoices.scanningModes else FpgaBoardChoices.ledArrayModes, settings.mode),
        text, "fpga-editor-display-mode") { update(settings.withMode(it, sevenSegment)) }
    if (sevenSegment) {
        OptionChoice(text("fpga_editor_digits"), settings.rows, FpgaBoardChoices.numbers(FpgaBoardChoices.digits, settings.rows),
            text, "fpga-editor-digits") { update(settings.withRows(it, true)) }
        if (settings.mode == FpgaDisplaySettings.DECODED) {
            OptionChoice(text("fpga_editor_decoded_bits"), settings.columns,
                FpgaBoardChoices.numbers(FpgaBoardChoices.decodedBits, settings.columns), text, "fpga-editor-decoded-bits") {
                update(settings.withColumns(it, true))
            }
        }
    } else {
        OptionChoice(text("fpga_editor_array_rows"), settings.rows, FpgaBoardChoices.numbers(FpgaBoardChoices.arraySizes, settings.rows),
            text, "fpga-editor-rows") { update(settings.withRows(it, false)) }
        OptionChoice(text("fpga_editor_array_columns"), settings.columns,
            FpgaBoardChoices.numbers(FpgaBoardChoices.arraySizes, settings.columns), text, "fpga-editor-columns") {
            update(settings.withColumns(it, false))
        }
    }
}

@Composable
private fun OptionChoice(label: String, current: String, options: List<FpgaBoardOption>, text: Localizer, testTag: String,
    onChoose: (String) -> Unit) {
    val choices = options.map { it.value to text(it.title) }
    val title = choices.firstOrNull { it.first == current }?.second ?: text("fpga_scan_unset")
    FpgaChoice(label, title, choices, selectedValue = current, testTag = testTag, onChoose = onChoose)
}

/** A typed board value; [error] marks the field and says what to fix, below it. */
@Composable
private fun BoardField(
    label: String,
    value: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

/** The whole row toggles, so TalkBack reads one checkbox with its label. */
@Composable
private fun BoardCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onCheckedChange = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PullChoice(label: String, current: FpgaPull, text: Localizer, onChange: (FpgaPull) -> Unit) {
    val choices = listOf(FpgaPull.FLOAT to text("fpga_editor_pull_float"), FpgaPull.UP to text("fpga_editor_pull_up"),
        FpgaPull.DOWN to text("fpga_editor_pull_down"))
    FpgaSegmentedChoice(label, current, choices, onChoose = onChange)
}

internal data class BoardImage(val picture: FpgaBoardPicture, val bitmap: ImageBitmap)

/** What a drag on the preview does, decided where it starts. */
private sealed interface PreviewDrag {
    data object Create : PreviewDrag
    data class Move(val index: Int, val from: FpgaRectangle) : PreviewDrag
    data class Resize(val index: Int, val from: FpgaRectangle, val corner: FpgaBoardGeometry.Corner) : PreviewDrag
}

/**
 * The board picture with its resources (Evolution's BoardManipulator): a tap selects the resource under the finger.
 * The picture sits in the scrolling editor, so drags scroll it until "Drag to edit" is switched on; then a drag on free
 * space draws a new resource, a drag inside the selected one moves it and a drag at its corners resizes it, and a drag
 * that starts on another resource still scrolls. The live x, y, width and height are read out, an
 * overlap is warned about, and Undo restores the last move. TalkBack moves and resizes with custom actions.
 */
@Composable
private fun BoardPreviewSection(
    image: BoardImage,
    draft: FpgaBoardEditorDraft,
    selected: Int,
    text: Localizer,
    onSelect: (Int) -> Unit,
    onCreate: (FpgaRectangle) -> Unit,
    onChange: (Int, FpgaRectangle) -> Unit,
    undo: Boolean,
    onUndo: () -> Unit,
) {
    val frame = image.picture
    val rectangles = draft.resources.map { it.rectangle }
    var live by remember { mutableStateOf<Pair<Int?, FpgaRectangle>?>(null) }
    val current = live?.second ?: rectangles.getOrNull(selected)
    val currentIndex = if (live != null) live?.first else selected.takeIf { rectangles.getOrNull(it) != null }
    // Off by default: a swipe that starts on the picture scrolls the editor and never draws or moves a rectangle.
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(editing, role = Role.Switch) { editing = it }
            .testTag("fpga-editor-drag-mode"), verticalAlignment = Alignment.CenterVertically) {
            Text(text("fpga_editor_drag_mode"), Modifier.weight(1f))
            Switch(editing, onCheckedChange = null)
        }
        BoardPreview(image, rectangles, selected, live, text,
            description = text("fpga_editor_picture"),
            state = draft.resources.getOrNull(selected)?.menuName(selected)?.let { text("fpga_editor_preview_selected", "resource" to it) },
            editing = editing, onSelect = onSelect, onLive = { live = it }, onCreate = onCreate, onChange = onChange)
        Text(text(if (editing) "fpga_editor_preview_help" else "fpga_editor_preview_help_select"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        current?.let { rectangle ->
            Text(text("fpga_editor_rect_readout", "x" to rectangle.x, "y" to rectangle.y, "width" to rectangle.width, "height" to rectangle.height),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("fpga-editor-rect-readout"))
            val overlaps = FpgaBoardGeometry.overlapping(rectangle, rectangles, currentIndex)
            overlaps.firstOrNull()?.let { other ->
                Text(text.plural("fpga_editor_overlap", overlaps.size.toLong(), "resource" to text(draft.resources[other].menuName(other))),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (undo) UndoRow(text("fpga_editor_rect_changed"), text("fpga_editor_undo"), onUndo = onUndo)
    }
}

@Composable
private fun BoardPreview(
    image: BoardImage,
    resources: List<FpgaRectangle?>,
    selected: Int,
    live: Pair<Int?, FpgaRectangle>?,
    text: Localizer,
    description: String,
    /** The selected resource, read after the picture's name. */
    state: String?,
    /** Drags draw, move and resize; otherwise they are left to the list and only taps select. */
    editing: Boolean,
    onSelect: (Int) -> Unit,
    onLive: (Pair<Int?, FpgaRectangle>?) -> Unit,
    onCreate: (FpgaRectangle) -> Unit,
    onChange: (Int, FpgaRectangle) -> Unit,
) {
    val frame = image.picture
    // Evolution BoardManipulator: defined areas, the highlighted area being edited, and its resize handles.
    val colors = LocalEditorPreferences.current?.preferences?.effectiveColors(LocalEditorDarkTheme.current).orEmpty()
    val selectedColor = colors.fpgaColor(EditorColor.FPGA_DEFINE_HIGHLIGHT)
    val otherColor = colors.fpgaColor(EditorColor.FPGA_DEFINE)
    val resizeColor = colors.fpgaColor(EditorColor.FPGA_DEFINE_RESIZE)
    val moveColor = colors.fpgaColor(EditorColor.FPGA_DEFINE_MOVE)
    val latestResources by rememberUpdatedState(resources)
    val latestSelected by rememberUpdatedState(selected)
    val selectedRectangle = resources.getOrNull(selected)
    fun adjust(dx: Int, dy: Int, dw: Int, dh: Int): Boolean {
        val rectangle = latestResources.getOrNull(latestSelected) ?: return false
        val next = if (dw == 0 && dh == 0) FpgaBoardGeometry.moved(rectangle, dx, dy, frame.width, frame.height)
            else FpgaBoardGeometry.resized(rectangle, FpgaBoardGeometry.Corner.BOTTOM_RIGHT, dw, dh, frame.width, frame.height)
        onChange(latestSelected, next)
        return true
    }
    val actions = if (selectedRectangle == null) emptyList() else listOf(
        "fpga_editor_move_left" to { adjust(-10, 0, 0, 0) }, "fpga_editor_move_right" to { adjust(10, 0, 0, 0) },
        "fpga_editor_move_up" to { adjust(0, -10, 0, 0) }, "fpga_editor_move_down" to { adjust(0, 10, 0, 0) },
        "fpga_editor_grow_width" to { adjust(0, 0, 10, 0) }, "fpga_editor_shrink_width" to { adjust(0, 0, -10, 0) },
        "fpga_editor_grow_height" to { adjust(0, 0, 0, 10) }, "fpga_editor_shrink_height" to { adjust(0, 0, 0, -10) },
    ).map { (key, action) -> CustomAccessibilityAction(text(key), action) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height).testTag("fpga-editor-preview")
        .semantics {
            contentDescription = description
            if (state != null) stateDescription = state
            customActions = actions
        }
        .pointerInput(frame, editing) {
            val handle = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val scale = size.width.toFloat() / frame.width
                val x0 = down.position.x / scale
                val y0 = down.position.y / scale
                val rectangles = latestResources
                val index = latestSelected
                val chosen = rectangles.getOrNull(index)
                val radius = handle / scale
                // A small rectangle is moved from inside; its corners resize from just outside it.
                val small = chosen != null && (chosen.width < 3 * radius || chosen.height < 3 * radius)
                val corner = chosen?.let { FpgaBoardGeometry.corner(it, x0, y0, radius) }
                    ?.takeIf { !small || !FpgaBoardGeometry.contains(chosen, x0, y0) }
                val mode: PreviewDrag? = when {
                    !editing -> null
                    chosen != null && corner != null -> PreviewDrag.Resize(index, chosen, corner)
                    chosen != null && FpgaBoardGeometry.contains(chosen, x0, y0) -> PreviewDrag.Move(index, chosen)
                    FpgaBoardGeometry.hit(x0, y0, rectangles) == null -> PreviewDrag.Create
                    else -> null
                }
                val dragged = if (mode == null) null else awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (dragged == null) {
                    // A tap: select the topmost resource under the finger. Without a mode the editor list scrolls.
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (mode == null) {
                        val released = waitForUpOrCancellationQuietly(down.id)
                        if (released) FpgaBoardGeometry.hit(x0, y0, rectangles)?.let(onSelect)
                    } else if (up != null && !up.pressed) FpgaBoardGeometry.hit(x0, y0, rectangles)?.let(onSelect)
                    return@awaitEachGesture
                }
                fun propose(px: Float, py: Float): FpgaRectangle {
                    val x1 = px / scale
                    val y1 = py / scale
                    val dx = (x1 - x0).roundToInt()
                    val dy = (y1 - y0).roundToInt()
                    return when (mode) {
                        PreviewDrag.Create -> FpgaBoardGeometry.fromDrag(x0, y0, x1, y1, frame.width, frame.height)
                        is PreviewDrag.Move -> FpgaBoardGeometry.moved(mode.from, dx, dy, frame.width, frame.height)
                        is PreviewDrag.Resize -> FpgaBoardGeometry.resized(mode.from, mode.corner, dx, dy, frame.width, frame.height)
                        null -> error("No drag")
                    }
                }
                val target = (mode as? PreviewDrag.Move)?.index ?: (mode as? PreviewDrag.Resize)?.index
                var proposal = propose(dragged.position.x, dragged.position.y)
                onLive(target to proposal)
                val completed = drag(dragged.id) { change ->
                    proposal = propose(change.position.x, change.position.y)
                    onLive(target to proposal)
                    change.consume()
                }
                onLive(null)
                if (!completed) return@awaitEachGesture
                when (mode) {
                    PreviewDrag.Create -> onCreate(proposal)
                    is PreviewDrag.Move -> onChange(mode.index, proposal)
                    is PreviewDrag.Resize -> onChange(mode.index, proposal)
                    null -> Unit
                }
            }
        }) {
        drawImage(image.bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val scaleX = size.width / frame.width
        val scaleY = size.height / frame.height
        resources.forEachIndexed { index, stored ->
            val box = if (live != null && live.first == index) live.second else stored
            if (box != null && box.width > 0 && box.height > 0) {
                drawRect(if (index == selected) selectedColor else otherColor.copy(alpha = 0.65f),
                    Offset(box.x * scaleX, box.y * scaleY), Size(box.width * scaleX, box.height * scaleY),
                    style = Stroke(width = (if (index == selected) 3 else 1).dp.toPx()))
            }
        }
        if (live != null && live.first == null) {
            val box = live.second
            drawRect(moveColor, Offset(box.x * scaleX, box.y * scaleY), Size(box.width * scaleX, box.height * scaleY),
                style = Stroke(width = 2.dp.toPx()))
        }
        val handleBox = (if (live != null && live.first == selected) live.second else resources.getOrNull(selected))
        if (handleBox != null) {
            val half = 4.dp.toPx()
            for ((cx, cy) in listOf(handleBox.x to handleBox.y, handleBox.x + handleBox.width to handleBox.y,
                handleBox.x to handleBox.y + handleBox.height, handleBox.x + handleBox.width to handleBox.y + handleBox.height)) {
                drawRect(resizeColor, Offset(cx * scaleX - half, cy * scaleY - half), Size(2 * half, 2 * half))
            }
        }
    }
}

/** Waits for the pointer to lift without consuming anything, so a list around the preview can still scroll. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancellationQuietly(
    id: androidx.compose.ui.input.pointer.PointerId,
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return !change.isConsumed
        if (change.isConsumed) return false
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
private val clockFields = listOf(FpgaBoardField.CLOCK_PIN, FpgaBoardField.FREQUENCY)
