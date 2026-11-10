package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed as pointerAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as pointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitNetworks
import dev.logisim.mobile.core.CircuitTopology
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.WidthIncompatibility
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.widthIncompatibilities
import dev.logisim.mobile.core.wireSet
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.PinPoke
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.ComponentMenuEntry
import dev.logisim.mobile.core.ComponentMenu
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoInteractions
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.KeyboardCommand
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.MemoryEditResult
import dev.logisim.mobile.core.MemoryEditorModel
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseBinding
import dev.logisim.mobile.core.ProjectMouseAction
import dev.logisim.mobile.core.ProjectMouseEditTool
import dev.logisim.mobile.core.SpatialIndex
import dev.logisim.mobile.core.SpatialItem
import dev.logisim.mobile.core.SpatialRect
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.orthogonalWireRoute
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import dev.logisim.mobile.core.wireBacktrack
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CanvasAccent = Color(0xFF1673C8)
private val CanvasWireRemoval = Color(0xFFB3261E)

@Composable
internal fun CircuitCanvas(
    document: CircuitDocument,
    simulation: SimulationState,
    selectedId: String?,
    pendingWire: GridPoint?,
    wirePoke: WirePoke?,
    onWirePoke: (WireSegment, GridPoint) -> Unit,
    onDismissWirePoke: () -> Unit,
    mode: EditorMode,
    modifier: Modifier = Modifier,
    onCanvasTap: (GridPoint) -> Unit,
    viewportReset: Int = 0,
    selectedIds: Set<String> = selectedId?.let { setOf(it) } ?: emptySet(),
    selectedWires: Set<dev.logisim.mobile.core.WireSegment> = emptySet(),
    viewportState: CanvasViewportState,
    onViewportChange: (CanvasViewportState) -> Unit,
    fitSelection: Boolean = false,
    onSelectArea: (GridPoint, GridPoint, Boolean) -> Unit,
    onSelectComponent: (String, Boolean) -> Unit,
    onSelectWire: (WireSegment, Boolean) -> Unit,
    onMoveSelection: (GridPoint, Boolean) -> Unit,
    keepConnectionsWhenMoving: Boolean,
    onCommand: (CanvasCommand) -> Unit,
    onTogglePlacementMatrix: () -> Unit,
    onOpenPlacementAutoLabel: () -> Boolean = { false },
    onToolShortcut: (EditorShortcut) -> Boolean = { false },
    canPaste: Boolean = false,
    registry: ComponentRegistry = ComponentCatalog.registry,
    showGrid: Boolean = true,
    snapToGrid: Boolean = true,
    highContrastSignals: Boolean = false,
    signalRadix: String? = null,
    wirePokePrimaryRadix: WirePokeRadix,
    wirePokeSecondaryRadix: WirePokeRadix?,
    onComponentEvent: (String, ComponentEvent) -> Boolean = { _, _ -> false },
    accessibilityActions: CircuitAccessibilityActions? = null,
    accessibilityContext: CircuitAccessibilityContext = CircuitAccessibilityContext(0L, document.id),
    isSimulationRunning: Boolean = false,
    onAccessibilityNotice: (LocalizedText) -> Unit = {},
    onViewSubcircuit: (String) -> Unit,
    onSplitWire: (GridPoint) -> Boolean = { _ -> false },
    onMoveWireCorner: (GridPoint, GridPoint) -> Boolean = { _, _ -> false },
    gateShape: EditorGateShape = EditorGateShape.ANSI,
    shortcutScheme: EditorShortcutScheme = EditorShortcutScheme.PLATFORM,
    shortcutOverrides: Map<EditorShortcut, EditorShortcutBinding> = emptyMap(),
    childCircuit: (String) -> CircuitDocument? = { null },
    printerView: Boolean = false,
    mouseBindings: Map<ProjectMouseGesture, ProjectMouseBinding> = emptyMap(),
    onMappedTap: (GridPoint, EditorMode) -> Unit = { _, _ -> },
    onMappedPlacement: (CircuitComponent, GridPoint, Boolean) -> Unit = { _, _, _ -> },
    showZoomControls: Boolean = true,
    onDrawEditToolWire: (GridPoint, GridPoint, Boolean) -> Unit = { _, _, _ -> },
    pendingPaste: PendingPaste? = null,
    onMovePastePreview: (GridPoint) -> Unit = {},
    onConfirmPaste: () -> Unit = {},
    onTextToolTap: (TextToolTarget, CircuitComponent?) -> Unit = { _, _ -> },
    editedMemoryWord: CanvasMemoryWord? = null,
    memoryWindows: Map<String, MemoryCanvasWindow> = emptyMap(),
    onMemoryWindowsChanged: (Map<String, MemoryCanvasWindow>) -> Unit = {},
    onMemoryAddressChanged: (String, Int) -> Unit = { _, _ -> },
    onMemoryEdit: ((CanvasMemoryEdit) -> Unit)? = null,
    onToolFacing: (Direction) -> Boolean = { false },
    projectPlacementTool: CircuitComponent? = null,
    onKeyboardPoke: (String) -> Unit = {},
    onPokePin: ((String, Int?) -> Unit)? = null,
    onComponentMenu: (ComponentMenuItem, String) -> Unit = { _, _ -> },
    onRemoveWire: (WireSegment) -> Unit = {},
    canEditCircuit: Boolean = true,
    onDismissKeyboardPoke: () -> Unit = {},
    pokedKeyboardId: () -> String? = { null },
    sequentialHexTarget: SequentialPokeTarget? = null,
    onSequentialHexEdit: ((SequentialPokeTarget) -> Unit)? = null,
    measuredTicksPerSecond: Double? = null,
    projectStates: Map<String, SimulationState> = simulation.circuitStates,
    showPlacementPreview: Boolean = false,
    placementPrototype: CircuitComponent? = null,
    colors: Map<EditorColor, Int> = emptyMap(),
    attributeHalo: Boolean = true,
    componentTips: Boolean = true,
    pinDotDiameter: Int = EditorPinAppearance.DOT_SMALL.diameter,
    valueCharacters: ValueCharacters = ValueCharacters.Default,
) {
    val strings = rememberLocalizer()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val latestViewport by rememberUpdatedState(viewportState)
    val updateViewport by rememberUpdatedState(onViewportChange)
    val view = viewportState.view
    val activeColors = if (printerView) emptyMap() else colors
    val darkTheme = LocalEditorDarkTheme.current
    val canvasBackground = activeColors.color(EditorColor.CANVAS, Color.White)
    val background = if (showGrid) activeColors.color(EditorColor.GRID_BACKGROUND, canvasBackground) else canvasBackground
    // Wires that no terminal gives a width are Value.NIL in Evolution and use NIL_COLOR.
    var floatingPoints by remember(document, registry) { mutableStateOf(emptySet<GridPoint>()) }
    LaunchedEffect(document, registry) {
        floatingPoints = withContext(Dispatchers.Default) { CircuitNetworks(document, registry).widthlessPoints() }
    }
    // Circuit.getWidthIncompatibilityData() and PokeTool's highlighted wire set, from the same connectivity.
    var topology by remember(document, registry) { mutableStateOf<CircuitTopology?>(null) }
    LaunchedEffect(document, registry) {
        topology = withContext(Dispatchers.Default) { runCatching { CircuitTopology.elaborate(document, registry) }.getOrNull() }
    }
    val widthErrors: List<WidthIncompatibility> = remember(topology) { topology?.widthIncompatibilities().orEmpty() }
    val widthErrorPoints = remember(widthErrors) { widthErrors.flatMapTo(mutableSetOf()) { it.netPoints } }
    val highlightedWires = remember(topology, wirePoke, printerView) {
        val poked = wirePoke?.takeUnless { printerView }?.wire
        if (poked == null) emptySet() else topology?.wireSet(poked) ?: setOf(poked)
    }
    var handledFitRequest by remember { mutableStateOf(viewportReset) }
    var dragPreview by remember { mutableStateOf<SelectionDragPreview?>(null) }
    var selectionBox by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var contextMenu by remember(accessibilityContext) { mutableStateOf<CanvasContextMenu?>(null) }
    // Pointer hover position for component tips (Canvas.getToolTipText); touch shows them in the long-press menu.
    var tipHover by remember(accessibilityContext) { mutableStateOf<Offset?>(null) }
    var shownTip by remember(accessibilityContext) { mutableStateOf<Pair<Offset, LocalizedText>?>(null) }
    LaunchedEffect(tipHover, componentTips, document, printerView) {
        shownTip = null
        val position = tipHover ?: return@LaunchedEffect
        if (!componentTips || printerView) return@LaunchedEffect
        kotlinx.coroutines.delay(CanvasTipDelayMillis)
        val world = latestViewport.view.world(position)
        shownTip = document.componentTipAt(world, registry)?.text()?.let { position to it }
    }
    var wireCornerPreview by remember { mutableStateOf<Pair<GridPoint, GridPoint>?>(null) }
    var editWirePreview by remember { mutableStateOf<WireDragPreview?>(null) }
    var activeGesture by remember { mutableStateOf<CanvasGestureMachine?>(null) }
    var activeIoPointer by remember { mutableStateOf<ActiveIoPointer?>(null) }
    val currentComponentEventSender by rememberUpdatedState(onComponentEvent)
    val currentWirePoke by rememberUpdatedState(onWirePoke)
    val currentDismissWirePoke by rememberUpdatedState(onDismissWirePoke)
    val currentMouseBindings by rememberUpdatedState(mouseBindings)
    val currentMappedTap by rememberUpdatedState(onMappedTap)
    val currentMappedPlacement by rememberUpdatedState(onMappedPlacement)
    val currentPlacementPrototype by rememberUpdatedState(placementPrototype)
    val currentDrawEditToolWire by rememberUpdatedState(onDrawEditToolWire)
    val currentTextToolTap by rememberUpdatedState(onTextToolTap)
    val currentMemoryEdit by rememberUpdatedState(onMemoryEdit)
    val currentSimulation by rememberUpdatedState(simulation)
    val currentMemoryAddressChanged by rememberUpdatedState(onMemoryAddressChanged)
    val currentKeyboardPoke by rememberUpdatedState(onKeyboardPoke)
    val currentPokePin by rememberUpdatedState(onPokePin)
    val dismissKeyboardPoke by rememberUpdatedState(onDismissKeyboardPoke)
    val memoryComponents = remember(document.components, registry) {
        document.components.mapNotNull { component ->
            memoryDisplayGrid(component, registry)?.let { component.id to (component to it) }
        }.toMap()
    }
    val observedMemoryWindows = remember(memoryComponents, memoryWindows, simulation, registry) {
        memoryComponents.mapValues { (id, memory) ->
            val (component, grid) = memory
            (memoryWindows[id] ?: MemoryCanvasWindow()).update(grid, memoryActiveAddress(component, simulation, registry))
        }
    }
    if (memoryWindows != observedMemoryWindows) SideEffect { onMemoryWindowsChanged(observedMemoryWindows) }
    val memoryGrids = remember(memoryComponents, observedMemoryWindows) {
        memoryComponents.mapValues { (id, memory) -> memory.second.at(observedMemoryWindows.getValue(id).firstAddress) }
    }
    val currentMemoryGrids by rememberUpdatedState(memoryGrids)
    val currentSequentialHexEdit by rememberUpdatedState(onSequentialHexEdit)
    var hadFocus by remember { mutableStateOf(false) }
    val currentSelection by rememberUpdatedState(selectedIds)
    val currentWires by rememberUpdatedState(selectedWires)
    val wireHandles = remember(document, selectedWires, registry) { wireHandlePoints(document, selectedWires, registry) }
    val currentWireHandles by rememberUpdatedState(wireHandles)
    val measurer = rememberTextMeasurer()
    val imageDecoder = rememberAnnotationImageCache()
    val density = LocalDensity.current.density
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val focusRequester = remember { FocusRequester() }
    val pasteFragment = pendingPaste?.fragment
    val placingPaste = pasteFragment != null
    val pastePreview = remember(pasteFragment) {
        pasteFragment?.let {
            CircuitDocument(components = it.components, wires = it.wires)
        }
    }
    val pasteOverlay = remember(pasteFragment) {
        CircuitDrawingOverlay(
            selectedComponents = pasteFragment?.components.orEmpty().map { it.id }.toSet(),
            selectedWires = pasteFragment?.wires.orEmpty().toSet(),
        )
    }
    val pasteJunctions = remember(pasteFragment) { circuitDrawingJunctions(pasteFragment?.wires.orEmpty()) }
    val pasteSimulation = remember { SimulationState() }
    val movePastePreview by rememberUpdatedState(onMovePastePreview)
    val currentMoveSelection by rememberUpdatedState(onMoveSelection)
    val currentKeepConnections by rememberUpdatedState(keepConnectionsWhenMoving)
    var placementHover by remember(accessibilityContext, mode, showPlacementPreview) { mutableStateOf<Offset?>(null) }
    var placementPress by remember(accessibilityContext, mode, showPlacementPreview) {
        mutableStateOf<Pair<CircuitComponent, Offset>?>(null)
    }
    val placementGhost = placementPress ?: placementPrototype?.let { prototype -> placementHover?.let { prototype to it } }
    val ghostPrototype = placementGhost?.first
    val ghostDocument = remember(ghostPrototype) { ghostPrototype?.let { CircuitDocument(components = listOf(it)) } }
    val ghostBounds = remember(ghostDocument, measurer, imageDecoder, registry, childCircuit, gateShape) {
        ghostDocument?.let { circuitDrawingBounds(it, measurer, imageDecoder, registry, childCircuit, gateShape) }
    }
    val ghostPaint = remember { Paint().apply { alpha = 0.42f } }
    val inset = 24f * density
    val controlsHeight = if (showZoomControls) 72f * density else 0f
    val junctions = remember(document.wires) { circuitDrawingJunctions(document.wires) }
    val wireIndex = remember(document.wires) {
        SpatialIndex(document.wires.map { wire ->
            val radius = SelectedWireRadius.toDouble()
            SpatialItem(wire, SpatialRect(
                min(wire.from.x, wire.to.x).toFloat().toDouble() - radius,
                min(wire.from.y, wire.to.y).toFloat().toDouble() - radius,
                max(wire.from.x, wire.to.x).toFloat().toDouble() + radius,
                max(wire.from.y, wire.to.y).toFloat().toDouble() + radius,
            ))
        })
    }
    val junctionIndex = remember(junctions) {
        SpatialIndex(junctions.map { point ->
            val x = point.x.toFloat().toDouble()
            val y = point.y.toFloat().toDouble()
            SpatialItem(point, SpatialRect(x - BusJunctionRadius, y - BusJunctionRadius, x + BusJunctionRadius, y + BusJunctionRadius))
        })
    }
    val drawingBounds = remember(document, measurer, imageDecoder, registry, childCircuit, gateShape) {
        circuitDrawingBounds(document, measurer, imageDecoder, registry, childCircuit, gateShape)
    }
    val accessibilityGraph = remember(document, registry, accessibilityContext, accessibilityActions != null) {
        if (accessibilityActions != null) CircuitAccessibilityGraph(document, registry, accessibilityContext) else null
    }

    fun changeView(next: CircuitViewport, fitted: Boolean = false) {
        updateViewport(latestViewport.copy(view = next, fitted = fitted, initialized = true))
    }

    fun fit(selectionOnly: Boolean = false) {
        val target = viewportFitDocument(document, selectedIds, selectedWires, selectionOnly)
        val contentBounds = if (target === document) drawingBounds
            else circuitDrawingBounds(target, measurer, imageDecoder, registry, childCircuit, gateShape)
        updateViewport(latestViewport.fit(document, inset, controlsHeight, selectedIds, selectedWires, selectionOnly, registry, contentBounds))
    }

    fun zoom(factor: Float, anchor: Offset = Offset(viewportSize.width / 2f, viewportSize.height / 2f)) {
        changeView(latestViewport.view.zoomed(factor, anchor))
    }

    fun releaseIoPointer() {
        val interaction = activeIoPointer ?: return
        activeIoPointer = null
        interaction.send(interaction.component.id, interaction.release)
    }

    fun clearInteraction() {
        releaseIoPointer()
        dragPreview = null
        selectionBox = null
        wireCornerPreview = null
        editWirePreview = null
        placementHover = null
        placementPress = null
    }

    val onWindowFocusLost by rememberUpdatedState {
        cancelCanvasInteractionOnFocusLoss(activeGesture, pendingWire != null || activeIoPointer != null,
            ::clearInteraction, onCommand)
    }
    LaunchedEffect(placingPaste, mode, projectPlacementTool) {
        if (placingPaste || mode == EditorMode.PLACE) {
            dismissKeyboardPoke()
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(windowInfo) {
        observeCanvasWindowFocus(windowInfo, { activeGesture }) {
            dismissKeyboardPoke()
            onWindowFocusLost()
        }
    }

    DisposableEffect(Unit) {
        onDispose { releaseIoPointer() }
    }

    LaunchedEffect(viewportSize, viewportState.initialized, viewportState.size, registry, drawingBounds) {
        updateViewport(latestViewport.resized(document, viewportSize, inset, controlsHeight, registry, drawingBounds))
    }
    LaunchedEffect(viewportReset, viewportState.initialized, viewportState.size) {
        if (viewportState.initialized && handledFitRequest != viewportReset) {
            fit(fitSelection)
            handledFitRequest = viewportReset
        }
    }
    fun handleKeyboardPokeKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || placingPaste) return false
        val id = pokedKeyboardId() ?: return false
        when (event.key) {
            Key.Escape -> {
                dismissKeyboardPoke()
                return true
            }
            Key.DirectionUp, Key.DirectionDown -> return true
        }
        val input = keyboardPokeEvent(event.key, event.utf16CodePoint, event.isCtrlPressed || event.isMetaPressed)
            ?: return false
        currentComponentEventSender(id, input)
        return true
    }

    fun handleCanvasKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (placingPaste && event.key == Key.Enter) {
            onConfirmPaste()
            return true
        }
        if (mode == EditorMode.PLACE && !placingPaste && event.key == Key.X &&
            !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed) {
            onTogglePlacementMatrix()
            return true
        }
        val boundAction = event.editorShortcutBinding(shortcutScheme, allowPlain = true)
            ?.let { editorShortcutFor(it, shortcutOverrides) }
        val facing = boundAction?.facing
        if (facing != null && onToolFacing(facing)) return true
        if (boundAction == EditorShortcut.PLACEMENT_AUTO_LABEL && !placingPaste && onOpenPlacementAutoLabel()) return true
        if (boundAction?.toolKey == true && !placingPaste && onToolShortcut(boundAction)) return true
        val command = fixedCanvasShortcut(event.key) ?: boundAction?.canvasCommand
        if (boundAction?.defaultBinding?.primary == true && command == null) return false
        if (command != null) {
            if (placingPaste && command == CanvasCommand.DELETE) {
                onCommand(CanvasCommand.CANCEL)
                return true
            }
            if (placingPaste && command in setOf(CanvasCommand.COPY, CanvasCommand.CUT,
                    CanvasCommand.DUPLICATE, CanvasCommand.SELECT_ALL, CanvasCommand.REDO)) return true
            if (command == CanvasCommand.CANCEL) {
                activeGesture?.cancel()
                clearInteraction()
                contextMenu = null
            }
            onCommand(command)
            return true
        }
        val direction = when (event.key) {
            Key.DirectionLeft -> Direction.WEST
            Key.DirectionRight -> Direction.EAST
            Key.DirectionUp -> Direction.NORTH
            Key.DirectionDown -> Direction.SOUTH
            else -> null
        }
        if (direction != null) {
            val step = (if (snapToGrid) 10 else 1) * (if (event.isShiftPressed) 10 else 1)
            val move = when (direction) {
                Direction.WEST -> GridPoint(-step, 0)
                Direction.EAST -> GridPoint(step, 0)
                Direction.NORTH -> GridPoint(0, -step)
                Direction.SOUTH -> GridPoint(0, step)
            }
            if (!placingPaste && (currentSelection.isNotEmpty() || currentWires.isNotEmpty())) onMoveSelection(move, keepConnectionsWhenMoving)
            else {
                val panStep = if (event.isShiftPressed) 400f else 40f
                changeView(latestViewport.view.copy(pan = latestViewport.view.pan - move.offset() * (panStep * density / step)))
            }
            return true
        }
        return when (event.key) {
            Key.Plus, Key.Equals -> { zoom(1.25f); true }
            Key.Minus -> { zoom(1f / 1.25f); true }
            Key.Zero, Key.MoveHome -> { fit(); true }
            else -> false
        }
    }

    val outputSummary = document.components.filter {
        it.kind == ComponentKind.OUTPUT || it.kind == ComponentKind.HEX_DISPLAY
    }.joinToString { component ->
        val value = simulation.vectorValues[component.id]?.let { formattedSignal(it, component.attributes["radix"] ?: signalRadix, valueCharacters) }
            ?: simulation.componentValues[component.id]?.display(valueCharacters) ?: valueCharacters.unknownChar.toString()
        "${component.label.ifBlank { strings(Messages.ComponentOutput) }}: $value"
    }

    Box(modifier.clipToBounds().background(background).onSizeChanged { viewportSize = it }
        .onPreviewKeyEvent(::handleKeyboardPokeKey)) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = strings(Messages.A11yCanvas, "outputs" to outputSummary)
                }
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (hadFocus && !focus.hasFocus) {
                        onWindowFocusLost()
                    }
                    hadFocus = focus.hasFocus
                }
                .onKeyEvent(::handleCanvasKey)
                .focusable()
                .pointerInput(placingPaste, snapToGrid, accessibilityContext, mode, showPlacementPreview) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                                if (event.keyboardModifiers.pointerCtrlPressed || event.keyboardModifiers.pointerMetaPressed) {
                                    zoom(exp(-delta.y * 0.12f), event.changes.firstOrNull()?.position ?: Offset.Zero)
                                } else changeView(latestViewport.view.copy(pan = latestViewport.view.pan - delta * (32f * density)))
                                event.changes.forEach { it.consume() }
                            } else if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                                val hover = event.changes.firstOrNull { it.type == PointerType.Mouse && !it.pressed }
                                tipHover = hover?.position
                                hover?.let {
                                    if (placingPaste) movePastePreview(canvasPoint(latestViewport.view.world(it.position), snapToGrid))
                                    else if (showPlacementPreview) placementHover = it.position
                                }
                            } else if (event.type == PointerEventType.Exit) {
                                placementHover = null
                                tipHover = null
                            } else if (event.type == PointerEventType.Press) {
                                tipHover = null
                            }
                        }
                    }
                }
                .pointerInput(accessibilityContext, document, mode, viewportReset, registry, snapToGrid, windowInfo, windowFocused, placingPaste, printerView, showPlacementPreview) {
                    awaitEachGesture {
                        val first = awaitCanvasFirstDown()
                        if (!windowInfo.isWindowFocused) return@awaitEachGesture
                        val buttons = currentEvent.buttons
                        val mouse = first.type == PointerType.Mouse
                        val modifiers = currentEvent.keyboardModifiers
                        val gesture = if (mouse) projectMouseGesture(buttons, modifiers) else null
                        val binding = if (placingPaste) null else gesture?.let(currentMouseBindings::get)?.action
                        val mappedTool = (binding as? ProjectMouseAction.Edit)?.tool
                        val secondary = if (binding == null) buttons.isSecondaryPressed else mappedTool == ProjectMouseEditTool.MENU
                        val mousePan = mouse && binding == null && !secondary && buttons.isTertiaryPressed
                        if (mouse && binding == null && !secondary && !mousePan && !buttons.isPrimaryPressed) {
                            return@awaitEachGesture
                        }
                        focusRequester.requestFocus()
                        dismissKeyboardPoke()
                        currentDismissWirePoke()
                        val start = first.position
                        val startWorld = latestViewport.view.world(start)
                        if (placingPaste && !mousePan && !secondary) movePastePreview(canvasPoint(startWorld, snapToGrid))
                        val gestureMode = mappedEditorMode(binding, mode)
                        val pressedPrototype = if (showPlacementPreview && !placingPaste && !printerView && !secondary &&
                            !mousePan && gestureMode == EditorMode.PLACE) {
                            (binding as? ProjectMouseAction.Place)?.prototype ?: currentPlacementPrototype
                        } else null
                        placementHover = null
                        placementPress = pressedPrototype?.let { it to start }
                        // Modifiers selecting an explicit mapping do not also add to the selection.
                        val additive = binding == null &&
                            (modifiers.pointerShiftPressed || modifiers.pointerCtrlPressed || modifiers.pointerMetaPressed)
                        val tolerance = (if (first.type == PointerType.Touch) 22f else 8f) * density / latestViewport.view.scale
                        val editWireTolerance = min(tolerance, 8f)
                        fun editWireEnd(position: Offset): GridPoint {
                            val world = latestViewport.view.world(position)
                            val snapped = canvasPoint(world, snapToGrid)
                            return document.editToolConnectionPoint(world, snapped, editWireTolerance, registry) ?: snapped
                        }
                        val editWireStart = if (!placingPaste && !secondary && !mousePan) {
                            when (gestureMode) {
                                EditorMode.EDIT -> document.editToolWireStart(startWorld, editWireTolerance, registry,
                                    snapToGrid, currentWires, modifiers.pointerAltPressed)
                                EditorMode.WIRE -> editWireEnd(start)
                                else -> null
                            }
                        } else null
                        val corner = if (!placingPaste && gestureMode == EditorMode.MOVE && !additive)
                            nearestWireHandle(currentWireHandles, startWorld, tolerance) else null
                        val hit = if (placingPaste) CanvasHit.Empty
                            else corner?.let { CanvasHit.WireCorner(it) } ?: document.hit(startWorld, tolerance, registry)
                        val ioComponent = (hit as? CanvasHit.Component)?.id?.let { id ->
                            document.components.firstOrNull { it.id == id }
                        }?.takeIf { gestureMode == EditorMode.INTERACT && !additive && !secondary && !mousePan && it.kind in pointerIoKinds }
                        val ioAction = ioComponent?.let { IoInteractions.press(it, canvasPoint(startWorld, snapToGrid = false)) }
                        val ioSender = currentComponentEventSender
                        val action = if (mousePan) CanvasDragAction.PAN else canvasDragAction(gestureMode, hit, additive, editWireStart)
                        val machine = CanvasGestureMachine(start, action)
                        val wireDirection = if (action == CanvasDragAction.DRAW_WIRE)
                            WireDragDirection(canvasPoint(startWorld, snapToGrid)) else null
                        activeGesture = machine
                        val movingIds = when (hit) {
                            is CanvasHit.Component -> if (hit.id in currentSelection) currentSelection
                                else if (additive) currentSelection + hit.id else setOf(hit.id)
                            is CanvasHit.Wire -> if (hit.segment in currentWires || additive) currentSelection else emptySet()
                            is CanvasHit.WireCorner -> emptySet()
                            CanvasHit.Empty -> emptySet()
                        }
                        val movingWires = when (hit) {
                            is CanvasHit.Component -> if (hit.id in currentSelection || additive) currentWires else emptySet()
                            is CanvasHit.Wire -> if (hit.segment in currentWires) currentWires
                                else if (additive) currentWires + hit.segment else setOf(hit.segment)
                            is CanvasHit.WireCorner -> emptySet()
                            CanvasHit.Empty -> emptySet()
                        }
                        fun selectHit(toggleExisting: Boolean = true) {
                            when (hit) {
                                is CanvasHit.Component -> if ((additive && toggleExisting) || hit.id !in currentSelection) onSelectComponent(hit.id, additive)
                                is CanvasHit.Wire -> if ((additive && toggleExisting) || hit.segment !in currentWires) onSelectWire(hit.segment, additive)
                                is CanvasHit.WireCorner -> Unit
                                CanvasHit.Empty -> onSelectArea(canvasPoint(startWorld, snapToGrid), canvasPoint(startWorld, snapToGrid), additive)
                            }
                        }
                        var lastTime = first.uptimeMillis
                        var movingSelected = false
                        try {
                            if (ioAction != null && ioComponent != null) {
                                onSelectComponent(ioComponent.id, false)
                                val release = ioAction.release
                                if (release != null && ioSender(ioComponent.id, ioAction.press)) {
                                    activeIoPointer = ActiveIoPointer(ioComponent, release, ioSender)
                                }
                            }
                            while (true) {
                                val event = awaitPointerEvent().forCanvasGesture(first) ?: continue
                                val change = event.changes.firstOrNull { it.id == first.id }
                                val pressed = event.changes.count { it.pressed }
                                if (change != null) lastTime = change.uptimeMillis
                                val phase = machine.move(event, first.id, viewConfiguration.touchSlop)
                                if (change != null && (phase == CanvasGesturePhase.PRESSED || phase == CanvasGesturePhase.DRAGGING)) {
                                    wireDirection?.update(canvasPoint(latestViewport.view.world(change.position), snapToGrid))
                                }
                                when (phase) {
                                    CanvasGesturePhase.TRANSFORMING -> {
                                        releaseIoPointer()
                                        dragPreview = null
                                        selectionBox = null
                                        wireCornerPreview = null
                                        editWirePreview = null
                                        placementPress = null
                                        if (pressed >= 2) {
                                            val center = event.calculateCentroid(useCurrent = false)
                                            val zoomed = latestViewport.view.zoomed(event.calculateZoom(), center)
                                            changeView(zoomed.copy(pan = zoomed.pan + event.calculatePan()))
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    CanvasGesturePhase.DRAGGING -> if (change != null && change.pressed && !secondary) {
                                        placementPress = null
                                        if (placingPaste && !mousePan) {
                                            movePastePreview(canvasPoint(latestViewport.view.world(change.position), snapToGrid))
                                        } else if (ioAction != null && ioComponent != null) {
                                            val interaction = activeIoPointer
                                            if (interaction != null) IoInteractions.drag(ioComponent,
                                                canvasPoint(latestViewport.view.world(change.position), snapToGrid = false))?.let {
                                                interaction.send(ioComponent.id, it)
                                            }
                                        } else when (action) {
                                            CanvasDragAction.PAN -> changeView(latestViewport.view.copy(
                                                pan = latestViewport.view.pan + change.position - change.previousPosition))
                                            CanvasDragAction.SELECT_AREA -> selectionBox = start to change.position
                                            CanvasDragAction.MOVE_SELECTION -> {
                                                if (!movingSelected) { selectHit(toggleExisting = false); movingSelected = true }
                                                val delta = latestViewport.view.world(change.position) - startWorld
                                                dragPreview = SelectionDragPreview(movingIds, movingWires, canvasPoint(delta, snapToGrid))
                                            }
                                            CanvasDragAction.MOVE_WIRE_CORNER -> if (hit is CanvasHit.WireCorner) {
                                                wireCornerPreview = hit.point to movedWireHandle(hit.point, startWorld,
                                                    latestViewport.view.world(change.position), snapToGrid)
                                            }
                                            CanvasDragAction.DRAW_WIRE -> editWireStart?.let { from ->
                                                editWirePreview = WireDragPreview(from, editWireEnd(change.position),
                                                    wireDirection?.verticalFirst == true)
                                            }
                                        }
                                        change.consume()
                                    }
                                    CanvasGesturePhase.PRESSED -> if (change != null && change.pressed && pressedPrototype != null) {
                                        placementPress = pressedPrototype to change.position
                                    }
                                    else -> Unit
                                }
                                if (pressed == 0) break
                            }
                            val completion = machine.finish()
                            releaseIoPointer()
                            if (mousePan) return@awaitEachGesture
                            if (placingPaste) {
                                if (completion != null) {
                                    if (secondary) onCommand(CanvasCommand.CANCEL)
                                    else onCanvasTap(canvasPoint(latestViewport.view.world(machine.position), snapToGrid))
                                }
                                return@awaitEachGesture
                            }
                            if (completion != null && (secondary || (completion is CanvasGestureCompletion.Tap &&
                                    first.type != PointerType.Mouse &&
                                    ioAction?.release == null &&
                                    lastTime - first.uptimeMillis >= viewConfiguration.longPressTimeoutMillis))) {
                                selectHit(toggleExisting = false)
                                val splitPoint = (hit as? CanvasHit.Wire)?.segment?.let {
                                    wireSplitPoint(it, latestViewport.view.world(machine.position), snapToGrid)
                                }
                                // Evolution's MenuTool: the selection menu when the pressed component is part of a
                                // larger selection, the component (or wire) menu otherwise, and no menu on empty canvas.
                                val pressedComponent = (hit as? CanvasHit.Component)?.id
                                val pressedWire = (hit as? CanvasHit.Wire)?.segment
                                val selectionMenu = (pressedComponent != null && pressedComponent in currentSelection ||
                                    pressedWire != null && pressedWire in currentWires) && currentSelection.size + currentWires.size > 1
                                if (pressedComponent != null || pressedWire != null) {
                                    contextMenu = CanvasContextMenu(machine.position, pressedComponent, splitPoint,
                                        latestViewport.view.world(machine.position), pressedWire, selectionMenu)
                                }
                            } else if (ioAction != null && ioComponent != null && completion != null) {
                                if (ioAction.release == null && completion is CanvasGestureCompletion.Tap) {
                                    ioSender(ioComponent.id, ioAction.press)
                                }
                            } else when (completion) {
                                is CanvasGestureCompletion.Tap -> {
                                    val world = latestViewport.view.world(completion.position)
                                    if (gestureMode == EditorMode.TEXT) {
                                        measurer.textToolTarget(document, world, registry)?.let { target ->
                                            currentTextToolTap(target, (binding as? ProjectMouseAction.Text)?.prototype)
                                        }
                                        return@awaitEachGesture
                                    }
                                    val component = (hit as? CanvasHit.Component)?.let { target -> document.components.firstOrNull { it.id == target.id } }
                                    val sequentialTarget = if (gestureMode == EditorMode.INTERACT && !additive &&
                                        !printerView && currentSequentialHexEdit != null && component != null) {
                                        component.sequentialPokeTarget(world, registry)?.takeIf { it == component.sequentialPokeTarget(startWorld, registry) }
                                    } else null
                                    if (sequentialTarget != null) {
                                        val toggle = sequentialTarget.toggle
                                        if (toggle == null) currentSequentialHexEdit?.invoke(sequentialTarget)
                                        else ioSender(sequentialTarget.componentId, toggle)
                                        return@awaitEachGesture
                                    }
                                    val externalEvent = if (gestureMode == EditorMode.INTERACT && !additive && component != null)
                                        externalIoEventAt(component, GridPoint(world.x.roundToInt(), world.y.roundToInt())) else null
                                    val memoryGrid = if (gestureMode == EditorMode.INTERACT && !additive &&
                                        !printerView && currentMemoryEdit != null && component != null)
                                        currentMemoryGrids[component.id] else null
                                    val memoryWord = memoryGrid?.addressAt(world)
                                    if (gestureMode == EditorMode.INTERACT && !additive &&
                                        !printerView && component?.kind == IoKinds.Keyboard
                                    ) {
                                        currentKeyboardPoke(component.id)
                                    } else if (memoryWord != null && component != null) {
                                        val image = runCatching { memoryEditorImage(component, currentSimulation, registry) }.getOrNull()
                                        if (image != null && memoryWord.toLong() < image.capacity) {
                                            val value = image[memoryWord.toLong()].toString(16).padStart(memoryGrid.digits, '0')
                                            currentMemoryEdit?.invoke(CanvasMemoryEdit.Word(CanvasMemoryWord(component.id, memoryWord), value))
                                        }
                                    } else if (component != null && memoryGrid?.addressLabelBounds?.contains(world) == true) {
                                        val config = MemoryComponents.configuration(component)
                                        currentMemoryEdit?.invoke(CanvasMemoryEdit.Address(component.id, memoryGrid.firstAddress.toString(16)) { address ->
                                            val model = MemoryEditorModel(MemoryImage(config.addressBits, config.dataBits), pageSize = 1)
                                            when (val result = model.navigate(address)) {
                                                is MemoryEditResult.Failure -> result.notice
                                                is MemoryEditResult.Success -> {
                                                    currentMemoryAddressChanged(component.id,
                                                        memoryGrid.at(result.model.firstAddress.toInt()).firstAddress)
                                                    null
                                                }
                                            }
                                        })
                                    } else if (gestureMode == EditorMode.INTERACT && !additive && !printerView && binding == null &&
                                        component?.kind == ComponentKind.INPUT && currentPokePin != null) {
                                        // Evolution's PinPoker: the tap pokes the bit or digit under the pointer.
                                        onSelectComponent(component.id, false)
                                        val target = PinPoke.target(component, floor(world.x).toInt(), floor(world.y).toInt(), registry)
                                        if (target == null || target >= 0) currentPokePin?.invoke(component.id, target)
                                    } else if (externalEvent != null && component != null) {
                                        onSelectComponent(component.id, false)
                                        ioSender(component.id, externalEvent)
                                    } else if (gestureMode == EditorMode.INTERACT && hit is CanvasHit.Wire && !additive) {
                                        currentWirePoke(hit.segment, canvasPoint(world, snapToGrid = false))
                                    } else if (hit is CanvasHit.WireCorner || ioComponent != null ||
                                        gestureMode == EditorMode.SELECT || gestureMode == EditorMode.EDIT ||
                                        ((gestureMode == EditorMode.MOVE || gestureMode == EditorMode.INTERACT) && additive)) selectHit()
                                    else {
                                        val point = document.touchPoint(world, gestureMode, tolerance, registry, snapToGrid)
                                        when (binding) {
                                            is ProjectMouseAction.Place -> currentMappedPlacement(binding.prototype, point, buttons.isPrimaryPressed)
                                            is ProjectMouseAction.Edit -> currentMappedTap(point, gestureMode)
                                            is ProjectMouseAction.Text -> Unit
                                            null -> onCanvasTap(point)
                                        }
                                    }
                                }
                                is CanvasGestureCompletion.Drag -> when (completion.action) {
                                    CanvasDragAction.SELECT_AREA -> onSelectArea(canvasPoint(startWorld, snapToGrid),
                                        canvasPoint(latestViewport.view.world(completion.to), snapToGrid), additive)
                                    CanvasDragAction.MOVE_SELECTION -> dragPreview?.let {
                                        val invert = binding == null && currentEvent.keyboardModifiers.pointerShiftPressed
                                        currentMoveSelection(it.delta, currentKeepConnections xor invert)
                                    }
                                    CanvasDragAction.MOVE_WIRE_CORNER -> wireCornerPreview?.let { (from, to) ->
                                        if (from != to) onMoveWireCorner(from, to)
                                    }
                                    CanvasDragAction.DRAW_WIRE -> editWireStart?.let { from ->
                                        currentDrawEditToolWire(from, editWireEnd(completion.to),
                                            wireDirection?.verticalFirst == true)
                                    }
                                    CanvasDragAction.PAN -> Unit
                                }
                                null -> Unit
                            }
                        } catch (cancelled: CancellationException) {
                            machine.cancel()
                            throw cancelled
                        } finally {
                            releaseIoPointer()
                            activeGesture = null
                            dragPreview = null
                            selectionBox = null
                            wireCornerPreview = null
                            editWirePreview = null
                            placementPress = null
                        }
                    }
                },
        ) {
            // Keep dots readable while zooming out without changing the actual 10-unit snap grid.
            if (showGrid && !printerView) canvasGridPlan(view, IntSize(size.width.roundToInt(), size.height.roundToInt()), density)?.let { grid ->
                val dotRole = if (view.scale <= 0.5f * density) EditorColor.GRID_ZOOMED_DOT else EditorColor.GRID_DOT
                val dotColor = activeColors.color(dotRole, dotRole.defaultColor(highContrastSignals))
                repeat(grid.columns) { column -> repeat(grid.rows) { row ->
                    drawCircle(dotColor, 0.65f * density,
                        grid.first + Offset(column * grid.spacing, row * grid.spacing))
                } }
            }
            // Allow one screen pixel for antialiasing outside the geometric stroke.
            val start = view.world(Offset(-1f, -1f))
            val end = view.world(Offset(size.width + 1f, size.height + 1f))
            val visible = if (start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite())
                SpatialRect(start.x.toDouble(), start.y.toDouble(), end.x.toDouble(), end.y.toDouble()) else null
            val preview = dragPreview
            val wiresToDraw = if (visible != null && preview?.wires.isNullOrEmpty()) wireIndex.query(visible) else document.wires
            val junctionsToDraw = visible?.let(junctionIndex::query) ?: junctions
            withTransform({ translate(view.pan.x, view.pan.y); scale(view.scale, view.scale, Offset.Zero) }) {
                val style = CircuitDrawingStyle(highContrastSignals, signalRadix, gateShape, printerView, activeColors, floatingPoints,
                    pinDotDiameter, valueCharacters, widthErrorPoints, darkTheme)
                // CanvasPainter draws the attribute halo under the circuit, hidden while the component is dragged.
                if (attributeHalo && !printerView && selectedIds.size == 1 && preview?.ids?.contains(selectedIds.first()) != true) {
                    document.components.firstOrNull { it.id == selectedIds.first() }?.let { haloed ->
                        runCatching { classicComponentBounds(haloed, registry) }.getOrNull()?.let { drawAttributeHalo(it) }
                    }
                }
                drawCircuitDrawing(document, simulation, measurer, imageDecoder, registry, childCircuit,
                    style,
                    CircuitDrawingOverlay(selectedIds, selectedWires, preview?.ids.orEmpty(), preview?.wires.orEmpty(),
                        preview?.delta ?: GridPoint(0, 0), pendingWire,
                        observedMemoryWindows.mapValues { it.value.firstAddress }, highlightedWires,
                        if (printerView) emptyList() else widthErrors), junctionsToDraw, wiresToDraw, projectStates)
                editWirePreview?.let { (from, to, verticalFirst) ->
                    val removed = document.wireBacktrack(from, to, registry)
                    if (removed != null) {
                        drawLine(CanvasWireRemoval, removed.from.offset(), removed.to.offset(), 4f * density / view.scale)
                    } else {
                        val width = 2f * density / view.scale
                        for (segment in orthogonalWireRoute(from, to, verticalFirst)) {
                            drawLine(CanvasAccent, segment.from.offset(), segment.to.offset(), width)
                        }
                    }
                }
                if (pastePreview != null && pendingPaste != null) {
                    withTransform({ translate(pendingPaste.offset.x.toFloat(), pendingPaste.offset.y.toFloat()) }) {
                        drawCircuitDrawing(pastePreview, pasteSimulation, measurer, imageDecoder, registry, childCircuit,
                            style.forPreview().copy(floatingPoints = emptySet(), widthErrorPoints = emptySet()), pasteOverlay, pasteJunctions)
                    }
                }
                if (showPlacementPreview && !placingPaste && !printerView &&
                    placementGhost != null && ghostDocument != null && ghostBounds != null) {
                    val (prototype, position) = placementGhost
                    val offset = canvasPoint(view.world(position), snapToGrid).offset() - prototype.position.offset()
                    withTransform({ translate(offset.x, offset.y) }) {
                        val bounds = Rect(ghostBounds.left.toFloat(), ghostBounds.top.toFloat(),
                            ghostBounds.right.toFloat(), ghostBounds.bottom.toFloat()).inflate(2f)
                        val canvas = drawContext.canvas
                        canvas.saveLayer(bounds, ghostPaint)
                        try {
                            drawCircuitDrawing(ghostDocument, pasteSimulation, measurer, imageDecoder, registry,
                                childCircuit, style.forPreview().copy(floatingPoints = emptySet(), widthErrorPoints = emptySet()))
                        } finally {
                            canvas.restore()
                        }
                    }
                }
                if (mode == EditorMode.MOVE && !placingPaste) {
                    val radius = 4f * density / view.scale
                    fun handle(point: GridPoint) {
                        val topLeft = point.offset() - Offset(radius, radius)
                        val extent = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                        drawRect(Color.White, topLeft, extent)
                        drawRect(CanvasAccent, topLeft, extent, style = Stroke(1.25f * density / view.scale))
                    }
                    for (point in wireHandles) if (wireCornerPreview?.first != point) handle(point)
                    wireCornerPreview?.let { (from, to) ->
                        drawLine(CanvasAccent.copy(alpha = 0.6f), from.offset(), to.offset(), 1.25f * density / view.scale)
                        handle(to)
                    }
                }
            }
        }
        selectionBox?.let { (from, to) ->
            Canvas(Modifier.fillMaxSize()) {
                val topLeft = Offset(min(from.x, to.x), min(from.y, to.y))
                val area = androidx.compose.ui.geometry.Size(kotlin.math.abs(to.x - from.x), kotlin.math.abs(to.y - from.y))
                drawRect(CanvasAccent.copy(alpha = 0.1f), topLeft, area)
                drawRect(CanvasAccent, topLeft, area, style = Stroke(1.5f * density))
            }
        }
        if (accessibilityActions != null && accessibilityGraph != null && !placingPaste) {
            CircuitSemanticOverlay(
                graph = accessibilityGraph,
                simulation = simulation,
                isSimulationRunning = isSimulationRunning,
                viewport = CircuitAccessibilityViewport(view.scale, view.pan.x, view.pan.y,
                    viewportSize.width, viewportSize.height),
                selectedComponents = selectedIds,
                actions = accessibilityActions,
                onNotice = onAccessibilityNotice,
                modifier = Modifier.onKeyEvent(::handleCanvasKey),
            )
        }
        sequentialHexTarget?.let { target ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(CanvasAccent, view.pan + target.bounds.topLeft * view.scale, target.bounds.size * view.scale,
                    style = Stroke(1.5f * density))
            }
        }
        editedMemoryWord?.let { word ->
            val grid = memoryGrids[word.componentId]
            val cell = grid?.takeIf { word.address in it.addresses }?.cell(word.address)
            if (cell != null) Canvas(Modifier.fillMaxSize()) {
                drawRect(CanvasAccent, view.pan + cell.topLeft * view.scale, cell.size * view.scale,
                    style = Stroke(1.5f * density))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Canvas.computeViewportContents: the width message, with the count when there are several.
            if (!printerView && widthErrors.isNotEmpty()) {
                val text = strings("canvas_width_error")
                Text(if (widthErrors.size == 1) text else "$text (${widthErrors.size})",
                    modifier = Modifier.background(canvasBackground.copy(alpha = 0.85f))
                        .padding(horizontal = 5.dp, vertical = 2.dp).testTag("editor-width-error"),
                    color = activeColors.themeColor(EditorColor.WIDTH_ERROR), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!printerView && !placingPaste && measuredTicksPerSecond != null) {
                val roundedRate = round(measuredTicksPerSecond * 100) / 100
                Text(
                    strings(Messages.SimulationMeasuredTickRate, "count" to formatHertz(
                        roundedRate.takeIf { it > 0.0 } ?: measuredTicksPerSecond,
                    )),
                    modifier = Modifier.background(ChromeColors.Surface).padding(horizontal = 5.dp, vertical = 2.dp)
                        .testTag("editor-measured-tick-rate"),
                    color = ChromeColors.Ink, fontSize = 12.sp,
                )
            }
        }
        wirePoke?.takeUnless { printerView }?.let { poke ->
            WirePokeCallout(poke, simulation, view, viewportSize, wirePokePrimaryRadix, wirePokeSecondaryRadix,
                isSimulationRunning)
        }
        shownTip?.takeIf { contextMenu == null }?.let { (position, text) ->
            val dark = LocalEditorDarkTheme.current
            Text(strings(text), fontSize = 12.sp, color = ChromeColors.Ink,
                modifier = Modifier.offset { IntOffset(position.x.roundToInt() + 12, position.y.roundToInt() + 16) }
                    .background(if (dark) ChromeColors.Heading else Color(0xFFFFFFE1)).border(1.dp, if (dark) ChromeColors.Muted else Color(0xFF767676)).padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("canvas-component-tip"))
        }
        contextMenu?.let { menu ->
            Box(Modifier.offset { IntOffset(menu.position.x.roundToInt(), menu.position.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { contextMenu = null }, containerColor = chromeMenuContainerColor(),
                    modifier = Modifier.exportEditorTestTags().testTag("canvas-context-menu")) {
                    fun command(command: CanvasCommand) { contextMenu = null; onCommand(command) }
                    val component = document.components.firstOrNull { it.id == menu.componentId }
                    if (componentTips && !printerView && !menu.selectionMenu) {
                        val tip = menu.world?.let { document.componentTipAt(it, registry) }?.takeIf { it.component.id == component?.id }
                        tip?.text(includeComponentOnly = true)?.let { text ->
                            DropdownMenuItem(text = { Text(strings(text), fontWeight = FontWeight.SemiBold) }, onClick = {}, enabled = false,
                                modifier = Modifier.testTag("canvas-component-tip"))
                        }
                    }
                    val child = component?.circuitName?.takeIf { component.kind == ComponentKind.SUBCIRCUIT }?.let(childCircuit)
                    val entries = when {
                        menu.selectionMenu -> ComponentMenu.selection(canEditCircuit)
                        component != null -> ComponentMenu.component(component, registry, canEditCircuit,
                            simulation.componentStates[component.id])
                            .filter { it.item != ComponentMenuItem.VIEW_SUBCIRCUIT || child != null }
                        // A wire is a component in Evolution: Delete and Show Attributes (Mobile has no wire attributes).
                        menu.wire != null -> listOf(ComponentMenuEntry(ComponentMenuItem.DELETE, enabled = canEditCircuit))
                        else -> emptyList()
                    }
                    entries.forEach { entry ->
                        if (entry.separatorBefore) HorizontalDivider()
                        DropdownMenuItem(text = { Text(componentMenuLabel(entry.item, child?.name)) }, enabled = entry.enabled,
                            modifier = Modifier.testTag("canvas-menu-${entry.item.name.lowercase()}"), onClick = {
                                contextMenu = null
                                when (entry.item) {
                                    ComponentMenuItem.DELETE_SELECTION -> command(CanvasCommand.DELETE)
                                    ComponentMenuItem.CUT_SELECTION -> command(CanvasCommand.CUT)
                                    ComponentMenuItem.COPY_SELECTION -> command(CanvasCommand.COPY)
                                    ComponentMenuItem.VIEW_SUBCIRCUIT -> component?.let { onViewSubcircuit(it.id) }
                                    ComponentMenuItem.DELETE -> if (component == null) menu.wire?.let(onRemoveWire)
                                        else onComponentMenu(entry.item, component.id)
                                    else -> component?.let { onComponentMenu(entry.item, it.id) }
                                }
                            })
                    }
                    // Mobile addition for touch: splitting a wire at the pressed point.
                    if (!menu.selectionMenu) menu.splitPoint?.let { point ->
                        DropdownMenuItem(text = { Text(strings("action_split_wire_here")) }, onClick = {
                            contextMenu = null
                            onSplitWire(point)
                        })
                    }
                }
            }
        }
        if (placingPaste) {
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(onClick = onConfirmPaste, modifier = Modifier.testTag("paste-confirm")) {
                    Text(strings(Messages.ActionApply))
                }
                FilledTonalButton(onClick = { onCommand(CanvasCommand.CANCEL) }, modifier = Modifier.testTag("paste-cancel")) {
                    Text(strings(Messages.ActionCancel))
                }
            }
        }
        if (showZoomControls) Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanvasButton("−", strings(Messages.A11yZoomOut), 48) { zoom(1f / 1.25f) }
            CanvasButton("${(view.scale * 100).roundToInt()}%", strings(Messages.A11yFit), 72) { fit() }
            CanvasButton("+", strings(Messages.A11yZoomIn), 48) { zoom(1.25f) }
        }
    }
}

