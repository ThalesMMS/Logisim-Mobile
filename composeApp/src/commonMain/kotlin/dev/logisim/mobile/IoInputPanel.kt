package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.IoButtonState
import dev.logisim.mobile.core.IoDipState
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoJoystickState
import dev.logisim.mobile.core.IoKeyboardState
import dev.logisim.mobile.core.IoKeypadState
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.KeyboardCommand
import kotlin.math.roundToInt
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.localization.rememberLocalizer

/** Controls dispatch events only. They never write simulation state or paint-time data. */
@Composable
internal fun IoInputPanel(
    component: CircuitComponent,
    state: ComponentState?,
    onEvent: (IoInputEvent) -> Unit,
    simulationStatus: SimulationRunStatus,
    isEventPending: (ComponentEvent) -> Boolean,
    presentation: InspectorPresentation,
    keyboardSession: KeyboardInspectorSession?,
    currentKeyboardSession: () -> KeyboardInspectorSession?,
    otherInspectorFocus: () -> InspectorFocusState,
    modifier: Modifier = Modifier,
) {
    val strings = rememberLocalizer()
    val label = component.label.ifBlank { strings(when (component.kind) {
        IoKinds.DipSwitch -> message(Messages.IoControlDipSwitch)
        IoKinds.Joystick -> message(Messages.IoControlJoystick)
        IoKinds.Keyboard -> message(Messages.IoControlKeyboard)
        IoKinds.MatrixKeypad -> message(Messages.IoControlMatrixKeypad)
        else -> component.kind.localizedLabel()
    }) }
    // The panel is height-limited in the inspector; a taller control (the joystick's pad and sliders) scrolls.
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (component.kind) {
            IoKinds.Button -> MomentaryControl(label, (state as? IoButtonState)?.pressed == true,
                { onEvent(IoInputEvent.Press(it)) })
            IoKinds.DipSwitch -> {
                val count = component.attributeInt("number", 8).coerceIn(1, 32)
                val positions = (state as? IoDipState)?.positions ?: 0uL
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(count) { index ->
                        val on = positions and (1uL shl index) != 0uL
                        OutlinedButton(onClick = { onEvent(IoInputEvent.ToggleSwitch(index)) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = strings(Messages.IoA11ySwitch, "label" to label, "number" to (index + 1), "state" to message(if (on) Messages.IoSwitchOn else Messages.IoSwitchOff))
                            }) { Text("${index + 1}\n${if (on) "1" else "0"}") }
                    }
                }
            }
            IoKinds.Joystick -> {
                val value = state as? IoJoystickState ?: IoJoystickState()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(132.dp).border(1.dp, ChromeColors.Muted).semantics {
                    contentDescription = strings(Messages.IoA11yJoystick, "label" to label, "x" to value.x, "y" to value.y)
                }.pointerInput(component.id) {
                    fun move(at: Offset) {
                        val x = ((at.x / size.width - 0.5f) * 28).roundToInt().coerceIn(-14, 14)
                        val y = ((at.y / size.height - 0.5f) * 28).roundToInt().coerceIn(-14, 14)
                        onEvent(IoInputEvent.MoveJoystick(x, y))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        move(down.position)
                        try {
                            var pressed = true
                            while (pressed) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                pressed = change.pressed
                                if (pressed) {
                                    move(change.position)
                                    change.consume()
                                }
                            }
                        } finally {
                            onEvent(IoInputEvent.Cancel)
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                    val ink = ChromeColors.Ink
                    Canvas(Modifier.size(120.dp)) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val end = center + Offset(value.x / 14f * center.x * 0.8f, value.y / 14f * center.y * 0.8f)
                        drawLine(ink, center, end, 4f)
                        drawCircle(Color.Red, 10f, end)
                    }
                }
                // A 3 × 3 pad with the diagonals. Like the pad and Evolution's joystick poker, a button holds its
                // direction while pressed and recenters on release (X5).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JOYSTICK_DIRECTIONS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { direction ->
                                JoystickDirectionButton(direction, strings(direction.label), onEvent)
                            }
                        }
                    }
                }
                }
                // Exact positions that stay, for intermediate values and for TalkBack (adjustable X and Y).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JoystickAxisSlider(strings("io_joystick_axis_x"), value.x, Modifier.weight(1f)) { onEvent(IoInputEvent.MoveJoystick(it, value.y)) }
                    JoystickAxisSlider(strings("io_joystick_axis_y"), value.y, Modifier.weight(1f)) { onEvent(IoInputEvent.MoveJoystick(value.x, it)) }
                }
                Text(strings("io_joystick_help"), style = MaterialTheme.typography.bodySmall)
            }
            IoKinds.Keyboard -> {
                val keyboard = state as? IoKeyboardState ?: IoKeyboardState()
                val capacity = component.attributeInt("buflen", 32).coerceIn(1, 256)
                val session = checkNotNull(keyboardSession)
                val host = rememberKeyboardHost(session, presentation)
                val fieldFocus = remember(host) { FocusRequester() }
                val visibility = remember(host) { BringIntoViewRequester() }
                val focusRequest = session.focus.focusRequest?.takeIf { it.presentation == presentation }
                LaunchedEffect(session, focusRequest) {
                    if (focusRequest != null) {
                        withFrameNanos { }
                        if (!session.isCurrent(host, currentKeyboardSession()) || session.focus.focusRequest != focusRequest) return@LaunchedEffect
                        fieldFocus.requestFocus()
                        visibility.bringIntoView()
                        session.focus.completeFocus(focusRequest)
                    }
                }
                val value = session.draft.value(keyboard, simulationStatus.pendingSteps, isEventPending)
                OutlinedTextField(value = value,
                    onValueChange = { session.edit(host, currentKeyboardSession(), it) },
                    label = { Text(strings(Messages.IoKeyboardCapacity, "label" to label, "count" to value.text.length, "capacity" to capacity)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).bringIntoViewRequester(visibility)
                        .onFocusChanged { session.focusChanged(host, currentKeyboardSession(), it.isFocused, otherInspectorFocus()) },
                    minLines = 2, maxLines = 4)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { session.send(host, currentKeyboardSession(), IoInputEvent.EditKeyboard(KeyboardCommand.HOME)) }) { Text(strings(Messages.IoHome)) }
                    OutlinedButton(onClick = { session.send(host, currentKeyboardSession(), IoInputEvent.EditKeyboard(KeyboardCommand.END)) }) { Text(strings(Messages.IoEnd)) }
                    OutlinedButton(onClick = { session.send(host, currentKeyboardSession(), IoInputEvent.InsertText("\b")) }) { Text(strings(Messages.IoAsciiBackspace)) }
                    OutlinedButton(onClick = { session.send(host, currentKeyboardSession(), IoInputEvent.InsertText("\u000c")) }) { Text(strings(Messages.IoAsciiFormFeed)) }
                }
            }
            IoKinds.MatrixKeypad -> {
                val keypad = state as? IoKeypadState ?: IoKeypadState()
                val labels = keypadLabels(component)
                repeat(4) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { column ->
                            val index = row * 4 + column
                            MomentaryControl(labels[index], index in keypad.pressed,
                                { onEvent(IoInputEvent.KeypadKey(row, column, it)) })
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun MomentaryControl(label: String, pressed: Boolean, setPressed: (Boolean) -> Unit) {
    val strings = rememberLocalizer()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val dispatch by rememberUpdatedState(setPressed)
    var focused by remember { mutableStateOf(false) }
    var pointerHeld by remember { mutableStateOf(false) }
    var heldKey by remember { mutableStateOf<Key?>(null) }

    fun updateHold(pointer: Boolean = pointerHeld, key: Key? = heldKey) {
        val wasHeld = pointerHeld || heldKey != null
        pointerHeld = pointer
        heldKey = key
        val isHeld = pointer || key != null
        if (wasHeld != isHeld) dispatch(isHeld)
    }

    DisposableEffect(Unit) {
        onDispose { updateHold(pointer = false, key = null) }
    }
    LaunchedEffect(windowFocused) {
        if (!windowFocused) updateHold(pointer = false, key = null)
    }
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .background(if (pressed) ChromeColors.Selected else ChromeColors.Heading)
        .border(if (focused) 2.dp else 1.dp, if (focused) ChromeColors.Accent else ChromeColors.Muted)
        .semantics {
            role = Role.Button
            contentDescription = strings(Messages.IoA11yButton, "label" to label, "state" to message(if (pressed) Messages.IoButtonPressed else Messages.IoButtonReleased))
            onClick {
                if (!pointerHeld && heldKey == null) {
                    dispatch(true)
                    dispatch(false)
                }
                true
            }
        }
        .onFocusChanged {
            focused = it.isFocused
            if (!focused) updateHold(key = null)
        }
        .onKeyEvent { event ->
            if (event.key != Key.Enter && event.key != Key.NumPadEnter && event.key != Key.Spacebar) {
                false
            } else when (event.type) {
                KeyEventType.KeyDown -> {
                    if (heldKey == null) updateHold(key = event.key)
                    true
                }
                KeyEventType.KeyUp -> {
                    if (heldKey == event.key) updateHold(key = null)
                    true
                }
                else -> false
            }
        }
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                updateHold(pointer = true)
                try { tryAwaitRelease() } finally { updateHold(pointer = false) }
            })
        }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(label, color = ChromeColors.Ink) }
}

