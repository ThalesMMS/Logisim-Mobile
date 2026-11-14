package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import dev.logisim.mobile.core.FpgaDiagnostic
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.formatTickFrequency
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

internal data class FpgaSavedMapLoad(
    val design: FpgaDesignArtifact,
    val board: FpgaBoardDefinition,
    val initial: Map<FpgaSignalBit, FpgaPinBinding>,
    /** The circuit whose stored map is read; every circuit of a project keeps its own boardmap. */
    val circuit: String = "",
)

internal sealed interface FpgaMapLoadStatus {
    data object Loaded : FpgaMapLoadStatus
    data class Failed(val board: String, val notices: List<LocalizedText>) : FpgaMapLoadStatus
}

/** A removed external board, kept for Undo until the next catalog change. */
internal data class FpgaRemovedBoard(val id: String, val entry: FpgaExternalBoard, val previousSelected: String?, val index: Int)

/** Save waits for confirmation before it replaces a stored map that binds more bits, or one it cannot read. */
internal data class FpgaSaveGuard(val board: String, val savedBits: Int?, val newBits: Int, val mapping: FpgaMapping)

private data class FpgaUnsavedMapping(
    val design: FpgaDesignArtifact,
    val bindings: Map<FpgaSignalBit, FpgaPinBinding>,
    val clocks: Map<String, FpgaClockInput>,
    val status: FpgaMapLoadStatus?,
)

/** What the mapping session last synced to: the circuit's design on a board, in one document. */
private data class FpgaMappingKey(
    val design: FpgaDesignArtifact?,
    val board: FpgaBoardDefinition?,
    val identity: Long,
    val circuit: String,
)

/** Null when [mapping] can replace the stored map of [board] without asking. */
internal fun FpgaMappingCodec.replacementGuard(board: FpgaBoardDefinition, flow: FpgaFlow, mapping: FpgaMapping): FpgaSaveGuard? {
    if (!hasSaved(board)) return null
    return when (val saved = loadSaved(board, flow)) {
        is FpgaMappingResult.Loaded -> saved.mapping.bindings.size.takeIf { it > mapping.bindings.size }
            ?.let { FpgaSaveGuard(board.name, it, mapping.bindings.size, mapping) }
        else -> FpgaSaveGuard(board.name, null, mapping.bindings.size, mapping)
    }
}

/**
 * The FPGA tool's state while its sheet is closed: the selected board, the unsaved bindings of every circuit and board
 * used with this document, and the board editor.
 */
class FpgaMappingSession {
    internal val boardXml = mutableStateOf("")
    internal val board = mutableStateOf<FpgaBoardDefinition?>(null)
    internal val importNotices = mutableStateOf<List<LocalizedText>>(emptyList())
    internal val importFailed = mutableStateOf(false)
    internal val showXml = mutableStateOf(true)
    val boardEditor = FpgaBoardEditorSession()
    internal val bindings = mutableStateOf<Map<FpgaSignalBit, FpgaPinBinding>>(emptyMap())
    internal val clockInputs = mutableStateOf<Map<String, FpgaClockInput>>(emptyMap())
    private var flowBoard: FpgaBoardDefinition? = null
    private val flow = mutableStateOf<FpgaFlow?>(null)
    private var synced: FpgaMappingKey? = null
    /**
     * Unsaved bindings and clock inputs by circuit and board name, so switching boards or circuits and back keeps them.
     * Each circuit stores its own boardmap, so bindings never pass from one circuit to another.
     */
    private val unsaved = mutableMapOf<Pair<String, String>, FpgaUnsavedMapping>()
    /** The stored map to read for bindings that were just created for a design and board. */
    internal var pendingLoad by mutableStateOf<FpgaSavedMapLoad?>(null)
        private set
    internal var loadStatus by mutableStateOf<FpgaMapLoadStatus?>(null)
    internal var removedBoard by mutableStateOf<FpgaRemovedBoard?>(null)
    internal var saveGuard by mutableStateOf<FpgaSaveGuard?>(null)
    /** Multi-bit ports whose bit cards are shown; the others show only their summary. */
    internal var expandedPorts by mutableStateOf<Set<String>>(emptySet())

    internal val currentBindings: Map<FpgaSignalBit, FpgaPinBinding> get() = bindings.value
    internal val currentClocks: Map<String, FpgaClockInput> get() = clockInputs.value

    /**
     * Selects the bindings for [design] of [circuit] on [board]. A circuit and board used before keep their unsaved
     * bindings, carried over for ports whose name, width and direction did not change; a new pair starts from the
     * defaults and asks for that circuit's stored map ([pendingLoad]). Another document ([identity]) starts over.
     */
    internal fun sync(
        design: FpgaDesignArtifact?,
        board: FpgaBoardDefinition?,
        identity: Long = 0,
        circuit: String = "",
        /** The circuits of the document; the unsaved bindings of a deleted circuit are dropped. Null keeps them all. */
        liveCircuits: Set<String>? = null,
    ) {
        val key = FpgaMappingKey(design, board, identity, circuit)
        val previous = synced
        if (liveCircuits != null) unsaved.keys.removeAll { it.first !in liveCircuits }
        if (previous == key) return
        if (previous == null || previous.identity != identity) {
            unsaved.clear()
            removedBoard = null
        } else if (liveCircuits == null || previous.circuit in liveCircuits) keep(previous)
        synced = key
        saveGuard = null
        if (design == null || board == null) {
            bindings.value = emptyMap()
            clockInputs.value = defaultClocks(design)
            pendingLoad = null
            loadStatus = null
            return
        }
        val stored = unsaved[circuit to board.name]
        if (stored == null) {
            bindings.value = design.reptarBindings(board)
            clockInputs.value = defaultClocks(design)
            loadStatus = null
            pendingLoad = FpgaSavedMapLoad(design, board, bindings.value, circuit)
        } else {
            val same = if (stored.design == design) null else design.ports.filter { port ->
                stored.design.ports.any { it.name == port.name && it.width == port.width && it.direction == port.direction }
            }.mapTo(mutableSetOf()) { it.name }
            val defaults = defaultClocks(design)
            bindings.value = if (same == null) stored.bindings else stored.bindings.filterKeys { it.port in same }
            clockInputs.value = if (same == null) stored.clocks
                else defaults + stored.clocks.filterKeys { it in same && it in defaults }
            loadStatus = stored.status
            pendingLoad = null
        }
    }

