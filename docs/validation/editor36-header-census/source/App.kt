package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.LogicValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import dev.logisim.mobile.core.SimulationProjectOptions

private val Ink = Color(0xFF181818)
private val Accent = Color(0xFF285580)
private val Paper = Color(0xFFEFEFEF)
private val Line = Color(0xFFC2C2C2)
private val Selected = Color(0xFFDCE7F2)

internal fun registryComponentGroups(registry: ComponentRegistry): List<Pair<String, List<ComponentKind>>> =
    registry.definitions.groupBy { it.library }.map { (library, definitions) ->
        val title = when (library) {
            "#Wiring" -> Messages.LibraryWiring
            "#Gates" -> Messages.LibraryGates
            "#Arithmetic" -> Messages.LibraryArithmetic
            "#Memory" -> Messages.LibraryMemory
            "#I/O" -> Messages.LibraryIo
            "#Base" -> Messages.LibraryAnnotations
            else -> library.removePrefix("#")
        }
        title to definitions.map { it.kind }
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
    memoryFiles: MemoryEditorFileActions? = null,
    assets: DocumentAssetActions? = null,
    initialImportReport: EditorImportReport? = null,
    initialReadOnly: Boolean = false,
    portableProjects: PortableProjectWorkspaceAccess? = null,
    directories: DirectoryAssetActions? = null,
) {
    val preferenceRepository = remember(preferencesStorage) { preferencesStorage?.let(::EditorPreferencesRepository) }
    val loaded = remember(preferenceRepository) { preferenceRepository?.load() ?: EditorPreferencesLoad() }
    var preferences by remember(preferenceRepository) { mutableStateOf(loaded.preferences) }
    var preferenceNotice by remember(loaded) { mutableStateOf(loaded.notices.firstOrNull()) }
    val effectiveLanguage = when (preferences.locale) {
        EditorLocale.SYSTEM -> language
        EditorLocale.PORTUGUESE -> AppLanguage.PORTUGUESE
        EditorLocale.ENGLISH -> AppLanguage.ENGLISH
    }
    SideEffect { onLanguageChanged(effectiveLanguage) }
    AppLocaleProvider(effectiveLanguage) {
        EditorApp(initialXml, onDocumentChanged, documents, editorTools, registry, sessionControl, onProjectReplaced, memoryFiles, assets, initialImportReport, initialReadOnly, portableProjects, directories,
            preferences = preferences, onPreferencesChange = { next ->
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
    registry: ComponentRegistry,
    sessionControl: EditorSessionControl?,
    onProjectReplaced: (EditorProjectReplacement) -> Unit,
    memoryFiles: MemoryEditorFileActions?,
    assets: DocumentAssetActions?,
    initialImportReport: EditorImportReport?,
    initialReadOnly: Boolean,
    portableProjects: PortableProjectWorkspaceAccess?,
    directories: DirectoryAssetActions?,
    preferences: EditorPreferences,
    onPreferencesChange: (EditorPreferences) -> Unit,
    onRestorePreferences: () -> Unit,
    preferenceNotice: LocalizedText?,
    onDismissPreferenceNotice: () -> Unit,
) {
    val strings = rememberLocalizer()
    var preparedState by remember(registry) { mutableStateOf<EditorState?>(null) }
    LaunchedEffect(registry) {
        preparedState = prepareEditorState(initialXml, registry, preferences.defaultTickFrequencyHz, initialImportReport, initialReadOnly = initialReadOnly)
    }
    val state = preparedState
    if (state == null) {
        MaterialTheme(colorScheme = lightColorScheme()) {
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
    SideEffect { state.defaultTickFrequencyHz = preferences.defaultTickFrequencyHz }
    val scope = rememberCoroutineScope()
    val currentLocalizer by rememberUpdatedState(strings)
    val effectiveMemoryFiles = remember(memoryFiles, assets, scope) {
        memoryFiles ?: assets?.let { NativeMemoryImageActions(it, scope, onNotice = state::reportStatus,
            onFailure = state::reportError, resolveNotice = { notice -> currentLocalizer(notice) }) }
    }
    var xmlDialog by remember { mutableStateOf(false) }
    var xmlText by remember { mutableStateOf("") }
    var librarySheet by remember { mutableStateOf(false) }
    var inspectorSheet by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf(false) }
    var fileDialog by remember { mutableStateOf(false) }
    var toolMenu by remember { mutableStateOf(false) }
    var signalSheet by remember { mutableStateOf(false) }
    var diagnosticsDialog by remember { mutableStateOf(false) }
    var preferencesSheet by remember { mutableStateOf(false) }
    var simulationOptions by remember { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf<String?>(null) }
    val latestPreferences by rememberUpdatedState(preferences)
    val latestPreferencesChange by rememberUpdatedState(onPreferencesChange)
    val latestPreferencesRestore by rememberUpdatedState(onRestorePreferences)
    val preferencesTool = remember {
        EditorTool("preferences", "Preferências", titleText = message("preferences_title")) {
            EditorPreferencesPanel(latestPreferences, { latestPreferencesChange(it) }, { latestPreferencesRestore() },
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(),
                onOpenProjectOptions = { activeTool = null; simulationOptions = true })
        }
    }
    val additionalTools = rememberAdditionalEditorTools(registry, assets, documents,
        onNotice = state::reportStatus, onFailure = state::reportError,
        highContrastSignals = preferences.highContrastSignals, signalRadix = preferences.signalRadix.xmlValue(),
        gateShape = preferences.gateShape, portableProjects = portableProjects, directories = directories)
    val editorTools = rememberBuiltInEditorTools(registry, documents, extensions,
        additionalDefaults = additionalTools, preferencesTool = preferencesTool,
        memoryFiles = effectiveMemoryFiles, onNotice = state::reportStatus, onFailure = state::reportError, assets = assets)
    LaunchedEffect(state.project, state.simulation, editorTools) {
        editorTools.forEach { it.onSimulation?.invoke(state.project, state.rootSimulation) }
    }
    val currentTools by rememberUpdatedState(editorTools)
    val runController = remember(state, scope) {
        SimulationRunController(scope, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step, registry).state },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            onFailure = { state.reportError(it.localizedDescription(message(Messages.CoreSimulationFailed))) },
            advanceWithObservations = { snapshot, step ->
                val job = currentCoroutineContext()[Job]
                advanceSimulation(snapshot, step, registry,
                    autoPropagating = SimulationProjectOptions.read(snapshot.project).autoPropagating,
                    cancellationCheck = { job?.ensureActive() })
            },
            publishObservations = { snapshot, observations -> observations.forEach { observation ->
                currentTools.forEach { it.onSimulation?.invoke(snapshot.project, observation.state) }
            } })
    }
    DisposableEffect(runController) { onDispose { runController.close() } }
    val runStatus by runController.status.collectAsState()
    val running = runStatus.phase == SimulationRunPhase.RUNNING
    val ticksPerSecond = runStatus.frequencyHz
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val projectReplaced by rememberUpdatedState(onProjectReplaced)
    DisposableEffect(state, sessionControl, runController, lifecycle) {
        state.onWorkspaceProjectReplaced = { projectReplaced(it) }
        state.onSimulationInvalidated = runController::invalidate
        state.onSimulationRequested = runController::request
        sessionControl?.attach(pause = runController::pause,
            setActive = { requested -> runController.setActive(requested && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) },
            exportView = state::exportViewState, importView = state::importViewState, identity = { state.documentIdentity }, exportXml = state::exportXml,
            documentWritable = { state.documentWritable }, exportImportReport = { state.importReport },
            setInteractionEnabled = state::setInteractionEnabled)
        runController.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sessionControl?.activationAllowed != false)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runController.setActive(sessionControl?.activationAllowed != false)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> runController.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.onSimulationInvalidated = {}
            state.onSimulationRequested = { _, _ -> SimulationRequestResult.REJECTED }
            state.onWorkspaceProjectReplaced = {}
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
    LaunchedEffect(state.activeCircuitName, state.activeInstancePath) { runController.pauseContinuousExecution() }
    val sendComponentEvent: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean =
        remember(state, state.document.id, state.activeInstancePath, state.documentIdentity) {
            val circuit = state.document.id
            val path = state.activeInstancePath.toList()
            val identity = state.documentIdentity
            val sender: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean =
                { id, event -> state.sendComponentEvent(id, event, path, circuit, identity) }
            sender
        }
    val importLoader = remember(state, scope, registry) {
        EditorImportLoader(scope, registry, { state.documentIdentity to state.revision }, { state.interactionsEnabled },
            onFailure = { state.reportError(it.localizedDescription(message("editor_import_failed"))) },
            onDiscarded = { state.reportStatus(message("editor_import_discarded")) })
    }
    DisposableEffect(importLoader) { onDispose { importLoader.close() } }
    val documentSession = remember(state, documents, importLoader) {
        documents?.let { actions -> EditorDocuments(state, actions) { content, current, completed ->
            runController.pause()
            val origin = LocalizedText.literal(content.reference.name)
            if (!importLoader.open(content.text, isRequestCurrent = current,
                    commit = { result -> actions.commitOpen(content.reference) { state.acceptImport(result, origin) } },
                    onComplete = completed)) completed(false)
        } }
    }
    DisposableEffect(documentSession) {
        documentSession?.attach()
        onDispose { documentSession?.detach() }
    }
    val exampleLoader = remember(state, scope, registry) {
        EditorExampleLoader(scope, registry, { state.documentIdentity to state.revision }, state::installExample,
            { state.reportError(it.localizedDescription(message("error_example_load"))) })
    }
    DisposableEffect(exampleLoader) { onDispose { exampleLoader.close() } }
    val accessibilityContext = state.accessibilityContext()
    val toolIdentity = state.documentIdentity
    val toolContext = EditorToolContext(state.project, state.document, state.simulation,
        state.document.components.filter { it.id in state.selectedIds },
        { if (state.documentIdentity == toolIdentity) state.applyProject(it) },
        { if (state.documentIdentity == toolIdentity) state.applyDocument(it) }, state::selectCircuit, { state.selectComponent(it) }, state::reportStatus,
        projectSimulation = state.rootSimulation, documentIdentity = state.documentIdentity, selectedWires = state.selectedWires,
        activeInstancePath = accessibilityContext.instancePath,
        isCurrentEditorContext = { state.accessibilityContext() == accessibilityContext },
        projectExports = ProjectGraphExportPolicy(state.projectAccess) {
            scope.isActive && state.documentIdentity == toolIdentity && state.projectAccess.graphAvailable
        },
        sendComponentEvent = sendComponentEvent,
        injectEvents = { events -> if (state.documentIdentity == toolIdentity) state.injectEvents(events) else false },
        tickSimulation = { if (state.documentIdentity == toolIdentity) state.tick() },
        isForeground = runStatus.phase != SimulationRunPhase.INACTIVE && runStatus.phase != SimulationRunPhase.CLOSED,
        isSimulationRunning = running,
        openExample = { example ->
            if (state.interactionsEnabled) { runController.pause(); exampleLoader.open(example) } else false
        },
        openTool = { id ->
            val tool = editorTools.firstOrNull { it.id == id }
            if (tool != null && (!tool.requiresProjectGraph || state.projectAccess.graphAvailable)) {
                runController.pause(); activeTool = id; true
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
        showNotice = state::reportStatus)
    val accessibleActions = editorCircuitAccessibilityActions(toolContext)
    val buzzerTones = remember(state.rootSimulation) {
        buildMap<String, dev.logisim.mobile.core.BuzzerTone> {
            state.rootSimulation.componentStates.forEach { (id, value) -> if (value is dev.logisim.mobile.core.BuzzerTone) put(id, value) }
            state.rootSimulation.circuitStates.forEach { (path, child) -> child.componentStates.forEach { (id, value) ->
                if (value is dev.logisim.mobile.core.BuzzerTone) put("$path/$id", value)
            } }
        }
    }
    ExtraIoAudioEffects(buzzerTones, enabled = runStatus.phase != SimulationRunPhase.INACTIVE && runStatus.phase != SimulationRunPhase.CLOSED,
        onFailure = { state.reportError(it) })
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Accent, secondary = Accent, surface = Color.White,
        background = Paper, onSurface = Ink, onBackground = Ink
    )) {
        editorTools.forEach { tool -> key(tool.id) {
            if (!tool.requiresProjectGraph || toolContext.projectExports.available) tool.runtime(toolContext)
        } }
        Surface(Modifier.fillMaxSize(), color = Paper) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= 760.dp && maxHeight >= 600.dp
                val compact = maxHeight < 420.dp
                val minimal = maxHeight < 240.dp
                Column(Modifier.fillMaxSize()) {
                    if (!minimal) {
                    EditorToolbar(actions = buildList {
                        add(EditorToolbarAction("undo", "↶", strings(Messages.ActionUndo), state.canUndo, onClick = state::undo))
                        add(EditorToolbarAction("redo", "↷", strings(Messages.ActionRedo), state.canRedo, onClick = state::redo))
                        add(EditorToolbarAction("xml", ".circ", strings(Messages.A11yImportExportProject)) {
                            runController.pause(); xmlText = state.exportXml(); xmlDialog = true
                        })
                        if (documentSession != null) add(EditorToolbarAction("files", strings(Messages.ActionFiles), strings(Messages.A11yProjectFiles)) {
                            runController.pause(); fileDialog = true
                        })
                        if (editorTools.isNotEmpty()) add(EditorToolbarAction("tools", strings(Messages.ActionTools), strings(Messages.A11yProjectTools)) {
                            runController.pause(); toolMenu = true
                        })
                        add(EditorToolbarAction("preferences", strings(Messages.PreferencesTitle)) { runController.pause(); preferencesSheet = true })
                        if (state.importReport != null) add(EditorToolbarAction("import-report", strings("import_report_title"), onClick = state::showImportReport))
                        add(EditorToolbarAction("help", "?", strings(Messages.A11yOpenHelp)) { helpDialog = true })
                    }, tag = "editor-project-toolbar", title = strings(Messages.ProjectToolsTitle), leading = {
                        Text("${strings(Messages.AppName)}  ${state.document.name}", fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                    HorizontalDivider(color = Line)
                    }
                    if (!state.documentWritable) Text(strings("document_read_only_preserved"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(8.dp))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (wide) {
                            Column(Modifier.width(250.dp).fillMaxHeight().border(1.dp, Line)) {
                                LibraryPanel(state, Modifier.weight(1f).fillMaxWidth(), onOpenExample = { example -> runController.pause(); exampleLoader.open(example) }, onChoose = { runController.pause() })
                                PropertyInspector(state, runStatus, runController::isEventPending, Modifier.fillMaxWidth().heightIn(max = 300.dp))
                            }
                        }
                        key("editor-workspace") {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            EditorToolbar(actions = buildList {
                                if (!wide) add(EditorToolbarAction("library", strings(Messages.ActionLibrary), strings(Messages.A11yOpenLibrary)) { librarySheet = true })
                                if (minimal) {
                                    add(EditorToolbarAction("undo", "↶", strings(Messages.ActionUndo), state.canUndo, onClick = state::undo))
                                    add(EditorToolbarAction("redo", "↷", strings(Messages.ActionRedo), state.canRedo, onClick = state::redo))
                                    add(EditorToolbarAction("xml", ".circ", strings(Messages.A11yImportExportProject)) { runController.pause(); xmlText = state.exportXml(); xmlDialog = true })
                                    if (documentSession != null) add(EditorToolbarAction("files", strings(Messages.ActionFiles), strings(Messages.A11yProjectFiles)) { runController.pause(); fileDialog = true })
                                    if (editorTools.isNotEmpty()) add(EditorToolbarAction("tools", strings(Messages.ActionTools), strings(Messages.A11yProjectTools)) { runController.pause(); toolMenu = true })
                                    add(EditorToolbarAction("preferences", strings(Messages.PreferencesTitle)) { runController.pause(); preferencesSheet = true })
                                    if (state.importReport != null) add(EditorToolbarAction("import-report", strings("import_report_title"), onClick = state::showImportReport))
                                    add(EditorToolbarAction("help", "?", strings(Messages.A11yOpenHelp)) { helpDialog = true })
                                }
                                if (compact) {
                                    add(EditorToolbarAction("run", if (running) strings(Messages.ActionPause) else strings(Messages.ActionRun),
                                        enabled = state.documentWritable && state.interactionsEnabled, selected = running) {
                                        if (running) runController.pause() else runController.start()
                                    })
                                    add(EditorToolbarAction("step", strings(Messages.ActionStep), strings(Messages.A11yStepClock), state.documentWritable && state.interactionsEnabled) { runController.pauseContinuousExecution(); state.tick() })
                                    add(EditorToolbarAction("event", strings(Messages.SimulationEventStep), strings(Messages.A11ySimulationEventStep), state.documentWritable && state.interactionsEnabled) { runController.step(SimulationStep.EVENT) })
                                    add(EditorToolbarAction("reset", strings(Messages.ActionReset), strings(Messages.A11yResetSimulation), state.documentWritable && state.interactionsEnabled) { runController.pause(); state.resetSimulation() })
                                    add(EditorToolbarAction("simulation-options", strings(Messages.SimulationOptionsTitle)) { runController.pause(); simulationOptions = true })
                                    add(EditorToolbarAction("signals", strings(Messages.SignalsHeading)) { signalSheet = true })
                                    if (state.simulation.diagnostics.isNotEmpty()) add(EditorToolbarAction("diagnostics", "! ${state.simulation.diagnostics.size}", strings(Messages.SimulationWarningsTitle)) { diagnosticsDialog = true })
                                }
                                add(EditorToolbarAction("simulate", strings(Messages.ActionSimulate), selected = state.mode == EditorMode.INTERACT) { state.changeMode(EditorMode.INTERACT) })
                                add(EditorToolbarAction("select", strings(Messages.ActionSelect), selected = state.mode == EditorMode.SELECT) { state.changeMode(EditorMode.SELECT) })
                                add(EditorToolbarAction("move", strings(Messages.ActionMove), selected = state.mode == EditorMode.MOVE) { state.changeMode(EditorMode.MOVE) })
                                add(EditorToolbarAction("wire", strings(Messages.ActionWire), selected = state.mode == EditorMode.WIRE) { state.changeMode(EditorMode.WIRE) })
                                add(EditorToolbarAction("fit", strings(Messages.ActionFit), strings(Messages.A11yFitCircuit), onClick = state::fitViewport))
                                if (state.selectedIds.isNotEmpty() || state.selectedWires.isNotEmpty()) {
                                    add(EditorToolbarAction("fit-selection", strings(Messages.ActionFitSelection), strings(Messages.A11yFitSelection), onClick = state::fitSelected))
                                }
                                if (!wide) add(EditorToolbarAction("properties", strings(Messages.ActionProperties), strings(Messages.A11yOpenProperties)) { inspectorSheet = true })
                                add(EditorToolbarAction("select-all", strings(Messages.ActionSelectAll), strings(Messages.A11ySelectAll), onClick = state::selectAll))
                                if (state.selectedIds.isNotEmpty() || state.selectedWires.isNotEmpty()) {
                                    add(EditorToolbarAction("copy", strings(Messages.ActionCopy), strings(Messages.A11yCopySelection), onClick = state::copySelected))
                                    add(EditorToolbarAction("cut", strings(Messages.ActionCut), strings(Messages.A11yCutSelection), onClick = state::cutSelected))
                                    add(EditorToolbarAction("duplicate", strings(Messages.ActionDuplicate), strings(Messages.A11yDuplicateSelection), onClick = state::duplicateSelected))
                                    add(EditorToolbarAction("delete", strings(Messages.ActionDelete), strings(Messages.A11yDeleteSelection), onClick = state::removeSelected))
                                }
                                add(EditorToolbarAction("paste", strings(Messages.ActionPaste), strings(Messages.A11yPasteComponents), state.canPaste, onClick = state::paste))
                            }, tag = "editor-command-toolbar", title = strings("commands_heading"))
                            HorizontalDivider(color = Line)
                            if (!compact) EditorToolbar(actions = buildList {
                                add(EditorToolbarAction("run", if (running) "Ⅱ " + strings(Messages.ActionPause) else "▶ " + strings(Messages.ActionRun),
                                    enabled = state.documentWritable && state.interactionsEnabled, selected = running) {
                                    state.changeMode(EditorMode.INTERACT)
                                    if (running) runController.pause() else runController.start()
                                })
                                add(EditorToolbarAction("step", strings(Messages.ActionStep), strings(Messages.A11yStepClock), state.documentWritable && state.interactionsEnabled) { runController.pauseContinuousExecution(); state.tick() })
                                add(EditorToolbarAction("event", strings(Messages.SimulationEventStep), strings(Messages.A11ySimulationEventStep), state.documentWritable && state.interactionsEnabled) { runController.step(SimulationStep.EVENT) })
                                add(EditorToolbarAction("simulation-options", strings(Messages.SimulationOptionsTitle)) { runController.pause(); simulationOptions = true })
                                add(EditorToolbarAction("reset", strings(Messages.ActionReset), strings(Messages.A11yResetSimulation), state.documentWritable && state.interactionsEnabled) { runController.pause(); state.resetSimulation() })
                                add(EditorToolbarAction("frequency", strings(Messages.SimulationFrequency, "count" to ticksPerSecond), strings(Messages.A11yChangeFrequency)) {
                                    state.applyProject(SimulationProjectOptions.read(state.project).copy(
                                        tickFrequencyHz = when (ticksPerSecond) { 1.0 -> 4.0; 4.0 -> 16.0; else -> 1.0 }).applyTo(state.project))
                                })
                            }, tag = "editor-simulation-toolbar", title = strings(Messages.SimulationOptionsTitle), leading = {
                                val detail = strings(Messages.SimulationSteps, "count" to state.simulation.ticks) +
                                    if (state.mode == EditorMode.PLACE) " · " + strings(Messages.SimulationInsertMode, "name" to state.placingKind.localizedLabel()) else ""
                                Text(detail, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            })
                            HorizontalDivider(color = Line)
                            if (!compact) SimulationDiagnostics(state.simulation.notices)
                            CircuitCanvas(
                                document = state.document, simulation = state.simulation, registry = registry,
                                showGrid = preferences.showGrid, snapToGrid = preferences.snapToGrid,
                                highContrastSignals = preferences.highContrastSignals, signalRadix = preferences.signalRadix.xmlValue(),
                                gateShape = preferences.gateShape, shortcutScheme = preferences.shortcutScheme,
                                selectedId = state.selectedId, pendingWire = state.pendingWire,
                                selectedIds = state.selectedIds,
                                mode = state.mode, modifier = Modifier.weight(1f).fillMaxWidth().testTag("editor-canvas"),
                                onCanvasTap = state::tap, onComponentEvent = sendComponentEvent,
                                accessibilityActions = accessibleActions, accessibilityContext = accessibilityContext,
                                isSimulationRunning = running,
                                onAccessibilityNotice = state::reportStatus, viewportReset = state.viewportReset,
                                onSelectArea = state::selectInArea, onSelectComponent = state::select,
                                onSelectWire = state::selectWire, onMoveSelection = state::moveSelected,
                                onSplitWire = state::splitWireAt, onMoveWireCorner = state::moveWireCorner,
                                onCommand = state::handleCanvasCommand, canPaste = state.canPaste,
                                viewportState = state.viewport, onViewportChange = state::updateViewport,
                                selectedWires = state.selectedWires, fitSelection = state.fitSelection,
                                childCircuit = state.project::circuit,
                            )
                            if (!compact) SignalPanel(state, preferences.signalRadix)
                        }
                        }
                    }
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().background(Paper).padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.simulation.oscillating) strings(Messages.SimulationUnsettled) else strings(state.statusText),
                            fontSize = 11.sp, modifier = Modifier.weight(1f).semantics {
                                if (state.statusText.key != Messages.StatusClockStep) liveRegion = LiveRegionMode.Polite
                            }, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (wide) Text(strings.plural(Messages.ProjectComponentCount, state.document.components.size.toLong()) + " · " + strings.plural(Messages.ProjectWireCount, state.document.wires.size.toLong()),
                            fontSize = 11.sp, color = Color.DarkGray)
                    }
                }
            }
        }
        if (librarySheet) {
            ModalBottomSheet(onDismissRequest = { librarySheet = false }, modifier = Modifier.exportEditorTestTags(), containerColor = Paper,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                LibraryPanel(state, Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding(),
                    onOpenExample = { example -> runController.pause(); exampleLoader.open(example) }, onChoose = {
                    runController.pause()
                    librarySheet = false
                })
            }
        }
        if (inspectorSheet) {
            val inspectorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { inspectorSheet = false }, modifier = Modifier.exportEditorTestTags().testTag("editor-inspector-sheet"), containerColor = Paper,
                dragHandle = null, sheetState = inspectorSheetState) {
                InspectorSheetDragHandle(inspectorSheetState, onDismiss = { inspectorSheet = false })
                PropertyInspector(state, runStatus, runController::isEventPending, Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding())
            }
        }
        if (signalSheet) {
            ModalBottomSheet(onDismissRequest = { signalSheet = false }, modifier = Modifier.exportEditorTestTags(), containerColor = Paper) {
                SignalPanel(state, preferences.signalRadix)
            }
        }
        if (diagnosticsDialog) {
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { diagnosticsDialog = false }, title = { Text(strings(Messages.SimulationWarningsTitle)) },
                text = { Text(state.simulation.diagnostics.distinct().joinToString("\n"), modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { diagnosticsDialog = false }) { Text(strings(Messages.ActionClose)) } })
        }
        if (preferencesSheet) {
            ModalBottomSheet(onDismissRequest = { preferencesSheet = false }, modifier = Modifier.exportEditorTestTags(), containerColor = Paper,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                EditorPreferencesPanel(preferences, onPreferencesChange, onRestorePreferences,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(),
                    onOpenProjectOptions = { preferencesSheet = false; simulationOptions = true })
            }
        }
        preferenceNotice?.let { notice ->
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = onDismissPreferenceNotice,
                title = { Text(strings(Messages.PreferencesTitle)) }, text = { Text(strings(notice)) },
                confirmButton = { TextButton(onClick = onDismissPreferenceNotice) { Text(strings(Messages.ActionClose)) } })
        }
        if (simulationOptions) {
            ModalBottomSheet(onDismissRequest = { simulationOptions = false }, modifier = Modifier.exportEditorTestTags(), containerColor = Paper,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                SimulationOptionsPanel(state.project, onApply = { state.applyProject(it); simulationOptions = false },
                    onDismiss = { simulationOptions = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding())
            }
        }
        if (xmlDialog) {
            XmlDialog(xmlText, onTextChange = { xmlText = it; importLoader.cancel() },
                loading = importLoader.loading,
                onDismiss = { importLoader.cancel(); xmlDialog = false }, onImport = {
                    runController.pause()
                    importLoader.open(xmlText, commit = { state.acceptImport(it, message(Messages.ProjectXmlTitle)) },
                        onComplete = { accepted -> if (accepted) xmlDialog = false })
                })
        }
        if (helpDialog) {
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { helpDialog = false }, title = { Text(strings(Messages.EditorHelpTitle)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings(Messages.EditorHelpLibrary))
                    Text(strings(Messages.EditorHelpViewport))
                    Text(strings(Messages.EditorHelpSimulation))
                    Text(strings(Messages.EditorHelpEditing))
                    Text(strings(Messages.EditorHelpFiles))
                } },
                confirmButton = { TextButton(onClick = { helpDialog = false }) { Text(strings(Messages.ActionClose)) } })
        }
        if (fileDialog && documentSession != null) {
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { fileDialog = false }, title = { Text(strings(Messages.ProjectFilesTitle)) },
                text = { Column {
                    TextButton(onClick = { fileDialog = false; documentSession.openCopy() }) { Text(strings(Messages.ActionImportCopy)) }
                    if (documentSession.canEditInPlace) TextButton(onClick = { fileDialog = false; documentSession.editFile() }) { Text(strings(Messages.ActionEditExternal)) }
                    TextButton(onClick = { fileDialog = false; documentSession.save() }, enabled = documentSession.canSave) { Text(strings(Messages.ActionSaveFile)) }
                    TextButton(onClick = { fileDialog = false; documentSession.saveAs() }) { Text(strings(Messages.ActionSaveAs)) }
                    TextButton(onClick = { fileDialog = false; documentSession.share() }) { Text(strings(Messages.ActionShare)) }
                } }, confirmButton = { TextButton(onClick = { fileDialog = false }) { Text(strings(Messages.ActionClose)) } })
        }
        if (toolMenu) {
            AppAlertDialog(modifier = Modifier.editorDialogBounds().exportEditorTestTags(), onDismissRequest = { toolMenu = false }, title = { Text(strings(Messages.ProjectToolsTitle)) },
                text = { Column(Modifier.testTag("editor-tool-menu").verticalScroll(rememberScrollState())) {
                    if (!state.projectAccess.graphAvailable) Text(strings("project_graph_unavailable"))
                    editorTools.forEach { tool -> TextButton(
                        enabled = !tool.requiresProjectGraph || toolContext.projectExports.available,
                        onClick = { activeTool = tool.id; toolMenu = false }) { Text(strings(tool.titleText)) } }
                } }, confirmButton = { TextButton(onClick = { toolMenu = false }) { Text(strings(Messages.ActionClose)) } })
        }
        editorTools.firstOrNull { it.id == activeTool }?.let { tool ->
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val sheetModifier = if (keyboardVisible) Modifier.editorDialogBounds() else Modifier
            ModalBottomSheet(onDismissRequest = { activeTool = null }, modifier = sheetModifier.exportEditorTestTags().testTag("editor-tool-sheet"),
                dragHandle = if (keyboardVisible) null else { { BottomSheetDefaults.DragHandle() } },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current
                Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().zIndex(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings(tool.titleText), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(if (keyboardVisible) "editor-tool-hide-keyboard" else "editor-tool-close"), onClick = {
                            if (keyboardVisible) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            } else activeTool = null
                        }) { Text(strings(if (keyboardVisible) Messages.ActionHideKeyboard else Messages.ActionClose)) }
                    }
                    if (tool.requiresProjectGraph) key(tool.id, toolContext.documentIdentity) {
                        if (!toolContext.projectExports.available) Text(strings("project_graph_unavailable"))
                        else tool.content(toolContext)
                    } else tool.content(toolContext)
                }
            }
        }
        if (state.importReportVisible) state.importReport?.let { ImportReportDialog(it, state::dismissImportReport) }
        state.importErrorText?.let { notice ->
            val message = strings(notice)
            AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = state::dismissImportError,
                title = { Text(strings("action_failed_title")) },
                text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = state::dismissImportError) { Text(strings(Messages.ActionUnderstood)) } })
        }
    }
}

