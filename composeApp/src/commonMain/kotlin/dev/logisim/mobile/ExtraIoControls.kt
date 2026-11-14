package dev.logisim.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.BuzzerTone
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.ExtraIoEvent
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.ExtraOscilloscopeState
import dev.logisim.mobile.core.ExtraSliderState
import dev.logisim.mobile.core.ExtraSwitchState
import dev.logisim.mobile.core.Ne555State
import dev.logisim.mobile.core.PlaRomTable
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.localizedPlural
import dev.logisim.mobile.localization.localizedString
import kotlin.math.roundToInt

/** Native-friendly interaction controls for the selected extra component. */
@Composable
fun ExtraIoControls(component: CircuitComponent, state: ComponentState?,
                    onEvent: (ExtraIoEvent) -> Unit, onAttributes: (Map<String, String>) -> Unit) {
    when (component.kind) {
        ExtraIoKinds.Switch, ExtraIoKinds.TwoWaySwitch -> {
            val closed = (state as? ExtraSwitchState)?.closed == true
            Button(onClick = { onEvent(ExtraIoEvent.Toggle) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (component.kind == ExtraIoKinds.TwoWaySwitch) {
                    localizedString("extra_io_switch_contact", "contact" to if (closed) 1 else 2)
                } else localizedString(if (closed) "extra_io_switch_open" else "extra_io_switch_close"))
            }
        }
        ExtraIoKinds.Slider -> {
            val slider = state as? ExtraSliderState ?: ExtraSliderState().direction(component.attributes["Direction"] == "right_to_left")
            val width = component.attributeInt("width", 8).coerceIn(1, 8)
            val value = slider.value(width).display
            val name = component.label.ifBlank { localizedString(component.kind.localizedLabel()) }
            Column {
                Text(localizedString("extra_io_slider_value", "value" to value), Modifier.clearAndSetSemantics {})
                // One stop per output value (X5): TalkBack hears the value, not a percentage of 0..255.
                Slider(slider.position.toFloat(), { onEvent(ExtraIoEvent.MoveSlider(extraSliderPosition(it, width))) },
                    valueRange = 0f..255f, steps = extraSliderSteps(width), modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = name
                        stateDescription = value
                    })
            }
        }
        ExtraIoKinds.Oscilloscope -> {
            val count = (state as? ExtraOscilloscopeState)?.samples?.size ?: 0
            Text(localizedPlural("extra_io_half_cycle_samples", count.toLong()))
            TextButton(onClick = { onEvent(ExtraIoEvent.ClearTrace) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedString("extra_io_clear_trace")) }
        }
        ExtraIoKinds.PlaRom -> PlaRomEditor(component, onAttributes)
        ExtraIoKinds.Buzzer -> {
            val tone = state as? BuzzerTone
            Text(if (tone?.audible == true) localizedString("extra_io_audio_frequency", "frequency" to tone.frequencyHz)
                else localizedString("extra_io_audio_disabled"))
        }
        ExtraIoKinds.Ne555 -> {
            val timer = state as? Ne555State
            Text(localizedString("extra_io_timer_output", "value" to if (timer?.outputHigh == true) 1 else 0))
            Text(localizedString("extra_io_capacitor_voltage", "voltage" to ((timer?.capacitorVoltage ?: 0.0) * 100).roundToInt() / 100.0))
        }
    }
}

/**
 * The slider stops between its 2^width output values: one per value up to 6 bits (62 tick marks); wider sliders move
 * continuously, and [extraSliderPosition] still lands on a value's position.
 */
internal fun extraSliderSteps(width: Int): Int = if (width in 1..6) ((1 shl width) - 2).coerceAtLeast(0) else 0

/** The 0..255 position of the output value nearest to [position]; SliderState.value then gives that value. */
internal fun extraSliderPosition(position: Float, width: Int): Int {
    val top = (1 shl width.coerceIn(1, 8)) - 1
    val level = (position.coerceIn(0f, 255f) * top / 255f).roundToInt()
    return (level * 255f / top).roundToInt()
}

@Composable
private fun PlaRomEditor(component: CircuitComponent, onAttributes: (Map<String, String>) -> Unit) {
    val decoded: Result<PlaRomTable> = try {
        Result.success(PlaRomTable.decode(component.attributeInt("inputs", 4), component.attributeInt("and", 4),
            component.attributeInt("outputs", 4), component.attributes["Contents"].orEmpty()))
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }
    val failure = decoded.exceptionOrNull()
    if (failure != null) {
        Text(localizedString(failure.localizedDescription(message("extra_io_pla_value_invalid"))))
        ClearPlaRomMatrix(onAttributes)
        return
    }
    val table = decoded.getOrThrow()
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Text(localizedString("extra_io_pla_title"))
        Row {
            Text(localizedString("extra_io_pla_term"), Modifier.widthIn(min = 56.dp))
            repeat(table.inputCount) { Text("I${table.inputCount - it - 1}", Modifier.widthIn(min = 48.dp)) }
            repeat(table.outputCount) { Text("O${table.outputCount - it - 1}", Modifier.widthIn(min = 48.dp)) }
        }
        repeat(table.termCount) { term ->
            Row {
                Text("${term + 1}", Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp))
                repeat(table.inputCount) { input ->
                    val literal = table.literals[term][input]
                    // The glyphs stay; TalkBack hears the term, the input (counting down like the headers) and its use.
                    val name = localizedString("extra_io_pla_input_cell", "term" to term + 1, "input" to table.inputCount - input - 1)
                    val use = localizedString(when (literal) { 1 -> "extra_io_pla_negated"; 2 -> "extra_io_pla_direct"; else -> "extra_io_pla_unused" })
                    TextButton(onClick = { onAttributes(mapOf("Contents" to table.withLiteral(term, input, (literal + 1) % 3).encode())) },
                        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics {
                            contentDescription = name
                            stateDescription = use
                            role = Role.Button
                        }) {
                        Text(when (literal) { 1 -> "¬I"; 2 -> "I"; else -> "·" }, Modifier.clearAndSetSemantics {})
                    }
                }
                repeat(table.outputCount) { output ->
                    val enabled = table.connections[term][output]
                    val name = localizedString("extra_io_pla_output_cell", "term" to term + 1, "output" to table.outputCount - output - 1)
                    Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                        .toggleable(enabled, role = Role.Checkbox) {
                            onAttributes(mapOf("Contents" to table.withConnection(term, output, it).encode()))
                        }.semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                        Text(if (enabled) "●" else "·", Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        ClearPlaRomMatrix(onAttributes)
    }
}

@Composable
private fun ClearPlaRomMatrix(onAttributes: (Map<String, String>) -> Unit) {
    TextButton(onClick = { onAttributes(mapOf("Contents" to "")) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(localizedString("extra_io_clear_matrix"))
    }
}
