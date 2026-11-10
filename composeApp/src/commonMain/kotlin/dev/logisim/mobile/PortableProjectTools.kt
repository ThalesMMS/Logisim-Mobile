package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Register this factory in AdditionalEditorTools after providing the workspace-copy host bridge. */
fun portableProjectEditorTool(
    assets: DocumentAssetActions,
    opener: PortableProjectCopyOpener,
    sourceArchiveForIdentity: suspend (Long) -> DocumentAsset?,
    directories: DirectoryAssetActions? = null,
    session: PortableProjectSourceSession = PortableProjectSourceSession(),
    registry: ComponentRegistry = ComponentCatalog.registry,
): EditorTool = EditorTool("portable-projects", "Portable projects", titleText = message("portable_title")) { context ->
    PortableProjectPanel(context, assets, opener, sourceArchiveForIdentity, directories, session, registry)
}

@Composable
fun PortableProjectPanel(
    context: EditorToolContext,
    assets: DocumentAssetActions,
    opener: PortableProjectCopyOpener,
    sourceArchiveForIdentity: suspend (Long) -> DocumentAsset?,
    directories: DirectoryAssetActions?,
    session: PortableProjectSourceSession,
    registry: ComponentRegistry = ComponentCatalog.registry,
) {
    val language = LocalAppLanguage.current
    val current by rememberUpdatedState(context)
    val scope = rememberCoroutineScope()
    var alive by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pendingDirectory by remember { mutableStateOf<DirectoryAssetGrant?>(null) }
    var pendingDirectoryIdentity by remember { mutableStateOf<Long?>(null) }
    var files by remember { mutableStateOf(emptyList<DirectoryCircuitFile>()) }
    var notices by remember { mutableStateOf(emptyList<LocalizedText>()) }
    var failed by remember { mutableStateOf(false) }
    fun report(values: List<LocalizedText>, error: Boolean = true) {
        if (!alive) return
        notices = values
        failed = error
        values.firstOrNull()?.let(current.showNotice)
    }
    fun valid(identity: Long) = alive && current.documentIdentity == identity
    fun adopt(result: PortableProjectReadResult, identity: Long) {
        if (!valid(identity)) {
            busy = false
            report(listOf(message("portable_request_obsolete")))
            return
        }
        when (result) {
            is PortableProjectReadResult.Failure -> {
                busy = false
                report(result.notices)
            }
            is PortableProjectReadResult.Ready -> try {
                opener.openCopy(result.copy, identity) { opened ->
                    if (opened is PortableProjectCopyOpenResult.Opened) session.opened(opened.documentIdentity, result.copy)
                    if (alive) {
                        busy = false
                        when (opened) {
                            is PortableProjectCopyOpenResult.Opened -> report(listOf(message("portable_opened", "name" to result.copy.name)), false)
                            is PortableProjectCopyOpenResult.Failure -> report(listOf(opened.notice))
                            PortableProjectCopyOpenResult.Cancelled -> Unit
                        }
                    }
                }
            } catch (failure: Exception) {
                busy = false
                report(listOf(portableProjectNotice(failure, "portable_copy_not_opened")))
            }
        }
    }
    fun openBundle() {
        val identity = current.documentIdentity
        busy = true
        notices = emptyList()
        try {
            assets.open(listOf(PORTABLE_PROJECT_MIME_TYPE, "application/zip", "application/octet-stream"),
                PORTABLE_PROJECT_MAXIMUM_BYTES) { result ->
                if (!alive) return@open
                when (result) {
                    is AssetOpenResult.Opened -> scope.launch {
                        try {
                            val prepared = withContext(Dispatchers.Default) { preparePortableBundle(result.asset, registry) }
                            adopt(prepared, identity)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            busy = false
                            report(listOf(portableProjectNotice(failure, "portable_bundle_open_failed")))
                        }
                    }
                    is AssetOpenResult.Failure -> { busy = false; report(listOf(result.notice)) }
                    AssetOpenResult.Cancelled -> { busy = false }
                }
            }
        } catch (failure: Exception) {
            busy = false
            report(listOf(portableProjectNotice(failure, "portable_bundle_open_failed")))
        }
    }
    fun openDirectory() {
        val actions = directories ?: return
        val identity = current.documentIdentity
        busy = true
        notices = emptyList()
        try {
            actions.open { result ->
                when (result) {
                    is DirectoryAssetOpenResult.Opened -> {
                        if (!alive) { result.grant.release(); return@open }
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            var retained = false
                            try {
                                val listed = withContext(Dispatchers.IO) { portableDirectoryFiles(result.grant) }
                                busy = false
                                when {
                                    !valid(identity) -> report(listOf(message("portable_request_obsolete")))
                                    listed.isEmpty() -> report(listOf(message("portable_directory_empty")))
                                    else -> { files = listed; pendingDirectory = result.grant; pendingDirectoryIdentity = identity; retained = true }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                busy = false
                                report(listOf(portableProjectNotice(failure, "portable_directory_open_failed")))
                            } finally {
                                if (!retained) result.grant.release()
                            }
                        }
                    }
                    is DirectoryAssetOpenResult.Failure -> { busy = false; report(listOf(result.notice)) }
                    DirectoryAssetOpenResult.Cancelled -> { busy = false }
                }
            }
        } catch (failure: Exception) {
            busy = false
            report(listOf(portableProjectNotice(failure, "portable_directory_open_failed")))
        }
    }
    fun importDirectoryFile(file: DirectoryCircuitFile) {
        val grant = pendingDirectory ?: return
        val identity = pendingDirectoryIdentity
        pendingDirectory = null
        pendingDirectoryIdentity = null
        files = emptyList()
        if (identity == null || !valid(identity)) {
            grant.release()
            report(listOf(message("portable_request_obsolete")))
            return
        }
        busy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val prepared = withContext(Dispatchers.IO) { preparePortableDirectory(grant, file.path, registry) }
                adopt(prepared, identity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                busy = false
                report(listOf(portableProjectNotice(failure, "portable_directory_open_failed")))
            } finally {
                grant.release()
            }
        }
    }
    fun export(share: Boolean) {
        val snapshot = current
        if (!snapshot.projectExports.ensureAvailable { report(listOf(it)) }) return
        val localArchive = session.archiveFor(snapshot.documentIdentity)
        busy = true
        notices = emptyList()
        scope.launch {
            try {
                val archive = localArchive ?: withContext(Dispatchers.IO) { sourceArchiveForIdentity(snapshot.documentIdentity) }
                if (!snapshot.projectExports.available) { busy = false; return@launch }
                val exported = withContext(Dispatchers.Default) { exportPortableProject(snapshot.project, archive, registry) }
                if (!snapshot.projectExports.available || !valid(snapshot.documentIdentity)) {
                    busy = false
                    return@launch
                }
                when (exported) {
                    is PortableProjectExportResult.Failure -> { busy = false; report(exported.notices) }
                    is PortableProjectExportResult.Ready -> {
                        val completed: (AssetActionResult) -> Unit = completed@{ result ->
                            if (alive) {
                                busy = false
                                if (!snapshot.projectExports.available) return@completed
                                when (result) {
                                    AssetActionResult.Completed -> if (valid(snapshot.documentIdentity))
                                        report(listOf(message(if (share) "portable_shared" else "portable_saved")), false)
                                    is AssetActionResult.Failure -> report(listOf(result.notice))
                                    AssetActionResult.Cancelled -> Unit
                                }
                            }
                        }
                        if (share) assets.share(exported.asset, completed) else assets.create(exported.asset, completed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                busy = false
                if (snapshot.projectExports.available)
                    report(listOf(portableProjectNotice(failure, "portable_bundle_export_failed")))
            }
        }
    }
    DisposableEffect(session) {
        onDispose {
            alive = false
            pendingDirectory?.release()
            pendingDirectory = null
            pendingDirectoryIdentity = null
        }
    }
    LaunchedEffect(context.documentIdentity) {
        session.retainOnly(context.documentIdentity)
        pendingDirectory?.release()
        pendingDirectory = null
        pendingDirectoryIdentity = null
        files = emptyList()
        notices = emptyList()
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message("portable_description").resolve(language))
        Button(onClick = ::openBundle, enabled = !busy && pendingDirectory == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(message("portable_open_bundle").resolve(language)) }
        if (directories != null) Button(onClick = ::openDirectory, enabled = !busy && pendingDirectory == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(message("portable_open_directory").resolve(language)) }
        pendingDirectory?.let { grant ->
            Text(message("portable_choose_main", "folder" to grant.name).resolve(language))
            for (file in files) Button(onClick = { importDirectoryFile(file) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(file.path) }
            TextButton(onClick = { grant.release(); pendingDirectory = null; pendingDirectoryIdentity = null; files = emptyList() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(message("portable_cancel_directory").resolve(language)) }
        }
        if (!context.projectExports.access.graphAvailable)
            Text(message("project_graph_unavailable").resolve(language))
        Button(onClick = { export(false) }, enabled = context.projectExports.available && !busy && pendingDirectory == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(message("portable_export").resolve(language)) }
        Button(onClick = { export(true) }, enabled = context.projectExports.available && !busy && pendingDirectory == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(message("portable_share").resolve(language)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notices.isNotEmpty()) Text(LocalizedText.join(notices).resolve(language),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
