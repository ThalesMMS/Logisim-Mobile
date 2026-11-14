package dev.logisim.mobile

import androidx.compose.runtime.Composable
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.CreationDefaults
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
    /** Opening the tool pauses a running simulation; tools that watch it run, such as Signal recording, do not. */
    val pausesSimulation: Boolean = true,
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
    /**
     * The editor's Run, Pause and clock Tick (a half or a full cycle), as the simulation toolbar does them, for tools
     * that drive the simulation they show (C57).
     */
    val startSimulation: () -> Boolean = { false },
    /** Whether Run and Tick can act, as the toolbar enables them: a writable document that accepts interaction. */
    val simulationControlsEnabled: Boolean = true,
    val pauseSimulation: () -> Unit = {},
    val stepClock: (fullCycle: Boolean) -> Unit = { fullCycle -> if (!fullCycle) tickSimulation() },
    /** Opens the value dialog of an input pin, for the Explorer's Set value action (X0); false when it cannot open. */
    val editInputValue: ((String) -> Boolean)? = null,
    /** Toggles one bit of an input pin, for the Explorer's Toggle bit action (X0). */
    val toggleInputBit: ((String, Int) -> Boolean)? = null,
    /** Whether the inspector shows the I/O controls of a component, for Open controls (X0); false says why. */
    val canOpenIoControls: ((String) -> Boolean)? = null,
    /** The creation defaults a new component takes, for the Explorer's Insert suggestions (X1). */
    val creationDefaults: CreationDefaults = CreationDefaults(),
    /** A token per circuit id that survives renames but not deleting a circuit and reusing its id. */
    val circuitLifetime: (String) -> Long = { 0L },
    /**
     * The grid point at the centre of the canvas in view, where new annotations start; null before it is measured (C39).
     * A function, read when a panel needs it, so panning does not recompose the editor and every tool.
     */
    val visibleCenter: () -> GridPoint? = { null },
) {
    internal var startJarEditor: (suspend (JarEditorLaunch) -> Unit)? = null
}

fun EditorToolContext.exportProjectGraph(action: () -> Unit): Boolean = projectExports.export(showNotice, action)