@Composable
private fun LibraryPanel(state: EditorState, modifier: Modifier = Modifier, onOpenExample: (dev.logisim.mobile.core.CircuitExample) -> Boolean, onChoose: () -> Unit = {}) {
    val strings = rememberLocalizer()
    var section by remember { mutableStateOf("project") }
    val componentGroups = remember(state.componentRegistry) { registryComponentGroups(state.componentRegistry) }
    val libraryEntries = remember(strings.language, componentGroups) { componentGroups.flatMap { (library, kinds) -> kinds.map { SearchLibraryEntry(it, it.localizedLabel().resolve(strings.language), strings(library)) } } }
    val commands = editorSearchCommands(state, strings)
    var searchIndex by remember { mutableStateOf<ProjectSearchIndex?>(null) }
    LaunchedEffect(state.project, commands, strings.language) {
        searchIndex = withContext(Dispatchers.Default) { ProjectSearchIndex.build(state.project, libraryEntries, commands, strings.language) }
    }
    Column(modifier.background(Paper).semantics { contentDescription = strings(Messages.A11yLibrary) }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SmallAction(strings(Messages.ActionCircuits), strings(Messages.A11yShowTree)) { section = "project" }
            SmallAction(strings(Messages.ActionLibrary), strings(Messages.A11yShowComponents)) { section = "library" }
            SmallAction(strings(Messages.ActionSearch), strings(Messages.A11ySearchAll)) { section = "search" }
        }
        when (section) {
            "project" -> ProjectNavigationPanel(state.project, state.activeTarget,
                onNavigate = { state.navigate(it); onChoose() },
                onCreate = { state.createCircuit(it) }, onRename = { id, name -> state.renameCircuit(id, name) },
                onMove = { id, direction -> state.moveCircuit(id, direction) }, onDelete = { state.removeCircuit(it) },
                onSetMain = { state.setMainCircuit(it) }, modifier = Modifier.weight(1f).fillMaxWidth())
            "search" -> searchIndex?.let { index ->
                ProjectSearchPanel(index, onNavigate = { state.navigate(it); onChoose() },
                    onInsert = { state.place(it); onChoose() }, onCommand = { command ->
                        when (command) {
                            "fit" -> state.fitViewport()
                            "select-all" -> state.selectAll()
                            "undo" -> state.undo()
                            "redo" -> state.redo()
                            "tick" -> state.tick()
                            "reset" -> state.resetSimulation()
                            "new" -> state.newDocument()
                            "sap1" -> dev.logisim.mobile.core.CircuitExamples.find("sap1")?.let(onOpenExample)
                        }
                        onChoose()
                    }, modifier = Modifier.weight(1f).fillMaxWidth())
            } ?: Text(strings(Messages.SearchPreparing), modifier = Modifier.weight(1f).padding(12.dp))
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                componentGroups.forEach { (group, kinds) ->
                    PanelHeading(strings(group))
                    kinds.forEach { kind -> LibraryRow(kind.shortSymbol(), kind.localizedLabel().resolve(strings.language),
                        state.mode == EditorMode.PLACE && state.placingKind == kind, strings(Messages.A11yInsertComponent, "name" to kind.localizedLabel())) {
                        state.place(kind); onChoose()
                    } }
                }
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SmallAction("SAP-1", strings(Messages.A11yLoadSap1)) { dev.logisim.mobile.core.CircuitExamples.find("sap1")?.let { if (onOpenExample(it)) onChoose() } }
            SmallAction(strings(Messages.ComponentAnd), strings(Messages.A11yLoadAnd)) { dev.logisim.mobile.core.CircuitExamples.find("and")?.let { if (onOpenExample(it)) onChoose() } }
            SmallAction(strings(Messages.ActionNew), strings(Messages.A11yNewCircuit)) { state.newDocument(); onChoose() }
        }
    }
}

