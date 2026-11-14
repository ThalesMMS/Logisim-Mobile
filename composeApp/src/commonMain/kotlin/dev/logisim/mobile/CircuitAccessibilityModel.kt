package dev.logisim.mobile

import androidx.compose.runtime.Immutable
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoLibrary
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PortDirection
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.namedTunnelGroups
import dev.logisim.mobile.core.outputPorts
import dev.logisim.mobile.core.portWidths
import dev.logisim.mobile.core.relativePoint

/**
 * Keys the canvas' per-circuit state (context menu, tips, placement hover). Immutable so Compose compares it by
 * value: as an unstable class, strong skipping compared it by identity, and the editor builds a new context on
 * every recomposition. A touch long-press selects first, so the menu it opened was dropped on the next frame (#326).
 */
@Immutable
data class CircuitAccessibilityContext(
    val documentIdentity: Long,
    val circuitId: String,
    val instancePath: List<String> = emptyList(),
)

data class AccessiblePortId(val componentId: String, val terminalName: String) {
    val key: String get() = "port:${componentId.length}:$componentId:$terminalName"
}

data class AccessiblePort(
    val id: AccessiblePortId,
    val label: String,
    val position: GridPoint,
    val direction: PortDirection,
    val declaredWidth: Int,
    val connectedPorts: List<AccessiblePortId>,
    val wireIndices: List<Int>,
)

data class AccessibleComponent(
    val component: CircuitComponent,
    val name: String,
    val kindName: String,
    val bounds: ComponentBounds,
    val ports: List<AccessiblePort>,
    /** Another component has the same name at the same point, so only the internal id can tell them apart. */
    val stacked: Boolean = false,
) {
    val key: String get() = "component:${component.id}"
    /** What the component's one-step action does for assistive technology, or null when it has none (X0). */
    val activation: AccessibleActivation? get() = when {
        component.kind == ComponentKind.INPUT -> if (component.width > 1) AccessibleActivation.TOGGLE_LOW_BIT else AccessibleActivation.TOGGLE
        component.kind == ComponentKind.CLOCK -> AccessibleActivation.TOGGLE
        component.kind == ComponentKind.BUTTON -> AccessibleActivation.PRESS
        IoLibrary.isInteractive(component.kind) -> AccessibleActivation.OPEN_CONTROLS
        else -> null
    }
    /** An input the action drives directly (a pin, clock or button), rather than through its inspector controls. */
    val isInteractiveInput: Boolean get() = activation.let { it != null && it != AccessibleActivation.OPEN_CONTROLS }
    /**
     * A multi-bit input pin: besides its low-bit toggle (a tap without a position), assistive technology sets its
     * whole value or toggles a chosen bit (X0).
     */
    val offersValueEntry: Boolean get() = component.kind == ComponentKind.INPUT && component.width > 1
    val kindText: LocalizedText get() = LocalizedText("component_${component.kind.name.lowercase()}", fallback = kindName)
    val nameText: LocalizedText get() = when {
        component.label.isNotBlank() || component.sharedLabel.isNotBlank() -> LocalizedText.literal(name)
        stacked -> message("a11y_explorer_unnamed_component", "kind" to kindText, "x" to component.position.x, "y" to component.position.y, "id" to component.id)
        else -> message("a11y_explorer_unnamed_component_at", "kind" to kindText, "x" to component.position.x, "y" to component.position.y)
    }
}

/**
 * The one-step action the Explorer and the canvas overlay offer for an input (X0), named for what it does: a 1-bit
 * pin or a clock toggles, a multi-bit pin toggles bit 0 (as a tap without a position), a Button is pressed and
 * released, and a DIP switch, joystick, keyboard or keypad opens the inspector that holds its controls.
 */
enum class AccessibleActivation { TOGGLE, TOGGLE_LOW_BIT, PRESS, OPEN_CONTROLS }

/** The action's label for [name]. */
fun AccessibleActivation.label(name: LocalizedText): LocalizedText = message(when (this) {
    AccessibleActivation.TOGGLE -> "a11y_explorer_activate"
    AccessibleActivation.TOGGLE_LOW_BIT -> "a11y_input_toggle_low_bit"
    AccessibleActivation.PRESS -> "a11y_button_press"
    AccessibleActivation.OPEN_CONTROLS -> "a11y_io_open_controls"
}, "name" to name)

/**
 * Runs [component]'s action and returns whether it applied. [AccessibleActivation.TOGGLE_LOW_BIT] toggles bit 0
 * whatever the pin's radix (a tap on a hex or octal pin would advance its low digit instead);
 * [AccessibleActivation.OPEN_CONTROLS] opens the inspector where the editor shows the controls, and is refused, with
 * the reason in the status line, where it does not; the others are the editor's activation.
 */
