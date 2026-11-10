package dev.logisim.mobile

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.AnnotationVisuals
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SequentialLibrary
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

internal sealed interface TextToolTarget {
    data class Existing(val id: String) : TextToolTarget
    data class Empty(val point: GridPoint) : TextToolTarget
}

internal data class TextToolEdit(val document: CircuitDocument, val component: CircuitComponent, val isNew: Boolean) {
    val initialText = when (component.kind) {
        AnnotationLibrary.Text -> component.attributes["text"] ?: component.label
        ComponentKind.TUNNEL -> component.label
        else -> component.label.ifBlank { component.sharedLabel }
    }
}

internal fun TextMeasurer.textToolTarget(document: CircuitDocument, point: Offset, registry: ComponentRegistry): TextToolTarget? {
    val components = document.components.asReversed()
    for (component in components) {
        val body = classicComponentBounds(component, registry)
        val text = when (component.kind) {
            AnnotationLibrary.Text -> AnnotationVisuals.item(component).primitives.singleOrNull() as? VisualPrimitive.Text
            AnnotationLibrary.Image -> null
            ComponentKind.TUNNEL -> classicTunnelLabel(component, body)
            else -> classicComponentLabel(component, body)
        }
        if (text == null || text.text.isBlank()) continue
        val bounds = visualTextBounds(text)
        if (point.x.toDouble() in bounds.left..bounds.right && point.y.toDouble() in bounds.top..bounds.bottom) {
            return TextToolTarget.Existing(component.id)
        }
    }
    val occupied = components.firstOrNull { classicComponentBounds(it, registry).contains(point) }
    if (occupied != null) {
        return TextToolTarget.Existing(occupied.id).takeIf {
            occupied.kind in textEditableBodies && occupied.label.isBlank() &&
                occupied.attributes["labelvisible"] != "false"
        }
    }
    val location = canvasPoint(point, snapToGrid = false)
    return TextToolTarget.Empty(location).takeIf { location.x >= 0 && location.y >= 0 && document.wires.none { it.contains(location) } }
}

// The original Text Tool accepts a body hit only for these label-capable devices.
private val textEditableBodies = setOf(
    ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.CLOCK, ComponentKind.PROBE,
    ComponentKind.D_FLIP_FLOP, SequentialLibrary.TFlipFlop, SequentialLibrary.JKFlipFlop, SequentialLibrary.SRFlipFlop,
    ComponentKind.REGISTER, ComponentKind.COUNTER, SequentialLibrary.ShiftRegister, SequentialLibrary.Random,
    ComponentKind.BUTTON, ComponentKind.LED,
)

@Composable
internal fun TextToolDialog(edit: TextToolEdit, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    var value by remember(edit) { mutableStateOf(edit.initialText) }
    val focus = remember { FocusRequester() }
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings(Messages.MouseMappingText)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus).testTag("text-tool-input"),
                label = { Text(strings(Messages.PropertyLabel)) },
                singleLine = edit.component.kind != AnnotationLibrary.Text,
            )
            LaunchedEffect(edit) {
                withFrameNanos { }
                focus.requestFocus()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(value); onDismiss() },
                enabled = value.none { it.code < 32 && it !in "\t\n\r" },
                modifier = Modifier.testTag("text-tool-apply"),
            ) {
                Text(strings(Messages.ActionApply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
