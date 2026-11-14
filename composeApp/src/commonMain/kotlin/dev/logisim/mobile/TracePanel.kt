package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceFrame
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceRamCellEntry
import dev.logisim.mobile.core.TraceRamCells
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalPicker
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.TraceWindow
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Run, Pause and clock Tick inside Signal recording, so a waveform can be watched while the simulation runs or steps
 * (C57). The editor wires them to its own simulation controls.
 */
class TraceSimulationControls(
    val running: Boolean,
    val enabled: Boolean,
    val onRun: () -> Unit,
    val onPause: () -> Unit,
    val onTick: (fullCycle: Boolean) -> Unit,
)

/** How often a running simulation refreshes the waveforms; capture itself follows every published state. */
internal const val TraceRefreshMillis = 100L

private enum class TraceCursor { A, B }

/** " › " joins a signal's instance path, from the main circuit down. */
internal fun traceInstancePath(path: List<String>): String = path.joinToString(" › ")

/**
 * The caller retains [recorder] with the project and supplies each captured simulation timestamp. With
 * [hostCaptures], the caller records every published state itself, in order, as Signal recording's tool hook does,
 * and the panel only shows them; otherwise the panel records each [simulation] it is given.
 */
@Composable
fun TracePanel(
    project: CircuitProject,
    simulation: SimulationState,
    recorder: TraceRecorder,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    sampleTime: Long = simulation.propagationTime,
    registry: ComponentRegistry = ComponentCatalog.registry,
    selectionEnabled: Boolean = true,
    onExportWaveDrom: (String) -> Unit = onExport,
    fileControls: @Composable (canRecord: Boolean) -> Unit = {},
    simulationControls: TraceSimulationControls? = null,
    hostCaptures: Boolean = false,
) {
    val localize = rememberLocalizer()
    var revision by remember(recorder) { mutableStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var orderDialog by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf(false) }
    // Opens on what was recorded, then follows the newest sample while it is in view (C57).
    var window by remember(recorder) { mutableStateOf(traceInitialWindow(recorder.firstTime, recorder.lastTime)) }
    var followedLast by remember(recorder) { mutableStateOf(recorder.lastTime) }
    var knownProject by remember(recorder) { mutableStateOf(project) }
    var cursorTarget by remember(recorder) { mutableStateOf(TraceCursor.A) }
    val running = simulationControls?.running == true
    LaunchedEffect(project, simulation, sampleTime) {
        // A host that records every state has already recorded this one, or a newer one: by the time this effect runs
        // the simulation may have moved on, and an older state must neither be recorded nor taken for a reset.
        if (!hostCaptures) {
            if (knownProject != project) {
                recorder.select(emptyList())
                knownProject = project
            }
            if ((recorder.lastTime ?: 0) > sampleTime) recorder.clear()
            recorder.capture(simulation, sampleTime)
        }
        // While running, the waveforms refresh on a timer instead of on every published state.
        if (!running) revision++
    }
    LaunchedEffect(recorder, running) {
        revision++
        while (running) {
            delay(TraceRefreshMillis)
            revision++
        }
    }
    val frames = remember(revision, recorder) { recorder.frames }
    LaunchedEffect(revision, recorder) {
        val last = frames.lastOrNull()?.time
        window = traceFollowWindow(window, followedLast, frames.firstOrNull()?.time, last)
        followedLast = last
    }
    val signals = recorder.signals
    val paused = recorder.paused
    val limit = maxOf(TraceDefaultSpan, (recorder.lastTime ?: 0) + traceTrailingRoom(window.span))
    val availableResult = remember(project, registry) { runCatching { TraceSignals.fromProject(project, registry) } }
    val available = availableResult.getOrDefault(emptyList())
    // Every signal of the project is named at once, so a name never changes with the selection (C64, C160).
    val names = remember(project, available, signals, localize) { traceSignalNames(project, available + signals, localize) }
    var confirmClear by remember(recorder) { mutableStateOf(false) }
    // The state this composition shows, unless the recording has already moved past it.
    fun captureShown() {
        if (sampleTime >= (recorder.lastTime ?: 0)) recorder.capture(simulation, sampleTime)
    }
    fun clear() {
        recorder.clear()
        captureShown()
        revision++
    }
    Column(modifier.fillMaxWidth().heightIn(min = 320.dp, max = 720.dp)) {
        simulationControls?.let { controls -> TraceSimulationRow(controls, simulation.ticks) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picker = true }, enabled = selectionEnabled) { Text(localize(Messages.TraceSelectSignals)) }
            TextButton(onClick = { orderDialog = true }, enabled = selectionEnabled && signals.size > 1) {
                Text(localize(Messages.TraceOrderSignals))
            }
            TextButton(onClick = { if (recorder.paused) recorder.resume() else recorder.pause(); revision++ }, enabled = selectionEnabled) {
                Text(localize(if (paused) Messages.TraceResume else Messages.TracePause))
            }
            TextButton(onClick = { table = !table }) { Text(localize(if (table) Messages.TraceTimeline else Messages.TraceTable)) }
            TextButton(onClick = { onExport(recorder.exportCsv()) }, enabled = frames.isNotEmpty()) { Text(localize(Messages.TraceExportCsv)) }
            TextButton(onClick = { onExportWaveDrom(recorder.exportWaveDrom { localize(it.labelText) }) },
                enabled = frames.isNotEmpty()) { Text(localize(Messages.TraceExportWaveDrom)) }
            // Clear discards every sample, so it sits at the end of the row, away from Pause, and asks first (#72).
            TextButton(onClick = { if (recorder.frames.size > 1) confirmClear = true else clear() }, enabled = selectionEnabled,
                modifier = Modifier.testTag("trace-clear")) { Text(localize(Messages.TraceClear)) }
        }
        fileControls(signals.isNotEmpty() && !paused)
        Text(localize(Messages.TraceSummary, "retained" to frames.size, "capacity" to recorder.capacity, "dropped" to recorder.droppedFrames, "unit" to recorder.timeUnit.notice), fontSize = 12.sp)
        fun zoom(factor: Double) { window = window.zoom(factor, (window.start + window.end) / 2, limit) }
        fun pan(direction: Int) { window = window.pan(direction * window.span / 2, limit) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { zoom(2.0) }) { Text(localize(Messages.TraceZoomIn)) }
            TextButton(onClick = { zoom(0.5) }) { Text(localize(Messages.TraceZoomOut)) }
            TextButton(onClick = { pan(-1) }) { Text(localize(Messages.TracePrevious)) }
            TextButton(onClick = { pan(1) }) { Text(localize(Messages.TraceNext)) }
            TextButton(onClick = { window = TraceWindow(0, maxOf(1, limit)) }) { Text(localize(Messages.TraceAllTime)) }
        }
        if (signals.isEmpty()) {
            Text(localize(Messages.TraceSelectHelp), Modifier.padding(12.dp))
        } else if (table) {
            TraceTable(signals, frames, localize(Messages.TraceTime), localize(Messages.TraceMissing),
                { names.of(it).title }, Modifier.weight(1f).fillMaxWidth(), unit = localize(recorder.timeUnit.notice))
        } else {
            Text(localize(if (cursorTarget == TraceCursor.A) "trace_window_gestures_a" else "trace_window_gestures_b",
                "start" to window.start, "end" to window.end, "unit" to recorder.timeUnit.notice),
                fontSize = 12.sp, modifier = Modifier.testTag("trace-window"))
            TraceTimeRuler(window, Modifier.fillMaxWidth().height(22.dp))
            val currentWindow by rememberUpdatedState(window)
            val currentLimit by rememberUpdatedState(limit)
            val first = recorder.cursorA?.toString() ?: "-"
            val second = recorder.cursorB?.toString() ?: "-"
            val waveformsLabel = localize("trace_waveforms")
            val waveformsState = localize("trace_waveforms_state", "start" to window.start, "end" to window.end,
                "unit" to recorder.timeUnit.notice, "first" to first, "second" to second)
            val actions = listOf(
                CustomAccessibilityAction(localize(Messages.TraceZoomIn)) { zoom(2.0); true },
                CustomAccessibilityAction(localize(Messages.TraceZoomOut)) { zoom(0.5); true },
                CustomAccessibilityAction(localize(Messages.TracePrevious)) { pan(-1); true },
                CustomAccessibilityAction(localize(Messages.TraceNext)) { pan(1); true },
                CustomAccessibilityAction(localize("trace_action_cursor_b_at_a")) {
                    val time = recorder.cursorA ?: window.start
                    recorder.setCursors(recorder.cursorA ?: time, time); revision++; true
                },
                CustomAccessibilityAction(localize("trace_action_cursor_a_at_b")) {
                    val time = recorder.cursorB ?: window.start
                    recorder.setCursors(time, recorder.cursorB ?: time); revision++; true
                },
            )
            // Pinch zooms around the fingers and a sideways drag pans (C63); a vertical drag still scrolls the rows,
            // and a tap reaches the row under it.
            Box(Modifier.weight(1f).fillMaxWidth().testTag("trace-waveforms")
                .semantics {
                    contentDescription = waveformsLabel
                    stateDescription = waveformsState
                    customActions = actions
                }
                .pointerInput(recorder) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoomBase: TraceWindow? = null
                        var zoomTotal = 1f
                        var focal = 0L
                        var panBase: TraceWindow? = null
                        var dragX = 0f
                        var dragY = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            if (pressed.size >= 2 || zoomBase != null) {
                                if (pressed.size >= 2) {
                                    val base = zoomBase ?: currentWindow.also { start ->
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        focal = (start.start + (centroid.x / width * start.span)).toLong().coerceIn(start.start, start.end)
                                        zoomBase = start
                                    }
                                    val factor = event.calculateZoom()
                                    if (factor.isFinite() && factor > 0f) zoomTotal *= factor
                                    window = base.zoom(zoomTotal.toDouble(), focal, currentLimit)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed.first()
                            val delta = change.positionChange()
                            dragX += delta.x
                            dragY += delta.y
                            if (panBase == null) {
                                if (abs(dragY) > viewConfiguration.touchSlop && abs(dragY) >= abs(dragX)) break
                                if (abs(dragX) > viewConfiguration.touchSlop && abs(dragX) > abs(dragY)) panBase = currentWindow
                            }
                            panBase?.let { base ->
                                window = base.pan(-(dragX / width * base.span).roundToLong(), currentLimit)
                                change.consume()
                            }
                        }
                    }
                }) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(signals.withIndex().toList(), key = { it.value.reference.key }) { (index, signal) ->
                        TraceRow(signal, index, names.of(signal), frames, window, recorder, onCursor = { time, second ->
                            val toB = second || cursorTarget == TraceCursor.B
                            recorder.setCursors(if (toB) recorder.cursorA else time, if (toB) time else recorder.cursorB)
                            revision++
                        })
                    }
                }
            }
        }
        val last = maxOf(1, limit).toFloat()
        Text(localize(Messages.TraceCursors, "first" to (recorder.cursorA ?: "-"), "second" to (recorder.cursorB ?: "-"), "distance" to (recorder.cursorDistance ?: "-")), fontSize = 12.sp)
        // Taps and the slider move the chosen cursor, so TalkBack reaches cursor B too (C63).
        val targetLabel = localize("trace_cursor_target")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = targetLabel }) {
            TraceCursor.entries.forEachIndexed { position, cursor ->
                SegmentedButton(selected = cursorTarget == cursor, onClick = { cursorTarget = cursor },
                    shape = SegmentedButtonDefaults.itemShape(position, TraceCursor.entries.size), icon = {},
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trace-cursor-target-${cursor.name.lowercase()}")) {
                    Text(localize(if (cursor == TraceCursor.A) "trace_cursor_target_a" else "trace_cursor_target_b"))
                }
            }
        }
        val sliderTime = (if (cursorTarget == TraceCursor.A) recorder.cursorA else recorder.cursorB) ?: window.start
        val sliderLabel = localize(if (cursorTarget == TraceCursor.A) Messages.TraceCursorA else "trace_cursor_b")
        Slider(value = sliderTime.toFloat().coerceIn(0f, last), valueRange = 0f..last,
            onValueChange = {
                if (cursorTarget == TraceCursor.A) recorder.setCursors(it.toLong(), recorder.cursorB)
                else recorder.setCursors(recorder.cursorA, it.toLong())
                revision++
            },
            modifier = Modifier.testTag("trace-cursor-slider").semantics { contentDescription = sliderLabel })
    }
    if (confirmClear) {
        AppAlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text(localize("trace_clear_confirm_title")) },
            text = { Text(localize.plural("trace_clear_confirm_text", frames.size.toLong())) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; if (selectionEnabled) clear() }, modifier = Modifier.testTag("trace-clear-confirm")) {
                    Text(localize(Messages.TraceClear))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(localize(Messages.ActionCancel)) } })
    }
    if (orderDialog) {
        TraceSignalOrderDialog(signals, selectionEnabled, nameOf = names::of, onClose = { orderDialog = false }) { reference, offset ->
            if (selectionEnabled && recorder.moveSignal(reference, offset)) revision++
        }
    }
    if (picker) {
        var query by remember { mutableStateOf("") }
        var showAll by remember { mutableStateOf(false) }
        var ramTarget by remember(project, registry) { mutableStateOf<TraceSignal?>(null) }
        fun updateSelection(next: List<TraceSignal>) {
            recorder.select(next)
            captureShown()
            revision++
        }
        val ram = ramTarget
        if (ram != null) {
            TraceRamCellDialog(ram, names.of(ram), signals, onDismiss = { ramTarget = null }, onAdd = { cells ->
                updateSelection(recorder.signals + cells)
                ramTarget = null
            })
        } else AppAlertDialog(onDismissRequest = { picker = false }, title = { Text(localize(Messages.TraceProjectSignals)) }, text = {
            // Loggable components by circuit instance, as Evolution's ComponentSelector lists them (C64).
            val groups = TraceSignalPicker.groups(project, available, signals, showAll, registry) { signal, _ ->
                query.isBlank() || "${names.of(signal).label} ${names.path(signal.reference.instancePath).joinToString(" ")}".contains(query.trim(), ignoreCase = true)
            }
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(localize(Messages.TraceFilter)) }, singleLine = true)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(showAll, role = Role.Switch) { showAll = it }
                    .testTag("trace-show-all"), verticalAlignment = Alignment.CenterVertically) {
                    Text(localize("trace_show_all_components"), Modifier.weight(1f))
                    Switch(showAll, onCheckedChange = null)
                }
                // A project the picker cannot list (recursive, or over the selector limit) says why, not just "empty".
                val listingError = availableResult.exceptionOrNull() as? Exception
                if (listingError != null) Text(localize(listingError.localizedDescription(message("trace_picker_empty"))),
                    Modifier.padding(vertical = 12.dp).testTag("trace-picker-error"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                else if (groups.isEmpty()) Text(localize("trace_picker_empty"), Modifier.padding(vertical = 12.dp), fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 420.dp).testTag("trace-picker-list")) {
                    groups.forEach { group ->
                        item(key = "group:${group.instancePath}") {
                            Text(traceInstancePath(names.path(group.instancePath)), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
                                    .testTag("trace-group-${group.instancePath.ifEmpty { "main" }}").semantics { heading() })
                        }
                        items(group.signals, key = { it.reference.key }) { signal ->
                            TracePickerRow(signal, names.of(signal), signals,
                                selectionEnabled, onToggle = { checked ->
                                    val current = recorder.signals
                                    if (checked != current.any { it.reference == signal.reference }) {
                                        updateSelection(if (checked) current + signal else current.filterNot { it.reference == signal.reference })
                                    }
                                }, onAddRamCell = { ramTarget = signal }, onRadix = { selected, radix ->
                                    recorder.setRadix(selected.reference, radix)
                                    revision++
                                })
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = false }) { Text(localize(Messages.TraceDone)) } })
    }
}