fun CircuitAccessibilityActions.activate(component: AccessibleComponent): Boolean {
    val id = component.component.id
    return when (component.activation) {
        null -> false
        AccessibleActivation.TOGGLE_LOW_BIT -> toggleInputBit?.invoke(id, 0) ?: activateComponent(id)
        AccessibleActivation.OPEN_CONTROLS -> openControls?.invoke(id) ?: run { openProperties(id); true }
        else -> activateComponent(id)
    }
}

/** Connectivity is physical, including named tunnels. Splitter terminals retain their distinct branches. */
class CircuitAccessibilityGraph(
    document: CircuitDocument,
    registry: ComponentRegistry,
    context: CircuitAccessibilityContext = CircuitAccessibilityContext(0L, document.id),
) {
    val context = context.copy(instancePath = context.instancePath.toList())
    val document: CircuitDocument = document.copy(components = document.components.map {
        it.copy(attributes = it.attributes.toMap(), explicitPorts = it.explicitPorts.toList())
    }, wires = document.wires.toList())
    val components: List<AccessibleComponent>
    val ports: Map<AccessiblePortId, AccessiblePort>
    private val componentsById: Map<String, AccessibleComponent>
    private val wirePortIds: List<List<AccessiblePortId>>

    init {
        val document = this.document
        require(context.circuitId == document.id)
        val duplicateLabels = document.components.filter { it.label.isNotBlank() }
            .groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
        val layouts = document.components.associate { it.id to accessibleLayout(it, registry) }
        val allPorts = document.components.flatMap { component ->
            layouts.getValue(component.id).ports.map { terminal ->
                AccessiblePort(AccessiblePortId(component.id, terminal.name), terminal.label.ifBlank { terminal.name },
                    terminal.position, terminal.direction, terminal.width, emptyList(), emptyList())
            }
        }
        val points = (allPorts.map { it.position } + document.wires.flatMap { listOf(it.from, it.to) }).distinct()
        val pointIndex = points.withIndex().associate { it.value to it.index }
        val physical = AccessibilityDisjointSet(points.size)
        val horizontal = points.withIndex().groupBy { it.value.y }.mapValues { (_, values) -> values.sortedBy { it.value.x } }
        val vertical = points.withIndex().groupBy { it.value.x }.mapValues { (_, values) -> values.sortedBy { it.value.y } }
        for (wire in document.wires) {
            val row = if (wire.from.y == wire.to.y) horizontal[wire.from.y].orEmpty() else vertical[wire.from.x].orEmpty()
            val isHorizontal = wire.from.y == wire.to.y
            val low = if (isHorizontal) minOf(wire.from.x, wire.to.x) else minOf(wire.from.y, wire.to.y)
            val high = if (isHorizontal) maxOf(wire.from.x, wire.to.x) else maxOf(wire.from.y, wire.to.y)
            var index = lowerBound(row, low, isHorizontal)
            val first = pointIndex.getValue(wire.from)
            while (index < row.size) {
                val item = row[index++]
                val coordinate = if (isHorizontal) item.value.x else item.value.y
                if (coordinate > high) break
                physical.join(first, item.index)
            }
        }
        for (group in document.namedTunnelGroups()) {
            val positions = group.mapNotNull { pointIndex[it.position] }
            positions.drop(1).forEach { physical.join(positions.first(), it) }
        }
        val portsByNet = allPorts.groupBy { physical.root(pointIndex.getValue(it.position)) }
            .mapValues { (_, values) -> values.map { it.id } }
        val indexInNet = portsByNet.values.flatMap { members -> members.mapIndexed { index, id -> id to index } }.toMap()
        val wiresByNet = document.wires.withIndex().groupBy { physical.root(pointIndex.getValue(it.value.from)) }
            .mapValues { (_, values) -> values.map { it.index } }
        wirePortIds = document.wires.map { portsByNet[physical.root(pointIndex.getValue(it.from))].orEmpty() }
        ports = allPorts.associate { port ->
            val net = physical.root(pointIndex.getValue(port.position))
            port.id to port.copy(connectedPorts = OtherPortsView(portsByNet.getValue(net), indexInNet.getValue(port.id)),
                wireIndices = wiresByNet[net].orEmpty())
        }
        fun kindName(component: CircuitComponent) = registry.definition(component.kind)?.fileName ?: component.kind.name
        fun displayLabel(component: CircuitComponent) = component.label.ifBlank { component.sharedLabel }.ifBlank { kindName(component) }
        fun located(component: CircuitComponent) = component.label.isBlank() || component.label in duplicateLabels
        // The position tells apart duplicate labels and unlabeled components; the internal id is added only for
        // components with the same name stacked on one point.
        val stackedNames = document.components.filter(::located)
            .groupingBy { Triple(displayLabel(it), it.position.x, it.position.y) }.eachCount().filterValues { it > 1 }.keys
        components = document.components.sortedWith(compareBy<CircuitComponent> { it.position.y }.thenBy { it.position.x }.thenBy { it.id })
            .map { component ->
                val kind = kindName(component)
                val label = displayLabel(component)
                val stacked = located(component) && Triple(label, component.position.x, component.position.y) in stackedNames
                val name = when {
                    !located(component) -> label
                    stacked -> "$label (${component.position.x}, ${component.position.y}), ${component.id}"
                    else -> "$label (${component.position.x}, ${component.position.y})"
                }
                AccessibleComponent(component, name, kind, layouts.getValue(component.id).bounds,
                    layouts.getValue(component.id).ports.map { ports.getValue(AccessiblePortId(component.id, it.name)) }, stacked)
            }
        componentsById = components.associateBy { it.component.id }
    }

    fun component(id: String): AccessibleComponent? = componentsById[id]

    /** Component terminals on the net this wire belongs to, so a wire is not described by coordinates alone. */
    fun wirePorts(index: Int): List<AccessiblePortId> = wirePortIds.getOrNull(index).orEmpty()

    fun wireConnectionText(index: Int, language: AppLanguage, limit: Int = 4): LocalizedText {
        val ids = wirePorts(index)
        if (ids.isEmpty()) return message("a11y_explorer_unconnected")
        val names = ids.take(limit).mapNotNull { connectedPortText(it)?.resolve(language) }.joinToString("; ")
        return if (ids.size <= limit) message("a11y_explorer_wire_connects", "ports" to names)
        else message("a11y_explorer_wire_connects_more", "ports" to names, "count" to ids.size - limit)
    }

    fun connectedPortName(id: AccessiblePortId): String? = ports[id]?.let { port ->
        componentsById[id.componentId]?.let { "${it.name}, ${port.label}" }
    }

    fun connectedPortText(id: AccessiblePortId): LocalizedText? = ports[id]?.let { port ->
        componentsById[id.componentId]?.let { message("a11y_explorer_named_port", "component" to it.nameText, "port" to port.label) }
    }

    fun search(query: String, language: AppLanguage = AppLanguage.ENGLISH): List<AccessibleComponent> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return components
        return components.filter { component ->
            val text = "${component.name} ${component.kindText.resolve(language)} ${component.kindName} ${component.component.id} ${component.ports.joinToString { it.label }}"
            terms.all { text.contains(it, ignoreCase = true) }
        }
    }
}

