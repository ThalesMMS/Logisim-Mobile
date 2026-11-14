package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedAttributeName
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlin.time.TimeMark

internal enum class EditorMode { INTERACT, EDIT, SELECT, MOVE, WIRE, PLACE, TEXT }

/** The selecting tools: a finger can select there, and Add to selection stays on. */
internal val AdditiveSelectionModes = setOf(EditorMode.EDIT, EditorMode.SELECT, EditorMode.MOVE)
/** The hints of the selecting tools, which describe a background drag that Add to selection changes. */
private val AdditiveHintKeys = setOf(Messages.StatusEditToolTouch, Messages.StatusSelectTouch, Messages.StatusMove)

internal enum class EditorHistoryDirection { UNDO, REDO }

internal data class EditorHistoryAction(val id: Long, val label: LocalizedText)

internal data class PendingPaste(
    val fragment: CircuitFragment,
    val offset: GridPoint = GridPoint(20, 20),
    val historyLabel: LocalizedText = message(Messages.ActionPaste),
    /** The fragment point that follows the pointer: the centre of its bounds, on the 10-unit grid. */
    val anchor: GridPoint = fragment.components.firstOrNull()?.position ?: fragment.wires.first().from,
) {
    fun at(point: GridPoint): PendingPaste = copy(offset = GridPoint(point.x - anchor.x, point.y - anchor.y))
}

/** The centre of [bounds], rounded to the 10-unit grid so a block moved by it stays on the grid. */
internal fun pasteAnchor(bounds: ComponentBounds): GridPoint {
    fun snap(value: Int) = (value + 5).floorDiv(10) * 10
    return GridPoint(snap((bounds.left + bounds.right) / 2), snap((bounds.top + bounds.bottom) / 2))
}

internal const val ExamplePresetKey = "logisim-mobile-example"

