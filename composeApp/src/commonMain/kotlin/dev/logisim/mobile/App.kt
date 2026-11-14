package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.SocDriverExport
import dev.logisim.mobile.core.SocElfLoader
import dev.logisim.mobile.core.SocLoadExecutableEvent
import dev.logisim.mobile.core.SocNios2ComponentState
import dev.logisim.mobile.core.SocRv32ComponentState
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.MobileExtensionCatalog
import dev.logisim.mobile.core.LogicValue
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.localization.AppLocaleProvider

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.InputValueCodec
import dev.logisim.mobile.core.InputValueKeyboard
import dev.logisim.mobile.core.InputValueParse
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PinPoke
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.formatTickFrequency
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

private val appCircuitClipboard = CircuitClipboard()

/** What a hosting workspace shows in the editor's title area: the stored project and its save state. */
class EditorWorkspaceHeader(
    val projectName: String,
    val status: String,
    val busy: Boolean = false,
    val onOpenProjects: (() -> Unit)? = null,
    val onOpenRecent: (() -> Unit)? = null,
    /** The stored project's own name for Save As and Share, or null for a draft that has none yet. */
    val fileName: String? = null,
    /** [status] shortened for a narrow header ("Not saved to file" for "Not saved to Adder.circ"); read in full. */
    val compactStatus: String? = null,
) {
    internal var session: WorkspaceEditorSessionBinding? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    initialXml: String? = null,
    onDocumentChanged: (String) -> Unit = {},
    documents: DocumentActions? = null,
    editorTools: List<EditorTool> = emptyList(),
    language: AppLanguage = systemAppLanguage(),
    registry: ComponentRegistry = ComponentCatalog.registry,
    sessionControl: EditorSessionControl? = null,
    onProjectReplaced: (EditorProjectReplacement) -> Unit = {},
    preferencesStorage: PreferencesStorage? = null,
    onLanguageChanged: (AppLanguage) -> Unit = {},
    onThemeModeChanged: (EditorThemeMode) -> Unit = {},
    memoryFiles: MemoryEditorFileActions? = null,
    assets: DocumentAssetActions? = null,
    initialImportReport: EditorImportReport? = null,
    initialReadOnly: Boolean = false,
    portableProjects: PortableProjectWorkspaceAccess? = null,
    directories: DirectoryAssetActions? = null,
    initialExtensionCatalog: MobileExtensionCatalog? = null,
    projectTemplates: ProjectTemplateStore? = null,
    onExtensionCatalogChanged: (MobileExtensionCatalog) -> Unit = {},
    onCommitProject: ((Long, String, (Boolean) -> Unit) -> Unit)? = null,
    workspaceHeader: EditorWorkspaceHeader? = null,
    boardCatalog: FpgaBoardCatalogStore? = null,
    importReportDismissals: ImportReportDismissals? = null,
    importReportKey: String? = null,
) {
    val preferenceRepository = remember(preferencesStorage) { preferencesStorage?.let(::EditorPreferencesRepository) }
    val loaded = remember(preferenceRepository) { preferenceRepository?.load() ?: EditorPreferencesLoad() }
    var preferences by remember(preferenceRepository) { mutableStateOf(loaded.preferences) }
    var preferenceNotice by remember(loaded) { mutableStateOf(loaded.notices.firstOrNull()) }
    val effectiveLanguage = preferences.locale.language ?: language
    SideEffect { onLanguageChanged(effectiveLanguage); onThemeModeChanged(preferences.themeMode) }
    AppLocaleProvider(effectiveLanguage) {
        EditorApp(initialXml, onDocumentChanged, documents, editorTools, registry, sessionControl, onProjectReplaced, memoryFiles, assets, initialImportReport, initialReadOnly, portableProjects, directories,
            initialExtensionCatalog, onExtensionCatalogChanged, onCommitProject, projectTemplates,
            workspaceHeader = workspaceHeader, boardCatalog = boardCatalog,
            importReportDismissals = importReportDismissals, importReportKey = importReportKey, preferences = preferences, onPreferencesChange = { next ->
                when (val result = preferenceRepository?.save(next)) {
                    is PreferencesSaveResult.Failure -> preferenceNotice = result.notice
                    else -> preferences = next
                }
            }, onRestorePreferences = {
                when (val result = preferenceRepository?.restoreDefaults()) {
                    is PreferencesSaveResult.Failure -> preferenceNotice = result.notice
                    else -> preferences = EditorPreferences()
                }
            }, preferenceNotice = preferenceNotice, onDismissPreferenceNotice = { preferenceNotice = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorApp(
    initialXml: String?,
    onDocumentChanged: (String) -> Unit,
    documents: DocumentActions?,
    extensions: List<EditorTool>,
    baseRegistry: ComponentRegistry,
    sessionControl: EditorSessionControl?,
    onProjectReplaced: (EditorProjectReplacement) -> Unit,
    memoryFiles: MemoryEditorFileActions?,
    assets: DocumentAssetActions?,
    initialImportReport: EditorImportReport?,
    initialReadOnly: Boolean,
    portableProjects: PortableProjectWorkspaceAccess?,
    directories: DirectoryAssetActions?,
    initialExtensionCatalog: MobileExtensionCatalog?,
    onExtensionCatalogChanged: (MobileExtensionCatalog) -> Unit,
    onCommitProject: ((Long, String, (Boolean) -> Unit) -> Unit)?,
    projectTemplates: ProjectTemplateStore?,
    workspaceHeader: EditorWorkspaceHeader?,
    boardCatalog: FpgaBoardCatalogStore?,
    preferences: EditorPreferences,
    onPreferencesChange: (EditorPreferences) -> Unit,
    onRestorePreferences: () -> Unit,
    preferenceNotice: LocalizedText?,
    onDismissPreferenceNotice: () -> Unit,
    jarController: JarEditorController? = null,
    importReportDismissals: ImportReportDismissals? = null,
    importReportKey: String? = null,
) {
    val strings = rememberLocalizer()
    val workspaceSession = workspaceHeader?.session
    var preparedState by remember(baseRegistry) { mutableStateOf<EditorState?>(null) }
    LaunchedEffect(baseRegistry) {
        if (jarController != null) return@LaunchedEffect
        val initialRegistry = initialExtensionCatalog?.registry ?: baseRegistry
        val editor = workspaceSession?.editor?.also {
            if (it.componentRegistry !== initialRegistry) it.updateExtensionRegistry(initialRegistry)
        } ?: prepareEditorState(initialXml, initialRegistry, preferences.defaultTickFrequencyHz, initialImportReport,
            initialReadOnly = initialReadOnly, clipboard = appCircuitClipboard).also { prepared ->
                workspaceSession?.let {
                    prepared.importViewState(it.view)
                    initialImportReport?.origin?.let(prepared::reportOpened)
                }
                if (importReportDismissals != null && importReportKey != null) importReportDismissals.restore(importReportKey, prepared)
            }
        workspaceSession?.retain?.invoke(editor)
        preparedState = editor
    }
    val state = if (jarController == null) preparedState else remember(jarController) { EditorState(null, jarController = jarController) }
    if (state == null) {
        LogisimTheme(preferences.themeMode.resolveDark()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text(strings(message("editor_loading_project")), Modifier.padding(top = 16.dp))
                }
            }
        }
        return
    }
    val registry = state.componentRegistry
    if (importReportDismissals != null && importReportKey != null) {
        // Re-recorded when a draft becomes a project or its XML changes, so the key matches the next mount.
        LaunchedEffect(state, importReportDismissals, importReportKey) {
            snapshotFlow { (state.importReport != null) to state.importReportVisible }.collect { (hasReport, visible) ->
                importReportDismissals.visibilityChanged(importReportKey, hasReport, visible)
            }
        }
        // Announced once per window: kept under the next key too, when a draft becomes a project (C145).
        val announced = remember(state) { mutableStateOf(false) }
        LaunchedEffect(state, importReportDismissals, importReportKey) {
            snapshotFlow { announced.value || state.importReportStatusShown }.collect { shown ->
                if (shown) { announced.value = true; importReportDismissals.announced(importReportKey) }
            }
        }
    }
    var extensionCatalog by remember(baseRegistry) { mutableStateOf(initialExtensionCatalog ?: MobileExtensionCatalog(baseRegistry)) }
    val catalogChanged by rememberUpdatedState(onExtensionCatalogChanged)
    val extensionTool = remember(extensionCatalog, state) {
        extensionsEditorTool(extensionCatalog, listOf(GrayCodeExtension.provider)) { catalog ->
            catalogChanged(catalog)
            state.updateExtensionRegistry(catalog.registry)
            extensionCatalog = catalog
        }
    }
    SideEffect {
        state.defaultTickFrequencyHz = preferences.defaultTickFrequencyHz
        state.afterPlacement = preferences.afterPlacement
    }
    val scope = rememberCoroutineScope()
    val currentLocalizer by rememberUpdatedState(strings)
    var activeTool by remember { mutableStateOf<String?>(null) }
    // C74: a result reported while a tool sheet is open is also shown in the sheet, which hides the status line.
    var toolSheetNotice by remember { mutableStateOf<ToolSheetNotice?>(null) }
    val reportToolNotice: (LocalizedText) -> Unit = remember(state) { var serial = 0L; { text ->
        state.reportStatus(text)
        activeTool?.let { toolSheetNotice = ToolSheetNotice(it, text, ++serial) }
    } }
    LaunchedEffect(activeTool) { if (toolSheetNotice?.toolId != activeTool) toolSheetNotice = null }
    val effectiveMemoryFiles = remember(memoryFiles, assets, scope) {
        memoryFiles ?: assets?.let { NativeMemoryImageActions(it, scope, onNotice = reportToolNotice,
            onFailure = state::reportError, resolveNotice = { notice -> currentLocalizer(notice) }) }
    }
    var xmlDialog by remember { mutableStateOf(false) }
    var xmlText by remember { mutableStateOf("") }
    var historyDirection by remember(state) { mutableStateOf<EditorHistoryDirection?>(null) }
    var librarySheet by remember { mutableStateOf(false) }
    // The explorer's tab, scroll position, groups and recent components outlive the sheet (C18).
    val libraryExplorer = rememberLibraryExplorerState()
    // The toolbar button that opened the current sheet. Closing the sheet returns TalkBack there (#322).
    var sheetOpenerTag by remember { mutableStateOf<String?>(null) }
    val accessibilityFocusReturn = rememberAccessibilityFocusReturn()
    val returnFocusToSheetOpener = {
        sheetOpenerTag?.let { tag -> scope.launch { accessibilityFocusReturn.moveTo(tag) } }
        sheetOpenerTag = null
    }
    var findActions by remember { mutableStateOf(false) }
    val openFindAction = { findActions = true; librarySheet = true; sheetOpenerTag = null }
    var activeLibraries by remember { mutableStateOf(false) }
    var mergeProject by remember { mutableStateOf(false) }
    var inspectorSheet by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf(false) }
    var legalDialog by remember { mutableStateOf(false) }
    // The circuit name and removal dialogs of the header picker and the Project menu ("" names a new circuit).
    var circuitNameDialog by remember(state.documentIdentity) { mutableStateOf<String?>(null) }
    var removeCircuitDialog by remember(state.documentIdentity) { mutableStateOf<String?>(null) }
    val menuExampleOpener = remember(state) { HelpExampleOpener() }
    var zoomDialog by remember(state, state.activeTarget) { mutableStateOf(false) }
    var zoomMenu by remember(state, state.activeTarget) { mutableStateOf(false) }
    val canvasDensity = LocalDensity.current.density
    SideEffect { state.canvasDensity = canvasDensity }
    var clearHistoryDialog by remember(state, state.documentIdentity) { mutableStateOf(false) }
    var explorerVisible by remember { mutableStateOf(true) }
    var signalsVisible by remember { mutableStateOf(false) }
    var signalSheet by remember { mutableStateOf(false) }
    val signalScroll = rememberScrollState()
    var signalInput by remember(state.documentIdentity, state.activeTarget) { mutableStateOf<String?>(null) }
    val editSignalInput: (String) -> Unit = { id -> signalInput = id }
    var textToolEdit by remember(state.documentIdentity, state.activeTarget) { mutableStateOf<TextToolEdit?>(null) }
    var placementAttributes by remember(state.documentIdentity) { mutableStateOf(false) }
    var memoryEdit by remember(state.documentIdentity, state.activeTarget, state.mode) { mutableStateOf<CanvasMemoryEdit?>(null) }
    val sequentialPokeEnabled = state.documentWritable && state.interactionsEnabled && !state.printerView
    var sequentialHexTarget by remember(state, state.documentIdentity, state.activeTarget, state.mode, sequentialPokeEnabled) {
        mutableStateOf<SequentialPokeTarget?>(null)
    }
    var memoryWindows by remember(state.documentIdentity, state.activeTarget) { mutableStateOf(emptyMap<String, MemoryCanvasWindow>()) }
    var autoLabelDialog by remember(state.documentIdentity, state.activeTarget) { mutableStateOf(false) }
    fun openPlacementAutoLabel(): Boolean {
        if (!state.canAutoLabelPlacement) return false
        autoLabelDialog = true
        return true
    }
    var diagnosticsDialog by remember { mutableStateOf(false) }
    var preferencesSheet by remember { mutableStateOf(false) }
    var simulationOptions by remember { mutableStateOf(false) }
    val latestPreferences by rememberUpdatedState(preferences)
    val latestPreferencesChange by rememberUpdatedState(onPreferencesChange)
    val latestPreferencesRestore by rememberUpdatedState(onRestorePreferences)
    val preferencesTool = remember(projectTemplates, assets) {
        EditorTool("preferences", "Preferências", titleText = message("preferences_title")) {
            EditorPreferencesPanel(latestPreferences, { latestPreferencesChange(it) }, { latestPreferencesRestore() },
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(),
                onOpenProjectOptions = { activeTool = null; simulationOptions = true },
                projectTemplates = projectTemplates, templatePicker = assets)
        }
    }
    val additionalTools = rememberAdditionalEditorTools(registry, assets, documents,
        onNotice = reportToolNotice, onFailure = state::reportError,
        highContrastSignals = preferences.highContrastSignals, signalRadix = preferences.signalRadix.xmlValue(),
        gateShape = preferences.gateShape, portableProjects = portableProjects, directories = directories, boardCatalog = boardCatalog,
        fpgaOptions = preferences.fpga, hdlType = preferences.hdlType, editorScope = state)
    val toolbarTool = remember(registry, state) { projectToolbarEditorTool(registry, state) }
    val editorTools = rememberBuiltInEditorTools(registry, documents, extensions,
        additionalDefaults = additionalTools + extensionTool + toolbarTool, preferencesTool = preferencesTool,
        memoryFiles = effectiveMemoryFiles, onNotice = reportToolNotice, onFailure = state::reportError, assets = assets)
    LaunchedEffect(state.project, state.rootSimulation, editorTools) {
        editorTools.forEach { it.onSimulation?.invoke(state.project, state.rootSimulation) }
    }
    val currentTools by rememberUpdatedState(editorTools)
    val runController = remember(state, scope) {
        SimulationRunController(scope, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step, state.componentRegistry).state },
            isCurrent = state::isCurrentRunSnapshot,
            publish = { snapshot, result ->
                state.publishRun(snapshot, result)
                currentTools.forEach { it.onSimulation?.invoke(snapshot.project, result) }
            },
            onFailure = { state.reportError(simulationFailureText(it)) },
            advanceWithObservations = { snapshot, step ->
                val job = currentCoroutineContext()[Job]
                advanceSimulation(snapshot, step, state.componentRegistry,
                    autoPropagating = SimulationProjectOptions.read(snapshot.project).autoPropagating,
                    cancellationCheck = { job?.ensureActive() })
            },
            publishObservations = { snapshot, observations ->
                if (snapshot.project === state.project) observations.forEach { observation ->
                    currentTools.forEach { it.onSimulationObservation?.invoke(snapshot.project, observation.state) }
                }
            }).also { it.setAutomaticTicks(state.automaticTicksEnabled) }
    }
    fun toggleAutomaticTicks() {
        state.automaticTicksEnabled = !runController.status.value.automaticTicks
        runController.setAutomaticTicks(state.automaticTicksEnabled)
    }
    DisposableEffect(runController) { onDispose { runController.close() } }
    val runStatus by runController.status.collectAsState()
    val running = runStatus.phase == SimulationRunPhase.RUNNING
    // The project's rate; the run controller follows it (configureFrequency below).
    val projectFrequency = remember(state.project) {
        runCatching { SimulationProjectOptions.read(state.project).tickFrequencyHz }.getOrNull()
    } ?: runStatus.frequencyHz
    /**
     * Simulate > Tick frequency and the frequency chip, as Evolution's Simulator.setTickFrequency: no undo entry and
     * the simulation keeps running. The controller restarts at the new rate before the project changes, so a step
     * still computing on the old revision exits on its ticket instead of pausing the run, and an input change still in
     * flight runs again on the new revision before a run waiting for it resumes.
     */
    fun chooseTickFrequency(hz: Double) {
        val previous = projectFrequency
        val changes = state.documentWritable && state.interactionsEnabled && hz != previous
        if (changes) runController.configureFrequency(hz, restartRunning = true)
        if (!state.setTickFrequency(hz) && changes) runController.configureFrequency(previous)
    }
    val automaticTicksAction = EditorToolbarAction(
        "automatic-ticks", strings("simulation_control_auto_tick"),
        enabled = state.documentWritable && state.interactionsEnabled, selected = runStatus.automaticTicks,
    ) { toggleAutomaticTicks() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var liveJar by remember(state) { mutableStateOf<JarEditorController?>(null) }
    var openingJar by remember(state) { mutableStateOf<Job?>(null) }
    fun stopJar() {
        openingJar?.cancel()
        liveJar?.stop()
        state.stopJarSimulation()
    }
    if (jarController != null) LaunchedEffect(state) { state.observeJarSimulation() }
    val projectReplaced by rememberUpdatedState(onProjectReplaced)
    val openWorkspaceSession by rememberUpdatedState(workspaceSession?.open)
    DisposableEffect(state, sessionControl, runController, lifecycle) {
        if (workspaceSession != null) state.openWorkspaceSession = { name, xml, report ->
            openWorkspaceSession?.invoke(name, xml, report) ?: false
        }
        state.onWorkspaceProjectReplaced = { projectReplaced(it) }
        state.onSimulationInvalidated = { stopJar(); runController.invalidate() }
        state.onSimulationRequested = runController::request
        sessionControl?.attach(pause = { stopJar(); runController.pause() },
            setActive = { requested ->
                if (!requested) stopJar()
                runController.setActive(requested && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            },
            exportView = state::exportViewState, importView = state::importViewState, identity = { state.documentIdentity }, exportXml = state::exportXml,
            documentWritable = { state.documentWritable }, exportImportReport = { state.importReport },
            setInteractionEnabled = { enabled ->
                if (!enabled) stopJar()
                state.setInteractionEnabled(enabled)
            }, reportOpened = state::reportOpened)
        runController.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sessionControl?.activationAllowed != false)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runController.setActive(sessionControl?.activationAllowed != false)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { stopJar(); runController.setActive(false) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            stopJar()
            lifecycle.removeObserver(observer)
            state.onSimulationInvalidated = {}
            state.onSimulationRequested = { _, _ -> SimulationRequestResult.REJECTED }
            state.onWorkspaceProjectReplaced = {}
            state.openWorkspaceSession = null
            state.setSimulationRunning(false)
            sessionControl?.detach()
            runController.pause()
        }
    }
    LaunchedEffect(running) { state.setSimulationRunning(running) }
    LaunchedEffect(state.revision) {
        if (state.revision > 0 && state.documentWritable) onDocumentChanged(state.exportXml())
    }
    LaunchedEffect(state.project) { runController.configureFrequency(SimulationProjectOptions.read(state.project).tickFrequencyHz) }
    // An instance of the running circuit keeps running; another circuit pauses and says why (C58).
    LaunchedEffect(state.activeCircuitName, state.activeInstancePath) { pauseOutsideRunningCircuit(state, runController) }
    val debugCopyIdentity = state.debugCopyIdentity
    val sendComponentEvent: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean =
        remember(state, state.document.id, state.activeInstancePath, state.documentIdentity, debugCopyIdentity) {
            val circuit = state.document.id
            val path = state.activeInstancePath.toList()
            val identity = state.documentIdentity
            val sender: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean =
                { id, event -> state.debugCopyIdentity === debugCopyIdentity &&
                    state.sendComponentEvent(id, event, path, circuit, identity) }
            sender
        }
    val importLoader = remember(state, scope, registry) {
        EditorImportLoader(scope, registry, { state.documentIdentity to state.revision }, { state.interactionsEnabled },
            onFailure = { state.reportError(it.localizedDescription(message("editor_import_failed"))) },
            onDiscarded = { state.reportStatus(message("editor_import_discarded")) })
    }
    DisposableEffect(importLoader) { onDispose { importLoader.close() } }
    var guidedLibraryOpen by remember(importLoader) { mutableStateOf<GuidedLibraryOpen?>(null) }
    LaunchedEffect(state.documentIdentity, state.revision) {
        guidedLibraryOpen?.let {
            if (state.documentIdentity != it.identity || state.revision != it.revision) guidedLibraryOpen = null
        }
    }
    val latestWorkspaceHeader by rememberUpdatedState(workspaceHeader)
    val documentSession = remember(state, documents, importLoader, directories, portableProjects) {
        documents?.let { actions -> EditorDocuments(state, actions, projectName = { latestWorkspaceHeader?.fileName },
            defaultName = { currentLocalizer(Messages.FileDefaultName) }, importDocument = { content, current, completed ->
            runController.pause()
            guidedLibraryOpen = null
            val origin = LocalizedText.literal(content.reference.name)
            if (!importLoader.open(content.text, isRequestCurrent = current,
                    commit = { result ->
                        actions.commitOpen(content.reference) {
                            if (directories != null && portableProjects != null && needsExternalLibraryFolder(result)) {
                                state.dismissImportError()
                                guidedLibraryOpen = GuidedLibraryOpen(content, state.documentIdentity, state.revision)
                                false
                            } else state.acceptImport(result, origin, content.text)
                        }
                    },
                    onComplete = completed)) completed(false)
        }) }
    }
    DisposableEffect(documentSession) {
        documentSession?.attach()
        onDispose { documentSession?.detach() }
    }
    val exportCircuit: ((String) -> Unit)? = if (assets != null && state.projectAccess.graphAvailable) {
        { name ->
            val identity = state.documentIdentity
            try {
                val xml = CircCodec.writeCircuit(state.project, name, state.componentRegistry)
                val fileName = name.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".circ"
                assets.create(DocumentAsset(fileName, "application/x-logisim-circuit", xml.encodeToByteArray())) { result ->
                    if (identity == state.documentIdentity) when (result) {
                        AssetActionResult.Completed -> state.reportStatus(message(Messages.StatusFileSaved, "name" to fileName))
                        is AssetActionResult.Failure -> state.reportError(result.notice)
                        AssetActionResult.Cancelled -> Unit
                    }
                }
            } catch (error: Exception) {
                state.reportError(error.localizedDescription(message(Messages.ErrorCircInvalid)))
            }
        }
    } else null
    val exampleLoader = remember(state, scope, registry) {
        EditorExampleLoader(scope, registry, { state.documentIdentity to state.revision }, state::installExample,
            { state.reportError(it.localizedDescription(message("error_example_load"))) })
    }
    DisposableEffect(exampleLoader) { onDispose { exampleLoader.close() } }
    var newProjectLoad by remember(state) { mutableStateOf<Job?>(null) }
    val newCircuitName = strings(Messages.NewCircuitName)
    val newProject: () -> Unit = newProject@{
        if (!state.interactionsEnabled) return@newProject
        runController.pause()
        if (projectTemplates == null) {
            state.newDocument(circuitName = newCircuitName)
            return@newProject
        }
        val token = state.documentIdentity to state.revision
        fun isCurrent() = state.interactionsEnabled && token == (state.documentIdentity to state.revision)
        newProjectLoad?.cancel()
        newProjectLoad = scope.launch {
            try {
                val (empty, template) = withContext(Dispatchers.Default) {
                    if (projectTemplates.type() == ProjectTemplateType.EMPTY) true to null else false to projectTemplates.readProject()
                }
                if (isCurrent()) { if (empty) state.newEmptyDocument(newCircuitName) else state.newDocument(template, newCircuitName) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent()) state.reportError(error.localizedDescription(message(Messages.ProjectTemplateReadFailed)))
            }
        }
    }
    val accessibilityContext = state.accessibilityContext()
    val multiEditUndo = remember(state) { MultiEditUndoNotice() }
    val inspectorSession = rememberPropertyInspectorSession(state, multiEditUndo)
    val currentInspectorSession by rememberUpdatedState(inspectorSession)
    val keyboardSession = rememberKeyboardInspectorSession(state)
    val currentKeyboardSession by rememberUpdatedState(keyboardSession)
    val toolIdentity = state.documentIdentity
    val toolProject = state.project
    val toolContext = EditorToolContext(state.project, state.document, state.simulation,
        state.document.components.filter { it.id in state.selectedIds },
        { if (state.documentIdentity == toolIdentity) state.applyProject(it) },
        { if (state.documentIdentity == toolIdentity) state.applyDocument(it) }, state::selectCircuit, { state.selectComponent(it) }, state::reportStatus,
        projectSimulation = state.rootSimulation, documentIdentity = state.documentIdentity, selectedWires = state.selectedWires,
        circuitLifetime = state::circuitLifetime,
        activeInstancePath = accessibilityContext.instancePath,
        commitProject = { next, completed ->
            if (state.documentIdentity != toolIdentity || state.project != toolProject ||
                !state.interactionsEnabled || !state.documentWritable) {
                completed(false)
            } else if (onCommitProject == null) {
                state.applyProject(next)
                completed(true)
            } else {
                val xml = CircCodec.writeProject(next, registry)
                onCommitProject(toolIdentity, xml) { saved ->
                    val current = scope.isActive && state.documentIdentity == toolIdentity && state.project == toolProject
                    if (saved && current) state.applyPersistedProject(next)
                    completed(saved && current)
                }
            }
        },
        isCurrentEditorContext = { state.debugCopyIdentity === debugCopyIdentity &&
            state.accessibilityContext() == accessibilityContext },
        projectExports = ProjectGraphExportPolicy(state.projectAccess) {
            scope.isActive && state.documentIdentity == toolIdentity && state.projectAccess.graphAvailable
        },
        sendComponentEvent = sendComponentEvent,
        cancelComponentEvent = runController::cancelEvent,
        injectEvents = { events -> state.documentIdentity == toolIdentity && state.debugCopyIdentity === debugCopyIdentity &&
            state.injectEvents(events) },
        isComponentEventPending = runController::isEventPending,
        pendingSimulationCommands = runStatus.pendingSteps,
        tickSimulation = { if (state.documentIdentity == toolIdentity && state.debugCopyIdentity === debugCopyIdentity) state.tick() },
        isForeground = runStatus.phase != SimulationRunPhase.INACTIVE && runStatus.phase != SimulationRunPhase.CLOSED,
        isSimulationRunning = running,
        openExample = { example ->
            if (state.interactionsEnabled) { runController.pause(); exampleLoader.open(example) } else false
        },
        openTool = { id ->
            val tool = editorTools.firstOrNull { it.id == id }
            if (tool != null && (!tool.requiresProjectGraph || state.projectAccess.graphAvailable)) {
                if (tool.pausesSimulation) runController.pause()
                activeTool = id; true
            } else {
                if (tool?.requiresProjectGraph == true) state.reportStatus(message("project_graph_unavailable"))
                false
            }
        },
        removeComponent = state::removeComponent, selectWire = { state.selectWire(it) }, removeWire = state::removeWire,
        navigate = state::focusLocation, connect = state::connectTerminals,
        removeSelection = state::removeSelected,
        openProperties = { activeTool = null; inspectorSheet = true },
        activateComponent = state::activateComponent, insertComponent = state::insertComponent,
        showNotice = reportToolNotice, extensionDocumentXml = state::exportExtensionSourceXml,
        printerView = state.printerView, closeTool = { activeTool = null },
        startSimulation = {
            val current = state.documentIdentity == toolIdentity && state.debugCopyIdentity === debugCopyIdentity
            current && state.documentWritable && state.interactionsEnabled && runController.start().also {
                if (it) state.changeMode(EditorMode.INTERACT)
            }
        },
        pauseSimulation = runController::pause,
        simulationControlsEnabled = state.documentWritable && state.interactionsEnabled,
        stepClock = { fullCycle ->
            if (state.documentIdentity == toolIdentity && state.debugCopyIdentity === debugCopyIdentity) {
                runController.pauseContinuousExecution()
                state.tick(fullCycle)
            }
        },
        editInputValue = { id -> state.requestInputValueEdit(id).also { if (it) editSignalInput(id) } },
        toggleInputBit = state::toggleInputBit, canOpenIoControls = state::requestIoControls,
        creationDefaults = state.creationDefaults, visibleCenter = state::visibleCenter).also { context ->
        if (jarController == null) context.startJarEditor = { launch ->
            val job = checkNotNull(currentCoroutineContext()[Job])
            val revision = state.revision
            fun isCurrent() = scope.isActive && state.documentIdentity == toolIdentity && state.revision == revision &&
                state.project === toolProject && state.interactionsEnabled && sessionControl?.activationAllowed != false &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            check(openingJar == null && liveJar == null && isCurrent() && state.exportExtensionSourceXml() == launch.xml)
            openingJar = job
            try {
                runController.pause()
                liveJar = JarEditorController.open(scope, launch.client, launch.request, launch.plan, launch.approval,
                    launch.xml, registry, launch.initial, ::isCurrent, state::reportError, onClosed = { liveJar = null })
                activeTool = null
            } finally {
                if (openingJar === job) openingJar = null
            }
        }
    }
    val accessibleActions = editorCircuitAccessibilityActions(toolContext)
    var memoryClearId by remember(state.documentIdentity) { mutableStateOf<String?>(null) }
    var imageOptimization by remember(state.documentIdentity) { mutableStateOf<PendingImageOptimization?>(null) }
    /** SocUpMenuProvider and SocBusMenuProvider windows: the SoC tool holds the assembler, program, map and bus. */
    fun openSocTool(id: String) {
        state.select(id)
        if (editorTools.any { it.id == "soc-debugger" }) { runController.pause(); activeTool = "soc-debugger" }
    }
    /** Evolution Menu Tool items that act outside the canvas (MenuTool.MenuComponent and MenuExtender, #347). */
    fun handleComponentMenu(item: ComponentMenuItem, id: String) {
        val component = state.document.components.firstOrNull { it.id == id } ?: return
        when (item) {
            ComponentMenuItem.ROTATE_LEFT, ComponentMenuItem.ROTATE_RIGHT -> state.rotateComponent(id, item == ComponentMenuItem.ROTATE_LEFT)
            ComponentMenuItem.DELETE -> state.removeComponent(id)
            ComponentMenuItem.SHOW_ATTRIBUTES, ComponentMenuItem.PLA_EDIT, ComponentMenuItem.PLA_ROM_EDIT -> {
                // The PLA program and PLA ROM matrix editors live in the attribute inspector.
                state.select(id)
                activeTool = null
                inspectorSheet = true
            }
            ComponentMenuItem.SPLITTER_DISTRIBUTE_ASCENDING, ComponentMenuItem.SPLITTER_DISTRIBUTE_DESCENDING ->
                state.redistributeSplitter(id, item == ComponentMenuItem.SPLITTER_DISTRIBUTE_ASCENDING)
            ComponentMenuItem.MEMORY_EDIT -> {
                state.select(id)
                if (editorTools.any { it.id == "memory-editor" }) { runController.pause(); activeTool = "memory-editor" }
            }
            ComponentMenuItem.MEMORY_CLEAR -> {
                // MemMenu.doClear: nothing to do when the contents are already zero; otherwise ask first.
                val image = runCatching { memoryEditorImage(component, state.simulation, registry) }.getOrNull() ?: return
                if (image.nonZeroWords.isNotEmpty()) memoryClearId = id
            }
            ComponentMenuItem.MEMORY_LOAD -> effectiveMemoryFiles?.importImage(component, MemoryImageFormat.HEX_WORDS_ADDRESSED) { result ->
                when (result) {
                    is MemoryImageImportResult.Success -> runCatching {
                        if (!applyMemoryEditorImage(toolContext, id, result.image, importedImage = result))
                            state.reportStatus(message("memory_tool_not_applied"))
                    }.onFailure { state.reportStatus(it.localizedDescription(message("memory_tool_invalid"))) }
                    is MemoryImageImportResult.Failure -> state.reportStatus(LocalizedText.join(result.diagnostics.map { it.notice }))
                    null -> Unit
                }
            }
            // PlaRom.PlaMenu Clear Contents: PlaRomData.clearMatrixValues saves an empty matrix.
            ComponentMenuItem.PLA_ROM_CLEAR ->
                state.updateComponentAttributes(id, mapOf("Contents" to ""), message(Messages.ComponentMenuMemoryClear))
            ComponentMenuItem.SOC_ASSEMBLER, ComponentMenuItem.SOC_SHOW_PROGRAM, ComponentMenuItem.SOC_MEMORY_MAP,
            ComponentMenuItem.SOC_INSERT_TRANSACTION -> openSocTool(id)
            ComponentMenuItem.SOC_READ_ELF -> assets?.open(listOf("application/octet-stream", "application/x-elf", "application/x-executable"),
                SocElfLoader.MAX_SEGMENT_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> {
                        fun program() = (state.simulation.componentStates[id] as? SocRv32ComponentState)?.debugger?.program
                            ?: (state.simulation.componentStates[id] as? SocNios2ComponentState)?.debugger?.program
                        val before = program()
                        if (!sendComponentEvent(id, SocLoadExecutableEvent(result.asset.bytes))) state.reportStatus(message("soc_instance_unavailable"))
                        else if (program().let { it != null && it !== before }) state.reportStatus(message(Messages.ComponentMenuSocElfLoaded))
                    }
                    is AssetOpenResult.Failure -> state.reportStatus(result.notice)
                    AssetOpenResult.Cancelled -> Unit
                }
            } ?: openSocTool(id)
            // PioMenu and VgaMenu Export C-templates: the header and source of SocDriverExport.
            ComponentMenuItem.SOC_EXPORT_C -> {
                val files = SocDriverExport.export(component)
                if (assets?.supportsBatchCreate != true || files == null) openSocTool(id)
                else assets.createBatch(listOf(
                    DocumentAsset("${files.name}.h", "text/x-c", files.header.encodeToByteArray()),
                    DocumentAsset("${files.name}.c", "text/x-c", files.source.encodeToByteArray()),
                )) { result ->
                    when (result) {
                        AssetActionResult.Completed -> state.reportStatus(message("soc_driver_files_saved", "name" to files.name))
                        AssetActionResult.Cancelled -> Unit
                        is AssetActionResult.Failure -> state.reportStatus(result.notice)
                    }
                }
            }
            ComponentMenuItem.IMAGE_RESET_SIZE, ComponentMenuItem.IMAGE_OPTIMIZE, ComponentMenuItem.IMAGE_MAKE_TRANSPARENT ->
                state.applyImageMenu(item, id)?.let { imageOptimization = it }
            ComponentMenuItem.MEMORY_SAVE -> {
                val image = runCatching { memoryEditorImage(component, state.simulation, registry) }.getOrNull() ?: return
                effectiveMemoryFiles?.exportImage(component, MemoryImageFormat.HEX_WORDS_ADDRESSED,
                    MemoryImageCodec.encode(image, MemoryImageFormat.HEX_WORDS_ADDRESSED))
            }
            else -> Unit
        }
    }
    fun stepClock(fullCycle: Boolean = false) {
        runController.pauseContinuousExecution()
        state.tick(fullCycle)
    }
    val toolbarShortcuts = remember { ToolbarShortcutTargets() }
    val shortcutModifier = Modifier.editorKeyboardShortcuts(
        scheme = preferences.shortcutScheme,
        overrides = preferences.shortcutOverrides,
        enabled = {
            !zoomDialog && !zoomMenu && state.interactionsEnabled && inspectorSession.focus.focusedPresentation == null &&
                keyboardSession?.focus?.focusedPresentation == null
        },
        onDoubleShift = if (preferences.searchDoubleShift) ({ openFindAction(); true }) else null,
    ) { command ->
        command.toolIndex?.let { index -> return@editorKeyboardShortcuts toolbarShortcuts.select(index) }
        if (command.requiresWritableDocument && !state.documentWritable &&
            !(jarController != null && command == EditorShortcut.RESET)) return@editorKeyboardShortcuts false
        when (command) {
            EditorShortcut.NEW -> newProject()
            EditorShortcut.OPEN -> {
                val session = documentSession ?: return@editorKeyboardShortcuts false
                runController.pause()
                session.openCopy()
            }
            EditorShortcut.SAVE, EditorShortcut.SAVE_AS -> {
                val session = documentSession ?: return@editorKeyboardShortcuts false
                runController.pause()
                if (command == EditorShortcut.SAVE && session.canSave) session.save() else session.saveAs()
            }
            EditorShortcut.RUN -> {
                state.changeMode(EditorMode.INTERACT)
                if (runController.status.value.phase == SimulationRunPhase.RUNNING) runController.pause()
                else return@editorKeyboardShortcuts runController.start()
            }
            EditorShortcut.STEP_EVENT -> return@editorKeyboardShortcuts runController.step(SimulationStep.EVENT) != null
            EditorShortcut.STEP_CLOCK, EditorShortcut.STEP_CLOCK_CYCLE -> stepClock(command == EditorShortcut.STEP_CLOCK_CYCLE)
            EditorShortcut.AUTOMATIC_TICKS -> toggleAutomaticTicks()
            EditorShortcut.RESET -> { runController.pause(); state.resetSimulation() }
            EditorShortcut.FIND_ACTION -> openFindAction()
            // Mobile exports and prints through the drawing export panel, which holds Print PDF.
            EditorShortcut.FILE_EXPORT, EditorShortcut.FILE_PRINT -> return@editorKeyboardShortcuts toolContext.openTool("visual-export")
            // The editor is the project's window; closing it returns to the workspace's project list.
            EditorShortcut.WINDOW_CLOSE -> workspaceHeader?.takeIf { !it.busy }?.onOpenProjects?.invoke()
                ?: return@editorKeyboardShortcuts false
            EditorShortcut.PROJ_MOVE_UP, EditorShortcut.PROJ_MOVE_DOWN ->
                return@editorKeyboardShortcuts state.moveCircuit(state.document.id, if (command == EditorShortcut.PROJ_MOVE_UP) -1 else 1)
            else -> return@editorKeyboardShortcuts false
        }
        true
    }
    val buzzerTones = remember(state.rootSimulation) {
        buildMap<String, dev.logisim.mobile.core.BuzzerTone> {
            state.rootSimulation.componentStates.forEach { (id, value) -> if (value is dev.logisim.mobile.core.BuzzerTone) put(id, value) }
            state.rootSimulation.circuitStates.forEach { (path, child) -> child.componentStates.forEach { (id, value) ->
                if (value is dev.logisim.mobile.core.BuzzerTone) put("$path/$id", value)
            } }
        }
    }
    val darkTheme = preferences.themeMode.resolveDark()
    val canvasColors = preferences.effectiveColors(darkTheme)
    // COMPONENT_ICON_COLOR of the theme in use; the light default is black, like Evolution's.
    val componentIconColor = canvasColors[EditorColor.COMPONENT_ICON] ?: (EditorColor.COMPONENT_ICON.defaultColor(false).toArgb() and 0xFFFFFF)
    val creationDefaults = preferences.creationDefaultsWithTextColor
    SideEffect { state.creationDefaults = creationDefaults }
    ExtraIoAudioEffects(buzzerTones, enabled = runStatus.phase != SimulationRunPhase.INACTIVE && runStatus.phase != SimulationRunPhase.CLOSED,
        onFailure = { state.reportError(it) })
    LogisimTheme(darkTheme) { CompositionLocalProvider(LocalEditorPreferences provides
        remember(preferences) { EditorPreferencesAccess(preferences) { latestPreferencesChange(it) } }) {
        editorTools.forEach { tool -> key(tool.id) {
            if (!tool.requiresProjectGraph || toolContext.projectExports.available) tool.runtime(toolContext)
        } }
        Surface(Modifier.fillMaxSize(), color = ChromeColors.Bar) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // The software keyboard shrinks the safe drawing area. Decide the side explorer by the
                // height without it: dropping the explorer while its New circuit dialog took text input
                // closed the dialog as soon as the keyboard opened (#319).
                val wide = editorLayoutIsWide(maxWidth, maxHeight, keyboardInsetHeight())
                val sidebarShown = wide && explorerVisible
                TrackInspectorPresentation(inspectorSession.focus, if (sidebarShown) InspectorPresentation.INLINE else InspectorPresentation.SHEET) {
                    inspectorSheet = it == InspectorPresentation.SHEET
                }
                keyboardSession?.let { session ->
                    TrackInspectorPresentation(session.focus, if (sidebarShown) InspectorPresentation.INLINE else InspectorPresentation.SHEET) {
                        inspectorSheet = it == InspectorPresentation.SHEET
                    }
                }
                val compact = maxHeight < 420.dp
                val narrow = maxWidth < 600.dp
                val windowWidth = maxWidth
                val shortLandscape = maxHeight < 480.dp && maxWidth > maxHeight
                val toolbarPlacement = preferences.toolbarPlacement.effectivePlacement(sidebarShown, hasProjectToolbar = true, shortLandscape)
                val simulationPlacement = simulationControlsPlacement(toolbarPlacement, narrow, preferences.showHeaderBar)
                // The full menu bar needs room beside the title; with the simulation controls also in
                // the header, it folds into the compact menu until the window is wide.
                val menuBar = maxWidth >= 720.dp &&
                    (simulationPlacement != SimulationControlsPlacement.HEADER || maxWidth >= 1000.dp)
                // Signals opened as a sheet on a short window stay open, inline, once the window grows.
                if (!compact && signalSheet) SideEffect { signalSheet = false; signalsVisible = true }
                val writable = state.documentWritable && state.interactionsEnabled
                val hasSelection = state.selectedIds.isNotEmpty() || state.selectedWires.isNotEmpty()
                val canEditSelection = hasSelection && state.pendingPaste == null
                val language = strings.language

                val separator = { id: String -> EditorToolbarAction(id, "", separator = true, onClick = {}) }

                val toolActions = projectToolbarActions(state, strings(Messages.ActionMove), componentIconColor)
                toolbarShortcuts.actions = toolActions
                // The Edit menu's Text item uses the project's own Text Tool, with its attributes, when the toolbar has one.
                val projectText = remember(state.project.extraXml, state.project.libraries, state.project.sourceVersion,
                    state.project.circuits, registry) { projectTextTool(projectToolbarEntries(state.project, registry)) }
                val textToolPrototype = remember(projectText, state.project.libraries, state.project.sourceVersion, state.project.circuits, registry) {
                    projectText?.prototype ?: mobileTextToolPrototype(state.project, registry)
                }

                val runAction = EditorToolbarAction("run", strings(if (running) Messages.ActionPause else Messages.ActionRun),
                    enabled = writable,
                    icon = if (running) EditorIcon.PAUSE else EditorIcon.PLAY) {
                    state.changeMode(EditorMode.INTERACT)
                    if (running) runController.pause() else runController.start()
                }
                // One name per action for the bar (tooltip), the Simulate menu and TalkBack.
                val eventAction = EditorToolbarAction("event", strings("simulation_control_step_event"),
                    enabled = writable, icon = EditorIcon.STEP) { runController.step(SimulationStep.EVENT) }
                val tickAction = EditorToolbarAction("step", strings("simulation_control_tick_half"),
                    enabled = writable, icon = EditorIcon.TICK) { stepClock() }
                val tickFullAction = EditorToolbarAction("tick-cycle", strings("simulation_control_tick_full"),
                    enabled = writable, icon = EditorIcon.TICK_FULL) { stepClock(fullCycle = true) }
                val autoTickAction = automaticTicksAction.copy(icon = EditorIcon.AUTO_TICK)
                val resetAction = EditorToolbarAction("reset", strings(Messages.A11yResetSimulation),
                    enabled = writable || jarController != null, icon = EditorIcon.RESET) { runController.pause(); state.resetSimulation() }
                val tickCount = state.simulation.ticks
                val ticksText = strings("simulation_ticks", "count" to tickCount)
                // The toolbar and the phone bottom bar give the counter all the width it needs; only the header
                // shortens it to the count (TicksCounter), so the other bars keep a plain text that no tick re-measures.
                val stepsLabel: @Composable () -> Unit = { TicksText(ticksText) }
                val simulationControls = SimulationControls(
                    title = strings("simulation_controls_title"),
                    run = runAction, event = eventAction, tickHalf = tickAction, tickFull = tickFullAction,
                    autoTick = autoTickAction, reset = resetAction,
                    frequency = TickFrequencyControl(projectFrequency, writable, runStatus.measuredTicksPerSecond, ::chooseTickFrequency),
                    menuExtras = listOf(EditorToolbarAction("simulation-options", strings("menu_simulation_options")) {
                        runController.pause(); simulationOptions = true
                    }),
                )

                val explorerAction = if (wide) EditorToolbarAction("explorer", strings("action_explorer"), strings("a11y_toggle_explorer"),
                    selected = explorerVisible, icon = EditorIcon.SIDEBAR) { explorerVisible = !explorerVisible }
                else EditorToolbarAction("library", strings(Messages.ActionLibrary), strings(Messages.A11yOpenLibrary), icon = EditorIcon.LIBRARY) {
                    findActions = false
                    librarySheet = true
                    sheetOpenerTag = "tool-library"
                }
                val propertiesAction = EditorToolbarAction("properties", strings(Messages.ActionProperties), strings(Messages.A11yOpenProperties),
                    icon = EditorIcon.PROPERTIES) { inspectorSheet = true; sheetOpenerTag = "tool-properties" }
                // A short window opens Signals as a sheet, which would be empty without pins, probes or displays (C68).
                val signalsAction = EditorToolbarAction("signals", strings(Messages.SignalsHeading),
                    enabled = !compact || signalStripComponents(state.document.components).isNotEmpty(),
                    selected = if (compact) null else signalsVisible, icon = EditorIcon.SIGNALS) {
                    if (compact) { signalSheet = true; sheetOpenerTag = "tool-signals" } else signalsVisible = !signalsVisible
                }
                // Evolution's Edit menu and header tooltips name the action Undo and Redo apply (MenuEdit, UnifiedHeaderBar).
                val history = editorHistoryLabels(strings, state.undoHistory.firstOrNull()?.label, state.redoHistory.firstOrNull()?.label)
                val undoAction = EditorToolbarAction("undo", history.undoLabel, history.undoDescription,
                    state.canUndo && state.interactionsEnabled,
                    icon = EditorIcon.UNDO, onClick = state::undo)
                val redoAction = EditorToolbarAction("redo", history.redoLabel, history.redoDescription,
                    state.canRedo && state.interactionsEnabled,
                    icon = EditorIcon.REDO, onClick = state::redo)

                // Each project tool sits in the menu Evolution gives it (editorToolMenu), with the same item id everywhere.
                fun toolsIn(menu: String) = editorTools.filter { it.id != "legal-notices" && editorToolMenu(it.id) == menu }.map { tool ->
                    EditorToolbarAction(if (tool.id == ASSEMBLY_VIEWER_ID) tool.id else "project-tool-${tool.id}", strings(tool.titleText),
                        enabled = !tool.requiresProjectGraph || toolContext.projectExports.available) {
                        if (tool.pausesSimulation) runController.pause()
                        activeTool = tool.id
                    }
                }
                val menus = buildList {
                    add(EditorMenu("file", strings("menu_file"), description = strings(Messages.A11yProjectFiles), itemsTag = "editor-file-menu", items = buildList {
                        add(EditorToolbarAction("new", strings(Messages.ActionNew), strings(Messages.A11yNewCircuit),
                            enabled = state.interactionsEnabled) { newProject() })
                        workspaceHeader?.onOpenProjects?.let { open ->
                            add(EditorToolbarAction("projects", strings("menu_projects"), strings("a11y_open_projects"), enabled = !workspaceHeader.busy) { open() })
                        }
                        documentSession?.let { session ->
                            add(separator("file-documents"))
                            add(EditorToolbarAction("open", strings(Messages.ActionImportCopy)) { runController.pause(); session.openCopy() })
                            if (session.canEditInPlace || workspaceHeader?.onOpenRecent != null) {
                                val label = if (session.canEditInPlace) Messages.ActionEditExternal else Messages.WorkspaceOpenOriginal
                                add(EditorToolbarAction("edit-external", strings(label)) { runController.pause(); session.editFile() })
                            }
                            workspaceHeader?.onOpenRecent?.let { open ->
                                add(EditorToolbarAction("recent", strings(Messages.WorkspaceOpenRecent), enabled = !workspaceHeader.busy) {
                                    runController.pause(); open()
                                })
                            }
                            // Evolution's doSave: with no file to save in place, Save asks for one, as Ctrl+S does.
                            add(EditorToolbarAction("save", strings(if (session.canSave) Messages.ActionSaveFile else "action_save_file_picker"),
                                enabled = state.documentWritable) { runController.pause(); session.saveOrSaveAs() })
                            add(EditorToolbarAction("save-as", strings(Messages.ActionSaveAs)) { runController.pause(); session.saveAs() })
                            add(EditorToolbarAction("share", strings(Messages.ActionShare)) { runController.pause(); session.share() })
                            if (session.canShareOriginalCopy) add(EditorToolbarAction("share-original", strings(Messages.ActionShareOriginalCirc)) {
                                runController.pause(); session.shareOriginalCopy()
                            })
                        }
                        if (assets != null) add(EditorToolbarAction("merge-project", strings("project_merge_title"),
                            enabled = state.documentWritable && state.interactionsEnabled) {
                            runController.pause()
                            mergeProject = true
                        })
                        add(EditorToolbarAction("xml", strings("menu_import_export_circ"), strings(Messages.A11yImportExportProject)) {
                            runController.pause(); xmlText = state.exportXml(); xmlDialog = true
                        })
                        // MenuFile's Export Image and Print, and the portable project bundles.
                        toolsIn("file").takeIf { it.isNotEmpty() }?.let { add(separator("file-export")); addAll(it) }
                        addAll(exampleMenuActions(strings, state.interactionsEnabled) { example ->
                            scope.launch {
                                menuExampleOpener.openReporting(example, registry, { state.project },
                                    accept = { toolContext.openExample(it) }, notice = state::reportStatus)
                            }
                        })
                        add(separator("file-settings"))
                        if (state.importReport != null) add(EditorToolbarAction("import-report", strings("import_report_title"), onClick = state::showImportReport))
                        add(EditorToolbarAction("preferences", strings("menu_preferences"), strings(Messages.PreferencesTitle)) {
                            runController.pause(); preferencesSheet = true
                        })
                    }))
                    add(EditorMenu("edit", strings("menu_edit"), buildList {
                        add(undoAction)
                        add(redoAction)
                        add(EditorToolbarAction("undo-history", strings("history_undo"), enabled = undoAction.enabled) {
                            runController.pause()
                            historyDirection = EditorHistoryDirection.UNDO
                        })
                        add(EditorToolbarAction("redo-history", strings("history_redo"), enabled = redoAction.enabled) {
                            runController.pause()
                            historyDirection = EditorHistoryDirection.REDO
                        })
                        add(EditorToolbarAction("clear-history", strings(Messages.ActionClearHistory),
                            enabled = state.interactionsEnabled && (state.canUndo || state.canRedo)) {
                            clearHistoryDialog = true
                        })
                        add(EditorToolbarAction("placement-auto-label", strings("placement_auto_label"),
                            enabled = state.canAutoLabelPlacement, selected = state.placementAutoLabel != null) { openPlacementAutoLabel() })
                        add(separator("edit-clipboard"))
                        add(EditorToolbarAction("cut", strings(Messages.ActionCut), strings(Messages.A11yCutSelection),
                            canEditSelection && writable, onClick = state::cutSelected))
                        add(EditorToolbarAction("copy", strings(Messages.ActionCopy), strings(Messages.A11yCopySelection), canEditSelection, onClick = state::copySelected))
                        add(EditorToolbarAction("paste", strings(Messages.ActionPaste), strings(Messages.A11yPasteComponents),
                            state.canPaste && writable, onClick = state::paste))
                        add(EditorToolbarAction("duplicate", strings(Messages.ActionDuplicate), strings(Messages.A11yDuplicateSelection),
                            canEditSelection && writable, onClick = state::duplicateSelected))
                        add(EditorToolbarAction("delete", strings(Messages.ActionDelete), strings(Messages.A11yDeleteSelection),
                            canEditSelection && writable, onClick = state::removeSelected))
                        val canTurn = canEditSelection && writable && state.canRotateSelection
                        add(EditorToolbarAction("rotate-left", strings(Messages.ComponentMenuRotateLeft), enabled = canTurn,
                            icon = EditorIcon.ROTATE_LEFT) { state.rotateSelected(left = true) })
                        add(EditorToolbarAction("rotate-right", strings(Messages.ComponentMenuRotateRight), enabled = canTurn,
                            icon = EditorIcon.ROTATE_RIGHT) { state.rotateSelected(left = false) })
                        add(separator("edit-selection"))
                        add(EditorToolbarAction("select-all", strings(Messages.ActionSelectAll), strings(Messages.A11ySelectAll), onClick = state::selectAll))
                        if (state.mode in AdditiveSelectionModes) {
                            add(EditorToolbarAction("additive-selection", strings(Messages.SelectionAddToggle),
                                enabled = state.interactionsEnabled, selected = state.additiveSelection,
                                onClick = state::toggleAdditiveSelection))
                        }
                        // The tools, whatever the project's toolbar holds: a touch screen has no tool keys.
                        add(separator("edit-modes"))
                        mobileBaseToolAction(state, MobileBaseTool.POKE, strings, null, "mode-poke")?.let(::add)
                        mobileBaseToolAction(state, MobileBaseTool.EDIT, strings, null, "mode-edit")?.let(::add)
                        add(EditorToolbarAction("select", strings(Messages.ActionSelect), selected = state.mode == EditorMode.SELECT,
                            icon = EditorIcon.MARQUEE) { state.changeMode(EditorMode.SELECT) })
                        add(EditorToolbarAction("move", strings(Messages.ActionMove), selected = state.mode == EditorMode.MOVE,
                            icon = EditorIcon.MOVE) { state.changeMode(EditorMode.MOVE) })
                        mobileBaseToolAction(state, MobileBaseTool.WIRE, strings, null, "mode-wire")?.let(::add)
                        mobileBaseToolAction(state, MobileBaseTool.TEXT, strings, textToolPrototype, "mode-text")?.let(::add)
                        if (state.mode == EditorMode.MOVE || state.mode == EditorMode.SELECT || state.mode == EditorMode.EDIT) {
                            add(EditorToolbarAction("keep-move-connections", strings(Messages.ActionKeepMoveConnections),
                                enabled = state.documentWritable && state.interactionsEnabled,
                                selected = state.keepConnectionsWhenMoving) {
                                state.keepConnectionsWhenMoving = !state.keepConnectionsWhenMoving
                            })
                        }
                        if (state.mode == EditorMode.PLACE) {
                            add(EditorToolbarAction("place-matrix", strings(Messages.MatrixPlaceTitle),
                                enabled = writable && state.pendingPaste == null, selected = state.placementMatrixEnabled,
                                onClick = state::togglePlacementMatrix))
                        }
                        if (!sidebarShown) add(propertiesAction)
                        // A hidden toolbar folds the project's own tools in here, less the modes the tool group above lists.
                        val projectTools = hiddenToolbarMenuActions(toolActions, projectText?.actionId)
                        if (toolbarPlacement == EditorToolbarPlacement.HIDDEN && projectTools.isNotEmpty()) {
                            add(separator("edit-tools"))
                            addAll(projectTools)
                        }
                    }))
                    add(EditorMenu("view", strings("menu_view"), buildList {
                        add(explorerAction)
                        add(signalsAction)
                        add(separator("view-zoom"))
                        add(EditorToolbarAction("fit", strings(Messages.ActionFit), strings(Messages.A11yFitCircuit), icon = EditorIcon.FIT, onClick = state::fitViewport))
                        add(EditorToolbarAction("fit-selection", strings(Messages.ActionFitSelection), strings(Messages.A11yFitSelection), hasSelection, onClick = state::fitSelected))
                        add(EditorToolbarAction("zoom-exact", strings(Messages.ActionZoomExact)) { zoomDialog = true })
                        add(EditorToolbarAction("zoom-in", strings(Messages.ActionZoomIn), strings(Messages.A11yZoomIn), icon = EditorIcon.ZOOM_IN) { state.zoomBy(1.25f) })
                        add(EditorToolbarAction("zoom-out", strings(Messages.ActionZoomOut), strings(Messages.A11yZoomOut), icon = EditorIcon.ZOOM_OUT) { state.zoomBy(1f / 1.25f) })
                        add(separator("view-printer"))
                        add(EditorToolbarAction("printer-view", strings("action_printer_view"),
                            strings(if (state.printerView) "a11y_live_view" else "a11y_printer_view"),
                            selected = state.printerView, onClick = state::togglePrinterView))
                    }))
                    val projectMenuTools = toolsIn("project")
                    add(EditorMenu("project", strings("menu_project"), description = strings(Messages.A11yProjectTools), itemsTag = "editor-tool-menu", items = buildList {
                        addAll(circuitCommandActions(state, strings, writable, onAdd = { circuitNameDialog = "" },
                            onRemove = { removeCircuitDialog = it }))
                        add(separator("project-libraries"))
                        add(EditorToolbarAction("manage-libraries", strings("active_library_manage")) { runController.pause(); activeLibraries = true })
                        if (projectMenuTools.isNotEmpty()) add(separator("project-tools"))
                        addAll(projectMenuTools)
                    }))
                    add(EditorMenu("simulate", strings("menu_simulate"), buildList {
                        add(runAction.copy(label = strings(if (running) "menu_pause" else "menu_run")))
                        add(eventAction)
                        add(tickAction)
                        add(tickFullAction)
                        add(autoTickAction)
                        add(separator("simulate-reset"))
                        add(resetAction)
                        // MenuSimulate's Tick Frequency radio items under one heading, as plain rates.
                        add(EditorToolbarAction("simulate-frequency", strings("simulation_tick_frequency"), separator = true, onClick = {}))
                        TickFrequencies.choicesWith(projectFrequency).forEach { choice ->
                            val rate = formatTickFrequency(choice.hz)
                            add(EditorToolbarAction("frequency-${choice.hz}",
                                if (choice.custom) strings("simulation_tick_frequency_custom", "value" to rate) else rate,
                                strings("simulation_tick_frequency_value", "value" to rate),
                                enabled = writable, selected = TickFrequencies.same(choice.hz, projectFrequency)) {
                                chooseTickFrequency(choice.hz)
                            })
                        }
                        add(separator("simulate-options"))
                        add(EditorToolbarAction("simulation-options", strings("menu_simulation_options"), strings(Messages.SimulationOptionsTitle)) {
                            runController.pause(); simulationOptions = true
                        })
                        // MenuSimulate's Logging, Test Vector and Assembly Viewer, with the other run-time tools.
                        toolsIn("simulate").takeIf { it.isNotEmpty() }?.let { add(separator("simulate-tools")); addAll(it) }
                        if (state.simulation.notices.isNotEmpty()) add(EditorToolbarAction("diagnostics",
                            strings("menu_warnings", "count" to state.simulation.notices.distinct().size), strings(Messages.SimulationWarningsTitle)) {
                            diagnosticsDialog = true
                        })
                    }))
                    // Evolution's FPGA menu, with the HDL, companion and Tcl tools; it shares the tools menu tag with Project.
                    toolsIn("fpga").takeIf { it.isNotEmpty() }?.let { tools ->
                        add(EditorMenu("fpga", strings("menu_fpga"), tools, description = strings("a11y_fpga_tools"), itemsTag = "editor-tool-menu"))
                    }
                    add(EditorMenu("help", strings("menu_help"), buildList {
                        add(EditorToolbarAction("find-action", strings(Messages.MenuFindAction), onClick = openFindAction))
                        addAll(toolsIn("help"))
                        add(EditorToolbarAction("help", strings(Messages.EditorHelpTitle), strings(Messages.A11yOpenHelp)) { helpDialog = true })
                        // Licenses opens its dialog directly, not a sheet holding one more button.
                        editorTools.firstOrNull { it.id == "legal-notices" }?.let { tool ->
                            add(EditorToolbarAction("legal-notices", strings(tool.titleText)) { legalDialog = true })
                        }
                    }))
                }
                val searchActions = editorSearchActions(menus, preferences.shortcutScheme, overrides = preferences.shortcutOverrides)

                Column(Modifier.fillMaxSize().then(shortcutModifier)) {
                    if (preferences.showHeaderBar) EditorHeader(
                        title = state.document.name,
                        project = workspaceHeader?.projectName,
                        saveState = workspaceHeader?.status,
                        compactSaveState = workspaceHeader?.compactStatus,
                        busy = workspaceHeader?.busy == true,
                        circuitPicker = { expanded, dismiss ->
                            HeaderCircuitPicker(expanded, dismiss, state.project, state.activeTarget, onNavigate = state::navigate,
                                onAddCircuit = if (writable) ({ circuitNameDialog = "" }) else null,
                                onOpenProjects = workspaceHeader?.onOpenProjects, projectsEnabled = workspaceHeader?.busy != true)
                        },
                        menus = menus,
                        menuBar = menuBar && headerFitsMenuBar(windowWidth, chromeMenuBarWidth(menus), trailingButtons = 2,
                            trailingDividers = if (simulationPlacement == SimulationControlsPlacement.HEADER) 1 else 0,
                            simulationInHeader = simulationPlacement == SimulationControlsPlacement.HEADER,
                            counterMin = ticksLabelWidth(TicksLabelReserveCount.toString())),
                        leading = explorerAction,
                        singleLine = shortLandscape,
                        // Half the header at most, as in the toolbar, so the title keeps room of its own.
                        simulation = if (simulationPlacement == SimulationControlsPlacement.HEADER) ({
                            HeaderSimulationControls({ TicksCounter(ticksText, tickCount) }, simulationControls, maxBarWidth = windowWidth / 2,
                                counterReserve = ticksLabelWidth(strings("simulation_ticks", "count" to TicksLabelReserveCount)))
                        }) else null,
                        trailing = buildList {
                            if (simulationPlacement == SimulationControlsPlacement.HEADER) add(separator("header-history"))
                            add(undoAction)
                            add(redoAction)
                        },
                    )
                    if (!state.documentWritable) Text(strings("document_read_only_preserved"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(ChromeColors.Warning).padding(horizontal = 8.dp, vertical = 4.dp))
                    if (state.isDebuggingInstance) {
                        Row(Modifier.fillMaxWidth().background(ChromeColors.Warning).padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(strings("subcircuit_debug_banner"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = state::returnToLiveInstance) { Text(strings("subcircuit_debug_return")) }
                        }
                    }
                    key("editor-workspace") {
                        EditorWorkspaceLayout(
                            placement = toolbarPlacement, dividerColor = ChromeColors.Divider,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            sidebar = if (sidebarShown) ({
                                // The docked inspector takes up to 60% of the sidebar's height, at least the 300 dp it
                                // had, so a large I/O panel leaves room for the attribute table (C15). Without I/O
                                // controls it stops where the library would keep less than its filter, Manage
                                // libraries and two categories (C15 follow-up).
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                val inspectorMax = dockedInspectorMaxHeight(maxHeight, state.selected?.kind.hasDockedIoControls())
                                Column(Modifier.fillMaxSize().background(ChromeColors.Panel)) {
                                    LibraryPanel(state, searchActions, libraryExplorer, Modifier.weight(1f).fillMaxWidth(),
                                        onManageLibraries = { runController.pause(); activeLibraries = true },
                                        onExportCircuit = exportCircuit,
                                        onChoose = { runController.pause() }, componentIconColor = componentIconColor)
                                    HorizontalDivider(color = ChromeColors.Divider)
                                    PanelHeading(state.selected?.let { strings("selection_heading", "name" to it.kind.localizedLabel()) }
                                        ?: strings(Messages.ActionProperties))
                                    PropertyInspector(state, runStatus, runController::isEventPending, inspectorSession,
                                        InspectorPresentation.INLINE, { inspectorSession.applyLabel(state, currentInspectorSession) },
                                        keyboardSession, { currentKeyboardSession }, { currentInspectorSession },
                                        Modifier.fillMaxWidth().heightIn(max = inspectorMax).testTag("editor-inspector-inline"),
                                        onOpenTool = { id -> inspectorSession.commitLabelDraft(); toolContext.openTool(id) })
                                }
                                }
                            }) else null,
                            toolbar = {
                                val trailing: (@Composable () -> Unit)? = when {
                                    simulationPlacement == SimulationControlsPlacement.TOOLBAR -> ({
                                        if (!sidebarShown) ChromeButton(propertiesAction)
                                        Row(Modifier.testTag("editor-simulation-toolbar"), horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            stepsLabel()
                                            // Half the toolbar at most, so the tools keep room of their own.
                                            SimulationControlBar(simulationControls, Modifier.widthIn(max = windowWidth / 2), barTag = null)
                                        }
                                    })
                                    !sidebarShown -> ({ ChromeButton(propertiesAction) })
                                    else -> null
                                }
                                if (toolbarPlacement != EditorToolbarPlacement.HIDDEN) {
                                    ChromeToolbar(toolActions, "editor-command-toolbar", strings("toolbar_tools_title"),
                                        Modifier.background(ChromeColors.Bar), orientation = toolbarPlacement.orientation, trailing = trailing)
                                }
                            },
                        ) {
                            Column(Modifier.fillMaxSize().background(ChromeColors.Surface)) {
                                if (!compact) SimulationDiagnostics(state.simulation.notices, state.simulation.noticeSources) { state.showComponents(it) }
                                CircuitCanvas(
                                    document = state.document, simulation = state.simulation, registry = registry,
                                    projectStates = toolContext.projectSimulation.circuitStates,
                                    showGrid = preferences.showGrid, snapToGrid = preferences.snapToGrid,
                                    measuredTicksPerSecond = runStatus.measuredTicksPerSecond.takeIf { preferences.showTickRate },
                                    showPlacementPreview = preferences.showPlacementPreview && state.interactionsEnabled && state.documentWritable,
                                    placementPrototype = state.placementPreview(),
                                    colors = canvasColors,
                                    attributeHalo = preferences.attributeHalo, componentTips = preferences.componentTips,
                                    pinDotDiameter = preferences.pinAppearance.diameter,
                                    valueCharacters = preferences.valueCharacters,
                                    highContrastSignals = preferences.highContrastSignals, signalRadix = preferences.signalRadix.xmlValue(),
                                    gateShape = preferences.gateShape, shortcutScheme = preferences.shortcutScheme,
                                    shortcutOverrides = preferences.shortcutOverrides,
                                    selectedId = state.selectedId, pendingWire = state.pendingWire,
                                    wirePoke = state.wirePoke, onWirePoke = state::pokeWire, onDismissWirePoke = state::dismissWirePoke,
                                    wirePokePrimaryRadix = preferences.wirePokePrimaryRadix,
                                    wirePokeSecondaryRadix = preferences.wirePokeSecondaryRadix,
                                    selectedIds = state.selectedIds,
                                    mode = state.mode, modifier = Modifier.weight(1f).fillMaxWidth().testTag("editor-canvas"),
                                    onCanvasTap = state::tap, onComponentEvent = sendComponentEvent,
                                    sequentialHexTarget = sequentialHexTarget,
                                    onSequentialHexEdit = if (sequentialPokeEnabled) {
                                        { sequentialHexTarget = it }
                                    } else null,
                                    onTextToolTap = { target, prototype -> textToolEdit = state.beginTextToolEdit(target, prototype) },
                                    editedMemoryWord = (memoryEdit as? CanvasMemoryEdit.Word)?.word,
                                    memoryWindows = memoryWindows,
                                    onMemoryWindowsChanged = { memoryWindows = it },
                                    onMemoryAddressChanged = { id, address ->
                                        val window = memoryWindows[id] ?: MemoryCanvasWindow()
                                        memoryWindows = memoryWindows + (id to window.copy(firstAddress = address))
                                    },
                                    onMemoryEdit = if (state.documentWritable && state.interactionsEnabled) {
                                        { memoryEdit = it }
                                    } else null,
                                    accessibilityActions = accessibleActions, accessibilityContext = accessibilityContext,
                                    isSimulationRunning = running,
                                    onAccessibilityNotice = state::reportStatus, viewportReset = state.viewportReset,
                                    onSelectArea = state::selectInArea, onSelectComponent = state::select,
                                    onSelectWire = state::selectWire, onMoveSelection = state::moveSelected,
                                    keepConnectionsWhenMoving = state.keepConnectionsWhenMoving,
                                    onViewSubcircuit = { id ->
                                        if (toolContext.isCurrentEditorContext()) state.viewSubcircuitInstance(id)
                                    },
                                    onSplitWire = state::splitWireAt, onMoveWireCorner = state::moveWireCorner,
                                    onDrawEditToolWire = state::drawEditToolWire,
                                    onCommand = state::handleCanvasCommand, canPaste = state.canPaste,
                                    onToolFacing = state::handleToolFacing, projectPlacementTool = state.projectPlacementTool,
                                    onKeyboardPoke = state::beginKeyboardPoke,
                                    onComponentMenu = { item, id -> handleComponentMenu(item, id) },
                                    onRemoveWire = { state.removeWire(it) }, canEditCircuit = state.documentWritable,
                                    onPokePin = { id, bit ->
                                        if (bit != null) state.pokePin(id, bit)
                                        else if (state.canEditInputValue(id)) editSignalInput(id)
                                    },
                                    onDismissKeyboardPoke = state::dismissKeyboardPoke,
                                    pokedKeyboardId = state::pokedKeyboardId,
                                    onTogglePlacementMatrix = state::togglePlacementMatrix,
                                    onOpenPlacementAutoLabel = ::openPlacementAutoLabel,
                                    onToolShortcut = state::handleToolShortcut,
                                    viewportState = state.viewport, onViewportChange = state::updateViewport,
                                    selectedWires = state.selectedWires, fitSelection = state.fitSelection,
                                    childCircuit = state.project::circuit, printerView = state.printerView,
                                    mouseBindings = remember(state.project.extraXml, state.project.libraries, state.project.sourceVersion, state.project.circuits, registry) {
                                        ProjectMouseMappings.read(state.project, registry)
                                    },
                                    onMappedTap = state::tap, onMappedPlacement = state::insertMappedComponent,
                                    onPlacementReleasedOffCanvas = state::releasePlacementOffCanvas,
                                    pendingPaste = state.pendingPaste, onMovePastePreview = state::movePastePreview,
                                    onConfirmPaste = state::confirmPaste,
                                    showZoomControls = false,
                                    additiveSelection = state.additiveSelection,
                                    // The selection bar and the Add chip float over the canvas, so showing them never
                                    // resizes it and the circuit stays where the finger left it.
                                    overlay = {
                                        if (state.additiveSelection) AdditiveSelectionChip(strings(Messages.SelectionAddingChip),
                                            strings(Messages.SelectionAddingStop), state::toggleAdditiveSelection,
                                            Modifier.align(Alignment.TopEnd).padding(8.dp))
                                        // Facing and attributes of the next component, set before placing (C34).
                                        if (state.mode == EditorMode.PLACE && state.pendingPaste == null && state.documentWritable &&
                                            state.interactionsEnabled) PlacementToolStrip(state, { placementAttributes = true },
                                            Modifier.align(Alignment.BottomCenter).padding(horizontal = SelectionBarMargin, vertical = 6.dp))
                                        // Above the bottom bar on phones and at the canvas bottom on wide layouts, where a
                                        // toolbar slot would push tools into its overflow. Properties joins it only when
                                        // neither the docked inspector nor the toolbar offers it.
                                        if (state.showsSelectionBar) EditorSelectionBar(
                                            selectionBarActions(state, strings, onProperties =
                                                if (sidebarShown || toolbarPlacement != EditorToolbarPlacement.HIDDEN) null else ({
                                                    inspectorSheet = true; sheetOpenerTag = "selection-properties"
                                                })),
                                            Modifier.align(Alignment.BottomCenter).padding(horizontal = SelectionBarMargin, vertical = 6.dp),
                                        )
                                    },
                                )
                                if (!compact && signalsVisible) {
                                    HorizontalDivider(color = ChromeColors.Divider)
                                    SignalPanel(state, preferences.signalRadix, signalScroll, editSignalInput)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = ChromeColors.Divider)
                    val shownStatus = state.displayedStatusText
                    val statusText = if (state.simulation.oscillating) strings(Messages.SimulationUnsettled) else strings(shownStatus)
                    val bottomSimulation = simulationPlacement == SimulationControlsPlacement.BOTTOM_BAR
                    // On phones the status line and the simulation bar form one bottom bar: the tick counter, Signals
                    // and the zoom sit in the status line, so the controls below never move as the count grows.
                    Column(Modifier.fillMaxWidth().background(ChromeColors.Bar)
                        .then(if (bottomSimulation) Modifier.testTag("editor-simulation-toolbar") else Modifier)) {
                        Row(Modifier.fillMaxWidth()
                            .padding(start = 10.dp, end = 4.dp)
                            .heightIn(min = if (!preferences.showHeaderBar) ChromeBarHeight else 34.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!preferences.showHeaderBar) ChromeCompactMenu(menus, strings("a11y_open_menu"), strings("menu_back"))
                            // The Add chip already announces the additive mode; its hint is not read again. Behind an open
                            // tool sheet the sheet's own notice announces the tool's result instead (C74).
                            EditorStatusText(statusText, shownStatus.key != Messages.StatusClockStep &&
                                shownStatus.key != Messages.StatusAdditiveTouch && activeTool == null, Modifier.weight(1f),
                                maxLines = statusLines(if (bottomSimulation) 3 else 2))
                            SimulationResetUndoAction(state)
                            // Under the open inspector sheet the Undo is shown in the sheet; its timeout here starts
                            // once the sheet has closed (C7).
                            if (sidebarShown || !inspectorSheet) MultiEditUndoAction(state, multiEditUndo)
                            ImportReportStatusAction(state)
                            if (bottomSimulation) stepsLabel()
                            else if (wide) Text(strings.plural(Messages.ProjectComponentCount, state.document.components.size.toLong()) + " · " +
                                strings.plural(Messages.ProjectWireCount, state.document.wires.size.toLong()),
                                fontSize = 11.sp, color = ChromeColors.Muted, maxLines = 1)
                            // A short window has no room for the warnings banner; its count opens the same dialog (C67).
                            if (compact) SimulationWarningsIndicator(state.simulation.notices) { diagnosticsDialog = true }
                            ChromeButton(signalsAction, size = 32.dp)
                            EditorZoomControls(state, hasSelection, stepButtons = !narrow, expanded = zoomMenu,
                                onExpandedChange = { zoomMenu = it }, onCustom = { zoomDialog = true })
                        }
                        if (bottomSimulation) SimulationControlBar(simulationControls, Modifier.fillMaxWidth(), barTag = null)
                    }
                }
                if (!sidebarShown && inspectorSheet) {
                    val dismissInspector = {
                        // A label typed in the sheet is applied when it closes, not discarded (C155).
                        inspectorSession.commitLabelDraft()
                        inspectorSession.focus.dismissPresentation(InspectorPresentation.SHEET)
                        keyboardSession?.focus?.dismissPresentation(InspectorPresentation.SHEET)
                        inspectorSheet = false
                        returnFocusToSheetOpener()
                    }
                    val inspected = state.document.components.filter { it.id in state.selectedIds }
                    EditorModalSheet(onDismissRequest = dismissInspector,
                        modifier = Modifier.exportEditorTestTags().testTag("editor-inspector-sheet"),
                        containerColor = ChromeColors.Paper, inspector = true,
                        title = when (inspected.size) {
                            0 -> strings(Messages.ActionProperties)
                            1 -> strings("sheet_properties_of", "name" to inspected.single().kind.localizedLabel())
                            else -> strings("sheet_properties_of", "name" to LocalizedText(Messages.InspectorComponentCount,
                                quantity = inspected.size.toLong()))
                        }) {
                        PropertyInspector(state, runStatus, runController::isEventPending, inspectorSession,
                            InspectorPresentation.SHEET, { inspectorSession.applyLabel(state, currentInspectorSession) },
                            keyboardSession, { currentKeyboardSession }, { currentInspectorSession },
                            Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding(),
                            onOpenTool = { id ->
                                // The tool replaces the sheet: a label typed there is applied first, as when it closes (C155).
                                inspectorSession.commitLabelDraft()
                                toolContext.openTool(id).also { if (it) inspectorSheet = false }
                            })
                        MultiEditUndoBanner(state, multiEditUndo)
                    }
                }
                if (compact && signalSheet) {
                    EditorModalSheet(onDismissRequest = { signalSheet = false; returnFocusToSheetOpener() },
                        modifier = Modifier.testTag("editor-signal-sheet").exportEditorTestTags(), containerColor = ChromeColors.Paper,
                        title = strings(Messages.SignalsHeading)) {
                        SignalPanel(state, preferences.signalRadix, signalScroll, editSignalInput, wrap = true)
                    }
                }
                if (librarySheet) {
                    val dismissLibrary = { librarySheet = false; findActions = false; returnFocusToSheetOpener() }
                    EditorModalSheet(onDismissRequest = dismissLibrary,
                        modifier = Modifier.exportEditorTestTags().testTag("editor-library-sheet"), containerColor = ChromeColors.Paper,
                        title = strings(if (findActions) Messages.MenuFindAction else Messages.ActionLibrary)) {
                        key(findActions) {
                            LibraryPanel(state, searchActions, libraryExplorer, Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding(),
                                findActions = findActions, shareScroll = !sidebarShown,
                                componentIconColor = componentIconColor,
                                onExportCircuit = exportCircuit,
                                onManageLibraries = {
                                    runController.pause()
                                    dismissLibrary()
                                    activeLibraries = true
                                }, onChoose = {
                                    runController.pause()
                                    dismissLibrary()
                                })
                        }
                    }
                }
            }
        }
        historyDirection?.let { direction ->
            val undoing = direction == EditorHistoryDirection.UNDO
            val actions = if (undoing) state.undoHistory else state.redoHistory
            val dismiss = { historyDirection = null }
            AppAlertDialog(
                modifier = Modifier.exportEditorTestTags().testTag("editor-history-dialog"),
                onDismissRequest = dismiss,
                title = { Text(strings(if (undoing) "history_undo" else "history_redo")) },
                text = {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        actions.forEachIndexed { index, action ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth().testTag("editor-history-item-${index + 1}"),
                                onClick = { dismiss(); state.travelHistory(direction, action.id) },
                            ) { Text(strings(action.label)) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = dismiss) { Text(strings(Messages.ActionClose)) } },
            )
        }
        if (activeLibraries) ActiveProjectLibrariesDialog(state, assets) { activeLibraries = false }
        if (mergeProject && assets != null) ProjectMergeDialog(state, assets) { mergeProject = false }
        if (zoomDialog) EditorZoomDialog(state.zoomPercent / 100f, { state.zoomToPercent(it * 100f) }) { zoomDialog = false }
        if (state.documentWritable) key(inspectorSession) {
            PropertyInspectorDialog(inspectorSession, assets, onClear = { edit -> inspectorSession.clearValue(state, currentInspectorSession, edit) }) { edit ->
                inspectorSession.applyValue(state, currentInspectorSession, edit)
            }
        }
        signalInput?.let { id -> SignalInputDialog(state, id) { signalInput = null } }
        memoryClearId?.let { id ->
            AppAlertDialog(onDismissRequest = { memoryClearId = null },
                title = { Text(strings(Messages.MemoryClearContents)) },
                text = { Text(strings(Messages.MemoryClearContentsConfirm)) },
                confirmButton = { TextButton(onClick = {
                    memoryClearId = null
                    val component = state.document.components.firstOrNull { it.id == id }
                    val image = component?.let { runCatching { memoryEditorImage(it, state.simulation, registry) }.getOrNull() }
                    if (image != null && !applyMemoryEditorImage(toolContext, id, image.cleared()))
                        state.reportStatus(message("memory_tool_not_applied"))
                }) { Text(strings(Messages.MemoryClearContents)) } },
                dismissButton = { TextButton(onClick = { memoryClearId = null }) { Text(strings(Messages.ActionCancel)) } })
        }
        imageOptimization?.let { pending ->
            AppAlertDialog(onDismissRequest = { imageOptimization = null },
                title = { Text(strings(Messages.ComponentMenuImageOptimize)) },
                text = { Text(strings(Messages.ComponentMenuImageOptimizeConfirm, "width" to pending.resize.width,
                    "height" to pending.resize.height, "targetWidth" to pending.resize.targetWidth, "targetHeight" to pending.resize.targetHeight)) },
                confirmButton = { TextButton(onClick = {
                    imageOptimization = null
                    state.confirmImageOptimization(pending)
                }, modifier = Modifier.testTag("image-optimize-confirm")) { Text(strings(Messages.ComponentMenuImageOptimize)) } },
                dismissButton = { TextButton(onClick = { imageOptimization = null }) { Text(strings(Messages.ActionCancel)) } })
        }
        if (autoLabelDialog && state.canAutoLabelPlacement) {
            PlacementAutoLabelDialog(state.placementAutoLabel.orEmpty(), state::setPlacementAutoLabel) { autoLabelDialog = false }
        }
        if (placementAttributes && state.mode == EditorMode.PLACE) {
            PlacementToolAttributesSheet(state) { placementAttributes = false }
        }
        textToolEdit?.let { edit ->
            TextToolDialog(edit, onApply = { state.applyTextToolEdit(edit, it) }, onDismiss = { textToolEdit = null })
        }
        sequentialHexTarget?.let { target ->
            val output = state.simulation.componentOutputs[target.componentId]?.get(target.portName)
            val value = output?.toULongOrNull()?.toString(16)?.padStart((target.width + 3) / 4, '0') ?: output?.display.orEmpty()
            SequentialHexEditor(
                target = target,
                component = state.document.components.firstOrNull { it.id == target.componentId },
                currentValue = value,
                onDigits = { digits -> target.digitEvent(digits)?.let { sendComponentEvent(target.componentId, it) } },
                onDismiss = { sequentialHexTarget = null },
            )
        }
        when (val edit = memoryEdit) {
            is CanvasMemoryEdit.Word -> CanvasMemoryWordEditor(edit.word, edit.value,
                onEdit = { state.editMemoryWord(accessibilityContext, edit.word.componentId, edit.word.address, it) },
                onDismiss = { memoryEdit = null },
            )
            is CanvasMemoryEdit.Address -> CanvasMemoryAddressEditor(edit.componentId, edit.value,
                onGo = edit.go, onDismiss = { memoryEdit = null },
            )
            null -> Unit
        }
        if (clearHistoryDialog) {
            AppAlertDialog(
                modifier = Modifier.exportEditorTestTags(),
                onDismissRequest = { clearHistoryDialog = false },
                title = { Text(strings(Messages.ActionClearHistory)) },
                text = { Text(strings(Messages.ConfirmClearHistory)) },
                confirmButton = {
                    TextButton(modifier = Modifier.testTag("confirm-clear-history"), onClick = {
                        clearHistoryDialog = false
                        state.clearHistory()
                    }) { Text(strings(Messages.ActionClearHistory)) }
                },
                dismissButton = {
                    TextButton(modifier = Modifier.testTag("cancel-clear-history"), onClick = { clearHistoryDialog = false }) {
                        Text(strings(Messages.ActionCancel))
                    }
                },
            )
        }
        state.pendingMatrixPlacement?.let { placement ->
            MatrixPlacementDialog(placement, state.statusText, state::cancelMatrixPlacement, state.componentRegistry) { columns, rows, dx, dy, label ->
                state.confirmMatrixPlacement(placement, columns, rows, dx, dy, label)
            }
        }
        if (state.canConfirmDebugCopy) {
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = state::cancelDebugCopy,
                title = { Text(strings("subcircuit_debug_title")) },
                text = { Text(strings("subcircuit_debug_confirmation")) },
                confirmButton = { TextButton(onClick = state::confirmDebugCopy) { Text(strings("subcircuit_debug_create")) } },
                dismissButton = { TextButton(onClick = state::cancelDebugCopy) { Text(strings(Messages.ActionCancel)) } })
        }
        if (diagnosticsDialog) SimulationWarningsDialog(state.simulation.notices, state.simulation.noticeSources,
            onShow = { ids -> state.showComponents(ids).also { if (it) diagnosticsDialog = false } }) { diagnosticsDialog = false }
        if (preferencesSheet) {
            EditorModalSheet(onDismissRequest = { preferencesSheet = false }, modifier = Modifier.exportEditorTestTags(), containerColor = ChromeColors.Paper,
                title = strings(Messages.PreferencesTitle)) {
                EditorPreferencesPanel(preferences, onPreferencesChange, onRestorePreferences,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(),
                    onOpenProjectOptions = { preferencesSheet = false; simulationOptions = true },
                    projectTemplates = projectTemplates, templatePicker = assets)
            }
        }
        preferenceNotice?.let { notice ->
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = onDismissPreferenceNotice,
                title = { Text(strings(Messages.PreferencesTitle)) }, text = { Text(strings(notice)) },
                confirmButton = { TextButton(onClick = onDismissPreferenceNotice) { Text(strings(Messages.ActionClose)) } })
        }
        if (simulationOptions) {
            EditorModalSheet(onDismissRequest = { simulationOptions = false }, modifier = Modifier.exportEditorTestTags(), containerColor = ChromeColors.Paper,
                title = strings(Messages.SimulationOptionsTitle)) {
                SimulationOptionsPanel(state.project, onApply = { state.applyProject(it); simulationOptions = false },
                    onApplyMouseMappings = state::applyProject,
                    onDismiss = { simulationOptions = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding(),
                    mouseMappingRegistry = registry)
            }
        }
        if (xmlDialog) {
            XmlDialog(xmlText, onTextChange = { xmlText = it; importLoader.cancel() },
                loading = importLoader.loading, workspaceCopy = workspaceSession != null,
                onDismiss = { importLoader.cancel(); xmlDialog = false }, onImport = {
                    runController.pause()
                    val sourceXml = xmlText
                    importLoader.open(sourceXml, commit = { state.acceptImport(it, message(Messages.ProjectXmlTitle), sourceXml) },
                        onComplete = { accepted -> if (accepted) xmlDialog = false })
                })
        }
        if (legalDialog) LegalNoticesDialog { legalDialog = false }
        circuitNameDialog?.let { id ->
            CircuitNameDialog(state.project, id, onCreate = { state.createCircuit(it) }, onRename = { circuit, name ->
                state.renameCircuit(circuit, name)
            }) { circuitNameDialog = null }
        }
        removeCircuitDialog?.let { id -> CircuitRemoveDialog(state.project, id, onRemove = { state.removeCircuit(it) }) { removeCircuitDialog = null } }
        if (helpDialog) {
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { helpDialog = false }, title = { Text(strings(Messages.EditorHelpTitle)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings(Messages.TouchHelpLibrary))
                    Text(strings(Messages.TouchHelpViewport))
                    Text(strings(Messages.TouchHelpSimulation))
                    Text(strings(Messages.TouchHelpEditing))
                    Text(strings(Messages.EditorHelpFiles))
                } },
                confirmButton = { TextButton(onClick = { helpDialog = false }) { Text(strings(Messages.ActionClose)) } })
        }
        editorTools.firstOrNull { it.id == activeTool }?.let { tool ->
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val sheetModifier = if (keyboardVisible) Modifier.editorDialogBounds() else Modifier
            val swipeToDismiss = tool.id != "appearance"
            EditorModalSheet(onDismissRequest = { activeTool = null }, modifier = sheetModifier.exportEditorTestTags().testTag("editor-tool-sheet"),
                sheetGesturesEnabled = swipeToDismiss, showDragHandle = swipeToDismiss && !keyboardVisible, title = strings(tool.titleText)) {
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 700.dp)) {
                    // Keep the search and keyboard dismissal side by side when the IME
                    // leaves too little room for both a header and the search field. The
                    // rows grow with the font, so the threshold does too (#239, 200%).
                    val compactSearch = tool.id == "accessibility" && keyboardVisible &&
                        maxHeight < 120.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val sheetGuard = LocalEditorSheetDismissGuard.current
                    val inlineNotices = remember(tool.id) { ToolSheetInlineNotices() }
                    val dismiss: @Composable () -> Unit = {
                        TextButton(modifier = Modifier
                            .then(if (compactSearch) Modifier.widthIn(max = maxWidth / 2) else Modifier)
                            .heightIn(min = 48.dp)
                            .testTag(if (keyboardVisible) "editor-tool-hide-keyboard" else "editor-tool-close"), onClick = {
                            if (keyboardVisible) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            } else if (sheetGuard?.allowsDismissal() != false) activeTool = null
                        }) {
                            Text(strings(if (keyboardVisible) Messages.ActionHideKeyboard else Messages.ActionClose),
                                maxLines = if (compactSearch) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = if (compactSearch) 0.dp else 12.dp)) {
                            if (!compactSearch) {
                                Row(Modifier.fillMaxWidth().zIndex(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(strings(tool.titleText), modifier = Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    dismiss()
                                }
                            }
                            val sheetNotice = toolSheetNotice?.takeIf { it.toolId == tool.id }
                            ToolSheetNoticeBanner(sheetNotice, inlineNotices) { toolSheetNotice = null }
                            CompositionLocalProvider(LocalCompactExplorerKeyboard provides compactSearch,
                                LocalToolSheetInlineNotices provides inlineNotices, LocalToolSheetNotice provides sheetNotice) {
                                if (tool.requiresProjectGraph) key(tool.id, toolContext.documentIdentity) {
                                    if (!toolContext.projectExports.available) Text(strings("project_graph_unavailable"))
                                    else tool.content(toolContext)
                                } else tool.content(toolContext)
                            }
                        }
                        if (compactSearch) dismiss()
                    }
                }
            }
        }
        val guided = guidedLibraryOpen
        if (guided != null && directories != null && portableProjects != null) {
            fun isCurrent() = guidedLibraryOpen === guided && state.documentIdentity == guided.identity &&
                state.revision == guided.revision && state.interactionsEnabled
            if (isCurrent()) key(guided) {
                GuidedLibraryOpenDialog(guided, directories, portableProjects.opener, registry, ::isCurrent) {
                    if (guidedLibraryOpen === guided) guidedLibraryOpen = null
                }
            }
        }
        if (state.importReportVisible) state.importReport?.let { report ->
            // Show selects a group's components in its circuit and frames them.
            ImportReportDialog(report, state::dismissImportReport, state.project) { circuit, ids ->
                state.dismissImportReport()
                state.selectCircuit(circuit)
                ids.forEachIndexed { index, id -> state.select(id, additive = index > 0) }
                state.fitSelected()
            }
        }
        state.importErrorText?.let { notice ->
            val message = strings(notice)
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = state::dismissImportError,
                title = { Text(strings("action_failed_title")) },
                text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = state::dismissImportError) { Text(strings(Messages.ActionUnderstood)) } },
                dismissButton = {
                    if (state.jarImportRecoveryAvailable && editorTools.any { it.id == jarCompanionToolId }) {
                        TextButton(onClick = {
                            state.dismissImportError()
                            toolContext.openTool(jarCompanionToolId)
                        }, modifier = Modifier.testTag("open-jar-after-import")) { Text(strings("jar_tool_title")) }
                    }
                })
        }
        liveJar?.let { controller ->
            val properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
            Dialog(onDismissRequest = controller::stop, properties = appDialogProperties(properties)) {
                Surface(Modifier.appDialogViewport(controller::stop, properties).fillMaxSize().testTag("jar-live-editor")) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(strings("jar_tool_live_title"), Modifier.weight(1f))
                            TextButton(onClick = controller::stop, modifier = Modifier.testTag("jar-live-editor-close")) {
                                Text(strings(Messages.ActionClose))
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            EditorApp(initialXml = null, onDocumentChanged = {}, documents = null, extensions = emptyList(),
                                baseRegistry = controller.geometry.registry, sessionControl = null, onProjectReplaced = {},
                                memoryFiles = null, assets = null, initialImportReport = null, initialReadOnly = true,
                                portableProjects = null, directories = null, initialExtensionCatalog = null,
                                onExtensionCatalogChanged = {}, onCommitProject = null, projectTemplates = null,
                                workspaceHeader = null, boardCatalog = null, preferences = preferences, onPreferencesChange = onPreferencesChange,
                                onRestorePreferences = onRestorePreferences, preferenceNotice = null,
                                onDismissPreferenceNotice = {}, jarController = controller)
                        }
                    }
                }
            }
        }
    }
}}

