package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.DiagnosticTarget
import dev.logisim.mobile.core.FpgaBoardCodec
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardImportResult
import dev.logisim.mobile.core.FpgaBoardPin
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.FpgaCircuitReview
import dev.logisim.mobile.core.forBoard
import dev.logisim.mobile.core.mappingCodec
import dev.logisim.mobile.core.withFpgaTickFrequency
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.FpgaDesignArtifact
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaClockScale
import dev.logisim.mobile.core.FpgaDesignRules
import dev.logisim.mobile.core.FpgaDiagnosticSeverity
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.FpgaMapping
import dev.logisim.mobile.core.FpgaMappingCodec
import dev.logisim.mobile.core.FpgaMappingResult
import dev.logisim.mobile.core.companionBuildOperation
import dev.logisim.mobile.core.supportsCompanionBuild
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaPlanResult
import dev.logisim.mobile.core.FpgaProjectGenerator
import dev.logisim.mobile.core.FpgaScanDisplay
import dev.logisim.mobile.core.scannedDisplay
import dev.logisim.mobile.core.reptarBindings
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.FpgaVendor
import dev.logisim.mobile.core.HdlDiagnosticSeverity
import dev.logisim.mobile.core.supportsDerivedClock
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logisimmobile.composeapp.generated.resources.Res

internal data class FpgaClockInput(
    val enabled: Boolean = false,
    val frequency: String = "",
    val derived: Boolean = false,
    val multiplier: String = "8",
    val divider: String = "16",
) {
    val frequencyHz: Long?
        get() = frequency.trim().takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it in 1..1_000_000_000_000L }
    val scale: FpgaClockScale? get() = FpgaClockScale.parse(multiplier, divider)
    val valid: Boolean get() = !enabled || (frequencyHz != null && (!derived || scale != null))
}

private fun clockInput(frequencyHz: Long?, scale: FpgaClockScale?): FpgaClockInput =
    if (scale == null) FpgaClockInput(frequencyHz != null, frequencyHz?.toString().orEmpty())
    else FpgaClockInput(true, frequencyHz?.toString().orEmpty(), true, scale.multiplier, scale.divider)

internal fun FpgaDesignArtifact.withClockInputs(inputs: Map<String, FpgaClockInput>): FpgaDesignArtifact = copy(
    ports = ports.map { port ->
        val clock = inputs[port.name]
        if (port.direction != FpgaPinDirection.INPUT || port.width != 1 || clock == null) port
        else port.copy(
            clockFrequencyHz = if (clock.enabled) clock.frequencyHz ?: 0L else null,
            clockScale = if (clock.enabled && clock.derived) clock.scale ?: FpgaClockScale(0, 0) else null,
        )
    },
)

class FpgaMappingSession {
    internal val boardXml = mutableStateOf("")
    internal val board = mutableStateOf<FpgaBoardDefinition?>(null)
    internal val importNotices = mutableStateOf<List<LocalizedText>>(emptyList())
    internal val importFailed = mutableStateOf(false)
    internal val showXml = mutableStateOf(true)
    private var mappingKey: Triple<FpgaDesignArtifact?, FpgaBoardDefinition?, Long>? = null
    private val bindings = mutableStateOf<Map<FpgaSignalBit, FpgaPinBinding>>(emptyMap())
    private var flowBoard: FpgaBoardDefinition? = null
    private val flow = mutableStateOf<FpgaFlow?>(null)
    private var clockDesign: Pair<FpgaDesignArtifact?, Long>? = null
    private val clockInputs = mutableStateOf<Map<String, FpgaClockInput>>(emptyMap())

    internal fun bindingsFor(design: FpgaDesignArtifact?, board: FpgaBoardDefinition?, identity: Long = 0) = bindings.apply {
        val nextKey = Triple(design, board, identity)
        if (mappingKey != nextKey) {
            value = if (design != null && board != null) design.reptarBindings(board) else emptyMap()
            mappingKey = nextKey
        }
    }

    internal fun flowFor(board: FpgaBoardDefinition?) = flow.apply {
        if (flowBoard != board) { value = board?.let(::boardFlows)?.firstOrNull(); flowBoard = board }
    }

    internal fun clocksFor(design: FpgaDesignArtifact?, identity: Long = 0) = clockInputs.apply {
        val nextKey = design to identity
        if (clockDesign != nextKey) {
            value = design?.ports.orEmpty().filter { it.direction == FpgaPinDirection.INPUT && it.width == 1 }
                .associate { it.name to clockInput(it.clockFrequencyHz, it.clockScale) }
            clockDesign = nextKey
        }
    }
}

