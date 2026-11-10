package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun TraceFileControls(context: EditorToolContext, canRecord: Boolean, recording: TraceFileRecording,
    files: TraceFileActions?, onFailure: (LocalizedText) -> Unit) {
    val localize = rememberLocalizer()
    var header by remember { mutableStateOf(true) }
    var append by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(TraceFileFormat.TIMESTAMPED) }
    var selecting by remember { mutableStateOf(false) }
    var selectionFailure by remember { mutableStateOf<LocalizedText?>(null) }
    val stopped = recording.state == TraceFileRecordingState.STOPPED
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        if (stopped) {
            Checkbox(header, { header = it }, enabled = !selecting)
            Text(localize("trace_file_header"))
            if (files?.supportsAppend == true) {
                Checkbox(append, { append = it }, enabled = !selecting)
                Text(localize("trace_file_append"))
            }
            TextButton(enabled = files != null && canRecord && !selecting,
                onClick = { context.exportProjectGraph {
                    selecting = true
                    selectionFailure = null
                    files?.select(append) { result ->
                        selecting = false
                        when (result) {
                            is TraceFileOpenResult.Opened -> {
                                if (context.isCurrentEditorContext() && context.projectExports.available) {
                                    recording.start(result.writer, header, format)
                                } else result.writer.close {}
                            }
                            is TraceFileOpenResult.Failure -> { selectionFailure = result.notice; onFailure(result.notice) }
                            TraceFileOpenResult.Cancelled -> Unit
                        }
                    }
                } }) { Text(localize("trace_file_start")) }
        } else {
            TextButton(enabled = recording.state != TraceFileRecordingState.STOPPING, onClick = {
                if (recording.state == TraceFileRecordingState.PAUSED) recording.resume() else recording.pause()
            }) { Text(localize(if (recording.state == TraceFileRecordingState.PAUSED) "trace_file_resume" else "trace_file_pause")) }
            TextButton(enabled = recording.state != TraceFileRecordingState.STOPPING, onClick = recording::stop) {
                Text(localize("trace_file_stop"))
            }
            Text(localize(when (recording.state) {
                TraceFileRecordingState.PAUSED -> "trace_file_paused"
                TraceFileRecordingState.STOPPING -> "trace_file_stopping"
                else -> "trace_file_recording"
            }, "name" to recording.name))
        }
    }
    if (stopped && files != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = format == TraceFileFormat.LOGISIM_2_7_1,
                onCheckedChange = { format = if (it) TraceFileFormat.LOGISIM_2_7_1 else TraceFileFormat.TIMESTAMPED },
                enabled = !selecting,
            )
            Text(localize("trace_file_logisim_2_7_1"))
        }
    }
    (selectionFailure ?: recording.failure)?.let { Text(localize(it)) }
    if (files == null) Text(localize("trace_file_unavailable"))
}