@Composable
private fun PanelHeading(title: String) {
    // A heading, so TalkBack's heading navigation moves between the library's groups and past the inspector title.
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChromeColors.Ink, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().semantics { heading() }
        .background(ChromeColors.Heading).padding(horizontal = 10.dp, vertical = 6.dp))
}

/** One bar for the menus, the document title and undo/redo, in place of the stacked desktop bars. */
@Composable
private fun EditorHeader(
    title: String,
    project: String?,
    saveState: String?,
    busy: Boolean,
    /** The circuit picker the title opens, as Evolution's header circuit button: expanded, then its dismissal. */
    circuitPicker: @Composable (Boolean, () -> Unit) -> Unit,
    menus: List<EditorMenu>,
    compactSaveState: String? = null,
    menuBar: Boolean,
    leading: EditorToolbarAction,
    trailing: List<EditorToolbarAction>,
    singleLine: Boolean = false,
    /** The tick counter and simulation controls, which share with the title what the menus and undo/redo leave. */
    simulation: (@Composable () -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(ChromeColors.Bar)) {
        Row(Modifier.fillMaxWidth().heightIn(min = ChromeBarHeight).testTag("editor-project-toolbar").padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ChromeButton(leading)
            if (menuBar) ChromeMenuBar(menus) else ChromeCompactMenu(menus, strings("a11y_open_menu"), strings("menu_back"))
            // The menus and undo/redo are measured first and keep the edges; the simulation controls take the width
            // they need from the rest, and the title what is left.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // The whole title, padding included, is the circuit picker's 48 dp target; the simulation group leaves
                // it that and, unless a whole count needs it, the chevron's room (HeaderTitleMinWidth). Projects… in the
                // picker returns to the project list.
                Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = ChromeTouchTarget).testTag("editor-header-title")
                    .clickable(role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = strings("a11y_switch_circuit")) { picker = true }
                    .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Line heights follow each text's own size: the inherited body line height
                    // doubled the header at large font scales and left a short window no canvas.
                    val titleText = @Composable { modifier: Modifier -> HeaderTitleText(title, modifier) }
                    // The project name and its save state stay separate texts for assistive technology.
                    val details = @Composable { scope: RowScope ->
                        if (!project.isNullOrBlank()) Text(project, fontSize = 11.sp, lineHeight = 1.25.em, color = ChromeColors.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = with(scope) { Modifier.weight(1f, fill = false) })
                        if (!saveState.isNullOrBlank()) Text((if (project.isNullOrBlank()) "" else " · ") + saveState,
                            fontSize = 11.sp, lineHeight = 1.25.em, color = ChromeColors.Muted, maxLines = 1)
                    }
                    if (singleLine) {
                        // A short landscape window keeps the header to one row of controls.
                        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            titleText(Modifier.weight(1f, fill = false))
                            if (!project.isNullOrBlank() || !saveState.isNullOrBlank()) Row(Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically) { details(this) }
                        }
                    } else Column(Modifier.weight(1f, fill = false)) {
                        titleText(Modifier)
                        if (!project.isNullOrBlank() || !saveState.isNullOrBlank()) HeaderDetails(project, saveState, compactSaveState)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                circuitPicker(picker) { picker = false }
                }
                simulation?.invoke()
            }
            trailing.forEach { action ->
                if (action.separator) Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(ChromeColors.Divider))
                else ChromeButton(action)
            }
        }
        HorizontalDivider(color = ChromeColors.Divider)
    }
}

