package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.MemoryComponentState
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.MemoryImageFormat
import dev.logisim.mobile.core.MemoryImageImportResult
import dev.logisim.mobile.core.MemoryImageReplaced
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer

/** Identifies the memory whose edits are staged: a document, circuit, simulation instance and component. */
internal data class MemoryEditorDraftKey(
    val documentIdentity: Long,
    val circuitId: String,
    val instancePath: String,
    val componentId: String,
)

/**
 * Staged memory edits kept by the tool, like the trace tool's recorder, so closing the sheet (swipe, scrim or Close)
 * does not discard them. Apply, Cancel and Discard clear an entry; another document drops them all.
 */
internal class MemoryEditorDrafts {
    private val drafts = mutableMapOf<MemoryEditorDraftKey, MemoryEditorDraft>()

    operator fun get(key: MemoryEditorDraftKey): MemoryEditorDraft? = drafts[key]

    fun put(key: MemoryEditorDraftKey, draft: MemoryEditorDraft?) {
        if (draft == null) drafts.remove(key) else drafts[key] = draft
    }

    fun retainDocument(documentIdentity: Long) {
        drafts.keys.removeAll { it.documentIdentity != documentIdentity }
    }
}

fun memoryEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    files: MemoryEditorFileActions? = null,
): EditorTool {
    val drafts = MemoryEditorDrafts()
    return EditorTool("memory-editor", "Memory editor", titleText = message("memory_tool_title")) { context ->
        drafts.retainDocument(context.documentIdentity)
        val component = context.selectedComponents.singleOrNull()?.takeIf { isMemoryEditorComponent(it) }
        if (component == null) {
            MemoryComponentChooser(context)
        } else {
            MemoryToolContent(component, context, registry, files, drafts)
        }
    }
}

/** The tool's and the editor's name for a memory: its label, else its localized kind (never "DUAL_RAM"). */
internal fun memoryEditorTitle(component: CircuitComponent): LocalizedText =
    if (component.label.isBlank()) component.kind.localizedLabel() else LocalizedText.literal(component.label)

