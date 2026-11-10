package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.MemoryCell
import dev.logisim.mobile.core.MemoryEditResult
import dev.logisim.mobile.core.MemoryEditorModel
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.MemoryRadix
import dev.logisim.mobile.core.localizedDescription
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

@Suppress("DEPRECATION")
@Composable
internal fun MemoryEditorPanel(
    image: MemoryImage,
    persistent: Boolean,
    hasLineOverflow: Boolean,
    onApply: (MemoryImage, Boolean, MemoryImageImportResult.Success?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    importedImage: MemoryImageImportResult.Success? = null,
    onImportedImageConsumed: () -> Unit = {},
    onImportFile: ((MemoryImageFormat) -> Unit)? = null,
    onExportFile: ((MemoryImageFormat, ByteArray) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    var model by remember(image.addressBits, image.dataBits) { mutableStateOf(MemoryEditorModel(image)) }
    var address by remember { mutableStateOf("0") }
    var rangeEnd by remember { mutableStateOf("0") }
    var fill by remember { mutableStateOf("0") }
    var errors by remember { mutableStateOf<List<LocalizedText>>(emptyList()) }
    var editing by remember { mutableStateOf<MemoryCell?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearLineOverflow by remember(image.addressBits, image.dataBits) { mutableStateOf(false) }
    var stagedImport by remember(image.addressBits, image.dataBits) { mutableStateOf<MemoryImageImportResult.Success?>(null) }
    var exchange by remember { mutableStateOf(false) }
    var exchangeText by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(MemoryImageFormat.HEX_WORDS_ADDRESSED) }
    val clipboard = LocalClipboardManager.current

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
    fun importImage(decoded: MemoryImageImportResult.Success) {
        val result = model.replaceImage(decoded.image)
        accept(result)
        if (result is MemoryEditResult.Success) stagedImport = decoded
    }
    LaunchedEffect(importedImage) {
        if (importedImage != null) {
            importImage(importedImage)
            onImportedImageConsumed()
        }
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (persistent) strings(Messages.MemoryRomContents) else strings(Messages.MemoryRamContents), style = MaterialTheme.typography.titleMedium)
        Text(if (persistent) strings(Messages.MemoryApplyRomHint)
            else strings(Messages.MemoryApplyRamHint),
            style = MaterialTheme.typography.bodySmall)
        Text(strings(Messages.MemoryDimensions, "words" to image.capacity, "bits" to LocalizedText(Messages.MemoryWordBits, quantity = image.dataBits.toLong())), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MemoryRadix.entries.forEach { radix ->
                TextButton(onClick = { model = model.copy(addressRadix = radix, valueRadix = radix) }) {
                    Text(if (radix == model.valueRadix) "[${radix.base}]" else radix.base.toString())
                }
            }
            TextButton(onClick = { model = model.page(-1) }) { Text(strings(Messages.MemoryPreviousPage)) }
            TextButton(onClick = { model = model.page(1) }) { Text(strings(Messages.MemoryNextPage)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text(strings(Messages.MemoryStartAddress)) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { accept(model.navigate(address)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.MemoryGo)) }
            OutlinedTextField(rangeEnd, { rangeEnd = it }, label = { Text(strings(Messages.MemorySelectionEnd)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { accept(model.select(address, rangeEnd)) }) { Text(strings(Messages.MemorySelectRange)) }
            TextButton(onClick = { runOperation { clipboard.setText(AnnotatedString(model.selectionText())) } }, enabled = model.selection != null) { Text(strings(Messages.MemoryCopySelection)) }
            TextButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                accept(model.paste(address, text))
            }) { Text(strings(Messages.MemoryPasteValues)) }
            TextButton(onClick = {
                runOperation {
                    exchangeText = MemoryImageCodec.encodeText(model.image, MemoryImageFormat.HEX_WORDS_ADDRESSED)
                    exchange = true
                }
            }) { Text(strings(Messages.MemoryExchange)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(fill, { fill = it }, label = { Text(strings(Messages.MemorySelectionValue)) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { accept(model.fillSelection(fill)) }, enabled = model.selection != null) { Text(strings(Messages.MemoryFill)) }
        }
        errors.forEach { Text(strings(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        BoxWithConstraints(Modifier.weight(1f, fill = false).heightIn(max = 440.dp)) {
            val columns = if (maxWidth < 600.dp) 2 else 4
            val rows = model.cells().chunked(columns)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(rows.size) { index ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rows[index].forEach { cell ->
                            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                .background(if (cell.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { editing = cell }.padding(6.dp)
                                .semantics { contentDescription = strings(Messages.MemoryA11yCell, "address" to cell.addressText, "value" to cell.valueText) }) {
                                Text(cell.addressText, style = MaterialTheme.typography.labelSmall)
                                Text(cell.valueText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { confirmClear = true },
                enabled = model.image.nonZeroWords.isNotEmpty() || hasLineOverflow && !clearLineOverflow) {
                Text(strings(Messages.MemoryClearContents))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(strings(Messages.MemoryCancel)) }
            Button(onClick = { onApply(model.image, clearLineOverflow, stagedImport) }) { Text(strings(Messages.MemoryApply)) }
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
    editing?.let { cell ->
        var value by remember(cell.address) { mutableStateOf(cell.valueText) }
        AppAlertDialog(onDismissRequest = { editing = null }, title = { Text(strings(Messages.MemoryCellTitle, "address" to cell.addressText)) },
            text = { OutlinedTextField(value, { value = it }, label = { Text(strings(Messages.MemoryValueRadix, "base" to model.valueRadix.base)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val result = model.editCell(cell.addressText, value)
                accept(result)
                if (result is MemoryEditResult.Success) editing = null
            }) { Text(strings(Messages.MemoryChange)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(strings(Messages.MemoryCancel)) } })
    }
    if (exchange) {
        AppAlertDialog(onDismissRequest = { exchange = false }, title = { Text(strings(Messages.MemoryImageTitle)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MemoryImageFormat.entries.forEach { candidate ->
                        TextButton(onClick = {
                            format = candidate
                            if (candidate.header != null) runOperation { exchangeText = MemoryImageCodec.encodeText(model.image, candidate) }
                        }) { Text(if (candidate == format) "[${strings(candidate.localizedDescription())}]" else strings(candidate.localizedDescription())) }
                    }
                }
                if (format.header != null) {
                    OutlinedTextField(exchangeText, { exchangeText = it }, minLines = 4, maxLines = 8,
                        label = { Text(strings(Messages.MemoryImageContents)) }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(exchangeText)) }) { Text(strings(Messages.MemoryCopy)) }
                        TextButton(onClick = {
                            when (val decoded = MemoryImageCodec.decodeText(exchangeText, image.addressBits, image.dataBits, format)) {
                                is MemoryImageImportResult.Success -> { importImage(decoded); exchange = false }
                                is MemoryImageImportResult.Failure -> errors = decoded.diagnostics.map { it.notice }
                            }
                        }) { Text(strings(Messages.MemoryImportText)) }
                    }
                } else Text(strings(Messages.MemoryBinaryFileHint), style = MaterialTheme.typography.bodySmall)
                errors.forEach { Text(strings(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = { onImportFile?.invoke(format) }, enabled = onImportFile != null) { Text(strings(Messages.MemoryOpenFile)) }
                    TextButton(onClick = { runOperation { onExportFile?.invoke(format, MemoryImageCodec.encode(model.image, format)) } },
                        enabled = onExportFile != null) { Text(strings(Messages.MemorySaveFile)) }
                }
            } },
            confirmButton = { TextButton(onClick = { exchange = false }) { Text(strings(Messages.MemoryClose)) } })
    }
}