@Composable
private fun CanvasButton(text: String, description: String, width: Int, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(48.dp).width(width.dp).semantics { contentDescription = description },
        shape = RectangleShape,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = ChromeColors.Bar, contentColor = ChromeColors.Ink),
        border = BorderStroke(1.dp, ChromeColors.Divider),
        contentPadding = PaddingValues(4.dp),
    ) { Text(text, fontSize = 12.sp) }
}

private fun CircuitDocument.nearestComponent(point: Offset, tolerance: Float, registry: ComponentRegistry): CircuitComponent? =
    components.asReversed().map { component ->
        val bounds = component.bounds(registry)
        val dx = max(max(bounds.left - point.x, 0f), point.x - bounds.right)
        val dy = max(max(bounds.top - point.y, 0f), point.y - bounds.bottom)
        component to (dx * dx + dy * dy)
    }.filter { it.second <= tolerance * tolerance }.minByOrNull { it.second }?.first

private data class CanvasContextMenu(
    val position: Offset, val componentId: String?, val splitPoint: GridPoint?, val world: Offset? = null,
    val wire: WireSegment? = null, val selectionMenu: Boolean = false,
)

@Composable
private fun componentMenuLabel(item: ComponentMenuItem, child: String?): String {
    val strings = rememberLocalizer()
    return when (item) {
        ComponentMenuItem.ROTATE_LEFT -> strings(Messages.ComponentMenuRotateLeft)
        ComponentMenuItem.ROTATE_RIGHT -> strings(Messages.ComponentMenuRotateRight)
        ComponentMenuItem.DELETE -> strings(Messages.ComponentMenuDelete)
        ComponentMenuItem.SHOW_ATTRIBUTES -> strings(Messages.ActionShowAttributes)
        ComponentMenuItem.VIEW_SUBCIRCUIT -> strings(Messages.ActionViewSubcircuit, "name" to child.orEmpty())
        ComponentMenuItem.MEMORY_EDIT -> strings(Messages.ComponentMenuMemoryEdit)
        ComponentMenuItem.MEMORY_CLEAR -> strings(Messages.ComponentMenuMemoryClear)
        ComponentMenuItem.MEMORY_LOAD -> strings(Messages.ComponentMenuMemoryLoad)
        ComponentMenuItem.MEMORY_SAVE -> strings(Messages.ComponentMenuMemorySave)
        ComponentMenuItem.PLA_EDIT -> strings(Messages.ComponentMenuPlaEdit)
        ComponentMenuItem.PLA_ROM_EDIT -> strings(Messages.ComponentMenuMemoryEdit)
        ComponentMenuItem.PLA_ROM_CLEAR -> strings(Messages.ComponentMenuMemoryClear)
        ComponentMenuItem.SOC_ASSEMBLER -> strings(Messages.ComponentMenuSocAssembler)
        ComponentMenuItem.SOC_READ_ELF -> strings(Messages.ComponentMenuSocReadElf)
        ComponentMenuItem.SOC_SHOW_PROGRAM -> strings(Messages.ComponentMenuSocShowProgram)
        ComponentMenuItem.SOC_MEMORY_MAP -> strings(Messages.ComponentMenuSocMemoryMap)
        ComponentMenuItem.SOC_INSERT_TRANSACTION -> strings(Messages.ComponentMenuSocInsertTransaction)
        ComponentMenuItem.SOC_EXPORT_C -> strings(Messages.ComponentMenuSocExportC)
        ComponentMenuItem.IMAGE_RESET_SIZE -> strings(Messages.ComponentMenuImageResetSize)
        ComponentMenuItem.IMAGE_OPTIMIZE -> strings(Messages.ComponentMenuImageOptimize)
        ComponentMenuItem.IMAGE_MAKE_TRANSPARENT -> strings(Messages.ComponentMenuImageMakeTransparent)
        ComponentMenuItem.SPLITTER_DISTRIBUTE_ASCENDING -> strings(Messages.ComponentMenuDistributeAscending)
        ComponentMenuItem.SPLITTER_DISTRIBUTE_DESCENDING -> strings(Messages.ComponentMenuDistributeDescending)
        ComponentMenuItem.DELETE_SELECTION -> strings(Messages.ComponentMenuDeleteSelection)
        ComponentMenuItem.CUT_SELECTION -> strings(Messages.ComponentMenuCutSelection)
        ComponentMenuItem.COPY_SELECTION -> strings(Messages.ComponentMenuCopySelection)
    }
}

