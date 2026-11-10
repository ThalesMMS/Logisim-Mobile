package dev.logisim.mobile

import androidx.compose.runtime.Composable
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText

/** A tool owns its panel state; project edits pass through the editor's undo history. */
data class EditorTool(
    val id: String,
    val title: String,
    val onSimulation: ((CircuitProject, SimulationState) -> Unit)? = null,
    val titleText: LocalizedText = LocalizedText.literal(title),
    val runtime: @Composable (EditorToolContext) -> Unit = {},
    val requiresProjectGraph: Boolean = false,
    val onSimulationObservation: ((CircuitProject, SimulationState) -> Unit)? = null,
    val content: @Composable (EditorToolContext) -> Unit,
)

data class EditorLocation(
    val circuitId: String,
    val instancePath: List<String> = emptyList(),
    val componentIds: Set<String> = emptySet(),
    val wires: Set<WireSegment> = emptySet(),
)

data class EditorToolContext(
    val project: CircuitProject,
    val document: CircuitDocument,
    val simulation: SimulationState,
    val selectedComponents: List<CircuitComponent>,
    val applyProject: (CircuitProject) -> Unit,
    val applyDocument: (CircuitDocument) -> Unit,
    val selectCircuit: (String) -> Unit,
    val selectComponent: (String) -> Unit,
    val showStatus: (String) -> Unit,
    val projectSimulation: SimulationState = simulation,
    val documentIdentity: Long = 0L,
    val activeInstancePath: List<String> = emptyList(),
    val isCurrentEditorContext: () -> Boolean = { true },
    val isForeground: Boolean = true,
    val isSimulationRunning: Boolean = false,
    val openExample: (CircuitExample) -> Boolean = { false },
    val openTool: (String) -> Boolean = { false },
    val tickSimulation: () -> Unit = {},
    val injectEvents: (Map<String, List<ComponentEvent>>) -> Boolean = { false },
    val isComponentEventPending: (ComponentEvent) -> Boolean = { false },
    val pendingSimulationCommands: Int = 0,
    val sendComponentEvent: (String, ComponentEvent) -> Boolean = { _, _ -> false },
    val removeComponent: (String) -> Boolean = { false },
    val selectWire: (WireSegment) -> Unit = {},
    val removeWire: (WireSegment) -> Boolean = { false },
    val selectedWires: Set<WireSegment> = emptySet(),
    val navigate: (EditorLocation) -> Unit = {},
    val connect: (GridPoint, GridPoint) -> Boolean = { _, _ -> false },
    val removeSelection: () -> Unit = {},
    val openProperties: () -> Unit = {},
    val activateComponent: (String) -> Boolean = { false },
    val insertComponent: (ComponentKind, GridPoint) -> Boolean = { _, _ -> false },
    val projectExports: ProjectGraphExportPolicy = ProjectGraphExportPolicy(),
    val showNotice: (LocalizedText) -> Unit = { showStatus(it.resolve(AppLanguage.PORTUGUESE)) },
    val extensionDocumentXml: (() -> String)? = null,
    val commitProject: (CircuitProject, (Boolean) -> Unit) -> Unit = { next, completed ->
        if (!isCurrentEditorContext() || !projectExports.available || !projectExports.access.writable) completed(false)
        else { applyProject(next); completed(true) }
    },
    val printerView: Boolean = false,
    val closeTool: () -> Unit = {},
    val cancelComponentEvent: (ComponentEvent) -> Unit = {},
) {
    internal var startJarEditor: (suspend (JarEditorLaunch) -> Unit)? = null
}

fun EditorToolContext.exportProjectGraph(action: () -> Unit): Boolean = projectExports.export(showNotice, action)