/**
 * The project name and its save state, on one line when both fit. Otherwise the state moves to a second line, so a
 * long state ("Not saved to Adder.circ") never squeezes the name out. A state too wide even for its own line shows as
 * [compactSaveState] ("Not saved to file"), still read in full by assistive technology. They stay separate texts.
 */
@Composable
private fun HeaderDetails(project: String?, saveState: String?, compactSaveState: String?) = BoxWithConstraints {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TicksLabelStyle)
    val available = constraints.maxWidth
    val compact = saveState != null && compactSaveState != null && constraints.hasBoundedWidth &&
        remember(saveState, style, measurer) { measurer.measure(saveState, style, maxLines = 1).size.width } > available
    val text = @Composable { value: String, modifier: Modifier ->
        Text(value, style = TicksLabelStyle, color = ChromeColors.Muted, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
    val both = !project.isNullOrBlank() && !saveState.isNullOrBlank()
    Layout(modifier = Modifier.testTag("editor-header-details"), content = {
        if (!project.isNullOrBlank()) text(project, Modifier.testTag("editor-header-project"))
        if (both) text(" · ", Modifier.clearAndSetSemantics {})
        if (!saveState.isNullOrBlank()) {
            if (compact) text(compactSaveState!!, Modifier.testTag("editor-header-save-state").semantics { contentDescription = saveState })
            else text(saveState, Modifier.testTag("editor-header-save-state"))
        }
    }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val oneLine = !both || placeables.sumOf { it.width } <= constraints.maxWidth
        if (oneLine) {
            layout(placeables.sumOf { it.width }.coerceAtLeast(constraints.minWidth), placeables.maxOf { it.height }) {
                var x = 0
                placeables.forEach { it.placeRelative(x, 0); x += it.width }
            }
        } else {
            val (name, _, state) = placeables
            layout(maxOf(name.width, state.width).coerceAtLeast(constraints.minWidth), name.height + state.height) {
                name.placeRelative(0, 0)
                state.placeRelative(0, name.height)
            }
        }
    }
}

private val TicksLabelStyle = TextStyle(fontSize = 11.sp, lineHeight = 1.25.em)
private val TicksLabelPadding = 6.dp
/** The count the header keeps room for beside its simulation bar: seven digits. */
private const val TicksLabelReserveCount = 8_888_888L

/** The width of the tick counter showing [text], in the current font. */
@Composable
private fun ticksLabelWidth(text: String): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.merge(TicksLabelStyle)
    return remember(text, measurer, density, style) {
        with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() } + TicksLabelPadding * 2
    }
}

