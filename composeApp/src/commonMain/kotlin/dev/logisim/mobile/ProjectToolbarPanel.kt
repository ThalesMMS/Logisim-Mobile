package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
 * Mobile-only base tools. Evolution's toolbar is the project's, and its desktop keeps the Edit, Wiring and Poke tools
 * one key or mouse button away even when the toolbar is empty (KeyboardToolSelection, default.templ mappings); touch
 * has neither, so a toolbar without any of Evolution's tool modes gets these. They are never written to `<toolbar>`.
 */
internal enum class MobileBaseTool(val id: String, val mode: EditorMode) {
    POKE("mobile-tool-poke", EditorMode.INTERACT),
    EDIT("mobile-tool-edit", EditorMode.EDIT),
    SELECT("mobile-tool-select", EditorMode.SELECT),
    WIRE("mobile-tool-wire", EditorMode.WIRE),
    TEXT("mobile-tool-text", EditorMode.TEXT);

    fun label(): LocalizedText = when (this) {
        POKE -> message(Messages.ActionPokeTool)
        EDIT -> message(Messages.ActionEditTool)
        SELECT -> message(Messages.ActionSelect)
        WIRE -> message(Messages.ActionWire)
        TEXT -> message(Messages.MouseMappingText)
    }

    companion object {
        const val ID_PREFIX = "mobile-tool-"
        const val SEPARATOR_ID = "mobile-tool-separator"
    }
}

/**
 * The base tools offered beside the project's toolbar: all of them when it has no tool mode (#344 projects), else
 * none. A toolbar with its own Text Tool keeps that one instead of a second Text tool.
 */
internal fun mobileBaseTools(entries: List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>>): List<MobileBaseTool> = when {
    entries.any { it.second is ProjectToolbarBinding.Mode } -> emptyList()
    projectTextTool(entries) != null -> MobileBaseTool.entries - MobileBaseTool.TEXT
    else -> MobileBaseTool.entries
}

/** The toolbar action id of the project's toolbar entry at [index]. */
private fun projectToolActionId(index: Int) = "project-tool-$index"

/** The project's own Text Tool: the first one its toolbar binds, and the id of its toolbar action. */
internal class ProjectTextTool(val actionId: String, val prototype: CircuitComponent)

/** [ProjectTextTool] of the toolbar [entries], or null when the toolbar has no Text Tool. */
internal fun projectTextTool(entries: List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>>): ProjectTextTool? {
    val index = entries.indexOfFirst { it.second is ProjectToolbarBinding.Text }
    val binding = entries.getOrNull(index)?.second as? ProjectToolbarBinding.Text ?: return null
    return ProjectTextTool(projectToolActionId(index), binding.prototype)
}

/** Evolution's #Base Text Tool with its default attributes, read from the project without changing it. */
internal fun mobileTextToolPrototype(project: CircuitProject, registry: ComponentRegistry): CircuitComponent? =
    (ProjectToolbar.binding(ProjectToolbarItem.Tool("#Base", "Text Tool"), project, registry) as? ProjectToolbarBinding.Text)?.prototype

/**
 * The project's own toolbar, or Evolution's default one when none of its tools binds, as editor actions ([projectToolbarEntries]).
 * The touch-only Move mode joins the first group of tools, after the project's own modes. A toolbar without tool modes,
 * empty included, starts with the [MobileBaseTool]s, Move among them, so touch can always poke, edit and wire.
 */
@Composable
internal fun projectToolbarActions(state: EditorState, moveLabel: String, componentIconColor: Int? = null): List<EditorToolbarAction> {
    val strings = rememberLocalizer()
    val project = state.project
    val registry = state.componentRegistry
    val entries = remember(project.extraXml, project.libraries, project.sourceVersion, project.circuits, registry) {
        projectToolbarEntries(project, registry)
    }
    val textPrototype = remember(project.libraries, project.sourceVersion, project.circuits, registry) {
        mobileTextToolPrototype(project, registry)
    }
    return editorToolbarActions(state, entries, strings, moveLabel, componentIconColor, textPrototype)
}