/** A joystick pad button: its deflection (Evolution's ±14) and its localized name. */
internal class JoystickDirection(val x: Int, val y: Int, val glyph: String, val label: String)

internal val JOYSTICK_DIRECTIONS = listOf(
    JoystickDirection(-14, -14, "↖", "io_joystick_up_left"), JoystickDirection(0, -14, "↑", "io_joystick_up"),
    JoystickDirection(14, -14, "↗", "io_joystick_up_right"), JoystickDirection(-14, 0, "←", "io_joystick_left"),
    JoystickDirection(0, 0, "●", Messages.IoCenter), JoystickDirection(14, 0, "→", "io_joystick_right"),
    JoystickDirection(-14, 14, "↙", "io_joystick_down_left"), JoystickDirection(0, 14, "↓", "io_joystick_down"),
    JoystickDirection(14, 14, "↘", "io_joystick_down_right"),
)

/**
 * Pressing moves the stick and releasing recenters it, like dragging the pad. TalkBack's double tap cannot hold, so
 * its click sets the direction until another one or Center; the X and Y sliders set exact values.
 */
@Composable
private fun JoystickDirectionButton(direction: JoystickDirection, label: String, onEvent: (IoInputEvent) -> Unit) {
    val dispatch by rememberUpdatedState(onEvent)
    var pressed by remember { mutableStateOf(false) }
    Box(Modifier.size(56.dp).background(if (pressed) ChromeColors.Selected else ChromeColors.Heading)
        .border(1.dp, ChromeColors.Muted)
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick {
                dispatch(IoInputEvent.MoveJoystick(direction.x, direction.y))
                true
            }
        }
        .pointerInput(direction) {
            detectTapGestures(onPress = {
                pressed = true
                dispatch(IoInputEvent.MoveJoystick(direction.x, direction.y))
                try { tryAwaitRelease() } finally {
                    pressed = false
                    dispatch(IoInputEvent.Cancel)
                }
            })
        }, contentAlignment = Alignment.Center) {
        Text(direction.glyph, color = ChromeColors.Ink, style = MaterialTheme.typography.titleLarge)
    }
}

/** One joystick axis, -14..14 in whole steps; its value is spoken and shown. */
@Composable
private fun JoystickAxisSlider(label: String, value: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    val strings = rememberLocalizer()
    Column(modifier) {
        // The slider announces the axis and its value; the label is not read a second time.
        Text(strings("io_joystick_axis_value", "axis" to label, "value" to value), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { })
        Slider(value.toFloat(), { next -> next.roundToInt().coerceIn(-14, 14).takeIf { it != value }?.let(onValue) },
            valueRange = -14f..14f, steps = 27, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = value.toString()
            })
    }
}

internal fun keypadLabels(component: CircuitComponent): List<String> {
    val pmod = component.attributes["mode"] == "pmod_kypd"
    if (pmod) return listOf("1", "2", "3", "A", "4", "5", "6", "B", "7", "8", "9", "C", "0", "F", "E", "D")
    return listOf("1", "2", "3", component.attributes["keyALabel"] ?: "A", "4", "5", "6", component.attributes["keyBLabel"] ?: "B",
        "7", "8", "9", component.attributes["keyCLabel"] ?: "C", component.attributes["keyStarLabel"] ?: "*", "0",
        component.attributes["keyHashLabel"] ?: "#", component.attributes["keyDLabel"] ?: "D")
}
