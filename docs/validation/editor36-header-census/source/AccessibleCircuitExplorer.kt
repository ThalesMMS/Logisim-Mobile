package dev.logisim.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.localizedString
import kotlinx.coroutines.launch

/** Every command is supplied by the editor so keyboard, reader, and canvas edits use the same history. */
data class CircuitAccessibilityActions(
    val selectComponent: (String) -> Unit,
    val openProperties: (String) -> Unit,
    val removeComponent: (String) -> Boolean,
    val activateComponent: (String) -> Boolean,
    val selectWire: (Int) -> Unit,
    val removeWire: (Int) -> Boolean,
    val connect: (GridPoint, GridPoint) -> Boolean,
    val insertComponent: (ComponentKind, GridPoint) -> Boolean,
)

/** A nonspatial route to the full circuit. Values are frozen until requested to avoid reader chatter. */
@Composable
fun AccessibleCircuitExplorer(
    document: CircuitDocument,
    simulation: SimulationState,
    registry: ComponentRegistry,
    selectedComponents: Set<String>,
    selectedWireIndices: Set<Int>,
    actions: CircuitAccessibilityActions,
    modifier: Modifier = Modifier,
    context: CircuitAccessibilityContext = CircuitAccessibilityContext(0L, document.id),
) {
    val graph = remember(document, registry, context) { CircuitAccessibilityGraph(document, registry, context) }
    var query by remember { mutableStateOf("") }
    var showWires by remember { mutableStateOf(false) }
    var expanded by remember(graph.context) { mutableStateOf(emptySet<String>()) }
    var connection by remember(graph.context) { mutableStateOf(AccessibleConnectionSession()) }
    val pending = connection.start
    var captured by remember(graph) { mutableStateOf(simulation) }
    var automatic by remember { mutableStateOf(false) }
    var insertDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LocalizedText?>(null) }
    var refreshAfter by remember { mutableStateOf<SimulationState?>(null) }
    val currentValues = if (automatic) simulation else captured
    val language = LocalAppLanguage.current
    val searchFocus = remember { FocusRequester() }
    val searchVisibility = remember { BringIntoViewRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var searchSize by remember { mutableStateOf(IntSize.Zero) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val title = localizedString("a11y_explorer_title")
    val filtered = remember(graph, query, language) { graph.search(query, language) }
    LaunchedEffect(simulation, refreshAfter) {
        if (refreshAfter != null && simulation !== refreshAfter) { captured = simulation; refreshAfter = null }
    }
    LaunchedEffect(searchFocused, viewportSize, searchSize) {
        if (searchFocused) {
            withFrameNanos { }
            searchVisibility.bringIntoView()
        }
    }

    fun removed(name: LocalizedText, action: () -> Boolean): Boolean {
        val applied = action()
        notice = if (applied) message("a11y_explorer_item_removed", "name" to name)
        else message("a11y_explorer_operation_failed")
        return applied
    }

    fun choosePort(port: AccessiblePort) {
        val result = connection.choose(graph, port.id, actions.connect)
        connection = result.session
        notice = result.notice
    }

    LazyColumn(modifier.padding(12.dp).fillMaxSize().testTag("a11y-circuit-list").onSizeChanged { viewportSize = it }.focusGroup().semantics {
        paneTitle = title
        isTraversalGroup = true
        collectionInfo = CollectionInfo(if (showWires) document.wires.size else filtered.size, 1)
    }
        .onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed) -> {
                    scope.launch {
                        listState.scrollToItem(2)
                        withFrameNanos { }
                        searchFocus.requestFocus()
                    }
                    true
                }
                event.key == Key.Escape && pending != null -> {
                    connection = AccessibleConnectionSession()
                    notice = message("a11y_explorer_connection_cancelled")
                    true
                }
                else -> false
            }
        }, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "title") {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        item(key = "help") {
            Text(localizedString("a11y_explorer_help"), style = MaterialTheme.typography.bodyMedium)
        }
        item(key = "search") {
            OutlinedTextField(query, { query = it }, label = { Text(localizedString("a11y_explorer_search")) },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                    .bringIntoViewRequester(searchVisibility).onFocusChanged { searchFocused = it.isFocused }
                    .onSizeChanged { searchSize = it }
                    .testTag("a11y-circuit-search"), singleLine = true)
        }
        item(key = "commands") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showWires = false }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { selected = !showWires }) { Text(localizedString("a11y_explorer_components")) }
                OutlinedButton(onClick = { showWires = true }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { selected = showWires }) { Text(localizedString("a11y_explorer_wires")) }
                OutlinedButton(onClick = { insertDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localizedString("a11y_explorer_insert"))
                }
                OutlinedButton(onClick = {
                    captured = simulation
                    notice = message("a11y_explorer_refreshed", "ticks" to simulation.ticks)
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("a11y-refresh-values")) { Text(localizedString("a11y_explorer_refresh")) }
            }
        }
        item(key = "automatic") {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("a11y-auto-refresh")
                .toggleable(value = automatic, role = Role.Checkbox, onValueChange = { automatic = it })) {
                Checkbox(automatic, onCheckedChange = null)
                Text(localizedString("a11y_explorer_auto"), Modifier.weight(1f).padding(vertical = 12.dp))
            }
        }
        item(key = "captured") {
            Text(localizedString("a11y_explorer_captured", "ticks" to currentValues.ticks), style = MaterialTheme.typography.bodySmall)
        }
        if (pending != null) item(key = "connection") {
            TextButton(onClick = { connection = AccessibleConnectionSession(); notice = message("a11y_explorer_connection_cancelled") },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedString("a11y_explorer_cancel_connection")) }
        }
        notice?.let { current -> item(key = "notice") {
            Text(localizedString(current), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } }
        if (showWires) {
            if (document.wires.isEmpty()) item { Text(localizedString("a11y_explorer_no_wires")) }
            itemsIndexed(document.wires, key = { index, wire -> "$index:${wire.from}:${wire.to}" }) { index, wire ->
                val nameText = message("a11y_explorer_wire", "index" to index + 1, "x1" to wire.from.x, "y1" to wire.from.y,
                    "x2" to wire.to.x, "y2" to wire.to.y)
                val name = nameText.resolve(language)
                val value = currentValues.portVectors[wire.from] ?: currentValues.portVectors[wire.to]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
                    isTraversalGroup = true
                    collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                }) {
                    TextButton(onClick = { actions.selectWire(index) }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("a11y-wire-$index").semantics {
                            selected = index in selectedWireIndices
                            stateDescription = accessibleSignal(value).resolve(language)
                            customActions = listOf(CustomAccessibilityAction(message("a11y_explorer_remove", "name" to nameText).resolve(language)) {
                                removed(nameText) { actions.removeWire(index) }
                            })
                        }) { Text(name) }
                    Text(accessibleSignal(value).resolve(language))
                    TextButton(onClick = { removed(nameText) { actions.removeWire(index) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localizedString("a11y_explorer_remove", "name" to nameText))
                    }
                }
            }
        } else {
            item(key = "count") { Text(localizedString("a11y_explorer_component_count", "count" to filtered.size), style = MaterialTheme.typography.bodySmall) }
            if (filtered.isEmpty()) item { Text(localizedString("a11y_explorer_empty")) }
            itemsIndexed(filtered, key = { _, component -> component.key }) { index, component ->
                val value = accessibleComponentValue(component, currentValues).resolve(language)
                val selectText = message("a11y_explorer_select", "name" to component.nameText).resolve(language)
                val editText = message("a11y_explorer_edit", "name" to component.nameText).resolve(language)
                val removeText = message("a11y_explorer_remove", "name" to component.nameText).resolve(language)
                val activateText = message("a11y_explorer_activate", "name" to component.nameText).resolve(language)
                val isSelected = component.component.id in selectedComponents
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)).padding(8.dp).semantics {
                        isTraversalGroup = true
                        collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                    }) {
                    TextButton(onClick = { actions.selectComponent(component.component.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(component.key).semantics {
                            contentDescription = selectText
                            selected = isSelected
                            stateDescription = message(if (isSelected) "a11y_explorer_selected" else "a11y_explorer_component_state",
                                "kind" to component.kindText, "value" to value).resolve(language)
                            customActions = buildList {
                                add(CustomAccessibilityAction(editText) { actions.openProperties(component.component.id); true })
                                add(CustomAccessibilityAction(removeText) { removed(component.nameText) { actions.removeComponent(component.component.id) } })
                                if (component.isInteractiveInput) add(CustomAccessibilityAction(activateText) {
                                    val applied = actions.activateComponent(component.component.id)
                                    if (applied) refreshAfter = simulation
                                    applied
                                })
                            }
                        }) { Text(localizedString(component.nameText), style = MaterialTheme.typography.titleSmall) }
                    Text(localizedString(component.kindText))
                    Text(value)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { actions.openProperties(component.component.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(editText) }
                        if (component.isInteractiveInput) TextButton(onClick = {
                            if (!actions.activateComponent(component.component.id)) notice = message("a11y_explorer_operation_failed")
                            else refreshAfter = simulation
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(activateText) }
                        TextButton(onClick = { removed(component.nameText) { actions.removeComponent(component.component.id) } },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(removeText) }
                        TextButton(onClick = { expanded = if (component.component.id in expanded) expanded - component.component.id else expanded + component.component.id },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(localizedString(if (component.component.id in expanded) "a11y_explorer_hide_ports" else "a11y_explorer_ports", "name" to component.nameText))
                        }
                    }
                    if (component.component.id in expanded) component.ports.forEach { port ->
                        AccessiblePortRow(graph, port, currentValues, pending != null, ::choosePort, actions)
                    }
                }
            }
        }
    }
    if (insertDialog) AccessibleInsertDialog(registry, onDismiss = { insertDialog = false }) { kind, point ->
        val result = actions.insertComponent(kind, point)
        if (result) {
            insertDialog = false
            notice = message("a11y_explorer_inserted", "name" to kind.localizedLabel(), "x" to point.x, "y" to point.y)
        } else notice = message("a11y_explorer_operation_failed")
        result
    }
}