/** The tick counter of the toolbar and the phone bottom bar, which leave it the width it needs. */
@Composable
private fun TicksText(text: String) {
    Text(text, fontSize = TicksLabelStyle.fontSize, lineHeight = TicksLabelStyle.lineHeight, color = ChromeColors.Muted,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = TicksLabelPadding).testTag("editor-simulation-ticks"))
}

/**
 * The header's tick counter, [text]. Where its parent leaves less room than that needs, it shows the [count] alone, which
 * assistive technology still reads as [text]; only a count wider than that room is ellipsized.
 */
@Composable
private fun TicksCounter(text: String, count: Long) {
    val whole = ticksLabelWidth(text)
    BoxWithConstraints {
        val compact = maxWidth < whole
        Text(if (compact) count.toString() else text, fontSize = TicksLabelStyle.fontSize, lineHeight = TicksLabelStyle.lineHeight,
            color = ChromeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TicksLabelPadding).testTag("editor-simulation-ticks")
                .then(if (compact) Modifier.semantics { contentDescription = text } else Modifier))
    }
}

/**
 * The header's simulation group: the tick counter, then the simulation bar, in a width that the menus and undo/redo
 * fix. The bar is measured first: never narrower than Run, Tick, Reset and its overflow need at 48 dp
 * ([SimulationControlBarMinWidth]), and above that at most [maxBarWidth] and never into the [counterReserve] the
 * counter needs for seven digits or the [titleReserve] the title keeps. Its width therefore depends on the window and
 * font only, and a growing count never moves a control into the overflow. The counter takes what the bar and the
 * title's 48 dp target leave, shortened to the count alone where that is less than it needs. One tagged node holds both, as
 * in the toolbar and the phone bottom bar.
 */
