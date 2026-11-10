package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.AnalysisCircuitGenerator
import dev.logisim.mobile.core.AnalysisLimits
import dev.logisim.mobile.core.BooleanExpressionParser
import dev.logisim.mobile.core.BooleanMinimizer
import dev.logisim.mobile.core.CircuitAnalyzer
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.KarnaughCell
import dev.logisim.mobile.core.KarnaughMap
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.MinimizedBooleanFunction
import dev.logisim.mobile.core.NormalForm
import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthTable
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private enum class AnalysisView(val titleKey: String) {
    TABLE(Messages.AnalysisTable), EXPRESSIONS(Messages.AnalysisExpressions), KARNAUGH(Messages.AnalysisKarnaugh),
}

private data class AnalysisColumn(val input: Boolean, val index: Int)

private data class AnalysisPanelResult(
    val table: TruthTable? = null,
    val page: AnalysisTablePage? = null,
    val forms: List<MinimizedBooleanFunction>? = null,
    val generated: CircuitDocument? = null,
    val tableFile: EvolutionTruthTableFile? = null,
    val export: DocumentAsset? = null,
    val ownsPage: Boolean = false,
)

/** [onGenerated] must add or replace the circuit through the editor's normal history command. */
@Composable
fun AnalyzePanel(
    project: CircuitProject,
    circuitName: String = project.mainCircuitName,
    onGenerated: (CircuitDocument) -> Unit,
    modifier: Modifier = Modifier,
    registry: ComponentRegistry? = null,
    simulatorFactory: (() -> CircuitSimulator)? = null,
    assets: DocumentAssetActions? = null,
    selectPagedExport: ((String, String, (TraceFileOpenResult) -> Unit) -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    var table by remember { mutableStateOf<TruthTable?>(null) }
    var tableFile by remember { mutableStateOf<EvolutionTruthTableFile?>(null) }
    var page by remember { mutableStateOf<AnalysisTablePage?>(null) }
    var filePending by remember { mutableStateOf(false) }
    var forms by remember { mutableStateOf<List<MinimizedBooleanFunction>>(emptyList()) }
    var inputText by remember { mutableStateOf("A, B") }
    var outputText by remember { mutableStateOf("Y") }
    var expressionText by remember { mutableStateOf("Y := A & B") }
    var selectedView by remember { mutableStateOf(AnalysisView.TABLE) }
    var outputIndex by remember { mutableStateOf(0) }
    var editingColumn by remember { mutableStateOf<AnalysisColumn?>(null) }
    var form by remember { mutableStateOf(NormalForm.SOP) }
    var nandOnly by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LocalizedText?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val limits = remember { AnalysisLimits() }
    val busy = running != null || filePending
    var retainedImport by remember { mutableStateOf<ImportedPagedTruthTable?>(null) }
    DisposableEffect(Unit) {
        onDispose { retainedImport?.close() }
    }

    fun run(cancelledKey: String = Messages.AnalysisCancelled, work: suspend (() -> Boolean) -> AnalysisPanelResult) {
        if (running != null || filePending) return
        error = null
        running = scope.launch {
            var stagedImport: ImportedPagedTruthTable? = null
            try {
                val job = currentCoroutineContext()[Job]!!
                val result = withContext(Dispatchers.Default) {
                    work { !job.isActive }.also { if (it.ownsPage) stagedImport = it.page?.source as? ImportedPagedTruthTable }
                }
                result.table?.let {
                    table = it
                    tableFile = result.tableFile
                    page = null
                    retainedImport?.close()
                    retainedImport = null
                    forms = emptyList()
                    outputIndex = 0
                    editingColumn = null
                    if (result.tableFile != null) selectedView = AnalysisView.TABLE
                }
                result.page?.let {
                    table = null
                    tableFile = null
                    page = it
                    val imported = it.source as? ImportedPagedTruthTable
                    if (retainedImport !== imported) {
                        retainedImport?.close()
                        retainedImport = imported
                    }
                    stagedImport = null
                    forms = emptyList()
                    editingColumn = null
                    selectedView = AnalysisView.TABLE
                }
                if (result.export != null && assets != null) {
                    filePending = true
                    assets.create(result.export) { saved ->
                        filePending = false
                        if (saved is AssetActionResult.Failure) error = saved.notice
                    }
                }
                result.forms?.let { forms = it }
                result.generated?.let(onGenerated)
            } catch (_: CancellationException) {
                error = message(cancelledKey)
            } catch (_: AnalysisCancelledException) {
                error = message(cancelledKey)
            } catch (failure: IllegalArgumentException) {
                error = failure.localizedDescription(message(Messages.AnalysisFailed))
            } finally {
                stagedImport?.close()
                running = null
            }
        }
    }

    fun edit(row: Int, output: Int) {
        if (busy) return
        val current = table ?: return
        table = current.withCell(row, output, current.rows[row][output].next())
        forms = emptyList()
    }
    val characters = currentValueCharacters()
    val colors = analysisColors()

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Messages.AnalysisTitle), style = MaterialTheme.typography.titleLarge)
        Text(strings(Messages.AnalysisLimitsHelp),
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                run { cancel ->
                    val source = CircuitAnalyzer.pagedTruthTable(
                        project, circuitName, limits.copy(maxInputBits = 20), registry, simulatorFactory?.invoke(),
                    )
                    if (source.inputs.size <= limits.maxInputBits) {
                        AnalysisPanelResult(table = TruthTable(source.inputs, source.outputs, source.readRows(0, source.rowCount, cancel)))
                    } else AnalysisPanelResult(page = source.analysisPage(0, cancel))
                }
            }) { Text(strings(Messages.AnalysisReadCircuit)) }
            if (running?.isActive == true) TextButton(onClick = { running?.cancel() }) { Text(strings(Messages.ActionCancel)) }
            AnalysisView.entries.forEach { view ->
                val title = strings(view.titleKey)
                TextButton(onClick = { selectedView = view }) { Text(if (selectedView == view) "• $title" else title) }
            }
        }
        if (assets != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = {
                    val files = assets.analysisTables
                    if (files != null) run { cancel -> importSelectedAnalysisTable(files, cancel) }
                    else {
                        filePending = true
                        error = null
                        // Files providers may advertise CSV/TXT as generic data. Validate the file in the codec.
                        assets.open(listOf("application/octet-stream"), MAX_EVOLUTION_TABLE_FILE_BYTES) { opened ->
                            filePending = false
                            when (opened) {
                                is AssetOpenResult.Opened -> run { cancel ->
                                    val imported = EvolutionTruthTableFile.read(opened.asset, limits, cancel)
                                    AnalysisPanelResult(table = imported.table, tableFile = imported)
                                }
                                is AssetOpenResult.Failure -> error = opened.notice
                                AssetOpenResult.Cancelled -> Unit
                            }
                        }
                    }
                }) { Text(strings("analysis_import_table_file")) }
                EvolutionTableFormat.entries.forEach { format ->
                    val current = table
                    val pagedSource = page?.source
                    val canExport = current != null || (pagedSource != null && selectPagedExport != null)
                    TextButton(enabled = !busy && canExport, onClick = {
                        if (current != null) {
                            val source = tableFile
                            run { cancel ->
                                val asset = source?.export(current, format, cancel)
                                    ?: EvolutionTruthTableFile.write(current, format, cancel)
                                AnalysisPanelResult(export = asset)
                            }
                        } else if (pagedSource != null && selectPagedExport != null) {
                            run(cancelledKey = Messages.AnalysisExportIncomplete) { cancel ->
                                exportPagedAnalysis(pagedSource, format, selectPagedExport, cancel)
                                AnalysisPanelResult()
                            }
                        }
                    }) { Text(strings("analysis_export_table_file", "format" to format.name)) }
                }
            }
        }
        error?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
        if (selectedView == AnalysisView.EXPRESSIONS) {
            OutlinedTextField(expressionText, { expressionText = it }, label = { Text(strings(Messages.AnalysisAssignmentHelp)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp), enabled = !busy, maxLines = 5)
            Button(enabled = !busy, onClick = {
                val text = expressionText
                run { cancel ->
                    val assignments = text.lines().filter { it.isNotBlank() }.map { BooleanExpressionParser.parseAssignment(it) }
                    requireLocalized(assignments.map { it.output }.distinct().size == assignments.size) { message(Messages.AnalysisDistinctOutputs) }
                    AnalysisPanelResult(table = TruthTable.fromExpressions(assignments.associate { it.output to it.expression },
                        limits = limits, cancelled = cancel))
                }
            }) { Text(strings(Messages.AnalysisExpressionsTable)) }
        } else if (table == null && page == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(inputText, { inputText = it }, label = { Text(strings(Messages.AnalysisInputList)) }, modifier = Modifier.width(240.dp))
                OutlinedTextField(outputText, { outputText = it }, label = { Text(strings(Messages.AnalysisOutputList)) }, modifier = Modifier.width(240.dp))
            }
            Button(enabled = !busy, onClick = {
                val inputs = inputText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val outputs = outputText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                run {
                    requireLocalized(inputs.size <= limits.maxInputBits) { LocalizedText(Messages.AnalysisMaxInputs, quantity = limits.maxInputBits.toLong()) }
                    requireLocalized(outputs.size <= limits.maxOutputBits) { LocalizedText(Messages.AnalysisMaxOutputs, quantity = limits.maxOutputBits.toLong()) }
                    AnalysisPanelResult(table = TruthTable(inputs, outputs, List(1 shl inputs.size) { List(outputs.size) { TruthCell.ZERO } }))
                }
            }) { Text(strings(Messages.AnalysisEmptyTable)) }
        }
        page?.let { current ->
            when (selectedView) {
                AnalysisView.TABLE -> PagedAnalysisTable(
                    current, busy,
                    onPage = { number -> run { cancel -> AnalysisPanelResult(page = current.source.analysisPage(number, cancel)) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                AnalysisView.KARNAUGH -> Text(strings(Messages.AnalysisKarnaughReduce))
                AnalysisView.EXPRESSIONS -> Unit
            }
        }
        table?.let { current ->
            editingColumn?.let { column ->
                AnalysisColumnDialog(
                    names = if (column.input) current.inputs else current.outputs,
                    index = column.index,
                    input = column.input,
                    onDismiss = { editingColumn = null },
                    onApply = { name, destination ->
                        if (column.input) {
                            table = current.withInputColumn(column.index, name, destination)
                        } else {
                            val selectedName = if (outputIndex == column.index) name else current.outputs[outputIndex]
                            val updated = current.withOutputColumn(column.index, name, destination)
                            table = updated
                            outputIndex = updated.outputs.indexOf(selectedName)
                        }
                        forms = emptyList()
                        editingColumn = null
                    },
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { form = if (form == NormalForm.SOP) NormalForm.POS else NormalForm.SOP; forms = emptyList() }) {
                    Text(if (form == NormalForm.SOP) strings(Messages.AnalysisSop) else strings(Messages.AnalysisPos))
                }
                Button(enabled = !busy, onClick = {
                    run { cancel -> AnalysisPanelResult(forms = current.outputs.indices.map { BooleanMinimizer.minimize(current, it, form, limits, cancel) }) }
                }) { Text(strings(Messages.AnalysisSimplify)) }
                FilterChip(selected = nandOnly, enabled = !busy, onClick = { nandOnly = !nandOnly },
                    label = { Text(strings(Messages.AnalysisNandOnly)) })
                Button(enabled = !busy, onClick = {
                    val generateNandOnly = nandOnly
                    run { cancel ->
                        val generated = AnalysisCircuitGenerator.generate(current,
                            strings(Messages.AnalysisGeneratedName, "name" to circuitName), form, limits,
                            nandOnly = generateNandOnly, cancelled = cancel)
                        requireLocalized(AnalysisCircuitGenerator.verify(current, generated, limits, cancel)) { message(Messages.AnalysisGeneratedMismatch) }
                        AnalysisPanelResult(generated = generated)
                    }
                }) { Text(strings(Messages.AnalysisGenerate)) }
            }
            forms.forEachIndexed { index, reduced ->
                Text("${current.outputs[index]} = ${reduced.expression.format()}", style = MaterialTheme.typography.bodySmall)
            }
            when (selectedView) {
                AnalysisView.TABLE -> {
                    Text(strings(characters.truthTableHelp()),
                        style = MaterialTheme.typography.bodySmall)
                    val clipboard = LocalClipboardManager.current
                    var selecting by remember(current.inputs, current.outputs) { mutableStateOf(false) }
                    var block by remember(current.inputs, current.outputs) { mutableStateOf<TruthTableBlock?>(null) }
                    var awaitingCorner by remember(current.inputs, current.outputs) { mutableStateOf(false) }
                    // TableTabCaret: the cursor cell, the marked block and the rows a multi-row change touched.
                    var cursor by remember(current.inputs, current.outputs) { mutableStateOf<TruthTableCellPosition?>(null) }
                    var highlightRows by remember(current.inputs, current.outputs) { mutableStateOf(emptySet<Int>()) }

                    fun selectCell(row: Int, column: Int) {
                        val position = TruthTableCellPosition(row, column)
                        val previous = block
                        block = if (awaitingCorner && previous != null) TruthTableBlock(previous.first, position)
                            else TruthTableBlock(position)
                        awaitingCorner = !awaitingCorner
                        cursor = position
                        highlightRows = emptySet()
                    }
                    val marked = block?.takeIf { it.first != it.last }
                    fun cellBackground(row: Int, column: Int): Color? = when {
                        cursor == TruthTableCellPosition(row, column) -> colors.themeColor(EditorColor.TABLE_CURSOR)
                        marked?.contains(row, column) == true -> colors.themeColor(EditorColor.TABLE_SELECTION)
                        row in highlightRows -> colors.themeColor(EditorColor.TABLE_HIGHLIGHT)
                        else -> null
                    }

                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            selecting = !selecting
                            block = null
                            awaitingCorner = false
                        }) { Text(strings(if (selecting) Messages.AnalysisEndSelection else Messages.AnalysisSelectBlock)) }
                        TextButton(enabled = !busy && block != null, onClick = {
                            block?.let { clipboard.setText(AnnotatedString(current.copyTsv(it, characters))) }
                        }) { Text(strings(Messages.AnalysisCopyTsv)) }
                        TextButton(enabled = !busy && block != null, onClick = {
                            block?.let { selectedBlock ->
                                try {
                                    val pasted = current.pasteTsv(clipboard.getText()?.text.orEmpty(), selectedBlock, characters)
                                    highlightRows = pasted.rows.indices.filter { pasted.rows[it] != current.rows[it] }.toSet()
                                    table = pasted
                                    forms = emptyList()
                                    error = null
                                } catch (failure: IllegalArgumentException) {
                                    error = failure.localizedDescription(message(Messages.AnalysisFailed))
                                }
                            }
                        }) { Text(strings(Messages.AnalysisPasteTsv)) }
                    }
                    if (selecting) Text(strings(Messages.AnalysisSelectBlockHelp),
                        style = MaterialTheme.typography.bodySmall)
                    val scroll = rememberScrollState()
                    Row(Modifier.horizontalScroll(scroll)) {
                        current.inputs.forEachIndexed { index, name ->
                            AnalysisCell(name, header = true,
                                action = if (busy) null else ({ editingColumn = AnalysisColumn(input = true, index = index) }),
                                description = strings(Messages.AnalysisEditInputColumn, "name" to name))
                        }
                        current.outputs.forEachIndexed { index, name ->
                            AnalysisCell(name, header = true,
                                action = if (busy) null else ({ editingColumn = AnalysisColumn(input = false, index = index) }),
                                description = strings(Messages.AnalysisEditOutputColumn, "name" to name))
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(current.rows.size, key = { it }) { row ->
                            Row(Modifier.horizontalScroll(scroll)) {
                                current.inputs.indices.forEach { input ->
                                    val value = (if (current.input(row, input)) TruthCell.ONE else TruthCell.ZERO).display(characters)
                                    AnalysisCell(value,
                                        action = if (selecting && !busy) ({ selectCell(row, input) }) else null,
                                        description = strings(Messages.AnalysisA11yInputCell, "row" to row,
                                            "input" to current.inputs[input], "value" to value),
                                        clipboardSelected = if (selecting) block?.contains(row, input) == true else null,
                                        background = cellBackground(row, input),
                                        cursor = cursor == TruthTableCellPosition(row, input),
                                        tag = "analysis-cell-$row-$input")
                                }
                                current.outputs.indices.forEach { output ->
                                    val column = current.inputs.size + output
                                    val value = current.rows[row][output].display(characters)
                                    AnalysisCell(value, action = if (busy) null else ({
                                        if (selecting) selectCell(row, column) else {
                                            edit(row, output)
                                            cursor = TruthTableCellPosition(row, column)
                                            highlightRows = emptySet()
                                        }
                                    }), description = strings(Messages.AnalysisA11yTableCell, "row" to row,
                                        "output" to current.outputs[output], "value" to value),
                                        clipboardSelected = if (selecting) block?.contains(row, column) == true else null,
                                        background = cellBackground(row, column),
                                        cursor = cursor == TruthTableCellPosition(row, column),
                                        tag = "analysis-cell-$row-$column")
                                }
                            }
                        }
                    }
                }
                AnalysisView.EXPRESSIONS -> Unit
                AnalysisView.KARNAUGH -> {
                    if (current.inputs.size > 6) Text(strings(Messages.AnalysisKarnaughReduce))
                    else {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            current.outputs.forEachIndexed { index, output -> TextButton(onClick = { outputIndex = index }) { Text(output) } }
                        }
                        val map = KarnaughMap.from(current, outputIndex, forms.getOrNull(outputIndex))
                        val preferenceAccess = LocalEditorPreferences.current
                        var localLined by remember { mutableStateOf(false) }
                        val lined = preferenceAccess?.preferences?.kmapLinedStyle ?: localLined
                        KarnaughStyleChoice(lined) { value ->
                            if (preferenceAccess != null) preferenceAccess.onChange(preferenceAccess.preferences.copy(kmapLinedStyle = value))
                            else localLined = value
                        }
                        Text(strings(Messages.AnalysisKarnaughAxes, "rows" to map.rowVariables.joinToString(), "columns" to map.columnVariables.joinToString()),
                            style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.horizontalScroll(rememberScrollState()).testTag(if (lined) "kmap-lined" else "kmap-numbered")) {
                            if (lined) KarnaughLinedColumnHeaders(map)
                            else Row { AnalysisCell("", height = KarnaughCellHeight); map.columnLabels.forEach { AnalysisCell(it, header = true, height = KarnaughCellHeight) } }
                            Row {
                                Column {
                                    map.rowLabels.indices.forEach { row ->
                                        if (lined) Row { KarnaughLinedRowHeader(map, row) }
                                        else AnalysisCell(map.rowLabels[row], header = true, height = KarnaughCellHeight)
                                    }
                                }
                                KarnaughCells(map, colors, characters) { cell ->
                                    edit(cell.truthRow, outputIndex)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun importSelectedAnalysisTable(
    files: AnalysisTableFiles,
    cancelled: () -> Boolean,
): AnalysisPanelResult {
    val selected = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<AnalysisTableSelection> { continuation ->
            files.select { result ->
                continuation.resume(result) { _, late, _ -> (late as? AnalysisTableSelection.Opened)?.input?.close() }
            }
        }
    }
    val input = when (selected) {
        is AnalysisTableSelection.Opened -> selected.input
        is AnalysisTableSelection.Failure -> throw LocalizedArgumentException(selected.notice)
        AnalysisTableSelection.Cancelled -> return AnalysisPanelResult()
    }
    val imported = try {
        input.withReadAccess {
            EvolutionTruthTableFile.importLines(input.name, input.lines(cancelled), { size ->
                BufferedAnalysisTableSpool(size, files.createSpoolFile())
            }, cancelled)
        }
    } finally {
        input.close()
    }
    return when (imported) {
        is ImportedAnalysisTable.Editable -> AnalysisPanelResult(table = imported.file.table, tableFile = imported.file)
        is ImportedAnalysisTable.Paged -> try {
            AnalysisPanelResult(page = imported.table.analysisPage(0, cancelled), ownsPage = true)
        } catch (failure: Throwable) {
            imported.table.close()
            throw failure
        }
    }
}

@Composable
internal fun AnalysisCell(
    text: String,
    header: Boolean = false,
    action: (() -> Unit)? = null,
    description: String = text,
    clipboardSelected: Boolean? = null,
    /** A TableTabCaret or K-map fill; transparent lets the K-map groups show through. */
    background: Color? = null,
    /** TableTabCaret.paintForeground: a 2-pixel border in COMPONENT_COLOR around the cursor. */
    cursor: Boolean = false,
    textColor: Color? = null,
    height: Dp? = null,
    tag: String? = null,
) {
    val dark = LocalEditorDarkTheme.current
    val colors = analysisColors()
    Text(text, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        color = textColor ?: if (dark) Color(0xFFDDDDDD) else Color.Black,
        modifier = Modifier.width(AnalysisCellWidth).then(if (height == null) Modifier.heightIn(min = 48.dp) else Modifier.height(height))
            .background(background ?: when {
                header -> if (dark) Color(0xFF45494A) else Color(0xFFE9E9E9)
                else -> if (dark) Color(0xFF2B2B2B) else Color.White
            })
            .border(1.dp, if (dark) Color(0xFF555555) else Color(0xFFD0D0D0))
            .then(if (cursor) Modifier.border(2.dp, colors.themeColor(EditorColor.COMPONENT)) else Modifier)
            .then(if (action == null) Modifier else Modifier.clickable(onClick = action))
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .semantics {
                contentDescription = description
                if (clipboardSelected != null) selected = clipboardSelected
            }.padding(8.dp))
}

/** The editor's theme colors, or the built-in ones of the theme outside the editor. */
@Composable
internal fun analysisColors(): Map<EditorColor, Int> {
    val dark = LocalEditorDarkTheme.current
    return LocalEditorPreferences.current?.preferences?.effectiveColors(dark) ?: if (dark) DarkEditorColors else emptyMap()
}

private val AnalysisCellWidth = 56.dp
private val KarnaughCellHeight = 48.dp
private val KarnaughGroupInset = 4.dp
private val KarnaughGroupRadius = 5.dp

/**
 * KarnaughMapPanel.doPaintKMap: the cells, the group rectangles (KarnaughMapGroups.paint with the
 * KMAP1..16 colors) and the entries in KMAP_CELL_TEXT_COLOR over them.
 */
@Composable
private fun KarnaughCells(
    map: KarnaughMap,
    colors: Map<EditorColor, Int>,
    characters: ValueCharacters,
    onEdit: (KarnaughCell) -> Unit,
) {
    val strings = rememberLocalizer()
    val dark = LocalEditorDarkTheme.current
    val groups = map.groups
    val fills = groups.map { group -> karnaughGroupFill(colors.themeColor(EditorColor.kmapGroups[group.colorIndex]), dark) }
    val textColor = colors.themeColor(EditorColor.KMAP_CELL_TEXT)
    Column(Modifier.background(if (dark) Color(0xFF2B2B2B) else Color.White).drawBehind {
        val width = AnalysisCellWidth.toPx()
        val height = KarnaughCellHeight.toPx()
        val inset = KarnaughGroupInset.toPx()
        val radius = KarnaughGroupRadius.toPx()
        groups.forEachIndexed { index, group ->
            for (area in group.areas) drawRoundRect(fills[index],
                Offset(area.column * width + inset, area.row * height + inset),
                Size(area.width * width - 2 * inset, area.height * height - 2 * inset), CornerRadius(radius, radius))
        }
    }.testTag("kmap-cells").semantics { contentDescription = strings("analysis_kmap_groups", "count" to groups.size) }) {
        map.cells.forEachIndexed { row, cells ->
            Row {
                cells.forEach { cell ->
                    val value = cell.value.display(characters)
                    AnalysisCell(value, action = { onEdit(cell) }, background = Color.Transparent, textColor = textColor,
                        height = KarnaughCellHeight,
                        description = strings(Messages.AnalysisA11yKarnaughCell, "row" to row, "column" to cell.column, "value" to value))
                }
            }
        }
    }
}

/** KMapGroupInfo.adjustColor for an unhighlighted group: alpha 128, or brightened with alpha 180 when dark. */
internal fun karnaughGroupFill(color: Color, dark: Boolean): Color {
    if (!dark) return color.copy(alpha = 128 / 255f)
    fun brighten(component: Float): Int {
        val value = kotlin.math.round(component * 255).toInt()
        return minOf(255, (value + (255 - value) * 0.45f).toInt())
    }
    return Color(brighten(color.red), brighten(color.green), brighten(color.blue), 180)
}

/** MinimizedTab's style selector: Evolution's numbered (Gray-code) or lined K-map headers. */
@Composable
private fun KarnaughStyleChoice(lined: Boolean, onChange: (Boolean) -> Unit) {
    val strings = rememberLocalizer()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings("analysis_kmap_style"), style = MaterialTheme.typography.bodySmall)
        FilterChip(selected = !lined, onClick = { onChange(false) }, label = { Text(strings("analysis_kmap_numbered")) },
            modifier = Modifier.testTag("kmap-style-numbered"))
        FilterChip(selected = lined, onClick = { onChange(true) }, label = { Text(strings("analysis_kmap_lined")) },
            modifier = Modifier.testTag("kmap-style-lined"))
    }
}

private val KarnaughLineWidth = 2.dp
private val KarnaughLineCell = 24.dp

/** Lined header (KarnaughMapPanel.drawLinedHeader): one bar per column variable over the columns where it is 1. */
@Composable
private fun KarnaughLinedColumnHeaders(map: KarnaughMap) {
    val ink = if (LocalEditorDarkTheme.current) Color(0xFFDDDDDD) else Color.Black
    map.columnVariables.forEachIndexed { variable, name ->
        Row {
            Box(Modifier.width(KarnaughLineCell * map.rowVariables.size.coerceAtLeast(1)))
            map.columnLabels.forEachIndexed { column, label ->
                val on = label.getOrNull(variable) == '1'
                val first = on && map.columnLabels.getOrNull(column - 1)?.getOrNull(variable) != '1'
                Box(Modifier.width(56.dp).height(KarnaughLineCell).semantics {
                    contentDescription = if (on) name else ""
                }, contentAlignment = Alignment.BottomCenter) {
                    if (first) Text(name, color = ink, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp))
                    if (on) Box(Modifier.fillMaxWidth().height(KarnaughLineWidth).background(ink))
                }
            }
        }
    }
}

/** Lined header for one row: one vertical bar per row variable that is 1 in that row. */
@Composable
private fun KarnaughLinedRowHeader(map: KarnaughMap, row: Int) {
    val ink = if (LocalEditorDarkTheme.current) Color(0xFFDDDDDD) else Color.Black
    if (map.rowVariables.isEmpty()) Box(Modifier.width(KarnaughLineCell))
    map.rowVariables.forEachIndexed { variable, name ->
        val on = map.rowLabels[row].getOrNull(variable) == '1'
        val first = on && map.rowLabels.getOrNull(row - 1)?.getOrNull(variable) != '1'
        Box(Modifier.width(KarnaughLineCell).heightIn(min = 48.dp).height(48.dp).semantics { contentDescription = if (on) name else "" },
            contentAlignment = Alignment.CenterEnd) {
            if (first) Text(name, color = ink, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart))
            if (on) Box(Modifier.width(KarnaughLineWidth).fillMaxHeight().background(ink))
        }
    }
}
