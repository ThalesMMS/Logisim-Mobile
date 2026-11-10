package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircBundleCodec
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircXmlEncoding
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.ProjectCircuitLibraries
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun libraryLabel(library: String): String = when (library) {
    "#Wiring" -> Messages.LibraryWiring
    "#Gates" -> Messages.LibraryGates
    "#Arithmetic" -> Messages.LibraryArithmetic
    "#Memory" -> Messages.LibraryMemory
    "#I/O" -> Messages.LibraryIo
    "#Base" -> Messages.LibraryAnnotations
    else -> library.removePrefix("#")
}

internal fun registryComponentGroups(registry: ComponentRegistry, project: CircuitProject): List<Pair<String, List<ComponentKind>>> =
    registry.definitions.filter { ProjectBuiltinLibraries.includes(project, it.library) }
        .groupBy { it.library }.map { (library, definitions) -> libraryLabel(library) to definitions.map { it.kind } }

internal fun readActiveProjectLibrary(asset: DocumentAsset, registry: ComponentRegistry): CircProjectImportResult = try {
    val imported = when {
        asset.name.endsWith(".lsebdl", ignoreCase = true) -> when (val result = preparePortableBundle(asset, registry)) {
            is PortableProjectReadResult.Ready -> CircProjectImportResult.Success(result.copy.project)
            is PortableProjectReadResult.Failure -> CircProjectImportResult.Failure(result.notices)
        }
        asset.name.endsWith(".circ", ignoreCase = true) -> {
            requireLocalized(asset.size <= CircBundleCodec.MAX_FILE_BYTES) { message("portable_directory_file_too_large") }
            CircCodec.readProject(CircXmlEncoding.decode(asset.bytes), registry)
        }
        else -> CircProjectImportResult.Failure(listOf(message("active_library_file_type")))
    }
    if (imported is CircProjectImportResult.Success) {
        val missing = imported.project.libraries.values.firstOrNull { description ->
            description.startsWith("file#") || description.startsWith("jar#") &&
                registry.definitions.none { it.library == description }
        }
        requireLocalized(missing == null) { message("active_library_dependency", "name" to missing) }
    }
    imported
} catch (failure: Exception) {
    CircProjectImportResult.Failure(listOf(portableProjectNotice(failure, "active_library_load_failed")))
}

@Composable
internal fun ActiveProjectLibrariesDialog(
    state: EditorState,
    assets: DocumentAssetActions?,
    onClose: () -> Unit,
) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
    var choosingBuiltin by remember { mutableStateOf(false) }
    val builtins = remember(state.componentRegistry) { ProjectBuiltinLibraries.available(state.componentRegistry) }
    val (loadedBuiltins, unloadedBuiltins) = builtins.partition { ProjectBuiltinLibraries.includes(state.project, it) }

    fun load(reloadId: String? = null) {
        if (assets == null || busy || !state.documentWritable) return
        val identity = state.documentIdentity
        val revision = state.revision
        val registry = state.componentRegistry
        busy = true
        notice = null
        try {
            assets.open(listOf("application/octet-stream"), PORTABLE_PROJECT_MAXIMUM_BYTES) { result ->
                if (!scope.isActive) return@open
                when (result) {
                    AssetOpenResult.Cancelled -> busy = false
                    is AssetOpenResult.Failure -> { busy = false; notice = result.notice }
                    is AssetOpenResult.Opened -> scope.launch {
                        val imported = withContext(Dispatchers.Default) {
                            readActiveProjectLibrary(result.asset, registry)
                        }
                        busy = false
                        if (state.documentIdentity != identity || state.revision != revision || state.componentRegistry !== registry) {
                            notice = message("portable_request_obsolete")
                        } else when (imported) {
                            is CircProjectImportResult.Failure -> notice = LocalizedText.join(imported.notices)
                            is CircProjectImportResult.Success -> {
                                val name = result.asset.name.substringBeforeLast('.').ifBlank { result.asset.name }
                                if (reloadId == null) state.attachLibrary(imported.project, name)
                                else state.reloadLibrary(reloadId, imported.project)
                                notice = state.statusText
                            }
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            busy = false
            notice = portableProjectNotice(failure, "active_library_load_failed")
        }
    }

    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags().testTag("active-project-libraries"),
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(strings("active_library_manage")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notice?.let { Text(strings(it)) }
                if (busy) LinearProgressIndicator()
                Text(strings("active_library_builtin_title"))
                TextButton(
                    onClick = { choosingBuiltin = !choosingBuiltin },
                    enabled = state.documentWritable && !busy && unloadedBuiltins.isNotEmpty(),
                    modifier = Modifier.testTag("active-library-show-available"),
                ) { Text(strings(if (choosingBuiltin) "active_library_hide_builtin" else "active_library_show_builtin")) }
                if (choosingBuiltin) for (library in unloadedBuiltins) {
                    val name = message(libraryLabel(library))
                    TextButton(onClick = {
                        state.setBuiltinLibrary(library, loaded = true, name)
                        notice = state.statusText
                        if (unloadedBuiltins.size == 1) choosingBuiltin = false
                    }, enabled = state.documentWritable && !busy, modifier = Modifier.testTag("active-library-add-${library.removePrefix("#")}")) {
                        Text(strings(message("active_library_add_builtin", "name" to name)))
                    }
                }
                for (library in loadedBuiltins) {
                    val name = message(libraryLabel(library))
                    TextButton(onClick = {
                        state.setBuiltinLibrary(library, loaded = false, name)
                        notice = state.statusText
                    }, enabled = state.documentWritable && !busy, modifier = Modifier.testTag("active-library-remove-${library.removePrefix("#")}")) {
                        Text(strings(message("active_library_unload", "name" to name)))
                    }
                }
                Text(strings("active_library_description"))
                for (library in ProjectCircuitLibraries.list(state.project)) {
                    Text(library.name)
                    for (circuit in library.circuits) {
                        TextButton(enabled = state.documentWritable && !busy && state.document.id != circuit.id,
                            onClick = {
                                if (state.placeLibraryCircuit(circuit)) onClose() else notice = state.statusText
                            }) {
                            Text(strings(message(Messages.A11yInsertComponent, "name" to circuit.name)))
                        }
                    }
                    TextButton(enabled = assets != null && state.documentWritable && !busy,
                        onClick = { load(library.id) }) {
                        Text(strings(message("active_library_reload", "name" to library.name)))
                    }
                    TextButton(enabled = state.documentWritable && !busy, onClick = {
                        state.detachLibrary(library.id)
                        notice = state.statusText
                    }) { Text(strings(message("active_library_unload", "name" to library.name))) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { load() }, enabled = assets != null && state.documentWritable && !busy) {
                Text(strings("active_library_load"))
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(strings(Messages.ActionClose)) } },
    )
}
