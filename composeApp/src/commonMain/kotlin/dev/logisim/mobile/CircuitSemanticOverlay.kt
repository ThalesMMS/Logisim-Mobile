package dev.logisim.mobile

import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CircuitAccessibilityViewport(val scale: Float, val panX: Float, val panY: Float, val width: Int, val height: Int) {
    init { require(scale.isFinite() && scale > 0 && panX.isFinite() && panY.isFinite()) }
}

/**
 * Virtual elements follow the drawn components without consuming pointer gestures.
 * Full-circuit navigation remains available in the explorer when the canvas is zoomed out.
 */
@Composable
fun CircuitSemanticOverlay(
    graph: CircuitAccessibilityGraph,
    simulation: SimulationState,
    isSimulationRunning: Boolean,
    viewport: CircuitAccessibilityViewport,
    selectedComponents: Set<String>,
    actions: CircuitAccessibilityActions,
    onNotice: (LocalizedText) -> Unit,
    modifier: Modifier = Modifier,
    visibleComponentIds: Set<String>? = null,
    maximumNodes: Int = 250,
    /** The long-press menu items of a component, offered as custom actions through [CircuitAccessibilityActions.componentMenu]. */
    componentMenu: (CircuitComponent) -> List<Pair<ComponentMenuItem, LocalizedText>> = { emptyList() },
) {
    require(maximumNodes > 0)
    val language = LocalAppLanguage.current
    val density = LocalDensity.current
    val minimumExtent = 48f * density.density
    val (nodes, targets) = remember(graph, viewport, visibleComponentIds, selectedComponents, maximumNodes, minimumExtent) {
        val nodes = accessibilityOverlayNodes(graph, viewport, selectedComponents, visibleComponentIds, maximumNodes)
        nodes to accessibilityOverlayTargets(nodes, viewport, minimumExtent, selectedComponents)
    }
    val visible = nodes.components.filter { it.component.id in targets.components }
    var captured by remember(graph) { mutableStateOf(simulation) }
    LaunchedEffect(graph, isSimulationRunning, if (isSimulationRunning) null else simulation) {
        if (!isSimulationRunning) captured = simulation
    }
    val readings = if (isSimulationRunning) captured else simulation
    var connection by remember(graph.context) { mutableStateOf(AccessibleConnectionSession()) }
    val startingPort = connection.start
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var terminalChooser by remember(graph) { mutableStateOf<String?>(null) }
    var bitChooser by remember(graph) { mutableStateOf<String?>(null) }
    fun cancelConnection() {
        connection = AccessibleConnectionSession()
        onNotice(message("a11y_explorer_connection_cancelled"))
    }
    fun choosePort(port: AccessiblePort): Boolean {
        val result = connection.choose(graph, port.id, actions.connect)
        connection = result.session
        onNotice(result.notice)
        return result.connected || result.session.start != null || result.notice.key == "a11y_explorer_connection_cancelled"
    }
    fun portDescription(port: AccessiblePort): LocalizedText {
        val description = port.description(readings)
        return if (isSimulationRunning) message("a11y_canvas_running_snapshot", "value" to description) else description
    }
    fun portAction(name: LocalizedText?): String = message(
        if (startingPort == null) "a11y_explorer_start_connection" else "a11y_explorer_complete_connection", "name" to name,
    ).resolve(language)
    fun pendingPortState(id: AccessiblePortId): String =
        message("a11y_explorer_pending_connection", "name" to graph.connectedPortText(id)).resolve(language)
    Box(modifier.fillMaxSize().clipToBounds().semantics { isTraversalGroup = true }) {
        for ((index, component) in visible.withIndex()) {
            val rect = targets.components.getValue(component.component.id)
            val name = component.nameText
            val selectText = message("a11y_explorer_select", "name" to name).resolve(language)
            val signalText = accessibleComponentValue(component, readings)
            val value = if (isSimulationRunning) message("a11y_canvas_running_snapshot", "value" to signalText).resolve(language)
                else signalText.resolve(language)
            val inlinePorts = targets.inlinePorts[component.component.id].orEmpty()
            val pendingInlinePort = startingPort?.takeIf { id -> inlinePorts.any { it.id == id } }
            key(component.key) {
                Box(Modifier.offset { IntOffset(rect.left, rect.top) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                    .testTag("canvas-${component.key}")
                    .then(if (focusedKey == component.key) Modifier.border(2.dp, Color(0xFF285580)) else Modifier)
                    .onFocusChanged { state ->
                        if (state.isFocused) focusedKey = component.key
                        else if (focusedKey == component.key) focusedKey = null
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = name.resolve(language)
                        val componentState = message(if (component.component.id in selectedComponents) "a11y_explorer_selected" else "a11y_explorer_component_state",
                            "kind" to component.kindText, "value" to value).resolve(language)
                        stateDescription = if (pendingInlinePort == null) componentState else "$componentState ${pendingPortState(pendingInlinePort)}"
                        selected = component.component.id in selectedComponents
                        traversalIndex = index.toFloat()
                        onClick(selectText) { actions.selectComponent(component.component.id); true }
                        customActions = buildList {
                            if (startingPort != null) add(CustomAccessibilityAction(message("a11y_explorer_cancel_connection").resolve(language)) {
                                cancelConnection(); true
                            })
                            add(CustomAccessibilityAction(message("a11y_explorer_edit", "name" to name).resolve(language)) {
                                actions.openProperties(component.component.id); true
                            })
                            add(CustomAccessibilityAction(message("a11y_explorer_remove", "name" to name).resolve(language)) {
                                actions.removeComponent(component.component.id)
                            })
                            component.activation?.let { activation ->
                                add(CustomAccessibilityAction(activation.label(name).resolve(language)) { actions.activate(component) })
                            }
                            if (component.offersValueEntry) {
                                actions.editInputValue?.let { edit ->
                                    // False when the dialog cannot open; the editor's status line says why.
                                    add(CustomAccessibilityAction(message("a11y_input_set_value", "name" to name).resolve(language)) {
                                        edit(component.component.id)
                                    })
                                }
                                if (actions.toggleInputBit != null) add(CustomAccessibilityAction(
                                    message("a11y_input_toggle_bit", "name" to name).resolve(language)) {
                                    bitChooser = component.component.id; true
                                })
                            }
                            // The Menu Tool items of the long-press menu: rotate, view, memory, distribute, image (C42).
                            actions.componentMenu?.let { choose ->
                                for ((item, label) in componentMenu(component.component)) {
                                    add(CustomAccessibilityAction(label.resolve(language)) { choose(item, component.component.id); true })
                                }
                            }
                            val availablePorts = inlinePorts.filter { it.id != startingPort }
                            if (needsCanvasTerminalChooser(availablePorts.size, size)) {
                                add(CustomAccessibilityAction(message("a11y_explorer_ports", "name" to name).resolve(language)) {
                                    terminalChooser = component.component.id
                                    true
                                })
                            } else {
                                for (port in availablePorts) {
                                    add(CustomAccessibilityAction(portAction(portDescription(port))) { choosePort(port) })
                                }
                            }
                        }
                    }.onKeyEvent {
                        when {
                            it.type != KeyEventType.KeyDown -> false
                            it.key == Key.Escape && startingPort != null -> { cancelConnection(); true }
                            it.isShiftPressed && (it.key == Key.Enter || it.key == Key.NumPadEnter) && component.ports.isNotEmpty() -> {
                                terminalChooser = component.component.id
                                true
                            }
                            it.key in setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar) -> {
                                actions.selectComponent(component.component.id)
                                true
                            }
                            else -> false
                        }
                    }.focusable())
            }
        }
        for ((index, port) in nodes.ports.withIndex()) {
            val rect = targets.ports[port.id] ?: continue
            val name = graph.connectedPortText(port.id)
            val actionLabel = portAction(name)
            key(port.id.key) {
                Box(Modifier.offset { IntOffset(rect.left, rect.top) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                    .testTag("canvas-${port.id.key}")
                    .then(if (focusedKey == port.id.key) Modifier.border(2.dp, Color(0xFF285580)) else Modifier)
                    .onFocusChanged { state ->
                        if (state.isFocused) focusedKey = port.id.key
                        else if (focusedKey == port.id.key) focusedKey = null
                    }.semantics {
                        role = Role.Button
                        contentDescription = portDescription(port).resolve(language)
                        traversalIndex = visible.indexOfFirst { it.component.id == port.id.componentId } + (index + 1) / 1_000f
                        stateDescription = if (port.id == startingPort) pendingPortState(port.id) else actionLabel
                        onClick(actionLabel) { choosePort(port) }
                        if (startingPort != null) customActions = listOf(CustomAccessibilityAction(message("a11y_explorer_cancel_connection").resolve(language)) {
                            cancelConnection(); true
                        })
                    }.onKeyEvent {
                        when {
                            it.type != KeyEventType.KeyDown -> false
                            it.key == Key.Escape && startingPort != null -> { cancelConnection(); true }
                            it.key in setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar) -> { choosePort(port); true }
                            else -> false
                        }
                    }.focusable())
            }
        }
    }
    bitChooser?.let(graph::component)?.let { component ->
        InputBitChooserDialog(component.nameText, component.component.width, readings.vectorValues[component.component.id],
            onToggle = { bit -> actions.toggleInputBit?.invoke(component.component.id, bit); bitChooser = null },
            onDismiss = { bitChooser = null })
    }
    terminalChooser?.let(graph::component)?.let { component ->
        val firstTerminal = remember(component.key) { FocusRequester() }
        var focusFirstTerminal by remember(component.key) { mutableStateOf(true) }
        AppAlertDialog(
            onDismissRequest = { terminalChooser = null },
            title = { Text(message("a11y_explorer_ports", "name" to component.nameText).resolve(language)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(component.ports, key = { _, port -> port.id.key }) { index, port ->
                        if (index == 0 && focusFirstTerminal) LaunchedEffect(firstTerminal) {
                            withFrameNanos { }
                            firstTerminal.requestFocus()
                            focusFirstTerminal = false
                        }
                        val label = if (port.id == startingPort) message("a11y_explorer_cancel_connection").resolve(language)
                        else portAction(portDescription(port))
                        TextButton(onClick = { choosePort(port); terminalChooser = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .then(if (index == 0) Modifier.focusRequester(firstTerminal) else Modifier)) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { terminalChooser = null }) { Text(message("a11y_explorer_close").resolve(language)) } },
        )
    }
}

// Android Compose rejects a node with 32 or more custom actions.
internal fun needsCanvasTerminalChooser(portActions: Int, otherActions: Int): Boolean = portActions + otherActions >= 32

data class AccessibilityScreenBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class AccessibilityOverlayTargets(
    val components: Map<String, IntRect>,
    val ports: Map<AccessiblePortId, IntRect>,
    val inlinePorts: Map<String, List<AccessiblePort>>,
)

/**
 * The native tree drops a virtual node covered by a later sibling and clips a partly covered one, so targets must
 * not overlap. A component whose own area is taken, like a duplicate placed on its original, gets a minimum-size
 * target in the nearest free area. Explorer access remains for a canvas with no free area left, and connection
 * actions on the owner for crowded terminals.
 */
internal fun accessibilityOverlayTargets(
    nodes: AccessibilityOverlayNodes,
    viewport: CircuitAccessibilityViewport,
    minimumExtent: Float,
    selectedComponents: Set<String>,
): AccessibilityOverlayTargets {
    val occupied = mutableListOf<IntRect>()
    fun isFree(rect: IntRect): Boolean =
        occupied.none { rect.left < it.right && rect.right > it.left && rect.top < it.bottom && rect.bottom > it.top }
    fun anchoredRect(bounds: ComponentBounds): IntRect {
        val raw = accessibilityTargetBounds(bounds, viewport, minimumExtent)
        val width = raw.width.roundToInt()
        val height = raw.height.roundToInt()
        val left = raw.left.roundToInt().coerceAtMost(viewport.width - width)
        val top = raw.top.roundToInt().coerceAtMost(viewport.height - height)
        return IntRect(left, top, left + width, top + height)
    }
    fun reserve(rect: IntRect): IntRect? = rect.takeIf(::isFree)?.also { occupied += it }

    val components = mutableMapOf<String, IntRect>()
    val displaced = mutableListOf<AccessibleComponent>()
    for (component in nodes.components.sortedBy { it.component.id !in selectedComponents }) {
        val rect = reserve(anchoredRect(component.bounds))
        if (rect == null) displaced += component else components[component.component.id] = rect
    }
    val ports = mutableMapOf<AccessiblePortId, IntRect>()
    val inlinePorts = mutableMapOf<String, MutableList<AccessiblePort>>()
    fun placePort(port: AccessiblePort) {
        val (x, y) = port.position
        val rect = reserve(anchoredRect(ComponentBounds(x - 1, y - 1, x + 1, y + 1)))
        if (rect == null) inlinePorts.getOrPut(port.id.componentId) { mutableListOf() }.add(port)
        else ports[port.id] = rect
    }
    // Terminals of anchored components keep their real position, so displaced targets are placed around them.
    val (anchoredPorts, displacedPorts) = nodes.ports.partition { it.id.componentId in components }
    anchoredPorts.forEach(::placePort)
    val extentWidth = minimumExtent.roundToInt().coerceAtMost(viewport.width).coerceAtLeast(1)
    val extentHeight = minimumExtent.roundToInt().coerceAtMost(viewport.height).coerceAtLeast(1)
    val offsets by lazy { nearestGridOffsets(max(viewport.width / extentWidth, viewport.height / extentHeight) + 1) }
    fun nearestFreeRect(origin: IntRect): IntRect? {
        val originLeft = origin.left + (origin.width - extentWidth) / 2
        val originTop = origin.top + (origin.height - extentHeight) / 2
        for ((column, row) in offsets) {
            val left = (originLeft + column * extentWidth).coerceIn(0, viewport.width - extentWidth)
            val top = (originTop + row * extentHeight).coerceIn(0, viewport.height - extentHeight)
            val rect = IntRect(left, top, left + extentWidth, top + extentHeight)
            if (isFree(rect)) return rect
        }
        return null
    }
    if (displaced.isNotEmpty() && viewport.width > 0 && viewport.height > 0) {
        for (component in displaced) {
            // Once a scan finds no free area the canvas is full, so the remaining components use the Explorer.
            val rect = nearestFreeRect(anchoredRect(component.bounds)) ?: break
            occupied += rect
            components[component.component.id] = rect
        }
    }
    displacedPorts.filter { it.id.componentId in components }.forEach(::placePort)
    return AccessibilityOverlayTargets(components, ports, inlinePorts)
}

/** Grid offsets around a cell, nearest first, excluding the cell itself. */
private fun nearestGridOffsets(radius: Int): List<Pair<Int, Int>> = buildList {
    for (column in -radius..radius) for (row in -radius..radius) if (column != 0 || row != 0) add(column to row)
}.sortedBy { (column, row) -> column * column + row * row }

fun accessibilityScreenBounds(bounds: ComponentBounds, viewport: CircuitAccessibilityViewport, minimumExtent: Float): AccessibilityScreenBounds {
    require(minimumExtent >= 0 && minimumExtent.isFinite())
    val width = max(bounds.width * viewport.scale, minimumExtent)
    val height = max(bounds.height * viewport.scale, minimumExtent)
    val centerX = (bounds.left + bounds.width / 2f) * viewport.scale + viewport.panX
    val centerY = (bounds.top + bounds.height / 2f) * viewport.scale + viewport.panY
    return AccessibilityScreenBounds(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
}

/** Keep a visible edge component's full interaction target inside the canvas clip. */
fun accessibilityTargetBounds(bounds: ComponentBounds, viewport: CircuitAccessibilityViewport, minimumExtent: Float): AccessibilityScreenBounds {
    val raw = accessibilityScreenBounds(bounds, viewport, minimumExtent)
    val viewportWidth = viewport.width.coerceAtLeast(0).toFloat()
    val viewportHeight = viewport.height.coerceAtLeast(0).toFloat()
    val width = min(raw.width, viewportWidth)
    val height = min(raw.height, viewportHeight)
    val left = ((raw.left + raw.right - width) / 2).coerceIn(0f, viewportWidth - width)
    val top = ((raw.top + raw.bottom - height) / 2).coerceIn(0f, viewportHeight - height)
    return AccessibilityScreenBounds(left, top, left + width, top + height)
}

fun accessibilityVisibleComponents(
    graph: CircuitAccessibilityGraph,
    viewport: CircuitAccessibilityViewport,
    visibleComponentIds: Set<String>? = null,
    maximumNodes: Int = 250,
): List<AccessibleComponent> {
    require(maximumNodes > 0)
    if (viewport.width <= 0 || viewport.height <= 0) return emptyList()
    return graph.components.asSequence().filter { visibleComponentIds == null || it.component.id in visibleComponentIds }
        .filter { component ->
            val rect = accessibilityScreenBounds(component.bounds, viewport, 0f)
            min(rect.right, viewport.width.toFloat()) > max(rect.left, 0f) &&
                min(rect.bottom, viewport.height.toFloat()) > max(rect.top, 0f)
        }.take(maximumNodes).toList()
}

/** Selected terminals retain a bounded share of the tree even when many components are visible. */
data class AccessibilityOverlayNodes(val components: List<AccessibleComponent>, val ports: List<AccessiblePort>)

fun accessibilityOverlayNodes(
    graph: CircuitAccessibilityGraph,
    viewport: CircuitAccessibilityViewport,
    selectedComponents: Set<String>,
    visibleComponentIds: Set<String>? = null,
    maximumNodes: Int = 250,
): AccessibilityOverlayNodes {
    require(maximumNodes > 0)
    val selectedIds = visibleComponentIds?.intersect(selectedComponents) ?: selectedComponents
    val selected = accessibilityVisibleComponents(graph, viewport, selectedIds, maximumNodes)
    val ports = selected.asSequence().flatMap { it.ports.asSequence() }.take(maximumNodes / 2).toList()
    val candidates = accessibilityVisibleComponents(graph, viewport, visibleComponentIds, maximumNodes)
    val components = (selected + candidates).distinctBy { it.component.id }.take(maximumNodes - ports.size)
        .sortedWith(compareBy<AccessibleComponent> { it.component.position.y }.thenBy { it.component.position.x }.thenBy { it.component.id })
    val includedIds = components.mapTo(mutableSetOf()) { it.component.id }
    return AccessibilityOverlayNodes(components, ports.filter { it.id.componentId in includedIds })
}