/** [projectToolbarActions] outside composition, for the given toolbar [entries]. */
internal fun editorToolbarActions(
    state: EditorState,
    entries: List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>>,
    strings: dev.logisim.mobile.localization.Localizer,
    moveLabel: String,
    componentIconColor: Int? = null,
    textPrototype: CircuitComponent? = mobileTextToolPrototype(state.project, state.componentRegistry),
): List<EditorToolbarAction> {
    val actions = projectToolbarEntryActions(state, entries, strings, componentIconColor)
    val move = EditorToolbarAction("move", moveLabel, selected = state.mode == EditorMode.MOVE, icon = EditorIcon.MOVE) {
        state.changeMode(EditorMode.MOVE)
    }
    val base = mobileBaseTools(entries).mapNotNull { tool -> mobileBaseToolAction(state, tool, strings, textPrototype) }
    if (base.isNotEmpty()) {
        // Poke, Edit, Select, Move, Wire, Text, then the project's own tools.
        val selectEnd = base.indexOfFirst { it.id == MobileBaseTool.SELECT.id } + 1
        val group = base.take(selectEnd) + move + base.drop(selectEnd)
        return if (actions.isEmpty()) group
            else group + EditorToolbarAction(MobileBaseTool.SEPARATOR_ID, "", separator = true, onClick = {}) + actions
    }
    val firstGroupEnd = actions.indexOfFirst { it.separator }.let { if (it < 0) actions.size else it }
    return actions.take(firstGroupEnd) + move + actions.drop(firstGroupEnd)
}

/**
 * The toolbar actions the Edit menu adds when the toolbar is hidden: the project's placement tools. Move, the
 * [MobileBaseTool]s and the project's Poke, Edit, Select and Wiring tools are already in the menu's tool group, and so
 * is its Text Tool [textToolId], which that group's Text item uses ([projectTextTool]).
 */
internal fun hiddenToolbarMenuActions(toolActions: List<EditorToolbarAction>, textToolId: String? = null): List<EditorToolbarAction> {
    val modeIcons = EditorMode.entries.filter { it != EditorMode.TEXT }.mapNotNull { it.editorIcon() }.toSet()
    return toolActions.filterNot { it.id == "move" || it.id == textToolId || it.id.startsWith(MobileBaseTool.ID_PREFIX) || it.icon in modeIcons }
        .fold(emptyList<EditorToolbarAction>()) { kept, action ->
            if (action.separator && (kept.isEmpty() || kept.last().separator)) kept else kept + action
        }.dropLastWhile { it.separator }
}

/** One [MobileBaseTool] as a toolbar or menu action; Text needs Evolution's Text Tool [textPrototype]. */
internal fun mobileBaseToolAction(
    state: EditorState,
    tool: MobileBaseTool,
    strings: dev.logisim.mobile.localization.Localizer,
    textPrototype: CircuitComponent?,
    id: String = tool.id,
): EditorToolbarAction? = if (tool == MobileBaseTool.TEXT) {
    textPrototype?.let { prototype ->
        EditorToolbarAction(id, strings(tool.label()), enabled = state.documentWritable && state.interactionsEnabled,
            selected = state.mode == EditorMode.TEXT, icon = EditorIcon.TEXT) { state.activateTextTool(prototype) }
    }
} else EditorToolbarAction(id, strings(tool.label()), selected = state.mode == tool.mode, icon = tool.mode.editorIcon()) {
    state.changeMode(tool.mode)
}

