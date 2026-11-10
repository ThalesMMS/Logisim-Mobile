package dev.logisim.mobile

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.Messages
import kotlin.time.TimeSource

enum class EditorShortcut(
    val messageKey: String,
    val defaultBinding: EditorShortcutBinding,
    val requiresWritableDocument: Boolean = false,
    internal val canvasCommand: CanvasCommand? = null,
    internal val facing: Direction? = null,
    /** Evolution's KeyboardToolSelection index among the selectable toolbar items. */
    internal val toolIndex: Int? = null,
    /** Evolution's AddTool/SelectTool keys, handled by [EditorState.handleToolShortcut]. */
    internal val toolKey: Boolean = false,
) {
    NEW(Messages.ActionNew, EditorShortcutBinding("N")),
    OPEN(Messages.ActionOpen, EditorShortcutBinding("O")),
    SAVE(Messages.ActionSave, EditorShortcutBinding("S"), true),
    SAVE_AS(Messages.ActionSaveAs, EditorShortcutBinding("S", shift = true)),
    RUN(Messages.ActionRun, EditorShortcutBinding("E"), true),
    STEP_EVENT(Messages.SimulationEventStep, EditorShortcutBinding("I"), true),
    STEP_CLOCK(Messages.ActionStep, EditorShortcutBinding("T"), true),
    STEP_CLOCK_CYCLE(Messages.MenuTickCycle, EditorShortcutBinding("F9"), true),
    AUTOMATIC_TICKS(Messages.SimulationAutomaticTicks, EditorShortcutBinding("K"), true),
    RESET(Messages.ActionReset, EditorShortcutBinding("R"), true),
    FIND_ACTION(Messages.MenuFindAction, EditorShortcutBinding("A", shift = true)),
    SELECT_ALL(Messages.ActionSelectAll, EditorShortcutBinding("A"), canvasCommand = CanvasCommand.SELECT_ALL),
    COPY(Messages.ActionCopy, EditorShortcutBinding("C"), canvasCommand = CanvasCommand.COPY),
    CUT(Messages.ActionCut, EditorShortcutBinding("X"), canvasCommand = CanvasCommand.CUT),
    PASTE(Messages.ActionPaste, EditorShortcutBinding("V"), canvasCommand = CanvasCommand.PASTE),
    DUPLICATE(Messages.ActionDuplicate, EditorShortcutBinding("D"), canvasCommand = CanvasCommand.DUPLICATE),
    UNDO(Messages.ActionUndo, EditorShortcutBinding("Z"), canvasCommand = CanvasCommand.UNDO),
    REDO(Messages.ActionRedo, EditorShortcutBinding("Z", shift = true), canvasCommand = CanvasCommand.REDO),
    FIT_SELECTION(Messages.ActionFitSelection, EditorShortcutBinding("F"), canvasCommand = CanvasCommand.FIT_SELECTION),
    FACE_NORTH(Messages.DirectionNorth, EditorShortcutBinding("↑", primary = false), facing = Direction.NORTH),
    FACE_SOUTH(Messages.DirectionSouth, EditorShortcutBinding("↓", primary = false), facing = Direction.SOUTH),
    FACE_EAST(Messages.DirectionEast, EditorShortcutBinding("→", primary = false), facing = Direction.EAST),
    FACE_WEST(Messages.DirectionWest, EditorShortcutBinding("←", primary = false), facing = Direction.WEST),
    PLACEMENT_AUTO_LABEL(Messages.PlacementAutoLabel, EditorShortcutBinding("L", primary = false)),
    FILE_EXPORT(Messages.ShortcutFileExport, EditorShortcutBinding("E", shift = true)),
    FILE_PRINT(Messages.ShortcutFilePrint, EditorShortcutBinding("P")),
    WINDOW_CLOSE(Messages.ShortcutWindowClose, EditorShortcutBinding("W", shift = true)),
    TOOL_SELECT_1(Messages.ShortcutToolSelect, EditorShortcutBinding("1"), toolIndex = 0),
    TOOL_SELECT_2(Messages.ShortcutToolSelect, EditorShortcutBinding("2"), toolIndex = 1),
    TOOL_SELECT_3(Messages.ShortcutToolSelect, EditorShortcutBinding("3"), toolIndex = 2),
    TOOL_SELECT_4(Messages.ShortcutToolSelect, EditorShortcutBinding("4"), toolIndex = 3),
    TOOL_SELECT_5(Messages.ShortcutToolSelect, EditorShortcutBinding("5"), toolIndex = 4),
    TOOL_SELECT_6(Messages.ShortcutToolSelect, EditorShortcutBinding("6"), toolIndex = 5),
    TOOL_SELECT_7(Messages.ShortcutToolSelect, EditorShortcutBinding("7"), toolIndex = 6),
    TOOL_SELECT_8(Messages.ShortcutToolSelect, EditorShortcutBinding("8"), toolIndex = 7),
    TOOL_SELECT_9(Messages.ShortcutToolSelect, EditorShortcutBinding("9"), toolIndex = 8),
    TOOL_SELECT_10(Messages.ShortcutToolSelect, EditorShortcutBinding("0"), toolIndex = 9),
    TOOL_SELECT_11(Messages.ShortcutToolSelect, EditorShortcutBinding.Unassigned, toolIndex = 10),
    TOOL_SELECT_12(Messages.ShortcutToolSelect, EditorShortcutBinding.Unassigned, toolIndex = 11),
    TOOL_SELECT_13(Messages.ShortcutToolSelect, EditorShortcutBinding.Unassigned, toolIndex = 12),
    TOOL_SELECT_14(Messages.ShortcutToolSelect, EditorShortcutBinding.Unassigned, toolIndex = 13),
    EDIT_TOOL_DUPLICATE(Messages.ShortcutEditToolDuplicate, EditorShortcutBinding("Insert", primary = false),
        canvasCommand = CanvasCommand.DUPLICATE),
    PROJ_MOVE_UP(Messages.ShortcutProjMoveUp, EditorShortcutBinding("U", shift = true), true),
    PROJ_MOVE_DOWN(Messages.ShortcutProjMoveDown, EditorShortcutBinding("D", shift = true), true),
    AUTO_LABEL_TOGGLE(Messages.ShortcutAutoLabelToggle, EditorShortcutBinding("T", primary = false), toolKey = true),
    AUTO_LABEL_VIEW(Messages.ShortcutAutoLabelView, EditorShortcutBinding("V", primary = false), toolKey = true),
    AUTO_LABEL_HIDE(Messages.ShortcutAutoLabelHide, EditorShortcutBinding("H", primary = false), toolKey = true),
    AUTO_LABEL_SELF_NUMBERED_STOP(Messages.ShortcutAutoLabelSelfNumberedStop, EditorShortcutBinding("A", primary = false), toolKey = true),
    ADD_TOOL_ROTATE(Messages.ShortcutAddToolRotate, EditorShortcutBinding("R", primary = false), toolKey = true),
    GATE_MODIFIER_SIZE_SMALL(Messages.ShortcutGateSizeSmall, EditorShortcutBinding("S", primary = false), toolKey = true),
    GATE_MODIFIER_SIZE_MEDIUM(Messages.ShortcutGateSizeMedium, EditorShortcutBinding("M", primary = false), toolKey = true),
    GATE_MODIFIER_SIZE_WIDE(Messages.ShortcutGateSizeWide, EditorShortcutBinding("W", primary = false), toolKey = true),
    GATE_MODIFIER_INPUT_ADD(Messages.ShortcutGateInputAdd, EditorShortcutBinding("=", primary = false), toolKey = true),
    GATE_MODIFIER_INPUT_SUB(Messages.ShortcutGateInputSub, EditorShortcutBinding("-", primary = false), toolKey = true);

    internal val messageArguments: Array<Pair<String, String>>
        get() = toolIndex?.let { arrayOf("number" to (it + 1).toString()) } ?: emptyArray()

    internal val defaultAliases: List<EditorShortcutBinding>
        get() = when (this) {
            REDO -> listOf(EditorShortcutBinding("Y"), EditorShortcutBinding("Y", shift = true))
            // Ctrl+Shift+D belongs to Evolution's Move circuit down, so Duplicate keeps no Shift alias.
            COPY, CUT, PASTE, FIT_SELECTION -> listOf(defaultBinding.copy(shift = true))
            GATE_MODIFIER_SIZE_SMALL -> listOf(EditorShortcutBinding("N", primary = false))
            GATE_MODIFIER_INPUT_ADD -> listOf(EditorShortcutBinding("+", primary = false), EditorShortcutBinding("Num+", primary = false))
            GATE_MODIFIER_INPUT_SUB -> listOf(EditorShortcutBinding("Num-", primary = false))
            else -> emptyList()
        }
}

