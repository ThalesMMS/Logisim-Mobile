package dev.logisim.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.AnalysisCircuitGenerator
import dev.logisim.mobile.core.AnalysisLimitException
import dev.logisim.mobile.core.AnalysisLimits
import dev.logisim.mobile.core.BooleanExpression
import dev.logisim.mobile.core.BooleanExpressionParser
import dev.logisim.mobile.core.BooleanImplicant
import dev.logisim.mobile.core.BooleanMinimizer
import dev.logisim.mobile.core.BooleanOperator
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

internal enum class AnalysisView(val titleKey: String) {
    TABLE(Messages.AnalysisTable), EXPRESSIONS(Messages.AnalysisExpressions), KARNAUGH(Messages.AnalysisKarnaugh),
}

private data class AnalysisColumn(val input: Boolean, val index: Int)

private data class AnalysisPanelResult(
    val table: TruthTable? = null,
    /** The table changes the current one (expressions applied to it) instead of replacing it. */
    val edit: Boolean = false,
    /** The table came from the Expressions text. */
    val typedExpressions: Boolean = false,
    /** The circuit a table was read from, named in the generated circuit. */
    val circuit: String? = null,
    val page: AnalysisTablePage? = null,
    val forms: List<MinimizedBooleanFunction>? = null,
    /** The notation [forms] were minimized in; a change of notation during the run asks for them again. */
    val formsFor: NormalForm = NormalForm.SOP,
    val generated: CircuitDocument? = null,
    val tableFile: EvolutionTruthTableFile? = null,
    val export: DocumentAsset? = null,
    val ownsPage: Boolean = false,
)

private const val MaxHistory = 50

/** Cells (rows × outputs) of snapshots the history keeps: a 12-input table with many outputs keeps fewer steps. */
private const val MaxHistoryCells = 1 shl 20

/** Expressions are filled in from tables up to this many inputs: minimized up to the exact limit, canonical above it. */
internal const val MaxDerivedExpressionInputs = 8
private const val DefaultInputs = "A, B"
private const val DefaultOutputs = "Y"
private const val SampleExpressions = "Y := A & B"

/**
 * One analysis per document, kept by the tool while its sheet is closed, as Evolution keeps one Analyzer window
 * per project (AnalyzerManager). [bind] starts over when the document changes and [close] releases an imported
 * table's spool file. Running work and pending file pickers belong to the sheet, not to the session.
 *
 * Evolution's analyzer has no undo; on touch screens a stray tap replaces or overwrites a table, so table edits
 * and replacements keep a bounded history of immutable snapshots. None of this is saved in the .circ file.
 */
internal class AnalysisSession(private val historyCells: Int = MaxHistoryCells) {
    /** One step of the history: the table with what made it unedited and where it came from. */
    private class Snapshot(
        val table: TruthTable,
        val baseline: TruthTable?,
        val tableFile: EvolutionTruthTableFile?,
        val sourceCircuit: String?,
    )

    var documentIdentity by mutableStateOf<Long?>(null)
        private set
    var table by mutableStateOf<TruthTable?>(null)
        private set
    var tableFile by mutableStateOf<EvolutionTruthTableFile?>(null)
        private set
    var page by mutableStateOf<AnalysisTablePage?>(null)
        private set
    var retainedImport: ImportedPagedTruthTable? = null
        private set
    var sourceCircuit: String? = null
        private set
    var forms by mutableStateOf<List<MinimizedBooleanFunction>>(emptyList())
    /**
     * Shown forms are being minimized again in a new notation: the SOP/POS control stays in place, selected, until
     * they arrive, and a run cancelled meanwhile leaves it there. A table change ends it, as it ends the forms.
     */
    var formsPending by mutableStateOf(false)
        private set
    /** TableTabCaret types 0, 1 or x into the cell; null cycles 0, 1, x as a tap did before. */
    var tapValue by mutableStateOf<TruthCell?>(null)
    var inputText by mutableStateOf(DefaultInputs)
    var outputText by mutableStateOf(DefaultOutputs)
    var expressionText by mutableStateOf(SampleExpressions)
    /** The user typed in or chose the expressions; until then they are derived from the table. */
    var expressionsEdited by mutableStateOf(false)
    var expressionsHint by mutableStateOf<LocalizedText?>(null)
    internal var derivedFrom: Pair<TruthTable, NormalForm>? = null
    var selectedView by mutableStateOf(AnalysisView.TABLE)
    var outputIndex by mutableStateOf(0)
    var form by mutableStateOf(NormalForm.SOP)
    var nandOnly by mutableStateOf(false)
    var error by mutableStateOf<LocalizedText?>(null)
    var notice by mutableStateOf<LocalizedText?>(null)

    // TableTabCaret: the selection mode, the marked block, the cursor cell and the rows a multi-row change touched.
    var selecting by mutableStateOf(false)
    var block by mutableStateOf<TruthTableBlock?>(null)
    var awaitingCorner by mutableStateOf(false)
    var cursor by mutableStateOf<TruthTableCellPosition?>(null)
    var highlightRows by mutableStateOf(emptySet<Int>())

    private var baseline: TruthTable? = null
    private val undoStack = mutableStateListOf<Snapshot>()
    private val redoStack = mutableStateListOf<Snapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val historySize: Int get() = undoStack.size

    /**
     * The table differs from the one it was read, imported or created as: a cell turned back is not an edit, and
     * Undo brings back each table with its own baseline.
     */
    val dirty: Boolean get() = table.let { it != null && it != baseline }
    val selectedOutput: String? get() = table?.outputs?.getOrNull(outputIndex)

    fun bind(identity: Long) {
        if (documentIdentity == identity) return
        reset()
        documentIdentity = identity
    }

    fun reset() {
        close()
        documentIdentity = null
        page = null
        table = null
        tableFile = null
        baseline = null
        sourceCircuit = null
        clearForms()
        tapValue = null
        inputText = DefaultInputs
        outputText = DefaultOutputs
        expressionText = SampleExpressions
        expressionsEdited = false
        expressionsHint = null
        derivedFrom = null
        selectedView = AnalysisView.TABLE
        outputIndex = 0
        form = NormalForm.SOP
        nandOnly = false
        error = null
        notice = null
        clearSelection()
        undoStack.clear()
        redoStack.clear()
    }

    /** Release the imported table's spool; a page reading from it goes with it. */
    fun close() {
        val imported = retainedImport ?: return
        retainedImport = null
        if (page?.source === imported) page = null
        imported.close()
    }

    /** A cell edit or paste. */
    fun edit(next: TruthTable) = commit(next)

    /**
     * MinimizedTab's notation. Minimized forms of the old notation go at once, so neither the list, the copied
     * text nor the K-map groups show them while the new ones are computed. Returns whether they were shown (or
     * still pending from an earlier change), in which case [formsPending] keeps the notation control in place.
     */
    fun changeForm(next: NormalForm): Boolean {
        val shown = forms.isNotEmpty() || formsPending
        form = next
        forms = emptyList()
        formsPending = shown
        return shown
    }

