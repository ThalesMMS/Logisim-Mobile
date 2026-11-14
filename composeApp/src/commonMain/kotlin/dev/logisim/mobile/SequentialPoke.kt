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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.FlipFlopPoked
import dev.logisim.mobile.core.SequentialHexEntered
import dev.logisim.mobile.core.SequentialLibrary
import dev.logisim.mobile.core.ShiftRegisterStagePoked
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

/**
 * The poke target under [point]. [tolerance] (world units, 0 for the mouse) lets a fingertip reach it: the flip-flop
 * toggle grows from Evolution's 8-unit circle up to half the body, and a shift-register tap anywhere on the body
 * row reaches the nearest stage. The returned bounds stay the drawn target.
 */
internal fun CircuitComponent.sequentialPokeTarget(
    point: Offset, registry: ComponentRegistry, tolerance: Float = 0f,
): SequentialPokeTarget? {
    if (kind == SequentialLibrary.ShiftRegister) return shiftPokeTarget(point, registry, tolerance)
    if (kind !in flipFlopKinds && kind != ComponentKind.REGISTER && kind != ComponentKind.COUNTER) return null
    val layout = layout(registry)
    val bounds = layout.bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    if (kind in flipFlopKinds) {
        // AbstractFlipFlop.Poker.isInside: d² < 8² around the toggle point.
        val radius = max(8f, min(tolerance, bounds.minDimension / 2f))
        if ((point - bounds.center).getDistanceSquared() >= radius * radius) return null
        return SequentialPokeTarget(id, Rect(bounds.center - Offset(8f, 8f), bounds.center + Offset(8f, 8f)), toggle = FlipFlopPoked)
    }
    if (!bounds.contains(point)) return null
    return SequentialPokeTarget(id, bounds, width = layout.outputs.first { it.name == "q" }.width)
}

private fun CircuitComponent.shiftPokeTarget(point: Offset, registry: ComponentRegistry, tolerance: Float): SequentialPokeTarget? {
    val targets = shiftPokeTargets(registry)
    if (tolerance <= 0f) return targets.firstOrNull { it.bounds.contains(point) }
    // Stages sit 10 units apart: grow each box by at most half that sideways, and by the tolerance vertically
    // within the register's body; the nearest box wins.
    val body = layout(registry).bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    val sideways = min(tolerance, 5f)
    return targets.filter { target ->
        val reach = Rect(target.bounds.left - sideways, target.bounds.top - tolerance,
            target.bounds.right + sideways, target.bounds.bottom + tolerance)
        reach.contains(point) && body.inflate(0.5f).contains(point)
    }.minWithOrNull(compareBy<SequentialPokeTarget> { target ->
        val dx = max(max(target.bounds.left - point.x, 0f), point.x - target.bounds.right)
        val dy = max(max(target.bounds.top - point.y, 0f), point.y - target.bounds.bottom)
        dx * dx + dy * dy
    }.thenBy { abs(point.x - it.bounds.center.x) })
}

/**
 * The poke dialog's title: the register's label, or its kind when unlabelled, and the shift-register stage it edits
 * (C49; Evolution edits the poked value in place, so the dialog says what it edits).
 */
internal fun sequentialPokeTitle(component: CircuitComponent?, target: SequentialPokeTarget): LocalizedText {
    val name = component?.label?.takeIf { it.isNotBlank() }?.let(LocalizedText::literal)
        ?: component?.kind?.localizedLabel() ?: LocalizedText.literal(target.componentId)
    return target.stage?.let { message("sequential_poke_title_stage", "name" to name, "stage" to it) }
        ?: message("sequential_poke_title", "name" to name)
}

@Composable
internal fun SequentialHexEditor(
    target: SequentialPokeTarget,
    currentValue: String,
    onDigits: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    component: CircuitComponent? = null,
) {
    val strings = rememberLocalizer()
    val focus = remember(target) { FocusRequester() }
    var draft by remember(target) { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exportEditorTestTags(),
        title = { Text(strings(sequentialPokeTitle(component, target)), Modifier.testTag("sequential-poke-title")) },
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
                // Each digit applies at once; the line under the field shows the register's live value.
                supportingText = { Text(strings("sequential_poke_current", "value" to currentValue)) },
                singleLine = true,
                // Hexadecimal digits: letters without suggestions or autocorrection, shown in capitals.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
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
