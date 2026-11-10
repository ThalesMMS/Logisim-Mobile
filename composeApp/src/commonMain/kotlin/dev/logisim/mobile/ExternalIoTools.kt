package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The provider is shared with the scene's pairing tool; constructing tools opens no listener. */
fun externalIoEditorTools(companion: () -> CompanionClient? = { null }): List<EditorTool> {
    val bridge = ExternalIoRuntimeBridge(companion)
    return listOf(EditorTool("external-io", "External I/O", titleText = message("external_io_title"),
        runtime = { context -> bridge.Runtime(context) }) { context -> ExternalIoPanel(context, bridge) })
}

private val externalIoKinds = setOf(ExternalIoComponents.PortIo, ExternalIoComponents.RgbVideo,
    ExternalIoComponents.RealTimeClock, ExternalIoComponents.Telnet, ExternalIoComponents.ReptarLocalBus)

@Composable
private fun ExternalIoPanel(context: EditorToolContext, bridge: ExternalIoRuntimeBridge) {
    val text = rememberLocalizer()
    val component = context.selectedComponents.singleOrNull()?.takeIf { it.kind in externalIoKinds }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text("external_io_title"), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        if (bridge.entries.values.any { it.cleanupPending }) {
            Text(text("external_io_cleanup_failed"), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = bridge::retryCleanup, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text("external_io_cleanup_retry"))
            }
        }
        if (component == null) {
            Text(text("external_io_select"))
            return@Column
        }
        Text(component.label.ifBlank { text("component_${component.kind.name.lowercase()}") },
            style = MaterialTheme.typography.titleMedium)
        Text(text("external_io_instance", "path" to context.simulation.instancePath.ifEmpty { text("external_io_root") }))
        val key = externalIoInstanceKey(context, component)
        val state = context.simulation.componentStates[component.id]
        when (component.kind) {
            ExternalIoComponents.PortIo -> ExternalPortIoPanel(component, state as? PortIoState, context, key != null)
            ExternalIoComponents.RgbVideo -> ExternalVideoPanel(component, state as? VideoState)
            ExternalIoComponents.RealTimeClock -> {
                val sample = state as? RealTimeClockState
                Text(text("external_io_clock_help"))
                if (sample == null) Text(text("external_io_clock_no_sample")) else {
                    val seconds = component.attributes["timeType"] == "2"
                    val value = if (seconds) sample.epochMillis / 1000 -
                        if (sample.epochMillis < 0 && sample.epochMillis % 1000 != 0L) 1 else 0 else sample.epochMillis
                    SelectionContainer { Text(text("external_io_clock_sample", "value" to value, "unit" to if (seconds) "s" else "ms")) }
                }
            }
            ExternalIoComponents.ReptarLocalBus -> Text(text("external_io_reptar_unavailable"))
            ExternalIoComponents.Telnet -> {
                if (key == null) Text(text("external_io_instance_required")) else {
                    val entry = externalIoTelnetEntry(bridge, key, state as? TelnetState ?: TelnetState(), context.sendComponentEvent)
                    ExternalTelnetPanel(component, state as? TelnetState ?: TelnetState(), bridge, entry, context)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExternalPortIoPanel(component: CircuitComponent, state: PortIoState?, context: EditorToolContext, active: Boolean) {
    val text = rememberLocalizer()
    val count = component.attributeInt("number", 8).coerceIn(1, 64)
    val outputOnly = component.attributes["direction"] == "onlyOutput"
    Text(text("external_io_pin_help"))
    if (!active) Text(text("external_io_instance_required"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val value = state?.pins?.takeIf { index < it.width }?.bit(index) ?: LogicValue.UNKNOWN
            OutlinedButton(onClick = {
                if (!context.sendComponentEvent(component.id, PortIoTogglePin(index)))
                    context.showNotice(message("external_io_event_rejected"))
            }, enabled = active && !outputOnly, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text("external_io_pin", "pin" to index, "value" to externalIoLogicLabel(value)))
            }
        }
    }
}

internal fun externalIoLogicLabel(value: LogicValue): String = when (value) {
    LogicValue.LOW -> "0"; LogicValue.HIGH -> "1"; LogicValue.UNKNOWN -> "?"; LogicValue.ERROR -> "E"
}

@Composable
private fun ExternalVideoPanel(component: CircuitComponent, state: VideoState?) {
    val text = rememberLocalizer()
    val width = component.attributeInt("width", 128).coerceIn(2, 4096)
    val height = component.attributeInt("height", 128).coerceIn(2, 4096)
    Text(text("external_io_video_help"))
    Text(text("external_io_video_size", "width" to width, "height" to height))
    Text(text("external_io_video_cursor", "x" to (state?.cursorX ?: 0), "y" to (state?.cursorY ?: 0)))
    val description = text("external_io_video_a11y", "width" to width, "height" to height,
        "x" to (state?.cursorX ?: 0), "y" to (state?.cursorY ?: 0))
    Canvas(Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = description }) {
        drawRect(Color(0xff222222))
        val scale = minOf(size.width / width, size.height / height)
        val origin = Offset((size.width - width * scale) / 2, (size.height - height * scale) / 2)
        drawRect(Color.Black, origin, Size(width * scale, height * scale))
        clipRect(origin.x, origin.y, origin.x + width * scale, origin.y + height * scale) {
            state?.frame?.forEachColorRun { run ->
                drawRect(Color(run.argb), origin + Offset(run.x * scale, run.y * scale), Size(run.length * scale, scale))
            }
        }
    }
}

@Composable
private fun ExternalTelnetPanel(component: CircuitComponent, state: TelnetState, bridge: ExternalIoRuntimeBridge,
                                entry: ExternalIoTelnetEntry, context: EditorToolContext) {
    val text = rememberLocalizer()
    Text(text("external_io_telnet_help"))
    if (!bridge.ready) {
        Text(text("external_io_telnet_pair"))
        OutlinedButton(onClick = { context.openTool("companion-pairing") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text("companion_ui_title"))
        }
    }
    val active = entry.status != null
    Row(Modifier.fillMaxWidth()) {
        Checkbox(entry.allowLan, onCheckedChange = { entry.allowLan = it }, enabled = !active && !entry.startAttempted && !entry.busy)
        Text(text("external_io_telnet_lan"), modifier = Modifier.padding(top = 12.dp))
    }
    Text(text("external_io_telnet_lan_help"), style = MaterialTheme.typography.bodySmall)
    Text(text("external_io_telnet_status", "state" to text(externalTelnetStateKey(if (entry.paused)
        TelnetLinkState.Failed("") else entry.status?.link ?: state.link))))
    Text(text("external_io_telnet_queues", "input" to state.received.size, "output" to state.pendingOutput.size))
    entry.status?.let { status ->
        SelectionContainer { Text(text("external_io_telnet_endpoint", "address" to status.bindAddress, "port" to status.localPort)) }
        if (status.droppedInputBytes > 0 || status.droppedOutputBytes > 0)
            Text(text("external_io_telnet_drops", "input" to status.droppedInputBytes, "output" to status.droppedOutputBytes))
    }
    if (!active) {
        Button(onClick = { bridge.prepare(entry, component) }, enabled = bridge.ready && !entry.busy && !entry.startAttempted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("external_io_telnet_prepare")) }
        entry.pending?.let { pending ->
            HorizontalDivider()
            Text(text("external_io_telnet_review"), style = MaterialTheme.typography.titleMedium)
            Text(text("external_io_telnet_review_values", "port" to pending.configuration.port,
                "access" to text(if (pending.configuration.allowLan) "external_io_telnet_network" else "external_io_telnet_loopback"),
                "buffer" to pending.bufferSize, "mode" to text(if (pending.configuration.telnetMode) "external_io_yes" else "external_io_no")))
            val matches = runCatching { externalTelnetSettings(component, entry.allowLan) == entry.settings }.getOrDefault(false)
            if (!matches) Text(text("external_io_telnet_stale_plan"), color = MaterialTheme.colorScheme.error)
            Button(onClick = { bridge.confirm(entry) }, enabled = bridge.ready && matches && !entry.busy && !entry.cleanupPending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(text(if (entry.startAttempted) "external_io_telnet_retry_start" else "external_io_telnet_confirm"))
            }
            if (entry.startAttempted) OutlinedButton(onClick = { bridge.stop(entry) }, enabled = !entry.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("external_io_telnet_cancel_start")) }
        }
    } else {
        OutlinedButton(onClick = { bridge.stop(entry) }, enabled = !entry.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("external_io_telnet_stop")) }
        if (entry.paused) Button(onClick = { bridge.resume(entry) }, enabled = !entry.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text("external_io_telnet_retry")) }
    }
    if (entry.busy) Text(text("external_io_telnet_busy"))
    entry.notice?.let { Text(text(it), color = MaterialTheme.colorScheme.error) }
}

internal fun externalTelnetStateKey(link: TelnetLinkState): String = when (link) {
    TelnetLinkState.Stopped -> "external_io_telnet_state_stopped"
    TelnetLinkState.Listening -> "external_io_telnet_state_listening"
    TelnetLinkState.Connected -> "external_io_telnet_state_connected"
    is TelnetLinkState.Failed -> "external_io_telnet_state_failed"
}