private const val CanvasTipDelayMillis = 750L

private data class SelectionDragPreview(val ids: Set<String>, val wires: Set<WireSegment>, val delta: GridPoint)

private data class ActiveIoPointer(
    val component: CircuitComponent,
    val release: IoInputEvent,
    val send: (String, ComponentEvent) -> Boolean,
)

private val pointerIoKinds = setOf(IoKinds.Button, IoKinds.DipSwitch, IoKinds.Joystick, IoKinds.MatrixKeypad)

private fun CircuitDocument.hit(point: Offset, tolerance: Float, registry: ComponentRegistry): CanvasHit {
    nearestComponent(point, tolerance, registry)?.let { return CanvasHit.Component(it.id) }
    val nearest = wires.map { wire ->
        val closest = if (wire.from.x == wire.to.x) Offset(wire.from.x.toFloat(),
            point.y.coerceIn(min(wire.from.y, wire.to.y).toFloat(), max(wire.from.y, wire.to.y).toFloat()))
        else Offset(point.x.coerceIn(min(wire.from.x, wire.to.x).toFloat(), max(wire.from.x, wire.to.x).toFloat()), wire.from.y.toFloat())
        wire to (closest - point).getDistance()
    }.filter { it.second <= tolerance }.minByOrNull { it.second }
    return nearest?.let { CanvasHit.Wire(it.first) } ?: CanvasHit.Empty
}

