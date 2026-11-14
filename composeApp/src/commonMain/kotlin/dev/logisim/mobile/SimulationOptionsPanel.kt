package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GateUndefinedPolicy
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.TickFrequencyChoice
import dev.logisim.mobile.core.formatTickFrequency
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * The parent applies the single resulting project through its normal undo history. Unlike the toolbar's frequency
 * chip and Simulate > Tick frequency (EditorState.setTickFrequency, not undoable, as in Evolution), a frequency chosen
 * here is part of that one undoable "Edit project" step, with the other options it applies.
 *
 * The typed options live above the Mouse mappings detour, so opening it, cancelling it or applying a mapping (through
 * [onApplyMouseMappings], which returns here) keeps them; a sheet asks before a dismissal discards them (X3).
 */
@Composable
fun SimulationOptionsPanel(
    project: CircuitProject,
    onApply: (CircuitProject) -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    mouseMappingRegistry: ComponentRegistry = ComponentCatalog.registry,
    onApplyMouseMappings: (CircuitProject) -> Unit = onApply,
) {
    val strings = rememberLocalizer()
    var showMouseMappings by remember { mutableStateOf(false) }
    // Keyed on the options, not the project: applying a mouse mapping changes the project but not its options.
    val read = remember(project) { runCatching { SimulationProjectOptions.read(project) }.getOrNull() }
    var initial by remember(read) { mutableStateOf(read) }
    val draft = remember(initial) { initial?.let(::SimulationOptionsDraft) }
    val result = draft?.result()
    EditorSheetDraft(draft?.dirty == true, onApply = result?.let { options -> { onApply(options.applyTo(project)) } })
    if (showMouseMappings) {
        ProjectMouseMappingsPanel(project, mouseMappingRegistry, onApply = { onApplyMouseMappings(it); showMouseMappings = false },
            onBack = { showMouseMappings = false }, modifier = modifier)
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings("simulation_options_title"), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        TextButton(onClick = { showMouseMappings = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(strings("mouse_mapping_title"))
        }
        if (draft == null) {
            Text(strings("simulation_options_import_invalid"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { initial = SimulationProjectOptions() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings("simulation_options_restore_defaults"))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        } else {
            SimulationOptionsForm(draft, onApply = { onApply(it.applyTo(project)) }, onDismiss = onDismiss)
        }
    }
}

/** The options as typed, kept apart from the form so they survive it leaving the composition. */
internal class SimulationOptionsDraft(val initial: SimulationProjectOptions) {
    var limitText by mutableStateOf(initial.propagationLimit.toString())
    var frequency by mutableStateOf(initial.tickFrequencyHz)
    var memoryUnknown by mutableStateOf(initial.memoryStartupUnknown)
    var gatePolicy by mutableStateOf(initial.gateUndefined)
    var automatic by mutableStateOf(initial.autoPropagating)
    val limit: Int? get() = limitText.toIntOrNull()?.takeIf { it > 0 }

    fun result(): SimulationProjectOptions? = limit?.let { SimulationProjectOptions(it, memoryUnknown, gatePolicy, frequency, automatic) }

    val dirty: Boolean get() = limitText != initial.propagationLimit.toString() || frequency != initial.tickFrequencyHz ||
        memoryUnknown != initial.memoryStartupUnknown || gatePolicy != initial.gateUndefined || automatic != initial.autoPropagating
}

@Composable
private fun SimulationOptionsForm(
    draft: SimulationOptionsDraft,
    onApply: (SimulationProjectOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val limit = draft.limit
    OutlinedTextField(draft.limitText, { draft.limitText = it }, modifier = Modifier.fillMaxWidth().testTag("simulation-options-limit"),
        singleLine = true,
        label = { Text(strings("simulation_options_propagation_limit")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = limit == null,
        supportingText = { if (limit == null) Text(strings("simulation_options_positive_integer")) })
    TickFrequencyField(draft.frequency) { draft.frequency = it }
    SimulationOptionToggle(strings("simulation_options_memory_unknown"), draft.memoryUnknown) { draft.memoryUnknown = it }
    Text(strings("simulation_options_undefined_inputs"), style = MaterialTheme.typography.titleSmall)
    for (policy in GateUndefinedPolicy.entries) {
        val label = strings(if (policy == GateUndefinedPolicy.IGNORE) "simulation_options_undefined_ignore" else "simulation_options_undefined_error")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = draft.gatePolicy == policy, role = Role.RadioButton, onClick = { draft.gatePolicy = policy }),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = draft.gatePolicy == policy, onClick = null)
            Text(label, modifier = Modifier.padding(top = 12.dp))
        }
    }
    SimulationOptionToggle(strings("simulation_options_auto_propagating"), draft.automatic) { draft.automatic = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        Button(enabled = limit != null, onClick = { draft.result()?.let(onApply) },
            modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("simulation_options_apply")) }
    }
}

/**
 * The tick frequency as a drop-down of Evolution's rates (the toolbar's and the Simulate menu's list). A stored rate
 * that is not one of them, such as an imported 3 Hz or 4 kHz, stays listed and selected as custom, so applying the
 * other options keeps it unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TickFrequencyField(current: Double, onChange: (Double) -> Unit) {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val choices = TickFrequencies.choicesWith(current)
    fun label(choice: TickFrequencyChoice): String = formatTickFrequency(choice.hz).let {
        if (choice.custom) strings("simulation_tick_frequency_custom", "value" to it) else it
    }
    val currentLabel = choices.firstOrNull { TickFrequencies.same(it.hz, current) }?.let(::label) ?: formatTickFrequency(current)
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(currentLabel, {}, readOnly = true, singleLine = true,
            label = { Text(strings("simulation_tick_frequency")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("simulation-options-frequency"))
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                val selected = TickFrequencies.same(choice.hz, current)
                DropdownMenuItem(text = { Text(label(choice)) }, onClick = { expanded = false; onChange(choice.hz) },
                    leadingIcon = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("simulation-options-frequency-${choice.hz}")
                        .semantics { this.selected = selected; role = Role.RadioButton })
            }
        }
    }
}

@Composable
private fun SimulationOptionToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked, onCheckedChange = null)
    }
}
