package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.AppearanceEdits
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.systemAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * Installs a verified generated circuit as one undoable project edit. The analysis lives in one session per tool,
 * so dismissing the sheet (swipe, Back, scrim or Close) keeps the table, as Evolution's Analyzer window does.
 */
fun analysisEditorTool(
    registry: ComponentRegistry? = null,
    simulatorFactory: (() -> CircuitSimulator)? = null,
    assets: DocumentAssetActions? = null,
): EditorTool {
    val session = AnalysisSession()
    return EditorTool("combinational-analysis",
        message(Messages.AnalysisTitle).resolve(systemAppLanguage()), titleText = message(Messages.AnalysisTitle), requiresProjectGraph = true,
        runtime = { context ->
            // Another document starts a new analysis even while the sheet is closed, releasing an imported spool.
            LaunchedEffect(session, context.documentIdentity) { session.bind(context.documentIdentity) }
            DisposableEffect(session) { onDispose { session.close() } }
        },
    ) { context ->
        // Bound in an effect, not while composing: a reset closes an imported table's spool. Until the session is
        // bound to this document the sheet shows nothing rather than the previous document's analysis.
        LaunchedEffect(session, context.documentIdentity) { session.bind(context.documentIdentity) }
        if (session.documentIdentity == context.documentIdentity) AnalysisToolContent(session, context, registry, simulatorFactory, assets)
    }
}

@Composable
private fun AnalysisToolContent(
    session: AnalysisSession,
    context: EditorToolContext,
    registry: ComponentRegistry?,
    simulatorFactory: (() -> CircuitSimulator)?,
    assets: DocumentAssetActions?,
) {
    var destination by remember(context.documentIdentity) { mutableStateOf<GeneratedDestination?>(null) }
    AnalyzePanel(session, context.project, context.document.name, onGenerated = generated@{ generated ->
        if (!context.isCurrentEditorContext() || !context.projectExports.available) return@generated
        if (!context.projectExports.access.writable) {
            // Shown in the sheet as well: the editor's status line is behind it.
            val refusal = message("document_read_only_preserved")
            session.error = refusal
            context.showNotice(refusal)
            return@generated
        }
        destination = GeneratedDestination(context.project, generated)
    }, registry = registry, simulatorFactory = simulatorFactory, assets = assets,
        selectPagedExport = assets?.traceFiles?.let { destinations ->
            { name, mimeType, onResult ->
                if (!context.isCurrentEditorContext() || !context.exportProjectGraph {
                        destinations.create(name, mimeType, onResult)
                    }) onResult(TraceFileOpenResult.Cancelled)
            }
        },
    )
    destination?.let {
        AnalysisDestinationDialog(it, context, onDismiss = { destination = null }, onSaved = { notice -> session.notice = notice })
    }
}

private data class GeneratedDestination(val original: CircuitProject, val generated: CircuitDocument)

@Composable
private fun AnalysisDestinationDialog(
    destination: GeneratedDestination,
    context: EditorToolContext,
    onDismiss: () -> Unit,
    onSaved: (LocalizedText) -> Unit = {},
) {
    val strings = rememberLocalizer()
    var name by remember(destination) { mutableStateOf(destination.generated.name) }
    var confirmedTarget by remember(destination) { mutableStateOf<CircuitDocument?>(null) }
    var saving by remember(destination) { mutableStateOf(false) }
    var error by remember(destination) { mutableStateOf<LocalizedText?>(null) }
    val targetName = if (destination.original.circuit(name) != null) name else name.trim()
    val target = destination.original.circuit(targetName)
    val current = context.project == destination.original && context.projectExports.available && context.projectExports.access.writable

    fun commit(replace: CircuitDocument?) {
        val failed = message(Messages.AnalysisDestinationFailed)
        if (!current || !context.isCurrentEditorContext()) {
            error = message(Messages.AnalysisDestinationChanged)
            return
        }
        try {
            val candidate = installGeneratedCircuit(destination.original, destination.generated, targetName, replace)
            saving = true
            context.commitProject(candidate) { saved ->
                saving = false
                if (saved) {
                    context.selectCircuit(targetName)
                    val notice = message(if (replace == null) Messages.AnalysisCreated else Messages.AnalysisDestinationReplaced)
                    context.showNotice(notice)
                    onSaved(notice)
                    onDismiss()
                } else error = failed
            }
        } catch (failure: IllegalArgumentException) {
            saving = false
            error = failure.localizedDescription(failed)
        }
    }

    val confirmation = confirmedTarget
    AppAlertDialog(
        onDismissRequest = {
            if (!saving) {
                if (confirmation == null) onDismiss() else confirmedTarget = null
            }
        },
        title = { Text(strings(if (confirmation == null) Messages.AnalysisDestination else Messages.AnalysisDestinationReplace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmation != null) {
                    Text(strings(Messages.AnalysisDestinationReplacePrompt, "name" to confirmation.name))
                } else {
                    Text(strings(Messages.AnalysisDestinationHelp))
                    OutlinedTextField(name, { name = it; error = null }, enabled = !saving, singleLine = true,
                        label = { Text(strings(Messages.AnalysisDestinationName)) }, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
                        destination.original.circuits.forEach { circuit ->
                            TextButton(enabled = !saving, onClick = { name = circuit.name; error = null }) { Text(circuit.name) }
                        }
                    }
                }
                val notice = if (!current && !saving) message(Messages.AnalysisDestinationChanged) else error
                notice?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && current && targetName.isNotEmpty(), onClick = {
                if (confirmation != null) commit(confirmation)
                else if (target != null) confirmedTarget = target
                else commit(null)
            }) {
                val key = when {
                    confirmation != null -> Messages.AnalysisDestinationReplace
                    target != null -> Messages.AnalysisDestinationContinue
                    else -> Messages.AnalysisDestinationCreate
                }
                Text(strings(key))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = {
                if (confirmation == null) onDismiss() else { confirmedTarget = null; error = null }
            }) { Text(strings(Messages.ActionCancel)) }
        },
    )
}

/** Build the full edit before touching editor history or storage. An existing target needs a matching confirmation. */
internal fun installGeneratedCircuit(
    project: CircuitProject,
    generated: CircuitDocument,
    name: String,
    confirmedTarget: CircuitDocument? = null,
): CircuitProject {
    val target = project.circuit(name)
    require(name.isNotBlank() && confirmedTarget == target)
    if (target != null) return AppearanceEdits.reconcileCircuit(project, target, generated.copy(id = target.id, name = target.name))
    val ids = project.circuits.mapTo(mutableSetOf()) { it.id }
    var number = 1
    while ("analysis-$number" in ids) number++
    return project.addCircuit(generated.copy(name = name, id = "analysis-$number"))
}
