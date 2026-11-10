package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.FlipFlopPoked
import dev.logisim.mobile.core.SequentialHexEntered
import dev.logisim.mobile.core.SequentialLibrary
import dev.logisim.mobile.core.ShiftRegisterStagePoked
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

internal val flipFlopKinds = setOf(
    ComponentKind.D_FLIP_FLOP, SequentialLibrary.TFlipFlop, SequentialLibrary.JKFlipFlop, SequentialLibrary.SRFlipFlop,
)

internal data class SequentialPokeTarget(
    val componentId: String,
    val bounds: Rect,
    val stage: Int? = null,
    val width: Int = 1,
    val toggle: ComponentEvent? = null,
) {
    val portName: String get() = stage?.let { "stage$it" } ?: "q"

    fun digitEvent(digits: List<Int>): ComponentEvent? {
        val index = stage ?: return SequentialHexEntered(digits)
        val digit = digits.lastOrNull { it < (1 shl width) } ?: return null
        return ShiftRegisterStagePoked(index, digit)
    }
}

internal fun CircuitComponent.shiftPokeTargets(registry: ComponentRegistry): List<SequentialPokeTarget> {
    if (kind != SequentialLibrary.ShiftRegister || registry.attributes(this).string("appearance") != "classic") return emptyList()
    val layout = layout(registry)
    val bounds = layout.bounds
    val y = bounds.top + (bounds.bottom - bounds.top) * if (label.isEmpty()) 0.5f else 0.75f
    return layout.outputs.filter { it.name.startsWith("stage") && it.width <= 4 }.map { port ->
        val stage = port.name.removePrefix("stage").toInt()
        val x = port.position.x.toFloat()
        SequentialPokeTarget(id, Rect(x - 5f, y - 6f, x + 5f, y + 8f), stage, port.width,
            if (port.width == 1) ShiftRegisterStagePoked(stage) else null)
    }
}

internal fun CircuitComponent.sequentialPokeTarget(point: Offset, registry: ComponentRegistry): SequentialPokeTarget? {
    if (kind == SequentialLibrary.ShiftRegister) return shiftPokeTargets(registry).firstOrNull { it.bounds.contains(point) }
    if (kind !in flipFlopKinds && kind != ComponentKind.REGISTER && kind != ComponentKind.COUNTER) return null
    val layout = layout(registry)
    val bounds = layout.bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    if (kind in flipFlopKinds) {
        if ((point - bounds.center).getDistanceSquared() >= 64f) return null
        return SequentialPokeTarget(id, Rect(bounds.center - Offset(8f, 8f), bounds.center + Offset(8f, 8f)), toggle = FlipFlopPoked)
    }
    if (!bounds.contains(point)) return null
    return SequentialPokeTarget(id, bounds, width = layout.outputs.first { it.name == "q" }.width)
}

@Composable
internal fun SequentialHexEditor(
    target: SequentialPokeTarget,
    currentValue: String,
    onDigits: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberLocalizer()
    val focus = remember(target) { FocusRequester() }
    var draft by remember(target) { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exportEditorTestTags(),
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { next ->
                    val prefix = draft.commonPrefixWith(next).length
                    val suffix = draft.drop(prefix).commonSuffixWith(next.drop(prefix)).length
                    val digits = next.substring(prefix, next.length - suffix).mapNotNull { it.digitToIntOrNull(16) }
                    draft = next.filter { it.digitToIntOrNull(16) != null }.takeLast(16)
                    if (digits.isNotEmpty()) onDigits(digits)
                },
                label = { Text(strings(Messages.MemoryValueRadix, "base" to 16)) },
                supportingText = { Text(currentValue) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDismiss() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("sequential-poke-hex"),
            )
            LaunchedEffect(target) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("sequential-poke-close")) {
                Text(strings(Messages.ActionClose))
            }
        },
    )
}
