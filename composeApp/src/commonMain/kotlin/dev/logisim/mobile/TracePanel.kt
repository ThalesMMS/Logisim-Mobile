package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceFrame
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.TraceWindow
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry

/** The caller retains [recorder] with the project and supplies each captured simulation timestamp. */
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
) {
    val localize = rememberLocalizer()
    var revision by remember(recorder) { mutableStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var orderDialog by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf(false) }
    var window by remember(recorder) { mutableStateOf(TraceWindow(0, 20)) }
    var knownProject by remember(recorder) { mutableStateOf(project) }
    LaunchedEffect(project, simulation, sampleTime) {
        if (knownProject != project) {
            recorder.select(emptyList())
            knownProject = project
        }
        if ((recorder.lastTime ?: 0) > sampleTime) recorder.clear()
        recorder.capture(simulation, sampleTime)
        revision++
    }
    val frames = remember(revision, recorder) { recorder.frames }
    val signals = recorder.signals
    val paused = recorder.paused
    val limit = maxOf(20, recorder.lastTime ?: 0)
    Column(modifier.fillMaxWidth().heightIn(min = 320.dp, max = 720.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picker = true }, enabled = selectionEnabled) { Text(localize(Messages.TraceSelectSignals)) }
            TextButton(onClick = { orderDialog = true }, enabled = selectionEnabled && signals.size > 1) {
                Text(localize(Messages.TraceOrderSignals))
            }
            TextButton(onClick = { if (recorder.paused) recorder.resume() else recorder.pause(); revision++ }, enabled = selectionEnabled) {
                Text(localize(if (paused) Messages.TraceResume else Messages.TracePause))
            }
            TextButton(onClick = { recorder.clear(); recorder.capture(simulation, sampleTime); revision++ }, enabled = selectionEnabled) { Text(localize(Messages.TraceClear)) }
            TextButton(onClick = { table = !table }) { Text(localize(if (table) Messages.TraceTimeline else Messages.TraceTable)) }
            TextButton(onClick = { onExport(recorder.exportCsv()) }, enabled = frames.isNotEmpty()) { Text(localize(Messages.TraceExportCsv)) }
            TextButton(onClick = { onExportWaveDrom(recorder.exportWaveDrom { localize(it.labelText) }) },
                enabled = frames.isNotEmpty()) { Text(localize(Messages.TraceExportWaveDrom)) }
        }
        fileControls(signals.isNotEmpty() && !paused)
        Text(localize(Messages.TraceSummary, "retained" to frames.size, "capacity" to recorder.capacity, "dropped" to recorder.droppedFrames, "unit" to recorder.timeUnit.notice), fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { window = window.zoom(2.0, (window.start + window.end) / 2, limit) }) { Text(localize(Messages.TraceZoomIn)) }
            TextButton(onClick = { window = window.zoom(0.5, (window.start + window.end) / 2, limit) }) { Text(localize(Messages.TraceZoomOut)) }
            TextButton(onClick = { window = window.pan(-window.span / 2, limit) }) { Text(localize(Messages.TracePrevious)) }
            TextButton(onClick = { window = window.pan(window.span / 2, limit) }) { Text(localize(Messages.TraceNext)) }
            TextButton(onClick = { window = TraceWindow(0, maxOf(1, limit)) }) { Text(localize(Messages.TraceAllTime)) }
        }
        if (signals.isEmpty()) {
            Text(localize(Messages.TraceSelectHelp), Modifier.padding(12.dp))
        } else if (table) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item { Text(localize(Messages.TraceTime) + "\t" + signals.joinToString("\t") { localize(it.labelText) }, fontFamily = FontFamily.Monospace) }
                items(frames) { frame ->
                    val values = frame.values.mapIndexed { index, value ->
                        value?.let(signals[index].radix::format) ?: localize(Messages.TraceMissing)
                    }
                    Text("${frame.time}\t" + values.joinToString("\t"),
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        } else {
            Text(localize(Messages.TraceWindow, "start" to window.start, "end" to window.end, "unit" to recorder.timeUnit.notice), fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(signals.withIndex().toList(), key = { it.value.reference.key }) { (index, signal) ->
                    TraceRow(signal, index, frames, window, recorder, onCursor = { time, second ->
                        recorder.setCursors(if (second) recorder.cursorA else time, if (second) time else recorder.cursorB)
                        revision++
                    })
                }
            }
        }
        val last = maxOf(1, limit).toFloat()
        Text(localize(Messages.TraceCursors, "first" to (recorder.cursorA ?: "-"), "second" to (recorder.cursorB ?: "-"), "distance" to (recorder.cursorDistance ?: "-")), fontSize = 12.sp)
        Slider(value = (recorder.cursorA ?: window.start).toFloat().coerceIn(0f, last), valueRange = 0f..last,
            onValueChange = { recorder.setCursors(it.toLong(), recorder.cursorB); revision++ },
            modifier = Modifier.semantics { contentDescription = localize(Messages.TraceCursorA) })
    }
    if (orderDialog) {
        TraceSignalOrderDialog(signals, selectionEnabled, onClose = { orderDialog = false }) { reference, offset ->
            if (selectionEnabled && recorder.moveSignal(reference, offset)) revision++
        }
    }
    if (picker) {
        var query by remember { mutableStateOf("") }
        val available = remember(project, registry) { TraceSignals.fromProject(project, registry) }
        var ramTarget by remember(project, registry) { mutableStateOf<TraceSignal?>(null) }
        fun updateSelection(next: List<TraceSignal>) {
            recorder.select(next)
            recorder.capture(simulation, sampleTime)
            revision++
        }
        val ram = ramTarget
        if (ram != null) {
            TraceRamCellDialog(ram, signals, onDismiss = { ramTarget = null }, onAdd = { cell ->
                updateSelection(recorder.signals + cell)
                ramTarget = null
            })
        } else AppAlertDialog(onDismissRequest = { picker = false }, title = { Text(localize(Messages.TraceProjectSignals)) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(localize(Messages.TraceFilter)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    val choices = available + signals.filter { it.reference.ramAddress != null }
                    items(choices.filter { "${localize(it.labelText)} ${it.reference.key}".contains(query, ignoreCase = true) }, key = { it.reference.key }) { signal ->
                        Row {
                            val selected = signals.find { it.reference == signal.reference }
                            Checkbox(selected != null, onCheckedChange = { checked ->
                                val current = recorder.signals
                                if (checked != current.any { it.reference == signal.reference }) {
                                    updateSelection(if (checked) current + signal else current.filterNot { it.reference == signal.reference })
                                }
                            }, enabled = selectionEnabled && (selected != null || signals.size < TraceRecorder.MAX_SIGNALS))
                            Column(Modifier.padding(top = 8.dp)) {
                                Text("${localize(signal.labelText)} [${signal.width}]", fontSize = 12.sp)
                                Text(signal.reference.key, fontSize = 10.sp)
                                if (signal.ramAddressBits != null) {
                                    TextButton(onClick = { ramTarget = signal }, enabled = selectionEnabled && signals.size < TraceRecorder.MAX_SIGNALS) {
                                        Text(localize(Messages.TraceAddRamCell))
                                    }
                                }
                                if (selected != null) {
                                    TraceSignalRadixPicker(selected, selectionEnabled) { radix ->
                                        recorder.setRadix(selected.reference, radix)
                                        revision++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = false }) { Text(localize(Messages.TraceDone)) } })
    }
}

@Composable
private fun TraceRamCellDialog(
    ram: TraceSignal,
    signals: List<TraceSignal>,
    onDismiss: () -> Unit,
    onAdd: (TraceSignal) -> Unit,
) {
    val localize = rememberLocalizer()
    var input by remember(ram.reference) { mutableStateOf("") }
    val raw = input.trim()
    val address = if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toLongOrNull(16)
        else raw.toLongOrNull()
    val cell = address?.let { TraceSignals.ramCell(ram, it) }
    val duplicate = cell != null && signals.any { it.reference == cell.reference }
    val available = cell != null && !duplicate && signals.size < TraceRecorder.MAX_SIGNALS
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localize(Messages.TraceAddRamCell)) },
        text = {
            Column {
                Text("${localize(ram.labelText)} · ${ram.reference.key}")
                OutlinedTextField(input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(localize(Messages.TraceRamAddress)) },
                    isError = raw.isNotEmpty() && (cell == null || duplicate),
                    supportingText = {
                        Text(if (duplicate) localize(Messages.TraceDuplicateSignal)
                            else localize(Messages.TraceRamAddressRange, "last" to ((1L shl requireNotNull(ram.ramAddressBits)) - 1)))
                    })
            }
        },
        confirmButton = {
            TextButton(onClick = { cell?.let(onAdd) }, enabled = available) {
                Text(localize(Messages.TraceAddRamCell))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localize(Messages.ActionCancel)) } },
    )
}