fun fpgaEditorTool(
    reviewProvider: (EditorToolContext) -> FpgaCircuitReview,
    onExport: ((FpgaBuildPlan) -> Unit)? = null,
    onSynthesize: ((FpgaBuildPlan) -> Unit)? = null,
    onPickBoard: (((String) -> Unit) -> Unit)? = null,
    companionClient: CompanionClient? = null,
    onArtifacts: ((List<CompanionArtifact>) -> Unit)? = null,
    mappingSession: FpgaMappingSession = FpgaMappingSession(),
    companionSession: FpgaCompanionSession = FpgaCompanionSession(),
    onPickMapping: (((String) -> Unit) -> Unit)? = null,
    boardCatalog: FpgaBoardCatalogStore = FpgaBoardCatalogStore(),
    boardAssets: DocumentAssetActions? = null,
    onPickBitstream: (((DocumentAsset) -> Unit) -> Unit)? = null,
): EditorTool {
    return EditorTool(id = "fpga", title = "FPGA", titleText = message("fpga_title")) { context ->
        val pending = companionSession.displayedPlan
        val imported = companionSession.importedBitstream
        if (imported != null && companionClient != null) {
            FpgaImportedProgramPanel(companionClient, imported, companionSession.program) {
                companionSession.program.clear()
                companionSession.importedBitstream = null
            }
        } else if (pending != null && companionClient != null) {
            FpgaCompanionPanel(pending, companionClient, onBack = { companionSession.displayedPlan = null },
                onArtifacts = onArtifacts, session = companionSession)
        } else {
            val synthesize: ((FpgaBuildPlan) -> Unit)? = onSynthesize ?: companionClient?.let {
                { plan -> companionSession.displayedPlan = plan }
            }
            val openBitstream: (() -> Unit)? = if (companionClient != null && onPickBitstream != null) {
                {
                    onPickBitstream { asset ->
                        if (context.isCurrentEditorContext() && !companionSession.retainsClient &&
                            companionSession.displayedPlan == null) {
                            companionSession.program.clear()
                            companionSession.importedBitstream = FpgaImportedBitstream.read(asset)
                        }
                    }
                }
            } else null
            key(context.project, context.document, reviewProvider) {
                val review by produceState<FpgaCircuitReview?>(null) {
                    value = withContext(Dispatchers.Default) { reviewProvider(context) }
                }
                val result = review
                if (result == null) {
                    Text(rememberLocalizer()("diagnostic_running"), modifier = Modifier.padding(16.dp))
                } else FpgaPanel(result.design, onExport, synthesize, onPickBoard, session = mappingSession, review = result,
                    boardCatalog = boardCatalog, boardAssets = boardAssets,
                    mappingCodecFor = remember(context.document) { { selected: FpgaCircuitReview -> selected.mappingCodec(context.document) } },
                    onPickMapping = onPickMapping,
                    documentIdentity = context.documentIdentity,
                    canApplyMapping = { context.isCurrentEditorContext() && context.projectExports.available },
                    onSaveMapping = { document ->
                        context.commitProject(context.project.withCircuit(document)) { saved ->
                            context.showNotice(message(if (saved) "fpga_map_saved" else "fpga_map_save_failed"))
                        }
                    },
                    onNavigate = { target ->
                        if (context.isCurrentEditorContext()) {
                            val path = when {
                                context.document.id == context.project.mainCircuit.id -> target.instancePath
                                context.activeInstancePath.isNotEmpty() -> context.activeInstancePath + target.instancePath
                                else -> emptyList()
                            }
                            context.navigate(target.editorLocation().copy(instancePath = path))
                            context.closeTool()
                        }
                    },
                    onOpenBitstream = openBitstream)
            }
        }
    }
}

