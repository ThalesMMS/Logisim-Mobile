package dev.logisim.mobile

import dev.logisim.mobile.core.ProjectBuiltinLibraries
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.unit.em
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.CreationDefaults
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.localizedString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val LocalCompactExplorerKeyboard = compositionLocalOf { false }

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
    /** A long-press menu item of a component, routed like the visual menu (C42); null where the host has none. */
    val componentMenu: ((ComponentMenuItem, String) -> Unit)? = null,
    /** Opens the value dialog of an input pin, returning whether it opened (X0). */
    val editInputValue: ((String) -> Boolean)? = null,
    /** Toggles one bit of an input pin, returning whether it did (X0). */
    val toggleInputBit: ((String, Int) -> Boolean)? = null,
    /**
     * Opens the inspector holding the controls of a DIP switch, joystick, keyboard or keypad, returning whether the
     * controls are there (X0); null opens the properties unconditionally.
     */
    val openControls: ((String) -> Boolean)? = null,
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
    availableDefinitions: List<ComponentDefinition> = registry.definitions,
    /** The creation defaults [CircuitAccessibilityActions.insertComponent] applies, so suggestions match the shape inserted. */
    creationDefaults: CreationDefaults = CreationDefaults(),
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
    // Insert's position choice, kept while the Explorer stays open; null follows the selection (X1).
    var insertPosition by remember { mutableStateOf<AccessibleInsertPosition?>(null) }
    var lastInsertion by remember(graph.context) { mutableStateOf<GridPoint?>(null) }
    var bitChooser by remember(graph.context) { mutableStateOf<String?>(null) }
    var restoreInsertFocus by remember { mutableStateOf(false) }
    val insertFocus = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val accessibilityFocusReturn = rememberAccessibilityFocusReturn()
    var notice by remember(graph.context) { mutableStateOf<LocalizedText?>(null) }
    var refreshAfter by remember(graph.context) { mutableStateOf<SimulationState?>(null) }
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
    val searchLabel = localizedString("a11y_explorer_search")
    val compactKeyboard = LocalCompactExplorerKeyboard.current
    val filtered = remember(graph, query, language) { graph.search(query, language) }
    LaunchedEffect(simulation, refreshAfter) {
        if (refreshAfter != null && simulation !== refreshAfter) { captured = simulation; refreshAfter = null }
    }
    // Closing the insert dialog would otherwise leave TalkBack on the first node of the sheet
    // window (its drag handle); return input and TalkBack focus to the control that opened it.
    LaunchedEffect(insertDialog, restoreInsertFocus) {
        if (insertDialog || !restoreInsertFocus) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == "commands" }) listState.scrollToItem(3)
        // When the dialog window goes away, the sheet window regains focus and Compose restores
        // the field that was focused before (the search box). Request focus only after that.
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        withFrameNanos { }
        withFrameNanos { }
        runCatching { insertFocus.requestFocus() }
        // TalkBack does not follow the restored input focus across the window change.
        accessibilityFocusReturn.moveTo("a11y-explorer-insert")
        // Clear the request last: it is a key of this effect, so clearing it earlier cancels the effect.
        restoreInsertFocus = false
    }
    LaunchedEffect(searchFocused, viewportSize, searchSize, compactKeyboard) {
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

    Column(modifier.fillMaxSize().onSizeChanged { viewportSize = it }
        .padding(horizontal = 12.dp, vertical = if (compactKeyboard) 0.dp else 12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("a11y-circuit-list").focusGroup().semantics {
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
                val searchCaption: @Composable () -> Unit = {
                    Text(searchLabel, maxLines = if (compactKeyboard) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(query, { query = it }, label = searchCaption.takeUnless { compactKeyboard },
                    placeholder = searchCaption.takeIf { compactKeyboard },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                        .bringIntoViewRequester(searchVisibility).onFocusChanged { searchFocused = it.isFocused }
                        .onSizeChanged { searchSize = it }
                        .then(if (compactKeyboard) Modifier.semantics { contentDescription = searchLabel } else Modifier)
                        .testTag("a11y-circuit-search"), singleLine = true,
                    textStyle = LocalTextStyle.current.copy(lineHeight = 1.25.em))
            }
            item(key = "commands") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showWires = false }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { selected = !showWires }) { Text(localizedString("a11y_explorer_components")) }
                    OutlinedButton(onClick = { showWires = true }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { selected = showWires }) { Text(localizedString("a11y_explorer_wires")) }
                    OutlinedButton(onClick = {
                        // Make Insert the sheet's focused node, so the window restores it when the dialog closes.
                        runCatching { insertFocus.requestFocus() }
                        insertDialog = true
                    }, modifier = Modifier.heightIn(min = 48.dp)
                        .focusRequester(insertFocus).focusProperties { canFocus = true }.testTag("a11y-explorer-insert")) {
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
                    .toggleable(value = automatic, role = Role.Checkbox, onValueChange = { enabled ->
                        if (!enabled) captured = simulation
                        refreshAfter = null
                        notice = null
                        automatic = enabled
                    })) {
                    Checkbox(automatic, onCheckedChange = null)
                    Text(localizedString("a11y_explorer_auto"), Modifier.weight(1f).padding(vertical = 12.dp))
                }
            }
            if (pending != null) item(key = "connection") {
                TextButton(onClick = { connection = AccessibleConnectionSession(); notice = message("a11y_explorer_connection_cancelled") },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedString("a11y_explorer_cancel_connection")) }
            }
            if (showWires) {
                if (document.wires.isEmpty()) item { Text(localizedString("a11y_explorer_no_wires")) }
                itemsIndexed(document.wires, key = { index, wire -> "$index:${wire.from}:${wire.to}" }) { index, wire ->
                    val nameText = message("a11y_explorer_wire", "index" to index + 1, "x1" to wire.from.x, "y1" to wire.from.y,
                        "x2" to wire.to.x, "y2" to wire.to.y)
                    val connects = graph.wireConnectionText(index, language).resolve(language)
                    val name = nameText.resolve(language)
                    val value = currentValues.portVectors[wire.from] ?: currentValues.portVectors[wire.to]
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
                        isTraversalGroup = true
                        collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                    }) {
                        TextButton(onClick = { actions.selectWire(index) }, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("a11y-wire-$index").semantics {
                                contentDescription = "$name. $connects"
                                selected = index in selectedWireIndices
                                stateDescription = accessibleSignal(value).resolve(language)
                                customActions = listOf(CustomAccessibilityAction(message("a11y_explorer_remove", "name" to nameText).resolve(language)) {
                                    removed(nameText) { actions.removeWire(index) }
                                })
                            }) { ExplorerButtonCaption(name) }
                        Text(connects)
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
                    val activation = component.activation
                    val activateText = activation?.label(component.nameText)?.resolve(language)
                    val setValueText = message("a11y_input_set_value", "name" to component.nameText).resolve(language)
                    val toggleBitText = message("a11y_input_toggle_bit", "name" to component.nameText).resolve(language)
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
                                    if (activateText != null) add(CustomAccessibilityAction(activateText) {
                                        val applied = actions.activate(component)
                                        if (applied && component.isInteractiveInput) refreshAfter = simulation
                                        applied
                                    })
                                    if (component.offersValueEntry) {
                                        actions.editInputValue?.let { edit ->
                                            add(CustomAccessibilityAction(setValueText) {
                                                edit(component.component.id).also { if (!it) notice = message("a11y_explorer_operation_failed") }
                                            })
                                        }
                                        if (actions.toggleInputBit != null) add(CustomAccessibilityAction(toggleBitText) {
                                            bitChooser = component.component.id; true
                                        })
                                    }
                                }
                            }) { Text(localizedString(component.nameText), style = MaterialTheme.typography.titleSmall) }
                        Text(localizedString(component.kindText))
                        Text(value)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { actions.openProperties(component.component.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(editText) }
                            if (activateText != null) TextButton(onClick = {
                                if (!actions.activate(component)) notice = message("a11y_explorer_operation_failed")
                                else if (component.isInteractiveInput) refreshAfter = simulation
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(activateText) }
                            if (component.offersValueEntry) {
                                actions.editInputValue?.let { edit ->
                                    TextButton(onClick = {
                                        if (!edit(component.component.id)) notice = message("a11y_explorer_operation_failed")
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(setValueText)
                                    }
                                }
                                if (actions.toggleInputBit != null) TextButton(onClick = { bitChooser = component.component.id },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(toggleBitText) }
                            }
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
        val status = notice
        if (!compactKeyboard) Text(
            if (status == null) localizedString("a11y_explorer_captured", "ticks" to currentValues.ticks)
            else localizedString(status),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics {
                if (status != null) liveRegion = LiveRegionMode.Polite
            },
        )
    }
    if (insertDialog) {
        val selected = document.components.lastOrNull { it.id in selectedComponents }
        val position = insertPosition?.takeIf { selected != null || it == AccessibleInsertPosition.NEXT_FREE || it == AccessibleInsertPosition.CUSTOM }
            ?: if (selected != null) AccessibleInsertPosition.RIGHT_OF_SELECTED else AccessibleInsertPosition.NEXT_FREE
        AccessibleInsertDialog(availableDefinitions, position, hasSelection = selected != null,
            onPosition = { insertPosition = it },
            suggest = { kind, chosen -> accessibleInsertSuggestion(document, registry, kind, chosen, selected, lastInsertion, creationDefaults) },
            onDismiss = { insertDialog = false; restoreInsertFocus = true }) { kind, point ->
            val result = actions.insertComponent(kind, point)
            if (result) {
                insertDialog = false
                restoreInsertFocus = true
                lastInsertion = point
                notice = message("a11y_explorer_inserted", "name" to kind.localizedLabel(), "x" to point.x, "y" to point.y)
            } else notice = message("a11y_explorer_operation_failed")
            result
        }
    }
    bitChooser?.let(graph::component)?.let { component ->
        InputBitChooserDialog(component.nameText, component.component.width, currentValues.vectorValues[component.component.id],
            onToggle = { bit ->
                if (actions.toggleInputBit?.invoke(component.component.id, bit) == true) refreshAfter = simulation
                else notice = message("a11y_explorer_operation_failed")
                bitChooser = null
            },
            onDismiss = { bitChooser = null })
    }
}

/**
 * Visible caption of an Explorer button whose spoken label is already its `contentDescription`.
 * Compose iOS joins the description with the merged child text, so the caption stays out of the
 * semantics tree; Android reads the same description as before (#328).
 */
@Composable
private fun ExplorerButtonCaption(text: String) {
    Text(text, Modifier.clearAndSetSemantics {})
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
        }) { ExplorerButtonCaption(actionText) }
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