    private fun keep(previous: FpgaMappingKey) {
        val design = previous.design ?: return
        val board = previous.board ?: return
        val slot = previous.circuit to board.name
        val pending = pendingLoad
        // A stored map that has not been read yet is read again when the circuit and board come back.
        if (pending != null && pending.circuit == previous.circuit && pending.board.name == board.name &&
            bindings.value == pending.initial) unsaved.remove(slot)
        else unsaved[slot] = FpgaUnsavedMapping(design, bindings.value, clockInputs.value, loadStatus)
    }

    /** An edit of the bindings. "Saved mapping loaded" no longer describes them, so that notice ends. */
    internal fun editBindings(next: Map<FpgaSignalBit, FpgaPinBinding>) {
        bindings.value = next
        if (loadStatus == FpgaMapLoadStatus.Loaded) loadStatus = null
    }

    /** An edit of the clock inputs, which ends a "Saved mapping loaded" notice like a binding edit. */
    internal fun editClocks(next: Map<String, FpgaClockInput>) {
        clockInputs.value = next
        if (loadStatus == FpgaMapLoadStatus.Loaded) loadStatus = null
    }

    /**
     * The map of [circuit] for [board] was written: what was reported about the stored one, such as a map that could
     * not be read, no longer applies. Saving completes after the commit, so it applies to the circuit and board saved.
     */
    internal fun mapSaved(circuit: String, board: String) {
        val current = synced
        if (current != null && current.circuit == circuit && current.board?.name == board) loadStatus = null
        unsaved[circuit to board]?.let { unsaved[circuit to board] = it.copy(status = null) }
    }

    /** Applies the stored map read for [request], unless the bindings were edited meanwhile. */
    internal fun completeLoad(request: FpgaSavedMapLoad, result: FpgaMappingResult?) {
        if (pendingLoad != request) return
        pendingLoad = null
        if (bindings.value != request.initial) return
        when (result) {
            is FpgaMappingResult.Loaded -> {
                applyLoaded(result.mapping)
                loadStatus = FpgaMapLoadStatus.Loaded
            }
            // A stale or broken map is reported once; the defaults stay.
            is FpgaMappingResult.Failure -> loadStatus = FpgaMapLoadStatus.Failed(request.board.name, result.notices)
            else -> Unit
        }
    }

    /** A loaded map sets the bindings and the clock inputs of the design's one-bit inputs. */
    internal fun applyLoaded(mapping: FpgaMapping) {
        bindings.value = mapping.bindings
        clockInputs.value = clockInputs.value.mapValues { (port, _) -> clockInput(mapping.clocks[port], mapping.clockScales[port]) }
    }

    internal fun flowFor(board: FpgaBoardDefinition?) = flow.apply {
        if (flowBoard != board) { value = board?.let(::boardFlows)?.firstOrNull(); flowBoard = board }
    }

