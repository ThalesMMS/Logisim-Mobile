package dev.logisim.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.MemoryAddressRanges
import dev.logisim.mobile.core.MemoryEditResult
import dev.logisim.mobile.core.MemoryEditorModel
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.MemoryRadix
import dev.logisim.mobile.core.MemorySelection
import dev.logisim.mobile.core.rebaseMemoryEdits
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer

/** Edits staged in the memory editor and kept by the tool while its sheet is closed. */
internal data class MemoryEditorDraft(
    /** The memory image the edits started from; a different current image means the memory changed underneath. */
    val base: MemoryImage,
    val image: MemoryImage,
    val stagedImport: MemoryImageImportResult.Success?,
    val clearLineOverflow: Boolean,
    /** The words the edits wrote, also with the value a word already held; Restore edits writes these back. */
    val written: MemoryAddressRanges = MemoryAddressRanges(),
) {
    /** A loaded or cleared image stands for the whole memory; plain word edits change only the words they touch. */
    val replacesWholeImage: Boolean get() = stagedImport != null || clearLineOverflow
}

private val hexKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun MemoryEditorPanel(
    image: MemoryImage,
    persistent: Boolean,
    hasLineOverflow: Boolean,
    onApply: (MemoryImage, Boolean, MemoryImageImportResult.Success?) -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    importedImage: MemoryImageImportResult.Success? = null,
    onImportedImageConsumed: () -> Unit = {},
    importFailure: LocalizedText? = null,
    onImportFailureConsumed: () -> Unit = {},
    onImportFile: (() -> Unit)? = null,
    onExportFile: ((MemoryImageFormat, ByteArray) -> Unit)? = null,
    draft: MemoryEditorDraft? = null,
    onDraftChange: (MemoryEditorDraft?) -> Unit = {},
) {
    val strings = rememberLocalizer()
    val dimensions = image.addressBits to image.dataBits
    val restored = remember(dimensions) {
        draft?.takeIf { it.base.addressBits == image.addressBits && it.base.dataBits == image.dataBits }
    }
    val restoredMatches = restored != null && restored.base == image
    var base by remember(dimensions) { mutableStateOf(image) }
    var model by remember(dimensions) {
        mutableStateOf(if (restoredMatches) MemoryEditorModel(restored.image, written = restored.written) else MemoryEditorModel(image))
    }
    var stagedImport by remember(dimensions) { mutableStateOf(if (restoredMatches) restored.stagedImport else null) }
    var clearLineOverflow by remember(dimensions) { mutableStateOf(restoredMatches && restored.clearLineOverflow) }
    // Restored edits announce themselves; edits staged against a memory that has changed since wait for a choice.
    var restoredBanner by remember(dimensions) { mutableStateOf(restoredMatches) }
    var conflict by remember(dimensions) { mutableStateOf(if (restored != null && !restoredMatches) restored else null) }
    var address by remember { mutableStateOf("0") }
    var rangeEnd by remember { mutableStateOf("0") }
    var fill by remember { mutableStateOf("0") }
    var errors by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }
    var editing by remember(dimensions) { mutableStateOf<Long?>(null) }
    var anchor by remember(dimensions) { mutableStateOf<Long?>(null) }
    var lastEdited by remember(dimensions) { mutableStateOf<Long?>(null) }
    // Selections the user makes are announced; the selection a word commit moves to is not, so continuous entry in the
    // word dialog is not interrupted by the summary behind it after every word.
    var announceSelection by remember(dimensions) { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var exchange by remember { mutableStateOf(false) }
    var exchangeText by remember { mutableStateOf("") }
    var exchangeErrors by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }
    // In a tool sheet, the result of Save file arrives as the sheet's notice, whose banner this dialog covers (C74).
    val sheetNotice = LocalToolSheetNotice.current
    var savedAfter by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(exchange) { if (!exchange) savedAfter = null }
    var saveFormat by remember { mutableStateOf(MemoryImageFormat.HEX_WORDS_ADDRESSED) }
    val clipboard = LocalClipboardManager.current
    // The word being edited stays in view behind the dialog as Next and Previous move it, across pages too.
    val editingCell = remember { BringIntoViewRequester() }
    LaunchedEffect(editing, model.firstAddress) {
        if (editing != null) {
            withFrameNanos { }
            editingCell.bringIntoView()
        }
    }
    val dirty = model.image != base || stagedImport != null || clearLineOverflow

    LaunchedEffect(model.image, model.written, stagedImport, clearLineOverflow) {
        if (conflict != null && dirty) conflict = null
        if (conflict == null) onDraftChange(if (dirty) MemoryEditorDraft(base, model.image, stagedImport, clearLineOverflow, model.written) else null)
    }

    fun accept(result: MemoryEditResult) {
        when (result) {
            is MemoryEditResult.Success -> { model = result.model; errors = emptyList() }
            is MemoryEditResult.Failure -> errors = listOf(result.notice)
        }
    }
    fun runOperation(operation: () -> Unit) {
        try { operation() }
        catch (failure: IllegalArgumentException) { errors = listOf(failure.localizedDescription(message(Messages.MemoryErrorOperation))) }
    }
    fun importImage(decoded: MemoryImageImportResult.Success): Boolean {
        val result = model.replaceImage(decoded.image)
        accept(result)
        if (result is MemoryEditResult.Success) stagedImport = decoded
        return result is MemoryEditResult.Success
    }
    fun apply() {
        if (onApply(model.image, clearLineOverflow, stagedImport)) {
            base = model.image
            model = model.copy(written = MemoryAddressRanges())
            stagedImport = null
            clearLineOverflow = false
            restoredBanner = false
            conflict = null
            onDraftChange(null)
        }
    }
    fun discard() {
        model = model.copy(image = image, selection = null, written = MemoryAddressRanges())
        base = image
        stagedImport = null
        clearLineOverflow = false
        restoredBanner = false
        conflict = null
        anchor = null
        onDraftChange(null)
    }
    /**
     * Brings back edits staged before the memory changed. Every word the edits wrote (a word edit, Fill or Paste, also
     * with the value it already held) is written over the current contents, so words the simulation or Load image
     * changed meanwhile are kept; a loaded or cleared image comes back whole.
     */
    fun restore(kept: MemoryEditorDraft) {
        val restoredImage = if (kept.replacesWholeImage) kept.image else rebaseMemoryEdits(kept.base, kept.image, image, kept.written)
        base = image
        model = model.copy(image = restoredImage, written = kept.written)
        stagedImport = kept.stagedImport
        clearLineOverflow = kept.clearLineOverflow
        conflict = null
        restoredBanner = true
        // Published here as well: edits equal to the current contents change no effect key, yet the old draft must end.
        val restored = MemoryEditorDraft(image, restoredImage, kept.stagedImport, kept.clearLineOverflow, kept.written)
        onDraftChange(restored.takeIf { it.image != image || it.replacesWholeImage })
    }
    fun anchorAt(cell: Long) {
        anchor = cell
        announceSelection = true
        model = model.copy(selection = MemorySelection(cell, cell))
    }
    fun extendTo(cell: Long) {
        val start = anchor ?: model.selection?.first ?: cell
        anchor = start
        announceSelection = true
        model = model.copy(selection = MemorySelection(minOf(start, cell), maxOf(start, cell)))
    }
    fun clearSelection() {
        anchor = null
        announceSelection = true
        model = model.copy(selection = null)
    }
    /** Paste and Fill act on the selection, else on the last edited word. */
    fun targetSelection(): MemoryEditorModel? = when {
        model.selection != null -> model
        lastEdited != null -> model.copy(selection = MemorySelection(lastEdited!!, lastEdited!!))
        else -> null
    }

    LaunchedEffect(importedImage) {
        if (importedImage != null) {
            if (importImage(importedImage)) exchange = false
            onImportedImageConsumed()
        }
    }
    LaunchedEffect(importFailure) {
        if (importFailure != null) {
            exchangeErrors = listOf(importFailure)
            onImportFailureConsumed()
        }
    }

    Column(modifier.padding(horizontal = 12.dp).testTag("memory-editor-panel")) {
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val columns = if (maxWidth < 600.dp) 2 else 4
            // Too narrow for side-by-side fields at this font size (#239: up to 200%): fields and pages stack.
            val crowded = maxWidth / LocalDensity.current.fontScale < 320.dp
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // At large font sizes Import / export moves under the title instead of squeezing it.
                FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(title ?: strings(if (persistent) Messages.MemoryRomContents else Messages.MemoryRamContents),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = {
                        runOperation {
                            exchangeText = MemoryImageCodec.encodeText(model.image, saveFormat.takeIf { it.header != null }
                                ?: MemoryImageFormat.HEX_WORDS_ADDRESSED)
                            exchangeErrors = emptyList()
                            exchange = true
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-exchange")) { Text(strings(Messages.MemoryExchange)) }
                }
                Text(strings(if (persistent) Messages.MemoryApplyRomHint else Messages.MemoryApplyRamHint),
                    style = MaterialTheme.typography.bodySmall)
                Text(strings(Messages.MemoryDimensions, "words" to image.capacity,
                    "bits" to LocalizedText(Messages.MemoryWordBits, quantity = image.dataBits.toLong())),
                    style = MaterialTheme.typography.bodySmall)
                if (restoredBanner && dirty && conflict == null) {
                    MemoryDraftBanner(strings(message("memory_unapplied_changes")), "memory-unapplied-banner",
                        discard = ::discard, confirm = strings(Messages.MemoryApply), onConfirm = ::apply)
                }
                conflict?.let { kept ->
                    MemoryDraftBanner(strings(if (kept.replacesWholeImage) "memory_draft_conflict_image" else "memory_draft_conflict"),
                        "memory-conflict-banner",
                        discard = ::discard, confirm = strings(message("memory_draft_restore")), onConfirm = { restore(kept) })
                }
                MemoryRadixControl(model.valueRadix) { radix -> model = model.copy(valueRadix = radix) }
                MemoryPageControls(model, strings, stacked = crowded, onPage = { model = model.page(it) })
                val addressField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(address, { address = it }, label = { Text(strings("memory_address_hex")) }, singleLine = true,
                        keyboardOptions = hexKeyboard, modifier = fieldModifier.testTag("memory-address"))
                }
                val endField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(rangeEnd, { rangeEnd = it }, label = { Text(strings("memory_range_end_hex")) }, singleLine = true,
                        keyboardOptions = hexKeyboard, modifier = fieldModifier.testTag("memory-range-end"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    addressField(Modifier.weight(1f))
                    TextButton(onClick = { accept(model.navigate(address)); anchor = null; announceSelection = true },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(strings(Messages.MemoryGo))
                    }
                    if (!crowded) endField(Modifier.weight(1f))
                }
                if (crowded) endField(Modifier.fillMaxWidth())
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { accept(model.select(address, rangeEnd)); anchor = null; announceSelection = true },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(strings(Messages.MemorySelectRange))
                    }
                    TextButton(onClick = { runOperation { clipboard.setText(AnnotatedString(model.selectionText())) } },
                        enabled = model.selection != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.MemoryCopySelection)) }
                    // Paste writes from the selection, else the last edited word, else the page start; the label names it.
                    val pasteAt = model.selection?.first ?: lastEdited ?: model.firstAddress
                    TextButton(onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        accept(model.paste(pasteAt, text))
                        anchor = null
                        announceSelection = true
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-paste")) {
                        Text(strings("memory_paste_at", "address" to model.image.addressHex(pasteAt)))
                    }
                    TextButton(onClick = ::clearSelection, enabled = model.selection != null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("memory-clear-selection")) { Text(strings("memory_clear_selection")) }
                }
                val fillField: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(fill, { fill = it }, label = { Text(strings(Messages.MemorySelectionValue)) }, singleLine = true,
                        keyboardOptions = hexKeyboard.copy(keyboardType = if (model.valueRadix == MemoryRadix.HEXADECIMAL) KeyboardType.Ascii else KeyboardType.Number),
                        modifier = fieldModifier.testTag("memory-fill-value"))
                }
                // Crowded, the value field takes the full width and Fill goes under it.
                if (crowded) fillField(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!crowded) fillField(Modifier.weight(1f))
                    TextButton(onClick = { targetSelection()?.let { accept(it.fillSelection(fill)) } },
                        enabled = model.selection != null || lastEdited != null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(strings(Messages.MemoryFill))
                    }
                }
                Text(memorySelectionSummary(model, anchor, strings), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("memory-selection-summary")
                        .semantics { if (announceSelection) liveRegion = LiveRegionMode.Polite })
                errors.forEach { Text(strings(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    model.cells().chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { cell ->
                                val shape = RoundedCornerShape(4.dp)
                                val start = strings("memory_select_start")
                                val extend = strings("memory_select_extend")
                                val editWord = strings("memory_edit_word")
                                val selecting = anchor != null
                                Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                                    .background(if (cell.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .then(if (cell.selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                                    // A tap edits the word, or extends the selection while one is being made, and a long
                                    // press starts one. The remaining choice is the single custom action, so each is listed once.
                                    .combinedClickable(onClickLabel = if (selecting) extend else editWord,
                                        onLongClickLabel = start, onLongClick = { anchorAt(cell.address) }, onClick = {
                                            if (anchor != null) extendTo(cell.address) else editing = cell.address
                                        })
                                    .semantics {
                                        contentDescription = strings(Messages.MemoryA11yCell, "address" to cell.addressText, "value" to cell.valueText)
                                        selected = cell.selected
                                        customActions = listOf(
                                            if (selecting) CustomAccessibilityAction(editWord) { editing = cell.address; true }
                                            else CustomAccessibilityAction(extend) { extendTo(cell.address); true },
                                        )
                                    }
                                    .then(if (cell.address == editing) Modifier.bringIntoViewRequester(editingCell) else Modifier)
                                    .testTag("memory-cell-${cell.addressText}")
                                    .padding(6.dp)) {
                                    Text(cell.addressText, style = MaterialTheme.typography.labelSmall)
                                    Text(cell.valueText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        // At large font sizes Cancel and Apply move under Clear contents instead of being pushed off the edge.
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmClear = true },
                enabled = model.image.nonZeroWords.isNotEmpty() || hasLineOverflow && !clearLineOverflow,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Messages.MemoryClearContents))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-cancel")) { Text(strings(Messages.MemoryCancel)) }
                Button(onClick = ::apply, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-apply")) { Text(strings(Messages.MemoryApply)) }
            }
        }
    }
    if (confirmClear) {
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(strings(Messages.MemoryClearContents)) },
            text = { Text(strings(Messages.MemoryClearContentsConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    model = model.clearContents()
                    clearLineOverflow = true
                    stagedImport = null
                    errors = emptyList()
                    confirmClear = false
                }) { Text(strings(Messages.MemoryClearContents)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(strings(Messages.MemoryCancel)) }
            },
        )
    }
    editing?.let { address ->
        // Recomputed from the model on every step, never a stale cell snapshot.
        val cell = model.cell(address)
        fun commit(text: String): LocalizedText? = when (val result = model.editCell(cell.addressText, text)) {
            is MemoryEditResult.Success -> {
                model = result.model; lastEdited = address; anchor = null; announceSelection = false; errors = emptyList(); null
            }
            is MemoryEditResult.Failure -> result.notice
        }
        MemoryWordDialog(
            key = address,
            value = cell.valueText,
            title = message(Messages.MemoryCellTitle, "address" to cell.addressText),
            label = message(Messages.MemoryValueRadix, "base" to model.valueRadix.base),
            action = message(Messages.MemoryChange),
            onSubmit = ::commit,
            onDismiss = { editing = null },
            keyboardType = if (model.valueRadix == MemoryRadix.HEXADECIMAL) KeyboardType.Ascii else KeyboardType.Number,
            steps = MemoryWordSteps(previousEnabled = address > 0, nextEnabled = address < image.capacity - 1) { offset, text ->
                commit(text) ?: run {
                    val target = address + offset
                    if (target < model.firstAddress || target >= model.firstAddress + model.pageSize) {
                        model = model.copy(firstAddress = target / model.pageSize * model.pageSize)
                    }
                    editing = target
                    null
                }
            },
        )
    }
    if (exchange) {
        AppAlertDialog(modifier = Modifier.editorDialogBounds(), onDismissRequest = { exchange = false },
            title = { Text(strings(Messages.MemoryImageTitle)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(strings("memory_open_auto_hint"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { exchangeErrors = emptyList(); onImportFile?.invoke() }, enabled = onImportFile != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("memory-open-file")) { Text(strings(Messages.MemoryOpenFile)) }
                HorizontalDivider()
                Text(strings("memory_format_label"), style = MaterialTheme.typography.titleSmall)
                MemoryFormatChooser(saveFormat, onSelect = { candidate ->
                    saveFormat = candidate
                    exchangeErrors = emptyList()
                    if (candidate.header != null) runOperation { exchangeText = MemoryImageCodec.encodeText(model.image, candidate) }
                })
                if (saveFormat.header != null) {
                    OutlinedTextField(exchangeText, { exchangeText = it }, minLines = 4, maxLines = 8,
                        label = { Text(strings(Messages.MemoryImageContents)) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(exchangeText)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(strings(Messages.MemoryCopy))
                        }
                        TextButton(onClick = {
                            // A header in the text names its format; headerless text uses the selected format.
                            val detected = MemoryImageCodec.decodeText(exchangeText, image.addressBits, image.dataBits, null)
                            val decoded = if (detected is MemoryImageImportResult.Failure && detected.decodableAs(saveFormat))
                                MemoryImageCodec.decodeText(exchangeText, image.addressBits, image.dataBits, saveFormat) else detected
                            when (decoded) {
                                is MemoryImageImportResult.Success -> if (importImage(decoded)) exchange = false
                                    else exchangeErrors = errors
                                is MemoryImageImportResult.Failure -> exchangeErrors = decoded.diagnostics.map { it.notice }
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.MemoryImportText)) }
                    }
                } else Text(strings(Messages.MemoryBinaryFileHint), style = MaterialTheme.typography.bodySmall)
                exchangeErrors.forEach { Text(strings(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    try {
                        savedAfter = sheetNotice?.serial ?: 0L
                        onExportFile?.invoke(saveFormat, MemoryImageCodec.encode(model.image, saveFormat))
                    } catch (failure: IllegalArgumentException) {
                        exchangeErrors = listOf(failure.localizedDescription(message(Messages.MemoryErrorOperation)))
                    }
                }, enabled = onExportFile != null, modifier = Modifier.heightIn(min = 48.dp).testTag("memory-save-file")) {
                    Text(strings(Messages.MemorySaveFile))
                }
                AccessibleEditorNotice(sheetNotice?.takeIf { notice -> savedAfter?.let { notice.serial > it } == true }?.text,
                    Modifier.testTag("memory-save-notice"))
            } },
            confirmButton = {
                TextButton(onClick = { exchange = false }, modifier = Modifier.testTag("memory-exchange-close")) { Text(strings(Messages.MemoryClose)) }
            })
    }
}

@Composable
private fun MemoryDraftBanner(text: String, tag: String, discard: () -> Unit, confirm: String, onConfirm: () -> Unit) {
    val strings = rememberLocalizer()
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = discard, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-discard")) {
                    Text(strings("memory_discard_changes"))
                }
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-confirm")) { Text(confirm) }
            }
        }
    }
}