@Composable
private fun TraceSignalRadixPicker(signal: TraceSignal, enabled: Boolean, onChange: (TraceRadix) -> Unit) {
    val localize = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        TraceRadix.BINARY to Messages.PreferencesBinary,
        TraceRadix.DECIMAL to Messages.PreferencesUnsignedDecimal,
        TraceRadix.HEXADECIMAL to Messages.PreferencesHexadecimal,
    )
    val selected = localize(options.first { it.first == signal.radix }.second)
    val description = "${localize(signal.labelText)}, ${localize(Messages.PreferencesSignalRadix)}, $selected"
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description }) { Text(selected) }
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

@Composable
private fun TraceRow(
    signal: TraceSignal,
    index: Int,
    frames: List<TraceFrame>,
    window: TraceWindow,
    recorder: TraceRecorder,
    onCursor: (Long, Boolean) -> Unit,
) {
    val localize = rememberLocalizer()
    val visible = remember(frames, window) {
        val before = frames.lastOrNull { it.time < window.start }
        listOfNotNull(before) + frames.filter { it.time in window.start..window.end }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${localize(signal.labelText)} · ${signal.reference.key}", fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics {
            contentDescription = localize(Messages.TraceSignalA11y, "signal" to signal.labelText, "instance" to if (signal.reference.instancePath.isEmpty()) message(Messages.TraceMainInstance) else signal.reference.instancePath)
        }.pointerInput(window) {
            detectTapGestures(onTap = { point ->
                onCursor(window.start + (point.x / size.width * window.span).toLong(), false)
            }, onLongPress = { point ->
                onCursor(window.start + (point.x / size.width * window.span).toLong(), true)
            })
        }) {
            fun x(time: Long) = ((time - window.start).toDouble() / window.span * size.width).toFloat().coerceIn(0f, size.width)
            for ((position, frame) in visible.withIndex()) {
                val value = frame.values.getOrNull(index)
                val next = visible.getOrNull(position + 1)?.time ?: window.end
                val color = when {
                    value == null -> Color.Gray
                    value.errorMask != 0uL -> Color.Red
                    value.unknownMask != 0uL -> Color.Blue
                    else -> Color(0xFF08792F)
                }
                val y = if (signal.width > 1) size.height / 2 else
                    if (value?.bit(0) == LogicValue.HIGH) size.height * 0.2f else size.height * 0.8f
                drawLine(color, Offset(x(frame.time), y), Offset(x(next), y), if (signal.width > 1) 5f else 2f)
                if (position > 0) drawLine(color, Offset(x(frame.time), size.height * 0.2f), Offset(x(frame.time), size.height * 0.8f), 1f)
            }
            listOfNotNull(recorder.cursorA, recorder.cursorB).forEach { time ->
                if (time in window.start..window.end) drawLine(Color(0xFF8C2999), Offset(x(time), 0f), Offset(x(time), size.height), 1f)
            }
        }
        val time = recorder.cursorA ?: recorder.lastTime
        if (time != null) {
            val value = recorder.valueAt(signal.reference, time)?.let(signal.radix::format)
            Text(value ?: localize(Messages.TraceMissing), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
