package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitExample
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The host accepts an opening request, then reports the final result after installing a workspace copy. */
fun helpExamplesEditorTool(
    onOpenExample: (EditorToolContext, CircuitExample) -> Boolean,
    registry: ComponentRegistry = ComponentCatalog.registry,
    onOpenTool: ((EditorToolContext, String) -> Boolean)? = null,
): EditorTool = EditorTool(
    id = "help-examples", title = "Help and examples", titleText = message("help_title"),
) { context -> HelpExamplesPanel(context, registry, onOpenExample, onOpenTool) }

private enum class HelpPage(val titleKey: String) {
    GUIDES("help_guides"), EXAMPLES("help_examples"), COMPATIBILITY("help_compatibility"),
}

@Composable
private fun HelpExamplesPanel(
    context: EditorToolContext,
    registry: ComponentRegistry,
    onOpenExample: (EditorToolContext, CircuitExample) -> Boolean,
    onOpenTool: ((EditorToolContext, String) -> Boolean)?,
) {
    val strings = rememberLocalizer()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val latestContext by rememberUpdatedState(context)
    var page by remember { mutableStateOf(HelpPage.GUIDES) }
    var checking by remember { mutableStateOf<String?>(null) }
    val opener = remember { HelpExampleOpener() }
    // Each example keeps its own result, shown under its buttons where the user tapped (#108).
    var results by remember { mutableStateOf(emptyMap<String, ToolNotice>()) }

    fun openUrl(url: String) {
        try { uriHandler.openUri(url) }
        catch (error: Exception) {
            context.showNotice(error.localizedDescription(message("help_link_failed")))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpPage.entries.forEach { option ->
                OutlinedButton(onClick = { page = option }, modifier = Modifier.semantics {
                    role = Role.Tab
                    selected = page == option
                }) {
                    Text(strings(option.titleKey), color = if (page == option) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                HelpPage.GUIDES -> {
                    item { Text(strings("help_offline_guide"), Modifier.padding(horizontal = 12.dp)) }
                    item {
                        TextButton(onClick = { openUrl("https://thalesmms.github.io/apps/logisim-evolution-mobile/privacy/") }) {
                            Text(strings("help_privacy_policy"))
                        }
                    }
                    items(HelpContent.guides, key = { it.id }) { guide ->
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings(guide.title), style = MaterialTheme.typography.titleMedium)
                            guide.steps.forEachIndexed { index, step -> Text("${index + 1}. ${strings(step)}") }
                            if (guide.id == "editing") TextButton(onClick = context.openProperties) { Text(strings("help_open_properties")) }
                            guide.toolId?.let { toolId -> onOpenTool?.let { openTool ->
                                TextButton(onClick = {
                                    if (!openTool(context, toolId)) context.showNotice(message("help_tool_unavailable"))
                                }) { Text(strings("help_open_related_tool")) }
                            } }
                            HorizontalDivider()
                        }
                    }
                    item { TextButton(onClick = { openUrl(HelpContent.userGuideUrl) }) { Text(strings("help_online_guide")) } }
                }
                HelpPage.EXAMPLES -> {
                    item { Text(strings("help_examples_intro"), Modifier.padding(horizontal = 12.dp)) }
                    items(HelpContent.examples, key = { it.id }) { example ->
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings(example.title), style = MaterialTheme.typography.titleMedium)
                            Text(strings(example.description))
                            Text(strings(example.instructions))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                // The example opens as its own workspace copy (or an undoable replacement without a
                                // workspace), so it needs no confirmation; the status line reports the request.
                                TextButton(onClick = {
                                    if (checking == null && opener.opening == null) scope.launch {
                                        opener.openReporting(example, registry, { latestContext.project },
                                            accept = { onOpenExample(latestContext, it) }, notice = { latestContext.showNotice(it) })
                                    }
                                }, enabled = checking == null && opener.opening == null) { Text(strings("help_open_example")) }
                                TextButton(enabled = checking == null && opener.opening == null, modifier = Modifier.testTag("help-verify-${example.id}"), onClick = {
                                    if (checking != null || opener.opening != null) return@TextButton
                                    val circuit = example.circuit ?: return@TextButton
                                    checking = example.id
                                    results = results + (example.id to message("help_checking_example", "name" to example.title).asProgress())
                                    scope.launch {
                                        val outcome = try {
                                            val checked = withContext(Dispatchers.Default) { HelpContent.verify(circuit, registry) }
                                            if (checked.failures.isEmpty()) message("help_example_verified",
                                                "name" to example.title, "count" to checked.observations.size).asResult()
                                            else message("help_example_failed", "name" to example.title,
                                                "details" to LocalizedText.join(checked.notices)).asError()
                                        } catch (error: CancellationException) { throw error }
                                        catch (error: Exception) {
                                            error.localizedDescription(message("help_example_invalid")).asError()
                                        } finally { checking = null }
                                        results = results + (example.id to outcome)
                                        latestContext.showNotice(outcome.text)
                                    }
                                }) { Text(strings("help_verify_reference")) }
                                if (checking == example.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            ToolNoticeText(results[example.id], Modifier.testTag("help-result-${example.id}"))
                            HorizontalDivider()
                        }
                    }
                }
                HelpPage.COMPATIBILITY -> {
                    item {
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings("help_build", "version" to AppIdentity.version, "build" to AppIdentity.build))
                            Text(strings("help_registry_scope", "count" to registry.definitions.size))
                            Text(strings("help_known_limits"))
                            TextButton(onClick = { openUrl(HelpContent.parityUrl) }) { Text(strings("help_open_parity")) }
                        }
                    }
                    items(HelpContent.support) { area ->
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings(area.title), style = MaterialTheme.typography.titleMedium)
                            Text(strings(area.description))
                            // Each link names the issue it opens ("Issue 8"), not a bare "#8".
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                area.issues.forEach { issue ->
                                    val description = strings("help_issue_link_description", "number" to issue)
                                    TextButton(onClick = { openUrl("${HelpContent.repository}/issues/$issue") },
                                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
                                        Text(strings("help_issue_link", "number" to issue))
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings("help_registered_libraries"), style = MaterialTheme.typography.titleMedium)
                            // Evolution's display names ("Gates", "Annotations"), in their alphabetical order.
                            registry.definitions.groupingBy { it.library }.eachCount().entries
                                .map { (library, count) -> strings(builtinLibraryName(library)) to count }.sortedBy { it.first }
                                .forEach { (name, count) -> Text(strings("help_library_count", "name" to name, "count" to count)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Opens [example] as the gallery and File > Open example do: the XML is read first, [accept] hands it to the host,
 * and [notice] reports the request or why it failed.
 */
internal suspend fun HelpExampleOpener.openReporting(
    example: HelpExample,
    registry: ComponentRegistry,
    currentProject: () -> CircuitProject,
    accept: (CircuitExample) -> Boolean,
    notice: (LocalizedText) -> Unit,
) {
    val circuit = example.circuit ?: return
    when (val opened = this.open(circuit, currentProject = currentProject,
        prepare = { withContext(Dispatchers.Default) { HelpContent.prepare(it, registry) } }, accept = accept)) {
        HelpExampleOpenResult.Requested -> notice(message("help_example_requested", "name" to example.title))
        HelpExampleOpenResult.ProjectChanged -> notice(message("help_project_changed"))
        HelpExampleOpenResult.HostRejected -> notice(message("help_example_open_failed"))
        HelpExampleOpenResult.Busy -> Unit
        is HelpExampleOpenResult.Failed -> notice(LocalizedText.join(opened.notices))
    }
}


internal sealed interface HelpExampleOpenResult {
    data object Requested : HelpExampleOpenResult
    data object Busy : HelpExampleOpenResult
    data object ProjectChanged : HelpExampleOpenResult
    data object HostRejected : HelpExampleOpenResult
    data class Failed(val notices: List<LocalizedText>) : HelpExampleOpenResult
}

/** Accessed on the UI dispatcher; a second request cannot overtake a pending import. */
internal class HelpExampleOpener {
    var opening by mutableStateOf<String?>(null)
        private set

    suspend fun open(
        example: CircuitExample,
        currentProject: () -> CircuitProject,
        prepare: suspend (CircuitExample) -> CircProjectImportResult,
        accept: (CircuitExample) -> Boolean,
    ): HelpExampleOpenResult {
        if (opening != null) return HelpExampleOpenResult.Busy
        opening = example.id
        val previousProject = currentProject()
        return try {
            val imported = prepare(example)
            if (currentProject() !== previousProject) HelpExampleOpenResult.ProjectChanged
            else when (imported) {
                is CircProjectImportResult.Failure -> HelpExampleOpenResult.Failed(imported.notices)
                is CircProjectImportResult.Success -> if (accept(example)) HelpExampleOpenResult.Requested else HelpExampleOpenResult.HostRejected
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            HelpExampleOpenResult.Failed(listOf(error.localizedDescription(message("help_example_open_failed"))))
        } finally { opening = null }
    }
}
