package dev.logisim.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.Localizer

/**
 * Whether the touch selection bar shows: something is selected in a selecting tool (Edit, Select, Move) of a
 * writable, live document, with no paste or duplicate waiting for its destination.
 */
internal val EditorState.showsSelectionBar: Boolean
    get() = (selectedIds.isNotEmpty() || selectedWires.isNotEmpty()) && pendingPaste == null && documentWritable &&
        interactionsEnabled && !isDebuggingInstance && (mode in AdditiveSelectionModes || showsPokeMenuBar)

/**
 * Pointer inputs a finger holds down in the Poke tool: the canvas sends their press on touch-down and a release on
 * lift ([IoInteractions.press] gives them a release), so a long press on them is a real press and never opens the
 * Menu Tool popup (C40). These are the [PointerIoKinds] whose press has a release: Button, Joystick and the Matrix
 * Keypad's keys; a DIP switch toggles on a tap and keeps its long-press menu.
 */
internal val PokeHeldKinds = setOf(IoKinds.Button, IoKinds.Joystick, IoKinds.MatrixKeypad)

/**
 * In the Poke tool, a pressed Button, Joystick or Matrix Keypad (which the press selects) offers its menu items in
 * the bar, as a long press offers them on every other component (C40).
 */
internal val EditorState.showsPokeMenuBar: Boolean
    get() = mode == EditorMode.INTERACT && selectedWires.isEmpty() && selectedIds.size == 1 && selected?.kind in PokeHeldKinds

/**
 * Mobile addition: the commands Evolution reaches with keys (Ctrl+Space or the arrows to turn, Delete, Ctrl+D,
 * Ctrl+C) and the Menu Tool, one tap away for a finger. Rotate turns every selected component with a facing
 * ([EditorState.rotateSelected]); Add is touch's sticky Shift. [onProperties] is null where the inspector is docked
 * or the command toolbar already shows Properties. Six buttons, or seven with Properties, fit a 360 dp phone
 * ([EditorSelectionBar]), their captions shrunk or ellipsized but never broken inside a word ([fitSelectionCaption]).
 */
internal fun selectionBarActions(state: EditorState, strings: Localizer, onProperties: (() -> Unit)?): List<EditorToolbarAction> {
    val turnable = state.canRotateSelection
    // The Poke tool's bar holds the Menu Tool items of a held pointer input: Rotate, Delete and Show Attributes.
    val menuOnly = state.showsPokeMenuBar
    return listOfNotNull(
        EditorToolbarAction("selection-rotate-left", strings(Messages.ComponentMenuRotateLeft), enabled = turnable,
            icon = EditorIcon.ROTATE_LEFT) { state.rotateSelected(left = true) },
        EditorToolbarAction("selection-rotate-right", strings(Messages.ComponentMenuRotateRight), enabled = turnable,
            icon = EditorIcon.ROTATE_RIGHT) { state.rotateSelected(left = false) },
        if (menuOnly) null else EditorToolbarAction("selection-duplicate", strings(Messages.ActionDuplicate),
            strings(Messages.A11yDuplicateSelection), icon = EditorIcon.DUPLICATE, onClick = state::duplicateSelected),
        if (menuOnly) null else EditorToolbarAction("selection-copy", strings(Messages.ActionCopy), strings(Messages.A11yCopySelection),
            icon = EditorIcon.COPY, onClick = state::copySelected),
        EditorToolbarAction("selection-delete", strings(Messages.ActionDelete), strings(Messages.A11yDeleteSelection),
            icon = EditorIcon.DELETE, onClick = state::removeSelected),
        if (menuOnly) null else EditorToolbarAction("selection-add", strings(Messages.SelectionAddShort),
            strings(Messages.SelectionAddToggle), selected = state.additiveSelection, icon = EditorIcon.ADD_SELECTION,
            onClick = state::toggleAdditiveSelection),
        onProperties?.let {
            EditorToolbarAction("selection-properties", strings(Messages.ActionProperties), strings(Messages.A11yOpenProperties),
                icon = EditorIcon.PROPERTIES, onClick = it)
        },
    )
}

/** Horizontal space around the floating selection bar, inside the canvas. */
internal val SelectionBarMargin = 4.dp
private val SelectionBarGap = 2.dp
private val SelectionBarPadding = 2.dp
private val SelectionBarMinCell = ChromeTouchTarget
private val SelectionBarMaxCell = 76.dp

/**
 * The selection actions as icon and caption buttons of equal width, floating over the bottom of the canvas. They
 * share the width available, from the 48 dp touch target up to 76 dp: six fit a 310 dp canvas and seven a 360 dp
 * one, the bar being the canvas less [SelectionBarMargin] on each side. Only a narrower canvas scrolls the row. Cells
 * are whole pixels, so rounding never pushes the last one out of view.
 */