/**
 * Insert component: a type, and where it goes. Beside the selection or the next free spot need no coordinates and
 * never cover another component (X1); Custom coordinates shows X and Y with number keyboards, steps of 10 and values
 * snapped to the grid. Types are sorted by their spoken names under library headings.
 *
 * The dialog is one scrolling list, the types included, so a swipe anywhere moves it and a focused field is kept
 * above the keyboard. X's keyboard has Next (to Y) and Y's has Done (clears the focus and hides the keyboard): iOS's number pad has no
 * Return key, so its fields use the numbers and punctuation keyboard, which has one and a minus sign
 * ([InsertCoordinateImeOptions]).
 */
@Composable
private fun AccessibleInsertDialog(
    availableDefinitions: List<ComponentDefinition>,
    position: AccessibleInsertPosition,
    hasSelection: Boolean,
    onPosition: (AccessibleInsertPosition) -> Unit,
    suggest: (ComponentKind, AccessibleInsertPosition) -> GridPoint?,
    onDismiss: () -> Unit,
    onInsert: (ComponentKind, GridPoint) -> Boolean,
) {
    var query by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("100") }
    var y by remember { mutableStateOf("100") }
    // Why the last choice inserted nothing: typed coordinates that are not integers, or no free spot nearby.
    var refusal by remember { mutableStateOf<String?>(null) }
    val yFocus = remember { FocusRequester() }
    val language = LocalAppLanguage.current
    val groups = remember(availableDefinitions, query, language) { availableDefinitions.filter {
        it.kind.localizedLabel().resolve(language).contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) || it.kind.name.contains(query, ignoreCase = true)
    }.groupBy { it.library }.toList().map { (library, definitions) ->
        ProjectBuiltinLibraries.displayName(library).resolve(language) to
            definitions.sortedWith(compareBy({ it.kind.localizedLabel().resolve(language).lowercase() }, { it.kind.name }))
    }.sortedBy { it.first.lowercase() } }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(localizedString("a11y_explorer_insert")) },
        text = {
          // The dialog's own focus and keyboard, not the window's behind it.
          val keyboard = LocalSoftwareKeyboardController.current
          val focusManager = LocalFocusManager.current
          Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(localizedString("a11y_explorer_insert_kind")) }, singleLine = true,
                modifier = Modifier.testTag("a11y-insert-kind"))
            Text(localizedString("a11y_insert_position"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Column(Modifier.selectableGroup()) {
                for ((choice, key) in listOf(
                    AccessibleInsertPosition.RIGHT_OF_SELECTED to "a11y_insert_right_of_selected",
                    AccessibleInsertPosition.BELOW_SELECTED to "a11y_insert_below_selected",
                    AccessibleInsertPosition.NEXT_FREE to "a11y_insert_next_free",
                    AccessibleInsertPosition.CUSTOM to "a11y_insert_custom",
                )) {
                    val enabled = hasSelection || (choice != AccessibleInsertPosition.RIGHT_OF_SELECTED && choice != AccessibleInsertPosition.BELOW_SELECTED)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = position == choice, enabled = enabled, role = Role.RadioButton) { onPosition(choice); refusal = null }
                        .testTag("a11y-insert-position-${choice.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = position == choice, onClick = null, enabled = enabled)
                        Text(localizedString(key), Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                    }
                }
            }
            if (position == AccessibleInsertPosition.CUSTOM) {
                Text(localizedString("a11y_explorer_insert_coordinates"))
                InsertCoordinateField(x, { x = it; refusal = null }, localizedString("a11y_explorer_insert_x"), "a11y-insert-x",
                    ImeAction.Next) { yFocus.requestFocus() }
                InsertCoordinateField(y, { y = it; refusal = null }, localizedString("a11y_explorer_insert_y"), "a11y-insert-y",
                    ImeAction.Done, Modifier.focusRequester(yFocus)) {
                    // Hiding alone leaves the iOS keyboard up while Y keeps the focus; clearing it lets the keyboard go.
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }
            refusal?.let { key ->
                Text(localizedString(key), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("a11y-insert-refusal").semantics { liveRegion = LiveRegionMode.Polite })
            }
            // The types are part of the dialog's one scrolling list: a second, nested list would scroll on its own
            // and hide the dialog's other rows behind its fixed height.
            Column(Modifier.fillMaxWidth()) {
                for ((library, definitions) in groups) {
                    key("library:$library") {
                        Text(library, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                    }
                    for (definition in definitions) key(definition.kind.name) {
                        TextButton(onClick = {
                            val custom = position == AccessibleInsertPosition.CUSTOM
                            val point = if (custom) accessibleInsertionPoint(x, y) else suggest(definition.kind, position)
                            refusal = when {
                                point != null -> null
                                custom -> "a11y_explorer_insert_invalid"
                                else -> "a11y_insert_no_free_position"
                            }
                            if (point != null) onInsert(definition.kind, point)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(localizedString(definition.kind.localizedLabel())) }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(localizedString("a11y_explorer_close")) } })
}

/** A coordinate with − and + buttons that move it by one grid step of 10; its keyboard's [imeAction] runs [onImeAction]. */
@Composable
private fun InsertCoordinateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    imeAction: ImeAction,
    fieldModifier: Modifier = Modifier,
    onImeAction: () -> Unit,
) {
    val language = LocalAppLanguage.current
    fun step(delta: Int) = onValueChange(((value.trim().toIntOrNull()?.let { (it + 5).floorDiv(10) * 10 } ?: 100) + delta).toString())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { step(-10) }, modifier = Modifier.size(48.dp).testTag("$tag-minus")
            .semantics { contentDescription = message("placement_stepper_decrease", "name" to label).resolve(language) }) {
            Text("−", modifier = Modifier.clearAndSetSemantics {})
        }
        OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction,
                platformImeOptions = InsertCoordinateImeOptions),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            modifier = Modifier.weight(1f).then(fieldModifier).testTag(tag))
        IconButton(onClick = { step(10) }, modifier = Modifier.size(48.dp).testTag("$tag-plus")
            .semantics { contentDescription = message("placement_stepper_increase", "name" to label).resolve(language) }) {
            Text("+", modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** Typed coordinates, snapped to the nearest point of the 10-unit grid; null when either is not an integer. */
fun accessibleInsertionPoint(x: String, y: String): GridPoint? {
    fun snap(value: Int) = (value.toLong() + 5).floorDiv(10L).times(10L).coerceIn(Int.MIN_VALUE.toLong() / 10 * 10, Int.MAX_VALUE.toLong() / 10 * 10).toInt()
    return GridPoint(snap(x.trim().toIntOrNull() ?: return null), snap(y.trim().toIntOrNull() ?: return null))
}