@Composable
private fun HeaderSimulationControls(
    counter: @Composable () -> Unit,
    controls: SimulationControls,
    maxBarWidth: Dp,
    counterReserve: Dp,
    titleReserve: Dp = HeaderTitleMinWidth,
) {
    Layout(
        content = {
            counter()
            SimulationControlBar(controls, barTag = null)
        },
        modifier = Modifier.testTag("editor-simulation-toolbar"),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = loose.maxWidth
        val title = titleReserve.roundToPx()
        val preferred = minOf(maxBarWidth.roundToPx(), width - counterReserve.roundToPx() - title)
        val barMax = preferred.coerceAtLeast(simulationControlBarMinWidthPx(this)).coerceAtMost(width)
        val bar = measurables[1].measure(loose.copy(maxWidth = barMax))
        // The bar never grows into the picker chevron's part of the reserve, but a whole count may: at the bar's floor
        // the title keeps its 48 dp target and drops the chevron (HeaderTitleText).
        val target = minOf(title, ChromeTouchTarget.roundToPx())
        val label = measurables[0].measure(loose.copy(maxWidth = (width - bar.width - target).coerceAtLeast(0)))
        val height = maxOf(bar.height, label.height)
        layout(label.width + bar.width, height) {
            label.placeRelative(0, (height - label.height) / 2)
            bar.placeRelative(label.width, (height - bar.height) / 2)
        }
    }
}

