package dev.logisim.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.IoButtonState
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/** The components the Signals strip shows: inputs and buttons first, then outputs, probes, displays and registers. */
internal fun signalStripComponents(components: List<CircuitComponent>): List<CircuitComponent> = components.filter {
    it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT ||
        it.kind.name in setOf("BUTTON", "PROBE", "HEX_DISPLAY", "LED", "REGISTER", "COUNTER")
}.sortedBy { if (it.kind == ComponentKind.INPUT || it.kind == ComponentKind.BUTTON) 0 else 1 }

/**
 * A component's name in the strip: its label, or its type and location as Signal recording names it
 * ([Messages.TraceUnnamedSignal]), so two unlabeled pins never read the same.
 */
internal fun signalStripName(component: CircuitComponent): LocalizedText =
    if (component.label.isNotBlank()) LocalizedText.literal(component.label)
    else message(Messages.TraceUnnamedSignal, "type" to component.kind.localizedLabel(),
        "x" to component.position.x, "y" to component.position.y)

/**
 * The Signals strip (C68). Inputs toggle or open their value editor, a Button is held while pressed, and a read-only
 * value selects its component. [wrap] lays the chips out in rows, for the sheet of a short window; otherwise they
 * scroll sideways in one row under the canvas. A circuit without such components says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignalPanel(state: EditorState, radix: EditorSignalRadix, scroll: ScrollState, onEditInput: (String) -> Unit,
                         wrap: Boolean = false) {
    val strings = rememberLocalizer()
    val components = signalStripComponents(state.document.components)
    val container = Modifier.fillMaxWidth().testTag("editor-signal-panel").background(ChromeColors.Paper)
    if (components.isEmpty()) {
        Text(strings("signals_empty"), fontSize = 12.sp, color = ChromeColors.Muted,
            modifier = container.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 14.dp).testTag("editor-signal-empty"))
        return
    }
    val chips: @Composable () -> Unit = {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(strings(Messages.SignalsHeading), fontSize = 11.sp, color = ChromeColors.Muted)
        }
        components.forEach { component -> SignalChip(state, component, radix, onEditInput) }
    }
    if (wrap) {
        FlowRow(container.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    } else {
        Row(container.horizontalScroll(scroll).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
    }
}

@Composable
private fun SignalChip(state: EditorState, component: CircuitComponent, radix: EditorSignalRadix, onEditInput: (String) -> Unit) {
    val strings = rememberLocalizer()
    val bit = state.simulation.componentValues[component.id] ?: LogicValue.UNKNOWN
    val vector = state.simulation.vectorValues[component.id]
    val characters = currentValueCharacters()
    val value = vector?.let { formatEditorSignal(it, radix, characters) } ?: bit.display(characters)
    val label = strings(signalStripName(component))
    val interactive = state.activeInstancePath.isNotEmpty() || state.activeCircuitName == state.project.mainCircuitName
    when {
        component.kind == ComponentKind.BUTTON -> SignalButtonHold(state, component, label)
        component.kind == ComponentKind.INPUT && interactive -> {
            val description = if (component.width == 1) strings(Messages.A11yToggleInput, "name" to label, "value" to value)
                else strings("a11y_edit_input_value", "name" to label, "value" to value)
            OutlinedButton(onClick = {
                if (component.width == 1) state.activateComponent(component.id) else onEditInput(component.id)
            }, shape = RoundedCornerShape(1.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("signal-input-${component.id}")
                    .semantics { contentDescription = description }) {
                Text("$label $value", Modifier.clearAndSetSemantics {}, fontSize = 12.sp, color = signalTextColor(vector, bit))
            }
        }
        else -> {
            val reveal = strings("a11y_signal_reveal")
            Text(strings(Messages.SimulationSignalValue, "name" to label, "value" to value), color = signalTextColor(vector, bit),
                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = reveal) {
                    state.select(component.id)
                    state.fitSelected()
                }.background(ChromeColors.Surface).border(1.dp, ChromeColors.Divider).padding(horizontal = 10.dp, vertical = 15.dp)
                    .testTag("signal-value-${component.id}")
                    .semantics { contentDescription = strings(Messages.A11ySignal, "name" to label, "value" to value) })
        }
    }
}

/**
 * The colour of a value in the strip: high in green, unknown and error bits in the canvas palette's colours for the
 * active theme and contrast preference, everything else in the text colour.
 */
@Composable
private fun signalTextColor(vector: LogicVector?, bit: LogicValue): Color {
    val dark = LocalEditorDarkTheme.current
    val preferences = LocalEditorPreferences.current?.preferences
    val colors = preferences?.effectiveColors(dark) ?: if (dark) DarkEditorColors else emptyMap()
    val palette = if (preferences?.highContrastSignals == true) CircuitSignalPalette.HighContrast else CircuitSignalPalette.Classic
    return when {
        vector?.errorMask?.let { it != 0uL } == true || bit == LogicValue.ERROR -> colors.color(EditorColor.SIGNAL_ERROR, palette.error)
        vector?.unknownMask?.let { it != 0uL } == true || vector == null && bit == LogicValue.UNKNOWN ->
            colors.color(EditorColor.SIGNAL_UNKNOWN, palette.unknown)
        (vector?.width ?: 1) == 1 && bit == LogicValue.HIGH -> if (dark) Color(0xFF66CC66) else Color(0xFF008000)
        else -> ChromeColors.Ink
    }
}

/**
 * A Button in the strip, pressed while a finger holds it as the canvas and the inspector's MomentaryControl do: the
 * press and the release go through the simulation's accepted-input queue, and the release is sent whatever ends the
 * hold (lifting, a cancelled gesture, the window losing focus or the strip leaving the screen). TalkBack's activation
 * presses and releases it.
 */
@Composable
private fun SignalButtonHold(state: EditorState, component: CircuitComponent, label: String) {
    val strings = rememberLocalizer()
    val pressed = (state.simulation.componentStates[component.id] as? IoButtonState)?.pressed == true
    val currentState by rememberUpdatedState(state)
    var held by remember(component.id) { mutableStateOf(false) }
    // A simulation that takes no held input (a live JAR view) still toggles the Button once, on release.
    var clickOnRelease by remember(component.id) { mutableStateOf(false) }
    fun press(down: Boolean, cancelled: Boolean = false) {
        if (held == down) return
        held = down
        when {
            down -> clickOnRelease = !currentState.sendComponentEvent(component.id, IoInputEvent.Press(true))
            clickOnRelease -> { clickOnRelease = false; if (!cancelled) currentState.activateComponent(component.id) }
            else -> currentState.sendComponentEvent(component.id, IoInputEvent.Press(false))
        }
    }
    DisposableEffect(component.id) { onDispose { if (held) press(false, cancelled = true) } }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused && held) press(false, cancelled = true) }
    val description = strings(Messages.IoA11yButton, "label" to label,
        "state" to message(if (pressed) Messages.IoButtonPressed else Messages.IoButtonReleased))
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .background(if (pressed || held) ChromeColors.Selected else ChromeColors.Surface, RoundedCornerShape(1.dp))
        .border(1.dp, ChromeColors.Divider, RoundedCornerShape(1.dp))
        .testTag("signal-input-${component.id}")
        .semantics {
            role = Role.Button
            contentDescription = description
            onClick {
                if (!held) {
                    press(true)
                    press(false)
                }
                true
            }
        }
        .pointerInput(component.id) {
            detectTapGestures(onPress = {
                press(true)
                try { tryAwaitRelease() } finally { press(false) }
            })
        }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, Modifier.clearAndSetSemantics {}, fontSize = 12.sp, color = ChromeColors.Ink)
    }
}
