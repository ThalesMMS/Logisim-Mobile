package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
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
    var confirmation by remember { mutableStateOf<HelpExample?>(null) }
    var checking by remember { mutableStateOf<String?>(null) }
    val opener = remember { HelpExampleOpener() }
    var result by remember { mutableStateOf<LocalizedText?>(null) }

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
            result?.let { notice -> item { Text(strings(notice), Modifier.padding(12.dp)) } }
            when (page) {
                HelpPage.GUIDES -> {
                    item { Text(strings("help_offline_guide"), Modifier.padding(horizontal = 12.dp)) }
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
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    if (checking == null && opener.opening == null) confirmation = example
                                }, enabled = checking == null && opener.opening == null) { Text(strings("help_open_example")) }
                                TextButton(enabled = checking == null && opener.opening == null, onClick = {
                                    if (checking != null || opener.opening != null) return@TextButton
                                    val circuit = example.circuit ?: return@TextButton
                                    checking = example.id
                                    result = message("help_checking_example", "name" to example.title)
                                    scope.launch {
                                        try {
                                            val checked = withContext(Dispatchers.Default) { HelpContent.verify(circuit, registry) }
                                            result = if (checked.failures.isEmpty()) message("help_example_verified",
                                                "name" to example.title, "count" to checked.observations.size)
                                            else message("help_example_failed", "name" to example.title,
                                                "details" to LocalizedText.join(checked.notices))
                                        } catch (error: CancellationException) { throw error }
                                        catch (error: Exception) {
                                            result = error.localizedDescription(message("help_example_invalid"))
                                        } finally { checking = null }
                                    }
                                }) { Text(strings("help_verify_reference")) }
                            }
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
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                area.issues.forEach { issue -> TextButton(onClick = { openUrl("${HelpContent.repository}/issues/$issue") }) { Text("#$issue") } }
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings("help_registered_libraries"), style = MaterialTheme.typography.titleMedium)
                            registry.definitions.groupingBy { it.library }.eachCount().entries.sortedBy { it.key }.forEach { (library, count) ->
                                Text(strings("help_library_count", "name" to library, "count" to count))
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { example ->
        AppAlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(strings("help_confirm_open", "name" to example.title)) },
            text = { Text(strings("help_open_copy_explanation")) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                val circuit = example.circuit ?: return@TextButton
                scope.launch {
                    when (val opened = opener.open(circuit,
                        currentProject = { latestContext.project },
                        prepare = { withContext(Dispatchers.Default) { HelpContent.prepare(it, registry) } },
                        accept = { onOpenExample(latestContext, it) })) {
                        HelpExampleOpenResult.Requested -> latestContext.showNotice(message("help_example_requested", "name" to example.title))
                        HelpExampleOpenResult.ProjectChanged -> latestContext.showNotice(message("help_project_changed"))
                        HelpExampleOpenResult.HostRejected -> latestContext.showNotice(message("help_example_open_failed"))
                        HelpExampleOpenResult.Busy -> Unit
                        is HelpExampleOpenResult.Failed -> latestContext.showNotice(LocalizedText.join(opened.notices))
                    }
                }
            }) { Text(strings("help_open_example")) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(strings("help_cancel")) } },
        )
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