private val StatusFontSize = 11.sp
private const val StatusLineHeight = 1.25f

/**
 * How many status lines, up to [limit], fit the 48 dp the row's buttons already take, so a wrapped hint never makes
 * the bars taller and the circuit smaller: three at the default font, one at 200 %.
 */
@Composable
private fun statusLines(limit: Int): Int {
    val line = with(LocalDensity.current) { (StatusFontSize * StatusLineHeight).toDp() }
    return (ChromeTouchTarget / line).toInt().coerceIn(1, limit)
}

/**
 * The status line. [maxLines] is 3 on the phone bottom bar, where it shares a row with the counter, Signals and the
 * zoom ([statusLines]).
 */
@Composable
private fun EditorStatusText(text: String, announce: Boolean, modifier: Modifier = Modifier, maxLines: Int = 1) {
    // A notice cut to its lines opens to its whole text on a tap and closes on the next, or when another arrives.
    var expanded by remember(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    val strings = rememberLocalizer()
    val openable = cut || expanded
    Text(text, fontSize = StatusFontSize, lineHeight = StatusLineHeight.em, color = ChromeColors.Ink,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
        modifier = modifier
            .then(if (expanded) Modifier.heightIn(max = StatusExpandedMaxHeight).verticalScroll(rememberScrollState()) else Modifier)
            .then(if (openable) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = strings(if (expanded) "status_line_collapse" else "status_line_expand")) { expanded = !expanded }
                .heightIn(min = ChromeTouchTarget).wrapContentHeight() else Modifier)
            .testTag("editor-status").semantics {
                if (announce) liveRegion = LiveRegionMode.Polite
                if (openable) stateDescription = strings(if (expanded) "status_line_expanded" else "status_line_collapsed")
            })
}

