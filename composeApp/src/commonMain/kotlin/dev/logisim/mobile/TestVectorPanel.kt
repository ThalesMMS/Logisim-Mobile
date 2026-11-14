package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.TestVectorFormat
import dev.logisim.mobile.core.TestVectorRunSession
import dev.logisim.mobile.core.VectorRunStatus
import kotlinx.coroutines.delay

private const val VECTOR_EXAMPLE = "A B Y\n0 0 0\n1 1 1"

/**
 * A vector table or a results CSV to save, or to show for copying when no document picker exists. [vectorText] is the
 * editor text a vector table was written from, which counts as saved once the file is created.
 */
internal data class TestVectorExport(val name: String, val mimeType: String, val content: String, val vectorText: String? = null)

/** The panel edits [state], which the tool keeps while the sheet is closed; [onOpenFile] is null without a picker. */
@Composable
internal fun TestVectorPanel(
    project: CircuitProject,
    state: TestVectorToolState,
    onExport: (TestVectorExport) -> Unit,
    modifier: Modifier = Modifier,
    registry: ComponentRegistry = ComponentCatalog.registry,
    onOpenFile: (() -> Unit)? = null,
) {
    val localize = rememberLocalizer()
    val circuitName = state.circuit(project)
    val running = state.running
    val session = state.session
    val busy = running || state.opening

    fun load(): TestVectorRunSession? = state.load(project, registry)

    LaunchedEffect(running, session) {
        val current = session
        while (state.running && current != null && current.advance() != null) {
            state.revision++
            delay(1)
        }
        state.running = false
        state.revision++
    }
    // Closing the sheet pauses a run; the table, results and text stay with the tool.
    DisposableEffect(state) {
        onDispose {
            if (state.running) {
                state.running = false
                state.session?.pause()
            }
        }
    }

    Column(modifier.fillMaxWidth().heightIn(min = 340.dp, max = 760.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localize(Messages.VectorTitle), style = MaterialTheme.typography.titleMedium)
        // Every circuit is listed in one menu with the current one selected, instead of a row of '✓ ' text buttons (#59).
        ChoiceDropdown(localize("vector_circuit"), project.circuits.map { it.name }, circuitName, optionLabel = { it },
            enabled = !busy, modifier = Modifier.fillMaxWidth(), tag = "vector-circuit") {
            state.circuitName = it
            state.session = null
            state.failure = null
        }
        if (onOpenFile != null) {
            OutlinedButton(onClick = onOpenFile, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("vector-open-file")) {
                Text(localize("vector_open_file"))
            }
        }
        ToolNoticeText(state.notice, Modifier.testTag("vector-notice"))
        OutlinedTextField(state.text, onValueChange = { state.text = it; state.session = null; state.failure = null; state.notice = null },
            enabled = !busy, label = { Text(localize(Messages.VectorFile)) },
            placeholder = { Text(VECTOR_EXAMPLE) }, minLines = 4, maxLines = 8, keyboardOptions = FieldKeyboards.Code,
            modifier = Modifier.fillMaxWidth().testTag("vector-text").semantics { contentDescription = localize(Messages.VectorFileA11y) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Text(localize(Messages.VectorHelp), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = !busy, onClick = { load() }, modifier = Modifier.testTag("vector-check")) { Text(localize("vector_check")) }
            TextButton(enabled = !busy, onClick = {
                val current = state.session?.takeIf { it.status !in setOf(VectorRunStatus.CANCELLED, VectorRunStatus.COMPLETED) } ?: load()
                current?.resume()
                state.running = current != null
            }) { Text(localize(Messages.VectorRun)) }
            TextButton(enabled = running, onClick = { state.running = false; state.session?.pause(); state.revision++ }) { Text(localize(Messages.VectorPause)) }
            TextButton(enabled = !busy, onClick = {
                val current = state.session ?: load()
                current?.resume()
                current?.advance()
                current?.pause()
                state.revision++
            }) { Text(localize(Messages.VectorStep)) }
            TextButton(enabled = session != null, onClick = { state.running = false; state.session?.cancel(); state.revision++ }) { Text(localize(Messages.VectorCancel)) }
            TextButton(enabled = !running && session != null, onClick = { state.session?.reset(); state.revision++ }) { Text(localize(Messages.VectorReset)) }
            TextButton(enabled = session != null, onClick = {
                state.session?.let { onExport(TestVectorExport("test-vector-results.csv", "text/csv", it.exportCsv())) }
            }) { Text(localize(Messages.VectorExportResults)) }
            TextButton(enabled = !busy, onClick = {
                try {
                    val text = state.text
                    onExport(TestVectorExport("test-vectors.txt", "text/plain", TestVectorFormat.write(TestVectorFormat.read(text)), vectorText = text))
                }
                catch (error: IllegalArgumentException) { state.failure = error.localizedDescription(message(Messages.VectorInvalid)) }
            }) { Text(localize(Messages.VectorExportVectors)) }
        }
        AccessibleEditorNotice(state.failure, Modifier.testTag("vector-failure"), isError = true)
        val current = session
        val revision = state.revision
        val results = remember(current, revision) { current?.results.orEmpty() }
        if (current != null) {
            Text(localize(Messages.VectorSummary, "completed" to current.completedCount, "total" to current.table.rows.size, "passed" to current.passedCount, "failed" to current.failedCount, "status" to current.status.notice), fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.rowIndex }) { result ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(localize(Messages.VectorRowResult, "line" to result.lineNumber, "set" to result.set, "sequence" to result.sequence, "result" to message(if (result.passed) Messages.VectorPass else Messages.VectorFail)),
                        color = if (result.passed) ChromeColors.PassInk else MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    for (difference in result.differences) {
                        Text(localize(difference.notice), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

internal fun editorTestVectorSession(project: CircuitProject, circuitName: String, text: String, registry: ComponentRegistry): TestVectorRunSession =
    TestVectorRunSession(
        project, circuitName, TestVectorFormat.read(text),
        CircuitSimulator(propagationLimit = SimulationProjectOptions.read(project).propagationLimit, registry = registry),
    )
