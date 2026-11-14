package dev.logisim.mobile

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.uniqueProjectName
import dev.logisim.mobile.core.localization.message
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.localization.AppLocaleProvider
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Closing or trashing a tab hands the editor to the tab after it, or else the one before it. */
internal fun workspaceNeighborSession(openIds: List<WorkspaceProjectId>, id: WorkspaceProjectId): WorkspaceProjectId? {
    val index = openIds.indexOf(id)
    if (index < 0) return openIds.lastOrNull()
    return openIds.getOrNull(index + 1) ?: openIds.getOrNull(index - 1)
}

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
            .apply {
                // A fresh install starts with the AND example under its own title, not its circuit name.
                start(legacyXml, legacyExternalReference, defaultName = message("help_example_and_title").resolve(effectiveLanguage),
                    recoveredName = message(Messages.WorkspaceRecoveredProject).resolve(effectiveLanguage))
            }
    }
    val state = runtimeState.workspace
    var projectsVisible by remember(binding) { mutableStateOf(false) }
    var recentVisible by remember(binding) { mutableStateOf(false) }
    val sessions = remember(binding) { WorkspaceEditorSessions() }
    // Survives activity recreation, unlike the binding's in-memory import reports (#325).
    val importReportDismissals = rememberSaveable(saver = ImportReportDismissals.Saver) { ImportReportDismissals() }
    var closeTarget by remember(binding) { mutableStateOf<WorkspaceProjectId?>(null) }
    var closingSession by remember(binding) { mutableStateOf(false) }
    val snackbars = remember(binding) { SnackbarHostState() }
    var dismissedNotices by remember(binding) { mutableStateOf(emptySet<LocalizedText>()) }
    val activeId = state?.editorProjectId
    val catalogProjects = state?.catalog?.projects.orEmpty().filterNot { it.deleted }.associateBy { it.id }
    val openIds = sessions.openIds.filter { it in catalogProjects }
    SideEffect {
        activeId?.let(sessions::opened)
        if (state != null) sessions.openIds.filterNot { it in catalogProjects }.forEach(sessions::close)
    }
    fun notify(message: String, action: String? = null, onAction: () -> Unit = {}) {
        sceneScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(message, action, withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
    /** A refused Undo (the workspace was busy, or its write failed) says so, and is offered again while it still applies. */
    fun offerUndo(strings: Localizer, message: String, available: () -> Boolean, undo: ((Boolean) -> Unit) -> Unit) {
        notify(message, strings("workspace_undo")) {
            undo { success ->
                if (!success) {
                    if (available()) offerUndo(strings, strings("workspace_undo_failed"), available, undo)
                    else notify(strings("workspace_undo_failed"))
                }
            }
        }
    }
    fun closeSession(strings: Localizer, id: WorkspaceProjectId, save: Boolean, writeOriginal: Boolean = save) {
        if (runtimeState.busy || closingSession) return
        closingSession = true
        val next = workspaceNeighborSession(openIds, id)
        val name = catalogProjects[id]?.name.orEmpty()
        binding.closeEditorSession(id, next, save, writeOriginal) { success ->
            closingSession = false
            closeTarget = null
            if (!success) projectsVisible = true
            else {
                sessions.close(id)
                if (!save) {
                    val reverted = strings("workspace_reverted", "name" to name)
                    // Undo only when the revert replaced something: a write, or edits that never reached storage.
                    fun undoable() = binding.state.workspace?.revertUndo == id
                    if (undoable()) offerUndo(strings, reverted, ::undoable) { done -> binding.undoRevert(id, done) }
                    else notify(reverted)
                }
            }
        }
    }
    /**
     * As Evolution's Frame.confirmClose: a tab without changes closes at once; only changed ones ask. A tab linked to a
     * file also asks when this window never saw the file match the project (it was mounted from the catalog).
     */
    fun requestClose(strings: Localizer, id: WorkspaceProjectId) {
        val workspace = runtimeState.workspace ?: return
        if (runtimeState.busy || closingSession) return
        val changed = id in workspace.changedSessions || (id == workspace.editorProjectId && runtimeState.pendingWrites)
        val fileUnconfirmed = (id in workspace.unconfirmedFiles || id in workspace.externalDirty) &&
            binding.documents?.supportsEditInPlace == true
        // Nothing changed since the tab opened (or since its file was last saved), so there is nothing to write either.
        if (changed || fileUnconfirmed) closeTarget = id else closeSession(strings, id, save = true, writeOriginal = false)
    }
    fun trash(strings: Localizer, id: WorkspaceProjectId, revision: Long) {
        val wasOpen = id == runtimeState.workspace?.editorProjectId
        val wasTab = id in openIds
        val name = catalogProjects[id]?.name.orEmpty()
        binding.deleteProject(id, revision, next = if (wasOpen) workspaceNeighborSession(openIds, id) else null) { success ->
            if (success) {
                if (wasOpen) projectsVisible = false
                fun trashed() = binding.state.workspace?.catalog?.projects?.firstOrNull { it.id == id && it.deleted }
                if (trashed() != null) offerUndo(strings, strings("workspace_moved_to_trash", "name" to name), { trashed() != null }) { done ->
                    val project = trashed()
                    if (project == null) done(false)
                    else binding.restoreProject(id, project.revision, reopenSession = wasTab) { restored ->
                        // Its tab comes back too: the open project reopens, another tab returns to the tab row.
                        if (restored && wasOpen) binding.openProject(id)
                        else if (restored && wasTab) sessions.opened(id)
                        done(restored)
                    }
                }
            }
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
    val savingShown = rememberSavingShown(runtimeState.pendingWrites)
    AppLocaleProvider(effectiveLanguage) {
        val strings = rememberLocalizer()
        LogisimTheme(themeMode.resolveDark()) {
            Surface(color = ChromeColors.Bar) {
                if (state == null) {
                    WorkspaceLoading()
                } else {
                    val indicator = workspaceSaveIndicator(state, if (savingShown) runtimeState else runtimeState.copy(pendingWrites = false))
                    val activeFile = activeId?.let { state.sessionFiles[it] }?.takeIf { binding.documents?.supportsEditInPlace == true }
                    val bannerNotice = workspaceBannerNotice(state, indicator)
                    val notices = workspaceNotices(state, bannerNotice)
                    LaunchedEffect(notices) {
                        // A notice that went away and comes back is new again.
                        dismissedNotices = dismissedNotices.filterTo(mutableSetOf()) { text -> notices.any { it.text == text } }
                    }
                    val bannerVisible = !state.editorClosed && indicator.needsAction
                    val topInset = if (openIds.size > 1 || bannerVisible) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        else Modifier
                    Column(Modifier.fillMaxSize().then(topInset)) {
                        if (openIds.size > 1) {
                            val now = remember(openIds) { Clock.System.now().toEpochMilliseconds() }
                            val labels = workspaceTabLabels(openIds.map(catalogProjects::getValue), state.sessionFiles, now)
                            // The open tab scrolls into view, so the row never hides which project the editor shows.
                            val tabScroll = rememberScrollState()
                            val tabBounds = remember(binding) { mutableStateMapOf<WorkspaceProjectId, IntRange>() }
                            val activeBounds = activeId?.let { tabBounds[it] }
                            LaunchedEffect(activeId, activeBounds, tabScroll.viewportSize) {
                                val bounds = activeBounds ?: return@LaunchedEffect
                                val viewport = tabScroll.viewportSize
                                if (viewport <= 0) return@LaunchedEffect
                                if (bounds.first < tabScroll.value) tabScroll.animateScrollTo(bounds.first)
                                else if (bounds.last > tabScroll.value + viewport) tabScroll.animateScrollTo(bounds.last - viewport)
                            }
                            Row(Modifier.fillMaxWidth().exportEditorTestTags().testTag("workspace-session-tabs")
                                .horizontalScroll(tabScroll).selectableGroup(),
                                verticalAlignment = Alignment.CenterVertically) {
                                for (id in openIds) {
                                    WorkspaceSessionTab(
                                        label = strings(labels.getValue(id)), selected = id == activeId,
                                        // An edit since the tab opened, or a linked file that does not have the latest edits.
                                        states = listOfNotNull(
                                            strings("workspace_tab_changed").takeIf { id in state.changedSessions },
                                            state.sessionFiles[id]?.takeIf { id in state.externalDirty && binding.documents?.supportsEditInPlace == true }
                                                ?.let { strings("workspace_not_saved_to_file", "file" to it) },
                                        ),
                                        enabled = !runtimeState.busy, tag = "workspace-session-${id.value}",
                                        closeTag = "workspace-close-session-${id.value}",
                                        onSelect = { if (id != activeId) binding.openProject(id) }, onClose = { requestClose(strings, id) },
                                        onPlaced = { start, end -> if (tabBounds[id] != start..end) tabBounds[id] = start..end })
                                }
                            }
                            HorizontalDivider()
                        }
                        // Outside App, so a failed save shows even with Preferences > Show header bar turned off.
                        if (bannerVisible) WorkspaceSaveBanner(indicator, bannerNotice, runtimeState.busy,
                            onRetry = binding::flush, onReload = { binding.reloadStored() },
                            onSaveCopy = { binding.saveLocalAsCopy(copyName(strings, state)) })
                        if (state.editorClosed) {
                            WorkspaceEmptyState(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing),
                                busy = runtimeState.busy, documents = binding.documents,
                                onNewProject = {
                                    binding.createProject(strings("workspace_untitled_project"), strings(Messages.NewCircuitName),
                                        newCircuitAttributes(preferencesStorage), uniqueName = true)
                                },
                                onOpenRecent = { recentVisible = true }, onProjects = { projectsVisible = true },
                                onExample = { example, title -> binding.openExample(example, title) })
                            // While no editor is composed, a file opened from another app or the picker opens here at once.
                            // The runtime hands files over one at a time, only while it is idle; the rest wait for the
                            // editor the first one mounts, which opens them as it opens any file.
                            DisposableEffect(binding) {
                                binding.setWindowOpenHandler { result ->
                                    when (result) {
                                        is DocumentOpenResult.Opened -> binding.openWithoutEditor(result) { opened ->
                                            // A file that cannot be opened says why here, not only in the Projects sheet.
                                            if (!opened) binding.state.workspace?.notices?.lastOrNull()?.let { notify(strings(it)) }
                                            // As the editor's status line: e.g. this provider only allows Save as.
                                            else result.notice?.let { notify(strings(it)) }
                                        }
                                        is DocumentOpenResult.Failure -> notify(strings(result.notice))
                                        DocumentOpenResult.Cancelled -> Unit
                                    }
                                }
                                onDispose { binding.setWindowOpenHandler(null) }
                            }
                        } else {
                            val mount = state.editorMount
                            // The last reopen announced in this window ([CatalogWorkspaceState.reopenedFile]).
                            val announcedReopen = remember(binding) { mutableLongStateOf(0L) }
                            val editorSession = remember(binding, mount) {
                                state.editorProjectId?.let { id -> WorkspaceEditorSessionBinding(
                                    sessions.restore(id, state.editorXml, state.readOnly), state.session.viewState,
                                    retain = { sessions.retain(id, it) },
                                    open = { name, xml, report ->
                                        binding.openEditorSession(name.resolve(effectiveLanguage).removeSuffix(".circ"), xml, report)
                                    },
                                ) }
                            }
                            val header = EditorWorkspaceHeader(
                                projectName = state.session.document?.info?.name?.takeIf { state.editorProjectId != null }
                                    ?: strings(Messages.WorkspaceUnsavedDraft),
                                status = when (indicator) {
                                    WorkspaceSaveIndicator.SAVING -> strings("workspace_saving")
                                    WorkspaceSaveIndicator.FAILED -> strings("workspace_not_saved")
                                    WorkspaceSaveIndicator.CONFLICT -> strings("workspace_saved_elsewhere")
                                    WorkspaceSaveIndicator.READ_ONLY -> strings(Messages.WorkspaceReadOnlyOriginal)
                                    WorkspaceSaveIndicator.DRAFT, WorkspaceSaveIndicator.PENDING -> strings(Messages.WorkspaceUnsavedChanges)
                                    // A linked file is saved only when it has the project's content, as Evolution's dirty marker.
                                    // The file is named unless the project already carries its name, so the header keeps both.
                                    WorkspaceSaveIndicator.SAVED -> {
                                        val named = activeFile != null && activeFile.removeSuffix(".circ") != state.session.document?.info?.name
                                        when {
                                            activeFile != null && activeId in state.externalDirty ->
                                                if (named) strings("workspace_not_saved_to_file", "file" to activeFile)
                                                else strings("workspace_not_saved_to_its_file")
                                            activeFile != null -> if (named) strings("workspace_saved_to_file", "file" to activeFile)
                                                else strings("workspace_saved_to_its_file")
                                            else -> strings("workspace_saved_status")
                                        }
                                    }
                                },
                                busy = runtimeState.busy,
                                onOpenProjects = { if (!runtimeState.busy) projectsVisible = true },
                                onOpenRecent = { if (!runtimeState.busy) recentVisible = true },
                                // Save As and Share propose the project's name, never the draft placeholder.
                                fileName = state.session.document?.info?.name?.takeIf { state.editorProjectId != null },
                                // Where "Not saved to Somador.circ" does not fit (a phone), the header says "the file".
                                compactStatus = if (indicator != WorkspaceSaveIndicator.SAVED || activeFile == null) null
                                    else if (activeId in state.externalDirty) strings("workspace_not_saved_to_its_file")
                                    else strings("workspace_saved_to_its_file"),
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
                                    // An open linked file opened again brings this editor forward: it says the file
                                    // opened (held until the editor attaches), once per reopen.
                                    val reopened = state.reopenedFile
                                    LaunchedEffect(reopened?.serial) {
                                        if (reopened != null && reopened.serial > announcedReopen.longValue &&
                                            control.reportOpened(reopened.name)) announcedReopen.longValue = reopened.serial
                                    }
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
                    // The Surface stacks its children, so this floats over the editor without a wrapper.
                    if (!projectsVisible) SnackbarHost(snackbars, Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 64.dp))
                    if (recentVisible) {
                        WorkspaceRecentFiles(state.recentFiles, onDismiss = { recentVisible = false },
                            onOpen = { file -> binding.openRecentFile(file) { opened -> if (opened) recentVisible = false } },
                            onRemove = { file -> binding.removeRecent(file) })
                    }
                    if (projectsVisible) {
                        EditorModalSheet(onDismissRequest = { projectsVisible = false }, title = strings("workspace_projects")) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(bottom = 16.dp)) {
                                // The only place notices are listed (the save banner's own notice stays in the banner); each can be dismissed.
                                notices.filter { it.text !in dismissedNotices }.forEach { notice ->
                                    WorkspaceNoticeRow(notice) { dismissedNotices = dismissedNotices + notice.text }
                                }
                                if (state.readOnly && state.editorProjectId != null) {
                                    TextButton(onClick = { binding.reloadStored() }) { Text(strings(Messages.WorkspaceRetryOpen)) }
                                }
                                if (activeId != null) {
                                    TextButton(onClick = { projectsVisible = false; requestClose(strings, activeId) },
                                        enabled = !runtimeState.busy) { Text(strings("workspace_close_session")) }
                                }
                                // The name dialog stays open and shows why the catalog refused the name.
                                fun named(completed: (LocalizedText?) -> Unit): (Boolean) -> Unit = { success ->
                                    val workspace = binding.state.workspace
                                    completed(if (success) null else workspace?.let { workspaceNotices(it) }
                                        ?.firstOrNull { it.severity == WorkspaceNoticeSeverity.ERROR }?.text ?: LocalizedText(Messages.WorkspaceErrorName))
                                }
                                WorkspacePanel(state.catalog, onOpen = { binding.openProject(it) { success -> if (success) projectsVisible = false } },
                                    onCreate = { name, completed ->
                                        binding.createProject(name, strings(Messages.NewCircuitName), newCircuitAttributes(preferencesStorage),
                                            completed = named { failure -> completed(failure); if (failure == null) projectsVisible = false })
                                    },
                                    onDuplicate = { id, revision ->
                                        // The copy is named in the app language, not the system's.
                                        val source = catalogProjects[id]?.name.orEmpty()
                                        binding.duplicateProject(id, revision, name = strings(Messages.WorkspaceCopyName, "name" to source))
                                    },
                                    onRename = { id, revision, name, completed -> binding.renameProject(id, revision, name, named(completed)) },
                                    onDelete = { id, revision -> trash(strings, id, revision) },
                                    onRestore = { id, revision -> binding.restoreProject(id, revision) },
                                    onPurge = { id, revision ->
                                        val name = state.catalog.projects.firstOrNull { it.id == id }?.name.orEmpty()
                                        binding.purgeProject(id, revision) { deleted ->
                                            if (deleted) notify(strings("workspace_deleted_permanently", "name" to name))
                                        }
                                    },
                                    onEmptyTrash = {
                                        binding.emptyTrash { count -> if (count != null) notify(strings.plural("workspace_trash_emptied", count.toLong())) }
                                    },
                                    newProjectName = uniqueProjectName(strings("workspace_untitled_project"),
                                        state.catalog.projects.filterNot { it.deleted }.map { it.name }),
                                    modifier = Modifier.weight(1f, fill = false))
                                SnackbarHost(snackbars)
                            }
                        }
                    }
                    closeTarget?.let { id ->
                        val name = catalogProjects[id]?.name.orEmpty()
                        // A linked file can still be written; a catalog-only project is already saved and can only be reverted.
                        val file = state.sessionFiles[id]?.takeIf { binding.documents?.supportsEditInPlace == true }
                        val enabled = !runtimeState.busy && !closingSession
                        val active = id == state.editorProjectId
                        // After a failed write or a conflict the latest edits exist only in this tab, whatever the dialog offers.
                        val unsaved = active && indicator.needsAction
                        // Keeping changes cannot succeed during a conflict: offer what resolves it, as the banner does.
                        val conflict = active && indicator == WorkspaceSaveIndicator.CONFLICT
                        val changed = id in state.changedSessions || (active && runtimeState.pendingWrites)
                        AppAlertDialog(
                            modifier = Modifier.exportEditorTestTags().testTag("workspace-close-session-dialog"),
                            onDismissRequest = { if (!closingSession) closeTarget = null },
                            title = { Text(strings("workspace_close_named_session", "name" to name)) },
                            text = {
                                Text(when {
                                    unsaved -> strings("workspace_close_unsaved_explanation")
                                    file != null && changed -> strings("workspace_close_linked_explanation", "file" to file)
                                    file != null -> strings("workspace_close_linked_unconfirmed_explanation", "file" to file)
                                    else -> strings("workspace_close_changed_explanation")
                                })
                            },
                            confirmButton = {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                                    val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    when {
                                        // Keep, save to file and revert all fail on the other window's revision; the
                                        // banner's Reload and Save a copy resolve it. Save a copy keeps this tab's edits and
                                        // comes first; Reload discards them, so it is the secondary action.
                                        conflict -> {
                                            FilledTonalButton(onClick = { closeTarget = null; binding.saveLocalAsCopy(copyName(strings, state)) },
                                                enabled = enabled, modifier = wide.testTag("workspace-close-session-save-copy")) {
                                                Text(strings("workspace_banner_save_copy"))
                                            }
                                            TextButton(onClick = { closeTarget = null; binding.reloadStored() }, enabled = enabled,
                                                modifier = wide.testTag("workspace-close-session-reload")) { Text(strings("workspace_banner_reload")) }
                                        }
                                        file != null -> {
                                            FilledTonalButton(onClick = { closeSession(strings, id, save = true) }, enabled = enabled, modifier = wide) {
                                                Text(strings("workspace_close_save_to_file", "file" to file))
                                            }
                                            TextButton(onClick = { closeSession(strings, id, save = true, writeOriginal = false) }, enabled = enabled,
                                                modifier = wide) { Text(strings("workspace_close_dont_save_to_file")) }
                                        }
                                        else -> {
                                            FilledTonalButton(onClick = { closeSession(strings, id, save = true) }, enabled = enabled, modifier = wide) {
                                                Text(strings("workspace_close_keep_changes"))
                                            }
                                            TextButton(onClick = { closeSession(strings, id, save = false) }, enabled = enabled, modifier = wide) {
                                                Text(strings("workspace_close_revert_to_opened", "elapsed" to openedAgo(strings, state.sessionOpenedAt[id])))
                                            }
                                        }
                                    }
                                    TextButton(onClick = { closeTarget = null }, enabled = !closingSession,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "{name} (copy)" in the app language for the open project, or "Untitled" for a draft. */
private fun copyName(strings: Localizer, state: CatalogWorkspaceState): String =
    state.session.document?.info?.name?.takeIf { state.editorProjectId != null }
        ?.let { strings(Messages.WorkspaceCopyName, "name" to it) } ?: strings("workspace_untitled_project")

/**
 * Autosave runs after every edit, pan and selection, so "Saving…" shows only for a write still pending after
 * [delayMillis]; it goes as soon as the write completes.
 */
@Composable
internal fun rememberSavingShown(pending: Boolean, delayMillis: Long = 500): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        if (pending) { delay(delayMillis); shown = true } else shown = false
    }
    return shown && pending
}

/**
 * One session tab: a Tab for assistive technology (selected, with what changed as its state), a dot when it has
 * changes, and a 48 dp close button named "Close tab {name}".
 */
@Composable
private fun WorkspaceSessionTab(
    label: String, selected: Boolean, states: List<String>, enabled: Boolean, tag: String, closeTag: String,
    onSelect: () -> Unit, onClose: () -> Unit, onPlaced: (start: Int, end: Int) -> Unit,
) {
    val strings = rememberLocalizer()
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.onPlaced { coordinates ->
        val start = coordinates.positionInParent().x.roundToInt()
        onPlaced(start, start + coordinates.size.width)
    }, verticalAlignment = Alignment.CenterVertically) {
        // The whole 48 dp row height is the tab's target; the pill inside it shows the selection and the ripple.
        val interaction = remember { MutableInteractionSource() }
        Row(Modifier.padding(start = 4.dp).heightIn(min = 48.dp)
            // The open project's tab is selected and has nothing to activate, so it offers no click (C152).
            .then(if (selected) Modifier.semantics(mergeDescendants = true) { role = Role.Tab; this.selected = true }
                else Modifier.selectable(selected = false, interactionSource = interaction, indication = null, enabled = enabled,
                    role = Role.Tab, onClick = onSelect))
            .testTag(tag)
            .semantics { if (states.isNotEmpty()) stateDescription = states.joinToString(", ") },
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(vertical = 4.dp).clip(shape)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = if (selected) 16.dp else 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (states.isNotEmpty()) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        // The tab's own label, so two tabs of the same name are not both "Close tab main".
        val closeLabel = strings("workspace_a11y_close_tab", "name" to label)
        IconButton(onClick = onClose, enabled = enabled,
            modifier = Modifier.size(48.dp).testTag(closeTag).semantics { contentDescription = closeLabel }) {
            Text("×", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The window after its last tab closed: what to do next instead of a blank screen. New project, opening a file,
 * Open recent, the examples and the project list each start or reopen a session.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceEmptyState(
    modifier: Modifier, busy: Boolean, documents: DocumentActions?,
    onNewProject: () -> Unit, onOpenRecent: () -> Unit, onProjects: () -> Unit, onExample: (CircuitExample, String) -> Unit,
) {
    val strings = rememberLocalizer()
    var examples by remember { mutableStateOf(false) }
    Box(modifier.verticalScroll(rememberScrollState()).testTag("workspace-empty-state"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings("workspace_no_project_title"), style = MaterialTheme.typography.titleLarge, color = ChromeColors.Ink,
                modifier = Modifier.semantics { heading() })
            Text(strings("workspace_no_project_explanation"), style = MaterialTheme.typography.bodyMedium, color = ChromeColors.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
            val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            Button(onClick = onNewProject, enabled = !busy, modifier = wide.testTag("workspace-empty-new")) {
                Text(strings(Messages.WorkspaceNewProject))
            }
            if (documents != null) {
                OutlinedButton(onClick = { documents.open(DocumentOpenMode.IMPORT_COPY) }, enabled = !busy,
                    modifier = wide.testTag("workspace-empty-import")) { Text(strings(Messages.ActionImportCopy)) }
                if (documents.supportsEditInPlace) {
                    OutlinedButton(onClick = { documents.open(DocumentOpenMode.EDIT_IN_PLACE) }, enabled = !busy,
                        modifier = wide.testTag("workspace-empty-edit-external")) { Text(strings(Messages.ActionEditExternal)) }
                }
                OutlinedButton(onClick = onOpenRecent, enabled = !busy, modifier = wide.testTag("workspace-empty-recent")) {
                    Text(strings(Messages.WorkspaceOpenRecent))
                }
            }
            OutlinedButton(onClick = { examples = true }, enabled = !busy, modifier = wide.testTag("workspace-empty-examples")) {
                Text(strings("help_examples"))
            }
            TextButton(onClick = onProjects, enabled = !busy, modifier = wide.testTag("workspace-empty-projects")) {
                Text(strings(Messages.WorkspaceProjects))
            }
        }
    }
    if (examples) {
        AppAlertDialog(onDismissRequest = { examples = false }, title = { Text(strings("help_examples")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (example in HelpContent.examples) {
                    val title = strings(example.title)
                    TextButton(onClick = {
                        examples = false
                        CircuitExamples.find(example.id)?.let { onExample(it, title) }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workspace-empty-example-${example.id}")) {
                        Text(title, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { examples = false }) { Text(strings(Messages.ActionCancel)) } })
    }
}

private fun openedAgo(strings: Localizer, openedAtMillis: Long?): String {
    val minutes = openedAtMillis?.let { (Clock.System.now().toEpochMilliseconds() - it) / 60_000 }?.coerceAtLeast(0) ?: 0
    return when {
        minutes < 1 -> strings("workspace_opened_just_now")
        minutes < 60 -> strings.plural("workspace_opened_minutes_ago", minutes)
        else -> strings.plural("workspace_opened_hours_ago", minutes / 60)
    }
}

/**
 * A failed write or a conflict with another window, in the editor itself rather than only in the Projects sheet.
 * It stays until the save succeeds or the project is reloaded, and is announced politely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceSaveBanner(
    indicator: WorkspaceSaveIndicator, notice: LocalizedText?, busy: Boolean,
    onRetry: () -> Unit, onSaveCopy: () -> Unit, onReload: () -> Unit,
) {
    val strings = rememberLocalizer()
    Column(Modifier.fillMaxWidth().background(ChromeColors.Warning).testTag("workspace-save-banner")
        .padding(start = 12.dp, end = 4.dp, top = 6.dp)) {
        Text(notice?.let { strings(it) } ?: strings("workspace_save_failed"), fontSize = 13.sp, color = ChromeColors.Ink,
            modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Retrying cannot resolve a conflict: reload the other window's revision or keep this one as a copy.
            if (indicator == WorkspaceSaveIndicator.CONFLICT) {
                TextButton(onClick = onReload, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("workspace_banner_reload")) }
            } else {
                TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("workspace_banner_retry")) }
            }
            TextButton(onClick = onSaveCopy, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("workspace_banner_save_copy")) }
        }
    }
}

@Composable
private fun WorkspaceNoticeRow(notice: WorkspaceNotice, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    val warning = notice.severity == WorkspaceNoticeSeverity.WARNING
    val color = when (notice.severity) {
        WorkspaceNoticeSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        WorkspaceNoticeSeverity.WARNING -> ChromeColors.Ink
        WorkspaceNoticeSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        .then(if (warning) Modifier.background(ChromeColors.Warning) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Text(strings(notice.text), Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = color, style = MaterialTheme.typography.bodyMedium)
        val dismiss = strings("workspace_dismiss_notice")
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).semantics { contentDescription = dismiss }) {
            Text("×", color = color)
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