private fun projectToolbarEntryActions(
    state: EditorState,
    entries: List<Pair<ProjectToolbarItem, ProjectToolbarBinding?>>,
    strings: dev.logisim.mobile.localization.Localizer,
    componentIconColor: Int?,
): List<EditorToolbarAction> {
    return buildList {
        entries.forEachIndexed { index, (item, binding) ->
            val id = projectToolActionId(index)
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
    // Where Add puts the chosen tool: after a row ("Insert tool after"), else at the end (X4).
    var insertAt by remember(initial) { mutableStateOf<Int?>(null) }
    var query by remember(initial) { mutableStateOf("") }
    var editing by remember(initial) { mutableStateOf<Pair<Int, CircuitComponent>?>(null) }
    // The tool sheet asks before Back, the scrim, a swipe or Close discards unapplied toolbar changes (X3).
    EditorSheetDraft(writable && entries != initial, onApply = { apply(ProjectToolbar.apply(project, entries)) })
    val choices = remember(registry, project.sourceVersion, project.circuits) {
        val outputAttributes = if (project.sourceVersion.startsWith("2.7.")) mapOf("output" to "true") else mapOf("type" to "output")
        // Grouped like the library tree Evolution's ToolbarOptions picks from: tools, each library, the project's circuits.
        listOf(message("project_toolbar_group_tools") to listOf(
            ProjectToolbarItem.Tool("#Base", "Poke Tool") to ProjectToolbarBinding.Mode.INTERACT.label(),
            ProjectToolbarItem.Tool("#Base", "Edit Tool") to ProjectToolbarBinding.Mode.EDIT.label(),
            ProjectToolbarItem.Tool("#Base", "Wiring Tool") to ProjectToolbarBinding.Mode.WIRE.label(),
            ProjectToolbarItem.Tool("#Base", "Text Tool") to message(Messages.MouseMappingText),
        )) + registry.definitions.filter { it.library.startsWith("#") }.groupBy { it.library }.map { (library, definitions) ->
            builtinLibraryName(library) to definitions.map { definition ->
                ProjectToolbarItem.Tool(definition.library, definition.fileName,
                    if (definition.kind == ComponentKind.OUTPUT) outputAttributes else emptyMap()) to definition.kind.localizedLabel()
            }
        } + listOf(message("project_toolbar_group_circuits") to project.circuits.map { circuit ->
            ProjectToolbarItem.Tool(null, circuit.name) to LocalizedText.literal(circuit.name)
        })
    }
    editing?.let { (index, prototype) ->
        ProjectToolDefaultsDialog(prototype, registry, onApply = { updates ->
            entries = entries.toMutableList().apply { this[index] = ProjectToolbar.withToolAttributes(this[index], updates) }
            editing = null
        }, onDismiss = { editing = null })
    }

    fun nameOf(item: ProjectToolbarItem): String {
        val binding = ProjectToolbar.binding(item, project, registry)
        return when {
            item.isSeparator -> strings("project_toolbar_separator")
            binding != null -> strings(binding.label())
            else -> strings("project_toolbar_unavailable", "name" to item.name.orEmpty())
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (choosing) {
            insertAt?.let { at -> entries.getOrNull(at - 1)?.let { after ->
                Text(strings("project_toolbar_inserting_after", "name" to nameOf(after)), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("project-toolbar-inserting"))
            } }
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(strings("project_toolbar_find")) })
            val matching = choices.map { (group, tools) ->
                group to tools.filter { (item, title) ->
                    strings(title).contains(query, ignoreCase = true) || item.name.contains(query, ignoreCase = true)
                }
            }.filter { it.second.isNotEmpty() }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                for ((group, tools) in matching) {
                    item(key = "group:${group.key}:${group.fallback}") {
                        Text(strings(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
                    }
                    items(tools) { (item, title) ->
                        TextButton(onClick = {
                            entries = ProjectToolbarEdits.insert(entries, insertAt ?: entries.size, item)
                            choosing = false
                            insertAt = null
                        }, enabled = writable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(strings(title), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            TextButton(onClick = { choosing = false; insertAt = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Messages.ActionCancel))
            }
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
                            entries = ProjectToolbarEdits.move(entries, index, index - 1)
                        }
                        ToolbarRowButton("↓", strings("project_toolbar_move_down", "name" to name), writable && index < entries.lastIndex) {
                            entries = ProjectToolbarEdits.move(entries, index, index + 1)
                        }
                        // Insertion, the long moves and removal sit in a menu, away from the arrows a thumb taps (X4).
                        ToolbarRowMenu(index, name, entries.lastIndex, writable, onInsertTool = {
                            insertAt = index + 1
                            query = ""
                            choosing = true
                        }, onInsertSeparator = { entries = ProjectToolbarEdits.insert(entries, index + 1, ProjectToolbarItem.Separator) },
                            onMove = { to -> entries = ProjectToolbarEdits.move(entries, index, to) },
                            onRemove = { entries = ProjectToolbarEdits.remove(entries, index) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { choosing = true; insertAt = null; query = "" }, enabled = writable,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(strings("project_toolbar_add_tool"))
                }
                TextButton(onClick = { entries = entries + ProjectToolbarItem.Separator }, enabled = writable,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(strings("project_toolbar_add_separator"))
                }
            }
            Button(onClick = { apply(ProjectToolbar.apply(project, entries)) }, enabled = writable && entries != initial,
                modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp)) { Text(strings(Messages.ActionApply)) }
        }
    }
}

@Composable
private fun ToolbarRowButton(symbol: String, description: String, enabled: Boolean, tag: String? = null, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
        .then(if (tag == null) Modifier else Modifier.testTag(tag))
        .semantics { contentDescription = description }) { Text(symbol) }
}

/** A toolbar row's "⋮" menu: insert after it, move it to an end, or remove it. */
@Composable
private fun ToolbarRowMenu(
    index: Int,
    name: String,
    lastIndex: Int,
    writable: Boolean,
    onInsertTool: () -> Unit,
    onInsertSeparator: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarRowButton("⋮", strings("project_toolbar_more", "name" to name), writable, "project-toolbar-more-$index") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("project-toolbar-menu-$index")) {
            @Composable fun item(label: String, tag: String, enabled: Boolean = true, action: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; action() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
            item(strings("project_toolbar_insert_tool_after"), "project-toolbar-insert-after", action = onInsertTool)
            item(strings("project_toolbar_insert_separator_after"), "project-toolbar-insert-separator-after", action = onInsertSeparator)
            item(strings("project_toolbar_move_top"), "project-toolbar-move-top", enabled = index > 0) { onMove(0) }
            item(strings("project_toolbar_move_bottom"), "project-toolbar-move-bottom", enabled = index < lastIndex) { onMove(lastIndex) }
            item(strings("project_toolbar_remove", "name" to name), "project-toolbar-remove", action = onRemove)
        }
    }
}

/** Toolbar edits on the entry list; ProjectToolbar.apply writes the same `<toolbar>` for the same final order. */
internal object ProjectToolbarEdits {
    fun insert(entries: List<ProjectToolbarItem>, index: Int, item: ProjectToolbarItem): List<ProjectToolbarItem> =
        entries.toMutableList().apply { add(index.coerceIn(0, size), item) }

    fun move(entries: List<ProjectToolbarItem>, from: Int, to: Int): List<ProjectToolbarItem> =
        if (from !in entries.indices) entries else entries.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }

    fun remove(entries: List<ProjectToolbarItem>, index: Int): List<ProjectToolbarItem> =
        if (index !in entries.indices) entries else entries.toMutableList().apply { removeAt(index) }
}

private fun ProjectToolbarBinding.label(): LocalizedText = when (this) {
    ProjectToolbarBinding.Mode.INTERACT -> message(Messages.ActionPokeTool)
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
 * Evolution's KeyboardToolSelection: the Nth selectable toolbar item, skipping separators, the touch-only
 * Move mode and the injected [MobileBaseTool]s, so the numbering matches the project's toolbar.
 */
internal class ToolbarShortcutTargets {
    var actions: List<EditorToolbarAction> = emptyList()

    fun select(index: Int): Boolean {
        val action = actions.filter { !it.separator && it.id != "move" && !it.id.startsWith(MobileBaseTool.ID_PREFIX) }
            .getOrNull(index) ?: return false
        if (!action.enabled) return false
        action.onClick()
        return true
    }
}
