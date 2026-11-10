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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GateUndefinedPolicy
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

/** The parent applies the single resulting project through its normal undo history. */
@Composable
fun SimulationOptionsPanel(
    project: CircuitProject,
    onApply: (CircuitProject) -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    mouseMappingRegistry: ComponentRegistry = ComponentCatalog.registry,
) {
    val strings = rememberLocalizer()
    var showMouseMappings by remember(project) { mutableStateOf(false) }
    if (showMouseMappings) {
        ProjectMouseMappingsPanel(project, mouseMappingRegistry, onApply, onBack = { showMouseMappings = false }, modifier = modifier)
        return
    }
    var initial by remember(project) { mutableStateOf(runCatching { SimulationProjectOptions.read(project) }.getOrNull()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings("simulation_options_title"), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        TextButton(onClick = { showMouseMappings = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(strings("mouse_mapping_title"))
        }
        val options = initial
        if (options == null) {
            Text(strings("simulation_options_import_invalid"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { initial = SimulationProjectOptions() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings("simulation_options_restore_defaults"))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        } else {
            SimulationOptionsForm(options, onApply = { onApply(it.applyTo(project)) }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SimulationOptionsForm(
    initial: SimulationProjectOptions,
    onApply: (SimulationProjectOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    var limitText by remember(initial) { mutableStateOf(initial.propagationLimit.toString()) }
    var frequencyText by remember(initial) { mutableStateOf(initial.tickFrequencyHz.toString()) }
    var memoryUnknown by remember(initial) { mutableStateOf(initial.memoryStartupUnknown) }
    var gatePolicy by remember(initial) { mutableStateOf(initial.gateUndefined) }
    var automatic by remember(initial) { mutableStateOf(initial.autoPropagating) }
    val limit = limitText.toIntOrNull()?.takeIf { it > 0 }
    val frequency = frequencyText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    OutlinedTextField(limitText, { limitText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(strings("simulation_options_propagation_limit")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = limit == null,
        supportingText = { if (limit == null) Text(strings("simulation_options_positive_integer")) })
    OutlinedTextField(frequencyText, { frequencyText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(strings("simulation_options_frequency")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = frequency == null,
        supportingText = { if (frequency == null) Text(strings("simulation_options_positive_frequency")) })
    SimulationOptionToggle(strings("simulation_options_memory_unknown"), memoryUnknown) { memoryUnknown = it }
    Text(strings("simulation_options_undefined_inputs"), style = MaterialTheme.typography.titleSmall)
    for (policy in GateUndefinedPolicy.entries) {
        val label = strings(if (policy == GateUndefinedPolicy.IGNORE) "simulation_options_undefined_ignore" else "simulation_options_undefined_error")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = gatePolicy == policy, role = Role.RadioButton, onClick = { gatePolicy = policy }),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = gatePolicy == policy, onClick = null)
            Text(label, modifier = Modifier.padding(top = 12.dp))
        }
    }
    SimulationOptionToggle(strings("simulation_options_auto_propagating"), automatic) { automatic = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        Button(enabled = limit != null && frequency != null, onClick = {
            if (limit != null && frequency != null) onApply(SimulationProjectOptions(limit, memoryUnknown, gatePolicy, frequency, automatic))
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings("simulation_options_apply")) }
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