/** With nothing selected, lists the current circuit's memories; choosing one selects it and opens its contents. */
@Composable
private fun MemoryComponentChooser(context: EditorToolContext) {
    val strings = rememberLocalizer()
    val memories = context.document.components.filter(::isMemoryEditorComponent)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings(if (memories.isEmpty()) "memory_tool_none" else "memory_tool_choose"), style = MaterialTheme.typography.bodyMedium)
        memories.forEach { memory ->
            val location = strings("memory_tool_location", "x" to memory.position.x, "y" to memory.position.y)
            val detail = if (memory.label.isBlank()) location
                else strings("memory_tool_labelled_detail", "kind" to memory.kind.localizedLabel(), "location" to location)
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button) { context.selectComponent(memory.id) }
                .testTag("memory-tool-candidate-${memory.id}")
                .padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(strings(memoryEditorTitle(memory)), style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Ends with the panel; a file picked after the editor closed or moved to another memory is ignored. */
private class MemoryImportSession { var active = true }

@Composable
private fun MemoryToolContent(
    component: CircuitComponent,
    context: EditorToolContext,
    registry: ComponentRegistry,
    files: MemoryEditorFileActions?,
    drafts: MemoryEditorDrafts,
) {
    val strings = rememberLocalizer()
    val state = context.simulation.componentStates[component.id]
    val source = remember(component, state, registry) {
        runCatching { memoryEditorImage(component, context.simulation, registry) }
    }
    val draftKey = MemoryEditorDraftKey(context.documentIdentity, context.document.id, context.simulation.instancePath, component.id)
    val session = remember(draftKey) { MemoryImportSession() }
    DisposableEffect(session) { onDispose { session.active = false } }
    val latestContext by rememberUpdatedState(context)
    var importedImage by remember(draftKey) { mutableStateOf<MemoryImageImportResult.Success?>(null) }
    var importFailure by remember(draftKey) { mutableStateOf<LocalizedText?>(null) }
    var needsFormat by remember(draftKey) { mutableStateOf<MemoryImportOutcome.NeedsFormat?>(null) }
    var needsFormatError by remember(draftKey) { mutableStateOf<LocalizedText?>(null) }
    val image = source.getOrNull()
    if (image == null) {
        Text(strings(source.exceptionOrNull()?.localizedDescription(message("memory_tool_unavailable")) ?: message("memory_tool_unavailable")), modifier = Modifier.padding(12.dp))
        return
    }
    // A different memory, circuit, instance or document starts a fresh panel (its own model and staged edits).
    key(draftKey) { MemoryEditorPanel(image, persistent = component.kind == ComponentKind.ROM,
        hasLineOverflow = MemoryComponents.hasLineOverflow(state),
        title = strings(memoryEditorTitle(component)),
        onApply = { replacement, clearLineOverflow, stagedImport ->
            try {
                if (applyMemoryEditorImage(context, component.id, replacement, clearLineOverflow, stagedImport)) {
                    drafts.put(draftKey, null)
                    context.showNotice(message(if (component.kind == ComponentKind.ROM) "memory_tool_rom_updated" else "memory_tool_ram_updated"))
                    context.closeTool()
                    true
                } else {
                    context.showNotice(message("memory_tool_not_applied"))
                    false
                }
            } catch (error: IllegalArgumentException) {
                context.showNotice(error.localizedDescription(message("memory_tool_invalid")))
                false
            }
        },
        onDismiss = {
            drafts.put(draftKey, null)
            importedImage = null
            context.closeTool()
        },
        importedImage = importedImage,
        onImportedImageConsumed = { importedImage = null },
        importFailure = importFailure,
        onImportFailureConsumed = { importFailure = null },
        onImportFile = files?.let { actions -> {
            actions.importImageWithChooser(component) { outcome ->
                if (!session.active || !latestContext.isCurrentEditorContext()) return@importImageWithChooser
                when (outcome) {
                    is MemoryImportOutcome.Imported -> importedImage = outcome.result
                    is MemoryImportOutcome.NeedsFormat -> { needsFormatError = null; needsFormat = outcome }
                    is MemoryImportOutcome.Failed -> importFailure = outcome.notice
                    MemoryImportOutcome.Cancelled -> Unit
                }
            }
        } },
        onExportFile = files?.let { actions -> { format, contents -> actions.exportImage(component, format, contents) } },
        draft = drafts[draftKey],
        onDraftChange = { drafts.put(draftKey, it) },
    ) }
    needsFormat?.let { pending ->
        MemoryFormatChoiceDialog(pending.diagnostics.map { it.notice }, initial = initialChoice(pending), error = needsFormatError,
            onLoad = { format ->
                files?.decodeImage(component, pending.bytes, format) { result ->
                    if (!session.active || needsFormat !== pending || !latestContext.isCurrentEditorContext()) return@decodeImage
                    when (result) {
                        is MemoryImageImportResult.Success -> { needsFormat = null; importedImage = result }
                        is MemoryImageImportResult.Failure -> needsFormatError = LocalizedText.join(result.diagnostics.map { it.notice })
                    }
                }
            },
            onDismiss = { needsFormat = null })
    }
}

/**
 * Binary-looking data starts on Binary, and so does an unsupported header, as Evolution's HexFormatDialog starts on
 * Binary for a header it does not recognize (a text format would read that header again). Headerless text starts on
 * v3.0 hex words, addressed when it has address colons.
 */
internal fun initialChoice(pending: MemoryImportOutcome.NeedsFormat): MemoryImageFormat = when {
    pending.diagnostics.any { it.detail.key == Messages.MemoryCodecInvalidUtf8 } -> MemoryImageFormat.BINARY_BIG
    MemoryImageImportResult.Failure(pending.diagnostics).unsupportedHeader() -> MemoryImageFormat.BINARY_BIG
    pending.bytes.any { it == ':'.code.toByte() } -> MemoryImageFormat.HEX_WORDS_ADDRESSED
    else -> MemoryImageFormat.HEX_WORDS_PLAIN
}

internal fun isMemoryEditorComponent(component: CircuitComponent): Boolean =
    component.kind == ComponentKind.ROM || component.kind == ComponentKind.RAM || component.kind == MemoryComponents.DualRam

internal fun memoryEditorImage(component: CircuitComponent, simulation: SimulationState, registry: ComponentRegistry): MemoryImage {
    requireLocalized(isMemoryEditorComponent(component)) { message("memory_tool_select") }
    val configuration = MemoryComponents.configuration(component)
    val current = simulation.componentStates[component.id]
    val state = if (configuration.readOnly &&
        (current as? MemoryComponentState)?.sourceContents != component.attributes["contents"].orEmpty()) {
        val definition = registry.requireDefinition(component.kind)
        definition.behavior?.initialState(component, registry.attributes(component))
    } else current
    return state?.let(MemoryComponents::image)
        ?: throw dev.logisim.mobile.core.localization.LocalizedArgumentException(message("memory_tool_session_unavailable"))
}

internal fun applyMemoryEditorImage(
    context: EditorToolContext,
    componentId: String,
    image: MemoryImage,
    clearLineOverflow: Boolean = false,
    importedImage: MemoryImageImportResult.Success? = null,
): Boolean {
    val component = context.document.components.firstOrNull { it.id == componentId } ?: return false
    requireLocalized(isMemoryEditorComponent(component)) { message("memory_tool_not_memory") }
    val configuration = MemoryComponents.configuration(component)
    requireLocalized(image.addressBits == configuration.addressBits && image.dataBits == configuration.dataBits) {
        message("memory_tool_dimensions")
    }
    if (configuration.readOnly) {
        val updated = component.copy(attributes = component.attributes + ("contents" to MemoryImageCodec.encodeCirc(image)))
        context.applyDocument(context.document.copy(components = context.document.components.map {
            if (it.id == componentId) updated else it
        }))
        return true
    }
    return context.sendComponentEvent(componentId, MemoryImageReplaced(image, clearLineOverflow, importedImage))
}
