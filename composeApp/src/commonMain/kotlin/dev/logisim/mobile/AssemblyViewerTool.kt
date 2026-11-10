package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.AssemblyListing
import dev.logisim.mobile.core.assemblyRegisterAddress
import dev.logisim.mobile.core.assemblyRegisters
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ASSEMBLY_VIEWER_ID = "assembly-viewer"

internal fun assemblyViewerEditorTool(assets: DocumentAssetActions): EditorTool {
    val session = AssemblyViewerSession()
    return EditorTool(ASSEMBLY_VIEWER_ID, "Assembly Viewer", titleText = message("assembly_viewer_title")) { context ->
        AssemblyViewer(context, assets, session)
    }
}

private class AssemblyViewerSession {
    var opened by mutableStateOf<OpenedAssemblyListing?>(null)
    var selection by mutableStateOf<AssemblyRegisterSelection?>(null)
}

private data class OpenedAssemblyListing(val documentIdentity: Long, val name: String, val listing: AssemblyListing)
private data class AssemblyRegisterSelection(val documentIdentity: Long, val circuitId: String, val componentId: String)

@Composable
private fun AssemblyViewer(context: EditorToolContext, assets: DocumentAssetActions, session: AssemblyViewerSession) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    // Evolution lists the labeled registers by label and follows the first one until another is chosen.
    val registers = remember(context.document) { context.document.assemblyRegisters() }
    val registerId = session.selection?.takeIf {
        it.documentIdentity == context.documentIdentity && it.circuitId == context.document.id
    }?.componentId
    val register = registers.firstOrNull { it.id == registerId } ?: registers.firstOrNull()
    val opened = session.opened?.takeIf { it.documentIdentity == context.documentIdentity }
    var failure by remember(context.documentIdentity) { mutableStateOf<LocalizedText?>(null) }
    var opening by remember(context.documentIdentity) { mutableStateOf(false) }
    val listing = opened?.listing
    val address = register?.let { context.simulation.assemblyRegisterAddress(it.id) }
    val activeLine = listing?.lineAt(address)
    val listState = rememberLazyListState()
    LaunchedEffect(listing, activeLine) {
        if (activeLine != null) listState.scrollToItem(activeLine)
        else if (listing != null) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !opening, onClick = {
                opening = true
                assets.open(listOf("text/plain", "application/octet-stream"), AssemblyListing.MAX_BYTES) { result ->
                    scope.launch {
                        if (!context.isCurrentEditorContext()) {
                            opening = false
                            return@launch
                        }
                        when (result) {
                            is AssetOpenResult.Opened -> {
                                val parsed = withContext(Dispatchers.Default) { AssemblyListing.read(result.asset.bytes) }
                                if (context.isCurrentEditorContext()) {
                                    if (parsed != null) {
                                        session.opened = OpenedAssemblyListing(context.documentIdentity, result.asset.name, parsed)
                                        failure = null
                                    } else failure = message("assembly_viewer_invalid")
                                }
                            }
                            is AssetOpenResult.Failure -> failure = result.notice
                            AssetOpenResult.Cancelled -> Unit
                        }
                        opening = false
                    }
                }
            }) { Text(strings("assembly_viewer_open")) }
            OutlinedButton(
                onClick = context.tickSimulation,
                enabled = register != null && !opening && !context.isSimulationRunning && context.projectExports.access.writable,
            ) {
                Text(strings("menu_tick_once"))
            }
        }

        if (registers.isEmpty()) Text(strings("assembly_viewer_no_labeled_register"))
        else {
            Text(strings("assembly_viewer_register"), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in registers) FilterChip(
                    selected = item.id == register?.id,
                    onClick = {
                        session.selection = AssemblyRegisterSelection(context.documentIdentity, context.document.id, item.id)
                    },
                    label = { Text(item.label) },
                )
            }
        }
        failure?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
        opened?.let { Text(it.name, style = MaterialTheme.typography.labelMedium) }
        if (register != null && listing != null) {
            val status = when {
                address == null -> strings("assembly_viewer_undefined")
                activeLine == null -> strings("assembly_viewer_address_missing", "address" to address.toString(16).uppercase())
                else -> strings("assembly_viewer_address", "address" to address.toString(16).uppercase())
            }
            Text(status)
        }

        if (listing != null) LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = listState) {
            itemsIndexed(listing.lines) { index, line ->
                val active = index == activeLine
                Text(
                    line,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            .semantics { selected = true } else Modifier)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