data class EditorShortcutBinding(val key: String, val shift: Boolean = false, val primary: Boolean = true) {
    init {
        require(key.isEmpty() && !shift || key in shortcutKeys.values || !primary && key in plainOnlyShortcutKeys.values)
    }

    /** No chord: Evolution leaves toolbar tools 11–14 unbound until the user assigns one. */
    val isUnassigned: Boolean get() = key.isEmpty()

    private fun displayKey(): String = when {
        isUnassigned -> UNASSIGNED
        shift -> "Shift+$key"
        else -> key
    }

    internal fun serialized(): String = if (primary) displayKey() else "Plain+${displayKey()}"

    internal fun label(
        scheme: EditorShortcutScheme,
        platform: ShortcutPlatform = currentShortcutPlatform,
    ): String {
        if (isUnassigned) return "—"
        if (!primary) return displayKey()
        val modifier = if (scheme == EditorShortcutScheme.PLATFORM && platform == ShortcutPlatform.IOS) "⌘" else "Ctrl"
        return "$modifier+${displayKey()}"
    }

    internal companion object {
        private const val UNASSIGNED = "None"
        val Unassigned = EditorShortcutBinding("")

        fun parse(value: String): EditorShortcutBinding? {
            val keyAndShift = value.removePrefix("Plain+")
            val primary = keyAndShift == value
            if (keyAndShift == UNASSIGNED) return EditorShortcutBinding("", primary = primary)
            val typed = keyAndShift.removePrefix("Shift+")
            val candidates = if (primary) shortcutKeys.values else shortcutKeys.values + plainOnlyShortcutKeys.values
            val key = candidates.firstOrNull { it == typed } ?: candidates.firstOrNull { it.equals(typed, ignoreCase = true) }
            return key?.let { EditorShortcutBinding(it, typed != keyAndShift, primary) }
        }
    }
}

