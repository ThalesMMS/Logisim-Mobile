package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer

fun memoryEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    files: MemoryEditorFileActions? = null,
): EditorTool = EditorTool("memory-editor", "Memory editor", titleText = message("memory_tool_title")) { context ->
    val strings = rememberLocalizer()
    val component = context.selectedComponents.singleOrNull()?.takeIf { isMemoryEditorComponent(it) }
    if (component == null) {
        Text(strings("memory_tool_select"), modifier = Modifier.padding(12.dp))
    } else {
        MemoryToolContent(component, context, registry, files)
    }
}

@Composable
private fun MemoryToolContent(
    component: CircuitComponent,
    context: EditorToolContext,
    registry: ComponentRegistry,
    files: MemoryEditorFileActions?,
) {
    val strings = rememberLocalizer()
    val state = context.simulation.componentStates[component.id]
    val source = remember(component, state, registry) {
        runCatching { memoryEditorImage(component, context.simulation, registry) }
    }
    var editing by remember(context.documentIdentity, context.document.id, context.simulation.instancePath, component.id) { mutableStateOf(true) }
    var importedImage by remember(context.documentIdentity, component.id) { mutableStateOf<MemoryImageImportResult.Success?>(null) }
    val image = source.getOrNull()
    if (image == null) {
        Text(strings(source.exceptionOrNull()?.localizedDescription(message("memory_tool_unavailable")) ?: message("memory_tool_unavailable")), modifier = Modifier.padding(12.dp))
    } else if (!editing) {
        Column(Modifier.padding(12.dp)) {
            Text(component.label.ifBlank { component.kind.name })
            TextButton(onClick = { editing = true }) { Text(strings("memory_tool_edit")) }
        }
    } else {
        MemoryEditorPanel(image, persistent = component.kind == ComponentKind.ROM,
            hasLineOverflow = MemoryComponents.hasLineOverflow(state),
            onApply = { replacement, clearLineOverflow, stagedImport ->
                try {
                    if (applyMemoryEditorImage(context, component.id, replacement, clearLineOverflow, stagedImport)) {
                        editing = false
                        context.showNotice(message(if (component.kind == ComponentKind.ROM) "memory_tool_rom_updated" else "memory_tool_ram_updated"))
                    } else context.showNotice(message("memory_tool_not_applied"))
                } catch (error: IllegalArgumentException) {
                    context.showNotice(error.localizedDescription(message("memory_tool_invalid")))
                }
            },
            onDismiss = { importedImage = null; editing = false }, importedImage = importedImage,
            onImportedImageConsumed = { importedImage = null },
            onImportFile = files?.let { actions -> { format ->
                actions.importImage(component, format) { result ->
                    if (!editing) return@importImage
                    when (result) {
                        is MemoryImageImportResult.Success -> importedImage = result
                        is MemoryImageImportResult.Failure -> context.showNotice(LocalizedText.join(result.diagnostics.map { it.notice }))
                        null -> Unit
                    }
                }
            } },
            onExportFile = files?.let { actions -> { format, contents -> actions.exportImage(component, format, contents) } })
    }
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
