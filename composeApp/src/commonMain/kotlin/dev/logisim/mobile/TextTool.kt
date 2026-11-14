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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import dev.logisim.mobile.core.EvolutionNames
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.max
import kotlin.math.sqrt

internal sealed interface TextToolTarget {
    data class Existing(val id: String) : TextToolTarget
    data class Empty(val point: GridPoint) : TextToolTarget
}

/** Evolution's label rules for the edited component ([componentName] is its factory or circuit name). */
internal data class TextToolLabelRules(val componentName: String?)

internal data class TextToolEdit(
    val document: CircuitDocument,
    val component: CircuitComponent,
    val isNew: Boolean,
    /** The label checks of InstanceComponent's SyntaxChecker; null for Text annotations and original Logisim projects. */
    val labelRules: TextToolLabelRules? = null,
) {
    val initialText = when (component.kind) {
        AnnotationLibrary.Text -> component.attributes["text"] ?: component.label
        ComponentKind.TUNNEL -> component.label
        else -> component.label.ifBlank { component.sharedLabel }
    }

    /** Why [value] would be refused as this label, or null; an unchanged label is never refused (C41). */
    fun problem(value: String): LocalizedText? {
        val rules = labelRules ?: return null
        if (component.kind == AnnotationLibrary.Text || value == initialText) return null
        return EvolutionNames.labelProblem(value, rules.componentName)
    }
}

/**
 * The label or body a Text tool press at [point] edits, or the empty point where it adds a Text. Exact hits come
 * first, as in Evolution: a label under the press, then a body under it (which never edits another component's
 * label). [tolerance] (world units, 0 for the mouse) lets a finger that hits neither reach a label or body beside it:
 * the nearest one within reach wins, labels and bodies ranked together, before a new Text is started (C46).
 */
internal fun TextMeasurer.textToolTarget(
    document: CircuitDocument, point: Offset, registry: ComponentRegistry, tolerance: Float = 0f,
): TextToolTarget? {
    val components = document.components.asReversed()
    // Each visible label with its distance from the press, topmost component first.
    val labels = components.mapNotNull { component ->
        val body = classicComponentBounds(component, registry)
        val text = when (component.kind) {
            AnnotationLibrary.Text -> AnnotationVisuals.item(component).primitives.singleOrNull() as? VisualPrimitive.Text
            AnnotationLibrary.Image -> null
            ComponentKind.TUNNEL -> classicTunnelLabel(component, body)
            else -> classicComponentLabel(component, body)
        }
        if (text == null || text.text.isBlank()) return@mapNotNull null
        val bounds = visualTextBounds(text)
        component to distance(point, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    labels.firstOrNull { it.second == 0.0 }?.let { return TextToolTarget.Existing(it.first.id) }
    // An unlabelled pin, LED or register takes a new label from a press on its body (InstanceTextField).
    fun bodyTarget(component: CircuitComponent): TextToolTarget? = TextToolTarget.Existing(component.id).takeIf {
        component.kind in textEditableBodies && component.label.isBlank() && component.attributes["labelvisible"] != "false"
    }
    val occupied = components.firstOrNull { classicComponentBounds(it, registry).contains(point) }
    if (occupied != null) {
        // A finger on a labelled body still reaches that component's own label beside it, never a neighbour's.
        return bodyTarget(occupied) ?: labels.firstOrNull { (component, distance) ->
            component.id == occupied.id && distance <= tolerance
        }?.let { TextToolTarget.Existing(it.first.id) }
    }
    if (tolerance > 0f) {
        val bodies = components.map { component ->
            val bounds = classicComponentBounds(component, registry)
            component to distance(point, bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble())
        }
        val nearestLabel = labels.filter { it.second <= tolerance }.minByOrNull { it.second }
        val nearestBody = bodies.filter { it.second <= tolerance }.minByOrNull { it.second }
        when {
            nearestLabel != null && (nearestBody == null || nearestLabel.second <= nearestBody.second) ->
                return TextToolTarget.Existing(nearestLabel.first.id)
            nearestBody != null -> return bodyTarget(nearestBody.first)
        }
    }
    val location = canvasPoint(point, snapToGrid = false)
    return TextToolTarget.Empty(location).takeIf { location.x >= 0 && location.y >= 0 && document.wires.none { it.contains(location) } }
}

/** The distance from [point] to the rectangle [left]..[right] × [top]..[bottom]; 0 inside it. */
private fun distance(point: Offset, left: Double, top: Double, right: Double, bottom: Double): Double {
    val dx = max(max(left - point.x, 0.0), point.x - right)
    val dy = max(max(top - point.y, 0.0), point.y - bottom)
    return sqrt(dx * dx + dy * dy)
}

// The original Text Tool accepts a body hit only for these label-capable devices.
private val textEditableBodies = setOf(
    ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.CLOCK, ComponentKind.PROBE,
    ComponentKind.D_FLIP_FLOP, SequentialLibrary.TFlipFlop, SequentialLibrary.JKFlipFlop, SequentialLibrary.SRFlipFlop,
    ComponentKind.REGISTER, ComponentKind.COUNTER, SequentialLibrary.ShiftRegister, SequentialLibrary.Random,
    ComponentKind.BUTTON, ComponentKind.LED,
)

/**
 * Edits a label or a Text's text. A label Evolution would refuse keeps the dialog open with the typed text and the
 * reason under the field, and Apply stays disabled until it is fixed (InstanceComponent explains the refusal, C41).
 */
@Composable
internal fun TextToolDialog(edit: TextToolEdit, onApply: (String) -> LocalizedText?, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    var value by remember(edit) { mutableStateOf(edit.initialText) }
    var refused by remember(edit) { mutableStateOf<LocalizedText?>(null) }
    val focus = remember { FocusRequester() }
    val problem = edit.problem(value) ?: refused
    val annotation = edit.component.kind == AnnotationLibrary.Text
    AppAlertDialog(
        modifier = Modifier.exportEditorTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(strings(Messages.MouseMappingText)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; refused = null },
                modifier = Modifier.focusRequester(focus).testTag("text-tool-input"),
                label = { Text(strings(if (annotation) Messages.AnnotationText else Messages.PropertyLabel)) },
                singleLine = !annotation,
                isError = problem != null,
                supportingText = problem?.let { notice ->
                    { Text(strings(notice), Modifier.testTag("text-tool-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                },
            )
            LaunchedEffect(edit) {
                withFrameNanos { }
                focus.requestFocus()
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    refused = onApply(value)
                    if (refused == null) onDismiss()
                },
                enabled = problem == null && value.none { it.code < 32 && it !in "\t\n\r" },
                modifier = Modifier.testTag("text-tool-apply"),
            ) {
                Text(strings(Messages.ActionApply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Messages.ActionCancel)) } },
    )
}