@Composable
private fun TraceSimulationRow(controls: TraceSimulationControls, ticks: Long) {
    val strings = rememberLocalizer()
    Row(Modifier.fillMaxWidth().testTag("trace-simulation-controls"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ChromeButton(EditorToolbarAction("trace-run", strings(if (controls.running) Messages.ActionPause else Messages.ActionRun),
            enabled = controls.enabled, icon = if (controls.running) EditorIcon.PAUSE else EditorIcon.PLAY) {
            if (controls.running) controls.onPause() else controls.onRun()
        }, tag = "trace-sim-run")
        ChromeButton(EditorToolbarAction("trace-tick", strings("simulation_control_tick_half"), enabled = controls.enabled,
            icon = EditorIcon.TICK) { controls.onTick(false) }, tag = "trace-sim-tick")
        ChromeButton(EditorToolbarAction("trace-tick-full", strings("simulation_control_tick_full"), enabled = controls.enabled,
            icon = EditorIcon.TICK_FULL) { controls.onTick(true) }, tag = "trace-sim-tick-full")
        Text(strings("simulation_ticks", "count" to ticks), fontSize = 12.sp, color = ChromeColors.Muted,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TracePickerRow(
    signal: TraceSignal,
    name: TraceSignalName,
    signals: List<TraceSignal>,
    selectionEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onAddRamCell: () -> Unit,
    onRadix: (TraceSignal, TraceRadix) -> Unit,
) {
    val localize = rememberLocalizer()
    val selected = signals.find { it.reference == signal.reference }
    val enabled = selectionEnabled && (selected != null || signals.size < TraceRecorder.MAX_SIGNALS)
    Column(Modifier.fillMaxWidth()) {
        // The checkbox and its labels toggle together; the RAM-cell and radix buttons stay separate targets.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected != null, enabled = enabled, role = Role.Checkbox,
            onValueChange = onToggle).testTag("trace-signal-${signal.reference.key}"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(selected != null, onCheckedChange = null, enabled = enabled)
            Column {
                Text("${name.label} [${signal.width}]", fontSize = 12.sp)
                // The instance path tells same-named signals apart; the internal key stays out of sight (C64).
                if (name.nested) Text(name.instance, fontSize = 10.sp, color = ChromeColors.Muted)
            }
        }
        Column(Modifier.padding(start = 48.dp)) {
            if (signal.ramAddressBits != null) {
                TextButton(onClick = onAddRamCell, enabled = selectionEnabled && signals.size < TraceRecorder.MAX_SIGNALS) {
                    Text(localize(Messages.TraceAddRamCell))
                }
            }
            if (selected != null) {
                // The full title, so two signals with the same label are not read the same way by TalkBack (C64, C160).
                TraceSignalRadixPicker(selected, name.title, selectionEnabled) { radix -> onRadix(selected, radix) }
            }
        }
    }
}

/**
 * Adds RAM cells by address in hexadecimal, as the memory editor shows them, one address or a whole range at a time
 * (X6). Addresses already recorded, or beyond the signal limit, are counted as skipped before confirming.
 */
@Composable
private fun TraceRamCellDialog(
    ram: TraceSignal,
    name: TraceSignalName,
    signals: List<TraceSignal>,
    onDismiss: () -> Unit,
    onAdd: (List<TraceSignal>) -> Unit,
) {
    val localize = rememberLocalizer()
    var input by remember(ram.reference) { mutableStateOf("") }
    val addressBits = requireNotNull(ram.ramAddressBits)
    val last = (1L shl addressBits) - 1
    val entry = TraceRamCells.parse(input, addressBits)
    val room = (TraceRecorder.MAX_SIGNALS - signals.size).coerceAtLeast(0)
    val recorded = signals.map { it.reference }.toSet()
    val cells = (entry as? TraceRamCellEntry.Addresses)?.let { addresses ->
        addresses.addresses(room + recorded.size).asSequence().mapNotNull { TraceSignals.ramCell(ram, it) }
            .filter { it.reference !in recorded }.take(room).toList()
    }.orEmpty()
    val requested = (entry as? TraceRamCellEntry.Addresses)?.count ?: 0L
    val skipped = requested - cells.size
    val lastHex = last.toString(16).uppercase()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localize(Messages.TraceAddRamCell)) },
        text = {
            Column {
                Text(name.title)
                OutlinedTextField(input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(localize("trace_ram_cells")) },
                    isError = entry is TraceRamCellEntry.Invalid || entry is TraceRamCellEntry.OutOfRange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.Characters),
                    supportingText = {
                        Text(when (entry) {
                            TraceRamCellEntry.Empty -> localize("trace_ram_cells_help", "last" to lastHex)
                            TraceRamCellEntry.Invalid -> localize("trace_ram_cells_invalid")
                            is TraceRamCellEntry.OutOfRange -> localize("trace_ram_cells_out_of_range", "last" to lastHex)
                            is TraceRamCellEntry.Addresses -> buildString {
                                append(localize.plural("trace_ram_cells_adds", cells.size.toLong()))
                                if (skipped > 0) append(' ').append(localize.plural("trace_ram_cells_skipped", skipped,
                                    "limit" to TraceRecorder.MAX_SIGNALS))
                            }
                        })
                    },
                    modifier = Modifier.testTag("trace-ram-address"))
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(cells) }, enabled = cells.isNotEmpty(), modifier = Modifier.testTag("trace-ram-add")) {
                Text(localize("trace_add_ram_cells"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localize(Messages.ActionCancel)) } },
    )
}