internal class EditorState(
    initialXml: String?,
    componentRegistry: ComponentRegistry = ComponentCatalog.registry,
    var defaultTickFrequencyHz: Double = EditorPreferences.DEFAULT_TICK_FREQUENCY_HZ,
    initialImportReport: EditorImportReport? = null,
    initializationCheck: () -> Unit = {},
    initializationFailure: LocalizedText? = null,
    initialReadOnly: Boolean = false,
    private val clipboard: CircuitClipboard = CircuitClipboard(),
    private val jarController: JarEditorController? = null,
) {
    var componentRegistry by mutableStateOf(jarController?.geometry?.registry ?: componentRegistry)
        private set
    private var checkInitialization = initializationCheck
    private val initialImport = if (jarController != null) null else
        initializationFailure?.let { CircProjectImportResult.Failure(listOf(it)) }
            ?: initialXml?.let { checkInitialization(); CircCodec.readProject(it, componentRegistry) }
    private val initialProject = jarController?.geometry?.project ?: (initialImport as? CircProjectImportResult.Success)?.project
        ?: if (initialImport is CircProjectImportResult.Failure) CircuitProject(listOf(CircuitDocument()))
        else defaultProject(DemoCircuits.andGate())
    private var rejectedDocumentXml by mutableStateOf(
        if (jarController != null) jarController.geometry.sourceXml
        else if (initialReadOnly) initialXml ?: CircCodec.writeProject(initialProject, componentRegistry)
        else initialXml.takeIf { initialImport is CircProjectImportResult.Failure }
    )
    private var graphAvailable by mutableStateOf(initialImport !is CircProjectImportResult.Failure)
    private var pendingExtensionImportXml by mutableStateOf<String?>(null)
    val projectAccess: EditorProjectAccess get() = when {
        !graphAvailable -> EditorProjectAccess.PRESERVED_RAW
        documentWritable -> EditorProjectAccess.EDITABLE
        else -> EditorProjectAccess.READ_ONLY
    }
    val documentWritable: Boolean get() = rejectedDocumentXml == null
    var interactionsEnabled by mutableStateOf(true)
        private set
    var afterPlacement = EditorAfterPlacement.EDIT

    fun setInteractionEnabled(enabled: Boolean) {
        interactionsEnabled = enabled
        if (!enabled) {
            stopJarSimulation()
            dismissKeyboardPoke()
            pendingWire = null
            dismissWirePoke()
            pendingPaste = null
            matrixPlacement = null
            cancelDebugCopy()
        }
    }
    private var editor = CircuitEditor(initialProject.mainCircuit, this.componentRegistry)
    private val simulator get() = CircuitSimulator(propagationLimit = SimulationProjectOptions.read(project).propagationLimit, registry = componentRegistry, cancellationCheck = checkInitialization)
    private val past = ArrayDeque<HistoryEntry>()
    private val future = ArrayDeque<HistoryEntry>()
    private var nextHistoryId = 0L
    /** Counts [setTickFrequency] changes; a snapshot older than the last one gets the current rate on restore. */
    private var tickFrequencyEpoch = 0L
    private val clipboardOwner = Any()
    var pendingPaste by mutableStateOf<PendingPaste?>(null)
        private set
    private var drives: Map<String, LogicValue> = emptyMap()
    private var vectorDrives: Map<String, LogicVector> = emptyMap()
    private var startupResetIds: Set<String> = emptySet()
    private var startupVectorDrives: Map<String, LogicVector> = emptyMap()
    private var documentSessionId = 0L
    private var nextDocumentSessionId = 1L
    private var savedIoSequence = 0L
    private var savedIoInteractions = emptyMap<SavedIoTarget, SavedIoInteraction>()
    val documentIdentity: Long get() = documentSessionId
    var onProjectReplaced: () -> Unit = {}
    var onSimulationInvalidated: () -> Unit = {}
    var onSimulationRequested: ((SimulationRunSnapshot, SimulationStep) -> SimulationRequestResult)? = null
    private var runClock: TimeMark? = null
    var onWorkspaceProjectReplaced: (EditorProjectReplacement) -> Unit = {}
    /** Opens a new workspace session: its display name (not the circuit name), its XML and its import report. */
    var openWorkspaceSession: ((LocalizedText, String, EditorImportReport?) -> Boolean)? = null
    var automaticTicksEnabled = true
    private var viewports by mutableStateOf<Map<String, CanvasViewportState>>(emptyMap())

    private val circuitLifetimes = CircuitLifetimes(initialProject)
    private val projectState = mutableStateOf(initialProject)
    var project: CircuitProject
        get() = projectState.value
        private set(value) {
            circuitLifetimes.observe(value)
            projectState.value = value
        }
    /** Survives renaming circuit [id], but not deleting it and creating another circuit under the same id. */
    fun circuitLifetime(id: String): Long = circuitLifetimes[id]
    var activeCircuitName by mutableStateOf(initialProject.mainCircuitName)
        private set
    var activeInstancePath by mutableStateOf<List<String>>(emptyList())
        private set
    private val selections = mutableMapOf<String, Pair<Set<String>, Set<WireSegment>>>()
    var document by mutableStateOf(editor.document)
        private set
    private var projectSimulation = jarController?.state?.value
        ?: simulator.settle(project, inputs = drives, options = SimulationProjectOptions.read(project).asMap())
    val rootSimulation: SimulationState get() = projectSimulation
    private var debugCopy by mutableStateOf<DebugInstanceSimulation?>(null)
    private var pendingDebugInput by mutableStateOf<Pair<String, LogicVector?>?>(null)
    private var pendingDebugPokeBit = 0
    /** Whether the poke awaiting the debugging copy toggles exactly its bit (the accessible Toggle bit, X0). */
    private var pendingDebugPokeAsBit = false
    val isDebuggingInstance: Boolean get() = debugCopy != null
    val debugCopyIdentity: Any? get() = debugCopy
    val canConfirmDebugCopy: Boolean get() = pendingDebugInput != null
    var simulation by mutableStateOf(projectSimulation)
        private set
    var mode by mutableStateOf(EditorMode.INTERACT)
        private set
    /**
     * Touch's sticky Shift: while on, a finger's tap adds or drops an item and a marquee adds to the selection,
     * as Shift does in Evolution's SelectTool. Only the selecting tools keep it; other tools and documents reset it.
     */
    var additiveSelection by mutableStateOf(false)
        private set
    var keepConnectionsWhenMoving by mutableStateOf(true)
    var printerView by mutableStateOf(false)
        private set
    var placementMatrixEnabled by mutableStateOf(false)
        private set
    private var matrixPlacement by mutableStateOf<PendingMatrixPlacement?>(null)
    /**
     * The document [matrixPlacement] belongs to: the one it was started on, or the same circuit once [setTickFrequency]
     * replaced it with only its simulationFrequency changed. Any other document change drops the placement.
     */
    private var matrixPlacementDocument by mutableStateOf<CircuitDocument?>(null)
    val pendingMatrixPlacement: PendingMatrixPlacement? get() = matrixPlacement?.takeIf { matrixPlacementDocument === document }

    var placingTemplate by mutableStateOf<CircuitComponent?>(null)
        private set
    var projectPlacementTool by mutableStateOf<CircuitComponent?>(null)
        private set
    var placingKind by mutableStateOf(ComponentKind.AND)
        private set
    /** Layout preferences for components and circuits created from now on; existing ones keep theirs. */
    var creationDefaults by mutableStateOf(CreationDefaults())
    private var keyboardPokeContext: KbdDraftContext? = null
    private data class PlacementLabelScope(val session: Long, val circuit: String, val kind: ComponentKind, val subcircuit: String?)
    private var placementAutoLabels by mutableStateOf(emptyMap<PlacementLabelScope, String>())
    private val placementLabelScope get() = PlacementLabelScope(documentIdentity, document.id, placingKind, placingTemplate?.circuitName)
    val canAutoLabelPlacement: Boolean get() = documentWritable && interactionsEnabled && mode == EditorMode.PLACE &&
        placingTemplate?.label.isNullOrEmpty() && (placingKind == ComponentKind.SUBCIRCUIT ||
        componentRegistry.definition(placingKind)?.schema?.keys?.any { it.name == "label" } == true)
    val placementAutoLabel: String? get() = if (canAutoLabelPlacement) placementAutoLabels[placementLabelScope] else null
    var selectedId by mutableStateOf<String?>(null)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectedWires by mutableStateOf<Set<WireSegment>>(emptySet())
        private set
    var wirePoke by mutableStateOf<WirePoke?>(null)
        private set
    var pendingWire by mutableStateOf<GridPoint?>(null)
        private set
    var undoHistory by mutableStateOf<List<EditorHistoryAction>>(emptyList())
        private set
    var redoHistory by mutableStateOf<List<EditorHistoryAction>>(emptyList())
        private set
    val canUndo: Boolean get() = undoHistory.isNotEmpty()
    val canRedo: Boolean get() = redoHistory.isNotEmpty()
    var revision by mutableStateOf(0)
        private set
    var viewportReset by mutableStateOf(0)
        private set
    var fitSelection by mutableStateOf(false)
        private set
    private val navigationKey: String get() = workspaceNavigationKey(document.id, activeInstancePath)
    val activeTarget: CircuitNavigationTarget get() = CircuitNavigationTarget(document.id, document.name, activeInstancePath)
    val viewport: CanvasViewportState get() = viewports[navigationKey] ?: CanvasViewportState()

    fun exportViewState(): WorkspaceViewState = WorkspaceViewState(
        activeCircuitId = document.id,
        selectedComponentIds = selectedIds, activeInstancePath = activeInstancePath, selectedWires = selectedWires,
        viewports = viewports.mapValues { (_, viewport) ->
            WorkspaceViewport(viewport.view.scale, viewport.view.pan.x, viewport.view.pan.y)
        },
        viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED,
    )

    fun importViewState(view: WorkspaceViewState) {
        val compatible = view.compatibleWith(project)
        val circuit = project.circuits.firstOrNull { it.id == compatible.activeCircuitId } ?: project.mainCircuit
        val path = compatible.activeInstancePath.takeIf { instanceCircuit(it)?.id == circuit.id }.orEmpty()
        // Restoring the view is not news: the adjustments an open just made stay in the status line (C145).
        val adjustments = statusText.takeIf { importReportStatusShown }
        navigate(CircuitNavigationTarget(circuit.id, circuit.name, path))
        adjustments?.let { statusText = it }
        selectedIds = compatible.selectedComponentIds
        selectedId = selectedIds.lastOrNull()
        selectedWires = compatible.selectedWires
        viewports = compatible.viewports.mapValues { (_, viewport) -> CanvasViewportState(
            view = CircuitViewport(viewport.scale, androidx.compose.ui.geometry.Offset(viewport.panX, viewport.panY)),
            initialized = true, fitted = false,
        ) }
    }

    fun togglePrinterView() {
        dismissKeyboardPoke()
        printerView = !printerView
    }

    fun updateViewport(value: CanvasViewportState) { viewports = viewports + (navigationKey to value) }

    /** The grid point at the centre of the canvas in view, or null before the canvas is measured (C39). */
    val visibleCenter: GridPoint? get() {
        val view = viewport
        if (!view.initialized || view.size.width <= 0 || view.size.height <= 0) return null
        val centre = view.view.world(androidx.compose.ui.geometry.Offset(view.size.width / 2f, view.size.height / 2f))
        return GridPoint((centre.x / 10f).roundToInt() * 10, (centre.y / 10f).roundToInt() * 10)
    }

    fun zoomBy(factor: Float) {
        if (factor.isFinite() && factor > 0f) zoomTo(viewport.view.scale * factor)
    }

    /** Keeps the world point under the center of the canvas in view. */
    fun zoomTo(scale: Float) {
        val current = viewport
        val center = androidx.compose.ui.geometry.Offset(current.size.width / 2f, current.size.height / 2f)
        updateViewport(current.copy(view = current.view.zoomedTo(scale, center, MaxZoomPerDp * canvasDensity),
            fitted = false, initialized = true))
    }

    /** Pixels per dp of the canvas, which the editor reports: zoom percentages and the zoom ceiling count dp. */
    var canvasDensity by mutableStateOf(1f)
    /** The zoom as Evolution shows it: 100% is one dp per circuit unit, whatever the screen density. */
    val zoomPercent: Float get() = zoomPercentOf(viewport.view.scale, canvasDensity)
    fun zoomToPercent(percent: Float) = zoomTo(percent / 100f * canvasDensity)
    /** The hint of the tool in use; the editor opens in the Poke tool. */
    var statusText by mutableStateOf(modeStatus(mode))
        private set
    /**
     * What the status line shows: [statusText], except that while Add to selection is on the selecting tools' hints
     * give way to the additive one. In Move a background drag then draws a marquee instead of panning.
     */
    val displayedStatusText: LocalizedText get() =
        if (additiveSelection && mode in AdditiveSelectionModes && statusText.key in AdditiveHintKeys) message(Messages.StatusAdditiveTouch)
        else statusText
    val status: String get() = statusText.resolve(AppLanguage.PORTUGUESE)
    var importErrorText by mutableStateOf(
        (initialImport as? CircProjectImportResult.Failure)?.let { LocalizedText.join(it.notices) }
    )
        private set
    var jarImportRecoveryAvailable by mutableStateOf(false)
        private set

    var importReport by mutableStateOf(initialImportReport ?: (initialImport as? CircProjectImportResult.Success)?.let { EditorImportReport.from(it) })
        private set
    var importReportVisible by mutableStateOf(importReport?.showOnOpen == true)
        private set
    val importError: String? get() = importErrorText?.resolve(AppLanguage.PORTUGUESE)
    fun showImportReport() { importReportVisible = importReport != null }
    fun dismissImportReport() { importReportVisible = false; importReport = importReport?.consumed() }

    /** The status line still says how many adjustments the open made, so it offers Details beside it (C145). */
    val importReportStatusShown: Boolean get() =
        importReport != null && !importReportVisible && statusText.key in ImportReportStatusKeys

    /**
     * The adjustments of a report that did not open by itself, for the status line; null when there is nothing to say.
     * A [name] must be the report's own origin, so an older report never speaks for a file opened since.
     */
    private fun importAdjustmentsStatus(name: LocalizedText? = null, imported: Boolean = false): LocalizedText? =
        importReport?.takeIf { it.announceOnOpen && !importReportVisible && (name == null || it.origin == name) }
            ?.let { importReportStatus(it, name, imported) }

    /**
     * The adjustments were already announced in this window before it was recreated (C145): the status line does not
     * say them again, and Details leaves with them. The report stays in the File menu.
     */
    fun forgetImportAnnouncement() {
        val report = importReport?.takeIf { it.announceOnOpen } ?: return
        importReport = report.copy(announceOnOpen = false)
        if (statusText.key in ImportReportStatusKeys) statusText = when (statusText.key) {
            // A pasted Project XML says it was imported, as reportOpened does without adjustments.
            ImportReportImportedStatusKey -> LocalizedText(Messages.StatusProjectImported, quantity = project.circuits.size.toLong())
            else -> report.origin?.takeIf { statusText.key == ImportReportFileStatusKey }
                ?.let { message(Messages.StatusFileOpened, "name" to it) } ?: modeStatus(mode)
        }
    }

    /**
     * "Opened NAME", or with how many compatibility adjustments when the report did not open by itself. A workspace
     * session opened from text pasted in the Project XML dialog names no file: it says the project was imported (C145).
     */
    fun reportOpened(name: LocalizedText) {
        val pasted = !name.namesAFile
        statusText = importAdjustmentsStatus(name, imported = pasted)
            ?: if (pasted) LocalizedText(Messages.StatusProjectImported, quantity = project.circuits.size.toLong())
            else message(Messages.StatusFileOpened, "name" to name)
    }

    val selected: CircuitComponent? get() = document.components.firstOrNull { it.id == selectedId }
    val canPaste: Boolean get() = clipboard.entry != null && pendingPaste == null

    init {
        checkInitialization()
        if (jarController == null) restoreExampleStartup()
        checkInitialization()
        importAdjustmentsStatus()?.let { statusText = it }
        if (!documentWritable) statusText = message("document_read_only_preserved")
        checkInitialization = {}
    }

    private fun ensureDocumentWritable(): Boolean {
        if (!interactionsEnabled) return false
        if (documentWritable) return true
        statusText = message("document_read_only_preserved")
        return false
    }

    fun changeMode(newMode: EditorMode) {
        dismissKeyboardPoke()
        mode = newMode
        placementMatrixEnabled = false
        matrixPlacement = null
        dismissWirePoke()
        if (newMode != EditorMode.PLACE && newMode != EditorMode.TEXT) placingTemplate = null
        if (newMode != EditorMode.PLACE) projectPlacementTool = null
        pendingWire = null
        pendingPaste = null
        if (newMode !in AdditiveSelectionModes) additiveSelection = false
        statusText = modeStatus(newMode)
    }

    /** The status hint of [mode]; touch hints name the two-finger pan and zoom and Add to selection. */
    private fun modeStatus(mode: EditorMode): LocalizedText = when (mode) {
        EditorMode.INTERACT -> message(Messages.StatusInteract)
        EditorMode.EDIT -> message(Messages.StatusEditToolTouch)
        EditorMode.SELECT -> message(Messages.StatusSelectTouch)
        EditorMode.MOVE -> message(Messages.StatusMove)
        EditorMode.WIRE -> message(Messages.StatusWireTouch)
        EditorMode.PLACE -> message(Messages.StatusPlace, "name" to placingKind.localizedLabel())
        EditorMode.TEXT -> message(Messages.MouseMappingText)
    }

    fun toggleAdditiveSelection() {
        additiveSelection = !additiveSelection && mode in AdditiveSelectionModes && interactionsEnabled
    }

    fun place(kind: ComponentKind) {
        if (!ensureDocumentWritable()) return
        projectPlacementTool = null
        // Like an Evolution AddTool, the library tool keeps the attributes chosen for it in this session.
        placingTemplate = placementTools[PlacementToolScope(documentSessionId, kind)]
        placingKind = kind
        libraryPlacement = true
        changeMode(EditorMode.PLACE)
    }

    fun place(template: CircuitComponent, projectTool: Boolean = false) {
        if (!ensureDocumentWritable()) return
        projectPlacementTool = template.takeIf { projectTool }
        placingTemplate = template
        placingKind = template.kind
        libraryPlacement = false
        changeMode(EditorMode.PLACE)
    }

    private data class PlacementToolScope(val session: Long, val kind: ComponentKind)
    /**
     * The tool attributes chosen before placing a library component, per kind, for this project session only: they
     * are never written to the file's `<lib><tool>` section (C34; a documented difference from Evolution).
     */
    private var placementTools by mutableStateOf(emptyMap<PlacementToolScope, CircuitComponent>())
    private var libraryPlacement = false

    /** The component the Place tool adds: its template, or a new one with the creation defaults. */
    private fun placementBase(): CircuitComponent? = placingTemplate ?: runCatching {
        componentRegistry.createNew(placingKind, "placement-tool", GridPoint(0, 0), creationDefaults)
    }.getOrNull()

    private fun choosePlacingTemplate(updated: CircuitComponent) {
        placingTemplate = updated
        if (libraryPlacement && projectPlacementTool == null) {
            placementTools = placementTools.filterKeys { it.session == documentSessionId } +
                (PlacementToolScope(documentSessionId, placingKind) to updated)
        }
    }

    /**
     * Sets attributes of the Place tool before placing, as Evolution's attribute table does for a selected AddTool
     * (AttrTableToolModel): the placement preview and every component placed next carry them, exactly as if they had
     * been set on each component after placing. Returns false, with the reason in the status line, when refused.
     */
    fun updatePlacingTemplate(updates: Map<String, String>): Boolean {
        if (mode != EditorMode.PLACE || pendingPaste != null || !ensureDocumentWritable()) return false
        val base = placementBase() ?: return false
        if (updates.all { (name, value) -> base.attributes[name] == value }) return true
        val updated = if (base.kind == ComponentKind.SUBCIRCUIT) base.copy(attributes = base.attributes + updates)
        else try { componentRegistry.reconfigure(base, updates) } catch (error: IllegalArgumentException) {
            statusText = error.localizedDescription(message(Messages.ErrorPropertyValue))
            return false
        }
        choosePlacingTemplate(updated)
        return true
    }

    /** The Place tool turned a quarter clockwise (AddTool's rotate key), or false when it has no facing. */
    fun rotatePlacingTemplate(): Boolean {
        if (mode != EditorMode.PLACE || pendingPaste != null || !ensureDocumentWritable()) return false
        val updated = placementBase()?.rotatedForPlacement() ?: return false
        choosePlacingTemplate(updated)
        return true
    }

    /** Whether the Place tool's component has a facing to choose (a subcircuit always does). */
    val placementHasFacing: Boolean get() = placingKind == ComponentKind.SUBCIRCUIT ||
        componentRegistry.definition(placingKind)?.let { definition ->
            definition.supportsRotation && definition.schema.keys.any { it.name == "facing" }
        } == true

    fun handleToolFacing(direction: Direction): Boolean {
        if (pendingPaste != null) return false
        when (mode) {
            EditorMode.EDIT -> {
                if (selectedIds.isEmpty()) return selectedWires.isNotEmpty()
                if (ensureDocumentWritable()) change(message("history_edit_properties")) {
                    val components = document.components.map { component ->
                        if (component.id in selectedIds) component.withToolFacing(direction) else component
                    }
                    editor.replace(document.copy(components = components))
                }
                return true
            }
            EditorMode.PLACE -> {
                // AddTool.keyPressed: an arrow key sets the facing of any tool that has one.
                if (!placementHasFacing) return false
                if (ensureDocumentWritable()) placementBase()?.withToolFacing(direction)?.let(::choosePlacingTemplate)
                return true
            }
            else -> return false
        }
    }

    /**
     * Menu Tool Rotate Left/Right (MenuTool.MenuComponent): a quarter turn of one component's facing, named
     * like Evolution's rotateComponentAction.
     */
    fun rotateComponent(id: String, left: Boolean): Boolean {
        if (!ensureDocumentWritable()) return false
        val component = document.components.firstOrNull { it.id == id } ?: return false
        if (!ComponentMenu.hasFacing(component, componentRegistry)) return false
        return change(rotationLabel(component)) { editor.updateComponents(mapOf(id to component.quarterTurn(left))) }
    }

    /** Whether [rotateSelected] can turn anything: some selected component has a facing. */
    val canRotateSelection: Boolean get() = selectedComponents().any { ComponentMenu.hasFacing(it, componentRegistry) }

    /**
     * EditTool.attemptRotate for touch: a quarter turn of every selected component with a facing, wires and
     * components without one unchanged, as one undoable step (selectionRefaceAction, or Rotate {name} for one).
     */
    fun rotateSelected(left: Boolean): Boolean {
        if (!ensureDocumentWritable() || pendingPaste != null) return false
        val turning = selectedComponents().filter { ComponentMenu.hasFacing(it, componentRegistry) }
        if (turning.isEmpty()) return false
        val label = turning.singleOrNull()?.let(::rotationLabel) ?: message(Messages.HistoryRotateSelection)
        return change(label) { editor.updateComponents(turning.associate { it.id to it.quarterTurn(left) }) }
    }

    private fun rotationLabel(component: CircuitComponent): LocalizedText = message(Messages.HistoryRotateComponent,
        "name" to (component.circuitName?.let(LocalizedText::literal) ?: component.kind.localizedLabel()))

    /** The effective facing (registry attributes, or the raw ones of a subcircuit) turned a quarter. */
    private fun CircuitComponent.quarterTurn(left: Boolean): CircuitComponent {
        val effective = if (kind == ComponentKind.SUBCIRCUIT) null
            else runCatching { componentRegistry.attributes(this).string("facing") }.getOrNull()
        val current = Direction.parse(effective ?: facing) ?: Direction.EAST
        val turned = ComponentMenu.rotated(current, left).serialized
        return if (kind == ComponentKind.SUBCIRCUIT) copy(attributes = attributes + ("facing" to turned))
            else componentRegistry.reconfigure(this, mapOf("facing" to turned))
    }

    /** Splitter Distribute Ascending/Descending (Splitter.redistribute): no change when already distributed. */
    fun redistributeSplitter(id: String, ascending: Boolean): Boolean {
        if (!ensureDocumentWritable()) return false
        val component = document.components.firstOrNull { it.id == id && it.kind == ComponentKind.SPLITTER } ?: return false
        val updates = ComponentMenu.redistribution(component, ascending)
        if (updates.isEmpty()) return false
        val updated = componentRegistry.reconfigure(component, updates)
        return change(message(if (ascending) Messages.ComponentMenuDistributeAscending else Messages.ComponentMenuDistributeDescending)) {
            editor.updateComponents(mapOf(id to updated))
        }
    }

    /**
     * A MenuExtender item that sets attributes of one component (PLA ROM Clear Contents, the Image items) as one
     * undoable action named [label]. Returns false when nothing changes.
     */
    fun updateComponentAttributes(id: String, updates: Map<String, String>, label: LocalizedText): Boolean {
        if (!ensureDocumentWritable()) return false
        val component = document.components.firstOrNull { it.id == id } ?: return false
        if (updates.all { (name, value) -> component.attributes[name] == value }) return false
        val updated = try { componentRegistry.reconfigure(component, updates) } catch (error: IllegalArgumentException) {
            statusText = error.localizedDescription(message(Messages.ErrorPropertyValue))
            return false
        }
        if (updated == component) return false
        return change(label) { editor.updateComponents(mapOf(id to updated)) }
    }

    private fun CircuitComponent.withToolFacing(direction: Direction): CircuitComponent {
        if (direction.serialized == facing) return this
        if (kind == ComponentKind.SUBCIRCUIT) return copy(attributes = attributes + ("facing" to direction.serialized))
        val definition = componentRegistry.definition(kind) ?: return this
        if (!definition.supportsRotation || definition.schema.keys.none { it.name == "facing" }) return this
        return componentRegistry.reconfigure(this, mapOf("facing" to direction.serialized))
    }

    /**
     * Evolution's AddTool and SelectTool keys: gate size and inputs, label visibility, rotation while
     * adding, and ending Auto Label. Returns false when the key does not apply, so +/− can still zoom.
     */
    fun handleToolShortcut(command: EditorShortcut): Boolean {
        if (!command.toolKey || pendingPaste != null || !interactionsEnabled) return false
        if (command == EditorShortcut.AUTO_LABEL_SELF_NUMBERED_STOP) {
            if (placementAutoLabel == null) return false
            updatePlacementAutoLabel(null)
            statusText = message(Messages.StatusPlace, "name" to placingKind.localizedLabel())
            return true
        }
        when (mode) {
            EditorMode.PLACE -> {
                if (placementMatrixEnabled && matrixPlacement != null) return false
                val template = placementBase() ?: return false
                val updated = (if (command == EditorShortcut.ADD_TOOL_ROTATE) template.rotatedForPlacement()
                    else template.withToolShortcut(command)) ?: return false
                if (ensureDocumentWritable() && updated != template) choosePlacingTemplate(updated)
                return true
            }
            EditorMode.EDIT, EditorMode.SELECT, EditorMode.MOVE -> {
                if (command == EditorShortcut.ADD_TOOL_ROTATE) return false
                val edits = document.components.filter { it.id in selectedIds }
                    .mapNotNull { component -> component.withToolShortcut(command)?.let { component.id to it } }
                if (edits.isEmpty()) return false
                val changed = edits.filter { (id, updated) -> document.components.first { it.id == id } != updated }.toMap()
                if (changed.isNotEmpty() && ensureDocumentWritable()) {
                    change(message("history_edit_properties")) { editor.updateComponents(changed) }
                }
                return true
            }
            else -> return false
        }
    }

    private fun CircuitComponent.rotatedForPlacement(): CircuitComponent? {
        val definition = componentRegistry.definition(kind)
        val rotates = kind == ComponentKind.SUBCIRCUIT ||
            definition != null && definition.supportsRotation && definition.schema.keys.any { it.name == "facing" }
        if (!rotates) return null
        val next = when (Direction.parse(facing)) {
            Direction.NORTH -> Direction.EAST
            Direction.EAST -> Direction.SOUTH
            Direction.SOUTH -> Direction.WEST
            else -> Direction.NORTH
        }
        return withToolFacing(next)
    }

    /** The component after [command], itself when the value is already at its limit, or null when it does not apply. */
    private fun CircuitComponent.withToolShortcut(command: EditorShortcut): CircuitComponent? {
        if (kind == ComponentKind.SUBCIRCUIT) return null
        val definition = componentRegistry.definition(kind) ?: return null
        @Suppress("UNCHECKED_CAST")
        fun key(name: String) = definition.schema.keys.firstOrNull { it.name == name } as AttributeKey<Any?>?
        fun AttributeKey<Any?>.accepts(raw: String) = parse(raw)?.let(validate) == true
        fun AttributeKey<Any?>.current() = attributes[name] ?: format(default)
        fun set(name: String, raw: String): CircuitComponent? =
            if (attributes[name] == raw) this else runCatching { componentRegistry.reconfigure(this, mapOf(name to raw)) }.getOrNull()
        val gate = definition.library == "#Gates"
        return when (command) {
            EditorShortcut.GATE_MODIFIER_SIZE_SMALL, EditorShortcut.GATE_MODIFIER_SIZE_MEDIUM, EditorShortcut.GATE_MODIFIER_SIZE_WIDE -> {
                // Evolution's narrow, medium and wide gate sizes; NOT and buffers use a different size attribute.
                val size = key("size")?.takeIf { gate && listOf("30", "50", "70").all(it::accepts) } ?: return null
                val raw = when (command) {
                    EditorShortcut.GATE_MODIFIER_SIZE_SMALL -> "30"
                    EditorShortcut.GATE_MODIFIER_SIZE_MEDIUM -> "50"
                    else -> "70"
                }
                set(size.name, raw)
            }
            EditorShortcut.GATE_MODIFIER_INPUT_ADD, EditorShortcut.GATE_MODIFIER_INPUT_SUB -> {
                val inputs = key("inputs")?.takeIf { gate } ?: return null
                val count = inputs.current().toIntOrNull() ?: return null
                val next = (count + if (command == EditorShortcut.GATE_MODIFIER_INPUT_ADD) 1 else -1).toString()
                if (next.toInt() < 2 || !inputs.accepts(next)) this else set(inputs.name, next) ?: this
            }
            EditorShortcut.AUTO_LABEL_TOGGLE, EditorShortcut.AUTO_LABEL_VIEW, EditorShortcut.AUTO_LABEL_HIDE -> {
                val visible = key("labelvisible") ?: return null
                val shown = visible.current() == "true"
                val next = when (command) {
                    EditorShortcut.AUTO_LABEL_TOGGLE -> !shown
                    EditorShortcut.AUTO_LABEL_VIEW -> true
                    else -> false
                }
                set(visible.name, next.toString())
            }
            else -> null
        }
    }

    fun placementPreview(): CircuitComponent? {
        if (mode != EditorMode.PLACE || !documentWritable || !interactionsEnabled) return null
        return placingTemplate ?: componentRegistry.createNew(placingKind, "placement-preview", GridPoint(0, 0), creationDefaults)
    }

    fun togglePlacementMatrix() {
        if (mode != EditorMode.PLACE || pendingPaste != null || !ensureDocumentWritable()) return
        placementMatrixEnabled = !placementMatrixEnabled
        matrixPlacement = null
        statusText = if (placementMatrixEnabled) message(Messages.MatrixPlaceChooseOrigin)
            else message(Messages.StatusPlace, "name" to placingKind.localizedLabel())
    }

    fun cancelMatrixPlacement() {
        matrixPlacement = null
        statusText = message(Messages.StatusOperationCancelled)
    }

    fun confirmMatrixPlacement(
        placement: PendingMatrixPlacement,
        columns: Int,
        rows: Int,
        dx: Int,
        dy: Int,
        baseLabel: String,
    ): Boolean {
        if (pendingMatrixPlacement !== placement || !ensureDocumentWritable()) return false
        val copies = placement.components(columns, rows, dx, dy, baseLabel) ?: run {
            statusText = message(Messages.MatrixPlaceInvalid)
            return false
        }
        matrixLabelProblem(document, componentRegistry, placement.template.kind, baseLabel, columns, rows)?.let {
            statusText = it
            return false
        }
        var ids = emptySet<String>()
        if (!change(message(Messages.MatrixPlaceTitle)) {
                requirePlaceable(placement.template)
                ids = editor.addComponents(copies)
            }) return false
        matrixPlacement = null
        placementMatrixEnabled = false
        if (afterPlacement == EditorAfterPlacement.EDIT) changeMode(EditorMode.EDIT)
        selectComponent(ids.lastOrNull())
        selectedIds = ids
        statusText = message(Messages.MatrixPlaceInserted, "count" to ids.size)
        return true
    }

    private fun requirePlaceable(template: CircuitComponent?) {
        val circuit = template?.circuitName?.let(project::circuit) ?: return
        requireLocalized(ProjectCircuitLibraries.canPlace(project, circuit, document)) {
            message("active_library_recursive_placement")
        }
    }

    fun setPlacementAutoLabel(value: String): Boolean {
        val label = value.trim()
        if (!canAutoLabelPlacement || label.isNotEmpty() && !PlacementLabels.isValid(label)) return false
        updatePlacementAutoLabel(label.takeIf { it.isNotEmpty() })
        return true
    }

    private fun updatePlacementAutoLabel(next: String?) {
        val scope = placementLabelScope
        placementAutoLabels = if (next == null) placementAutoLabels - scope else placementAutoLabels + (scope to next)
    }

    fun activateTextTool(prototype: CircuitComponent) {
        if (!ensureDocumentWritable()) return
        placingTemplate = prototype
        libraryPlacement = false
        changeMode(EditorMode.TEXT)
    }

    fun beginTextToolEdit(target: TextToolTarget, mappedPrototype: CircuitComponent?): TextToolEdit? {
        if (!interactionsEnabled || !ensureDocumentWritable()) return null
        return when (target) {
            is TextToolTarget.Existing -> document.components.firstOrNull { it.id == target.id }
                ?.let { TextToolEdit(document, it, isNew = false, labelRules = textToolLabelRules(it)) }
            is TextToolTarget.Empty -> {
                val prototype = mappedPrototype ?: placingTemplate?.takeIf { mode == EditorMode.TEXT } ?: return null
                // TextTool starts from TEXT_TOOL_COLOR unless the tool itself carries a color.
                val colored = if (prototype.kind == ComponentKind.TEXT && "color" !in prototype.attributes)
                    prototype.copy(attributes = prototype.attributes + creationDefaults.textAttributes()) else prototype
                // A Text without a font takes the project's default (Evolution: bold 16), left implicit on save.
                val font = AnnotationLibrary.defaultTextFont(project.isOriginalLogisim)
                val withFont = if (colored.kind == ComponentKind.TEXT && "font" !in colored.attributes)
                    colored.copy(attributes = colored.attributes + ("font" to font),
                        implicitAttributes = colored.implicitAttributes + ("font" to font)) else colored
                TextToolEdit(document, withFont.copy(position = target.point), isNew = true,
                    labelRules = textToolLabelRules(withFont))
            }
        }
    }

    /** The name rules the Text tool checks a label of [component] against: Evolution's, unless the project is original Logisim. */
    private fun textToolLabelRules(component: CircuitComponent): TextToolLabelRules? =
        if (component.kind == AnnotationLibrary.Text || !project.followsEvolutionNames()) null
        else TextToolLabelRules(component.circuitName ?: componentRegistry.definition(component.kind)?.fileName)

    /**
     * Why Evolution would refuse [value] as the label the Text tool edits (InstanceComponent's SyntaxChecker), or null:
     * a Text annotation's text, an unchanged label and original Logisim projects are never checked (C41).
     */
    fun textToolProblem(edit: TextToolEdit, value: String): LocalizedText? = edit.problem(value)

    /** Applies the Text tool's [value]; returns why it was refused (the dialog then stays open), or null. */
    fun applyTextToolEdit(edit: TextToolEdit, value: String): LocalizedText? {
        if (document !== edit.document || !interactionsEnabled || !ensureDocumentWritable()) return null
        val component = edit.component
        if (component.kind == ComponentKind.TEXT) {
            // Evolution's TextTool.editingStopped never adds an empty new Text, and for a cleared existing Text it
            // commits CircuitMutation.add of the same component: the previous text stays (d58a61e0, #346).
            if (value.isEmpty()) return null
            if (value == edit.initialText && !edit.isNew) return null
            val updated = componentRegistry.reconfigure(component, mapOf("text" to value))
            if (edit.isNew) {
                insertMappedComponent(updated, component.position)
            } else if (change(message("history_edit_text")) { editor.updateComponents(mapOf(component.id to updated)) }) {
                selectComponent(component.id)
            }
        } else if (value != edit.initialText) {
            textToolProblem(edit, value)?.let { statusText = it; return it }
            if (change(message("history_edit_label")) { editor.updateComponents(mapOf(component.id to component.copy(label = value))) }) {
                selectComponent(component.id)
            }
        }
        return null
    }

    fun tap(point: GridPoint) = tap(point, mode)

    fun tap(point: GridPoint, gestureMode: EditorMode) {
        if (!interactionsEnabled) return
        if (pendingPaste != null) {
            movePastePreview(point)
            confirmPaste()
            return
        }
        if (!documentWritable && gestureMode in setOf(EditorMode.PLACE, EditorMode.WIRE)) { ensureDocumentWritable(); return }
        when (gestureMode) {
            EditorMode.TEXT -> Unit // The canvas resolves measured labels before dispatch.
            EditorMode.PLACE -> {
                val template = placingTemplate
                val label = placementAutoLabel?.let { start ->
                    PlacementLabels.nextAvailable(start, PlacementLabels.unavailable(document, componentRegistry, placingKind))
                        ?: run { statusText = message("placement_auto_label_exhausted"); return }
                }
                if (placementMatrixEnabled && mode == EditorMode.PLACE) {
                    val prototype = template ?: componentRegistry.createNew(placingKind, "matrix", GridPoint(0, 0), creationDefaults)
                    val bounds = prototype.layout(componentRegistry).bounds
                    matrixPlacement = PendingMatrixPlacement(document, prototype, point, bounds, label ?: prototype.label)
                    matrixPlacementDocument = document
                    return
                }
                var id: String? = null
                if (!change(componentPlacementLabel(placingKind)) {
                        requirePlaceable(template)
                        val prototype = if (label == null) template else {
                            val source = template ?: componentRegistry.createNew(placingKind, "placement-tool", point, creationDefaults)
                            source.copy(label = label)
                        }
                        id = prototype?.let { editor.add(it, point) } ?: editor.add(placingKind, point, creationDefaults)
                    }) return
                if (label != null && placingKind != ComponentKind.TUNNEL) updatePlacementAutoLabel(PlacementLabels.after(label) ?: label)
                finishPlacement(id, placingKind, afterPlacement)
            }
            EditorMode.WIRE -> {
                val terminal = nearestConnection(point)
                val from = pendingWire
                if (from == null) {
                    pendingWire = terminal
                    statusText = message(Messages.StatusSelectDestination)
                } else if (from == terminal) {
                    pendingWire = null
                    statusText = message(Messages.StatusConnectionCancelled)
                } else {
                    if (!change(message("history_connect_wire")) { editor.connect(from, terminal) }) return
                    pendingWire = null
                    statusText = message(Messages.StatusWireConnected)
                }
            }
            EditorMode.INTERACT, EditorMode.EDIT, EditorMode.SELECT, EditorMode.MOVE -> {
                val component = document.components.asReversed().firstOrNull { it.contains(point, componentRegistry) }
                selectComponent(component?.id)
                if (gestureMode == EditorMode.INTERACT && component?.kind == ComponentKind.CLOCK) {
                    if (project.isOriginalLogisim) sendComponentEvent(component.id, ClockPoke) else tick()
                } else if (gestureMode == EditorMode.INTERACT && component?.kind == WiringLibrary.PowerOnReset) {
                    sendComponentEvent(component.id, WiringLibrary.PowerOnResetTriggered)
                } else if (gestureMode == EditorMode.INTERACT && component?.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)) {
                    toggle(component!!.id)
                } else if (component == null) {
                    document.wires.firstOrNull { it.contains(point) }?.let { wire ->
                        if (gestureMode == EditorMode.INTERACT) pokeWire(wire, point)
                        else {
                            selectWire(wire)
                            statusText = message(Messages.StatusWireSelected)
                        }
                    }
                }
            }
        }
    }

    fun drawEditToolWire(from: GridPoint, to: GridPoint, verticalFirst: Boolean = false) {
        if (from == to || !interactionsEnabled || !ensureDocumentWritable()) return
        var shortened = false
        if (!change(message("history_connect_wire")) {
                shortened = editor.backtrackWire(from, to)
                if (!shortened) editor.connect(from, to, verticalFirst)
            }) return
        pendingWire = null
        if (shortened) selectedWires = selectedWires.filter { it in document.wires }.toSet()
        statusText = message(if (shortened) Messages.StatusSelectionRemoved else Messages.StatusWireConnected)
    }

    private fun nearestConnection(point: GridPoint): GridPoint {
        val ports = document.components.flatMap { it.inputPorts(componentRegistry) + it.outputPorts(componentRegistry) }
        ports.minByOrNull { abs(it.x - point.x) + abs(it.y - point.y) }
            ?.takeIf { abs(it.x - point.x) <= 20 && abs(it.y - point.y) <= 20 }
            ?.let { return it }
        val wire = document.wires.minByOrNull {
            if (it.from.x == it.to.x) abs(it.from.x - point.x) +
                abs(point.y - point.y.coerceIn(minOf(it.from.y, it.to.y), maxOf(it.from.y, it.to.y)))
            else abs(it.from.y - point.y) +
                abs(point.x - point.x.coerceIn(minOf(it.from.x, it.to.x), maxOf(it.from.x, it.to.x)))
        }
        if (wire != null) {
            val projection = if (wire.from.x == wire.to.x) GridPoint(wire.from.x,
                point.y.coerceIn(minOf(wire.from.y, wire.to.y), maxOf(wire.from.y, wire.to.y)))
            else GridPoint(point.x.coerceIn(minOf(wire.from.x, wire.to.x), maxOf(wire.from.x, wire.to.x)), wire.from.y)
            if (abs(projection.x - point.x) + abs(projection.y - point.y) <= 10) return projection
        }
        return point
    }

    fun pokeWire(wire: WireSegment, point: GridPoint) {
        if (!interactionsEnabled || wire !in document.wires) return
        selectWire(wire)
        wirePoke = WirePoke(wire, projectOntoWire(wire, point))
        statusText = message(Messages.StatusWireSelected)
    }

    fun dismissWirePoke() { wirePoke = null }

    fun selectComponent(id: String?) {
        dismissKeyboardPoke()
        dismissWirePoke()
        val component = document.components.firstOrNull { it.id == id }
        selectedId = component?.id
        selectedIds = component?.let { setOf(it.id) } ?: emptySet()
        selectedWires = emptySet()
        if (component != null) statusText = message(Messages.StatusComponentSelected, "name" to component.displayText())
    }

    fun select(id: String, additive: Boolean = false) {
        if (document.components.none { it.id == id }) return
        dismissKeyboardPoke()
        dismissWirePoke()
        selectedIds = if (additive) {
            if (id in selectedIds) selectedIds - id else selectedIds + id
        } else setOf(id)
        selectedId = selectedIds.lastOrNull()
        if (!additive) selectedWires = emptySet()
    }

    fun selectWire(wire: WireSegment, additive: Boolean = false) {
        if (wire !in document.wires) return
        dismissKeyboardPoke()
        dismissWirePoke()
        if (!additive) { selectedIds = emptySet(); selectedId = null }
        selectedWires = if (additive) {
            if (wire in selectedWires) selectedWires - wire else selectedWires + wire
        } else setOf(wire)
    }

    fun splitWireAt(point: GridPoint): Boolean {
        if (!ensureDocumentWritable()) return false
        if (document.wires.none { it.contains(point) && it.from != point && it.to != point }) return false
        val original = document
        change(message("history_split_wire")) { editor.splitWire(point) }
        if (document == original) return false
        selectedIds = emptySet()
        selectedId = null
        selectedWires = document.wires.filter { it.from == point || it.to == point }.toSet()
        statusText = message("status_wire_split")
        return true
    }

    fun moveWireCorner(from: GridPoint, to: GridPoint): Boolean {
        if (!ensureDocumentWritable()) return false
        if (from !in wireHandlePoints(document, selectedWires, componentRegistry)) return false
        val original = document
        val oldSelection = selectedWires
        change(message("history_move_wire")) { editor.moveWirePoint(from, to) }
        if (document == original) return false
        selectedWires = oldSelection.filter { it in document.wires }.toSet() + document.wires.filter { it !in original.wires }
        statusText = message("status_wire_corner_moved")
        return true
    }

    fun handleCanvasCommand(command: CanvasCommand) {
        when (command) {
            CanvasCommand.COPY -> copySelected()
            CanvasCommand.CUT -> cutSelected()
            CanvasCommand.PASTE -> paste()
            CanvasCommand.DUPLICATE -> duplicateSelected()
            CanvasCommand.DELETE -> removeSelected()
            CanvasCommand.SELECT_ALL -> selectAll()
            CanvasCommand.UNDO -> undo()
            CanvasCommand.REDO -> redo()
            CanvasCommand.CANCEL -> {
                pendingWire = null
                dismissWirePoke()
                pendingPaste = null
                if (matrixPlacement != null) matrixPlacement = null
                else placementMatrixEnabled = false
                statusText = message(Messages.StatusOperationCancelled)
            }
            CanvasCommand.FIT_SELECTION -> fitSelected()
        }
    }

    fun removeComponent(id: String): Boolean {
        if (!ensureDocumentWritable()) return false
        if (document.components.none { it.id == id }) return false
        return removeSelection(setOf(id), emptySet())
    }

    fun removeWire(wire: WireSegment): Boolean {
        if (!ensureDocumentWritable()) return false
        if (wire !in document.wires) return false
        return removeSelection(emptySet(), setOf(wire))
    }

    fun focusLocation(location: EditorLocation) {
        val circuit = project.circuits.firstOrNull { it.id == location.circuitId } ?: return
        if (location.instancePath.isNotEmpty() && instanceCircuit(location.instancePath)?.id != circuit.id) return
        navigate(CircuitNavigationTarget(circuit.id, circuit.name, location.instancePath))
        selectedIds = location.componentIds.filter { id -> document.components.any { it.id == id } }.toSet()
        selectedId = selectedIds.lastOrNull()
        selectedWires = location.wires.filter { it in document.wires }.toSet()
        if (selectedIds.isEmpty() && selectedWires.isEmpty()) fitViewport() else fitSelected()
    }

    fun connectTerminals(from: GridPoint, to: GridPoint): Boolean {
        if (!ensureDocumentWritable()) return false
        val terminals = document.components.flatMap { it.inputPorts(componentRegistry) + it.outputPorts(componentRegistry) }.toSet()
        if (from == to || from !in terminals || to !in terminals) return false
        val before = document
        change(message("history_connect_wire")) { editor.connect(from, to) }
        return document != before
    }

    fun insertComponent(kind: ComponentKind, point: GridPoint): Boolean {
        if (!ensureDocumentWritable()) return false
        if (componentRegistry.definition(kind) == null) return false
        var id: String? = null
        if (!change(componentPlacementLabel(kind)) { id = editor.add(kind, point, creationDefaults) }) return false
        if (id == null) return false
        selectComponent(id)
        return true
    }

    fun insertMappedComponent(prototype: CircuitComponent, point: GridPoint, applyAfterPlacement: Boolean = false) {
        if (!ensureDocumentWritable()) return
        var id: String? = null
        if (!change(componentPlacementLabel(prototype.kind)) {
                requirePlaceable(prototype)
                id = editor.add(prototype, point)
            }) return
        finishPlacement(id, prototype.kind, if (applyAfterPlacement) afterPlacement else EditorAfterPlacement.KEEP_TOOL)
    }

    /**
     * A placement drag released off the canvas adds nothing. As AddTool.mouseReleased in SHOW_ADD_NO, determineNext
     * still applies "Edit tool after adding": the selection is dropped and, when [switchTool], the Edit tool is
     * chosen. A mapped button other than the primary one does not switch, as Canvas restores its tempTool.
     */
    fun releasePlacementOffCanvas(switchTool: Boolean = true) {
        if (afterPlacement != EditorAfterPlacement.EDIT) return
        if (switchTool) changeMode(EditorMode.EDIT)
        clearSelection()
    }

    private fun finishPlacement(id: String?, kind: ComponentKind, next: EditorAfterPlacement) {
        if (next == EditorAfterPlacement.EDIT) changeMode(EditorMode.EDIT)
        selectComponent(id)
        val status = when (next) {
            EditorAfterPlacement.EDIT -> Messages.StatusComponentInsertedInEditMode
            EditorAfterPlacement.KEEP_TOOL -> Messages.StatusComponentInserted
        }
        statusText = message(status, "name" to kind.localizedLabel())
    }

    fun activateComponent(id: String): Boolean {
        if (jarController != null) return sendJarInput(id)
        if (!ensureDocumentWritable()) return false
        val component = document.components.firstOrNull { it.id == id } ?: return false
        if (component.kind == ComponentKind.BUTTON && componentRegistry.definition(component.kind)?.behavior != null) {
            val pressed = sendComponentEvent(id, IoInputEvent.Press(true))
            val released = sendComponentEvent(id, IoInputEvent.Press(false))
            return pressed && released
        }
        // Toggle on a Clock does what the Poke tool's tap does (X0): ClockPoker in a 2.7.1 project, a tick otherwise.
        if (component.kind == ComponentKind.CLOCK) {
            if (project.isOriginalLogisim) {
                // ClockPoker reaches the clock's state only in the main circuit or an instance view; elsewhere the
                // status line says why nothing happened, as Open controls does.
                if (activeInstancePath.isEmpty() && activeCircuitName != project.mainCircuitName) {
                    statusText = message("status_io_controls_instance", "name" to component.displayText())
                    return false
                }
                return sendComponentEvent(id, ClockPoke)
            }
            tick()
            return true
        }
        if (component.kind !in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)) return false
        if (activeCircuitName != project.mainCircuitName &&
            (activeInstancePath.isEmpty() || component.kind != ComponentKind.INPUT)) return false
        toggle(id)
        return true
    }

    fun selectAll() {
        dismissWirePoke()
        selectedIds = document.components.map { it.id }.toSet()
        selectedId = selectedIds.lastOrNull()
        selectedWires = document.wires.toSet()
        statusText = LocalizedText(Messages.StatusComponentsSelected, quantity = selectedIds.size.toLong())
    }

    fun selectInArea(from: GridPoint, to: GridPoint, additive: Boolean = false) {
        dismissKeyboardPoke()
        dismissWirePoke()
        val left = minOf(from.x, to.x)
        val right = maxOf(from.x, to.x)
        val top = minOf(from.y, to.y)
        val bottom = maxOf(from.y, to.y)
        fun inside(point: GridPoint) = point.x in left..right && point.y in top..bottom
        val ids = document.components.filter { component ->
            val bounds = component.bounds(componentRegistry)
            bounds.left >= left && bounds.right <= right && bounds.top >= top && bounds.bottom <= bottom
        }.map { it.id }.toSet()
        val wires = document.wires.filter { inside(it.from) && inside(it.to) }.toSet()
        selectedIds = if (additive) selectedIds + ids else ids
        selectedWires = if (additive) selectedWires + wires else wires
        selectedId = selectedIds.lastOrNull()
        statusText = message(Messages.StatusSelectionCounts, "components" to LocalizedText(Messages.ProjectComponentCount, quantity = selectedIds.size.toLong()),
            "wires" to LocalizedText(Messages.ProjectWireCount, quantity = selectedWires.size.toLong()))
    }

    /** Whether a decimal or float input pin can open the value editor (Evolution's EditDecimal/EditFloat). */
    fun canEditInputValue(id: String): Boolean = documentWritable && interactionsEnabled &&
        document.components.any { it.id == id && it.kind == ComponentKind.INPUT } &&
        (activeInstancePath.isNotEmpty() || activeCircuitName == project.mainCircuitName)

    /** Poke without a pointer position (accessibility activation, signal panel): the lowest bit or digit. */
    fun toggle(id: String) = pokePin(id, 0)

    /**
     * Poke tool on an input Pin at [bit], as Evolution's PinPoker.handleBitPress: toggles the bit (radix 2) or
     * advances the hexadecimal/octal digit starting at [bit]; tri-state pins cycle U → 0 → 1 → U.
     */
    fun pokePin(id: String, bit: Int) { pokePin(id, bit, asBit = false) }

    /**
     * The accessible Toggle bit action (X0): toggles [bit] of an input pin whatever its display radix (a tri-state
     * pin cycles it U → 0 → 1). Returns false, with the reason in the status line where there is one, when nothing
     * changed: [id] is not an input pin with that bit, the editor is locked or read-only, or the circuit is shown
     * outside an instance. In an instance view it returns true once the debugging copy is asked for, and the
     * confirmation toggles the same bit.
     */
    fun toggleInputBit(id: String, bit: Int): Boolean {
        val component = document.components.firstOrNull { it.id == id && it.kind == ComponentKind.INPUT } ?: return false
        if (bit !in 0 until component.width) return false
        val before = statusText
        val applied = pokePin(id, bit, asBit = true)
        // A refusal without a reason of its own (a locked editor) is still reported, never silent.
        if (!applied && statusText === before) statusText = message("a11y_explorer_operation_failed")
        return applied
    }

    /**
     * Whether the inspector shows the I/O controls of [id] (a DIP switch, joystick, keyboard or keypad), for the
     * accessible Open controls action (X0): it shows them, as IoInputPanel, in a writable document's main circuit or
     * instance view. When it does not, the status line says why and nothing opens.
     */
    fun requestIoControls(id: String): Boolean {
        val component = document.components.firstOrNull { it.id == id && IoLibrary.isInteractive(it.kind) } ?: return false
        val before = statusText
        if (!ensureDocumentWritable()) {
            if (statusText === before) statusText = message("a11y_explorer_operation_failed")
            return false
        }
        if (activeInstancePath.isNotEmpty() || activeCircuitName == project.mainCircuitName) return true
        statusText = message("status_io_controls_instance", "name" to component.displayText())
        return false
    }

    /**
     * Whether the value dialog of input [id] may open (the accessible Set value action, X0); when it may not, the
     * status line says why, as a poke there would.
     */
    fun requestInputValueEdit(id: String): Boolean {
        if (canEditInputValue(id)) return true
        if (document.components.none { it.id == id && it.kind == ComponentKind.INPUT }) return false
        val before = statusText
        if (ensureDocumentWritable()) statusText = message(Messages.StatusSubcircuitInput)
        if (statusText === before) statusText = message("a11y_explorer_operation_failed")
        return false
    }

    /** Pokes [bit] as a tap does, or toggles exactly that bit when [asBit]; returns whether the input changed or awaits confirmation. */
    private fun pokePin(id: String, bit: Int, asBit: Boolean): Boolean {
        if (jarController != null) return if (asBit) sendJarBit(id, bit) else sendJarInput(id)
        if (!ensureDocumentWritable()) return false
        val component = document.components.firstOrNull {
            it.id == id && it.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)
        } ?: return false
        dismissResetUndo()
        val tristate = PinPoke.behavior(component.attributes) == "tristate"
        val radix = if (asBit) "2" else component.attributes["radix"] ?: "2"
        fun next(current: LogicVector?): LogicVector {
            val intended = current?.takeIf { it.width == component.width }
                ?: if (tristate) LogicVector.unknown(component.width) else LogicVector.low(component.width)
            return PinPoke.poke(intended, bit.coerceIn(0, component.width - 1), radix, tristate)
        }
        if (activeInstancePath.isNotEmpty() && component.kind == ComponentKind.INPUT) {
            val copy = debugCopy
            if (copy == null) {
                pendingDebugInput = id to null
                pendingDebugPokeBit = bit
                pendingDebugPokeAsBit = asBit
                return true
            }
            changeInstanceInput(id, next(copy.drives[id] ?: simulation.vectorValues[id]))
            return true
        }
        if (activeCircuitName != project.mainCircuitName) {
            statusText = message(Messages.StatusSubcircuitInput)
            return false
        }
        val current = vectorDrives[id] ?: drives[id]?.let { LogicVector.fromBits(listOf(it)) } ?: projectSimulation.vectorValues[id]
        val value = next(current)
        if (component.width == 1 && value.bit(0) != LogicValue.UNKNOWN) {
            drives = drives + (id to value.bit(0))
            vectorDrives = vectorDrives - id
        } else {
            drives = drives - id
            vectorDrives = vectorDrives + (id to value)
        }
        settleProject()
        statusText = message(Messages.StatusInputValue, "name" to component.displayText(),
            "value" to if (component.width == 1) value.bit(0).symbol() else value.display)
        return true
    }

    /**
     * Toggles [bit] of a pin in the live JAR simulation, which takes a whole value: the current value with that bit
     * flipped. A value with unknown bits cannot be sent, as for the pin's value dialog.
     */
    private fun sendJarBit(id: String, bit: Int): Boolean {
        val component = document.components.firstOrNull { it.id == id && it.kind == ComponentKind.INPUT } ?: return false
        val current = projectSimulation.vectorValues[id]?.takeIf { it.width == component.width } ?: LogicVector.low(component.width)
        val number = PinPoke.poke(current, bit.coerceIn(0, component.width - 1), "2", tristate = false).toULongOrNull()
        if (number == null) { statusText = message("input_value_unknown_unsupported"); return false }
        return sendJarInput(id, number)
    }

    fun setInputValue(id: String, value: ULong) {
        if (jarController != null) { sendJarInput(id, value); return }
        val component = document.components.firstOrNull {
            it.id == id && it.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)
        } ?: return
        setInputValue(id, LogicVector.of(component.width, value))
    }

    /**
     * Drives input [id] with [value], as Evolution's Pin editors set PinState.intendedValue: unknown bits reach the
     * circuit only from a tri-state pin's editor. Only the simulation's drives change; the document does not. The live
     * JAR simulation takes defined values only, so an unknown value there is refused with a notice.
     */
    fun setInputValue(id: String, value: LogicVector) {
        if (jarController != null) {
            val number = value.toULongOrNull()
            if (number == null) statusText = message("input_value_unknown_unsupported") else sendJarInput(id, number)
            return
        }
        if (!ensureDocumentWritable()) return
        val component = document.components.firstOrNull {
            it.id == id && it.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON)
        } ?: return
        val vector = if (value.width == component.width) value else value.resize(component.width)
        dismissResetUndo()
        if (activeInstancePath.isNotEmpty() && component.kind == ComponentKind.INPUT) {
            changeInstanceInput(id, vector)
            return
        }
        if (activeCircuitName != project.mainCircuitName) return
        drives = drives - id
        vectorDrives = vectorDrives + (id to vector)
        settleProject()
        statusText = message(Messages.StatusInputValue, "name" to component.displayText(), "value" to vector.display)
    }

    /**
     * Evolution's Simulator.setTickFrequency: the rate changes while the simulation keeps running, with no undo entry
     * and without clearing the selection or a subcircuit debug copy. SimulationProjectOptions.applyTo writes the value,
     * as Simulation options does, so the options and the main circuit's simulationFrequency serialize unchanged, and
     * the revision bump saves the document. Undo and redo keep the newest rate (see [restore]).
     *
     * The main circuit's document is replaced, so a pending matrix placement follows it to the new document. Drafts
     * that tool panels key on the document identity, such as the annotation fields, start again from the circuit.
     */
    fun setTickFrequency(hz: Double): Boolean {
        if (!hz.isFinite() || hz <= 0) return false
        if (!ensureDocumentWritable()) return false
        val options = runCatching { SimulationProjectOptions.read(project) }.getOrNull() ?: return false
        if (options.tickFrequencyHz == hz) return true
        val next = options.copy(tickFrequencyHz = hz).applyTo(project)
        tickFrequencyEpoch++
        project = next
        // The main circuit carries simulationFrequency; an editor left on the old document would write it back.
        val active = next.circuits.firstOrNull { it.id == document.id }
        if (active != null && active != document) {
            if (matrixPlacementDocument === document) matrixPlacementDocument = active
            document = active
            editor = CircuitEditor(active, componentRegistry)
        }
        revision++
        return true
    }

    private fun changeInstanceInput(id: String, value: LogicVector) {
        val copy = debugCopy
        if (copy == null) {
            pendingDebugInput = id to value
            return
        }
        val component = document.components.firstOrNull { it.id == id && it.kind == ComponentKind.INPUT } ?: return
        copy.drives = copy.drives + (id to value)
        advanceDebugCopy(SimulationStep.SETTLE)
        statusText = message(Messages.StatusInputValue, "name" to component.displayText(), "value" to value.display)
    }

    fun confirmDebugCopy() {
        val (id, value) = pendingDebugInput ?: return
        val bit = pendingDebugPokeBit
        val asBit = pendingDebugPokeAsBit
        cancelDebugCopy()
        if (!ensureDocumentWritable() || activeInstancePath.isEmpty()) return
        val copy = DebugInstanceSimulation.from(project, projectSimulation, document, activeInstancePath) ?: return
        onSimulationInvalidated()
        runClock = null
        debugCopy = copy
        showActiveSimulation()
        if (value == null) pokePin(id, bit, asBit) else changeInstanceInput(id, value)
    }

    fun cancelDebugCopy() { pendingDebugInput = null }

    fun returnToLiveInstance() {
        if (debugCopy == null) return
        clearDebugCopy()
        showActiveSimulation()
        reportSelectedCircuit()
    }

    private fun clearDebugCopy() {
        cancelDebugCopy()
        if (debugCopy == null) return
        onSimulationInvalidated()
        runClock = null
        debugCopy = null
    }

    private fun advanceDebugCopy(step: SimulationStep) {
        val snapshot = captureRunSnapshot()
        onSimulationRequested?.let { it(snapshot, step); return }
        publishRun(snapshot, advanceSimulation(snapshot, step, componentRegistry,
            autoPropagating = SimulationProjectOptions.read(snapshot.project).autoPropagating).state)
    }

    fun move(id: String, position: GridPoint) {
        if (!ensureDocumentWritable()) return
        val component = document.components.firstOrNull { it.id == id } ?: return
        val movingIds = if (id in selectedIds) selectedIds else setOf(id)
        val delta = GridPoint(position.x - component.position.x, position.y - component.position.y)
        val before = document
        if (!change(message(Messages.ActionMove)) { editor.move(movingIds, delta) }) return
        if (id !in selectedIds) selectComponent(id)
        statusText = if (document != before) message(Messages.StatusSelectionMoved)
            else message(Messages.StatusMoveCancelled)
    }

    fun moveSelected(delta: GridPoint, keepConnections: Boolean = keepConnectionsWhenMoving) {
        if (!ensureDocumentWritable()) return
        val movedWires = selectedWires.map { WireSegment(
            GridPoint(it.from.x + delta.x, it.from.y + delta.y),
            GridPoint(it.to.x + delta.x, it.to.y + delta.y)) }.toSet()
        if (!change(message(Messages.ActionMove)) { editor.move(selectedIds, selectedWires, delta, keepConnections) }) return
        selectedWires = movedWires.filter { it in document.wires }.toSet()
    }

    fun removeSelected() {
        if (!ensureDocumentWritable()) return
        if (selectedIds.isEmpty() && selectedWires.isEmpty()) return
        removeSelection(selectedIds, selectedWires)
    }

    private fun removeSelection(
        ids: Set<String>,
        wires: Set<WireSegment>,
        historyLabel: LocalizedText = message(Messages.ActionDelete),
    ): Boolean {
        val before = document
        if (!change(historyLabel) { editor.remove(ids, wires) }) return false
        clearSelection()
        statusText = message(Messages.StatusSelectionRemoved)
        return document != before
    }

    fun copySelected() {
        val fragment = editor.copy(selectedIds, selectedWires)
        clipboard.copy(fragment, project, clipboardOrigin())
        statusText = if (fragment.isEmpty) message(Messages.StatusCopyHint)
        else message(Messages.StatusClipboardCounts, "components" to LocalizedText(Messages.ProjectComponentCount, quantity = fragment.components.size.toLong()),
            "wires" to LocalizedText(Messages.ProjectWireCount, quantity = fragment.wires.size.toLong()))
    }

    fun cutSelected() {
        if (!ensureDocumentWritable()) return
        copySelected()
        if (clipboard.entry != null) removeSelection(selectedIds, selectedWires, message(Messages.ActionCut))
    }

    fun paste() {
        if (!ensureDocumentWritable() || !canPaste) return
        val fragment = clipboardFragment() ?: return
        pendingWire = null
        pendingPaste = pastePreview(fragment, message(Messages.ActionPaste))
        statusText = message(Messages.StatusSelectDestination)
    }

    /**
     * The preview of [fragment], anchored at the centre of its bounds. It starts 20 units from the original, as
     * before, unless that centre would be outside the visible canvas: then it starts centred in the view (C45).
     */
    private fun pastePreview(fragment: CircuitFragment, label: LocalizedText): PendingPaste {
        val bounds = (fragment.components.mapNotNull { runCatching { it.bounds(componentRegistry) }.getOrNull() } +
            fragment.wires.map { ComponentBounds(minOf(it.from.x, it.to.x), minOf(it.from.y, it.to.y),
                maxOf(it.from.x, it.to.x), maxOf(it.from.y, it.to.y)) })
            .reduceOrNull { a, b -> ComponentBounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
        val anchor = bounds?.let(::pasteAnchor) ?: return PendingPaste(fragment, historyLabel = label)
        val preview = PendingPaste(fragment, historyLabel = label, anchor = anchor)
        val view = viewport
        if (!view.initialized || view.size.width <= 0 || view.size.height <= 0) return preview
        val topLeft = view.view.world(androidx.compose.ui.geometry.Offset.Zero)
        val bottomRight = view.view.world(androidx.compose.ui.geometry.Offset(view.size.width.toFloat(), view.size.height.toFloat()))
        val shown = anchor.x + preview.offset.x
        val shownY = anchor.y + preview.offset.y
        if (shown >= topLeft.x && shown <= bottomRight.x && shownY >= topLeft.y && shownY <= bottomRight.y) return preview
        val centre = view.view.world(androidx.compose.ui.geometry.Offset(view.size.width / 2f, view.size.height / 2f))
        return preview.at(GridPoint(((centre.x / 10f).roundToInt()) * 10, ((centre.y / 10f).roundToInt()) * 10))
    }

    fun movePastePreview(point: GridPoint) {
        pendingPaste = pendingPaste?.at(point)
    }

    fun confirmPaste() {
        val pending = pendingPaste ?: return
        pasteFragment(pending.fragment, pending.offset, pending.historyLabel)
    }

    fun pasteAt(offset: GridPoint) {
        if (!ensureDocumentWritable()) return
        clipboardFragment()?.let { pasteFragment(it, offset) }
    }

    private fun clipboardFragment(): CircuitFragment? {
        val entry = clipboard.entry ?: return null
        if (entry.origin != clipboardOrigin()) {
            for ((name, definition) in entry.subcircuits) {
                if (project.circuit(name) != definition) return rejectClipboardSubcircuit(name)
            }
        }
        for (component in entry.fragment.components) {
            if (component.kind != ComponentKind.SUBCIRCUIT) continue
            val name = requireNotNull(component.circuitName)
            val child = project.circuit(name) ?: return rejectClipboardSubcircuit(name)
            if (child.id != entry.subcircuits.getValue(name).id) return rejectClipboardSubcircuit(name)
            if (!ProjectCircuitLibraries.canPlace(project, child, document)) {
                statusText = message("active_library_recursive_placement")
                return null
            }
        }
        return entry.fragment
    }

    private fun clipboardOrigin() = CircuitClipboard.Origin(clipboardOwner, documentSessionId)

    private fun rejectClipboardSubcircuit(name: String): CircuitFragment? {
        statusText = message("status_paste_subcircuit_mismatch", "name" to name)
        return null
    }

    private fun pasteFragment(
        fragment: CircuitFragment,
        offset: GridPoint,
        historyLabel: LocalizedText = message(Messages.ActionPaste),
    ) {
        if (!ensureDocumentWritable() || fragment.isEmpty) return
        val originalWires = document.wires.toSet()
        var inserted = emptySet<String>()
        if (!change(historyLabel) { inserted = editor.paste(fragment, offset) }) return
        pendingPaste = null
        selectedIds = inserted
        selectedId = inserted.lastOrNull()
        selectedWires = document.wires.filter { it !in originalWires }.toSet()
        statusText = LocalizedText(Messages.StatusComponentsPasted, quantity = inserted.size.toLong())
    }

    fun duplicateSelected() {
        if (!ensureDocumentWritable() || pendingPaste != null) return
        val fragment = editor.copy(selectedIds, selectedWires)
        if (fragment.isEmpty) {
            statusText = message(Messages.StatusCopyHint)
            return
        }
        pendingWire = null
        pendingPaste = pastePreview(fragment, message(Messages.ActionDuplicate))
        statusText = message(Messages.StatusSelectDestination)
    }

    /** Applies an inspector edit that was typed but not applied yet (a label) before [navigate] leaves the circuit. */
    internal var commitPendingEdit: (() -> Unit)? = null

    /**
     * How many times Undo or Redo restored a snapshot. A label typed in the inspector before then is not in the history,
     * as in Evolution, so it is dropped instead of being applied after the undo (which would also clear Redo).
     */
    internal var historyTravels: Long = 0L
        private set

    /** Applies [value] to the selected labels unless Evolution's editor would refuse it (#321); returns whether it did. */
    fun updateSelectedLabel(value: String): Boolean = updateLabels(selectedIds, value)

    /** Applies [value] to the labels of the components [ids] of the active circuit, as [updateSelectedLabel] does. */
    fun updateLabels(ids: Set<String>, value: String): Boolean {
        if (!ensureDocumentWritable()) return false
        val selected = document.components.filter { it.id in ids && it.label != value }
        if (selected.isEmpty()) return true
        selected.firstNotNullOfOrNull { labelProblem(value, it) }?.let { statusText = it; return false }
        return change(message("history_edit_label")) { editor.updateComponents(selected.associate { it.id to it.copy(label = value) }) }
    }

    /** `CircuitAttributes` and `InstanceComponent` checks of Evolution d58a61e0, skipped for original Logisim projects. */
    fun circuitNameProblem(name: String, circuit: CircuitDocument?): LocalizedText? =
        if (project.followsEvolutionNames()) EvolutionNames.circuitNameProblem(name, circuit) else null

    private fun labelProblem(value: String, component: CircuitComponent): LocalizedText? =
        if (component.kind == AnnotationLibrary.Text || !project.followsEvolutionNames()) null
        else EvolutionNames.labelProblem(value, component.circuitName ?: componentRegistry.definition(component.kind)?.fileName)

    fun updateSelectedAttribute(name: String, value: String) = updateSelectedAttributes(mapOf(name to value))

    fun updateSelectedAttributes(updates: Map<String, String>) {
        if (!ensureDocumentWritable()) return
        val selected = selectedComponents()
        if (selected.isEmpty()) return
        try {
            for (name in updates.keys) requireLocalized(selected.all { component ->
                componentRegistry.definition(component.kind)?.schema?.keys?.any { it.name == name } == true
            }) { message(Messages.InspectorNotEditable, "name" to shownAttributeName(selected, name)) }
            // Attaching to a bus saved without an identifier gives it one Evolution reads, in this same edit (C13).
            val attachment = SocComponents.attach(updates, document.components, selected.mapTo(mutableSetOf()) { it.id })
            val replacements = selected.associate { it.id to componentRegistry.reconfigure(it, attachment.updatesFor(it.id)) } +
                document.components.filter { it.id in attachment.others }.associate { it.id to componentRegistry.reconfigure(it, attachment.others.getValue(it.id)) }
            val shared = if ("content" in updates) selected.mapNotNull(project::sharedVhdlDefinition).distinctBy { it.name }
                else emptyList()
            if (shared.isEmpty()) {
                if (change(message("history_edit_properties")) { editor.updateComponents(replacements) }) statusText = message(Messages.StatusPropertyUpdated)
            } else {
                val next = shared.fold(project) { current, definition ->
                    current.updateSharedVhdlSource(definition.name, updates.getValue("content"))
                }
                val active = next.circuits.first { it.id == document.id }
                val primarySelection = selectedId
                val wireSelection = selectedWires
                applyProject(next.withCircuit(active.copy(components = active.components.map { replacements[it.id] ?: it })),
                    message("history_edit_properties"))
                selectedIds = replacements.keys
                selectedId = primarySelection
                selectedWires = wireSelection
                statusText = message(Messages.StatusPropertyUpdated)
            }
        } catch (error: IllegalArgumentException) {
            statusText = error.localizedDescription(message(Messages.ErrorPropertyValue))
        }
    }

    /** [name] as the inspector shows it for [components] ("Color model" for an RGB Video), as validate() names it. */
    private fun shownAttributeName(components: List<CircuitComponent>, name: String): LocalizedText =
        components.firstNotNullOfOrNull { component ->
            componentRegistry.definition(component.kind)?.schema?.keys?.firstOrNull { it.name == name }
        }?.displayName() ?: localizedAttributeName(name)

    fun undo() = travelHistory(EditorHistoryDirection.UNDO)

    fun redo() = travelHistory(EditorHistoryDirection.REDO)

    fun travelHistory(direction: EditorHistoryDirection, throughActionId: Long? = null) {
        if (!interactionsEnabled) return
        val undoing = direction == EditorHistoryDirection.UNDO
        if (undoing && pendingPaste != null) {
            handleCanvasCommand(CanvasCommand.CANCEL)
            if (throughActionId == null) return
        }

        val source = if (undoing) past else future
        val destination = if (undoing) future else past
        val steps = if (throughActionId == null) 1 else {
            val index = source.indexOfLast { it.action.id == throughActionId }
            if (index < 0) return
            source.size - index
        }
        repeat(minOf(steps, source.size)) {
            val entry = source.removeLast()
            destination.addLast(entry.copy(snapshot = snapshot()))
            historyTravels++
            restore(entry.snapshot)
        }
    }

    fun clearHistory() {
        if (!interactionsEnabled) return
        past.clear()
        future.clear()
        updateHistoryAvailability()
    }

    fun captureRunSnapshot(): SimulationRunSnapshot {
        val copy = debugCopy
        val previous = copy?.state ?: projectSimulation
        return SimulationRunSnapshot(
            project = copy?.project ?: project, previous = previous,
            inputs = if (copy == null) drives else emptyMap(), vectorInputs = copy?.drives ?: vectorDrives,
            options = SimulationProjectOptions.read(project).asMap(),
            elapsedTimeMillis = previous.elapsedTimeMillis + (runClock?.elapsedNow()?.inWholeMilliseconds ?: 0L),
            revision = revision.toLong(),
            startupResetIds = if (copy == null) startupResetIds else emptySet(),
            resetVectorInputs = if (copy == null) startupVectorDrives else copy.initialDrives,
        )
    }

    fun isCurrentRunSnapshot(snapshot: SimulationRunSnapshot): Boolean =
        documentWritable && snapshot.project === (debugCopy?.project ?: project) && snapshot.revision == revision.toLong()

    fun setSimulationRunning(running: Boolean) {
        runClock = if (running) TimeSource.Monotonic.markNow() else null
        if (running) dismissResetUndo()
    }

    fun publishRun(snapshot: SimulationRunSnapshot, result: SimulationState) {
        if (!isCurrentRunSnapshot(snapshot)) return
        val copy = debugCopy
        if (copy == null) {
            projectSimulation = result
            persistSavedIoInteractions(snapshot, result)
        } else {
            copy.state = result
        }
        if (runClock != null) runClock = TimeSource.Monotonic.markNow()
        showActiveSimulation()
    }

    private fun persistSavedIoInteractions(snapshot: SimulationRunSnapshot, result: SimulationState) {
        if (snapshot.events.isEmpty()) return
        val updates = mutableMapOf<SavedIoTarget, Map<String, String>>()
        for ((key, events) in snapshot.events) {
            if (events.none { it is IoInputEvent.ToggleSwitch }) continue
            val parts = key.split('/')
            val path = parts.dropLast(1)
            val circuit = if (path.isEmpty()) project.mainCircuit else instanceCircuit(path) ?: continue
            val component = circuit.components.firstOrNull { it.id == parts.last() } ?: continue
            val state = if (path.isEmpty()) result else result.circuitStates[path.joinToString("/")] ?: continue
            val componentState = state.componentStates[component.id] ?: continue
            val attributes = IoLibrary.savedInteractionAttributes(component, componentState)
            if (attributes.isEmpty() || attributes.all { (name, value) -> component.attributes[name] == value }) continue
            updates[SavedIoTarget(circuit.id, component.id)] = attributes
        }
        if (updates.isEmpty()) return
        project = withSavedIoAttributes(project, updates)
        document = project.circuits.first { it.id == document.id }
        editor = CircuitEditor(document, componentRegistry)
        savedIoSequence++
        savedIoInteractions = savedIoInteractions + updates.mapValues { SavedIoInteraction(savedIoSequence, it.value) }
        revision++
    }

    private fun withSavedIoAttributes(source: CircuitProject, updates: Map<SavedIoTarget, Map<String, String>>): CircuitProject {
        var changed = false
        val circuits = source.circuits.map { circuit ->
            var circuitChanged = false
            val components = circuit.components.map { component ->
                val attributes = updates[SavedIoTarget(circuit.id, component.id)]
                if (attributes == null || component.kind != IoKinds.DipSwitch || component.attributes["resetbehavior"] != "last" ||
                    attributes.all { (name, value) -> component.attributes[name] == value }) component
                else {
                    changed = true
                    circuitChanged = true
                    component.copy(attributes = component.attributes + attributes)
                }
            }
            if (circuitChanged) circuit.copy(components = components) else circuit
        }
        return if (changed) source.copy(circuits = circuits) else source
    }

    // Saved input data follows stimulation across schematic undo without creating an undo action.
    private fun preserveNewerSavedIoInteractions(target: Snapshot): Snapshot {
        val updates = savedIoInteractions.filter { (reference, interaction) ->
            interaction.sequence > (target.savedIoInteractions[reference]?.sequence ?: 0L) &&
                target.project.circuits.any { circuit -> circuit.id == reference.circuitId &&
                    circuit.components.any { it.id == reference.componentId && it.kind == IoKinds.DipSwitch && it.attributes["resetbehavior"] == "last" } }
        }
        if (updates.isEmpty()) return target
        return target.copy(
            project = withSavedIoAttributes(target.project, updates.mapValues { it.value.attributes }),
            savedIoInteractions = target.savedIoInteractions + updates,
        )
    }

    internal fun beginKeyboardPoke(id: String) {
        if (!documentWritable || !interactionsEnabled || printerView ||
            document.components.none { it.id == id && it.kind == IoKinds.Keyboard }) return
        selectComponent(id)
        keyboardPokeContext = keyboardDraftContext()
    }

    internal fun dismissKeyboardPoke() {
        keyboardPokeContext = null
    }

    internal fun pokedKeyboardId(): String? = keyboardPokeContext?.takeIf {
        documentWritable && interactionsEnabled && !printerView && it == keyboardDraftContext()
    }?.componentId

    fun sendComponentEvent(id: String, event: ComponentEvent): Boolean =
        sendComponentEvent(id, event, activeInstancePath, document.id, documentIdentity)

    internal fun editMemoryWord(context: CircuitAccessibilityContext, componentId: String, address: Int, value: String): LocalizedText? {
        val notApplied = message(Messages.MemoryToolNotApplied)
        if (context != accessibilityContext() || !ensureDocumentWritable()) return notApplied
        val component = document.components.firstOrNull { it.id == componentId && isMemoryEditorComponent(it) }
            ?: return notApplied
        return try {
            val persistent = component.kind == ComponentKind.ROM
            val image = if (persistent) memoryEditorImage(component, simulation, componentRegistry) else {
                // Validate a single RAM word without copying the live image. The event applies to the current state.
                val config = MemoryComponents.configuration(component)
                MemoryImage(config.addressBits, config.dataBits)
            }
            when (val result = MemoryEditorModel(image).editCell(address.toString(16), value)) {
                is MemoryEditResult.Failure -> result.notice
                is MemoryEditResult.Success -> {
                    if (persistent) {
                        if (result.model.image != image) {
                            val updated = component.copy(attributes = component.attributes +
                                ("contents" to MemoryImageCodec.encodeCirc(result.model.image)))
                            applyDocument(document.copy(components = document.components.map {
                                if (it.id == componentId) updated else it
                            }))
                        }
                        null
                    } else {
                        val wordAddress = address.toLong()
                        val word = result.model.image[wordAddress]
                        if (sendComponentEvent(componentId, MemoryCellsChanged(mapOf(wordAddress to word)))) null
                        else notApplied
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            error.localizedDescription(message(Messages.MemoryToolInvalid))
        }
    }

    fun sendComponentEvent(id: String, event: ComponentEvent, path: List<String>, circuitId: String, identity: Long): Boolean {
        if (documentIdentity != identity) return false
        val circuit = if (path.isEmpty()) project.mainCircuit else instanceCircuit(path) ?: return false
        if (circuit.id != circuitId) return false
        return injectEvents(mapOf((path + id).joinToString("/") to listOf(event)))
    }

    fun injectEvents(events: Map<String, List<ComponentEvent>>): Boolean {
        if (!documentWritable) return false
        if (events.isEmpty()) return true
        for (key in events.keys) {
            val parts = key.split('/')
            val circuit = if (parts.size == 1) project.mainCircuit else instanceCircuit(parts.dropLast(1)) ?: return false
            val component = circuit.components.firstOrNull { it.id == parts.last() && componentRegistry.definition(it.kind) != null } ?: return false
            if (!interactionsEnabled && events.getValue(key).any { !it.releasesInput(component.kind) }) return false
        }
        val prefix = debugCopy?.let { it.path + "/" }
        if (prefix != null && events.keys.any { !it.startsWith(prefix) }) return false
        dismissResetUndo()
        val scopedEvents = if (prefix == null) events else events.mapKeys { (key, _) -> key.removePrefix(prefix) }
        val requested = captureRunSnapshot().copy(events = scopedEvents.mapValues { it.value.toList() })
        onSimulationRequested?.let { return it(requested, SimulationStep.SETTLE) == SimulationRequestResult.ACCEPTED }
        val result = advanceSimulation(requested, SimulationStep.SETTLE, componentRegistry,
            autoPropagating = SimulationProjectOptions.read(project).autoPropagating)
        publishRun(requested, result.state)
        return true
    }

    fun tick(fullCycle: Boolean = false) {
        if (!ensureDocumentWritable()) return
        dismissResetUndo()
        val step = if (fullCycle) SimulationStep.TICK_CYCLE else SimulationStep.TICK
        if (isDebuggingInstance) {
            advanceDebugCopy(step)
            return
        }
        val snapshot = captureRunSnapshot()
        onSimulationRequested?.let { it(snapshot, step); return }
        publishRun(snapshot, advanceSimulation(snapshot, step, componentRegistry).state)
        statusText = message(Messages.StatusClockStep, "count" to projectSimulation.ticks)
    }

    fun resetSimulation() = resetSimulation(SimulationRequestPurpose.COMMAND)

    private fun resetSimulation(purpose: SimulationRequestPurpose) {
        if (jarController != null) { stopJarSimulation(); return }
        if (!ensureDocumentWritable()) return
        // Reset clears poked inputs and session-only memory, which no file holds, so the command can be undone (C69).
        resetUndo = if (purpose == SimulationRequestPurpose.COMMAND) ResetUndo(documentIdentity, revision, ResetUndoSaved(project,
            debugCopy, projectSimulation, drives, vectorDrives, debugCopy?.drives, debugCopy?.state)) else null
        onSimulationInvalidated()
        runClock = null
        debugCopy?.let { copy ->
            copy.drives = copy.initialDrives
            advanceDebugCopy(SimulationStep.RESET)
            statusText = message(Messages.StatusSimulationReset)
            return
        }
        drives = startupResetIds.associateWith { LogicValue.LOW }
        vectorDrives = startupVectorDrives
        onSimulationRequested?.let {
            if (purpose == SimulationRequestPurpose.COMMAND) statusText = message(Messages.StatusSimulationReset)
            it(captureRunSnapshot().copy(purpose = purpose), SimulationStep.RESET)
            return
        }
        projectSimulation = simulator.settle(project, projectSimulation, inputs = drives, vectorInputs = vectorDrives, reset = true, options = SimulationProjectOptions.read(project).asMap())
        if (startupResetIds.isNotEmpty()) {
            val resetHigh = drives + startupResetIds.associateWith { LogicValue.HIGH }
            projectSimulation = simulator.settle(project, projectSimulation, inputs = resetHigh, vectorInputs = vectorDrives.filterKeys { it !in startupResetIds })
            projectSimulation = simulator.settle(project, projectSimulation, inputs = drives, vectorInputs = vectorDrives)
        }
        showActiveSimulation()
        statusText = message(Messages.StatusSimulationReset)
    }

    /** The simulation a Reset replaced: the live state, the poked inputs and a subcircuit debug copy's own. */
    private class ResetUndoSaved(
        val project: CircuitProject, val debugCopy: DebugInstanceSimulation?,
        val simulation: SimulationState, val drives: Map<String, LogicValue>, val vectorDrives: Map<String, LogicVector>,
        val debugDrives: Map<String, LogicVector>?, val debugState: SimulationState?,
    )
    /** A Reset that can be undone, with what it replaced until something overtakes it. */
    private class ResetUndo(val identity: Long, val revision: Int, var saved: ResetUndoSaved?)
    private var resetUndo by mutableStateOf<ResetUndo?>(null)

    /**
     * What the Reset replaced, while nothing has overtaken it. Once an edit, another document or a navigation has, it can
     * never be undone, so the replaced simulation and its memory contents are let go at once rather than kept until
     * the next tick, input or reset.
     */
    private fun ResetUndo.current(): ResetUndoSaved? {
        val kept = saved ?: return null
        if (identity == documentIdentity && revision == this@EditorState.revision && kept.project === project &&
            kept.debugCopy === debugCopy) return kept
        saved = null
        return null
    }

    /** Whether a Reset still holds the simulation it replaced. */
    internal val holdsResetUndo: Boolean get() = resetUndo?.saved != null

    /**
     * The last Reset while it can still be undone: until the next run, tick, input change, edit, navigation or
     * another document. The UI keys its Undo action on it.
     */
    val undoableReset: Any? get() = resetUndo?.takeIf { it.current() != null && documentWritable && interactionsEnabled }

    fun dismissResetUndo() { resetUndo = null }

    /**
     * Restores the simulation a Reset replaced (C69): the state with its memory contents, the poked inputs and, in a
     * subcircuit debug copy, the copy's state and inputs. A reset still computing is cancelled first. Only the session
     * changes; the document does not.
     */
    fun undoReset(): Boolean {
        val pending = resetUndo ?: return false
        resetUndo = null
        val undo = pending.current() ?: return false
        if (!documentWritable || !interactionsEnabled) return false
        onSimulationInvalidated()
        runClock = null
        val copy = debugCopy
        if (copy != null) {
            copy.drives = undo.debugDrives ?: copy.drives
            copy.state = undo.debugState ?: copy.state
        } else {
            projectSimulation = undo.simulation
            drives = undo.drives
            vectorDrives = undo.vectorDrives
        }
        showActiveSimulation()
        statusText = message(Messages.SimulationResetUndone)
        return true
    }

    fun selectCircuit(name: String) {
        val circuit = project.circuit(name) ?: return
        navigate(CircuitNavigationTarget(circuit.id, circuit.name))
    }

    fun viewSubcircuitInstance(componentId: String) {
        val component = document.components.firstOrNull { it.id == componentId && it.kind == ComponentKind.SUBCIRCUIT } ?: return
        val child = project.circuit(component.circuitName ?: return) ?: return
        val path = if (activeInstancePath.isNotEmpty() || document.id == project.mainCircuit.id) {
            activeInstancePath + component.id
        } else emptyList()
        navigate(CircuitNavigationTarget(child.id, child.name, path))
    }

    fun navigate(target: CircuitNavigationTarget) {
        // A label typed in the inspector reaches its components before the editor leaves their circuit (C9); the
        // target is looked up after it, so it holds that edit when it is the same circuit.
        commitPendingEdit?.invoke()
        val circuit = project.circuits.firstOrNull { it.id == target.circuitId } ?: return
        if (target.instancePath.isNotEmpty() && instanceCircuit(target.instancePath)?.id != circuit.id) {
            statusText = message(Messages.ErrorCircuitPath)
            return
        }
        dismissWirePoke()
        dismissKeyboardPoke()
        pendingPaste = null
        dismissResetUndo()
        clearDebugCopy()
        additiveSelection = false
        selections[navigationKey] = selectedIds to selectedWires
        activeCircuitName = circuit.name
        activeInstancePath = target.instancePath
        document = circuit
        editor = CircuitEditor(circuit, componentRegistry)
        val previous = selections[navigationKey]
        selectedIds = previous?.first.orEmpty().filter { id -> circuit.components.any { it.id == id } }.toSet()
        selectedWires = previous?.second.orEmpty().filter { it in circuit.wires }.toSet()
        selectedId = selectedIds.lastOrNull()
        pendingWire = null
        showActiveSimulation()
        if (target.componentId != null) {
            selectComponent(target.componentId)
            fitSelected()
        }
        reportSelectedCircuit()
    }

    private fun reportSelectedCircuit() {
        val breadcrumb = activeInstancePath.takeIf { it.isNotEmpty() }?.let { ProjectNavigation.instanceBreadcrumb(project, it) }
        statusText = if (breadcrumb == null) message(Messages.StatusCircuitSelected, "name" to document.name)
        else message(Messages.StatusInstanceSelected, "path" to breadcrumb.joinToString(" / "), "name" to document.name)
    }

    private fun instanceCircuit(path: List<String>): CircuitDocument? {
        var current = project.mainCircuit
        val visited = mutableSetOf(current.id)
        for (id in path) {
            val component = current.components.firstOrNull { it.id == id && it.kind == ComponentKind.SUBCIRCUIT } ?: return null
            current = project.circuit(component.circuitName ?: return null) ?: return null
            if (!visited.add(current.id)) return null
        }
        return current
    }

    fun createCircuit(name: String): Boolean = manageProject {
        val normalized = name.trim()
        requireLocalized(normalized.isNotEmpty()) { message(Messages.ErrorCircuitNameRequired) }
        requireLocalized(project.circuits.none { it.name == normalized }) { message(Messages.ErrorCircuitNameExists) }
        circuitNameProblem(normalized, null)?.let { throw LocalizedArgumentException(it) }
        var number = 1
        while (project.circuits.any { it.id == "circuit-$number" }) number++
        val created = CircuitDocument(name = normalized, id = "circuit-$number", attributes = creationDefaults.circuitAttributes())
        applyProject(project.addCircuit(created), message(Messages.StatusCircuitCreated, "name" to normalized))
        selectCircuit(created.name)
        statusText = message(Messages.StatusCircuitCreated, "name" to normalized)
    }

    fun renameCircuit(id: String, name: String): Boolean = manageProject {
        val circuit = project.circuits.firstOrNull { it.id == id } ?: throw LocalizedArgumentException(message(Messages.ErrorCircuitMissing))
        val normalized = name.trim()
        if (normalized != circuit.name) circuitNameProblem(normalized, circuit)?.let { throw LocalizedArgumentException(it) }
        applyProject(project.renameCircuit(circuit.name, normalized), message(Messages.StatusCircuitRenamed, "name" to normalized))
        statusText = message(Messages.StatusCircuitRenamed, "name" to normalized)
    }

    fun moveCircuit(id: String, direction: Int): Boolean = manageProject {
        val from = project.circuits.indexOfFirst { it.id == id }
        val to = from + direction
        requireLocalized(from >= 0 && to in project.circuits.indices) { message(Messages.ErrorCircuitOrderLimit) }
        val names = project.circuits.map { it.name }.toMutableList()
        val moved = names.removeAt(from)
        names.add(to, moved)
        applyProject(project.reorderCircuits(names), message(Messages.StatusCircuitOrder))
        statusText = message(Messages.StatusCircuitOrder)
    }

    fun removeCircuit(id: String): Boolean = manageProject {
        val circuit = project.circuits.firstOrNull { it.id == id } ?: throw LocalizedArgumentException(message(Messages.ErrorCircuitMissing))
        requireLocalized(ProjectCircuitLibraries.list(project).none { library -> library.circuits.any { it.id == id } }) {
            message("active_library_use_manager")
        }
        applyProject(project.removeCircuit(circuit.name), message(Messages.StatusCircuitDeleted, "name" to circuit.name))
        statusText = message(Messages.StatusCircuitDeleted, "name" to circuit.name)
    }

    fun setMainCircuit(id: String): Boolean = manageProject {
        val circuit = project.circuits.firstOrNull { it.id == id } ?: throw LocalizedArgumentException(message(Messages.ErrorCircuitMissing))
        applyProject(project.copy(mainCircuitName = circuit.name), message(Messages.StatusMainCircuit, "name" to circuit.name))
        statusText = message(Messages.StatusMainCircuit, "name" to circuit.name)
    }

    fun setBuiltinLibrary(library: String, loaded: Boolean, name: LocalizedText): Boolean = manageProject {
        val next = if (loaded) ProjectBuiltinLibraries.add(project, library)
        else ProjectBuiltinLibraries.remove(project, library, componentRegistry)
        applyProject(next)
        statusText = message(if (loaded) "active_library_loaded" else "active_library_unloaded", "name" to name)
    }

    fun attachLibrary(source: CircuitProject, name: String): Boolean = manageProject {
        applyProject(ProjectCircuitLibraries.attach(project, source, name), message("active_library_loaded", "name" to name))
        statusText = message("active_library_loaded", "name" to name)
    }

    fun reloadLibrary(id: String, source: CircuitProject): Boolean = manageProject {
        val library = ProjectCircuitLibraries.list(project).firstOrNull { it.id == id } ?: return@manageProject
        applyProject(ProjectCircuitLibraries.reload(project, id, source))
        statusText = message("active_library_reloaded", "name" to library.name)
    }

    fun detachLibrary(id: String): Boolean = manageProject {
        val library = ProjectCircuitLibraries.list(project).firstOrNull { it.id == id } ?: return@manageProject
        applyProject(ProjectCircuitLibraries.detach(project, id), message("active_library_unloaded", "name" to library.name))
        statusText = message("active_library_unloaded", "name" to library.name)
    }

    fun placeLibraryCircuit(circuit: CircuitDocument): Boolean = manageProject {
        requireLocalized(ProjectCircuitLibraries.canPlace(project, circuit, document)) {
            message("active_library_recursive_placement")
        }
        place(ProjectCircuitLibraries.placement(circuit))
    }

    private inline fun manageProject(action: () -> Unit): Boolean = if (!ensureDocumentWritable()) false else try {
        action()
        true
    } catch (error: IllegalArgumentException) {
        statusText = error.localizedDescription(message(Messages.ErrorProjectEdit))
        false
    }

    /**
     * Selects the components [ids] of the shown circuit and frames them: a simulation warning's Show (C67). Returns
     * false, saying so in the status line, when none of them is in the circuit any more.
     */
    fun showComponents(ids: Collection<String>): Boolean {
        val present = document.components.map { it.id }.filter { it in ids }
        if (present.isEmpty()) {
            statusText = message("simulation_warning_source_gone")
            return false
        }
        dismissKeyboardPoke()
        dismissWirePoke()
        selectedIds = present.toSet()
        selectedId = present.last()
        selectedWires = emptySet()
        statusText = LocalizedText(Messages.StatusComponentsSelected, quantity = present.size.toLong())
        fitSelected()
        return true
    }

    fun fitViewport() { fitSelection = false; viewportReset++ }
    fun fitSelected() {
        if (selectedIds.isEmpty() && selectedWires.isEmpty()) return
        fitSelection = true
        viewportReset++
    }
    fun exportXml(): String = rejectedDocumentXml ?: CircCodec.writeProject(project, componentRegistry)

    /** The file contents written by Save, Save As and Share, honoring "Remove unused libraries on save". */
    fun saveXml(): String = rejectedDocumentXml ?: CircCodec.writeProject(project, componentRegistry, creationDefaults.removeUnusedLibraries)

    fun installExample(session: ExampleSession) {
        if (jarController != null) { stopJarSimulation(); return }
        if (!interactionsEnabled) return
        val next = session.project.copy(metadata = session.project.metadata + (ExamplePresetKey to session.example.id))
        openWorkspaceSession?.let { open ->
            open(exampleProjectName(session.example.id), CircCodec.writeProject(next, componentRegistry), null)
            return
        }
        replace(next, historyLabel = message("status_example_loaded"))
        onSimulationInvalidated()
        startupResetIds = examplePulseIds(session.example, session.inputs)
        startupVectorDrives = session.inputs.toMap()
        drives = emptyMap()
        vectorDrives = startupVectorDrives
        projectSimulation = session.state
        showActiveSimulation()
        statusText = message("status_example_loaded")
    }

    private fun exampleProjectName(id: String): LocalizedText =
        HelpContent.examples.firstOrNull { it.id == id }?.title ?: message("workspace_untitled_project")

    private fun examplePulseIds(example: CircuitExample, inputs: Map<String, LogicVector>): Set<String> {
        val pulsedLabels = example.startup.flatMap { action -> action.inputs.filterValues { it == "1" }.keys }.toSet()
        return project.mainCircuit.components.filter { component ->
            component.kind == ComponentKind.INPUT && component.width == 1 && component.label in pulsedLabels &&
                inputs[component.id]?.toULongOrNull() == 0uL
        }.mapTo(mutableSetOf()) { it.id }
    }

    private fun restoreExampleStartup() {
        val example = project.metadata[ExamplePresetKey]?.let(CircuitExamples::find) ?: return
        val restored = runCatching {
            val runner = ExampleRunner(simulator, componentRegistry)
            example.startup.fold(ExampleSession(example, project, projectSimulation)) { session, action -> runner.advance(session, action) }
        }.getOrElse {
            statusText = message("error_example_startup")
            return
        }
        startupResetIds = examplePulseIds(example, restored.inputs)
        startupVectorDrives = restored.inputs
        drives = emptyMap()
        vectorDrives = startupVectorDrives
        projectSimulation = restored.state
        showActiveSimulation()
    }

    fun loadDemo() {
        if (jarController != null) { stopJarSimulation(); return }
        if (!interactionsEnabled) return
        val next = defaultProject(DemoCircuits.andGate())
        openWorkspaceSession?.let { open ->
            open(exampleProjectName("and"), CircCodec.writeProject(next, componentRegistry), null)
            return
        }
        replace(next, historyLabel = message(Messages.StatusAndLoaded))
        statusText = message(Messages.StatusAndLoaded)
    }

    fun loadSap1() {
        if (!interactionsEnabled) return
        val open = openWorkspaceSession
        if (open != null && jarController == null) {
            (CircCodec.readProject(Sap1Example.xml, componentRegistry) as? CircProjectImportResult.Success)?.let { result ->
                open(exampleProjectName("sap1"), CircCodec.writeProject(result.project, componentRegistry), EditorImportReport.from(result))
                return
            }
        }
        if (importXml(Sap1Example.xml) && openWorkspaceSession == null) {
            startupResetIds = project.mainCircuit.components.filter {
                it.kind == ComponentKind.INPUT && it.label == "Reset"
            }.map { it.id }.toSet()
            resetSimulation(SimulationRequestPurpose.DOCUMENT_INITIALIZATION)
            statusText = message(Messages.StatusSap1Loaded)
        }
    }

    /** [circuitName] follows Evolution's localized `newCircuitName` ("main", "principal", ...). */
    fun newDocument(template: CircuitProject? = null, circuitName: String = DEFAULT_NEW_CIRCUIT_NAME) {
        if (jarController != null) { stopJarSimulation(); return }
        if (!interactionsEnabled) return
        val next = template ?: defaultProject(CircuitDocument(name = circuitName.trim().ifEmpty { DEFAULT_NEW_CIRCUIT_NAME },
            attributes = creationDefaults.circuitAttributes()))
        openWorkspaceSession?.let { open ->
            // Evolution names a new project "Untitled"; its main circuit keeps the localized circuit name.
            open(message("workspace_untitled_project"), CircCodec.writeProject(next, componentRegistry), null)
            return
        }
        replace(next)
        statusText = message(if (template == null) Messages.StatusEmptyCircuit else Messages.ProjectTemplateStarted)
    }

    /** A new project from Evolution's empty template: one circuit with the creation defaults and no libraries or toolbar. */
    fun newEmptyDocument(circuitName: String = DEFAULT_NEW_CIRCUIT_NAME) {
        if (jarController != null) { stopJarSimulation(); return }
        if (!interactionsEnabled) return
        newDocument(SimulationProjectOptions(tickFrequencyHz = defaultTickFrequencyHz).applyTo(
            emptyTemplateProject(circuitName, creationDefaults.circuitAttributes())), circuitName)
    }

    private fun defaultProject(circuit: CircuitDocument): CircuitProject =
        SimulationProjectOptions(tickFrequencyHz = defaultTickFrequencyHz).applyTo(
            EvolutionDefaultTemplate.apply(CircuitProject(listOf(circuit)), componentRegistry),
        )

    fun importXml(xml: String, origin: LocalizedText? = null): Boolean {
        if (jarController != null) { stopJarSimulation(); return false }
        if (!interactionsEnabled) return false
        return acceptImport(CircCodec.readProject(xml, componentRegistry), origin, xml)
    }

    fun exportExtensionSourceXml(): String = pendingExtensionImportXml ?: exportXml()

    fun updateExtensionRegistry(registry: ComponentRegistry) {
        if (jarController != null) { stopJarSimulation(); return }
        require(componentRegistry.definitions.all { registry.definition(it.kind) == it }) {
            "Extension registration must preserve the current component definitions."
        }
        componentRegistry = registry
        editor = CircuitEditor(document, registry)
        val retryXml = pendingExtensionImportXml ?: rejectedDocumentXml?.takeIf { !graphAvailable }
        if (retryXml != null) {
            val result = CircCodec.readProject(retryXml, registry)
            if (result is CircProjectImportResult.Success) acceptImport(result)
        }
    }

    fun acceptImport(result: CircProjectImportResult, origin: LocalizedText? = null, sourceXml: String? = null): Boolean {
        if (jarController != null) { stopJarSimulation(); return false }
        if (!interactionsEnabled) return false
        return when (result) {
            is CircProjectImportResult.Success -> {
                val report = EditorImportReport.from(result, origin)
                openWorkspaceSession?.let { open ->
                    // A file keeps its name; text pasted in the project XML dialog is an imported project.
                    val name = origin?.takeIf { it.namesAFile } ?: message("workspace_imported_project")
                    return open(name, CircCodec.writeProject(result.project, componentRegistry), report)
                }
                replace(result.project, report, message("history_import_project"))
                // Text pasted in the Project XML dialog is an imported project, not a file that was opened (C145).
                val pasted = origin != null && !origin.namesAFile
                statusText = importAdjustmentsStatus(origin, imported = pasted)
                    ?: LocalizedText(Messages.StatusProjectImported, quantity = project.circuits.size.toLong())
                importErrorText = null
                true
            }
            is CircProjectImportResult.Failure -> {
                val jarLibraries = sourceXml?.let {
                    runCatching { ExtensionReferenceScanner.inspect(it).jarLibraries.distinctBy { ref -> ref.descriptor } }
                        .getOrNull()
                }.orEmpty()
                pendingExtensionImportXml = sourceXml?.takeIf { jarLibraries.isNotEmpty() }
                jarImportRecoveryAvailable = jarLibraries.size == 1
                importErrorText = LocalizedText.join(result.notices)
                false
            }
        }
    }

    fun dismissImportError() {
        importErrorText = null
        jarImportRecoveryAvailable = false
    }
    fun reportError(value: String) = reportError(LocalizedText.literal(value))
    fun reportError(value: LocalizedText) {
        importErrorText = value
        jarImportRecoveryAvailable = false
    }
    fun reportStatus(value: String) { statusText = LocalizedText.literal(value) }
    fun reportStatus(value: LocalizedText) { statusText = value }

    fun applyDocument(next: CircuitDocument) {
        val previous = project.circuits.firstOrNull { it.id == next.id }
            ?: throw LocalizedArgumentException(message(Messages.ErrorCircuitMissing, "name" to next.id))
        applyProject(AppearanceEdits.reconcileCircuit(project, previous, next), message("history_edit_circuit"))
    }

    fun applyProject(next: CircuitProject) = applyProject(next, message("history_edit_project"))

    private fun applyProject(next: CircuitProject, historyLabel: LocalizedText) {
        if (ensureDocumentWritable()) applyProjectEdit(next, SimulationRequestPurpose.COMMAND, historyLabel)
    }

    internal fun applyPersistedProject(next: CircuitProject) {
        if (!documentWritable) return
        // Storage can finish after the app goes to the background. Settle on resume.
        val purpose = if (interactionsEnabled) SimulationRequestPurpose.COMMAND else SimulationRequestPurpose.DOCUMENT_INITIALIZATION
        applyProjectEdit(next, purpose, message("history_edit_project"))
    }

    private fun applyProjectEdit(next: CircuitProject, purpose: SimulationRequestPurpose, historyLabel: LocalizedText) {
        if (next == project) return
        clearDebugCopy()
        onSimulationInvalidated()
        pendingPaste = null
        val activeId = document.id
        val oldMain = project.mainCircuitName
        recordHistory(snapshot(), historyLabel)
        project = next
        clearMissingPlacement()
        if (oldMain != next.mainCircuitName) {
            drives = emptyMap()
            vectorDrives = emptyMap()
        }
        if (activeInstancePath.isNotEmpty() && instanceCircuit(activeInstancePath)?.id != activeId) activeInstancePath = emptyList()
        document = next.circuits.firstOrNull { it.id == activeId } ?: next.mainCircuit
        activeCircuitName = document.name
        editor = CircuitEditor(document, componentRegistry)
        settleProject(purpose)
        clearSelection()
        pendingWire = null
        revision++
        updateHistoryAvailability()
    }

    private fun selectedComponents(): List<CircuitComponent> =
        document.components.filter { it.id in selectedIds }

    private fun replace(
        next: CircuitProject,
        report: EditorImportReport? = null,
        historyLabel: LocalizedText = message(Messages.ActionNew),
    ) {
        pendingPaste = null
        if (placingTemplate != null) changeMode(EditorMode.INTERACT)
        clearDebugCopy()
        onSimulationInvalidated()
        val before = snapshot()
        val previousXml = exportXml()
        if (project != next || rejectedDocumentXml != null) {
            recordHistory(before, historyLabel)
            revision++
        }
        rejectedDocumentXml = null
        pendingExtensionImportXml = null
        jarImportRecoveryAvailable = false
        graphAvailable = true
        importErrorText = null
        importReport = report
        importReportVisible = report?.showOnOpen == true
        project = next
        savedIoInteractions = emptyMap()
        documentSessionId = nextDocumentSessionId++
        onProjectReplaced()
        viewports = emptyMap()
        fitSelection = false
        startupResetIds = emptySet()
        startupVectorDrives = emptyMap()
        activeCircuitName = next.mainCircuitName
        activeInstancePath = emptyList()
        selections.clear()
        editor = CircuitEditor(next.mainCircuit, componentRegistry)
        document = editor.document
        projectSimulation = SimulationState()
        clearSelection()
        additiveSelection = false
        pendingWire = null
        updateHistoryAvailability()
        viewportReset++
        onWorkspaceProjectReplaced(EditorProjectReplacement(previousXml, exportXml(), before.documentSessionId, documentIdentity, importReport))
        resetSimulation(SimulationRequestPurpose.DOCUMENT_INITIALIZATION)
    }

    private fun change(historyLabel: LocalizedText, action: () -> Unit): Boolean {
        val before = snapshot()
        val previous = document
        val next = try {
            action()
            AppearanceEdits.reconcileCircuit(before.project, previous, editor.document)
        } catch (failure: Throwable) {
            editor = CircuitEditor(previous, componentRegistry)
            if (failure !is IllegalArgumentException) throw failure
            statusText = failure.localizedDescription(message(Messages.ErrorProjectEdit))
            return false
        }
        if (next != before.project) {
            pendingPaste = null
            clearDebugCopy()
            onSimulationInvalidated()
            recordHistory(before, historyLabel)
            project = next
            document = next.circuits.first { it.id == previous.id }
            dismissWirePoke()
            editor = CircuitEditor(document, componentRegistry)
            val inputIds = inputIds(project.mainCircuit)
            drives = drives.filterKeys { it in inputIds }
            vectorDrives = vectorDrives.filterKeys { it in inputIds }
            settleProject()
            pendingWire = null
            revision++
        }
        updateHistoryAvailability()
        return true
    }

    private fun settleProject(purpose: SimulationRequestPurpose = SimulationRequestPurpose.COMMAND) {
        if (!documentWritable) { projectSimulation = SimulationState(); showActiveSimulation(); return }
        onSimulationRequested?.let { it(captureRunSnapshot().copy(purpose = purpose), SimulationStep.SETTLE); return }
        projectSimulation = simulator.settle(project, projectSimulation, inputs = drives, vectorInputs = vectorDrives, options = SimulationProjectOptions.read(project).asMap())
        showActiveSimulation()
    }

    private fun sendJarInput(id: String, value: ULong? = null): Boolean =
        interactionsEnabled && activeCircuitName == project.mainCircuitName && activeInstancePath.isEmpty() &&
            jarController?.input(id, value) == true

    internal suspend fun observeJarSimulation() {
        jarController?.state?.collect { next ->
            projectSimulation = next
            showActiveSimulation()
        }
    }

    internal fun stopJarSimulation() {
        jarController?.stop()
    }

    private fun showActiveSimulation() {
        val copy = debugCopy
        simulation = when {
            copy != null -> copy.state.copy(instancePath = copy.path)
            activeInstancePath.isNotEmpty() -> projectSimulation.circuitStates[activeInstancePath.joinToString("/")]
                ?: simulator.settle(project.copy(mainCircuitName = activeCircuitName), options = SimulationProjectOptions.read(project).asMap())
            activeCircuitName == project.mainCircuitName -> projectSimulation
            else -> simulator.settle(project.copy(mainCircuitName = activeCircuitName), options = SimulationProjectOptions.read(project).asMap())
        }
    }

    private fun clearSelection() {
        dismissWirePoke()
        selectedId = null
        selectedIds = emptySet()
        selectedWires = emptySet()
    }

    private fun snapshot() = Snapshot(project, activeCircuitName, startupResetIds, documentSessionId, activeInstancePath,
        startupVectorDrives, rejectedDocumentXml, importReport, graphAvailable, savedIoInteractions, tickFrequencyEpoch)

    /**
     * Evolution does not undo a tick frequency change, so restoring a snapshot taken before the latest
     * [setTickFrequency] keeps the current rate instead of silently reverting it. A rate set through Simulation
     * options after that change is part of the snapshot and stays undoable.
     */
    private fun preserveTickFrequency(target: Snapshot): Snapshot {
        if (target.tickFrequencyEpoch >= tickFrequencyEpoch) return target
        val current = runCatching { SimulationProjectOptions.read(project).tickFrequencyHz }.getOrNull() ?: return target
        val options = runCatching { SimulationProjectOptions.read(target.project) }.getOrNull() ?: return target
        if (options.tickFrequencyHz == current) return target
        return target.copy(project = options.copy(tickFrequencyHz = current).applyTo(target.project))
    }

    private fun restore(snapshot: Snapshot) {
        pendingPaste = null
        clearDebugCopy()
        onSimulationInvalidated()
        val previousXml = exportXml()
        val previousIdentity = documentIdentity
        val replaced = documentSessionId != snapshot.documentSessionId
        val restored = if (!replaced && documentWritable && snapshot.rejectedDocumentXml == null)
            preserveTickFrequency(preserveNewerSavedIoInteractions(snapshot)) else snapshot
        if (replaced) onProjectReplaced()
        documentSessionId = snapshot.documentSessionId
        rejectedDocumentXml = snapshot.rejectedDocumentXml
        graphAvailable = snapshot.graphAvailable
        importReport = snapshot.importReport
        importReportVisible = false
        project = restored.project
        clearMissingPlacement()
        savedIoInteractions = restored.savedIoInteractions
        startupResetIds = snapshot.startupResetIds
        startupVectorDrives = snapshot.startupVectorDrives
        activeCircuitName = snapshot.activeCircuitName
        activeInstancePath = snapshot.activeInstancePath
        document = project.circuit(activeCircuitName) ?: project.mainCircuit
        editor = CircuitEditor(document, componentRegistry)
        val inputIds = inputIds(project.mainCircuit)
        drives = drives.filterKeys { it in inputIds }
        vectorDrives = vectorDrives.filterKeys { it in inputIds }
        if (replaced) { projectSimulation = SimulationState(); additiveSelection = false }
        clearSelection()
        pendingWire = null
        revision++
        updateHistoryAvailability()
        if (replaced) onWorkspaceProjectReplaced(EditorProjectReplacement(previousXml, exportXml(), previousIdentity, documentIdentity, importReport))
        val purpose = if (replaced) SimulationRequestPurpose.DOCUMENT_INITIALIZATION else SimulationRequestPurpose.COMMAND
        if (replaced && project.metadata[ExamplePresetKey] != null) resetSimulation(purpose) else settleProject(purpose)
    }

    private fun componentPlacementLabel(kind: ComponentKind) =
        message("history_add_component", "name" to kind.localizedLabel())

    private fun recordHistory(before: Snapshot, label: LocalizedText) {
        past.addLast(HistoryEntry(EditorHistoryAction(nextHistoryId++, label), before))
        if (past.size > 100) past.removeFirst()
        future.clear()
    }
    private fun clearMissingPlacement() {
        val missingCircuit = placingTemplate?.circuitName?.let { project.circuit(it) == null } == true
        val missingLibrary = (mode == EditorMode.PLACE || mode == EditorMode.TEXT) &&
            componentRegistry.definition(placingTemplate?.kind ?: placingKind)?.library?.let {
                !ProjectBuiltinLibraries.includes(project, it)
            } == true
        if (missingCircuit || missingLibrary) changeMode(EditorMode.INTERACT)
    }
    private fun updateHistoryAvailability() {
        undoHistory = past.reversed().map { it.action }
        redoHistory = future.reversed().map { it.action }
    }

    private data class SavedIoTarget(val circuitId: String, val componentId: String)
    private data class SavedIoInteraction(val sequence: Long, val attributes: Map<String, String>)

    private data class HistoryEntry(val action: EditorHistoryAction, val snapshot: Snapshot)

    private data class Snapshot(
        val project: CircuitProject,
        val activeCircuitName: String,
        val startupResetIds: Set<String>,
        val documentSessionId: Long,
        val activeInstancePath: List<String>,
        val startupVectorDrives: Map<String, LogicVector>,
        val rejectedDocumentXml: String?,
        val importReport: EditorImportReport?,
        val graphAvailable: Boolean,
        val savedIoInteractions: Map<SavedIoTarget, SavedIoInteraction>,
        val tickFrequencyEpoch: Long,
    )
}

internal fun CircuitComponent.contains(point: GridPoint, registry: ComponentRegistry = ComponentCatalog.registry): Boolean {
    val bounds = bounds(registry)
    return point.x in bounds.left - 5..bounds.right + 5 && point.y in bounds.top - 5..bounds.bottom + 5
}

private fun inputIds(document: CircuitDocument): Set<String> =
    document.components.filter { it.kind in setOf(ComponentKind.INPUT, ComponentKind.BUTTON) }
        .map { it.id }.toSet()

internal fun ComponentKind.displayName(language: AppLanguage = AppLanguage.PORTUGUESE): String =
    localizedLabel().resolve(language)

private fun CircuitComponent.displayText(): LocalizedText =
    if (label.isBlank()) kind.localizedLabel() else LocalizedText.literal(label)

internal fun LogicValue.symbol(): String = when (this) {
    LogicValue.LOW -> "0"
    LogicValue.HIGH -> "1"
    LogicValue.UNKNOWN -> "?"
    LogicValue.ERROR -> "E"
}

internal const val DEFAULT_NEW_CIRCUIT_NAME = "main"