@Composable
private fun LibraryRow(symbol: String, label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (selected) Selected else Paper).clickable(onClick = onClick)
        .padding(start = 18.dp, end = 8.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(symbol, color = Color(0xFF167624), fontSize = 12.sp, modifier = Modifier.width(30.dp), fontWeight = FontWeight.Medium)
        Text(label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PanelHeading(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth()
        .background(Color(0xFFE2E2E2)).padding(horizontal = 8.dp, vertical = 8.dp))
}

@Composable
private fun PropertyInspector(
    state: EditorState,
    simulationStatus: SimulationRunStatus,
    isEventPending: (dev.logisim.mobile.core.ComponentEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (!state.documentWritable) { Text(rememberLocalizer()("document_read_only_preserved"), modifier = modifier.padding(12.dp)); return }
    val circuit = state.document.id
    val path = state.activeInstancePath.toList()
    val identity = state.documentIdentity
    val send: (String, dev.logisim.mobile.core.ComponentEvent) -> Boolean = { id, event ->
        state.sendComponentEvent(id, event, path, circuit, identity)
    }
    Column(modifier.background(Color.White)) {
        state.selected?.takeIf { dev.logisim.mobile.core.IoLibrary.isInteractive(it.kind) &&
            (state.activeInstancePath.isNotEmpty() || state.activeCircuitName == state.project.mainCircuitName) }?.let { component ->
            key(identity, circuit, path, component.id) {
                IoInputPanel(component, state.simulation.componentStates[component.id], { send(component.id, it) },
                    simulationStatus, isEventPending, Modifier.fillMaxWidth().heightIn(max = 260.dp))
            }
        }
        state.selected?.let { component ->
            ExtraIoControls(component, state.simulation.componentStates[component.id], { send(component.id, it) },
                state::updateSelectedAttributes)
        }
        TypedPropertyInspector(
            registry = state.componentRegistry,
            components = state.document.components.filter { it.id in state.selectedIds },
            onAttributes = state::updateSelectedAttributes,
            onLabel = state::updateSelectedLabel,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        )
    }
}

@Composable
private fun SimulationDiagnostics(diagnostics: List<LocalizedText>) {
    val strings = rememberLocalizer()
    val messages = diagnostics.distinct().map { strings(it) }
    if (messages.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color(0xFFFFF0CF)).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(messages.first(), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = strings(Messages.A11ySimulationWarning, "message" to messages.first()) })
        SmallAction(strings.plural(Messages.SimulationWarningCount, messages.size.toLong()), strings.plural(Messages.A11ySimulationWarningCount, messages.size.toLong())) { expanded = true }
    }
    if (expanded) {
        AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { expanded = false }, title = { Text(strings(Messages.SimulationWarningsTitle)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(Messages.SimulationWarningsExplanation))
                messages.forEach { Text(it) }
            } },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(strings(Messages.ActionClose)) } })
    }
}