    /** Minimized forms arrived: they are shown only in the current notation. Returns whether they were. */
    fun formsReady(next: List<MinimizedBooleanFunction>, notation: NormalForm): Boolean {
        if (notation != form) return false
        forms = next
        formsPending = false
        return true
    }

    private fun clearForms() {
        forms = emptyList()
        formsPending = false
    }

    /** A column edit, an added or removed variable, or expressions applied to the table. */
    fun editStructure(next: TruthTable, followOutput: String? = selectedOutput) {
        commit(next)
        structureChanged(followOutput)
    }

    /** A table read from the circuit, imported from a file or created from the form: the new unedited table. */
    fun load(next: TruthTable, file: EvolutionTruthTableFile? = null, circuit: String? = null) {
        table?.let(::pushHistory)
        redoStack.clear()
        dropPage()
        table = next
        baseline = next
        tableFile = file
        sourceCircuit = circuit
        clearForms()
        notice = null
        expressionsEdited = false
        expressionsHint = null
        derivedFrom = null
        clearSelection()
        outputIndex = 0
    }

    /** A page of a read-only table too large to edit. Undo returns to the editable table it replaced. */
    fun loadPage(next: AnalysisTablePage) {
        table?.let(::pushHistory)
        redoStack.clear()
        val imported = next.source as? ImportedPagedTruthTable
        if (retainedImport !== imported) {
            retainedImport?.close()
            retainedImport = imported
        }
        table = null
        baseline = null
        tableFile = null
        sourceCircuit = null
        page = next
        clearForms()
        // A paged table has no expressions to show: neither the sample nor the replaced table's text stays.
        expressionText = ""
        expressionsEdited = false
        expressionsHint = null
        derivedFrom = null
        clearSelection()
        outputIndex = 0
        selectedView = AnalysisView.TABLE
    }

    /** Back to the input and output form, filled in with the current names. Undo restores the table. */
    fun newTable() {
        val names = table?.let { it.inputs to it.outputs } ?: page?.source?.let { it.inputs to it.outputs }
        names?.let { (inputs, outputs) ->
            inputText = inputs.joinToString(", ")
            outputText = outputs.joinToString(", ")
        }
        table?.let(::pushHistory)
        redoStack.clear()
        dropPage()
        table = null
        baseline = null
        tableFile = null
        sourceCircuit = null
        clearForms()
        notice = null
        error = null
        expressionsEdited = false
        derivedFrom = null
        clearSelection()
        outputIndex = 0
        selectedView = AnalysisView.TABLE
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        snapshot()?.let(redoStack::add) ?: dropPage()
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        snapshot()?.let(undoStack::add)
        restore(next)
    }

    fun clearSelection() {
        selecting = false
        block = null
        awaitingCorner = false
        cursor = null
        highlightRows = emptySet()
    }

    private fun dropPage() {
        if (page == null) return
        close()
        page = null
    }

    private fun commit(next: TruthTable) {
        table?.let(::pushHistory)
        redoStack.clear()
        table = next
        clearForms()
        notice = null
    }

    private fun snapshot(): Snapshot? = table?.let { Snapshot(it, baseline, tableFile, sourceCircuit) }

    private fun restore(snapshot: Snapshot) {
        val previous = table
        val followOutput = selectedOutput
        val next = snapshot.table
        table = next
        baseline = snapshot.baseline
        tableFile = snapshot.tableFile
        sourceCircuit = snapshot.sourceCircuit
        clearForms()
        notice = null
        error = null
        if (previous == null || previous.inputs != next.inputs || previous.outputs != next.outputs) structureChanged(followOutput)
    }

    private fun structureChanged(followOutput: String?) {
        clearSelection()
        val current = table
        // An imported file describes its own variable layout; a different layout is exported from the table.
        tableFile = tableFile?.takeIf { current != null && it.table.inputs == current.inputs && it.table.outputs == current.outputs }
        outputIndex = if (current == null) 0
            else current.outputs.indexOf(followOutput).takeIf { it >= 0 } ?: outputIndex.coerceIn(0, current.outputs.lastIndex)
    }

    /** Called before [table] and [baseline] change, so the step keeps the baseline of the table it saves. */
    private fun pushHistory(previous: TruthTable) {
        undoStack.add(Snapshot(previous, baseline, tableFile, sourceCircuit))
        val limit = (historyCells / maxOf(1, previous.rows.size * previous.outputs.size)).coerceIn(1, MaxHistory)
        while (undoStack.size > limit) undoStack.removeAt(0)
    }
}