    private fun defaultClocks(design: FpgaDesignArtifact?): Map<String, FpgaClockInput> =
        design?.ports.orEmpty().filter { it.direction == FpgaPinDirection.INPUT && it.width == 1 }
            .associate { it.name to clockInput(it.clockFrequencyHz, it.clockScale) }
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
                    circuitKey = fpgaCircuitKey(context.document.id, context.circuitLifetime(context.document.id)),
                    liveCircuits = context.project.circuits.mapTo(mutableSetOf()) { fpgaCircuitKey(it.id, context.circuitLifetime(it.id)) },
                    canApplyMapping = { context.isCurrentEditorContext() && context.projectExports.available },
                    onSaveMapping = { document, completed ->
                        context.commitProject(context.project.withCircuit(document)) { saved ->
                            context.showNotice(message(if (saved) "fpga_map_saved" else "fpga_map_save_failed"))
                            completed(saved)
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

/**
 * The key of a circuit's unsaved bindings: its id and its [lifetime] token ([EditorState.circuitLifetime]). Renaming
 * keeps both, so the bindings stay; a deleted circuit's id can be reused by the next new circuit, whose token differs,
 * so it never inherits the deleted one's bindings, whatever its name.
 */
internal fun fpgaCircuitKey(id: String, lifetime: Long): String = "$id#$lifetime"

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
    /** The circuit the review belongs to. Each circuit stores its own boardmap, so its bindings are kept apart. */
    circuitKey: String = "",
    /** Every circuit key of the document; unsaved bindings of the others are dropped. */
    liveCircuits: Set<String>? = null,
    canApplyMapping: () -> Boolean = { true },
    /** Writes the circuit with its new boardmap, then reports whether the project was saved. */
    onSaveMapping: ((CircuitDocument, (Boolean) -> Unit) -> Unit)? = null,
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
    remember(design, board, documentIdentity, circuitKey) { session.sync(design, board, documentIdentity, circuitKey, liveCircuits) }
    val bindings by session.bindings
    var flow by remember(board) { session.flowFor(board) }
    val clocks by session.clockInputs
    val effectiveDesign = remember(design, clocks) { design?.withClockInputs(clocks) }
    var actionNotices by remember(effectiveDesign, board, bindings, flow) { mutableStateOf<List<LocalizedText>>(emptyList()) }
    var mappingNotices by remember(design, board, documentIdentity, circuitKey) { mutableStateOf<List<LocalizedText>>(emptyList()) }
    val currentBoard = board
    val editor = session.boardEditor
    val currentFlow = flow
    val boardBitmap = rememberFpgaBoardBitmap(currentBoard?.originalXml?.takeIf { effectiveDesign != null })
    val scannedDisplays = remember(currentBoard) { currentBoard?.resources.orEmpty().mapNotNull { it.scannedDisplay() } }
    val displayNames = remember(scannedDisplays, text) { scanDisplayNames(scannedDisplays, text) }
    val diagnostics = remember(effectiveDesign, currentBoard, bindings, currentFlow) {
        if (effectiveDesign != null && currentBoard != null && currentFlow != null)
            FpgaDesignRules.check(effectiveDesign, currentBoard, bindings, currentFlow)
        else emptyList()
    }
    val readiness = fpgaReadiness(catalogBusy, diagnostics, clocks, ticks != null, tickHz, currentBoard?.clock != null)
    val ready = effectiveDesign != null && currentBoard != null && currentFlow != null && readiness.ready
    val listState = rememberLazyListState()
    /** The bit whose pin is being chosen in the full-screen picker. */
    var picker by remember(effectiveDesign, currentBoard) { mutableStateOf<FpgaSignalBit?>(null) }
    /** The picker was opened from a port's "Choose pins" and moves through its bits. */
    var pickerWalks by remember(effectiveDesign, currentBoard) { mutableStateOf(false) }
    var confirmUnmapAll by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(true) }
    /** The last port unmapped from its header, with its bindings for Undo. */
    var unmappedPort by remember(effectiveDesign, currentBoard) { mutableStateOf<Pair<String, Map<FpgaSignalBit, FpgaPinBinding>>?>(null) }
    /** A list item to bring into view once the list shows it, such as a bit card opened from its problem. */
    var reveal by remember { mutableStateOf<String?>(null) }
    val rows = fpgaRows(review, circuitChecked, currentBoard != null && effectiveDesign != null, effectiveDesign?.ports.orEmpty(),
        session.expandedPorts)
    LaunchedEffect(reveal, rows) {
        val target = reveal ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == target }
        if (index >= 0) listState.animateScrollToItem(index)
        reveal = null
    }
    fun revealSignal(signal: FpgaSignalBit) {
        val port = effectiveDesign?.ports?.firstOrNull { it.name == signal.port } ?: return
        if (port.width > 1) session.expandedPorts += port.name
        reveal = FpgaRow.Signal(port, signal.bit).key
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

    /** [board] names the board of a saved map; a stored map read back ([stored]) is reported as loaded. */
    fun applyMapping(result: FpgaMappingResult, stored: Boolean = false, board: String? = null) {
        when (result) {
            is FpgaMappingResult.Loaded -> {
                session.applyLoaded(result.mapping)
                session.loadStatus = if (stored) FpgaMapLoadStatus.Loaded else null
                mappingNotices = emptyList()
            }
            is FpgaMappingResult.Saved -> {
                val hz = tickHz
                val circuit = circuitKey
                onSaveMapping?.invoke(if (ticks != null && hz != null) result.document.withFpgaTickFrequency(hz) else result.document) { saved ->
                    if (saved && board != null) session.mapSaved(circuit, board)
                }
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

    LaunchedEffect(session.pendingLoad, codec, currentFlow) {
        val request = session.pendingLoad ?: return@LaunchedEffect
        val loader = codec ?: return@LaunchedEffect
        val loadFlow = currentFlow ?: return@LaunchedEffect
        if (request.board != currentBoard || request.design != design || request.circuit != circuitKey) return@LaunchedEffect
        // A missing map keeps the defaults silently; a stored one replaces them, as Circuit.setBoardMap does.
        val result = withContext(Dispatchers.Default) {
            if (loader.hasSaved(request.board)) loader.loadSaved(request.board, loadFlow) else null
        }
        session.completeLoad(request, result)
    }

    fun currentMapping(): FpgaMapping {
        val frequencies = clocks.mapNotNull { (name, input) ->
            input.frequencyHz?.takeIf { input.enabled }?.let { name to it }
        }.toMap()
        val scales = clocks.mapNotNull { (name, input) ->
            input.scale?.takeIf { input.enabled && input.derived }?.let { name to it }
        }.toMap()
        return FpgaMapping(bindings, frequencies, scales)
    }

    fun saveMapping(codec: FpgaMappingCodec, board: FpgaBoardDefinition, flow: FpgaFlow, mapping: FpgaMapping, confirmed: Boolean) {
        val guard = if (confirmed) null else codec.replacementGuard(board, flow, mapping)
        if (guard != null) session.saveGuard = guard
        else applyMapping(codec.save(board, flow, mapping), board = board.name)
    }

    fun restoreBoard(removal: FpgaRemovedBoard) = changeCatalog {
        session.removedBoard = null
        catalog = withContext(Dispatchers.Default) {
            boardCatalog.restore(removal.id, removal.entry, removal.previousSelected, removal.index)
        }
        catalog.selected?.let { selection ->
            displayBoard(readCatalogBoard(selection, catalog))
            boardXml = catalog.external[selection]?.xml.orEmpty()
        }
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
        session.removedBoard = null
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
        when (val result = FpgaProjectGenerator.generate(effectiveDesign, currentBoard, bindings, currentFlow, ticks, tickHz ?: 1.0)) {
            is FpgaPlanResult.Success -> {
                action(result.plan)
                actionNotices = emptyList()
            }
            is FpgaPlanResult.Failure -> actionNotices = result.diagnostics.map { it.notice }
        }
    }

    // The editor's draft lives in the session: closing the sheet and reopening the tool returns here.
    if (editor.open && boardAssets != null) {
        FpgaBoardEditorPanel(editor, boardAssets, onSaved = ::importXml, modifier = modifier)
        return
    }

    session.saveGuard?.let { guard ->
        AppAlertDialog(onDismissRequest = { session.saveGuard = null },
            title = { Text(text("fpga_map_replace_title", "board" to guard.board)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (guard.savedBits == null) Text(text("fpga_map_replace_unreadable"))
                    else Text(text.plural("fpga_map_replace_saved_bits", guard.savedBits.toLong()))
                    Text(text.plural("fpga_map_replace_new_bits", guard.newBits.toLong()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    session.saveGuard = null
                    if (codec != null && currentBoard != null && currentFlow != null && currentBoard.name == guard.board)
                        saveMapping(codec, currentBoard, currentFlow, guard.mapping, confirmed = true)
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("fpga_map_replace_confirm"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { session.saveGuard = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("action_cancel"))
                }
            })
    }

    picker?.let { signal ->
        val port = effectiveDesign?.ports?.firstOrNull { it.name == signal.port }
        if (port != null && currentBoard != null) {
            FpgaPinPickerDialog(currentBoard, boardBitmap, port, signal.bit, bindings, onDismiss = { picker = null },
                onChoose = { binding ->
                    val next = bindings + (signal to binding)
                    session.editBindings(next)
                    // Choose pins walks through the port: Use moves on to the next unmapped bit, not back to the list.
                    picker = if (pickerWalks) fpgaNextUnmappedBit(port, signal.bit, next)?.let { FpgaSignalBit(port.name, it) } else null
                },
                onChoosePort = { next -> session.editBindings(next); picker = null },
                walking = pickerWalks)
        }
    }

    if (confirmUnmapAll) {
        AppAlertDialog(onDismissRequest = { confirmUnmapAll = false },
            title = { Text(text.plural("fpga_unmap_all_title", bindings.size.toLong())) },
            text = { Text(text("fpga_unmap_all_text")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnmapAll = false
                    unmappedPort = null
                    session.editBindings(emptyMap())
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text("fpga_unmap_all_confirm"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnmapAll = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(text("action_cancel")) }
            })
    }

    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    FpgaRow.Header -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                FpgaChoice(text("fpga_board_catalog"), choices.firstOrNull { it.first == catalog.selected }?.second
                                    ?: text("fpga_board_catalog_choose"), choices, enabled = !catalogBusy && choices.isNotEmpty(),
                                    selectedValue = catalog.selected, testTag = "fpga-board-catalog") { id ->
                                    changeCatalog {
                                        session.removedBoard = null
                                        val result = readCatalogBoard(id, catalog)
                                        if (result is FpgaBoardImportResult.Success) {
                                            catalog = withContext(Dispatchers.Default) { boardCatalog.select(id) }
                                            boardXml = catalog.external[id]?.xml.orEmpty()
                                        }
                                        displayBoard(result)
                                    }
                                }
                            }
                            // The board controls are disabled while the catalog loads; this says why.
                            if (catalogBusy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp)
                                .semantics { contentDescription = text("fpga_board_catalog_loading") }, strokeWidth = 3.dp)
                        }
                        catalogNotice?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
                        session.removedBoard?.let { removal ->
                            UndoRow(text("fpga_board_catalog_removed", "name" to removal.entry.name), text("fpga_editor_undo"),
                                enabled = !catalogBusy) { restoreBoard(removal) }
                        }
                        if (boardAssets != null) {
                            OutlinedButton(enabled = !catalogBusy, onClick = { editor.start(null) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(text("fpga_editor_new_board"))
                            }
                            if (currentBoard != null) {
                                OutlinedButton(enabled = !catalogBusy, onClick = { editor.start(currentBoard) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(text("fpga_editor_edit_board"))
                                }
                            }
                        }
                        // Next to the board actions, away from the board menu; Undo restores the stored XML and selection.
                        catalog.selected?.takeIf { it in catalog.external }?.let { id ->
                            TextButton(enabled = !catalogBusy, onClick = {
                                changeCatalog {
                                    val names = builtInNames
                                    val removal = FpgaRemovedBoard(id, catalog.external.getValue(id), catalog.selected,
                                        catalog.external.keys.indexOf(id))
                                    catalog = withContext(Dispatchers.Default) { boardCatalog.remove(id, names) }
                                    session.removedBoard = removal
                                    catalog.selected?.let { displayBoard(readCatalogBoard(it, catalog)); boardXml = "" } ?: clearBoard()
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(text("fpga_board_catalog_remove"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (review != null) {
                            OutlinedButton(onClick = { circuitChecked = !circuitChecked },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(text(if (circuitChecked) "fpga_hide_circuit_drc" else "fpga_check_circuit_drc"))
                            }
                        }
                    }
                    FpgaRow.CircuitHeading -> Column {
                        Text(text("fpga_circuit_drc_heading"), modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                        if (review?.diagnostics.isNullOrEmpty()) Text(text("fpga_circuit_drc_clear"))
                    }
                    is FpgaRow.CircuitDiagnostic -> review?.diagnostics?.getOrNull(row.index)?.let { diagnostic ->
                        FpgaDiagnosticRow(diagnostic.severity, diagnostic.notice, text, onNavigate?.let { { it(diagnostic.target) } })
                    }
                    FpgaRow.HdlHeading -> Text(text("fpga_hdl_diagnostics_heading"), modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    is FpgaRow.HdlDiagnostic -> review?.hdlDiagnostics?.getOrNull(row.index)?.let { diagnostic ->
                        val severity = if (diagnostic.severity == HdlDiagnosticSeverity.ERROR)
                            FpgaDiagnosticSeverity.ERROR else FpgaDiagnosticSeverity.WARNING
                        val target = DiagnosticTarget(diagnostic.circuitId, componentIds = setOfNotNull(diagnostic.componentId))
                        FpgaDiagnosticRow(severity, diagnostic.notice, text, onNavigate?.let { { it(target) } })
                    }
                    FpgaRow.Board -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Text(listOf(text(FpgaLabels.vendor(currentBoard.device.vendor)), currentBoard.device.family,
                                currentBoard.device.part, currentBoard.device.packageName).joinToString(" · "))
                            Text(text.plural("fpga_board_pins", currentBoard.pins.size.toLong()))
                            currentBoard.clock?.let { clock ->
                                Text(text("fpga_board_clock", "pin" to clock.location, "frequency" to clock.frequencyHz))
                            }
                            val flows = boardFlows(currentBoard)
                            if (flows.isEmpty()) Text(text("fpga_vendor_unavailable"), color = MaterialTheme.colorScheme.error)
                            else FpgaChoice(text("fpga_flow"), currentFlow?.let { flowName(it, text) }.orEmpty(),
                                flows.map { it to flowName(it, text) }, selectedValue = currentFlow) { flow = it }
                        }
                        HorizontalDivider()
                        if (design == null) {
                            Text(text("fpga_no_hdl"))
                        } else {
                            Text("${design.topLevelName} · ${design.language}", style = MaterialTheme.typography.titleMedium)
                            Text(text.plural("fpga_physical_signals", design.ports.sumOf { it.width.toLong() }))
                            when (val status = session.loadStatus) {
                                FpgaMapLoadStatus.Loaded -> Text(text("fpga_map_auto_loaded"), color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                is FpgaMapLoadStatus.Failed -> {
                                    Text(text("fpga_map_auto_load_failed", "board" to status.board), color = MaterialTheme.colorScheme.error)
                                    status.notices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall) }
                                }
                                null -> Unit
                            }
                            if (review?.clockTicks != null && ticks == null) Text(text("fpga_tick_physical_map"))
                            if (ticks != null) {
                                OutlinedTextField(tickText, { tickText = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text(text("fpga_tick_frequency_input")) }, singleLine = true, isError = tickHz == null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                FpgaTickPresets(tickHz, text) { tickText = tickFrequencyText(it) }
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
                    FpgaRow.Actions -> {
                        val ports = effectiveDesign?.ports.orEmpty()
                        val total = ports.sumOf { it.width.coerceAtLeast(0).toLong() }
                        val mapped = bindings.keys.count { signal -> ports.any { it.name == signal.port && signal.bit in 0 until it.width } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text.plural("fpga_mapping_summary", total, "mapped" to mapped), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { confirmUnmapAll = true }, enabled = bindings.isNotEmpty(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("fpga-unmap-all")) {
                                Text(text("fpga_unmap_all"),
                                    color = if (bindings.isNotEmpty()) MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                        }
                    }
                    is FpgaRow.PortHeader -> {
                        val port = row.port
                        val expanded = port.name in session.expandedPorts
                        val mappedBits = (0 until port.width).count { FpgaSignalBit(port.name, it) in bindings }
                        Column(Modifier.fillMaxWidth().testTag("fpga-port-${port.name}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    session.expandedPorts = if (expanded) session.expandedPorts - port.name else session.expandedPorts + port.name
                                }
                                .semantics { stateDescription = text(if (expanded) "fpga_port_expanded" else "fpga_port_collapsed") },
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${port.name} · ${directionLabel(port.direction, text)}", style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.semantics { heading() })
                                    Text(text.plural("fpga_port_summary", port.width.toLong(), "mapped" to mappedBits),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (expanded) "▲" else "▼", Modifier.padding(horizontal = 12.dp).clearAndSetSemantics { })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (port.clockFrequencyHz == null && currentBoard != null) {
                                    // "Map whole port" only when some resource holds every bit; on boards of one-pin
                                    // resources (BASYS3 LEDs and switches) the picker walks through the bits instead,
                                    // and a tapped switch can take the rest of the port onto the switches after it.
                                    val wholeFits = remember(currentBoard, port) { fpgaWholePortFits(currentBoard, port) }
                                    val firstOpen = (0 until port.width).firstOrNull { FpgaSignalBit(port.name, it) !in bindings } ?: 0
                                    OutlinedButton(onClick = {
                                        if (!wholeFits) session.expandedPorts = session.expandedPorts + port.name
                                        pickerWalks = !wholeFits
                                        picker = FpgaSignalBit(port.name, if (wholeFits) 0 else firstOpen)
                                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("fpga-port-map-${port.name}")) {
                                        Text(text(if (wholeFits) "fpga_port_map_whole" else "fpga_port_choose_pins"), textAlign = TextAlign.Center)
                                    }
                                }
                                TextButton(onClick = {
                                    unmappedPort = port.name to bindings.filterKeys { it.port == port.name }
                                    session.editBindings(bindings.filterKeys { it.port != port.name })
                                }, enabled = mappedBits > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("fpga-port-unmap-${port.name}")) {
                                    Text(text("fpga_port_unmap"))
                                }
                            }
                            unmappedPort?.takeIf { it.first == port.name }?.let { (name, previous) ->
                                UndoRow(text("fpga_port_unmapped", "port" to name), text("fpga_editor_undo")) {
                                    session.editBindings(bindings + previous)
                                    unmappedPort = null
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 6.dp))
                        }
                    }
                    is FpgaRow.Signal -> if (currentBoard != null) {
                        val port = row.port
                        val bit = row.bit
                        val signal = FpgaSignalBit(port.name, bit)
                        FpgaSignalMapping(port, bit, bindings[signal], currentBoard, text,
                            displays = scannedDisplays,
                            displayNames = displayNames,
                            clockInput = clocks[port.name]?.takeIf { bit == 0 },
                            canDeriveClock = currentFlow?.let { currentBoard.supportsDerivedClock(it) } == true,
                            onClockChange = { clock ->
                                session.editClocks(clocks + (port.name to clock))
                                if (clock.enabled && bindings[signal] is FpgaPinBinding.Constant) session.editBindings(bindings - signal)
                            },
                            onUseBoardClock = {
                                currentBoard.clock?.let { clock ->
                                    val previous = clocks[port.name] ?: FpgaClockInput()
                                    session.editClocks(clocks + (port.name to previous.copy(enabled = true, frequency = clock.frequencyHz.toString())))
                                    session.editBindings(bindings + (signal to FpgaPinBinding.Physical(clock.location)))
                                }
                            },
                            onMapDisplayBus = { binding ->
                                session.editBindings(bindings + (0 until port.width).associate { channel ->
                                    FpgaSignalBit(port.name, channel) to binding.copy(channel = binding.channel + channel)
                                })
                            },
                            onBinding = { binding ->
                                session.editBindings(if (binding == null) bindings - signal else bindings + (signal to binding))
                            },
                            onOpenPicker = { pickerWalks = false; picker = signal })
                    }
                    FpgaRow.Problems -> FpgaProblems(readiness, diagnostics, effectiveDesign?.ports.orEmpty(), showProblems, text,
                        onToggle = { showProblems = !showProblems }, onReveal = ::revealSignal)
                    FpgaRow.Footer -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (design != null && currentBoard != null && currentFlow != null && codec != null) {
                            OutlinedButton(onClick = { applyMapping(codec.loadSaved(currentBoard, currentFlow), stored = true) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(text("fpga_map_revert"))
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
                                saveMapping(codec, currentBoard, currentFlow, currentMapping(), confirmed = false)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(text("fpga_map_save"))
                            }
                            mappingNotices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
                        }
                        actionNotices.forEach { Text(text(it), color = MaterialTheme.colorScheme.error) }
                        Text(text("fpga_synthesis_intro"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // Status, Export and Prepare stay visible below the list instead of after every bit card.
        if (design != null && currentBoard != null && currentFlow != null) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).testTag("fpga-mapping-status")
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text(when {
                            catalogBusy -> text("fpga_mapping_loading_catalog")
                            ready -> text("fpga_mapping_valid")
                            else -> text.plural("fpga_mapping_problems", readiness.problemCount.toLong())
                        }, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                        readiness.reasons.take(2).forEach { reason ->
                            Text(text(reason.text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!catalogBusy && readiness.problemCount > 0) {
                        TextButton(onClick = { showProblems = true; reveal = FpgaRow.Problems.key },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(text("fpga_show_problems")) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onExport != null) Button(enabled = ready, onClick = { generate(onExport) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(text("fpga_export_project"), textAlign = TextAlign.Center)
                    }
                    val operation = currentFlow.companionBuildOperation(currentBoard)
                    if (onSynthesize != null && operation != null && currentBoard.supportsCompanionBuild(currentFlow)) {
                        Button(enabled = ready, onClick = { generate(onSynthesize) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(text(if (operation == "fpga-synthesize") "fpga_prepare_synthesis" else "fpga_prepare_vendor_build"),
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** The mapping page's list items. Keys stay stable when sections open or close, so a problem can scroll to its bit. */
internal sealed class FpgaRow(val key: String) {
    data object Header : FpgaRow("header")
    data object CircuitHeading : FpgaRow("circuit-drc")
    data class CircuitDiagnostic(val index: Int) : FpgaRow("circuit-drc-$index")
    data object HdlHeading : FpgaRow("hdl-drc")
    data class HdlDiagnostic(val index: Int) : FpgaRow("hdl-drc-$index")
    data object Board : FpgaRow("board")
    data object Actions : FpgaRow("actions")
    data class PortHeader(val port: FpgaDesignPort) : FpgaRow("port-${port.name}")
    data class Signal(val port: FpgaDesignPort, val bit: Int) : FpgaRow("sig-${port.name}-$bit")
    data object Problems : FpgaRow("problems")
    data object Footer : FpgaRow("footer")
}

/**
 * The rows of the mapping page. A one-bit port is one card; a wider port is a summary header whose bit cards show
 * when it is expanded. At most 8192 bit cards are listed, as before.
 */
internal fun fpgaRows(
    review: FpgaCircuitReview?,
    circuitChecked: Boolean,
    mapping: Boolean,
    ports: List<FpgaDesignPort>,
    expanded: Set<String>,
): List<FpgaRow> = buildList {
    add(FpgaRow.Header)
    if (review != null && circuitChecked) {
        add(FpgaRow.CircuitHeading)
        review.diagnostics.indices.forEach { add(FpgaRow.CircuitDiagnostic(it)) }
        if (review.hdlDiagnostics.isNotEmpty()) {
            add(FpgaRow.HdlHeading)
            review.hdlDiagnostics.indices.forEach { add(FpgaRow.HdlDiagnostic(it)) }
        }
    }
    add(FpgaRow.Board)
    if (mapping) {
        add(FpgaRow.Actions)
        val seen = mutableSetOf<String>()
        var budget = 8192
        // Duplicate port names are a design rule error; the list shows the first, so item keys stay unique.
        for (port in ports) {
            if (!seen.add(port.name)) continue
            val width = port.width.coerceIn(0, 4096)
            if (width > 1) add(FpgaRow.PortHeader(port))
            if (width == 1 || port.name in expanded) {
                for (bit in 0 until width) {
                    if (budget-- <= 0) break
                    add(FpgaRow.Signal(port, bit))
                }
            }
        }
        add(FpgaRow.Problems)
    }
    add(FpgaRow.Footer)
}

private fun directionLabel(direction: FpgaPinDirection, text: Localizer): String = when (direction) {
    FpgaPinDirection.INPUT -> text("fpga_direction_input")
    FpgaPinDirection.OUTPUT -> text("fpga_direction_output")
    FpgaPinDirection.INOUT -> text("fpga_direction_inout")
}

/** Up to this many problem rows are listed; the count in the heading stays complete. */
private const val PROBLEM_ROWS = 100

/**
 * Every reason generation is blocked, always shown and kept across edits. Unmapped bits are grouped by port, and a
 * row about a signal scrolls to its bit card.
 */
@Composable
private fun FpgaProblems(
    readiness: FpgaReadiness,
    diagnostics: List<FpgaDiagnostic>,
    ports: List<FpgaDesignPort>,
    shown: Boolean,
    text: Localizer,
    onToggle: () -> Unit,
    onReveal: (FpgaSignalBit) -> Unit,
) {
    val warnings = diagnostics.filter { it.severity == FpgaDiagnosticSeverity.WARNING }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = text(if (shown) "fpga_port_expanded" else "fpga_port_collapsed") },
            verticalAlignment = Alignment.CenterVertically) {
            Text(text.plural("fpga_problems_heading", readiness.problemCount.toLong()), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            Text(if (shown) "▲" else "▼", Modifier.padding(horizontal = 12.dp).clearAndSetSemantics { })
        }
        if (!shown) return@Column
        if (readiness.problemCount == 0 && warnings.isEmpty()) Text(text("fpga_no_problems"))
        readiness.reasons.forEach { reason ->
            FpgaDiagnosticRow(FpgaDiagnosticSeverity.ERROR, reason.text, text, reason.signal?.let { { onReveal(it) } },
                tag = "fpga-diagnostic-${reason.code}" + reason.signal?.let { "-${it.port}" }.orEmpty())
        }
        val unmapped = readiness.ruleErrors.filter { it.code == "unmapped-port" && it.signal != null }.groupBy { it.signal!!.port }
        val order = ports.map { it.name }
        unmapped.entries.sortedBy { order.indexOf(it.key) }.forEach { (port, items) ->
            FpgaDiagnosticRow(FpgaDiagnosticSeverity.ERROR, message("fpga_unmapped_bits", "port" to port).copy(quantity = items.size.toLong()),
                text, { onReveal(items.first().signal!!) }, tag = "fpga-diagnostic-unmapped-port-$port")
        }
        val others = readiness.ruleErrors.filterNot { it.code == "unmapped-port" && it.signal != null } + warnings
        others.take(PROBLEM_ROWS).forEach { diagnostic ->
            val signal = diagnostic.signal
            FpgaDiagnosticRow(diagnostic.severity, diagnostic.notice, text, signal?.let { { onReveal(it) } },
                tag = "fpga-diagnostic-${diagnostic.code}" + signal?.let { "-${it.port}-${it.bit}" }.orEmpty())
        }
        if (others.size > PROBLEM_ROWS) Text(text.plural("fpga_more_problems", (others.size - PROBLEM_ROWS).toLong()),
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Common tick rates, Evolution's Simulate menu frequencies; the field above keeps any other value. */
@Composable
private fun FpgaTickPresets(current: Double?, text: Localizer, onChoose: (Double) -> Unit) {
    val presets = TickFrequencies.supported.reversed()
    val selected = current?.let { hz -> presets.firstOrNull { TickFrequencies.same(it, hz) } }
    FpgaChoice(text("fpga_tick_presets"), selected?.let(::formatTickFrequency) ?: text("fpga_tick_preset_custom"),
        presets.map { it to formatTickFrequency(it) }, selectedValue = selected, testTag = "fpga-tick-presets", onChoose = onChoose)
}

/** A diagnostic as one line; with [onNavigate] the whole row is a button that shows what it is about. */
@Composable
private fun FpgaDiagnosticRow(
    severity: FpgaDiagnosticSeverity,
    notice: LocalizedText,
    text: Localizer,
    onNavigate: (() -> Unit)? = null,
    tag: String? = null,
) {
    val error = severity == FpgaDiagnosticSeverity.ERROR
    val label = text(if (error) "diagnostic_error" else "diagnostic_warning")
    val caption = "$label · ${text(notice)}"
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val tagged = if (tag != null) Modifier.testTag(tag) else Modifier
    if (onNavigate == null) {
        Text(caption, color = color, modifier = tagged)
    } else {
        TextButton(onClick = onNavigate, modifier = tagged.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(caption, color = color, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FpgaSignalMapping(
    port: FpgaDesignPort,
    bit: Int,
    binding: FpgaPinBinding?,
    board: FpgaBoardDefinition,
    text: Localizer,
    displays: List<FpgaScanDisplay>,
    displayNames: List<String>,
    clockInput: FpgaClockInput?,
    canDeriveClock: Boolean,
    onClockChange: (FpgaClockInput) -> Unit,
    onUseBoardClock: () -> Unit,
    onMapDisplayBus: (FpgaPinBinding.Display) -> Unit,
    onBinding: (FpgaPinBinding?) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val title = if (port.width == 1) port.name else "${port.name}[$bit]"
    val direction = directionLabel(port.direction, text)
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
    Column(Modifier.fillMaxWidth().testTag("fpga-signal-${port.name}-$bit"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // One heading per port: a one-bit port's card, or the summary header of a wider port.
        Text("$title · $direction", style = MaterialTheme.typography.titleSmall,
            modifier = if (port.width == 1) Modifier.semantics { heading() } else Modifier)
        if (clockInput != null) {
            FpgaSegmentedChoice(text("fpga_clock_role"), clockInput.enabled,
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
                    FpgaSegmentedChoice(text("fpga_clock_source"), clockInput.derived,
                        buildList {
                            add(false to text("fpga_clock_direct"))
                            if (canDeriveClock || clockInput.derived) add(true to text("fpga_clock_derived"))
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
        FpgaChoice(text("fpga_connection"), options.firstOrNull { it.first == mode }?.second ?: text("fpga_unmapped"), options,
            selectedValue = mode, testTag = "fpga-connection-${port.name}-$bit") { choice ->
            when (choice) {
                // A clock input takes the board oscillator, or a custom clock pin typed below when the board has none.
                "physical" -> if (port.clockFrequencyHz != null) onBinding(FpgaPinBinding.Physical(board.clock?.location.orEmpty()))
                    else onOpenPicker()
                "display" -> onBinding(FpgaPinBinding.Display(displays.first().resource.id, 0))
                "zero" -> onBinding(FpgaPinBinding.Constant(false))
                "one" -> onBinding(FpgaPinBinding.Constant(true))
                "open" -> onBinding(FpgaPinBinding.Open)
                else -> onBinding(null)
            }
        }
        if (binding is FpgaPinBinding.Display) {
            val displayIndex = displays.indexOfFirst { it.resource.id == binding.resourceId }
            val display = displays.getOrNull(displayIndex)
            FpgaChoice(text("fpga_scan_display"), displayNames.getOrNull(displayIndex) ?: text("fpga_scan_unset"),
                displays.indices.map { it to displayNames[it] }, selectedValue = displayIndex.takeIf { it >= 0 },
                testTag = "fpga-scan-display-${port.name}-$bit") {
                onBinding(FpgaPinBinding.Display(displays[it].resource.id, 0))
            }
            if (display != null) {
                val channelLimit = display.channelLimit(binding.rgbPixel)
                val validChannel = binding.channel in 0 until channelLimit
                FpgaDisplayChannelPicker(display, binding, validChannel, text, "${port.name}-$bit") { onBinding(it) }
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
            when {
                port.clockFrequencyHz != null && clock != null -> {
                    OutlinedButton(onClick = { onBinding(FpgaPinBinding.Physical(clock.location)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text("fpga_use_clock", "pin" to clock.location))
                    }
                    Text(text("fpga_selected_pin", "pin" to binding.location.ifBlank { "?" }))
                }
                // The documented custom clock pin (mobileclockpin): a board without an oscillator takes a typed pin.
                port.clockFrequencyHz != null -> OutlinedTextField(binding.location,
                    { location -> onBinding(binding.copy(location = location, resourceId = null)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text(text("fpga_clock_custom_pin")) }, singleLine = true,
                    isError = binding.location.isBlank())
                else -> FpgaPinSummary(binding, board, text, "${port.name}-$bit", onChange = onOpenPicker, onClear = { onBinding(null) })
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** The chosen pin as read-only text, with Change (the picker) and Clear; a typo can no longer be entered here. */
@Composable
private fun FpgaPinSummary(
    binding: FpgaPinBinding.Physical,
    board: FpgaBoardDefinition,
    text: Localizer,
    tag: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    // A loaded or legacy binding without a resource shows the first resource that has its location.
    val pin = board.pins.firstOrNull { it.location.equals(binding.location, true) &&
        (binding.resourceId == null || it.resourceId == binding.resourceId) }
    val resource = pin?.let { found -> board.resources.firstOrNull { it.id == found.resourceId } }
    val summary = when {
        binding.location.isBlank() -> text("fpga_pin_not_chosen")
        pin != null && resource != null ->
            text("fpga_pin_summary", "pin" to binding.location, "resource" to text(FpgaLabels.resource(resource)), "index" to pin.index)
        else -> text("fpga_pin_summary_unknown", "pin" to binding.location)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(summary, Modifier.weight(1f).testTag("fpga-pin-summary-$tag"), style = MaterialTheme.typography.bodyLarge,
            color = if (pin == null) MaterialTheme.colorScheme.error else Color.Unspecified)
        TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp).testTag("fpga-pin-change-$tag")) {
            Text(text("fpga_pin_change"))
        }
        TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("fpga-pin-clear-$tag")) {
            Text(text("fpga_pin_clear"))
        }
    }
    pin?.let { Text(fpgaElectricalSummary(it, text), style = MaterialTheme.typography.bodySmall) }
}

/**
 * A labelled menu of a closed choice set. TalkBack reads its "Label: Selection" text once, as a drop-down list, and
 * marks the selected entry; [selectedValue] identifies that entry.
 */
@Composable
internal fun <T> FpgaChoice(
    label: String,
    selected: String,
    choices: List<Pair<T, String>>,
    enabled: Boolean = true,
    selectedValue: T? = null,
    testTag: String? = null,
    onChoose: (T) -> Unit,
) {
    val text = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val shape = ButtonDefaults.outlinedShape
    val colors = ButtonDefaults.outlinedButtonColors()
    val tagged = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Column {
        // Drawn like the outlined buttons around it, but announced as a drop-down list. The visible text already holds
        // the selection; the state says whether the menu is open, with expand and collapse actions, as Material's
        // exposed drop-down menus do.
        val openState = text(if (expanded) "fpga_port_expanded" else "fpga_port_collapsed")
        Box(tagged.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .border(ButtonDefaults.outlinedButtonBorder(enabled), shape)
            .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
            .semantics {
                stateDescription = openState
                if (enabled) {
                    if (expanded) collapse { expanded = false; true } else expand { expanded = true; true }
                }
            }
            .padding(ButtonDefaults.ContentPadding), contentAlignment = Alignment.Center) {
            Text(text("fpga_choice_label", "label" to label, "selection" to selected), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.contentColor else colors.disabledContentColor)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            choices.forEachIndexed { index, (value, title) ->
                val current = if (selectedValue != null) value == selectedValue else title == selected
                val itemTag = if (testTag != null) Modifier.testTag("$testTag-option-$index") else Modifier
                DropdownMenuItem(text = { Text(title) }, onClick = { onChoose(value); expanded = false },
                    trailingIcon = if (current) {
                        { Text("\u2713", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics { }) }
                    } else null,
                    modifier = itemTag.heightIn(min = 48.dp).semantics { this.selected = current })
            }
        }
    }
}

/** Two or three options side by side, read by TalkBack as radio buttons with their selection, in a group named [label]. */
@Composable
internal fun <T> FpgaSegmentedChoice(
    label: String,
    selectedValue: T,
    choices: List<Pair<T, String>>,
    enabled: Boolean = true,
    onChoose: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The group carries the label for TalkBack; the visible text is not a second stop that repeats it.
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { })
        Row(Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { (value, title) ->
                val current = value == selectedValue
                val shape = MaterialTheme.shapes.small
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                    .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .border(1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
                    .selectable(current, enabled = enabled, role = Role.RadioButton) { onChoose(value) }
                    .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    val color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The check is visual only; the selected state is announced by the radio semantics.
                        if (current) Text("\u2713 ", color = color, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clearAndSetSemantics { })
                        Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = color)
                    }
                }
            }
        }
    }
}

/** One option of a single choice, such as a build goal or a programming target, with a visible selected container. */
@Composable
internal fun FpgaRadioOption(selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 4.dp, end = 8.dp).weight(1f), content = content)
    }
}

/** An inline confirmation with an Undo action; the app has no snackbar host inside its sheets. */
@Composable
internal fun UndoRow(message: String, action: String, enabled: Boolean = true, onUndo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f))
            TextButton(onClick = onUndo, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

/**
 * Picks a scanned display channel by digit and segment, or by row, column and color, and writes the same linear
 * channel the board map stores.
 */
@Composable
private fun FpgaDisplayChannelPicker(
    display: FpgaScanDisplay,
    binding: FpgaPinBinding.Display,
    valid: Boolean,
    text: Localizer,
    tag: String,
    onBinding: (FpgaPinBinding.Display) -> Unit,
) {
    val position = display.coordinatesOf(binding.channel).takeIf { valid }
    val unset = text("fpga_scan_unset")
    fun choose(row: Int = position?.row ?: 0, column: Int = position?.column ?: 0, plane: Int = position?.plane ?: 0) =
        onBinding(binding.copy(channel = display.channelOf(row, column, plane)))
    FpgaChoice(text(if (display.isSevenSegment) "fpga_scan_digit" else "fpga_scan_row"),
        position?.let { text(display.rowLabel(it.row)) } ?: unset,
        (0 until display.rows).map { it to text(display.rowLabel(it)) }, selectedValue = position?.row,
        testTag = "fpga-scan-row-$tag") { choose(row = it) }
    FpgaChoice(text(if (display.isSevenSegment) "fpga_scan_segment" else "fpga_scan_column"),
        position?.let { text(display.columnLabel(it.column)) } ?: unset,
        (0 until display.columns).map { it to text(display.columnLabel(it)) }, selectedValue = position?.column,
        testTag = "fpga-scan-column-$tag") { choose(column = it) }
    // A pixel binding drives all three planes, so it has no color to pick.
    if (display.isRgb && !binding.rgbPixel) {
        FpgaChoice(text("fpga_scan_color"), position?.let { text(display.planeLabel(it.plane)) } ?: unset,
            (0 until display.planes).map { it to text(display.planeLabel(it)) }, selectedValue = position?.plane,
            testTag = "fpga-scan-color-$tag") { choose(plane = it) }
    }
    Text(if (valid) text(display.channelLabel(binding.channel, binding.rgbPixel)) else text("fpga_invalid_display_channel"),
        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fpga-scan-summary-$tag"))
}

/** Displays are named by their label or type; a number is added only where names collide. */
internal fun scanDisplayNames(displays: List<FpgaScanDisplay>, text: Localizer): List<String> {
    val names = displays.map { display ->
        display.resource.label.ifBlank { text(if (display.isSevenSegment) "fpga_scan_type_seven_segment" else "fpga_scan_type_led_array") }
    }
    return names.mapIndexed { index, name ->
        if (names.count { it == name } < 2) name
        else text("fpga_scan_display_numbered", "name" to name, "index" to names.take(index + 1).count { it == name })
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
