package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.localization.message

internal fun EditorState.accessibilityContext() = CircuitAccessibilityContext(
    documentIdentity, document.id, activeInstancePath.toList(),
)

/** Resolves indices against the rendered document and rejects callbacks from a replaced context. */
fun editorCircuitAccessibilityActions(context: EditorToolContext): CircuitAccessibilityActions = CircuitAccessibilityActions(
    selectComponent = { if (context.isCurrentEditorContext()) context.selectComponent(it) },
    openProperties = { id ->
        if (context.isCurrentEditorContext()) { context.selectComponent(id); context.openProperties() }
    },
    removeComponent = { context.isCurrentEditorContext() && context.removeComponent(it) },
    activateComponent = { context.isCurrentEditorContext() && context.activateComponent(it) },
    selectWire = { index ->
        if (context.isCurrentEditorContext()) context.document.wires.getOrNull(index)?.let(context.selectWire)
    },
    removeWire = { index ->
        context.isCurrentEditorContext() && (context.document.wires.getOrNull(index)?.let(context.removeWire) ?: false)
    },
    connect = { from, to -> context.isCurrentEditorContext() && context.connect(from, to) },
    insertComponent = { kind, point -> context.isCurrentEditorContext() && context.insertComponent(kind, point) },
    editInputValue = context.editInputValue?.let { edit -> { id -> context.isCurrentEditorContext() && edit(id) } },
    toggleInputBit = context.toggleInputBit?.let { toggle -> { id, bit -> context.isCurrentEditorContext() && toggle(id, bit) } },
    openControls = context.canOpenIoControls?.let { can ->
        { id ->
            (context.isCurrentEditorContext() && can(id)).also { if (it) { context.selectComponent(id); context.openProperties() } }
        }
    },
)

fun circuitAccessibilityEditorTool(registry: ComponentRegistry): EditorTool = EditorTool(
    id = "accessibility",
    title = "Circuit explorer",
    titleText = message("a11y_explorer_title"),
) { context ->
    AccessibleCircuitExplorer(
        document = context.document,
        simulation = context.simulation,
        registry = registry,
        context = CircuitAccessibilityContext(context.documentIdentity, context.document.id, context.activeInstancePath.toList()),
        selectedComponents = context.selectedComponents.mapTo(mutableSetOf()) { it.id },
        selectedWireIndices = context.document.wires.withIndex().filter { it.value in context.selectedWires }.mapTo(mutableSetOf()) { it.index },
        actions = editorCircuitAccessibilityActions(context),
        availableDefinitions = registry.definitions.filter { ProjectBuiltinLibraries.includes(context.project, it.library) },
        creationDefaults = context.creationDefaults,
    )
}