private val directionShortcutKeys = mapOf(
    Key.DirectionUp to "↑", Key.DirectionDown to "↓", Key.DirectionRight to "→", Key.DirectionLeft to "←",
)

/** Keys that only canvas tool shortcuts use without Ctrl/⌘, as Evolution's Insert and gate input keys. */
private val plainOnlyShortcutKeys = directionShortcutKeys + mapOf(
    Key.Insert to "Insert", Key.Equals to "=", Key.Minus to "-", Key.Plus to "+",
    Key.NumPadAdd to "Num+", Key.NumPadSubtract to "Num-",
)

private val shortcutKeys = mapOf(
    Key.A to "A", Key.B to "B", Key.C to "C", Key.D to "D", Key.E to "E", Key.F to "F",
    Key.G to "G", Key.H to "H", Key.I to "I", Key.J to "J", Key.K to "K", Key.L to "L",
    Key.M to "M", Key.N to "N", Key.O to "O", Key.P to "P", Key.Q to "Q", Key.R to "R",
    Key.S to "S", Key.T to "T", Key.U to "U", Key.V to "V", Key.W to "W", Key.X to "X",
    Key.Y to "Y", Key.Z to "Z",
    Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
    Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
    Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4", Key.F5 to "F5", Key.F6 to "F6",
    Key.F7 to "F7", Key.F8 to "F8", Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12",
)

internal fun effectiveEditorShortcut(
    command: EditorShortcut,
    overrides: Map<EditorShortcut, EditorShortcutBinding>,
): EditorShortcutBinding = overrides[command] ?: command.defaultBinding

internal fun editorShortcutFor(
    binding: EditorShortcutBinding,
    overrides: Map<EditorShortcut, EditorShortcutBinding>,
    excluding: EditorShortcut? = null,
): EditorShortcut? = if (binding.isUnassigned) null else EditorShortcut.entries.firstOrNull { command ->
    command != excluding && (effectiveEditorShortcut(command, overrides) == binding ||
        (command !in overrides && binding in command.defaultAliases))
}

internal fun editorShortcutConflict(
    command: EditorShortcut,
    binding: EditorShortcutBinding,
    overrides: Map<EditorShortcut, EditorShortcutBinding>,
): EditorShortcut? {
    val conflict = editorShortcutFor(binding, overrides, excluding = command)
    if (conflict != null || binding != command.defaultBinding) return conflict
    return command.defaultAliases.firstNotNullOfOrNull { editorShortcutFor(it, overrides, excluding = command) }
}

internal fun EditorShortcutBinding.reservedCanvasAction(): String? = when {
    primary -> null
    key == "0" -> Messages.ActionFit
    key == "X" && !shift -> Messages.MatrixPlaceTitle
    else -> null
}

internal fun editorShortcutBinding(
    key: Key, shortcut: Boolean, shift: Boolean, alt: Boolean,
): EditorShortcutBinding? =
    if (!shortcut || alt) null else shortcutKeys[key]?.let { EditorShortcutBinding(it, shift) }

internal fun KeyEvent.editorShortcutBinding(scheme: EditorShortcutScheme, allowPlain: Boolean = false): EditorShortcutBinding? {
    val primary = isPrimaryShortcutModifier(scheme, isCtrlPressed, isMetaPressed)
    if (primary) return editorShortcutBinding(key, true, isShiftPressed, isAltPressed)
    if (!allowPlain || isAltPressed || isCtrlPressed || isMetaPressed) return null
    val plainKey = shortcutKeys[key] ?: plainOnlyShortcutKeys[key] ?: return null
    return EditorShortcutBinding(plainKey, isShiftPressed, primary = false)
}

