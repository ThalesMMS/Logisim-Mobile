package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.MobileExtensionCatalog
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.localization.AppLocaleProvider
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Each native scene passes its own window ID while sharing the transactional catalog storage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogWorkspaceHost(
    storage: WorkspaceCatalogStorage,
    legacyXml: String? = null,
    documents: DocumentActions? = null,
    assets: DocumentAssetActions? = null,
    directoryAssets: DirectoryAssetActions? = null,
    windowId: String = "main",
    legacyExternalReference: DocumentReference? = null,
    onExternalWorkspaceSaved: (String) -> Unit = {},
    editorTools: List<EditorTool> = emptyList(),
    language: AppLanguage = systemAppLanguage(),
    preferencesStorage: PreferencesStorage? = null,
    registry: ComponentRegistry = ComponentCatalog.registry,
) {
    val confirmed by rememberUpdatedState(onExternalWorkspaceSaved)
    val preferencesRepository = remember(preferencesStorage) { preferencesStorage?.let(::EditorPreferencesRepository) }
    val initialPreferences = remember(preferencesRepository) { preferencesRepository?.load() }
    var effectiveLanguage by remember(language, preferencesRepository) {
        mutableStateOf(initialPreferences?.preferences?.locale?.language ?: language)
    }
    var themeMode by remember(preferencesRepository) {
        mutableStateOf(initialPreferences?.preferences?.themeMode ?: EditorThemeMode.SYSTEM)
    }
    var themeSetupComplete by remember(preferencesRepository) { mutableStateOf(false) }
    if (!themeSetupComplete && preferencesRepository != null &&
        initialPreferences?.hasNoStoredPreferences == true && legacyXml == null
    ) {
        // The workspace bootstraps a catalog even on an untouched install. Check before starting it
        // so upgrades skip the chooser, and quitting before confirming still counts as first launch.
        val newWorkspace by produceState<Boolean?>(null, storage) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    storage.withExclusiveAccess {
                        storage.listNames().none {
                            it == WorkspaceCatalogRepository.CATALOG_FILE || it == WorkspaceCatalogRepository.BACKUP_FILE
                        }
                    }
                }.getOrDefault(false)
            }
        }
        if (newWorkspace != false) {
            AppLocaleProvider(effectiveLanguage) {
                if (newWorkspace == true) {
                    FirstLaunchThemeScreen { chosenMode ->
                        // A second scene can finish setup while this one still shows the chooser.
                        val latest = preferencesRepository.load()
                        val selected = if (latest.hasNoStoredPreferences) chosenMode else latest.preferences.themeMode
                        val result = if (latest.hasNoStoredPreferences || !latest.canPersist) {
                            preferencesRepository.save(latest.preferences.copy(themeMode = selected))
                        } else PreferencesSaveResult.Success
                        if (result == PreferencesSaveResult.Success) {
                            themeMode = selected
                            themeSetupComplete = true
                        }
                        result
                    }
                } else {
                    LogisimTheme(themeMode.resolveDark()) { WorkspaceLoading() }
                }
            }
            return
        }
    }
    var runtimeState by remember(storage, windowId, registry) { mutableStateOf(CatalogWorkspaceRuntimeState()) }
    var extensionCatalog by remember(storage, windowId, registry) { mutableStateOf(MobileExtensionCatalog(registry)) }
    val control = remember(storage, windowId, registry) { EditorSessionControl() }
    val sceneScope = rememberCoroutineScope()
    val projectTemplates = remember(storage, registry) { ProjectTemplateStore(storage, registry) }
    val boardCatalog = remember(storage) { FpgaBoardCatalogStore(storage) }
    val binding = remember(storage, windowId, documents, registry) {
        CatalogWorkspaceRuntime(storage, windowId, control, nativeDocuments = documents, registry = registry,
            onExternalWorkspaceSaved = { confirmed(it) }, projectTemplates = projectTemplates,
            onStateChanged = { runtimeState = it })
            .apply { start(legacyXml, legacyExternalReference) }
    }
    val state = runtimeState.workspace
    var projectsVisible by remember(binding) { mutableStateOf(false) }
    var recentVisible by remember(binding) { mutableStateOf(false) }
    val sessions = remember(binding) { WorkspaceEditorSessions() }
    // Survives activity recreation, unlike the binding's in-memory import reports (#325).
    val importReportDismissals = rememberSaveable(saver = ImportReportDismissals.Saver) { ImportReportDismissals() }
    var closeTarget by remember(binding) { mutableStateOf<WorkspaceProjectId?>(null) }
    var closingSession by remember(binding) { mutableStateOf(false) }
    val activeId = state?.editorProjectId
    val catalogProjects = state?.catalog?.projects.orEmpty().filterNot { it.deleted }.associateBy { it.id }
    val openIds = sessions.openIds.filter { it in catalogProjects }
    SideEffect {
        activeId?.let(sessions::opened)
        if (state != null) sessions.openIds.filterNot { it in catalogProjects }.forEach(sessions::close)
    }
    fun closeSession(id: WorkspaceProjectId, save: Boolean) {
        if (runtimeState.busy || closingSession) return
        closingSession = true
        val next = openIds.lastOrNull { it != id }
        binding.closeEditorSession(id, next, save) { success ->
            closingSession = false
            closeTarget = null
            if (success) sessions.close(id) else projectsVisible = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, binding) {
        fun active(active: Boolean) {
            binding.foregroundChanged(active)
        }
        active(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> active(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); binding.close() }
    }
    AppLocaleProvider(effectiveLanguage) {
        val strings = rememberLocalizer()
        LogisimTheme(themeMode.resolveDark()) {
            Surface(color = ChromeColors.Bar) {
                if (state == null) {
                    WorkspaceLoading()
                } else {
                    val topInset = if (openIds.size > 1) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        else Modifier
                    Column(Modifier.fillMaxSize().then(topInset)) {
                        if (openIds.size > 1) {
                            Row(Modifier.fillMaxWidth().exportEditorTestTags().testTag("workspace-session-tabs").horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically) {
                                for (id in openIds) {
                                    val name = catalogProjects.getValue(id).name
                                    val isSelected = id == activeId
                                    val tab = Modifier.testTag("workspace-session-${id.value}").semantics { selected = isSelected }
                                    // Large fonts already fill the 40 dp button; less padding keeps a short window's canvas.
                                    if (isSelected) {
                                        FilledTonalButton(onClick = {}, enabled = !runtimeState.busy, modifier = tab,
                                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    } else {
                                        TextButton(onClick = { binding.openProject(id) }, enabled = !runtimeState.busy, modifier = tab,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    val closeLabel = strings("workspace_close_named_session", "name" to name)
                                    TextButton(onClick = { closeTarget = id }, enabled = !runtimeState.busy,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("workspace-close-session-${id.value}")
                                            .semantics { contentDescription = closeLabel }) { Text("×") }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (state.editorClosed) {
                            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing),
                                contentAlignment = Alignment.Center) {
                                TextButton(onClick = { projectsVisible = true }, enabled = !runtimeState.busy) {
                                    Text(strings(Messages.WorkspaceProjects))
                                }
                            }
                        } else {
                            val mount = state.editorMount
                            val editorSession = remember(binding, mount) {
                                state.editorProjectId?.let { id -> WorkspaceEditorSessionBinding(
                                    sessions.restore(id, state.editorXml, state.readOnly), state.session.viewState,
                                    retain = { sessions.retain(id, it) }, open = binding::openEditorSession,
                                ) }
                            }
                            val header = EditorWorkspaceHeader(
                                projectName = state.session.document?.info?.name?.takeIf { state.editorProjectId != null }
                                    ?: strings(Messages.WorkspaceUnsavedDraft),
                                status = if (runtimeState.pendingWrites) strings("workspace_saving")
                                    else if (state.readOnly) strings(Messages.WorkspaceReadOnlyOriginal)
                                    else if (state.editorProjectId == null || state.session.dirty) strings(Messages.WorkspaceUnsavedChanges)
                                    else strings(Messages.WorkspaceSavedRevision, "revision" to (state.session.confirmedRevision ?: 0)),
                                busy = runtimeState.busy,
                                onOpenProjects = { if (!runtimeState.busy) projectsVisible = true },
                                onOpenRecent = { if (!runtimeState.busy) recentVisible = true },
                            ).apply { session = editorSession }
                            val portableProjects = remember(binding, mount, extensionCatalog) {
                                PortableProjectWorkspaceBridge(sceneScope, extensionCatalog.registry, mount,
                                    isCurrent = { expectedMount, identity -> !binding.state.busy &&
                                        binding.state.workspace?.editorMount == expectedMount && control.isAttached &&
                                        control.documentIdentity == identity },
                                    openPrepared = binding::openPreparedCopy, readAttachment = binding::readAttachment).access()
                            }
                            Column(Modifier.weight(1f)) {
                                key(binding, mount) {
                                    App(initialXml = state.editorXml,
                                        onDocumentChanged = { binding.documentChanged(mount, it) },
                                        documents = binding.documents, assets = assets, editorTools = editorTools, language = language, registry = registry,
                                        preferencesStorage = preferencesStorage, onLanguageChanged = { effectiveLanguage = it }, onThemeModeChanged = { themeMode = it },
                                        projectTemplates = projectTemplates, boardCatalog = boardCatalog,
                                        sessionControl = control, initialImportReport = state.editorImportReport, initialReadOnly = state.readOnly,
                                        portableProjects = portableProjects, directories = directoryAssets,
                                        initialExtensionCatalog = extensionCatalog,
                                        onExtensionCatalogChanged = { catalog ->
                                            binding.updateExtensionRegistry(catalog.registry)
                                            extensionCatalog = catalog
                                        },
                                        onProjectReplaced = { binding.projectReplaced(mount, it) },
                                        onCommitProject = { identity, xml, completed ->
                                            binding.commitEditorChange(mount, identity, xml, completed)
                                        },
                                        workspaceHeader = header,
                                        importReportDismissals = importReportDismissals,
                                        importReportKey = ImportReportDismissals.key(state.editorProjectId, state.editorXml))
                                    val editorReady = control.isAttached
                                    SideEffect { if (editorReady) binding.editorAttached(mount, control.exportImportReport()) }
                                    if (editorSession == null) LaunchedEffect(mount) { control.importViewState(state.session.viewState) }
                                    LaunchedEffect(mount, binding) {
                                        snapshotFlow { if (control.isAttached) control.exportViewState() else null }.distinctUntilChanged().collectLatest { view ->
                                            if (view != null) {
                                                delay(400)
                                                binding.viewChanged(mount, view)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (recentVisible) {
                        WorkspaceRecentFiles(state.recentFiles, onDismiss = { recentVisible = false },
                            onOpen = { file -> binding.openRecentFile(file) { opened -> if (opened) recentVisible = false } })
                    }
                    if (projectsVisible) {
                        EditorModalSheet(onDismissRequest = { projectsVisible = false }) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(bottom = 16.dp)) {
                                (state.notices + state.session.notices).distinct().forEach {
                                    Text(strings(it), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (!state.editorClosed && !state.readOnly && (state.session.dirty || state.editorProjectId == null)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { binding.flush() }) { Text(strings(Messages.WorkspaceRetrySave)) }
                                        TextButton(onClick = {
                                            binding.saveLocalAsCopy(state.session.document?.info?.name ?: strings(Messages.WorkspaceUnsavedDraft))
                                        }) { Text(strings(Messages.WorkspaceSaveLocalCopy)) }
                                    }
                                }
                                if (state.readOnly && state.editorProjectId != null) {
                                    TextButton(onClick = { binding.reloadStored() }) { Text(strings(Messages.WorkspaceRetryOpen)) }
                                }
                                if (state.session.conflict != null && state.editorProjectId != null) {
                                    TextButton(onClick = { binding.reloadStored() }) { Text(strings(Messages.WorkspaceReloadSaved)) }
                                }
                                if (activeId != null) {
                                    TextButton(onClick = { projectsVisible = false; closeTarget = activeId },
                                        enabled = !runtimeState.busy) { Text(strings("workspace_close_session")) }
                                }
                                WorkspacePanel(state.catalog, onOpen = { binding.openProject(it) { success -> if (success) projectsVisible = false } },
                                    onCreate = { binding.createProject(it, strings(Messages.NewCircuitName), newCircuitAttributes(preferencesStorage)) { success -> if (success) projectsVisible = false } },
                                    onDuplicate = binding::duplicateProject, onRename = binding::renameProject,
                                    onDelete = binding::deleteProject, onRestore = binding::restoreProject,
                                    modifier = Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                    closeTarget?.let { id ->
                        val name = catalogProjects[id]?.name.orEmpty()
                        AppAlertDialog(
                            modifier = Modifier.exportEditorTestTags().testTag("workspace-close-session-dialog"),
                            onDismissRequest = { if (!closingSession) closeTarget = null },
                            title = { Text(strings("workspace_close_named_session", "name" to name)) },
                            text = { Text(strings("workspace_close_session_confirmation")) },
                            confirmButton = {
                                TextButton(onClick = { closeSession(id, save = true) }, enabled = !runtimeState.busy && !closingSession) {
                                    Text(strings(Messages.ActionSave))
                                }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = { closeSession(id, save = false) }, enabled = !runtimeState.busy && !closingSession) {
                                        Text(strings("workspace_discard_pending"))
                                    }
                                    TextButton(onClick = { closeTarget = null }, enabled = !closingSession) { Text(strings(Messages.ActionCancel)) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceLoading() {
    val strings = rememberLocalizer()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(strings("editor_loading_project"), Modifier.padding(top = 12.dp))
        }
    }
}

/** The circuit creation defaults saved in the editor preferences, for a project created from the catalog. */
private fun newCircuitAttributes(storage: PreferencesStorage?): Map<String, String> =
    (storage?.let { EditorPreferencesRepository(it).load().preferences } ?: EditorPreferences()).creationDefaults.circuitAttributes()