/** Synthesis is an explicit host action. The panel never programs a physical device. */
@Composable
fun FpgaPanel(
    reviewedDesign: FpgaDesignArtifact?,
    onExport: ((FpgaBuildPlan) -> Unit)? = null,
    onSynthesize: ((FpgaBuildPlan) -> Unit)? = null,
    onPickBoard: (((String) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
    session: FpgaMappingSession = remember { FpgaMappingSession() },
    mappingCodec: FpgaMappingCodec? = null,
    /** Builds the codec for the review selected for the current board. Takes precedence over [mappingCodec]. */
    mappingCodecFor: ((FpgaCircuitReview) -> FpgaMappingCodec?)? = null,
    onPickMapping: (((String) -> Unit) -> Unit)? = null,
    documentIdentity: Long = 0,
    canApplyMapping: () -> Boolean = { true },
    onSaveMapping: ((CircuitDocument) -> Unit)? = null,
    boardCatalog: FpgaBoardCatalogStore = remember { FpgaBoardCatalogStore() },
    boardAssets: DocumentAssetActions? = null,
    review: FpgaCircuitReview? = null,
    onNavigate: ((DiagnosticTarget) -> Unit)? = null,
    onOpenBitstream: (() -> Unit)? = null,
) {
    val text = rememberLocalizer()
    var circuitChecked by remember(review) { mutableStateOf(false) }
    var boardXml by session.boardXml
    var board by session.board
    var importNotices by session.importNotices
    var importFailed by session.importFailed
    var showXml by session.showXml
    val scope = rememberCoroutineScope()
    var builtInNames by remember { mutableStateOf(emptyList<String>()) }
    var catalog by remember(boardCatalog) { mutableStateOf(FpgaBoardCatalog()) }
    var catalogBusy by remember(boardCatalog) { mutableStateOf(true) }
    var catalogNotice by remember(boardCatalog) { mutableStateOf<LocalizedText?>(null) }
    // Saved maps that bind a root Clock to a pin keep the physical input. Otherwise Clocks use the tick.
    val activeReview = remember(review, board) { review?.forBoard(board) }
    val design = if (review != null) activeReview?.design else reviewedDesign
    val codec = remember(activeReview, mappingCodec, mappingCodecFor) {
        if (activeReview != null && mappingCodecFor != null) mappingCodecFor(activeReview) else mappingCodec
    }
    val ticks = activeReview?.clockTicks
    var tickText by remember(ticks, documentIdentity) { mutableStateOf(ticks?.savedFrequencyHz?.let(::tickFrequencyText) ?: "1") }
    val tickHz = tickText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    var bindings by remember(design, board, documentIdentity) { session.bindingsFor(design, board, documentIdentity) }
    var flow by remember(board) { session.flowFor(board) }
    var clocks by remember(design, documentIdentity) { session.clocksFor(design, documentIdentity) }
    val effectiveDesign = remember(design, clocks) { design?.withClockInputs(clocks) }
    var validated by remember(effectiveDesign, board, bindings, flow) { mutableStateOf(false) }
    var actionNotices by remember(effectiveDesign, board, bindings, flow) { mutableStateOf<List<LocalizedText>>(emptyList()) }
    var mappingNotices by remember(design, board, documentIdentity) { mutableStateOf<List<LocalizedText>>(emptyList()) }
    val currentBoard = board
    var boardEditorOpen by remember { mutableStateOf(false) }
    var editedBoard by remember { mutableStateOf<FpgaBoardDefinition?>(null) }
    val currentFlow = flow
    val boardBitmap = rememberFpgaBoardBitmap(currentBoard?.originalXml?.takeIf { effectiveDesign != null })
    val scannedDisplays = remember(currentBoard) { currentBoard?.resources.orEmpty().mapNotNull { it.scannedDisplay() } }
    val diagnostics = remember(effectiveDesign, currentBoard, bindings, currentFlow) {
        if (effectiveDesign != null && currentBoard != null && currentFlow != null)
            FpgaDesignRules.check(effectiveDesign, currentBoard, bindings, currentFlow)
        else emptyList()
    }
    val errors = diagnostics.count { it.severity == FpgaDiagnosticSeverity.ERROR }
    val tickReady = ticks == null || (tickHz != null && currentBoard?.clock != null)
    val ready = !catalogBusy && effectiveDesign != null && currentBoard != null && currentFlow != null &&
        errors == 0 && clocks.values.all { it.valid } && tickReady
    val signals = remember(effectiveDesign) {
        effectiveDesign?.ports.orEmpty().asSequence().flatMap { port ->
            (0 until port.width.coerceIn(0, 4096)).asSequence().map { port to it }
        }.take(8192).toList()
    }

    fun displayBoard(result: FpgaBoardImportResult) {
        when (result) {
            is FpgaBoardImportResult.Success -> {
                board = result.board
                importNotices = result.warningNotices
                importFailed = false
                showXml = false
            }
            is FpgaBoardImportResult.Failure -> {
                importNotices = result.diagnosticNotices
                importFailed = true
            }
        }
    }

    fun applyMapping(result: FpgaMappingResult) {
        when (result) {
            is FpgaMappingResult.Loaded -> {
                bindings = result.mapping.bindings
                clocks = clocks.mapValues { (port, _) ->
                    val frequency = result.mapping.clocks[port]
                    clockInput(frequency, result.mapping.clockScales[port])
                }
                mappingNotices = emptyList()
            }
            is FpgaMappingResult.Saved -> {
                val hz = tickHz
                onSaveMapping?.invoke(if (ticks != null && hz != null) result.document.withFpgaTickFrequency(hz) else result.document)
                mappingNotices = emptyList()
            }
            is FpgaMappingResult.Failure -> mappingNotices = result.notices
        }
    }

    fun clearBoard() {
        board = null
        boardXml = ""
        importNotices = emptyList()
        importFailed = false
    }

    suspend fun readCatalogBoard(selection: String, entries: FpgaBoardCatalog): FpgaBoardImportResult {
        val name = selection.removePrefix(FpgaBoardCatalogStore.BUILTIN_PREFIX)
        val xml = entries.external[selection]?.xml ?: run {
            check(selection != name && name in builtInNames)
            Res.readBytes("files/fpga-boards/$name.xml").decodeToString(throwOnInvalidSequence = true)
        }
        return withContext(Dispatchers.Default) { FpgaBoardCodec.read(xml) }
    }

    suspend fun updateCatalog(action: suspend () -> Unit) {
        catalogNotice = null
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            catalogNotice = error.localizedDescription(message("fpga_board_catalog_failed"))
        } finally {
            catalogBusy = false
        }
    }

    fun changeCatalog(action: suspend () -> Unit) {
        if (catalogBusy) return
        catalogBusy = true
        scope.launch { updateCatalog(action) }
    }

    LaunchedEffect(boardCatalog) {
        updateCatalog {
            builtInNames = Res.readBytes("files/fpga-boards/index.txt").decodeToString().lineSequence()
                .filter(String::isNotBlank).toList()
            val names = builtInNames
            catalog = withContext(Dispatchers.Default) { boardCatalog.load(names) }
            val selection = catalog.selected
            if (selection != null) displayBoard(readCatalogBoard(selection, catalog))
            else if (board != null) clearBoard()
        }
    }

    fun importXml(xml: String) = changeCatalog {
        boardXml = xml
        val (result, savedCatalog) = withContext(Dispatchers.Default) {
            val imported = FpgaBoardCodec.read(xml)
            imported to (imported as? FpgaBoardImportResult.Success)?.let { boardCatalog.add(it.board) }
        }
        if (savedCatalog != null) catalog = savedCatalog
        displayBoard(result)
    }

    fun generate(action: (FpgaBuildPlan) -> Unit) {
        if (!ready || effectiveDesign == null || currentBoard == null || currentFlow == null) return
        validated = true
        when (val result = FpgaProjectGenerator.generate(effectiveDesign, currentBoard, bindings, currentFlow, ticks, tickHz ?: 1.0)) {
            is FpgaPlanResult.Success -> {
                action(result.plan)
                actionNotices = emptyList()
            }
            is FpgaPlanResult.Failure -> actionNotices = result.diagnostics.map { it.notice }
        }
    }

    if (boardEditorOpen && boardAssets != null) {
        FpgaBoardEditorPanel(editedBoard, boardAssets, onCancel = { boardEditorOpen = false }, onSaved = { xml ->
            boardEditorOpen = false
            importXml(xml)
        }, modifier = modifier)
        return
    }

    LazyColumn(modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text("fpga_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(text("fpga_setup_intro"))
                if (onOpenBitstream != null) {
                    OutlinedButton(onClick = onOpenBitstream, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_program_import"))
                    }
                }
                val builtInChoices = builtInNames.map { (FpgaBoardCatalogStore.BUILTIN_PREFIX + it) to it }
                val externalChoices = catalog.external.entries.sortedBy { it.value.name }.map { (id, entry) ->
                    id to text("fpga_board_catalog_external", "name" to entry.name)
                }
                val choices = builtInChoices + externalChoices
                FpgaChoice(text("fpga_board_catalog"), choices.firstOrNull { it.first == catalog.selected }?.second
                    ?: text("fpga_board_catalog_choose"), choices, enabled = !catalogBusy && choices.isNotEmpty()) { id ->
                    changeCatalog {
                        val result = readCatalogBoard(id, catalog)
                        if (result is FpgaBoardImportResult.Success) {
                            catalog = withContext(Dispatchers.Default) { boardCatalog.select(id) }
                            boardXml = catalog.external[id]?.xml.orEmpty()
                        }
                        displayBoard(result)
                    }
                }
                catalog.selected?.takeIf { it in catalog.external }?.let { id ->
                    TextButton(enabled = !catalogBusy, onClick = {
                        changeCatalog {
                            val names = builtInNames
                            catalog = withContext(Dispatchers.Default) { boardCatalog.remove(id, names) }
                            catalog.selected?.let { displayBoard(readCatalogBoard(it, catalog)); boardXml = "" } ?: clearBoard()
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text("fpga_board_catalog_remove"))
                    }
                }
                catalogNotice?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
                if (boardAssets != null) {
                    OutlinedButton(enabled = !catalogBusy, onClick = { editedBoard = null; boardEditorOpen = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_editor_new_board"))
                    }
                    if (currentBoard != null) {
                        OutlinedButton(enabled = !catalogBusy, onClick = { editedBoard = currentBoard; boardEditorOpen = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_editor_edit_board"))
                        }
                    }
                }
                if (review != null) {
                    OutlinedButton(onClick = { circuitChecked = !circuitChecked },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text(if (circuitChecked) "fpga_hide_circuit_drc" else "fpga_check_circuit_drc"))
                    }
                }
            }
        }
        if (review != null && circuitChecked) {
            item {
                Text(text("fpga_circuit_drc_heading"), modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                if (review.diagnostics.isEmpty()) Text(text("fpga_circuit_drc_clear"))
            }
            items(review.diagnostics) { diagnostic ->
                FpgaDiagnosticRow(diagnostic.severity, diagnostic.notice, text,
                    onNavigate?.let { { it(diagnostic.target) } })
            }
            if (review.hdlDiagnostics.isNotEmpty()) {
                item {
                    Text(text("fpga_hdl_diagnostics_heading"), modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                }
                items(review.hdlDiagnostics) { diagnostic ->
                    val severity = if (diagnostic.severity == HdlDiagnosticSeverity.ERROR)
                        FpgaDiagnosticSeverity.ERROR else FpgaDiagnosticSeverity.WARNING
                    val target = DiagnosticTarget(diagnostic.circuitId, componentIds = setOfNotNull(diagnostic.componentId))
                    FpgaDiagnosticRow(severity, diagnostic.notice, text, onNavigate?.let { { it(target) } })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onPickBoard != null) {
                    OutlinedButton(enabled = !catalogBusy, onClick = { onPickBoard(::importXml) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_import_board_file"))
                    }
                }
                TextButton(onClick = { showXml = !showXml }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showXml) text("fpga_hide_board_xml") else text("fpga_paste_board_xml"))
                }
                if (showXml) {
                    OutlinedTextField(boardXml, { boardXml = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                        label = { Text(text("fpga_board_xml_label")) },
                        minLines = 3, maxLines = 7, isError = importFailed)
                    Button(enabled = !catalogBusy && boardXml.isNotBlank(), onClick = { importXml(boardXml) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_read_board"))
                    }
                }
                importNotices.forEach { notice ->
                    Text(text(notice), color = if (importFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (currentBoard != null) {
                    Text(currentBoard.name, style = MaterialTheme.typography.titleMedium)
                    Text("${currentBoard.device.vendor} · ${currentBoard.device.family} · ${currentBoard.device.part} · ${currentBoard.device.packageName}")
                    Text(text.plural("fpga_board_pins", currentBoard.pins.size.toLong()))
                    currentBoard.clock?.let { clock ->
                        Text(text("fpga_board_clock", "pin" to clock.location, "frequency" to clock.frequencyHz))
                    }
                    val flows = boardFlows(currentBoard)
                    if (flows.isEmpty()) Text(text("fpga_vendor_unavailable"), color = MaterialTheme.colorScheme.error)
                    else FpgaChoice(text("fpga_flow"), currentFlow?.let { flowName(it, text) }.orEmpty(),
                        flows.map { it to flowName(it, text) }) { flow = it }
                }
                HorizontalDivider()
                if (design == null) {
                    Text(text("fpga_no_hdl"))
                } else {
                    Text("${design.topLevelName} · ${design.language}", style = MaterialTheme.typography.titleMedium)
                    Text(text.plural("fpga_physical_signals", design.ports.sumOf { it.width.toLong() }))
                    if (review?.clockTicks != null && ticks == null) Text(text("fpga_tick_physical_map"))
                    if (ticks != null) {
                        OutlinedTextField(tickText, { tickText = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(text("fpga_tick_frequency_input")) }, singleLine = true, isError = tickHz == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        val effectiveHz = if (tickHz != null && currentBoard != null) ticks.effectiveFrequencyHz(currentBoard, tickHz) else null
                        val cycles = if (tickHz != null && currentBoard != null) ticks.reloadCycles(currentBoard, tickHz) else null
                        when {
                            tickHz == null -> Text(text("fpga_tick_frequency_invalid"), color = MaterialTheme.colorScheme.error)
                            currentBoard != null && currentBoard.clock == null ->
                                Text(text("fpga_tick_no_oscillator"), color = MaterialTheme.colorScheme.error)
                            effectiveHz != null && cycles != null ->
                                Text(text("fpga_tick_effective", "frequency" to tickFrequencyText(effectiveHz), "cycles" to cycles))
                        }
                    }
                }
            }
        }
        if (currentBoard != null) {
            itemsIndexed(signals) { _, (port, bit) ->
                val signal = FpgaSignalBit(port.name, bit)
                FpgaSignalMapping(port, bit, bindings[signal], currentBoard, boardBitmap, text,
                    otherMapped = bindings.filterKeys { it != signal }.values
                        .mapNotNullTo(mutableSetOf()) { (it as? FpgaPinBinding.Physical)?.location?.lowercase() },
                    displays = scannedDisplays,
                    clockInput = clocks[port.name]?.takeIf { bit == 0 },
                    canDeriveClock = currentFlow?.let { currentBoard.supportsDerivedClock(it) } == true,
                    onClockChange = { clock ->
                        clocks = clocks + (port.name to clock)
                        if (clock.enabled && bindings[signal] is FpgaPinBinding.Constant) bindings = bindings - signal
                    },
                    onUseBoardClock = {
                        currentBoard.clock?.let { clock ->
                            val previous = clocks[port.name] ?: FpgaClockInput()
                            clocks = clocks + (port.name to previous.copy(enabled = true, frequency = clock.frequencyHz.toString()))
                            bindings = bindings + (signal to FpgaPinBinding.Physical(clock.location))
                        }
                    },
                    onMapDisplayBus = { binding ->
                        bindings = bindings + (0 until port.width).associate { channel ->
                            FpgaSignalBit(port.name, channel) to binding.copy(channel = binding.channel + channel)
                        }
                    },
                    onBinding = { binding ->
                        bindings = if (binding == null) bindings - signal else bindings + (signal to binding)
                    })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (design != null && currentBoard != null && currentFlow != null) {
                    if (codec != null) {
                        OutlinedButton(onClick = { applyMapping(codec.loadSaved(currentBoard, currentFlow)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_map_restore"))
                        }
                        if (onPickMapping != null) OutlinedButton(onClick = {
                            onPickMapping { xml ->
                                if (canApplyMapping() && session.board.value == currentBoard)
                                    applyMapping(codec.import(xml, currentBoard, currentFlow))
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_map_import"))
                        }
                        if (onSaveMapping != null) OutlinedButton(enabled = clocks.values.all { it.valid }, onClick = {
                            val frequencies = clocks.mapNotNull { (name, input) ->
                                input.frequencyHz?.takeIf { input.enabled }?.let { name to it }
                            }.toMap()
                            val scales = clocks.mapNotNull { (name, input) ->
                                input.scale?.takeIf { input.enabled && input.derived }?.let { name to it }
                            }.toMap()
                            applyMapping(codec.save(currentBoard, currentFlow, FpgaMapping(bindings, frequencies, scales)))
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text("fpga_map_save"))
                        }
                        mappingNotices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
                    }
                    OutlinedButton(onClick = { validated = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_validate_mapping"))
                    }
                    Text(if (ready) text("fpga_mapping_valid")
                        else text.plural("fpga_mapping_problems", errors.toLong()),
                        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (validated && diagnostics.isNotEmpty()) {
                        Text(text("fpga_pin_diagnostics_heading"), modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                        diagnostics.forEach { diagnostic ->
                            FpgaDiagnosticRow(diagnostic.severity, diagnostic.notice, text, pinCode = diagnostic.code)
                        }
                    }
                    if (onExport != null) Button(enabled = ready, onClick = { generate(onExport) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_export_project"))
                    }
                    val operation = currentFlow.companionBuildOperation(currentBoard)
                    if (onSynthesize != null && operation != null && currentBoard.supportsCompanionBuild(currentFlow)) {
                        Button(enabled = ready, onClick = { generate(onSynthesize) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text(if (operation == "fpga-synthesize") "fpga_prepare_synthesis" else "fpga_prepare_vendor_build"))
                        }
                    }
                }
                actionNotices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
                Text(text("fpga_synthesis_intro"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FpgaDiagnosticRow(
    severity: FpgaDiagnosticSeverity,
    notice: LocalizedText,
    text: Localizer,
    onNavigate: (() -> Unit)? = null,
    pinCode: String? = null,
) {
    val error = severity == FpgaDiagnosticSeverity.ERROR
    val label = text(if (error) "diagnostic_error" else "diagnostic_warning")
    val detail = "$label · ${text(notice)}"
    val caption = if (pinCode == null) detail else text("fpga_diagnostic", "code" to pinCode, "detail" to detail)
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    if (onNavigate == null) {
        Text(caption, color = color)
    } else {
        TextButton(onClick = onNavigate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(caption, color = color)
        }
    }
}

@Composable
private fun FpgaSignalMapping(
    port: FpgaDesignPort,
    bit: Int,
    binding: FpgaPinBinding?,
    board: FpgaBoardDefinition,
    boardBitmap: ImageBitmap?,
    text: Localizer,
    otherMapped: Set<String>,
    displays: List<FpgaScanDisplay>,
    clockInput: FpgaClockInput?,
    canDeriveClock: Boolean,
    onClockChange: (FpgaClockInput) -> Unit,
    onUseBoardClock: () -> Unit,
    onMapDisplayBus: (FpgaPinBinding.Display) -> Unit,
    onBinding: (FpgaPinBinding?) -> Unit,
) {
    val title = if (port.width == 1) port.name else "${port.name}[$bit]"
    val direction = when (port.direction) {
        FpgaPinDirection.INPUT -> text("fpga_direction_input")
        FpgaPinDirection.OUTPUT -> text("fpga_direction_output")
        FpgaPinDirection.INOUT -> text("fpga_direction_inout")
    }
    val options = buildList {
        add("unmapped" to text("fpga_unmapped"))
        add("physical" to text("fpga_physical_pin"))
        if (port.direction == FpgaPinDirection.OUTPUT && displays.isNotEmpty())
            add("display" to text("fpga_scan_display"))
        if (port.direction == FpgaPinDirection.INPUT && port.clockFrequencyHz == null) {
            add("zero" to text("fpga_constant_zero"))
            add("one" to text("fpga_constant_one"))
        }
        if (port.direction != FpgaPinDirection.INPUT) add("open" to text("fpga_open"))
    }
    val mode = when (binding) {
        null -> "unmapped"
        is FpgaPinBinding.Physical -> "physical"
        is FpgaPinBinding.Display -> "display"
        is FpgaPinBinding.Constant -> if (binding.high) "one" else "zero"
        FpgaPinBinding.Open -> "open"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title · $direction", style = MaterialTheme.typography.titleSmall)
        if (clockInput != null) {
            FpgaChoice(text("fpga_clock_role"), text(if (clockInput.enabled) "fpga_clock_role_clock" else "fpga_clock_role_signal"),
                listOf(false to text("fpga_clock_role_signal"), true to text("fpga_clock_role_clock"))) {
                onClockChange(clockInput.copy(enabled = it))
            }
            if (clockInput.enabled) {
                OutlinedTextField(clockInput.frequency, { onClockChange(clockInput.copy(frequency = it)) },
                    label = { Text(text("fpga_clock_frequency_input")) },
                    supportingText = { Text(text(if (clockInput.frequencyHz != null) "fpga_clock_frequency_help" else "fpga_clock_frequency_invalid")) },
                    isError = clockInput.frequencyHz == null, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                if (canDeriveClock || clockInput.derived) {
                    FpgaChoice(text("fpga_clock_source"),
                        text(if (clockInput.derived) "fpga_clock_derived" else "fpga_clock_direct"),
                        buildList {
                            add(false to text("fpga_clock_direct"))
                            if (canDeriveClock) add(true to text("fpga_clock_derived"))
                        }, onChoose = { onClockChange(clockInput.copy(derived = it)) })
                    if (clockInput.derived) {
                        OutlinedTextField(clockInput.multiplier, { onClockChange(clockInput.copy(multiplier = it)) },
                            label = { Text(text("fpga_clock_multiplier")) },
                            singleLine = true, isError = clockInput.scale == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        OutlinedTextField(clockInput.divider, { onClockChange(clockInput.copy(divider = it)) },
                            label = { Text(text("fpga_clock_divider")) },
                            singleLine = true, isError = clockInput.scale == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        val derived = clockInput.frequencyHz?.let { clockInput.scale?.outputFrequencyHz(it)?.roundToLong() }
                        if (derived != null) Text(text("fpga_clock_derived_frequency", "frequency" to derived))
                    }
                }
            }
            board.clock?.let { clock ->
                OutlinedButton(onClick = onUseBoardClock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_clock_use_board", "pin" to clock.location, "frequency" to clock.frequencyHz))
                }
            }
        }
        port.clockFrequencyHz?.takeIf { clockInput?.valid != false }?.let {
            Text(text("fpga_signal_clock", "frequency" to it), style = MaterialTheme.typography.bodySmall)
        }
        FpgaChoice(text("fpga_connection"), options.firstOrNull { it.first == mode }?.second ?: text("fpga_unmapped"), options) { choice ->
            onBinding(when (choice) {
                "physical" -> FpgaPinBinding.Physical("")
                "display" -> FpgaPinBinding.Display(displays.first().resource.id, 0)
                "zero" -> FpgaPinBinding.Constant(false)
                "one" -> FpgaPinBinding.Constant(true)
                "open" -> FpgaPinBinding.Open
                else -> null
            })
        }
        if (boardBitmap != null && port.clockFrequencyHz == null) {
            FpgaBoardPinPicker(board, boardBitmap, port.direction, binding as? FpgaPinBinding.Physical, title, text,
                onBinding, otherMapped)
        }
        if (binding is FpgaPinBinding.Display) {
            val display = displays.firstOrNull { it.resource.id == binding.resourceId }
            val choices = displays.map { it to (it.resource.label.ifBlank { it.resource.type } + " · " + it.resource.id) }
            FpgaChoice(text("fpga_scan_display"), choices.firstOrNull { it.first == display }?.second.orEmpty(), choices) {
                onBinding(FpgaPinBinding.Display(it.resource.id, 0))
            }
            if (display != null) {
                val channelLimit = display.channelLimit(binding.rgbPixel)
                val validChannel = binding.channel in 0 until channelLimit
                OutlinedTextField(
                    binding.channel.takeIf { it >= 0 }?.toString().orEmpty(),
                    { if (it.length <= 4 && it.all(Char::isDigit)) onBinding(binding.copy(channel = it.toIntOrNull() ?: -1)) },
                    label = { Text(text("fpga_scan_channel")) },
                    supportingText = { Text(text("fpga_scan_channel_range", "max" to channelLimit - 1)) },
                    isError = !validChannel,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
                if (bit == 0 && port.width > 1) {
                    OutlinedButton(
                        onClick = { onMapDisplayBus(binding) },
                        enabled = validChannel && binding.channel + port.width <= channelLimit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(text("fpga_scan_map_bus")) }
                }
            }
        }
        if (binding is FpgaPinBinding.Physical) {
            val clock = board.clock
            if (port.clockFrequencyHz != null && clock != null) {
                OutlinedButton(onClick = { onBinding(FpgaPinBinding.Physical(clock.location)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text("fpga_use_clock", "pin" to clock.location))
                }
                Text(text("fpga_selected_pin", "pin" to binding.location.ifBlank { "?" }))
            } else {
                val pins = board.pins.filter { it.direction == port.direction || it.direction == FpgaPinDirection.INOUT }
                FpgaPhysicalPinPicker(binding, pins, board, text, onBinding)
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FpgaPhysicalPinPicker(
    binding: FpgaPinBinding.Physical,
    pins: List<FpgaBoardPin>,
    board: FpgaBoardDefinition,
    text: Localizer,
    onBinding: (FpgaPinBinding) -> Unit,
) {
    var showPins by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val selected = pins.firstOrNull { it.location.equals(binding.location, true) &&
        (binding.resourceId == null || it.resourceId == binding.resourceId) }
    val matching = pins.filter { pin ->
        val resource = board.resources.first { it.id == pin.resourceId }
        filter.isBlank() || "${pin.location} ${resource.label} ${resource.type}".contains(filter, true)
    }
    OutlinedTextField(binding.location, { location -> onBinding(binding.copy(location = location, resourceId = null)) },
        modifier = Modifier.fillMaxWidth(), label = { Text(text("fpga_physical_pin")) }, singleLine = true)
    Column {
        OutlinedButton(onClick = { filter = ""; showPins = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(text.plural("fpga_choose_compatible_pin", pins.size.toLong()))
        }
        DropdownMenu(expanded = showPins, onDismissRequest = { showPins = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            OutlinedTextField(filter, { filter = it }, modifier = Modifier.padding(8.dp),
                label = { Text(text("fpga_filter_pins")) }, singleLine = true)
            matching.take(60).forEach { pin ->
                val resource = board.resources.first { it.id == pin.resourceId }
                DropdownMenuItem(text = { Text("${pin.location} · ${resource.label.ifBlank { resource.type }} [${pin.index}]") },
                    onClick = { onBinding(FpgaPinBinding.Physical(pin.location, pin.resourceId)); showPins = false },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
            if (matching.size > 60) Text(text("fpga_refine_pin_filter"), modifier = Modifier.padding(12.dp))
            if (matching.isEmpty()) Text(text("fpga_no_compatible_pin"), modifier = Modifier.padding(12.dp))
        }
    }
    selected?.let { pin ->
        val electrical = pin.electrical
        val polarity = if (electrical.activeLow) text("fpga_active_low") else text("fpga_active_high")
        Text(listOfNotNull(electrical.ioStandard, polarity,
            electrical.driveMilliamps?.let { "$it mA" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun <T> FpgaChoice(
    label: String, selected: String, choices: List<Pair<T, String>>, enabled: Boolean = true, onChoose: (T) -> Unit,
) {
    val text = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(text("fpga_choice_label", "label" to label, "selection" to selected))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onChoose(value); expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

private fun boardFlows(board: FpgaBoardDefinition): List<FpgaFlow> = when (board.device.vendor) {
    FpgaVendor.ALTERA -> listOf(FpgaFlow.QUARTUS)
    FpgaVendor.XILINX -> listOf(FpgaFlow.ISE)
    FpgaVendor.VIVADO -> listOf(FpgaFlow.VIVADO)
    FpgaVendor.OPEN_FPGA -> if (board.device.family.contains("ice", true)) listOf(FpgaFlow.OPEN_ICE40, FpgaFlow.OPEN_ECP5)
        else listOf(FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40)
    FpgaVendor.UNKNOWN -> emptyList()
}

private fun flowName(flow: FpgaFlow, text: Localizer): String = when (flow) {
    FpgaFlow.OPEN_ECP5 -> text("fpga_ecp5_flow")
    FpgaFlow.OPEN_ICE40 -> text("fpga_ice40_flow")
    FpgaFlow.VIVADO -> text("fpga_vivado_export")
    FpgaFlow.ISE -> text("fpga_ise_flow")
    FpgaFlow.QUARTUS -> text("fpga_quartus_export")
}

/** Whole frequencies are shown without a decimal part. */
internal fun tickFrequencyText(hz: Double): String =
    if (hz % 1.0 == 0.0 && hz < 1e15) hz.toLong().toString() else hz.toString()
