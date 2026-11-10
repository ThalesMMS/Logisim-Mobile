package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.EvolutionDefaultTemplate
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarBinding
import dev.logisim.mobile.core.ProjectToolbarItem
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

internal fun projectToolbarEditorTool(registry: ComponentRegistry, state: EditorState) =
    EditorTool("project-toolbar", "Project toolbar", titleText = message("project_toolbar_title"), requiresProjectGraph = true) { context ->
        ProjectToolbarPanel(context.project, registry, state.documentWritable, context.applyProject)
    }

/** Logisim Evolution's default toolbar (default.templ), used when a project's toolbar has no tool this app can bind. */
internal fun evolutionDefaultToolbar(project: CircuitProject): List<ProjectToolbarItem> =
    EvolutionDefaultTemplate.toolbar(project.sourceVersion)

/**
 * The project's toolbar entries with their bindings, or Evolution's default ones when none of the project's tools binds.
 * A project without toolbar entries, such as one from Evolution's empty template, has an empty toolbar, as XmlReader
 * leaves it (#344).
 */
internal fun projectToolbarEntries(project: CircuitProject, registry: ComponentRegistry): List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>> {
    val own = ProjectToolbar.read(project).map { item -> item to ProjectToolbar.binding(item, project, registry) }
    return if (own.isEmpty() || own.any { it.second != null }) own
    else evolutionDefaultToolbar(project).map { item -> item to ProjectToolbar.binding(item, project, registry) }
}

/**
 * The project's own toolbar, or Evolution's default one when none of its tools binds, as editor actions ([projectToolbarEntries]).
 * The touch-only Move mode joins the first group of tools, after the project's own modes.
 */
@Composable
internal fun projectToolbarActions(state: EditorState, moveLabel: String, componentIconColor: Int? = null): List<EditorToolbarAction> {
    val strings = rememberLocalizer()
    val project = state.project
    val registry = state.componentRegistry
    val entries = remember(project.extraXml, project.libraries, project.sourceVersion, project.circuits, registry) {
        projectToolbarEntries(project, registry)
    }
    val actions = projectToolbarEntryActions(state, entries, strings, componentIconColor)
    val move = EditorToolbarAction("move", moveLabel, selected = state.mode == EditorMode.MOVE, icon = EditorIcon.MOVE) {
        state.changeMode(EditorMode.MOVE)
    }
    val firstGroupEnd = actions.indexOfFirst { it.separator }.let { if (it < 0) actions.size else it }
    return actions.take(firstGroupEnd) + move + actions.drop(firstGroupEnd)
}

private fun projectToolbarEntryActions(
    state: EditorState,
    entries: List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>>,
    strings: dev.logisim.mobile.localization.Localizer,
    componentIconColor: Int?,
): List<EditorToolbarAction> {
    return buildList {
        entries.forEachIndexed { index, (item, binding) ->
            val id = "project-tool-$index"
            when {
                item.isSeparator -> add(EditorToolbarAction(id, "", separator = true, onClick = {}))
                binding is ProjectToolbarBinding.Mode -> {
                    val mode = binding.editorMode()
                    add(EditorToolbarAction(id, strings(binding.label()), selected = state.mode == mode, icon = mode.editorIcon()) {
                        state.changeMode(mode)
                    })
                }
                binding is ProjectToolbarBinding.Text -> {
                    add(EditorToolbarAction(id, strings(binding.label()), enabled = state.documentWritable && state.interactionsEnabled,
                        selected = state.mode == EditorMode.TEXT && state.placingTemplate == binding.prototype, icon = EditorIcon.TEXT) {
                        state.activateTextTool(binding.prototype)
                    })
                }
                binding is ProjectToolbarBinding.Place -> {
                    val component = binding.component
                    add(EditorToolbarAction(id, strings(binding.label()), enabled = state.documentWritable && state.interactionsEnabled,
                        selected = state.mode == EditorMode.PLACE && state.projectPlacementTool == component,
                        icon = component.kind.editorIcon(), iconColor = componentIconColor) { state.place(component, projectTool = true) })
                }
            }
        }
    }
}

internal fun EditorMode.editorIcon(): EditorIcon? = when (this) {
    EditorMode.INTERACT -> EditorIcon.POKE
    EditorMode.EDIT -> EditorIcon.SELECT
    EditorMode.SELECT -> EditorIcon.MARQUEE
    EditorMode.MOVE -> EditorIcon.MOVE
    EditorMode.WIRE -> EditorIcon.WIRE
    EditorMode.TEXT -> EditorIcon.TEXT
    EditorMode.PLACE -> null
}