@Composable
private fun SignalPanel(state: EditorState, radix: EditorSignalRadix) {
    val strings = rememberLocalizer()
    var inputEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val components = state.document.components.filter {
        it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT ||
            it.kind.name in setOf("BUTTON", "PROBE", "HEX_DISPLAY", "LED", "REGISTER", "COUNTER")
    }.sortedBy { if (it.kind == ComponentKind.INPUT || it.kind == ComponentKind.BUTTON) 0 else 1 }
    if (components.isEmpty()) return
    Row(Modifier.fillMaxWidth().testTag("editor-signal-panel").background(Paper).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(strings(Messages.SignalsHeading), fontSize = 11.sp, color = Color.DarkGray)
        components.forEach { component ->
            val bit = state.simulation.componentValues[component.id] ?: LogicValue.UNKNOWN
            val value = state.simulation.vectorValues[component.id]?.let { formatEditorSignal(it, radix) } ?: bit.symbol()
            val label = component.label.ifBlank { component.kind.localizedLabel().resolve(strings.language) }
            if (component.kind == ComponentKind.BUTTON || component.kind == ComponentKind.INPUT &&
                state.activeInstancePath.isEmpty() && state.activeCircuitName == state.project.mainCircuitName) {
                OutlinedButton(onClick = {
                    if (component.width == 1) state.activateComponent(component.id)
                    else inputEditor = component.id to (state.simulation.vectorValues[component.id]?.toULongOrNull()?.toString() ?: "0")
                }, shape = RoundedCornerShape(1.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings(Messages.A11yToggleInput, "name" to label, "value" to value) }) {
                    Text("$label $value", fontSize = 12.sp)
                }
            } else {
                Text(strings(Messages.SimulationSignalValue, "name" to label, "value" to value), color = if (bit == LogicValue.HIGH) Color(0xFF008000) else Ink,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.background(Color.White).border(1.dp, Line).padding(10.dp)
                        .semantics { contentDescription = strings(Messages.A11ySignal, "name" to label, "value" to value) })
            }
        }
    }
    inputEditor?.let { (id, initialValue) ->
        val component = state.document.components.firstOrNull { it.id == id }
        var text by remember(id) { mutableStateOf(initialValue) }
        val parsed = if (text.startsWith("0x", ignoreCase = true)) text.drop(2).toULongOrNull(16) else text.toULongOrNull()
        val valid = parsed != null && component != null && (component.width == 64 || parsed < (1uL shl component.width))
        AppAlertDialog(modifier = Modifier.exportEditorTestTags(), onDismissRequest = { inputEditor = null }, title = { Text(strings(Messages.InputValueTitle)) },
            text = { OutlinedTextField(text, { text = it }, label = { Text(strings(Messages.InputNumberFormat)) },
                supportingText = { Text(strings(Messages.InputBits, "count" to (component?.width ?: 1))) }, isError = !valid, singleLine = true) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                parsed?.let { state.setInputValue(id, it) }
                inputEditor = null
            }) { Text(strings(Messages.ActionApply)) } },
            dismissButton = { TextButton(onClick = { inputEditor = null }) { Text(strings(Messages.ActionCancel)) } })
    }
}

