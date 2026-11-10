package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ProjectMouseAction
import dev.logisim.mobile.core.ProjectMouseBinding
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseEditTool
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.ProjectMouseTool
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun ProjectMouseMappingsPanel(
    project: CircuitProject,
    registry: ComponentRegistry,
    onApply: (CircuitProject) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val strings = rememberLocalizer()
    var draft by remember(project) { mutableStateOf(project) }
    var gesture by remember(project) { mutableStateOf(ProjectMouseGesture(ProjectMouseButton.PRIMARY)) }
    var chooseButton by remember { mutableStateOf(false) }
    var chooseTool by remember { mutableStateOf(false) }
    var editingDefaults by remember(gesture, draft) { mutableStateOf(false) }
    val bindings = remember(draft, registry) { ProjectMouseMappings.read(draft, registry) }
    val current = bindings[gesture]
    val prototype = when (val action = current?.action) {
        is ProjectMouseAction.Place -> action.prototype
        is ProjectMouseAction.Text -> action.prototype
        else -> null
    }
    if (editingDefaults && prototype != null) {
        ProjectToolDefaultsDialog(prototype, registry, onApply = { updates ->
            draft = ProjectMouseMappings.withToolAttributes(draft, gesture, updates)
            editingDefaults = false
        }, onDismiss = { editingDefaults = false })
    }
    fun buttonLabel(button: ProjectMouseButton): String = strings(when (button) {
        ProjectMouseButton.PRIMARY -> "mouse_mapping_primary"
        ProjectMouseButton.MIDDLE -> "mouse_mapping_middle"
        ProjectMouseButton.SECONDARY -> "mouse_mapping_secondary"
    })
    fun gestureLabel(gesture: ProjectMouseGesture): String = buildList {
        if (gesture.ctrl) add("Ctrl")
        if (gesture.alt) add("Alt")
        if (gesture.shift) add("Shift")
        add(buttonLabel(gesture.button))
    }.joinToString(" + ")
    fun toolLabel(name: String): String {
        val key = when (name) {
            ProjectMouseEditTool.POKE.fileName -> Messages.ActionSimulate
            ProjectMouseEditTool.EDIT.fileName -> Messages.ActionEditTool
            ProjectMouseEditTool.SELECT.fileName -> Messages.ActionSelect
            ProjectMouseEditTool.WIRING.fileName -> Messages.ActionWire
            ProjectMouseEditTool.TEXT.fileName -> "mouse_mapping_text"
            ProjectMouseEditTool.MENU.fileName -> "mouse_mapping_menu"
            else -> return name
        }
        return strings(key)
    }
    fun bindingLabel(binding: ProjectMouseBinding): String {
        val circuit = (binding.action as? ProjectMouseAction.Place)?.prototype?.circuitName
        return when {
            circuit != null -> circuit
            binding.action == null -> strings("mouse_mapping_unavailable", "tool" to binding.name)
            else -> toolLabel(binding.name)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings("mouse_mapping_title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(strings("mouse_mapping_help"))
        for ((mappedGesture, binding) in bindings) {
            TextButton(onClick = { gesture = mappedGesture }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings("mouse_mapping_row", "gesture" to gestureLabel(mappedGesture),
                    "tool" to bindingLabel(binding)))
            }
        }
        Box {
            TextButton(onClick = { chooseButton = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings("mouse_mapping_button", "button" to buttonLabel(gesture.button)))
            }
            DropdownMenu(expanded = chooseButton, onDismissRequest = { chooseButton = false }) {
                for (button in ProjectMouseButton.entries) DropdownMenuItem(
                    text = { Text(buttonLabel(button)) },
                    onClick = { gesture = gesture.copy(button = button); chooseButton = false },
                )
            }
        }
        MouseModifierToggle("Ctrl", gesture.ctrl) { gesture = gesture.copy(ctrl = it) }
        MouseModifierToggle("Alt", gesture.alt) { gesture = gesture.copy(alt = it) }
        MouseModifierToggle("Shift", gesture.shift) { gesture = gesture.copy(shift = it) }
        Box {
            TextButton(onClick = { chooseTool = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                val tool = current?.let(::bindingLabel)
                    ?: strings("mouse_mapping_default")
                Text(strings("mouse_mapping_tool", "tool" to tool))
            }
            DropdownMenu(expanded = chooseTool, onDismissRequest = { chooseTool = false }) {
                fun select(tool: ProjectMouseTool?) {
                    draft = ProjectMouseMappings.set(draft, gesture, tool)
                    chooseTool = false
                }
                DropdownMenuItem(text = { Text(strings("mouse_mapping_default")) }, onClick = { select(null) })
                for (tool in ProjectMouseEditTool.entries) DropdownMenuItem(
                    text = { Text(toolLabel(tool.fileName)) },
                    onClick = { select(tool.reference) },
                )
                for (circuit in draft.circuits) DropdownMenuItem(
                    text = { Text(circuit.name) },
                    onClick = { select(ProjectMouseTool(null, circuit.name)) },
                )
                for (definition in registry.definitions.filter { it.library.startsWith("#") }.distinctBy { it.library to it.fileName }) {
                    DropdownMenuItem(
                        text = { Text(strings(definition.kind.localizedLabel())) },
                        onClick = { select(ProjectMouseTool(definition.library, definition.fileName)) },
                    )
                }
            }
        }
        if (prototype != null && current != null) {
            TextButton(onClick = { editingDefaults = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings("project_tool_edit_defaults", "name" to bindingLabel(current)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
            Button(onClick = { onApply(draft) }, enabled = draft != project, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Messages.ActionApply))
            }
        }
    }
}

@Composable
private fun MouseModifierToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}