/** Bin/Oct/Dec/Hex for the values only; addresses stay hexadecimal like Evolution's hex editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryRadixControl(selected: MemoryRadix, onSelect: (MemoryRadix) -> Unit) {
    val strings = rememberLocalizer()
    val options = listOf(
        Triple(MemoryRadix.BINARY, "memory_radix_bin", "memory_radix_binary"),
        Triple(MemoryRadix.OCTAL, "memory_radix_oct", "memory_radix_octal"),
        Triple(MemoryRadix.DECIMAL, "memory_radix_dec", "memory_radix_decimal"),
        Triple(MemoryRadix.HEXADECIMAL, "memory_radix_hex", "memory_radix_hexadecimal"),
    )
    // The label sits above the segments, so the four of them keep the full width at large font sizes.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings("memory_radix_label"), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (radix, short, long) ->
                val description = strings(long)
                SegmentedButton(selected = radix == selected, onClick = { onSelect(radix) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size), icon = {},
                    modifier = Modifier.heightIn(min = 48.dp).testTag("memory-radix-${radix.base}")
                        .semantics { contentDescription = description }) {
                    Text(strings(short), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun MemoryPageControls(model: MemoryEditorModel, strings: Localizer, stacked: Boolean, onPage: (Int) -> Unit) {
    val capacity = model.image.capacity
    val first = model.image.addressHex(model.firstAddress)
    val last = model.image.addressHex(minOf(capacity, model.firstAddress + model.pageSize) - 1)
    val end = model.image.addressHex(capacity - 1)
    val description = strings("memory_page_range_a11y", "first" to first, "last" to last, "end" to end)
    val indicator: @Composable (Modifier) -> Unit = { indicatorModifier ->
        Text(strings("memory_page_range", "first" to first, "last" to last, "end" to end), textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = indicatorModifier.testTag("memory-page-indicator").semantics { contentDescription = description })
    }
    // Stacked (narrow or large font), the range gets its own line above Previous and Next.
    if (stacked) indicator(Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPage(-1) }, enabled = model.firstAddress > 0,
            modifier = Modifier.heightIn(min = 48.dp).testTag("memory-previous-page")) { Text(strings(Messages.MemoryPreviousPage)) }
        if (stacked) Spacer(Modifier.weight(1f)) else indicator(Modifier.weight(1f))
        TextButton(onClick = { onPage(1) }, enabled = model.firstAddress + model.pageSize < capacity,
            modifier = Modifier.heightIn(min = 48.dp).testTag("memory-next-page")) { Text(strings(Messages.MemoryNextPage)) }
    }
}

/** An address as the editor shows it: hexadecimal with a `0x` prefix, padded to the memory's address width. */
private fun MemoryImage.addressHex(value: Long): String = "0x" + value.toString(16).padStart((addressBits + 3) / 4, '0')

