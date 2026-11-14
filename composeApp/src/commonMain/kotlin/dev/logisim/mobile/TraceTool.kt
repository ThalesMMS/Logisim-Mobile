package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.message
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import dev.logisim.mobile.core.localization.LocalizedText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.SimulationState

fun traceEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    recorder: TraceRecorder = TraceRecorder(),
    files: TraceFileActions? = null,
    onFailure: (LocalizedText) -> Unit = {},
    recording: TraceFileRecording = TraceFileRecording(recorder, onFailure),
    assets: DocumentAssetActions? = null,
    currentRegistry: () -> ComponentRegistry = { registry },
): EditorTool {
    var recordedProject: CircuitProject? = null
    var initializedSelection = false
    fun capture(project: CircuitProject, simulation: SimulationState, propagationComplete: Boolean) {
        if (recordedProject != project) {
            recording.stop()
            recorder.select(emptyList())
            recordedProject = project
            initializedSelection = false
        }
        if ((recorder.lastTime ?: 0) > simulation.propagationTime) recorder.clear()
        recorder.capture(simulation, propagationComplete = propagationComplete)
    }
    // Opening the trace keeps a run going, so the waveforms follow it (C57).
    return EditorTool("trace", "Signal recording", titleText = message(Messages.TraceTitle), requiresProjectGraph = true,
        pausesSimulation = false, runtime = { context ->
        DisposableEffect(recording, context.documentIdentity) {
            recording.bindDocument(context.documentIdentity)
            onDispose { recording.stop() }
        }
    }, content = { context ->
        val localize = rememberLocalizer()
        val activeRegistry = currentRegistry()
        LaunchedEffect(context.project, initializedSelection, activeRegistry) {
            if (!initializedSelection) {
                recorder.select(TraceSignals.fromProject(context.project, activeRegistry).filter {
                    it.reference.instancePath == context.simulation.instancePath &&
                        context.selectedComponents.any { component -> component.id == it.reference.componentId }
                })
                // The hook may already hold a newer state than this composition's; never record an older one after it.
                val shown = context.projectSimulation
                if (shown.propagationTime >= (recorder.lastTime ?: 0)) recorder.capture(shown, shown.propagationTime)
                initializedSelection = true
            }
        }
        var exported by remember { mutableStateOf<String?>(null) }
        var exportedAsWaveDrom by remember { mutableStateOf(false) }
        TracePanel(context.project, context.projectSimulation, recorder, onExport = {
            exportedAsWaveDrom = false
            exported = it
        }, registry = activeRegistry,
            selectionEnabled = recording.state == TraceFileRecordingState.STOPPED,
            // onSimulation below records every published state in order, so the panel only shows them.
            hostCaptures = true,
            simulationControls = TraceSimulationControls(running = context.isSimulationRunning,
                enabled = context.simulationControlsEnabled && context.isForeground && context.isCurrentEditorContext(),
                onRun = { context.startSimulation() }, onPause = context.pauseSimulation, onTick = context.stepClock),
            fileControls = { canRecord -> TraceFileControls(context, canRecord, recording, files, onFailure) },
            onExportWaveDrom = { text ->
                context.exportProjectGraph {
                    if (assets == null) {
                        exportedAsWaveDrom = true
                        exported = text
                    } else {
                        val asset = DocumentAsset("timing-diagram.json", "application/json", text.encodeToByteArray())
                        assets.create(asset) { result ->
                            if (context.isCurrentEditorContext() && context.projectExports.available) {
                                when (result) {
                                    AssetActionResult.Completed -> context.showNotice(message(Messages.StatusFileSaved, "name" to asset.name))
                                    is AssetActionResult.Failure -> onFailure(result.notice)
                                    AssetActionResult.Cancelled -> Unit
                                }
                            }
                        }
                    }
                }
            })
        exported?.let { text ->
            @Suppress("DEPRECATION")
            val clipboard = LocalClipboardManager.current
            // The dialog covers the sheet: Copy's result is shown in it, as the Memory editor's Save file (C74).
            val sheetNotice = LocalToolSheetNotice.current
            var copiedAfter by remember(text) { mutableStateOf<Long?>(null) }
            AppAlertDialog(onDismissRequest = { exported = null }, title = { Text(localize(if (exportedAsWaveDrom) Messages.TraceExportWaveDrom else Messages.TraceExportTitle)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, onValueChange = {}, readOnly = true, minLines = 5, maxLines = 12)
                    AccessibleEditorNotice(sheetNotice?.takeIf { notice -> copiedAfter?.let { notice.serial > it } == true }?.text,
                        Modifier.testTag("trace-copy-notice"))
                } },
                confirmButton = {
                    TextButton(onClick = {
                        copiedAfter = sheetNotice?.serial ?: 0L
                        context.exportProjectGraph {
                            clipboard.setText(AnnotatedString(text))
                            context.showNotice(message(if (exportedAsWaveDrom) Messages.TraceWaveDromCopied else Messages.TraceCopied))
                        }
                    }) { Text(localize(Messages.TraceCopy)) }
                },
                dismissButton = { TextButton(onClick = { exported = null }) { Text(localize(Messages.TraceClose)) } })
        }
    }, onSimulation = { project, simulation -> capture(project, simulation, propagationComplete = true) },
        onSimulationObservation = { project, simulation -> capture(project, simulation, propagationComplete = false) })
}