fun accessibleSignal(value: LogicVector?): LocalizedText {
    if (value == null) return message("a11y_value_unavailable")
    val number = value.toULongOrNull()
    if (number != null) {
        if (value.width == 1) return message(if (number == 0uL) "a11y_value_low" else "a11y_value_high")
        val digits = number.toString(16).uppercase().padStart((value.width + 3) / 4, '0')
        return message("a11y_value_known_bus", "width" to value.width, "hex" to digits)
    }
    val errors = (0 until value.width).count { value.bit(it) == LogicValue.ERROR }
    val unknown = (0 until value.width).count { value.bit(it) == LogicValue.UNKNOWN }
    return when {
        errors == value.width -> LocalizedText("a11y_value_error", quantity = value.width.toLong())
        unknown == value.width -> LocalizedText("a11y_value_unknown", quantity = value.width.toLong())
        else -> message("a11y_value_partial", "width" to value.width,
            "errors" to LocalizedText("a11y_error_bit_count", quantity = errors.toLong()),
            "unknown" to LocalizedText("a11y_unknown_bit_count", quantity = unknown.toLong()),
            "binary" to value.display)
    }
}

fun AccessiblePort.signal(simulation: SimulationState): LogicVector? = simulation.portVectors[position]
    ?: simulation.portValues[position]?.let(LogicVector::fromBit)