/** An opened status notice grows the bar up to this height, then scrolls. */
private val StatusExpandedMaxHeight = 180.dp

internal fun formatHertz(value: Double): String =
    if (value == kotlin.math.floor(value) && value < 1e9) value.toLong().toString() else value.toString()

@Composable
private fun PropertyInspector(
    state: EditorState,
    simulationStatus: SimulationRunStatus,
    isEventPending: (dev.logisim.mobile.core.ComponentEvent) -> Boolean,
    session: PropertyInspectorSession,
    presentation: InspectorPresentation,
    onLabel: () -> Unit,
    keyboardSession: KeyboardInspectorSession?,
    currentKeyboardSession: () -> KeyboardInspectorSession?,
    currentPropertySession: () -> PropertyInspectorSession,
    modifier: Modifier = Modifier,
    /** Opens an editor tool by id (a large value's dedicated editor); false when it is unavailable. */
    onOpenTool: (String) -> Boolean = { false },
) {
    if (!state.documentWritable) { Text(rememberLocalizer()("document_read_only_preserved"), modifier = modifier.padding(12.dp)); return }
    val circuit = state.document.id
    val path = state.activeInstancePath.toList()
    val identity = state.documentIdentity
    val debugCopyIdentity = state.debugCopyIdentity
    val send: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean = { id, event ->
        state.debugCopyIdentity === debugCopyIdentity && state.sendComponentEvent(id, event, path, circuit, identity)
    }
    BoxWithConstraints(modifier.background(ChromeColors.Surface)) {
    // Docked beside the library, the I/O controls take at most a quarter of the inspector (they scroll within it),
    // so a Matrix Keypad or a Joystick leaves room for the label and the attribute table (C15).
    val ioMax = if (presentation == InspectorPresentation.INLINE && constraints.hasBoundedHeight) {
        minOf(260.dp, maxOf(96.dp, maxHeight / 4))
    } else 260.dp
    Column {
        state.selected?.takeIf { dev.logisim.mobile.core.IoLibrary.isInteractive(it.kind) &&
            (state.activeInstancePath.isNotEmpty() || state.activeCircuitName == state.project.mainCircuitName) }?.let { component ->
            key(identity, circuit, path, component.id) {
                IoInputPanel(component, state.simulation.componentStates[component.id], { send(component.id, it) },
                    simulationStatus, isEventPending, presentation, keyboardSession, currentKeyboardSession,
                    { currentPropertySession().focus },
                    Modifier.fillMaxWidth().heightIn(max = ioMax).testTag("inspector-io-panel"))
            }
        }
        state.selected?.let { component ->
            ExtraIoControls(component, state.simulation.componentStates[component.id], { send(component.id, it) },
                state::updateSelectedAttributes)
        }
        TypedPropertyInspector(
            registry = state.componentRegistry,
            components = session.context.components,
            session = session,
            presentation = presentation,
            onLabel = onLabel,
            onLabelFocusChanged = { focused ->
                session.labelFocusChanged(state, currentPropertySession(), presentation, focused, keyboardSession?.focus)
            },
            onOpenField = { field ->
                if (session.isCurrent(state, currentPropertySession())) session.open(field, keyboardSession?.focus)
            },
            onChooseOption = { field, value -> session.applyOption(state, currentPropertySession(), field, value) },
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            onOpenEditor = { toolId -> session.isCurrent(state, currentPropertySession()) && onOpenTool(toolId) },
            onClearLabels = { session.clearLabels(state, currentPropertySession()) },
        )
    }
    }
}

