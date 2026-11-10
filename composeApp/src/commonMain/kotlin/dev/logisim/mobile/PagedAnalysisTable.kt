package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.PagedTruthTable
import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val RowsPerPage = 128
private const val RowsPerExportChunk = 2_048

internal data class AnalysisTablePage(
    val source: PagedTruthTable,
    val number: Int,
    val firstRow: Int,
    val rows: List<List<TruthCell>>,
) {
    val pageCount = (source.rowCount + RowsPerPage - 1) / RowsPerPage
}

internal fun PagedTruthTable.analysisPage(number: Int, cancelled: () -> Boolean): AnalysisTablePage {
    require(number in 0 until (rowCount + RowsPerPage - 1) / RowsPerPage)
    val firstRow = number * RowsPerPage
    return AnalysisTablePage(this, number, firstRow, readRows(firstRow, minOf(RowsPerPage, rowCount - firstRow), cancelled))
}

@Composable
internal fun PagedAnalysisTable(
    page: AnalysisTablePage,
    busy: Boolean,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberLocalizer()
    val source = page.source
    var requested by remember(page) { mutableStateOf((page.number + 1).toString()) }
    val destination = requested.toIntOrNull()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings(Messages.AnalysisPagePosition, "first" to page.firstRow + 1,
            "last" to page.firstRow + page.rows.size, "total" to source.rowCount))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy && page.number > 0, onClick = { onPage(page.number - 1) }) {
                Text(strings(Messages.AnalysisPagePrevious))
            }
            TextButton(enabled = !busy && page.number + 1 < page.pageCount, onClick = { onPage(page.number + 1) }) {
                Text(strings(Messages.AnalysisPageNext))
            }
            OutlinedTextField(requested, { requested = it }, enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(strings(Messages.AnalysisPageNumber, "total" to page.pageCount)) },
                modifier = Modifier.width(136.dp))
            TextButton(enabled = !busy && destination != null && destination in 1..page.pageCount,
                onClick = { destination?.let { onPage(it - 1) } }) { Text(strings(Messages.AnalysisPageGo)) }
        }
        val scroll = rememberScrollState()
        val characters = currentValueCharacters()
        Row(Modifier.horizontalScroll(scroll)) {
            (source.inputs + source.outputs).forEach { AnalysisCell(it, header = true) }
        }
        key(page.number) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = rememberLazyListState()) {
                items(page.rows.size) { index ->
                    val row = page.firstRow + index
                    Row(Modifier.horizontalScroll(scroll)) {
                        source.inputs.forEachIndexed { column, name ->
                            val value = (if (source.input(row, column)) TruthCell.ONE else TruthCell.ZERO).display(characters)
                            AnalysisCell(value, description = strings(Messages.AnalysisA11yInputCell,
                                "row" to row, "input" to name, "value" to value))
                        }
                        page.rows[index].forEachIndexed { column, cell ->
                            AnalysisCell(cell.display(characters), description = strings(Messages.AnalysisA11yTableCell,
                                "row" to row, "output" to source.outputs[column], "value" to cell.display(characters)))
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun exportPagedAnalysis(
    source: PagedTruthTable,
    format: EvolutionTableFormat,
    select: (String, String, (TraceFileOpenResult) -> Unit) -> Unit,
    cancelled: () -> Boolean,
) {
    val encoder = EvolutionTableEncoder(source.inputs, source.outputs, format)
    withContext(Dispatchers.Main) {
        writePagedAnalysis(source, encoder, select, cancelled)
    }
}

private suspend fun writePagedAnalysis(
    source: PagedTruthTable,
    encoder: EvolutionTableEncoder,
    select: (String, String, (TraceFileOpenResult) -> Unit) -> Unit,
    cancelled: () -> Boolean,
) {
    val opened = suspendCancellableCoroutine<TraceFileOpenResult> { continuation ->
        select(encoder.name, encoder.mimeType) { result ->
            continuation.resume(result) { _, late, _ -> (late as? TraceFileOpenResult.Opened)?.writer?.close {} }
        }
    }
    val writer = when (opened) {
        is TraceFileOpenResult.Opened -> opened.writer
        is TraceFileOpenResult.Failure -> throw LocalizedArgumentException(opened.notice)
        TraceFileOpenResult.Cancelled -> return
    }
    var finished = false
    try {
        writer.appendText(encoder.header())
        val records = (source as? ImportedPagedTruthTable)?.recordCount ?: source.rowCount
        for (row in 0 until records step RowsPerExportChunk) {
            currentCoroutineContext().ensureActive()
            val text = withContext(Dispatchers.Default) {
                encoder.rows(source, row, minOf(RowsPerExportChunk, records - row), cancelled)
            }
            writer.appendText(text)
        }
        finished = true
    } finally {
        val closed = withContext(NonCancellable) {
            suspendCoroutine { continuation -> writer.close { continuation.resume(it) } }
        }
        if (finished) closed.checkExportWrite()
    }
}

private suspend fun TraceFileWriter.appendText(text: String) {
    val result = withContext(NonCancellable) {
        suspendCoroutine { continuation -> append(text) { continuation.resume(it) } }
    }
    result.checkExportWrite()
}

private fun AssetActionResult.checkExportWrite() {
    when (this) {
        AssetActionResult.Completed -> Unit
        AssetActionResult.Cancelled -> throw LocalizedArgumentException(message(Messages.AnalysisExportIncomplete))
        is AssetActionResult.Failure -> throw LocalizedArgumentException(notice)
    }
}