/** An output name as BooleanExpressionParser.parseAssignment reads it back, backtick-quoted like Variable.format. */
internal fun analysisOutputName(name: String): String =
    if (name.matches(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")) && name.uppercase() !in setOf("AND", "OR", "XOR", "NOT", "EQUALS")) name
    else "`${name.replace("`", "``")}`"

/** The expression without the parentheses BooleanExpression.Binary.format puts around the whole of it. */
private fun BooleanExpression.formatAssigned(): String = format().let { if (this is BooleanExpression.Binary) it.substring(1, it.length - 1) else it }

/** `<output> := <expression>` lines, one per output, that parse back with BooleanExpressionParser. */
internal fun analysisAssignments(outputs: List<String>, expressions: List<BooleanExpression>): String =
    outputs.zip(expressions).joinToString("\n") { (output, expression) -> "${analysisOutputName(output)} := ${expression.formatAssigned()}" }

/**
 * The table as expressions (ExpressionTab): minimized up to [AnalysisLimits.maxMinimizationInputBits] inputs, the
 * canonical sum of products (or product of sums) up to [MaxDerivedExpressionInputs], or null for a larger table or
 * one whose canonical form exceeds the parser's limits ([derivedExpressionsHint] says which). A canonical form
 * leaves don't-care rows out: they evaluate to 0 in a sum of products and to 1 in a product of sums.
 */
internal fun TruthTable.derivedExpressions(
    form: NormalForm = NormalForm.SOP,
    limits: AnalysisLimits = AnalysisLimits(),
    cancelled: () -> Boolean = { false },
): String? {
    if (inputs.size > MaxDerivedExpressionInputs) return null
    val expressions = outputs.indices.map { output ->
        if (cancelled()) throw AnalysisCancelledException()
        if (inputs.size <= limits.maxMinimizationInputBits) {
            try {
                BooleanMinimizer.minimize(this, output, form, limits, cancelled).expression
            } catch (_: AnalysisLimitException) {
                canonical(output, form)
            }
        } else canonical(output, form)
    }
    val text = analysisAssignments(outputs, expressions)
    val names = inputs.toSet()
    return try {
        text.lines().forEach { BooleanExpressionParser.parseAssignment(it, names) }
        text
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Why [derivedExpressions] filled nothing in: too many inputs, or expressions too long to edit as text. */
internal fun TruthTable.derivedExpressionsHint(): LocalizedText =
    message(if (inputs.size > MaxDerivedExpressionInputs) "analysis_expressions_too_large" else "analysis_expressions_too_long")

private fun TruthTable.canonical(output: Int, form: NormalForm): BooleanExpression {
    val wanted = if (form == NormalForm.SOP) TruthCell.ONE else TruthCell.ZERO
    val terms = rows.indices.filter { rows[it][output] == wanted }.map { BooleanImplicant(it, 0, inputs.size).expression(inputs, form) }
    val operator = if (form == NormalForm.SOP) BooleanOperator.OR else BooleanOperator.AND
    return terms.reduceOrNull { a, b -> BooleanExpression.Binary(operator, a, b) } ?: BooleanExpression.Constant(form == NormalForm.POS)
}

/**
 * Apply `<output> := <expression>` lines. Without a table they make a new one whose inputs are the variables, in
 * the order they appear. With a table they replace only the outputs they define and append new ones, keeping the
 * other outputs and the input order. As Evolution's ExpressionTab Parser (badVariableName), a name that is not an
 * input of the table is refused, so a typo or an output name does not add an input and double the rows; without a
 * table, an output the lines assign cannot also be one of their variables.
 */
internal fun applyAnalysisExpressions(
    current: TruthTable?,
    text: String,
    limits: AnalysisLimits = AnalysisLimits(),
    cancelled: () -> Boolean = { false },
): TruthTable {
    val names = current?.inputs?.toSet().orEmpty()
    val assignments = text.lines().filter { it.isNotBlank() }.map { BooleanExpressionParser.parseAssignment(it, names) }
    requireLocalized(assignments.map { it.output }.distinct().size == assignments.size) { message(Messages.AnalysisDistinctOutputs) }
    val assigned = assignments.mapTo(mutableSetOf()) { it.output }
    assignments.flatMap { it.expression.variables() }.firstOrNull { if (current != null) it !in names else it in assigned }?.let { name ->
        throw LocalizedArgumentException(message("analysis_bad_variable_name", "name" to name))
    }
    // Evolution's ExpressionTab cannot add outputs at all; here an output must not take an input's name, or the
    // table would have an input and an output A, and Generate two pins labelled A.
    assignments.firstOrNull { it.output in names }?.let { assignment ->
        throw LocalizedArgumentException(message("analysis_output_is_input", "name" to assignment.output))
    }
    val expressions = assignments.associate { it.output to it.expression }
    if (current == null) return TruthTable.fromExpressions(expressions, limits = limits, cancelled = cancelled)
    val defined = TruthTable.fromExpressions(expressions, inputs = current.inputs, limits = limits, cancelled = cancelled)
    return current.withOutputsReplaced(defined, limits)
}

/** A free name for a new column: the next letter for inputs, Y, Z, W... for outputs. */
internal fun nextAnalysisName(used: Collection<String>, input: Boolean): String {
    val letters = if (input) ('A'..'Z').map { it.toString() } else listOf("Y", "Z", "W", "V", "U", "T", "S", "R", "Q", "P")
    return letters.firstOrNull { it !in used }
        ?: generateSequence(2) { it + 1 }.map { (if (input) "X" else "Y") + it }.first { it !in used }
}

/** [onGenerated] must add or replace the circuit through the editor's normal history command. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnalyzePanel(
    session: AnalysisSession,
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
    var filePending by remember { mutableStateOf(false) }
    var editingColumn by remember { mutableStateOf<AnalysisColumn?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    var pendingReplace by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showLimits by remember { mutableStateOf(false) }
    // A notation change that arrived while a run was finishing: the forms are minimized again once it ends. A run
    // resumes after withContext(Dispatchers.Default) on a worker thread, so its forms can be on screen (and the
    // control tapped) before its finally clears `running`; run() then refuses new work, which used to drop the change.
    var reminimize by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val limits = remember { AnalysisLimits() }
    val busy = running != null || filePending
    val clipboard = LocalClipboardManager.current

    fun run(cancelledKey: String = Messages.AnalysisCancelled, work: suspend (() -> Boolean) -> AnalysisPanelResult) {
        if (running != null || filePending) return
        session.error = null
        session.notice = null
        // The session outlives this sheet: a run that ends after another document reset it reports nothing there.
        val identity = session.documentIdentity
        fun current() = session.documentIdentity == identity
        running = scope.launch {
            var stagedImport: ImportedPagedTruthTable? = null
            try {
                val job = currentCoroutineContext()[Job]!!
                val result = withContext(Dispatchers.Default) {
                    work { !job.isActive }.also { if (it.ownsPage) stagedImport = it.page?.source as? ImportedPagedTruthTable }
                }
                // The work may finish on a worker thread, and the run resumes there. Its results are applied at the
                // next frame, on the thread that composes and measures, so the table's lazy list never measures in
                // the middle of the change (a host test saw the forms in the session but never on screen).
                withFrameNanos { }
                if (!current()) return@launch
                result.table?.let {
                    if (result.edit) session.editStructure(it) else session.load(it, result.tableFile, result.circuit)
                    // The text the user typed stays; it is not replaced by the table's own expressions.
                    if (result.typedExpressions) session.expressionsEdited = true
                    editingColumn = null
                    if (result.tableFile != null) session.selectedView = AnalysisView.TABLE
                }
                result.page?.let {
                    session.loadPage(it)
                    stagedImport = null
                    editingColumn = null
                }
                // Forms of a notation the user left while they were computed are asked for again in the new one.
                result.forms?.let { if (!session.formsReady(it, result.formsFor)) reminimize = true }
                if (result.export != null && assets != null) {
                    filePending = true
                    assets.create(result.export) { saved ->
                        filePending = false
                        if (saved is AssetActionResult.Failure && current()) session.error = saved.notice
                    }
                }
                result.generated?.let(onGenerated)
            } catch (_: CancellationException) {
                reminimize = false
                if (current()) session.error = message(cancelledKey)
            } catch (_: AnalysisCancelledException) {
                reminimize = false
                if (current()) session.error = message(cancelledKey)
            } catch (failure: IllegalArgumentException) {
                reminimize = false
                if (current()) session.error = failure.localizedDescription(message(Messages.AnalysisFailed))
            } finally {
                stagedImport?.close()
                running = null
            }
        }
    }

    /**
     * Read circuit, Import and New table replace an edited table only after a confirmation. Apply to table changes
     * the current table, as a cell edit does, and does not ask.
     */
    fun confirmReplace(action: () -> Unit) {
        if (session.dirty) pendingReplace = action else action()
    }

    fun minimize(current: TruthTable) {
        val form = session.form
        run { cancel ->
            AnalysisPanelResult(forms = current.outputs.indices.map { BooleanMinimizer.minimize(current, it, form, limits, cancel) },
                formsFor = form)
        }
    }

    /**
     * The SOP/POS control in the table and in the K-map: shown forms are recomputed in the new notation. A change
     * that arrives while a run is still finishing is not dropped: the forms are minimized once that run ends.
     */
    fun changeForm(form: NormalForm) {
        val current = session.table
        if (!session.changeForm(form) || current == null) return
        if (running != null || filePending) reminimize = true else minimize(current)
    }
    LaunchedEffect(reminimize, busy) {
        if (!reminimize || busy) return@LaunchedEffect
        reminimize = false
        val current = session.table
        if (current != null && session.formsPending) minimize(current)
    }

    fun createTable() {
        val inputs = session.inputText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val outputs = session.outputText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        run {
            requireLocalized(inputs.size <= limits.maxInputBits) { LocalizedText(Messages.AnalysisMaxInputs, quantity = limits.maxInputBits.toLong()) }
            requireLocalized(outputs.size <= limits.maxOutputBits) { LocalizedText(Messages.AnalysisMaxOutputs, quantity = limits.maxOutputBits.toLong()) }
            AnalysisPanelResult(table = TruthTable(inputs, outputs, List(1 shl inputs.size) { List(outputs.size) { TruthCell.ZERO } }))
        }
    }

    fun structural(change: (TruthTable) -> TruthTable, followOutput: String? = session.selectedOutput) {
        val current = session.table ?: return
        try {
            session.editStructure(change(current), followOutput)
            session.error = null
        } catch (failure: IllegalArgumentException) {
            session.error = failure.localizedDescription(message(Messages.AnalysisFailed))
        }
        editingColumn = null
    }

    /** A tap writes the Tap sets value, as typing 0, 1 or x into TableTabCaret does, or cycles in Cycle mode. */
    fun edit(row: Int, output: Int) {
        if (busy) return
        val current = session.table ?: return
        val value = session.tapValue ?: current.rows[row][output].next()
        if (value != current.rows[row][output]) session.edit(current.withCell(row, output, value))
    }
    val characters = currentValueCharacters()
    val colors = analysisColors()
    val table = session.table
    val page = session.page

    // ExpressionTab: until the user edits them, the expressions follow the table. They are derived when the view
    // opens, off the main thread, and the work is cancelled when the table, the form or the view changes.
    LaunchedEffect(session.selectedView, table, session.form, session.expressionsEdited) {
        if (session.selectedView != AnalysisView.EXPRESSIONS || session.expressionsEdited || table == null) return@LaunchedEffect
        val key = table to session.form
        if (session.derivedFrom == key) return@LaunchedEffect
        val job = currentCoroutineContext()[Job]
        val derived = try {
            withContext(Dispatchers.Default) { table.derivedExpressions(key.second, limits) { job?.isActive == false } }
        } catch (_: AnalysisCancelledException) {
            return@LaunchedEffect
        }
        if (session.expressionsEdited) return@LaunchedEffect
        session.expressionText = derived.orEmpty()
        session.expressionsHint = if (derived == null) table.derivedExpressionsHint() else null
        session.derivedFrom = key
    }

    pendingReplace?.let { action ->
        AppAlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text(strings("analysis_replace_title")) },
            text = { Text(strings("analysis_replace_message")) },
            confirmButton = {
                TextButton(onClick = { pendingReplace = null; action() }) { Text(strings("analysis_replace_confirm")) }
            },
            dismissButton = { TextButton(onClick = { pendingReplace = null }) { Text(strings(Messages.ActionCancel)) } },
        )
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The sheet header already names the tool. The views come first, as Evolution's analyzer tabs.
        AnalysisViewTabs(session.selectedView) {
            session.selectedView = it
            session.notice = null
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy, onClick = {
                val name = circuitName
                confirmReplace {
                    run { cancel ->
                        val source = CircuitAnalyzer.pagedTruthTable(
                            project, name, limits.copy(maxInputBits = 20), registry, simulatorFactory?.invoke(),
                        )
                        if (source.inputs.size <= limits.maxInputBits) {
                            AnalysisPanelResult(table = TruthTable(source.inputs, source.outputs, source.readRows(0, source.rowCount, cancel)),
                                circuit = name)
                        } else AnalysisPanelResult(page = source.analysisPage(0, cancel))
                    }
                }
            }) { Text(strings(Messages.AnalysisReadCircuit)) }
            if (running?.isActive == true) {
                TextButton(onClick = { running?.cancel() }) { Text(strings(Messages.ActionCancel)) }
            } else if (table != null) {
                OutlinedButton(enabled = !busy, onClick = { minimize(table) }) { Text(strings(Messages.AnalysisSimplify)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnalysisIconButton(EditorIcon.UNDO, strings("analysis_undo"), enabled = !busy && session.canUndo) { session.undo() }
                AnalysisIconButton(EditorIcon.REDO, strings("analysis_redo"), enabled = !busy && session.canRedo) { session.redo() }
                IconButton(onClick = { showLimits = !showLimits }, modifier = Modifier.semantics {
                    contentDescription = strings("analysis_limits_info")
                    selected = showLimits
                }) { InfoGlyph() }
                AnalysisOverflowMenu(
                    session = session, busy = busy, assets = assets,
                    canExport = table != null || (page != null && selectPagedExport != null),
                    onNewTable = { confirmReplace { session.newTable(); editingColumn = null } },
                    onImport = {
                        confirmReplace {
                            val files = assets?.analysisTables
                            if (files != null) run { cancel -> importSelectedAnalysisTable(files, cancel) }
                            else if (assets != null) {
                                filePending = true
                                session.error = null
                                // A picker answer after another document reset the session belongs to no analysis.
                                val identity = session.documentIdentity
                                // Files providers may advertise CSV/TXT as generic data. Validate the file in the codec.
                                assets.open(listOf("application/octet-stream"), MAX_EVOLUTION_TABLE_FILE_BYTES) { opened ->
                                    filePending = false
                                    if (session.documentIdentity != identity) return@open
                                    when (opened) {
                                        is AssetOpenResult.Opened -> run { cancel ->
                                            val imported = EvolutionTruthTableFile.read(opened.asset, limits, cancel)
                                            AnalysisPanelResult(table = imported.table, tableFile = imported)
                                        }
                                        is AssetOpenResult.Failure -> session.error = opened.notice
                                        AssetOpenResult.Cancelled -> Unit
                                    }
                                }
                            }
                        }
                    },
                    onExport = { format ->
                        val current = session.table
                        val pagedSource = session.page?.source
                        if (current != null) {
                            val source = session.tableFile
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
                    },
                    onGenerate = {
                        val current = session.table
                        if (current != null) {
                            val generateNandOnly = session.nandOnly
                            val form = session.form
                            val name = strings(Messages.AnalysisGeneratedName, "name" to (session.sourceCircuit ?: circuitName))
                            run { cancel ->
                                val generated = AnalysisCircuitGenerator.generate(current, name, form, limits,
                                    nandOnly = generateNandOnly, cancelled = cancel)
                                requireLocalized(AnalysisCircuitGenerator.verify(current, generated, limits, cancel)) { message(Messages.AnalysisGeneratedMismatch) }
                                AnalysisPanelResult(generated = generated)
                            }
                        }
                    },
                )
            }
        }
        if (showLimits) Text(strings(Messages.AnalysisLimitsHelp), style = MaterialTheme.typography.bodySmall)
        // Registered with the tool sheet, so its banner does not repeat what is shown here (C74).
        AccessibleEditorNotice(session.error, isError = true)
        AccessibleEditorNotice(session.notice, Modifier.testTag("analysis-notice"))

        table?.let { current ->
            editingColumn?.let { column ->
                AnalysisColumnDialog(
                    names = if (column.input) current.inputs else current.outputs,
                    index = column.index,
                    input = column.input,
                    canInsert = if (column.input) current.inputs.size < limits.maxInputBits else current.outputs.size < limits.maxOutputBits,
                    canDelete = column.input || current.outputs.size > 1,
                    onDismiss = { editingColumn = null },
                    onApply = { name, destination ->
                        if (column.input) structural({ it.withInputColumn(column.index, name, destination) })
                        else {
                            val follow = if (session.outputIndex == column.index) name else session.selectedOutput
                            structural({ it.withOutputColumn(column.index, name, destination) }, follow)
                        }
                    },
                    onInsert = { after ->
                        val used = current.inputs + current.outputs
                        val at = column.index + if (after) 1 else 0
                        if (column.input) structural({ it.withInputAdded(nextAnalysisName(used, input = true), at, limits) })
                        else structural({ it.withOutputAdded(nextAnalysisName(used, input = false), at, limits) })
                    },
                    onDelete = {
                        if (column.input) structural({ it.withInputRemoved(column.index) })
                        else structural({ it.withOutputRemoved(column.index) })
                    },
                )
            }
        }

        when (session.selectedView) {
            AnalysisView.TABLE -> when {
                page != null -> PagedAnalysisTable(
                    page, busy,
                    onPage = { number -> run { cancel -> AnalysisPanelResult(page = page.source.analysisPage(number, cancel)) } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                table == null -> AnalysisCreateForm(session, busy, Modifier.weight(1f).fillMaxWidth(), onCreate = ::createTable)
                else -> AnalysisTableView(session, table, busy, characters, colors, Modifier.weight(1f).fillMaxWidth(),
                    onEditColumn = { input, index -> editingColumn = AnalysisColumn(input, index) },
                    onEdit = ::edit,
                    onForm = ::changeForm,
                    onCopy = { text, notice ->
                        clipboard.setText(AnnotatedString(text))
                        session.notice = notice
                    },
                    readClipboard = { clipboard.getText()?.text.orEmpty() },
                    onUseExpressions = { text ->
                        session.expressionText = text
                        session.expressionsEdited = true
                        session.expressionsHint = null
                        session.selectedView = AnalysisView.EXPRESSIONS
                    },
                )
            }
            // A read-only paged table (more than 12 inputs) has no expressions to edit, and applying typed ones would
            // replace it and close its spool: the view says so, as the Karnaugh view does, and offers no field.
            AnalysisView.EXPRESSIONS -> if (page != null) {
                Text(strings.plural("analysis_expressions_paged", page.source.inputs.size.toLong()),
                    style = MaterialTheme.typography.bodyMedium)
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.expressionsHint?.takeIf { !session.expressionsEdited }?.let {
                    Text(strings(it), style = MaterialTheme.typography.bodySmall)
                }
                // The field keeps its cursor and selection, so the operator row inserts where the user is typing.
                var field by remember { mutableStateOf(TextFieldValue(session.expressionText)) }
                if (field.text != session.expressionText) {
                    field = TextFieldValue(session.expressionText, TextRange(session.expressionText.length))
                }
                fun edit(next: TextFieldValue) {
                    field = next
                    if (next.text != session.expressionText) {
                        session.expressionText = next.text
                        session.expressionsEdited = true
                    }
                }
                OutlinedTextField(field, ::edit, label = { Text(strings(Messages.AnalysisAssignmentHelp)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp), enabled = !busy, maxLines = 8,
                    // Variable names and operators, not prose: no capitals or corrections.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii))
                AnalysisOperatorRow(enabled = !busy) { symbol ->
                    val start = minOf(field.selection.start, field.selection.end)
                    val end = maxOf(field.selection.start, field.selection.end)
                    edit(TextFieldValue(field.text.substring(0, start) + symbol + field.text.substring(end), TextRange(start + symbol.length)))
                }
                if (table != null) Text(strings("analysis_expressions_merge_help"), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Unedited text is the table itself; applying it would only turn don't-cares into 0 or 1.
                    Button(enabled = !busy && session.expressionText.isNotBlank() && (table == null || session.expressionsEdited),
                        onClick = {
                            val text = session.expressionText
                            val current = session.table
                            // An edit of the current table (or a new one), not a replacement: one Undo away, no question.
                            run { cancel ->
                                AnalysisPanelResult(table = applyAnalysisExpressions(current, text, limits, cancel), edit = current != null,
                                    typedExpressions = true)
                            }
                        }) { Text(strings(if (table == null) Messages.AnalysisExpressionsTable else "analysis_expressions_apply")) }
                    if (table != null && session.expressionsEdited) TextButton(enabled = !busy, onClick = {
                        session.derivedFrom = null
                        session.expressionsEdited = false
                    }) { Text(strings("analysis_expressions_reset")) }
                }
            }
            AnalysisView.KARNAUGH -> when {
                // A read-only paged table cannot lose inputs; an editable one can, from its column headings.
                page != null -> Text(strings(Messages.AnalysisKarnaughLimit))
                // As in the Table view: a map needs a table, so the view offers to make one.
                table == null -> AnalysisCreateForm(session, busy, Modifier.weight(1f).fillMaxWidth(), onCreate = ::createTable)
                table.inputs.size > 6 -> Text(strings("analysis_karnaugh_reduce_delete"))
                else -> AnalysisKarnaughView(session, table, busy, characters, colors, Modifier.weight(1f).fillMaxWidth(),
                    onEdit = ::edit, onForm = ::changeForm)
            }
        }
    }
}

private val NameKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/** The symbols the operator row inserts, with the name TalkBack reads for each; all parse with BooleanExpressionParser. */
internal val AnalysisOperators = listOf(
    "&" to "analysis_insert_and", "|" to "analysis_insert_or", "^" to "analysis_insert_xor", "~" to "analysis_insert_not",
    "(" to "analysis_insert_open", ")" to "analysis_insert_close", "0" to "analysis_insert_value", "1" to "analysis_insert_value",
)

/**
 * The operators and constants one tap away under the expression field: an ASCII keyboard keeps & | ^ ~ on its symbol
 * pages. Each button is 48 dp and is read by its name ("Insert AND"), not by the symbol.
 */
@Composable
private fun AnalysisOperatorRow(enabled: Boolean, onInsert: (String) -> Unit) {
    val strings = rememberLocalizer()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnalysisOperators.forEach { (symbol, key) ->
            val description = strings(key, "value" to symbol)
            // Not focusable: a tap leaves the field focused, with its keyboard open and its selection in place.
            OutlinedButton(onClick = { onInsert(symbol) }, enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).focusProperties { canFocus = false }
                    .semantics { contentDescription = description },
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(symbol, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisViewTabs(selected: AnalysisView, onSelect: (AnalysisView) -> Unit) {
    val strings = rememberLocalizer()
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = Color.Transparent) {
        AnalysisView.entries.forEach { view ->
            // Narrow side padding: three labels fit a 360 dp phone without clipping "Expressions".
            Tab(selected = selected == view, onClick = { onSelect(view) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(view.titleKey), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }
        }
    }
}

/** The disabled IconButton already dims its content color, so the glyph draws that color as it is. */
@Composable
private fun AnalysisIconButton(icon: EditorIcon, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        EditorIconGlyph(icon, Modifier.size(22.dp), tint = LocalContentColor.current)
    }
}

/** The input and output names and Create empty table, in the Table and Karnaugh views when there is no table. */
@Composable
private fun AnalysisCreateForm(session: AnalysisSession, busy: Boolean, modifier: Modifier, onCreate: () -> Unit) {
    val strings = rememberLocalizer()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Stacked full-width fields: neither hides off-screen to the right on a phone.
        OutlinedTextField(session.inputText, { session.inputText = it }, label = { Text(strings(Messages.AnalysisInputList)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = NameKeyboard)
        OutlinedTextField(session.outputText, { session.outputText = it }, label = { Text(strings(Messages.AnalysisOutputList)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = NameKeyboard)
        Button(enabled = !busy, onClick = onCreate) { Text(strings(Messages.AnalysisEmptyTable)) }
    }
}

/** A circled "i": the analysis limits are one tap away instead of a paragraph above the table. */
@Composable
private fun InfoGlyph() {
    val ink = LocalContentColor.current
    Box(Modifier.size(22.dp).border(1.6.dp, ink, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text("i", color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Import, Export, Generate and NAND-only: used less often than Read circuit and Minimize. */
@Composable
private fun AnalysisOverflowMenu(
    session: AnalysisSession,
    busy: Boolean,
    assets: DocumentAssetActions?,
    canExport: Boolean,
    onNewTable: () -> Unit,
    onImport: () -> Unit,
    onExport: (EvolutionTableFormat) -> Unit,
    onGenerate: () -> Unit,
) {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    Box {
        AnalysisIconButton(EditorIcon.MORE, strings("analysis_more_actions"), enabled = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(strings("analysis_new_table")) }, enabled = !busy && (session.table != null || session.page != null),
                onClick = { open = false; onNewTable() })
            if (assets != null) {
                DropdownMenuItem(text = { Text(strings("analysis_import_table_file")) }, enabled = !busy,
                    onClick = { open = false; onImport() })
                EvolutionTableFormat.entries.forEach { format ->
                    DropdownMenuItem(text = { Text(strings("analysis_export_table_file", "format" to format.name)) },
                        enabled = !busy && canExport, onClick = { open = false; onExport(format) })
                }
            }
            DropdownMenuItem(text = { Text(strings(Messages.AnalysisGenerate)) }, enabled = !busy && session.table != null,
                onClick = { open = false; onGenerate() })
            // The item is the checkbox (the box itself has no toggle of its own): TalkBack reads its role and
            // state, and announces the change while the menu stays open.
            DropdownMenuItem(text = { Text(strings(Messages.AnalysisNandOnly)) }, enabled = !busy,
                leadingIcon = { Checkbox(checked = session.nandOnly, onCheckedChange = null) },
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(session.nandOnly)
                },
                onClick = { session.nandOnly = !session.nandOnly })
        }
    }
}

/** Evolution's MinimizedTab notation: sum of products or product of sums, as one single-choice control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalFormChoice(form: NormalForm, enabled: Boolean, modifier: Modifier = Modifier, onChange: (NormalForm) -> Unit) {
    val strings = rememberLocalizer()
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(NormalForm.SOP to Messages.AnalysisSop, NormalForm.POS to Messages.AnalysisPos).forEachIndexed { index, (value, key) ->
            SegmentedButton(selected = form == value, enabled = enabled, onClick = { if (form != value) onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp)) {
                // One line: the segments take the labels' width instead of wrapping them word by word.
                Text(strings(key), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** MinimizedTab's expressions: selectable, copied as assignments, or used as the Expressions text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinimizedForms(
    table: TruthTable,
    forms: List<MinimizedBooleanFunction>,
    onCopy: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    val strings = rememberLocalizer()
    val assignments = analysisAssignments(table.outputs, forms.map { it.expression })
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(strings("analysis_minimized_title"), style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Column {
                forms.forEachIndexed { index, reduced ->
                    Text("${table.outputs[index]} = ${reduced.expression.formatAssigned()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onCopy(assignments) }) { Text(strings("analysis_copy_expressions")) }
            TextButton(onClick = { onUse(assignments) }) { Text(strings("analysis_use_as_expressions")) }
        }
    }
}

/**
 * The truth table as one lazy list: the help, the minimized expressions and the selection actions scroll away,
 * and the column headings stay on top, sharing the rows' horizontal scroll.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AnalysisTableView(
    session: AnalysisSession,
    current: TruthTable,
    busy: Boolean,
    characters: ValueCharacters,
    colors: Map<EditorColor, Int>,
    modifier: Modifier,
    onEditColumn: (input: Boolean, index: Int) -> Unit,
    onEdit: (row: Int, output: Int) -> Unit,
    onForm: (NormalForm) -> Unit,
    onCopy: (text: String, notice: LocalizedText) -> Unit,
    readClipboard: () -> String,
    onUseExpressions: (String) -> Unit,
) {
    val strings = rememberLocalizer()
    val selecting = session.selecting
    val block = session.block

    fun selectCell(row: Int, column: Int) {
        val position = TruthTableCellPosition(row, column)
        val previous = block
        // A first corner starts a new block; the second one completes it.
        session.block = if (session.awaitingCorner && previous != null) TruthTableBlock(previous.first, position)
            else TruthTableBlock(position)
        session.awaitingCorner = !session.awaitingCorner
        session.cursor = position
        session.highlightRows = emptySet()
    }
    val marked = block?.takeIf { it.first != it.last }
    val cursor = session.cursor
    val highlightRows = session.highlightRows
    fun cellBackground(row: Int, column: Int): Color? = when {
        cursor == TruthTableCellPosition(row, column) -> colors.themeColor(EditorColor.TABLE_CURSOR)
        marked?.contains(row, column) == true -> colors.themeColor(EditorColor.TABLE_SELECTION)
        row in highlightRows -> colors.themeColor(EditorColor.TABLE_HIGHLIGHT)
        else -> null
    }
    val scroll = rememberScrollState()
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerLow // the tool sheet's container
    val widths = rememberAnalysisColumnWidths(current.inputs + current.outputs)

    LazyColumn(modifier.testTag("analysis-table")) {
        item(key = "help") {
            // The instructions say what a tap does now: cycle, or write the value chosen in "Tap sets".
            val tapValue = session.tapValue
            Text(if (tapValue == null) strings(characters.truthTableHelp()) else strings("analysis_table_help_sets", "value" to
                if (tapValue == TruthCell.DONT_CARE) strings("analysis_dont_care_value", "name" to strings("analysis_dont_care_name"),
                    "character" to tapValue.display(characters)) else tapValue.display(characters)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        val forms = session.forms
        // The control the user just tapped stays in place while the forms are minimized again in its notation.
        val formsShown = forms.size == current.outputs.size
        if (formsShown || session.formsPending) item(key = "forms") {
            Column {
                NormalFormChoice(session.form, enabled = true, modifier = Modifier.fillMaxWidth(), onChange = onForm)
                if (formsShown) MinimizedForms(current, forms, onCopy = { onCopy(it, message("analysis_expressions_copied")) }, onUse = onUseExpressions)
                else if (busy) Text(strings("analysis_forms_updating"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        item(key = "selection") {
            Column {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        // TableTabCaret keeps the marks until a new press: leaving selection mode keeps the block.
                        session.selecting = !selecting
                        session.awaitingCorner = false
                    }) { Text(strings(if (selecting) Messages.AnalysisEndSelection else Messages.AnalysisSelectBlock)) }
                    TextButton(enabled = !busy && block != null, onClick = {
                        block?.let { onCopy(current.copyTsv(it, characters), LocalizedText("analysis_copied_rows", quantity = it.rows.count().toLong())) }
                    }) { Text(strings(Messages.AnalysisCopyTsv)) }
                    TextButton(enabled = !busy && block != null, onClick = {
                        block?.let { selectedBlock ->
                            try {
                                val pasted = current.pasteTsv(readClipboard(), selectedBlock, characters)
                                session.edit(pasted)
                                session.highlightRows = pasted.rows.indices.filter { pasted.rows[it] != current.rows[it] }.toSet()
                                session.error = null
                            } catch (failure: IllegalArgumentException) {
                                session.error = failure.localizedDescription(message(Messages.AnalysisFailed))
                            }
                        }
                    }) { Text(strings(Messages.AnalysisPasteTsv)) }
                    AnalysisTapModeChoice(session, characters, busy)
                }
                // TableTabCaret.doKey: typing 0, 1 or x fills every marked output cell. Input columns are left out.
                marked?.let { selected ->
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(TruthCell.ZERO, TruthCell.ONE, TruthCell.DONT_CARE).forEach { value ->
                            val shown = value.display(characters)
                            val name = if (value == TruthCell.DONT_CARE) strings("analysis_dont_care_name") else shown
                            TextButton(enabled = !busy && selected.columns.last >= current.inputs.size,
                                modifier = Modifier.testTag("analysis-fill-${value.name}").semantics {
                                    contentDescription = strings("analysis_fill_block_description", "value" to name)
                                },
                                onClick = {
                                    val filled = current.withBlock(selected.rows, selected.columns, value)
                                    if (filled != current) {
                                        session.edit(filled)
                                        session.highlightRows = filled.rows.indices.filter { filled.rows[it] != current.rows[it] }.toSet()
                                    }
                                    session.error = null
                                }) { Text(strings("analysis_fill_block", "value" to shown)) }
                        }
                    }
                }
                if (selecting) Text(strings(Messages.AnalysisSelectBlockHelp), style = MaterialTheme.typography.bodySmall)
            }
        }
        stickyHeader(key = "header") {
            Row(Modifier.fillMaxWidth().background(headerBackground).horizontalScroll(scroll)) {
                current.inputs.forEachIndexed { index, name ->
                    AnalysisCell(name, header = true, width = widths[index],
                        action = if (busy) null else ({ onEditColumn(true, index) }),
                        description = strings(Messages.AnalysisEditInputColumn, "name" to name))
                }
                current.outputs.forEachIndexed { index, name ->
                    AnalysisCell(name, header = true, width = widths[current.inputs.size + index],
                        action = if (busy) null else ({ onEditColumn(false, index) }),
                        description = strings(Messages.AnalysisEditOutputColumn, "name" to name))
                }
            }
        }
        val cornerLabel = strings("analysis_a11y_mark_corner")
        val changeLabel = strings("analysis_a11y_change_value")
        items(current.rows.size, key = { it }) { row ->
            // TalkBack hears the row as the page header counts it (from 1) and the input values, then the value.
            val assignment = analysisAssignmentText(current.inputs) { current.input(row, it) }
            Row(Modifier.horizontalScroll(scroll)) {
                current.inputs.indices.forEach { input ->
                    val value = (if (current.input(row, input)) TruthCell.ONE else TruthCell.ZERO).display(characters)
                    AnalysisCell(value, width = widths[input],
                        action = if (selecting && !busy) ({ selectCell(row, input) }) else null,
                        description = strings("analysis_a11y_input_row_cell", "row" to row + 1, "input" to current.inputs[input]),
                        stateDescription = value, clickLabel = cornerLabel,
                        clipboardSelected = if (selecting) block?.contains(row, input) == true else null,
                        background = cellBackground(row, input),
                        cursor = cursor == TruthTableCellPosition(row, input),
                        tag = "analysis-cell-$row-$input")
                }
                current.outputs.indices.forEach { output ->
                    val column = current.inputs.size + output
                    val value = current.rows[row][output].display(characters)
                    AnalysisCell(value, width = widths[column], action = if (busy) null else ({
                        if (selecting) selectCell(row, column) else {
                            onEdit(row, output)
                            session.cursor = TruthTableCellPosition(row, column)
                            session.highlightRows = emptySet()
                        }
                    }), description = strings("analysis_a11y_output_cell", "row" to row + 1, "assignment" to assignment,
                        "output" to current.outputs[output]),
                        stateDescription = value, clickLabel = if (selecting) cornerLabel else changeLabel,
                        clipboardSelected = if (selecting) block?.contains(row, column) == true else null,
                        background = cellBackground(row, column),
                        cursor = cursor == TruthTableCellPosition(row, column),
                        tag = "analysis-cell-$row-$column")
                }
            }
        }
    }
}

/**
 * KarnaughMapPanel with MinimizedTab's controls: which output (OutputSelector), the notation, the header style,
 * the map, and the selected output's minimized expression under it. The whole view scrolls, so a 6-input map's
 * last row is reachable in landscape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisKarnaughView(
    session: AnalysisSession,
    current: TruthTable,
    busy: Boolean,
    characters: ValueCharacters,
    colors: Map<EditorColor, Int>,
    modifier: Modifier,
    onEdit: (row: Int, output: Int) -> Unit,
    onForm: (NormalForm) -> Unit,
) {
    val strings = rememberLocalizer()
    val outputIndex = session.outputIndex.coerceIn(0, current.outputs.lastIndex)
    val output = current.outputs[outputIndex]
    Column(modifier.verticalScroll(rememberScrollState()).testTag("analysis-kmap-view"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnalysisOutputSelector(current.outputs, outputIndex) { session.outputIndex = it }
        val map = KarnaughMap.from(current, outputIndex, session.forms.getOrNull(outputIndex))
        val preferenceAccess = LocalEditorPreferences.current
        var localLined by remember { mutableStateOf(false) }
        val lined = preferenceAccess?.preferences?.kmapLinedStyle ?: localLined
        // One line in landscape, two on a phone: the map starts as high as the controls allow.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            NormalFormChoice(session.form, enabled = true, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(), onChange = onForm)
            KarnaughStyleChoice(lined) { value ->
                if (preferenceAccess != null) preferenceAccess.onChange(preferenceAccess.preferences.copy(kmapLinedStyle = value))
                else localLined = value
            }
            // A map cell is the same table cell: it takes the same Tap sets value.
            AnalysisTapModeChoice(session, characters, busy)
        }
        Text(strings(Messages.AnalysisKarnaughAxes, "rows" to map.rowVariables.joinToString(), "columns" to map.columnVariables.joinToString()),
            style = MaterialTheme.typography.bodySmall)
        Text(strings("analysis_karnaugh_output", "name" to output), style = MaterialTheme.typography.titleSmall)
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
                KarnaughCells(map, output, colors, characters) { cell ->
                    if (!busy) onEdit(cell.truthRow, outputIndex)
                }
            }
        }
        session.forms.getOrNull(outputIndex)?.takeIf { session.forms.size == current.outputs.size }?.let { reduced ->
            SelectionContainer {
                Text("$output = ${reduced.expression.formatAssigned()}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** OutputSelector: chips with a selected state for a few outputs, a labelled menu for many, nothing for one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AnalysisOutputSelector(outputs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val strings = rememberLocalizer()
    when {
        outputs.size <= 1 -> Unit
        outputs.size <= 4 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            outputs.forEachIndexed { index, output ->
                FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(output) },
                    modifier = Modifier.testTag("kmap-output-$index"))
            }
        }
        else -> {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(outputs[selected], {}, readOnly = true, singleLine = true,
                    label = { Text(strings("analysis_output_select")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    outputs.forEachIndexed { index, output ->
                        DropdownMenuItem(text = { Text(output) }, onClick = { onSelect(index); expanded = false },
                            modifier = Modifier.semantics { this.selected = index == selected })
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
    width: Dp = AnalysisCellWidth,
    /** The value TalkBack reads after [description], so the position is heard once and the new value after a change. */
    stateDescription: String? = null,
    /** What a double tap does ("Change value"), read after the value; only used with an [action]. */
    clickLabel: String? = null,
) {
    val dark = LocalEditorDarkTheme.current
    val colors = analysisColors()
    // One line: a name wider than its column is cut with an ellipsis, and the description keeps the full name.
    Text(text, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        color = textColor ?: if (dark) Color(0xFFDDDDDD) else Color.Black,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).then(if (height == null) Modifier.heightIn(min = 48.dp) else Modifier.height(height))
            .background(background ?: when {
                header -> if (dark) Color(0xFF45494A) else Color(0xFFE9E9E9)
                else -> if (dark) Color(0xFF2B2B2B) else Color.White
            })
            .border(1.dp, if (dark) Color(0xFF555555) else Color(0xFFD0D0D0))
            .then(if (cursor) Modifier.border(2.dp, colors.themeColor(EditorColor.COMPONENT)) else Modifier)
            .then(if (action == null) Modifier else Modifier.clickable(onClickLabel = clickLabel, onClick = action))
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .semantics {
                contentDescription = description
                if (stateDescription != null) this.stateDescription = stateDescription
                if (clipboardSelected != null) selected = clipboardSelected
            }.padding(8.dp))
}

/** `A=0 B=1 C=0`: the input values of one truth-table row, as TalkBack reads a cell's position. */
internal fun analysisAssignmentText(inputs: List<String>, value: (Int) -> Boolean): String =
    inputs.indices.joinToString(" ") { "${inputs[it]}=${if (value(it)) 1 else 0}" }

/**
 * Tap sets: what a tap writes into an output cell, in the table and in the map. Cycle keeps 0 → 1 → x; 0, 1 or x
 * writes that value, as typing it at TableTabCaret's cursor does. The labels follow the value characters. One
 * compact button that opens the four choices, so the selection actions and the mode share one line and the table
 * keeps its rows in landscape.
 */
@Composable
private fun AnalysisTapModeChoice(session: AnalysisSession, characters: ValueCharacters, busy: Boolean, modifier: Modifier = Modifier) {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    val options = listOf(null, TruthCell.ZERO, TruthCell.ONE, TruthCell.DONT_CARE)
    fun shown(value: TruthCell?) = value?.display(characters) ?: strings("analysis_tap_mode_cycle")
    // A don't-care character such as "-" is read as its name, not as punctuation.
    fun spoken(value: TruthCell?) = if (value == TruthCell.DONT_CARE) strings("analysis_dont_care_name") else shown(value)
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("analysis-tap-mode")
            .semantics { contentDescription = strings("analysis_tap_mode_value", "value" to spoken(session.tapValue)) }) {
            Text(strings("analysis_tap_mode_value", "value" to shown(session.tapValue)), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { value ->
                val chosen = session.tapValue == value
                DropdownMenuItem(text = { Text(shown(value), fontWeight = if (chosen) FontWeight.Bold else null) },
                    leadingIcon = { RadioButton(selected = chosen, onClick = null) },
                    onClick = { open = false; session.tapValue = value },
                    modifier = Modifier.testTag("analysis-tap-${value?.name ?: "CYCLE"}").semantics {
                        role = Role.RadioButton
                        selected = chosen
                        if (value == TruthCell.DONT_CARE) contentDescription = spoken(value)
                    })
            }
        }
    }
}

/** The editor's theme colors, or the built-in ones of the theme outside the editor. */
@Composable
internal fun analysisColors(): Map<EditorColor, Int> {
    val dark = LocalEditorDarkTheme.current
    return LocalEditorPreferences.current?.preferences?.effectiveColors(dark) ?: if (dark) DarkEditorColors else emptyMap()
}

private val AnalysisCellWidth = 56.dp
private val AnalysisMaxCellWidth = 160.dp

/**
 * One width per truth-table column: wide enough for its bold heading on one line (a name such as "Cout" no longer
 * breaks inside the word), at least the 56 dp cell and at most 160 dp, beyond which a long name wraps.
 */
@Composable
internal fun rememberAnalysisColumnWidths(names: List<String>): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)
    return remember(names, density, style, measurer) {
        names.map { name ->
            // 8 dp padding on each side and the 1 dp border, as AnalysisCell draws them.
            val text = with(density) { measurer.measure(name, style, softWrap = false, maxLines = 1).size.width.toDp() }
            (text + 18.dp).coerceIn(AnalysisCellWidth, AnalysisMaxCellWidth)
        }
    }
}
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
    output: String,
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
        val changeLabel = strings("analysis_a11y_change_value")
        map.cells.forEachIndexed { row, cells ->
            Row {
                cells.forEach { cell ->
                    val value = cell.value.display(characters)
                    // Grid positions in Gray order mean nothing without sight: the cell names its truth-table row
                    // and the values its row and column headings assign, then the value as its state.
                    val assignment = (map.rowVariables.mapIndexed { index, name -> "$name=${map.rowLabels[row][index]}" } +
                        map.columnVariables.mapIndexed { index, name -> "$name=${map.columnLabels[cell.column][index]}" }).joinToString(" ")
                    AnalysisCell(value, action = { onEdit(cell) }, background = Color.Transparent, textColor = textColor,
                        height = KarnaughCellHeight, tag = "kmap-cell-$row-${cell.column}",
                        description = strings("analysis_a11y_output_cell", "row" to cell.truthRow + 1, "assignment" to assignment,
                            "output" to output),
                        stateDescription = value, clickLabel = changeLabel)
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