@Composable
internal fun EditorSelectionBar(actions: List<EditorToolbarAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val count = actions.size.coerceAtLeast(1)
        val cell = with(density) {
            val free = constraints.maxWidth - SelectionBarPadding.roundToPx() * 2 - SelectionBarGap.roundToPx() * (count - 1)
            (free / count).toDp().coerceIn(SelectionBarMinCell, SelectionBarMaxCell)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = ChromeColors.Bar, shadowElevation = 3.dp,
            border = BorderStroke(1.dp, ChromeColors.Divider)) {
            Row(Modifier.testTag("selection-bar").semantics { isTraversalGroup = true }
                .horizontalScroll(rememberScrollState()).padding(horizontal = SelectionBarPadding, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(SelectionBarGap), verticalAlignment = Alignment.CenterVertically) {
                actions.forEach { SelectionBarButton(it, cell) }
            }
        }
    }
}

@Composable
private fun SelectionBarButton(action: EditorToolbarAction, cell: Dp) {
    val shape = RoundedCornerShape(8.dp)
    val selected = action.selected
    val ink = if (action.enabled) ChromeColors.Ink else ChromeColors.Ink.copy(alpha = 0.38f)
    // The caption is the visible name; the content description is the only one assistive technology reads.
    val activation = if (selected != null) {
        Modifier.toggleable(value = selected, enabled = action.enabled, role = Role.Switch) { action.onClick() }
    } else Modifier.clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
    Column(
        Modifier.width(cell).sizeIn(minWidth = ChromeTouchTarget, minHeight = 56.dp)
            .clip(shape)
            .background(if (selected == true) ChromeColors.Selected else Color.Transparent)
            .then(if (selected == true) Modifier.border(1.dp, ChromeColors.SelectedBorder, shape) else Modifier)
            .then(activation)
            .testTag(action.id)
            .semantics { contentDescription = action.description }
            .padding(horizontal = SelectionCaptionInset, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        action.icon?.let { EditorIconGlyph(it, Modifier.size(22.dp), tint = ChromeColors.Ink, enabled = action.enabled) }
        SelectionBarCaption(action.label, cell, ink)
    }
}

/** The space beside a caption inside its button. */
private val SelectionCaptionInset = 2.dp
/** Caption sizes in sp, largest first: 10 sp, then down to 8 sp while a word does not fit its cell. */
private val SelectionCaptionSizes = listOf(10f, 9.5f, 9f, 8.5f, 8f)
private const val SelectionCaptionMaxLines = 2

/** A caption laid out by [fitSelectionCaption]: its [size] in sp and its [lines], each of whole words. */
internal data class SelectionCaption(val size: Float, val lines: List<String>)

/**
 * Lays [text] out on at most two lines of whole words, at the largest size from 10 down to 8 sp at which every line
 * fits [width]. A word wider than [width] even at 8 sp keeps a line of its own and is ellipsized there, so a caption
 * such as "Adicionar" or "Propriedades" is never broken inside a word. [measure] gives the width a string takes on
 * one line at a size in sp, in the unit of [width].
 */
internal fun fitSelectionCaption(text: String, width: Float, measure: (String, Float) -> Float): SelectionCaption {
    val words = text.split(' ').filter(String::isNotEmpty)
    fun lines(size: Float): List<String> = buildList {
        for (word in words) {
            val joined = lastOrNull()?.let { "$it $word" }
            if (joined != null && measure(joined, size) <= width) set(lastIndex, joined) else add(word)
        }
    }
    for (size in SelectionCaptionSizes) {
        val lines = lines(size)
        if (lines.size <= SelectionCaptionMaxLines && lines.all { measure(it, size) <= width }) return SelectionCaption(size, lines)
    }
    val smallest = SelectionCaptionSizes.last()
    val lines = lines(smallest)
    // More lines than the button holds: the last one takes the rest of the words, ellipsized.
    return SelectionCaption(smallest, lines.take(SelectionCaptionMaxLines - 1) +
        listOfNotNull(lines.drop(SelectionCaptionMaxLines - 1).joinToString(" ").ifEmpty { null }))
}

/**
 * The visible name under the icon of a button [cell] wide; the button's content description is what assistive
 * technology reads.
 */
@Composable
private fun SelectionBarCaption(text: String, cell: Dp, color: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current
    val caption = remember(text, cell, density, measurer, style) {
        val limit = with(density) { cell.roundToPx() - SelectionCaptionInset.roundToPx() * 2 }.toFloat()
        fitSelectionCaption(text, limit) { line, size ->
            measurer.measure(line, style.merge(TextStyle(fontSize = size.sp)), maxLines = 1, softWrap = false).size.width.toFloat()
        }
    }
    Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
        caption.lines.forEach { line ->
            Text(line, fontSize = caption.size.sp, lineHeight = 1.2.em, color = color, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Shown while Add to selection is on; its button turns it off. The whole chip takes the touches that land on it, so a
 * tap on its label never selects what the canvas has underneath.
 */
@Composable
internal fun AdditiveSelectionChip(label: String, stopDescription: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(modifier.testTag("selection-adding-chip").clip(shape)
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
        .background(ChromeColors.Selected)
        .border(1.dp, ChromeColors.SelectedBorder, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = ChromeColors.Ink, maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Box(Modifier.size(ChromeTouchTarget).clickable(role = Role.Button, onClick = onStop)
            .testTag("selection-adding-stop").semantics { contentDescription = stopDescription },
            contentAlignment = Alignment.Center) {
            EditorIconGlyph(EditorIcon.CLOSE, Modifier.size(14.dp))
        }
    }
}