fun AccessiblePort.description(simulation: SimulationState): LocalizedText {
    val value = signal(simulation)
    return message(if (value == null && declaredWidth == 0) "a11y_explorer_port_inferred_description" else "a11y_explorer_port_description", "direction" to message(when (direction) {
        PortDirection.INPUT -> "a11y_explorer_direction_input"
        PortDirection.OUTPUT -> "a11y_explorer_direction_output"
        PortDirection.INOUT -> "a11y_explorer_direction_inout"
    }), "name" to label, "width" to LocalizedText("a11y_width_bits", quantity = (value?.width ?: declaredWidth).toLong()), "value" to accessibleSignal(value))
}

class AccessibleConnectionSession private constructor(
    val start: AccessiblePortId?,
    private val context: CircuitAccessibilityContext?,
) {
    constructor() : this(null, null)

    fun choose(graph: CircuitAccessibilityGraph, targetId: AccessiblePortId, connect: (GridPoint, GridPoint) -> Boolean): AccessibleConnectionResult {
        if (start != null && context != graph.context) {
            return AccessibleConnectionResult(AccessibleConnectionSession(), message("a11y_explorer_connection_missing"))
        }
        val target = graph.ports[targetId]
            ?: return AccessibleConnectionResult(AccessibleConnectionSession(), message("a11y_explorer_connection_missing"))
        if (start == null) return AccessibleConnectionResult(AccessibleConnectionSession(targetId, graph.context),
            message("a11y_explorer_pending_connection", "name" to graph.connectedPortText(targetId)))
        if (start == targetId) return AccessibleConnectionResult(AccessibleConnectionSession(), message("a11y_explorer_connection_cancelled"))
        val from = graph.ports[start]
            ?: return AccessibleConnectionResult(AccessibleConnectionSession(), message("a11y_explorer_connection_missing"))
        val applied = from.position != target.position && connect(from.position, target.position)
        return AccessibleConnectionResult(AccessibleConnectionSession(), message(if (applied) "a11y_explorer_connection_created" else "a11y_explorer_connection_failed"), applied)
    }
}

data class AccessibleConnectionResult(val session: AccessibleConnectionSession, val notice: LocalizedText, val connected: Boolean = false)

fun AccessibleComponent.signal(simulation: SimulationState): LogicVector? = simulation.vectorValues[component.id]
    ?: simulation.componentValues[component.id]?.let(LogicVector::fromBit)

/** Uses elapsed time supplied by the host, with no clock or screen-reader dependency. */
class AccessibilityAnnouncementGate(private val minimumIntervalMillis: Long = 1_500) {
    private var previous: LocalizedText? = null
    private var lastTime = Long.MIN_VALUE

    init { require(minimumIntervalMillis >= 0) }

    fun accept(notice: LocalizedText, elapsedMillis: Long, explicitUserAction: Boolean): LocalizedText? {
        require(elapsedMillis >= 0)
        if (notice == previous) return null
        if (!explicitUserAction && lastTime != Long.MIN_VALUE && elapsedMillis - lastTime < minimumIntervalMillis) return null
        previous = notice
        lastTime = elapsedMillis
        return notice
    }
}

private fun accessibleLayout(component: CircuitComponent, registry: ComponentRegistry): ComponentLayout {
    registry.definition(component.kind)?.let { return registry.layout(component) }
    if (component.explicitPorts.isNotEmpty()) return ComponentLayout(component.explicitPorts.map {
        ComponentTerminal(it.name, component.relativePoint(it.position), it.direction, it.width, it.label)
    }, component.bounds())
    val inputs = component.inputPorts()
    val outputs = component.outputPorts()
    return ComponentLayout((inputs + outputs).distinct().mapIndexed { index, position ->
        val direction = when { position in inputs && position in outputs -> PortDirection.INOUT
            position in inputs -> PortDirection.INPUT
            else -> PortDirection.OUTPUT }
        ComponentTerminal("port$index", position, direction, component.portWidths()[position] ?: component.width)
    }, component.bounds())
}

private fun lowerBound(values: List<IndexedValue<GridPoint>>, coordinate: Int, horizontal: Boolean): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high) ushr 1
        val value = if (horizontal) values[middle].value.x else values[middle].value.y
        if (value < coordinate) low = middle + 1 else high = middle
    }
    return low
}

private class AccessibilityDisjointSet(size: Int) {
    private val parents = IntArray(size) { it }
    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }
    fun join(a: Int, b: Int) { parents[root(a)] = root(b) }
}

private class OtherPortsView(private val members: List<AccessiblePortId>, private val excluded: Int) : AbstractList<AccessiblePortId>() {
    override val size: Int get() = members.size - 1
    override fun get(index: Int): AccessiblePortId {
        if (index !in indices) throw IndexOutOfBoundsException(index.toString())
        return members[if (index < excluded) index else index + 1]
    }
}