/** Keys that a text field cannot capture as typed text, such as arrows and Insert. */
internal fun KeyEvent.isPlainOnlyShortcutKey(): Boolean = key in plainOnlyShortcutKeys

internal class EditorKeyPresses(
    private val overrides: Map<EditorShortcut, EditorShortcutBinding> = emptyMap(),
) {
    private val handledKeys = mutableSetOf<Key>()

    fun handle(
        key: Key,
        type: KeyEventType,
        shortcut: Boolean,
        shift: Boolean,
        alt: Boolean,
        enabled: Boolean,
        dispatch: (EditorShortcut) -> Boolean,
    ): Boolean {
        if (type == KeyEventType.KeyUp) return handledKeys.remove(key)
        if (type != KeyEventType.KeyDown || !enabled || !shortcut || alt) return false
        if (key in handledKeys) return true
        val binding = editorShortcutBinding(key, shortcut, shift, alt) ?: return false
        val command = editorShortcutFor(binding, overrides) ?: return false
        if (command.canvasCommand != null) return false
        if (!dispatch(command)) return false
        handledKeys += key
        return true
    }

    fun clear() = handledKeys.clear()
}

/**
 * Evolution's DoubleShiftTrigger: two clean Shift taps within [windowMillis] open Find Action.
 * Shift held with another key or modifier is a chord, not a tap, and disarms the gesture.
 */
internal class DoubleShiftDetector(private val windowMillis: Long = 300) {
    private var shiftDown = false
    private var tainted = false
    private var armed = false
    private var lastTap = 0L

    fun observe(key: Key, type: KeyEventType, otherModifier: Boolean, nowMillis: Long): Boolean {
        if (key != Key.ShiftLeft && key != Key.ShiftRight) {
            if (type == KeyEventType.KeyDown) { tainted = true; armed = false }
            return false
        }
        when (type) {
            KeyEventType.KeyDown -> {
                if (!shiftDown) { shiftDown = true; tainted = false }
                if (otherModifier) tainted = true
            }
            KeyEventType.KeyUp -> {
                val clean = shiftDown && !tainted && !otherModifier
                shiftDown = false
                tainted = false
                if (!clean) { armed = false; return false }
                if (armed && nowMillis - lastTap <= windowMillis) { armed = false; return true }
                armed = true
                lastTap = nowMillis
            }
        }
        return false
    }

    /** A pointer press means Shift modified the mouse, as in Evolution. */
    fun taint() { tainted = true; armed = false }
}

@Composable
internal fun Modifier.editorKeyboardShortcuts(
    scheme: EditorShortcutScheme,
    overrides: Map<EditorShortcut, EditorShortcutBinding>,
    enabled: () -> Boolean,
    onDoubleShift: (() -> Boolean)? = null,
    dispatch: (EditorShortcut) -> Boolean,
): Modifier {
    val focus = remember { FocusRequester() }
    val presses = remember(overrides) { EditorKeyPresses(overrides) }
    val doubleShift = remember { DoubleShiftDetector() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    val window = LocalWindowInfo.current
    // A soft keyboard consumes typed characters and passes only Shift on to the window, so a
    // capitalised word typed from a hardware or emulator keyboard reads as bare Shift taps.
    val imeVisible by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    LaunchedEffect(focus) { focus.requestFocus() }
    LaunchedEffect(window.isWindowFocused) {
        if (!window.isWindowFocused) presses.clear()
    }
    return focusRequester(focus)
        .onFocusChanged { if (!it.hasFocus) presses.clear() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) doubleShift.taint()
                }
            }
        }
        .onPreviewKeyEvent { event ->
            // Observe only, as Evolution does, so Shift keeps working as a modifier everywhere.
            if (imeVisible) { doubleShift.taint(); return@onPreviewKeyEvent false }
            val fired = doubleShift.observe(event.key, event.type,
                event.isCtrlPressed || event.isMetaPressed || event.isAltPressed,
                clock.elapsedNow().inWholeMilliseconds)
            if (fired && onDoubleShift != null && window.isWindowFocused && enabled()) onDoubleShift()
            false
        }
        .onKeyEvent { event ->
            // A focused child handles editing shortcuts first. Global commands also
            // stay inactive while a text editor or modal sheet owns the input.
            presses.handle(event.key, event.type,
                shortcut = isPrimaryShortcutModifier(scheme, event.isCtrlPressed, event.isMetaPressed),
                shift = event.isShiftPressed, alt = event.isAltPressed,
                enabled = window.isWindowFocused && enabled(), dispatch = dispatch)
        }
        .focusable()
}