@Composable
private fun ProjectToolbarPanel(
    project: CircuitProject,
    registry: ComponentRegistry,
    writable: Boolean,
    apply: (CircuitProject) -> Unit,
) {
    val strings = rememberLocalizer()
    val initial = remember(project.extraXml, project.sourceVersion) {
        ProjectToolbar.read(project)
    }
    var entries by remember(initial) { mutableStateOf(initial) }
    var choosing by remember(initial) { mutableStateOf(false) }
    var query by remember(initial) { mutableStateOf("") }
    var editing by remember(initial) { mutableStateOf<Pair<Int, CircuitComponent>?>(null) }
    val choices = remember(registry, project.sourceVersion, project.circuits) {
        val outputAttributes = if (project.sourceVersion.startsWith("2.7.")) mapOf("output" to "true") else mapOf("type" to "output")
        listOf(
            ProjectToolbarItem.Tool("#Base", "Poke Tool") to ProjectToolbarBinding.Mode.INTERACT.label(),
            ProjectToolbarItem.Tool("#Base", "Edit Tool") to ProjectToolbarBinding.Mode.EDIT.label(),
            ProjectToolbarItem.Tool("#Base", "Wiring Tool") to ProjectToolbarBinding.Mode.WIRE.label(),
            ProjectToolbarItem.Tool("#Base", "Text Tool") to message(Messages.MouseMappingText),
        ) + registry.definitions.filter { it.library.startsWith("#") }.map { definition ->
            ProjectToolbarItem.Tool(definition.library, definition.fileName,
                if (definition.kind == ComponentKind.OUTPUT) outputAttributes else emptyMap()) to definition.kind.localizedLabel()
        } + project.circuits.map { circuit -> ProjectToolbarItem.Tool(null, circuit.name) to LocalizedText.literal(circuit.name) }
    }
    editing?.let { (index, prototype) ->
        ProjectToolDefaultsDialog(prototype, registry, onApply = { updates ->
            entries = entries.toMutableList().apply { this[index] = ProjectToolbar.withToolAttributes(this[index], updates) }
            editing = null
        }, onDismiss = { editing = null })
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (choosing) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(strings("project_toolbar_find")) })
            val matching = choices.filter { (item, title) ->
                strings(title).contains(query, ignoreCase = true) || item.name.contains(query, ignoreCase = true)
            }
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(matching) { (item, title) ->
                    TextButton(onClick = { entries = entries + item; choosing = false }, enabled = writable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings(title)) }
                }
            }
            TextButton(onClick = { choosing = false }) { Text(strings(Messages.ActionCancel)) }
        } else {
            if (entries.isEmpty()) Text(strings("project_toolbar_empty"))
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                itemsIndexed(entries) { index, item ->
                    val binding = remember(item, project.extraXml, project.libraries, project.sourceVersion, project.circuits, registry) {
                        ProjectToolbar.binding(item, project, registry)
                    }
                    val name = when {
                        item.isSeparator -> strings("project_toolbar_separator")
                        binding != null -> strings(binding.label())
                        else -> strings("project_toolbar_unavailable", "name" to item.name.orEmpty())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val prototype = when (binding) {
                            is ProjectToolbarBinding.Place -> binding.component
                            is ProjectToolbarBinding.Text -> binding.prototype
                            else -> null
                        }
                        if (prototype == null) Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        else TextButton(onClick = { editing = index to prototype }, enabled = writable,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = strings("project_tool_edit_defaults", "name" to name) }) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                        ToolbarRowButton("↑", strings("project_toolbar_move_up", "name" to name), writable && index > 0) {
                            entries = entries.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }
                        ToolbarRowButton("↓", strings("project_toolbar_move_down", "name" to name), writable && index < entries.lastIndex) {
                            entries = entries.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }
                        ToolbarRowButton("×", strings("project_toolbar_remove", "name" to name), writable) {
                            entries = entries.toMutableList().apply { removeAt(index) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { choosing = true; query = "" }, enabled = writable, modifier = Modifier.weight(1f)) {
                    Text(strings("project_toolbar_add_tool"))
                }
                TextButton(onClick = { entries = entries + ProjectToolbarItem.Separator }, enabled = writable, modifier = Modifier.weight(1f)) {
                    Text(strings("project_toolbar_add_separator"))
                }
            }
            Button(onClick = { apply(ProjectToolbar.apply(project, entries)) }, enabled = writable && entries != initial,
                modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp)) { Text(strings(Messages.ActionApply)) }
        }
    }
}

@Composable
private fun ToolbarRowButton(symbol: String, description: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
        .semantics { contentDescription = description }) { Text(symbol) }
}

private fun ProjectToolbarBinding.label(): LocalizedText = when (this) {
    ProjectToolbarBinding.Mode.INTERACT -> message(Messages.ActionSimulate)
    ProjectToolbarBinding.Mode.EDIT -> message(Messages.ActionEditTool)
    ProjectToolbarBinding.Mode.SELECT -> message(Messages.ActionSelect)
    ProjectToolbarBinding.Mode.WIRE -> message(Messages.ActionWire)
    is ProjectToolbarBinding.Place -> component.circuitName?.let(LocalizedText::literal) ?: component.kind.localizedLabel()
    is ProjectToolbarBinding.Text -> message(Messages.MouseMappingText)
}

internal fun ProjectToolbarBinding.Mode.editorMode(): EditorMode = when (this) {
    ProjectToolbarBinding.Mode.INTERACT -> EditorMode.INTERACT
    ProjectToolbarBinding.Mode.EDIT -> EditorMode.EDIT
    ProjectToolbarBinding.Mode.SELECT -> EditorMode.SELECT
    ProjectToolbarBinding.Mode.WIRE -> EditorMode.WIRE
}

/**
 * Evolution's KeyboardToolSelection: the Nth selectable toolbar item, skipping separators and the
 * touch-only Move mode so the numbering matches the project's toolbar.
 */
internal class ToolbarShortcutTargets {
    var actions: List<EditorToolbarAction> = emptyList()

    fun select(index: Int): Boolean {
        val action = actions.filter { !it.separator && it.id != "move" }.getOrNull(index) ?: return false
        if (!action.enabled) return false
        action.onClick()
        return true
    }
}