/**
 * Evolution's Pin.EditDecimal and Pin.EditFloat for a multi-bit input: the current value in the pin's radix, selected
 * and focused so typing replaces it, the range or the error under the field, and Done applies. It drives the
 * simulation only; the document does not change.
 */
@Composable
private fun SignalInputDialog(state: EditorState, id: String, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val component = state.document.components.firstOrNull { it.id == id }
    if (component == null) {
        LaunchedEffect(id) { onDismiss() }
        return
    }
    val radix = state.componentRegistry.attributes(component).string("radix") ?: "2"
    val tristate = PinPoke.behavior(component.attributes) == "tristate"
    val codec = remember(component.width, radix, tristate) { InputValueCodec(component.width, radix, tristate) }
    val focus = remember(id) { FocusRequester() }
    var field by remember(id, codec) {
        val current = state.simulation.vectorValues[id] ?: LogicVector.low(component.width)
        val text = codec.format(current)
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val parsed = codec.parse(field.text)
    val value = (parsed as? InputValueParse.Value)?.vector
    fun apply() {
        if (value == null) return
        state.setInputValue(id, value)
        onDismiss()
    }
    val label = component.label.ifBlank { strings(component.kind.localizedLabel()) }
    val keyboard = codec.keyboard
    AppAlertDialog(modifier = Modifier.exportEditorTestTags().testTag("input-value-dialog"), onDismissRequest = onDismiss,
        title = { Text(strings("input_value_dialog_title", "label" to label, "radix" to strings(codec.radixName), "width" to component.width)) },
        text = {
            OutlinedTextField(field, { field = it }, label = { Text(strings("input_value_field")) }, singleLine = true,
                isError = parsed is InputValueParse.Invalid,
                supportingText = { Text(strings((parsed as? InputValueParse.Invalid)?.error ?: codec.hint)) },
                // Signed and float values need '-', hexadecimal its letters and a tri-state pin x: a text keyboard.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (keyboard == InputValueKeyboard.NUMBER) KeyboardType.Number else KeyboardType.Ascii,
                    capitalization = if (keyboard == InputValueKeyboard.HEXADECIMAL) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                    autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("input-value-field").onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) apply()
                        true
                    } else false
                })
            LaunchedEffect(id) { focus.requestFocus() }
        },
        confirmButton = { TextButton(enabled = value != null, onClick = ::apply, modifier = Modifier.testTag("input-value-apply")) {
            Text(strings(Messages.ActionApply))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } })
}

@Composable
private fun SmallAction(label: String, description: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
        Text(label, color = if (enabled) ChromeColors.Ink else ChromeColors.Ink.copy(alpha = 0.38f), fontSize = 12.sp)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun XmlDialog(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onImport: () -> Unit, loading: Boolean = false,
                      workspaceCopy: Boolean = false) {
    val strings = rememberLocalizer()
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    // The export the dialog opened with: importing it unchanged would only duplicate the project, so Import waits
    // for an edit or a paste and Copy leads until then.
    val original = remember { text }
    val changed = text != original
    // Paste replaces the whole text; one step back restores what it replaced.
    var beforePaste by remember { mutableStateOf<String?>(null) }
    var pasteEmpty by remember { mutableStateOf(false) }
    // Beyond the platform's narrow default width, so Import XML and Close share one row; on a
    // 568 x 320 dp window at 200% two rows left the scrolling body 12 dp (#239).
    AppAlertDialog(modifier = Modifier.padding(horizontal = 16.dp).editorDialogBounds().exportEditorTestTags(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss, title = { Text(strings(Messages.ProjectXmlTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("editor-xml-body").verticalScroll(rememberScrollState())) {
                Text(strings(Messages.ProjectXmlHelp),
                    style = MaterialTheme.typography.bodySmall)
                if (editing && text.length <= 16_384) {
                    OutlinedTextField(value = text, onValueChange = { beforePaste = null; onTextChange(it) }, label = { Text(strings(Messages.ProjectXmlLabel)) },
                        minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("editor-xml-field"), textStyle = MaterialTheme.typography.bodySmall)
                } else {
                    Text(strings("xml_preview_size", "count" to text.length), style = MaterialTheme.typography.bodySmall)
                    Text(text.take(1_200), maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (text.length <= 16_384) TextButton(onClick = { editing = true }, modifier = Modifier.testTag("editor-xml-edit")) { Text(strings("xml_edit")) }
                    else Text(strings("xml_large_help"), style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val copy: () -> Unit = { clipboard.setText(AnnotatedString(text)) }
                    if (changed) OutlinedButton(onClick = copy, modifier = Modifier.testTag("editor-xml-copy")) { Text(strings(Messages.ActionCopy)) }
                    else Button(onClick = copy, modifier = Modifier.testTag("editor-xml-copy")) { Text(strings(Messages.ActionCopy)) }
                    OutlinedButton(modifier = Modifier.testTag("editor-xml-paste"), onClick = {
                        val pasted = clipboard.getText()?.text
                        pasteEmpty = pasted.isNullOrEmpty()
                        if (!pasted.isNullOrEmpty()) {
                            beforePaste = text
                            onTextChange(pasted)
                            editing = false
                        }
                    }) { Text(strings(Messages.ActionPaste)) }
                    beforePaste?.let { previous ->
                        TextButton(modifier = Modifier.heightIn(min = 48.dp).testTag("editor-xml-undo-paste"), onClick = {
                            beforePaste = null
                            onTextChange(previous)
                        }) { Text(strings("xml_undo_paste")) }
                    }
                }
                if (pasteEmpty) Text(strings("xml_paste_empty"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                (if (!changed) "xml_import_unchanged" else if (workspaceCopy) "xml_import_copy_hint" else null)?.let { hint ->
                    Text(strings(hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("editor-xml-import-hint"))
                }
            }
        },
        confirmButton = {
            val label: @Composable () -> Unit = { Text(strings(if (loading) "editor_loading_project" else Messages.ActionImportXml)) }
            if (changed) Button(onClick = onImport, enabled = !loading, modifier = Modifier.testTag("editor-xml-import")) { label() }
            else TextButton(onClick = onImport, enabled = false, modifier = Modifier.testTag("editor-xml-import")) { label() }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

internal fun ComponentKind.supportsRotation(): Boolean = this !in setOf(
    ComponentKind.D_FLIP_FLOP, ComponentKind.REGISTER, ComponentKind.COUNTER,
    ComponentKind.ROM, ComponentKind.RAM, ComponentKind.ADDER, ComponentKind.SUBTRACTOR,
    ComponentKind.HEX_DISPLAY, ComponentKind.SEVEN_SEGMENT_DISPLAY, ComponentKind.TEXT
)

private fun EditorSignalRadix.xmlValue(): String = when (this) {
    EditorSignalRadix.BINARY -> "2"
    EditorSignalRadix.HEXADECIMAL -> "16"
    EditorSignalRadix.UNSIGNED_DECIMAL -> "10unsigned"
    EditorSignalRadix.SIGNED_DECIMAL -> "10signed"
}

/** Whether the editor has room for the side explorer; [keyboard] is the software keyboard's share of [height]'s inset. */
internal fun editorLayoutIsWide(width: Dp, height: Dp, keyboard: Dp): Boolean = width >= 760.dp && height + keyboard >= 600.dp

/** The part of the bottom safe drawing inset that only the software keyboard adds. */
@Composable
private fun keyboardInsetHeight(): Dp {
    val density = LocalDensity.current
    val withKeyboard = WindowInsets.safeDrawing.getBottom(density)
    val withoutKeyboard = WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(density)
    return with(density) { (withKeyboard - withoutKeyboard).coerceAtLeast(0).toDp() }
}

/** What the docked sidebar keeps above the inspector: its tabs, the filter, Manage libraries, two categories, the heading. */
internal val DockedLibraryMinHeight = 310.dp

/**
 * The most the docked inspector takes of a sidebar [height]: up to 60%, at least the 300 dp it had, so a large I/O
 * panel leaves room for the attribute table (C15). Without [ioControls] it also leaves the library
 * [DockedLibraryMinHeight], never going below 240 dp.
 */
internal fun dockedInspectorMaxHeight(height: Dp, ioControls: Boolean): Dp {
    val shared = maxOf(minOf(300.dp, height * 0.75f), height * 0.6f)
    return if (ioControls) shared else minOf(shared, maxOf(240.dp, height - DockedLibraryMinHeight))
}

/** Whether the docked inspector shows I/O controls above the attribute table for a component of this kind. */
internal fun ComponentKind?.hasDockedIoControls(): Boolean =
    this != null && (dev.logisim.mobile.core.IoLibrary.isInteractive(this) || this in dev.logisim.mobile.core.ExtraIoKinds.all)