@Composable
private fun TraceSignalRadixPicker(signal: TraceSignal, label: String, enabled: Boolean, onChange: (TraceRadix) -> Unit) {
    val localize = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        TraceRadix.BINARY to Messages.PreferencesBinary,
        TraceRadix.DECIMAL to Messages.PreferencesUnsignedDecimal,
        TraceRadix.HEXADECIMAL to Messages.PreferencesHexadecimal,
    )
    val selected = localize(options.first { it.first == signal.radix }.second)
    val description = "$label, ${localize(Messages.PreferencesSignalRadix)}, $selected"
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag("trace-radix-${signal.reference.key}").semantics { contentDescription = description }) { Text(selected) }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (radix, label) ->
                DropdownMenuItem(text = { Text(localize(label)) }, onClick = {
                    expanded = false
                    onChange(radix)
                })
            }
        }
    }
}

/** Times along the waveforms: ticks 1, 2 or 5 × 10ⁿ apart, aligned with the rows below (C62). */
@Composable
private fun TraceTimeRuler(window: TraceWindow, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val ink = ChromeColors.Muted
    val style = TextStyle(fontSize = 9.sp, color = ink, fontFamily = FontFamily.Monospace)
    val spacing = with(LocalDensity.current) { 56.dp.toPx() }
    Canvas(modifier.testTag("trace-time-ruler").semantics { hideFromAccessibility() }) {
        for (time in traceRulerTicks(window, size.width, spacing)) {
            val x = ((time - window.start).toDouble() / window.span * size.width).toFloat()
            drawLine(ink, Offset(x, size.height * 0.65f), Offset(x, size.height), 1f)
            val text = measurer.measure(time.toString(), style)
            val left = (x - text.size.width / 2f).coerceIn(0f, max(0f, size.width - text.size.width))
            drawText(text, topLeft = Offset(left, 0f))
        }
        drawLine(ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
    }
}

/**
 * The table view as a grid (C62): a fixed time column and one column per signal, as wide as its name or widest value.
 * The header scrolls sideways and every row follows it, so columns stay aligned; a sideways swipe on the rows scrolls
 * the header too. TalkBack reads a row as one announcement, its time and then each signal's value, and finds a single
 * sideways scroll, the header's.
 */
@Composable
private fun TraceTable(
    signals: List<TraceSignal>,
    frames: List<TraceFrame>,
    timeLabel: String,
    missing: String,
    label: (TraceSignal) -> String,
    modifier: Modifier,
    unit: String,
) {
    val localize = rememberLocalizer()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    val density = LocalDensity.current
    val colors = traceColors()
    fun width(text: String) = with(density) { measurer.measure(text, style).size.width.toDp() }
    val timeWidth = maxOf(width(timeLabel), width((frames.lastOrNull()?.time ?: 0L).toString())) + 16.dp
    val labels = signals.map(label)
    val widths = signals.mapIndexed { index, signal ->
        maxOf(width(labels[index]), width(traceValueSample(signal.width, signal.radix)), width(missing)) + 16.dp
    }
    val separator = localize("trace_table_cell_separator")
    val scroll: ScrollState = rememberScrollState()
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, false)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().testTag("trace-table-header")) {
            Text(timeLabel, Modifier.width(timeWidth).padding(horizontal = 4.dp, vertical = 4.dp), style = style, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(scroll)) {
                labels.forEachIndexed { index, text ->
                    Text(text, Modifier.width(widths[index]).padding(horizontal = 4.dp, vertical = 4.dp).testTag("trace-table-header-$index"),
                        style = style, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().scrollable(scroll, Orientation.Horizontal, reverseDirection = reverse)) {
            itemsIndexed(frames) { row, frame ->
                val values = signals.mapIndexed { index, signal -> frame.values.getOrNull(index)?.let(signal.radix::format) ?: missing }
                val description = localize("trace_table_row_a11y", "time" to frame.time, "unit" to unit,
                    "values" to values.mapIndexed { index, value ->
                        localize("trace_table_cell_a11y", "signal" to labels[index], "value" to value)
                    }.joinToString(separator))
                Row(Modifier.fillMaxWidth().testTag("trace-table-row-$row")
                    .semantics(mergeDescendants = true) { contentDescription = description }) {
                    Text(frame.time.toString(), Modifier.width(timeWidth).padding(horizontal = 4.dp, vertical = 3.dp), style = style)
                    Row(Modifier.followHorizontalScroll(scroll)) {
                        signals.forEachIndexed { index, _ ->
                            val value = frame.values.getOrNull(index)
                            Text(values[index],
                                Modifier.width(widths[index]).padding(horizontal = 4.dp, vertical = 3.dp).testTag("trace-table-cell-$row-$index"),
                                style = style, color = if (value == null || value.isFullyDefined) ChromeColors.Ink else colors.line(value),
                                maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays a row's cells out at the header's sideways [scroll] offset, clipped to the row, without a scroll of its own for
 * gestures or TalkBack to find.
 */
private fun Modifier.followHorizontalScroll(scroll: ScrollState): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val width = if (constraints.hasBoundedWidth) placeable.width.coerceAtMost(constraints.maxWidth) else placeable.width
    layout(width, placeable.height) {
        placeable.placeRelative(-scroll.value.coerceAtMost((placeable.width - width).coerceAtLeast(0)), 0)
    }
}

@Composable
private fun TraceRow(
    signal: TraceSignal,
    index: Int,
    name: TraceSignalName,
    frames: List<TraceFrame>,
    window: TraceWindow,
    recorder: TraceRecorder,
    onCursor: (Long, Boolean) -> Unit,
) {
    val localize = rememberLocalizer()
    val colors = traceColors()
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = ChromeColors.Ink)
    val density = LocalDensity.current
    val slant = with(density) { 4.dp.toPx() }
    val cursorColor = ChromeColors.TraceCursor
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // The instance path, not the internal key, tells same-named signals apart (C64, C160).
        Text(name.title, Modifier.testTag("trace-row-title-$index"), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics {
            contentDescription = localize(Messages.TraceSignalA11y, "signal" to name.label, "instance" to name.instance)
        }.pointerInput(window) {
            detectTapGestures(onTap = { point ->
                onCursor(window.start + (point.x / size.width * window.span).toLong(), false)
            }, onLongPress = { point ->
                onCursor(window.start + (point.x / size.width * window.span).toLong(), true)
            })
        }) {
            val segments = traceSegments(frames, index, window, size.width)
            val top = size.height * 0.2f
            val bottom = size.height * 0.8f
            val middle = size.height / 2
            if (signal.width == 1) {
                var previousY: Float? = null
                for (segment in segments) {
                    val value = segment.value
                    val color = colors.line(value)
                    val y = when {
                        value == null || !value.isFullyDefined -> middle
                        value.bit(0) == LogicValue.HIGH -> top
                        else -> bottom
                    }
                    previousY?.let { if (it != y) drawLine(color, Offset(segment.x0, it), Offset(segment.x0, y), 2f) }
                    drawLine(color, Offset(segment.x0, y), Offset(segment.x1, y), 2f)
                    previousY = y
                }
            } else {
                // Two rails with slanted changes, as Evolution's chronogram draws a bus, and the value inside.
                for (segment in segments) {
                    val value = segment.value
                    val color = colors.line(value)
                    if (value == null) {
                        drawLine(color, Offset(segment.x0, middle), Offset(segment.x1, middle), 2f)
                        continue
                    }
                    val edge = min(slant, (segment.x1 - segment.x0) / 2)
                    val outline = Path().apply {
                        moveTo(segment.x0, middle)
                        lineTo(segment.x0 + edge, top)
                        lineTo(segment.x1 - edge, top)
                        lineTo(segment.x1, middle)
                        lineTo(segment.x1 - edge, bottom)
                        lineTo(segment.x0 + edge, bottom)
                        close()
                    }
                    if (!value.isFullyDefined) drawPath(outline, color.copy(alpha = 0.18f))
                    drawPath(outline, color, style = Stroke(width = 1.5f))
                }
                val labels = busSegmentLabels(segments, signal.radix::format,
                    measure = { measurer.measure(it, labelStyle).size.width.toFloat() }, padding = slant + 2f)
                for (text in labels) {
                    val layout = measurer.measure(text.text, labelStyle)
                    drawText(layout, topLeft = Offset((text.x0 + text.x1 - layout.size.width) / 2, middle - layout.size.height / 2))
                }
            }
            listOfNotNull(recorder.cursorA, recorder.cursorB).forEach { time ->
                if (time in window.start..window.end) {
                    val x = ((time - window.start).toDouble() / window.span * size.width).toFloat()
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), 1f)
                }
            }
        }
        val time = recorder.cursorA ?: recorder.lastTime
        if (time != null) {
            val value = recorder.valueAt(signal.reference, time)?.let(signal.radix::format)
            Text(value ?: localize(Messages.TraceMissing), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