@Composable
private fun AccessiblePortRow(
    graph: CircuitAccessibilityGraph,
    port: AccessiblePort,
    simulation: SimulationState,
    hasStartingPort: Boolean,
    onChoose: (AccessiblePort) -> Unit,
    actions: CircuitAccessibilityActions,
) {
    var showConnections by remember(port.id) { mutableStateOf(false) }
    var visibleConnections by remember(port.id) { mutableStateOf(25) }
    val language = LocalAppLanguage.current
    val name = graph.connectedPortText(port.id)
    val description = localizedString(port.description(simulation))
    Column(Modifier.padding(start = 12.dp, top = 4.dp).semantics { isTraversalGroup = true }) {
        Text(description)
        val actionText = localizedString(if (hasStartingPort) "a11y_explorer_complete_connection" else "a11y_explorer_start_connection", "name" to name)
        TextButton(onClick = { onChoose(port) }, modifier = Modifier.heightIn(min = 48.dp).testTag(port.id.key).semantics {
            contentDescription = actionText
            stateDescription = description
        }) { Text(actionText) }
        TextButton(onClick = { showConnections = !showConnections }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(localizedString("a11y_explorer_connections", "name" to name))
        }
        if (showConnections) {
            if (port.connectedPorts.isEmpty() && port.wireIndices.isEmpty()) Text(localizedString("a11y_explorer_unconnected"))
            port.connectedPorts.take(visibleConnections).forEach { other ->
                TextButton(onClick = { actions.selectComponent(other.componentId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(message("a11y_explorer_select", "name" to graph.connectedPortText(other)).resolve(language))
                }
            }
            if (port.connectedPorts.size > visibleConnections) TextButton(onClick = { visibleConnections += 25 }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(localizedString("a11y_explorer_more_connections"))
            }
            Text(localizedString("a11y_explorer_wire_count", "count" to port.wireIndices.size))
        }
    }
}

@Composable
private fun AccessibleInsertDialog(registry: ComponentRegistry, onDismiss: () -> Unit, onInsert: (ComponentKind, GridPoint) -> Boolean) {
    var query by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("100") }
    var y by remember { mutableStateOf("100") }
    var invalid by remember { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    val definitions = remember(registry, query, language) { registry.definitions.filter {
        it.kind.localizedLabel().resolve(language).contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) || it.kind.name.contains(query, ignoreCase = true)
    }.sortedWith(compareBy({ it.library }, { it.fileName }, { it.kind.name })) }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(localizedString("a11y_explorer_insert")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(localizedString("a11y_explorer_insert_kind")) }, singleLine = true)
            Text(localizedString("a11y_explorer_insert_coordinates"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x, { x = it; invalid = false }, label = { Text(localizedString("a11y_explorer_insert_x")) }, singleLine = true)
                OutlinedTextField(y, { y = it; invalid = false }, label = { Text(localizedString("a11y_explorer_insert_y")) }, singleLine = true)
            }
            if (invalid) Text(localizedString("a11y_explorer_insert_invalid"), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LazyColumn(Modifier.heightIn(max = 260.dp).fillMaxWidth()) {
                items(definitions, key = { it.kind.name }) { definition ->
                    TextButton(onClick = {
                        val point = accessibleInsertionPoint(x, y)
                        invalid = point == null
                        if (point != null) onInsert(definition.kind, point)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(localizedString(definition.kind.localizedLabel())) }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(localizedString("a11y_explorer_close")) } })
}

fun accessibleInsertionPoint(x: String, y: String): GridPoint? {
    val point = GridPoint(x.toIntOrNull() ?: return null, y.toIntOrNull() ?: return null)
    return point.takeIf { it.x % 10 == 0 && it.y % 10 == 0 }
}
