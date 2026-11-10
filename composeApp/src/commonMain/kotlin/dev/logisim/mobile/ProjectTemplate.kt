package dev.logisim.mobile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircXmlEncoding
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TEMPLATE_BYTES = 16_000_000

/** AppPreferences TEMPLATE_EMPTY, TEMPLATE_PLAIN (default.templ) and TEMPLATE_CUSTOM, with their stored values 0, 1 and 2. */
enum class ProjectTemplateType(val stored: Int) { EMPTY(0), PLAIN(1), CUSTOM(2) }

/**
 * Template.createEmpty(): one empty circuit named [circuitName], without libraries, toolbar or mappings. Evolution then gives
 * the main circuit the creation defaults ([circuitAttributes]) like any new project.
 */
internal fun emptyTemplateProject(circuitName: String, circuitAttributes: Map<String, String> = emptyMap()): CircuitProject =
    CircuitProject(listOf(CircuitDocument(name = circuitName.trim().ifEmpty { DEFAULT_NEW_CIRCUIT_NAME }, attributes = circuitAttributes)))

/** An app-owned copy, independent of the picker URL and of the project catalog, and the template type new projects use. */
class ProjectTemplateStore(private val storage: WorkspaceCatalogStorage, private val registry: ComponentRegistry) {
    /** The installed custom template. */
    internal fun read(): String? = storage.withExclusiveAccess { readCustom() }

    /**
     * AppPreferences.getTemplateType(). Settings saved before the choice existed keep their behaviour: the custom template
     * when one is installed, otherwise the plain one. A custom choice without a file reads as plain, like TEMPLATE_UNKNOWN.
     */
    internal fun type(): ProjectTemplateType = storage.withExclusiveAccess { typeLocked() }

    internal fun select(type: ProjectTemplateType) = storage.withExclusiveAccess {
        check(type != ProjectTemplateType.CUSTOM || readCustom() != null)
        storage.writeAtomically(TYPE_FILE, type.stored.toString())
    }

    internal fun install(bytes: ByteArray) {
        requireLocalized(bytes.size in 1..MAX_TEMPLATE_BYTES) { message(Messages.ProjectTemplateSize) }
        val xml = CircXmlEncoding.decode(bytes)
        parse(xml)
        storage.withExclusiveAccess {
            storage.writeAtomically(FILE, xml)
            storage.writeAtomically(TYPE_FILE, ProjectTemplateType.CUSTOM.stored.toString())
        }
    }

    internal fun remove() = storage.withExclusiveAccess {
        if (typeLocked() == ProjectTemplateType.CUSTOM) storage.writeAtomically(TYPE_FILE, ProjectTemplateType.PLAIN.stored.toString())
        storage.writeAtomically(FILE, "")
    }

    /** AppPreferences.getTemplate(): the XML of the empty or custom template, or null for the plain one (default.templ). */
    internal fun templateXml(circuitName: String = DEFAULT_NEW_CIRCUIT_NAME, circuitAttributes: Map<String, String> = emptyMap()): String? =
        storage.withExclusiveAccess {
            when (typeLocked()) {
                ProjectTemplateType.EMPTY -> CircCodec.writeProject(emptyTemplateProject(circuitName, circuitAttributes), registry)
                ProjectTemplateType.CUSTOM -> readCustom()
                ProjectTemplateType.PLAIN -> null
            }
        }

    /** The installed custom template, or null for the plain one; the empty template is [EditorState.newEmptyDocument]. */
    internal fun readProject(): CircuitProject? = read()?.let(::parse)

    private fun readCustom(): String? = storage.read(FILE)?.takeUnless(String::isEmpty)

    private fun typeLocked(): ProjectTemplateType {
        val custom = readCustom() != null
        val stored = storage.read(TYPE_FILE)?.trim()?.toIntOrNull()?.let { value -> ProjectTemplateType.entries.firstOrNull { it.stored == value } }
            ?: if (custom) ProjectTemplateType.CUSTOM else ProjectTemplateType.PLAIN
        return if (stored == ProjectTemplateType.CUSTOM && !custom) ProjectTemplateType.PLAIN else stored
    }

    private fun parse(xml: String): CircuitProject = when (val result = CircCodec.readProject(xml, registry)) {
        is CircProjectImportResult.Success -> result.project
        is CircProjectImportResult.Failure -> throw LocalizedArgumentException(
            result.notices.firstOrNull() ?: message(Messages.WorkspaceErrorDocument),
        )
    }

    private companion object {
        const val FILE = "default-project-template.circ"
        const val TYPE_FILE = "default-project-template.type"
    }
}

@Composable
internal fun ProjectTemplateSettings(store: ProjectTemplateStore, assets: DocumentAssetActions) {
    val strings = rememberLocalizer()
    val scope = rememberCoroutineScope()
    var installed by remember(store) { mutableStateOf(false) }
    var type by remember(store) { mutableStateOf(ProjectTemplateType.PLAIN) }
    var busy by remember(store) { mutableStateOf(true) }
    var notice by remember(store) { mutableStateOf<LocalizedText?>(null) }
    suspend fun update(failure: LocalizedText, action: () -> Unit) {
        busy = true
        notice = null
        try {
            val (custom, selected) = withContext(Dispatchers.Default) {
                action()
                (store.read() != null) to store.type()
            }
            installed = custom
            type = selected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.localizedDescription(failure)
        } finally {
            busy = false
        }
    }
    LaunchedEffect(store) { update(message(Messages.ProjectTemplateReadFailed)) {} }

    // TemplateOptions: empty, plain (default.templ) or the user's template, which needs an installed file.
    for ((option, label) in listOf(ProjectTemplateType.EMPTY to "project_template_empty", ProjectTemplateType.PLAIN to "project_template_plain",
        ProjectTemplateType.CUSTOM to "project_template_custom")) {
        val enabled = !busy && (option != ProjectTemplateType.CUSTOM || installed)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project-template-${option.name.lowercase()}")
            .selectable(type == option, enabled = enabled, role = Role.RadioButton) {
                scope.launch { update(message(Messages.ProjectTemplateSaveFailed)) { store.select(option) } }
            }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = type == option, onClick = null, enabled = enabled)
            Text(strings(label), modifier = Modifier.padding(start = 8.dp))
        }
    }
    TextButton(onClick = {
        busy = true
        notice = null
        try {
            assets.open(listOf("application/octet-stream"), MAX_TEMPLATE_BYTES) { result ->
                when (result) {
                    is AssetOpenResult.Opened -> scope.launch {
                        update(message(Messages.ProjectTemplateSaveFailed)) { store.install(result.asset.bytes) }
                    }
                    is AssetOpenResult.Failure -> { notice = result.notice; busy = false }
                    AssetOpenResult.Cancelled -> busy = false
                }
            }
        } catch (error: Exception) {
            notice = error.localizedDescription(message(Messages.ProjectTemplateReadFailed))
            busy = false
        }
    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(strings(Messages.ProjectTemplateChoose))
    }
    if (installed) TextButton(onClick = {
        busy = true
        scope.launch { update(message(Messages.ProjectTemplateSaveFailed)) { store.remove() } }
    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(strings(Messages.ProjectTemplateRemove))
    }
    notice?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
}