private fun memorySelectionSummary(model: MemoryEditorModel, anchor: Long?, strings: Localizer): String {
    val summary = model.selection?.let {
        strings.plural("memory_selection_range", it.size, "first" to model.image.addressHex(it.first),
            "last" to model.image.addressHex(it.last), "count" to it.size)
    }
    val extending = strings("memory_selection_extending")
    return when {
        anchor != null && summary != null -> strings("memory_selection_extending_summary", "selection" to summary, "hint" to extending)
        anchor != null -> extending
        summary != null -> summary
        else -> strings("memory_selection_hint")
    }
}

private enum class MemoryFormatFamily(val tag: String) { RAW("raw"), HEX("hex"), BINARY("binary"), ASCII("ascii") }

private val MemoryImageFormat.family: MemoryFormatFamily get() = when (this) {
    MemoryImageFormat.RAW -> MemoryFormatFamily.RAW
    MemoryImageFormat.BINARY_BIG, MemoryImageFormat.BINARY_LITTLE -> MemoryFormatFamily.BINARY
    MemoryImageFormat.ASCII_BIG, MemoryImageFormat.ASCII_LITTLE -> MemoryFormatFamily.ASCII
    else -> MemoryFormatFamily.HEX
}
private val MemoryImageFormat.hexBytes: Boolean get() = name.startsWith("HEX_BYTES")
private val MemoryImageFormat.addressed: Boolean get() = name.contains("ADDRESSED")