private fun CircuitDocument.touchPoint(point: Offset, mode: EditorMode, tolerance: Float, registry: ComponentRegistry, snapToGrid: Boolean): GridPoint {
    if (mode == EditorMode.WIRE) {
        val terminal = (components.flatMap { it.inputPorts(registry) + it.outputPorts(registry) } +
            wires.flatMap { listOf(it.from, it.to) }).minByOrNull { (it.offset() - point).getDistance() }
        if (terminal != null && (terminal.offset() - point).getDistance() <= tolerance) return terminal
    } else if (mode == EditorMode.INTERACT || mode == EditorMode.MOVE) {
        nearestComponent(point, tolerance, registry)?.let { component ->
            val bounds = component.bounds(registry)
            return GridPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
        }
    }
    return canvasPoint(point, snapToGrid)
}

private fun GridPoint.offset(): Offset = Offset(x.toFloat(), y.toFloat())

internal fun keyboardPokeEvent(key: Key, codePoint: Int, commandModifier: Boolean): IoInputEvent? {
    val command = when (key) {
        Key.DirectionLeft -> KeyboardCommand.LEFT
        Key.DirectionRight -> KeyboardCommand.RIGHT
        Key.MoveHome -> KeyboardCommand.HOME
        Key.MoveEnd -> KeyboardCommand.END
        Key.Delete -> KeyboardCommand.DELETE
        else -> null
    }
    if (command != null) return IoInputEvent.EditKeyboard(command)
    val character = when (key) {
        Key.Backspace -> '\b'
        Key.Enter, Key.NumPadEnter -> '\n'
        else -> {
            if (codePoint !in 1..Char.MAX_VALUE.code) return null
            val typed = codePoint.toChar()
            if (typed != '\u000c' && (commandModifier || typed.isISOControl())) return null
            typed
        }
    }
    return IoInputEvent.InsertText(character.toString())
}
