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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.localization.rememberLocalizer
import dev.logisim.mobile.core.localization.LocalizedText
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

@Composable
fun TestVectorPanel(
    project: CircuitProject,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    registry: ComponentRegistry = ComponentCatalog.registry,
) {
    val localize = rememberLocalizer()
    var text by remember(initialText) { mutableStateOf(initialText) }
    var circuitName by remember(project) { mutableStateOf(project.mainCircuitName) }
    var session by remember(project, registry) { mutableStateOf<TestVectorRunSession?>(null) }
    var failure by remember(project) { mutableStateOf<LocalizedText?>(null) }
    var running by remember(project) { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    fun load(): TestVectorRunSession? = try {
        editorTestVectorSession(project, circuitName, text, registry).also {
            session = it
            failure = null
            revision++
        }
    } catch (error: IllegalArgumentException) {
        session = null
        failure = error.localizedDescription(message(Messages.VectorInvalid))
        null
    }

    LaunchedEffect(running, session) {
        val current = session
        while (running && current != null && current.advance() != null) {
            revision++
            delay(1)
        }
        running = false
        revision++
    }

    Column(modifier.fillMaxWidth().heightIn(min = 340.dp, max = 760.dp)) {
        Text(localize(Messages.VectorTitle), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            project.circuits.forEach { circuit ->
                TextButton(enabled = !running, onClick = { circuitName = circuit.name; session = null }) {
                    Text(if (circuit.name == circuitName) "✓ ${circuit.name}" else circuit.name)
                }
            }
        }
        OutlinedTextField(text, onValueChange = { text = it; session = null; failure = null },
            enabled = !running, label = { Text(localize(Messages.VectorFile)) },
            placeholder = { Text(VECTOR_EXAMPLE) }, minLines = 4, maxLines = 8,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = localize(Messages.VectorFileA11y) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Text(localize(Messages.VectorHelp), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = !running, onClick = { load() }) { Text(localize(Messages.VectorLoad)) }
            TextButton(enabled = !running, onClick = {
                val current = session?.takeIf { it.status !in setOf(VectorRunStatus.CANCELLED, VectorRunStatus.COMPLETED) } ?: load()
                current?.resume()
                running = current != null
            }) { Text(localize(Messages.VectorRun)) }
            TextButton(enabled = running, onClick = { running = false; session?.pause(); revision++ }) { Text(localize(Messages.VectorPause)) }
            TextButton(enabled = !running, onClick = {
                val current = session ?: load()
                current?.resume()
                current?.advance()
                current?.pause()
                revision++
            }) { Text(localize(Messages.VectorStep)) }
            TextButton(enabled = session != null, onClick = { running = false; session?.cancel(); revision++ }) { Text(localize(Messages.VectorCancel)) }
            TextButton(enabled = !running && session != null, onClick = { session?.reset(); revision++ }) { Text(localize(Messages.VectorReset)) }
            TextButton(enabled = session != null, onClick = { session?.let { onExport(it.exportCsv()) } }) { Text(localize(Messages.VectorExportResults)) }
            TextButton(enabled = !running, onClick = {
                try { onExport(TestVectorFormat.write(TestVectorFormat.read(text))) }
                catch (error: IllegalArgumentException) { failure = error.localizedDescription(message(Messages.VectorInvalid)) }
            }) { Text(localize(Messages.VectorExportVectors)) }
        }
        failure?.let { Text(localize(it), color = MaterialTheme.colorScheme.error) }
        val current = session
        val results = remember(current, revision) { current?.results.orEmpty() }
        if (current != null) {
            Text(localize(Messages.VectorSummary, "completed" to current.completedCount, "total" to current.table.rows.size, "passed" to current.passedCount, "failed" to current.failedCount, "status" to current.status.notice), fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.rowIndex }) { result ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(localize(Messages.VectorRowResult, "line" to result.lineNumber, "set" to result.set, "sequence" to result.sequence, "result" to message(if (result.passed) Messages.VectorPass else Messages.VectorFail)),
                        color = if (result.passed) Color(0xFF08792F) else MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