private fun hexFormat(bytes: Boolean, addressed: Boolean, little: Boolean): MemoryImageFormat = when {
    !bytes -> if (addressed) MemoryImageFormat.HEX_WORDS_ADDRESSED else MemoryImageFormat.HEX_WORDS_PLAIN
    addressed -> if (little) MemoryImageFormat.HEX_BYTES_ADDRESSED_LITTLE else MemoryImageFormat.HEX_BYTES_ADDRESSED_BIG
    else -> if (little) MemoryImageFormat.HEX_BYTES_PLAIN_LITTLE else MemoryImageFormat.HEX_BYTES_PLAIN_BIG
}

/**
 * The memory image formats grouped as Evolution's HexFormatDialog groups them: v2.0 raw, v3.0 hex (words or bytes,
 * addressed or plain, byte order), Binary and ASCII with C-style escapes (byte order). Each choice is one of the
 * existing [MemoryImageFormat] entries, so the files read and written are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryFormatChooser(
    selected: MemoryImageFormat,
    onSelect: (MemoryImageFormat) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "memory-format",
) {
    val strings = rememberLocalizer()
    var lastHex by remember { mutableStateOf(if (selected.family == MemoryFormatFamily.HEX) selected else MemoryImageFormat.HEX_WORDS_ADDRESSED) }
    LaunchedEffect(selected) { if (selected.family == MemoryFormatFamily.HEX) lastHex = selected }
    val family = selected.family

    @Composable
    fun FamilyRow(option: MemoryFormatFamily, label: String, choose: () -> MemoryImageFormat) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = family == option, role = Role.RadioButton, onClick = { onSelect(choose()) })
            .testTag("$tag-${option.tag}"), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = family == option, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    /**
     * One option of the selected family, like Evolution's (size), (style) and (endian) rows; [group] names it for
     * TalkBack. [segmented] options share a row; otherwise each is a radio row under a visible group label.
     */
    @Composable
    fun Options(segmented: Boolean, group: String, options: List<Pair<String, String>>, chosen: Int, choose: (Int) -> Unit) {
        if (segmented) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = optionIndent, bottom = 4.dp)) {
            options.forEachIndexed { index, (optionTag, label) ->
                val description = strings("memory_format_option_a11y", "group" to group, "option" to label)
                SegmentedButton(selected = index == chosen, onClick = { choose(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size), icon = {},
                    modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-$optionTag").semantics { contentDescription = description }) {
                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } else Column(Modifier.fillMaxWidth().padding(start = optionIndent, bottom = 4.dp).selectableGroup()) {
            // The rows already say their group to TalkBack, so the label is only drawn.
            Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { })
            options.forEachIndexed { index, (optionTag, label) ->
                val description = strings("memory_format_option_a11y", "group" to group, "option" to label)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = index == chosen, role = Role.RadioButton, onClick = { choose(index) })
                    .testTag("$tag-$optionTag").semantics { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = index == chosen, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val byteOrder = strings("memory_format_group_byte_order")
    val endian = listOf("big" to strings("memory_format_big_endian"), "little" to strings("memory_format_little_endian"))
    val optionLabels = listOf(strings("memory_format_words"), strings("memory_format_bytes"), strings("memory_format_addressed"),
        strings("memory_format_plain")) + endian.map { it.second }
    val optionStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Two segments share the indented width. When a word of an option label is wider than a segment (large fonts,
        // narrow screens), every option becomes a radio row instead, so no label is cut inside a word.
        val padding = SegmentedButtonDefaults.ContentPadding
        val segmentText = (maxWidth - optionIndent) / 2 - padding.calculateLeftPadding(LayoutDirection.Ltr) -
            padding.calculateRightPadding(LayoutDirection.Ltr) - 2.dp
        val segmented = remember(optionLabels, optionStyle, density, segmentText) {
            optionLabels.flatMap { it.split(' ') }.all { word ->
                with(density) { measurer.measure(word, optionStyle).size.width.toDp() } <= segmentText
            }
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            FamilyRow(MemoryFormatFamily.RAW, strings(Messages.MemoryFormatRaw)) { MemoryImageFormat.RAW }
            FamilyRow(MemoryFormatFamily.HEX, strings("memory_format_family_hex")) { lastHex }
            if (family == MemoryFormatFamily.HEX) {
                Options(segmented, strings("memory_format_group_unit"),
                    listOf("words" to strings("memory_format_words"), "bytes" to strings("memory_format_bytes")),
                    if (selected.hexBytes) 1 else 0) { onSelect(hexFormat(it == 1, selected.addressed, selected.littleEndian)) }
                Options(segmented, strings("memory_format_group_style"),
                    listOf("addressed" to strings("memory_format_addressed"), "plain" to strings("memory_format_plain")),
                    if (selected.addressed) 0 else 1) { onSelect(hexFormat(selected.hexBytes, it == 0, selected.littleEndian)) }
                if (selected.hexBytes) Options(segmented, byteOrder, endian, if (selected.littleEndian) 1 else 0) {
                    onSelect(hexFormat(true, selected.addressed, it == 1))
                }
            }
            FamilyRow(MemoryFormatFamily.BINARY, strings("memory_format_family_binary")) {
                if (selected.littleEndian) MemoryImageFormat.BINARY_LITTLE else MemoryImageFormat.BINARY_BIG
            }
            if (family == MemoryFormatFamily.BINARY) Options(segmented, byteOrder, endian, if (selected.littleEndian) 1 else 0) {
                onSelect(if (it == 1) MemoryImageFormat.BINARY_LITTLE else MemoryImageFormat.BINARY_BIG)
            }
            FamilyRow(MemoryFormatFamily.ASCII, strings("memory_format_family_ascii")) {
                if (selected.littleEndian) MemoryImageFormat.ASCII_LITTLE else MemoryImageFormat.ASCII_BIG
            }
            if (family == MemoryFormatFamily.ASCII) Options(segmented, byteOrder, endian, if (selected.littleEndian) 1 else 0) {
                onSelect(if (it == 1) MemoryImageFormat.ASCII_LITTLE else MemoryImageFormat.ASCII_BIG)
            }
        }
    }
}

/** The format options sit under their family, as in Evolution's HexFormatDialog. */
private val optionIndent = 24.dp

/** Evolution's HexFormatDialog: a file without a recognized header is loaded only after the user names its format. */
@Composable
internal fun MemoryFormatChoiceDialog(
    diagnostics: List<LocalizedText>,
    initial: MemoryImageFormat,
    error: LocalizedText?,
    onLoad: (MemoryImageFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    var selected by remember { mutableStateOf(initial) }
    AppAlertDialog(modifier = Modifier.editorDialogBounds(), onDismissRequest = onDismiss,
        title = { Text(strings("memory_format_choose_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                diagnostics.forEach { Text(strings(it), style = MaterialTheme.typography.bodySmall) }
                Text(strings("memory_format_choose_prompt"), style = MaterialTheme.typography.bodyMedium)
                MemoryFormatChooser(selected, onSelect = { selected = it }, tag = "memory-format-choice")
                error?.let { Text(strings(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLoad(selected) }, modifier = Modifier.testTag("memory-format-choice-load")) {
                Text(strings("memory_format_load"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.MemoryCancel)) } })
}