@Composable
private fun SmallAction(label: String, description: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
        Text(label, color = if (enabled) Ink else Color.Gray, fontSize = 12.sp)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun XmlDialog(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onImport: () -> Unit, loading: Boolean = false) {
    val strings = rememberLocalizer()
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    AppAlertDialog(modifier = Modifier.editorDialogBounds().exportEditorTestTags(),
        onDismissRequest = onDismiss, title = { Text(strings(Messages.ProjectXmlTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("editor-xml-body").verticalScroll(rememberScrollState())) {
                Text(strings(Messages.ProjectXmlHelp),
                    style = MaterialTheme.typography.bodySmall)
                if (editing && text.length <= 16_384) {
                    OutlinedTextField(value = text, onValueChange = onTextChange, label = { Text(strings(Messages.ProjectXmlLabel)) },
                        minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
                } else {
                    Text(strings("xml_preview_size", "count" to text.length), style = MaterialTheme.typography.bodySmall)
                    Text(text.take(1_200), maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (text.length <= 16_384) TextButton(onClick = { editing = true }) { Text(strings("xml_edit")) }
                    else Text(strings("xml_large_help"), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(strings(Messages.ActionCopy)) }
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let(onTextChange); editing = false }) { Text(strings(Messages.ActionPaste)) }
                }
            }
        },
        confirmButton = { Button(onClick = onImport, enabled = !loading) {
            Text(strings(if (loading) "editor_loading_project" else Messages.ActionImportXml))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionClose)) } })
}

internal fun ComponentKind.shortSymbol(): String = when (this) {
    ComponentKind.INPUT -> "01"
    ComponentKind.OUTPUT -> "Y"
    ComponentKind.CLOCK -> "CLK"
    ComponentKind.D_FLIP_FLOP -> "D"
    ComponentKind.AND, ComponentKind.NAND -> "&"
    ComponentKind.OR, ComponentKind.NOR -> "≥1"
    ComponentKind.XOR, ComponentKind.XNOR -> "=1"
    ComponentKind.NOT -> "¬"
    ComponentKind.BUFFER -> "▷"
    else -> when (name) {
        "REGISTER" -> "REG"
        "COUNTER" -> "+1"
        "ROM", "RAM" -> name
        "ADDER" -> "+"
        "SUBTRACTOR" -> "−"
        "SPLITTER" -> "⊣"
        "TUNNEL" -> "◀"
        "HEX_DISPLAY" -> "8"
        else -> name.take(3)
    }
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
