package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.formatTickFrequency
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.localizedString
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The flat palette of Logisim Evolution's desktop frame, following the active light or dark theme. */
internal object ChromeColors {
    val Bar: Color @Composable @ReadOnlyComposable get() = chromePalette.bar
    val Panel: Color @Composable @ReadOnlyComposable get() = chromePalette.panel
    val Heading: Color @Composable @ReadOnlyComposable get() = chromePalette.heading
    val Divider: Color @Composable @ReadOnlyComposable get() = chromePalette.divider
    val Selected: Color @Composable @ReadOnlyComposable get() = chromePalette.selected
    val SelectedBorder: Color @Composable @ReadOnlyComposable get() = chromePalette.selectedBorder
    val Ink: Color @Composable @ReadOnlyComposable get() = chromePalette.ink
    val Muted: Color @Composable @ReadOnlyComposable get() = chromePalette.muted
    val Accent: Color @Composable @ReadOnlyComposable get() = chromePalette.accent
    val Warning: Color @Composable @ReadOnlyComposable get() = chromePalette.warning
    val Paper: Color @Composable @ReadOnlyComposable get() = chromePalette.paper
    val Surface: Color @Composable @ReadOnlyComposable get() = chromePalette.surface
    val PassInk: Color @Composable @ReadOnlyComposable get() = chromePalette.passInk
    val TraceCursor: Color @Composable @ReadOnlyComposable get() = chromePalette.traceCursor
}

internal val ChromeButtonSize = 40.dp
/** Every chrome button keeps Android's 48 dp minimum touch target around its smaller glyph. */
internal val ChromeTouchTarget = 48.dp
/**
 * Bars leave room around the 48 dp minimum touch target that Android reports for every
 * clickable, so automation and accessibility see whole buttons inside their toolbar.
 */
internal val ChromeBarHeight = 50.dp

private val ChromePopupProperties = PopupProperties(focusable = editorMenusTakeFocus)

/** Menu popups follow the light/dark chrome scheme so their items keep readable contrast. */
@Composable
@ReadOnlyComposable
internal fun chromeMenuContainerColor(): Color = MaterialTheme.colorScheme.surface

@Composable
internal fun chromeMenuItemColors(): MenuItemColors {
    val ink = MaterialTheme.colorScheme.onSurface
    return MenuDefaults.itemColors(
        textColor = ink, leadingIconColor = ink, trailingIconColor = ink,
        disabledTextColor = ink.copy(alpha = 0.38f), disabledLeadingIconColor = ink.copy(alpha = 0.38f),
        disabledTrailingIconColor = ink.copy(alpha = 0.38f),
    )
}

/** [itemsTag] identifies the open menu for automation instead of its generic popup tag. */
internal data class EditorMenu(
    val id: String,
    val title: String,
    val items: List<EditorToolbarAction>,
    val description: String = title,
    val itemsTag: String? = null,
)



/**
 * A square tool button: an Evolution-style glyph, or a short label when the action has no icon. A glyph shows its
 * name in a tooltip on long press (hover with a pointer), as Evolution's header buttons do with setToolTipText.
 * The tooltip is for sight only: the content description is the one name assistive technology reads, and no
 * long-press action is offered to it ([tooltipGestures]), since the tooltip would only repeat that name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChromeButton(
    action: EditorToolbarAction,
    modifier: Modifier = Modifier,
    size: Dp = ChromeButtonSize,
    tag: String = "tool-${action.id}",
) {
    // Symbol labels (≡, ⌄, +, −) name nothing; their description does.
    val tip = listOf(action.label, action.description).firstOrNull { text -> text.any(Char::isLetter) }
    if (action.icon == null || tip == null) {
        ChromeButtonBody(action, modifier, size, tag)
        return
    }
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(Modifier.testTag("tooltip-${action.id}")) {
                Text(tip, Modifier.semantics { hideFromAccessibility() })
            }
        },
        state = state,
        modifier = modifier.tooltipGestures(state),
        // TooltipBox's own input handling also gives the button a "Show tooltip" long-press accessibility action.
        enableUserInput = false,
    ) { ChromeButtonBody(action, Modifier, size, tag) }
}

/**
 * TooltipBox's touch and mouse handling without its accessibility action: a long press shows the tooltip and keeps
 * the release from activating the button; a mouse pointer shows it while hovering.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.tooltipGestures(state: TooltipState): Modifier =
    pointerInput(state) {
        coroutineScope {
            awaitEachGesture {
                val pass = PointerEventPass.Initial
                val type = awaitFirstDown(requireUnconsumed = false, pass = pass).type
                if (type != PointerType.Touch && type != PointerType.Stylus) return@awaitEachGesture
                try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(pass) }
                } catch (_: PointerEventTimeoutCancellationException) {
                    launch(start = CoroutineStart.UNDISPATCHED) { state.show(MutatePriority.UserInput) }
                    waitForUpOrCancellation(pass)?.consume()
                }
            }
        }
    }.pointerInput(state) {
        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                    when (event.type) {
                        PointerEventType.Enter -> launch { state.show(MutatePriority.UserInput) }
                        PointerEventType.Exit -> state.dismiss()
                    }
                }
            }
        }
    }

@Composable
private fun ChromeButtonBody(action: EditorToolbarAction, modifier: Modifier, size: Dp, tag: String) {
    val selected = action.selected == true
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .sizeIn(minWidth = ChromeTouchTarget, minHeight = ChromeTouchTarget)
            .clip(shape)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .testTag(tag)
            .semantics {
                contentDescription = action.description
                action.selected?.let { this.selected = it }
                action.stateDescription?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(size).widthIn(min = size)
                .clip(shape)
                .background(if (selected) ChromeColors.Selected else Color.Transparent)
                // The accent border is the 3:1 cue of the armed or toggled tool; the tint alone is about 1.2:1.
                .then(if (selected) Modifier.border(2.dp, ChromeColors.Accent, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val icon = action.icon
            if (icon != null) {
                EditorIconGlyph(icon, Modifier.size(size * 0.55f), tint = action.iconColor.editorColorOr(ChromeColors.Ink), enabled = action.enabled)
            } else {
                Text(action.label, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (action.enabled) ChromeColors.Ink else ChromeColors.Ink.copy(alpha = 0.38f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChromeSeparator(vertical: Boolean) {
    if (vertical) Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(ChromeColors.Divider))
    else Box(Modifier.padding(vertical = 4.dp).height(1.dp).width(24.dp).background(ChromeColors.Divider))
}

/**
 * Places whole tool buttons. When they do not fit, an expand button unfolds the toolbar into extra
 * rows so every tool stays one tap away. A vertical toolbar is a narrow scrolling rail, like
 * Evolution's toolbar docked beside the canvas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChromeToolbar(
    actions: List<EditorToolbarAction>,
    tag: String,
    title: String,
    modifier: Modifier = Modifier,
    orientation: EditorToolbarOrientation = EditorToolbarOrientation.HORIZONTAL,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (orientation == EditorToolbarOrientation.VERTICAL) {
        Column(modifier.fillMaxHeight().width(ChromeBarHeight).testTag(tag).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            actions.forEach { action -> if (action.separator) ChromeSeparator(vertical = false) else ChromeButton(action) }
            trailing?.let { ChromeSeparator(vertical = false); it() }
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val toggle = EditorToolbarAction("$tag-overflow", "⌄",
        localizedString(if (expanded) "a11y_toolbar_less" else "a11y_toolbar_more", "name" to title),
        icon = if (expanded) EditorIcon.COLLAPSE else EditorIcon.EXPAND,
        stateDescription = expanderState(expanded)) { expanded = !expanded }
    if (expanded) {
        // The trailing status wraps with the actions. Kept beside them as a fixed column, a
        // large font on a narrow window left one action per row, collapsed the canvas and
        // pushed the fold button off screen (#239, 320 x 568 dp at 200%).
        FlowRow(modifier.fillMaxWidth().testTag(tag).padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            // Like a popover, the extra rows fold back once one of their actions is used.
            actions.forEach { action ->
                if (action.separator) ChromeSeparator(vertical = true)
                else ChromeButton(action.copy(onClick = { expanded = false; action.onClick() }))
            }
            ChromeButton(toggle, tag = "$tag-overflow")
            trailing?.let { Row(Modifier.padding(start = 8.dp).heightIn(min = ChromeBarHeight), verticalAlignment = Alignment.CenterVertically) { it() } }
        }
        return
    }
    SubcomposeLayout(modifier.fillMaxWidth().testTag(tag).padding(horizontal = 5.dp)) { constraints ->
        val gap = 2.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailingItem = trailing?.let { subcompose("trailing") { Row(verticalAlignment = Alignment.CenterVertically) { it() } }.single().measure(loose) }
        val trailingWidth = trailingItem?.let { it.width + gap * 4 } ?: 0
        val available = (constraints.maxWidth - trailingWidth).coerceAtLeast(0)
        val buttons = actions.map { action ->
            subcompose("action:${action.id}") {
                if (action.separator) ChromeSeparator(vertical = true) else ChromeButton(action)
            }.single().measure(loose.copy(maxWidth = available))
        }
        val overflowWidth = minOf(ChromeTouchTarget.roundToPx(), available)
        var count = toolbarVisibleActionCount(buttons.map { it.width }, available, overflowWidth, gap)
        // A trailing separator would only separate the toolbar from its own expand button.
        while (count in 1 until actions.size && actions[count - 1].separator) count--
        val overflow = if (count < actions.size) {
            subcompose("overflow") { ChromeButton(toggle, tag = "$tag-overflow") }
                .single().measure(loose.copy(maxWidth = overflowWidth))
        } else null
        val height = constraints.constrainHeight(maxOf(ChromeBarHeight.roundToPx(),
            buttons.take(count).maxOfOrNull { it.height } ?: 0, trailingItem?.height ?: 0))
        layout(constraints.maxWidth, height) {
            var x = 0
            buttons.take(count).forEach { button ->
                button.placeRelative(x, (height - button.height) / 2)
                x += button.width + gap
            }
            overflow?.placeRelative(x, (height - overflow.height) / 2)
            trailingItem?.placeRelative(constraints.maxWidth - trailingItem.width, (height - trailingItem.height) / 2)
        }
    }
}

/** A separator with a label heads the items after it, as the Simulate menu's tick frequencies. */
@Composable
internal fun ChromeMenuItems(actions: List<EditorToolbarAction>, onDismiss: () -> Unit) {
    actions.forEachIndexed { index, action ->
        if (action.separator && action.label.isNotEmpty()) {
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ChromeColors.Divider)
            ChromeMenuHeading(action.label, Modifier.testTag("menu-${action.id}"))
        } else if (action.separator) {
            if (index > 0 && index < actions.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ChromeColors.Divider)
        } else {
            DropdownMenuItem(
                // Long translations and large system fonts wrap instead of hiding the command name.
                text = { Text(action.label, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                onClick = { onDismiss(); action.onClick() },
                enabled = action.enabled,
                colors = chromeMenuItemColors(),
                leadingIcon = {
                    val icon = if (action.selected == true) EditorIcon.CHECK else action.icon
                    if (icon != null) EditorIconGlyph(icon, Modifier.size(18.dp),
                        tint = action.iconColor.takeUnless { icon == EditorIcon.CHECK }.editorColorOr(ChromeColors.Ink),
                        enabled = action.enabled)
                    else Spacer(Modifier.size(18.dp))
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 200.dp).testTag("menu-${action.id}").semantics {
                    contentDescription = action.description
                    action.selected?.let { selected = it }
                },
            )
        }
    }
}

/** Evolution's desktop menu bar: each title opens its menu below. */
@Composable
internal fun ChromeMenuBar(menus: List<EditorMenu>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        menus.forEach { menu -> key(menu.id) { ChromeMenuTitle(menu) } }
    }
}

/** TalkBack's state for a control that opens a menu or more rows (C29). */
@Composable
internal fun expanderState(expanded: Boolean): String =
    localizedString(if (expanded) "a11y_state_expanded" else "a11y_state_collapsed")

@Composable
private fun ChromeMenuTitle(menu: EditorMenu) {
    var expanded by remember { mutableStateOf(false) }
    val state = expanderState(expanded)
    Box {
        Box(Modifier.height(ChromeBarHeight).clip(RoundedCornerShape(6.dp))
            .background(if (expanded) ChromeColors.Selected else Color.Transparent)
            .clickable(role = Role.Button) { expanded = true }
            .testTag("menu-title-${menu.id}")
            .semantics { contentDescription = menu.description; stateDescription = state }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(menu.title, fontSize = 14.sp, color = ChromeColors.Ink, maxLines = 1)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, properties = ChromePopupProperties, containerColor = chromeMenuContainerColor(), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.exportEditorTestTags().testTag(menu.itemsTag ?: "editor-menu-${menu.id}")) {
            ChromeMenuItems(menu.items) { expanded = false }
        }
    }
}

/**
 * On narrow screens the whole menu bar folds into one button with drill-down menus. The button
 * and its popup keep the header's overflow tags so automation reaches menus the same way.
 */
@Composable
internal fun ChromeCompactMenu(menus: List<EditorMenu>, description: String, back: String) {
    var expanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    Box {
        ChromeButton(EditorToolbarAction("menu", "≡", description, icon = EditorIcon.MENU,
            stateDescription = expanderState(expanded)) { open = null; expanded = true },
            tag = "editor-project-toolbar-overflow")
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, properties = ChromePopupProperties, containerColor = chromeMenuContainerColor(), shape = RoundedCornerShape(8.dp), modifier = Modifier.exportEditorTestTags()
            .testTag(menus.firstOrNull { it.id == open }?.itemsTag ?: "editor-project-toolbar-menu")) {
            val current = menus.firstOrNull { it.id == open }
            if (current == null) {
                menus.forEach { menu ->
                    DropdownMenuItem(text = { Text(menu.title, fontSize = 14.sp) }, onClick = { open = menu.id },
                        trailingIcon = { EditorIconGlyph(EditorIcon.NEXT, Modifier.size(14.dp)) },
                        colors = chromeMenuItemColors(),
                        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 220.dp).testTag("menu-title-${menu.id}")
                            .semantics { contentDescription = menu.description })
                }
            } else {
                DropdownMenuItem(text = { Text(current.title, fontSize = 14.sp, fontWeight = FontWeight.Bold) }, onClick = { open = null },
                    leadingIcon = { EditorIconGlyph(EditorIcon.BACK, Modifier.size(14.dp)) },
                    colors = chromeMenuItemColors(),
                    modifier = Modifier.heightIn(min = 44.dp).testTag("menu-back").semantics { contentDescription = back })
                HorizontalDivider(color = ChromeColors.Divider)
                ChromeMenuItems(current.items) { expanded = false }
            }
        }
    }
}

/** A non-interactive title inside a menu, announced as a heading. */
@Composable
internal fun ChromeMenuHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, lineHeight = 1.25.em, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.widthIn(min = 160.dp).padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() })
}

/** The tick frequency of the project, the rate the run loop measured while above what it sustains, and the change. */
internal class TickFrequencyControl(
    val current: Double,
    val enabled: Boolean,
    val measuredTicksPerSecond: Double?,
    val onChoose: (Double) -> Unit,
)

/** Everything the simulation bar offers; the actions are the ones the Simulate menu lists, with the same names. */
internal class SimulationControls(
    val title: String,
    val run: EditorToolbarAction,
    val event: EditorToolbarAction,
    val tickHalf: EditorToolbarAction,
    val tickFull: EditorToolbarAction,
    val autoTick: EditorToolbarAction,
    val reset: EditorToolbarAction,
    val frequency: TickFrequencyControl,
    /** Extra commands at the end of the overflow menu, such as Simulation options. */
    val menuExtras: List<EditorToolbarAction> = emptyList(),
) {
    fun action(control: SimulationControl): EditorToolbarAction? = when (control) {
        SimulationControl.RUN -> run
        SimulationControl.EVENT -> event
        SimulationControl.TICK_HALF -> tickHalf
        SimulationControl.TICK_FULL -> tickFull
        SimulationControl.AUTO_TICK -> autoTick
        SimulationControl.RESET -> reset
        SimulationControl.FREQUENCY -> null
    }
}

private val SimulationBarPadding = 2.dp

/** The width under which [SimulationControlBar] squeezes Run, Tick, Reset or its overflow button below 48 dp. */
internal val SimulationControlBarMinWidth: Dp =
    simulationBarMinimumWidth(SimulationBarMetrics(frequency = 0f)).dp + SimulationBarPadding * 2
private val FrequencyChipPadding = 5.dp
private val FrequencyChipChevron = 12.dp

/** Wide enough for every offered rate in the current font, so choosing another one never resizes the chip. */
@Composable
private fun tickFrequencyChipWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val widest = TickFrequencies.choices.maxOf { measurer.measure(formatTickFrequency(it), style, maxLines = 1).size.width }
        with(density) { widest.toDp() } + FrequencyChipPadding * 2 + FrequencyChipChevron + 2.dp
    }
}

/**
 * The simulation controls of a bar, in Evolution's header order: Run, Step event, Tick half cycle, Tick full cycle and
 * the frequency, then Auto-tick and, after a divider, Reset. Run, Tick and Reset are always on the bar; what the width
 * leaves out goes to one overflow menu. The layout depends on the width and font only ([simulationBarControls]), so
 * using a control never folds or moves the bar. [tag] names the overflow button and menu; [barTag] the row itself.
 */
@Composable
internal fun SimulationControlBar(
    controls: SimulationControls,
    modifier: Modifier = Modifier,
    tag: String = "editor-simulation-toolbar",
    barTag: String? = tag,
) {
    val chipWidth = tickFrequencyChipWidth()
    BoxWithConstraints(modifier) {
        val available = if (maxWidth == Dp.Infinity) Float.POSITIVE_INFINITY else (maxWidth - SimulationBarPadding * 2).value
        val shown = simulationBarControls(available, SimulationBarMetrics(frequency = chipWidth.value))
        val hidden = SimulationControl.entries.filter { it !in shown }
        Row(Modifier.then(if (barTag == null) Modifier else Modifier.testTag(barTag)).heightIn(min = ChromeBarHeight)
            .padding(horizontal = SimulationBarPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            shown.forEachIndexed { index, control ->
                // Reset stands apart from the stepping controls, as a destructive action.
                if (control == SimulationControl.RESET && index > 0) ChromeSeparator(vertical = true)
                if (control == SimulationControl.FREQUENCY) TickFrequencyChip(controls.frequency, chipWidth)
                else ChromeButton(checkNotNull(controls.action(control)))
            }
            if (hidden.isNotEmpty()) SimulationOverflow(controls, hidden, tag)
        }
    }
}

/**
 * Evolution's frequency button: the current rate, opening a list of rates with the current one checked
 * (UnifiedHeaderBar.showFrequencyMenu and MenuSimulate's TickFrequencyChoice radio items).
 */
@Composable
internal fun TickFrequencyChip(control: TickFrequencyControl, width: Dp, tag: String = "tool-frequency") {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val label = formatTickFrequency(control.current)
    // Above the rate the run loop sustains, the measured rate shows what the circuit really gets.
    val measured = control.measuredTicksPerSecond?.takeIf { !TickFrequencies.isSustained(control.current) && it > 0 }
    val description = strings("simulation_tick_frequency_value", "value" to label) +
        measured?.let { ". " + strings(Messages.SimulationMeasuredTickRate, "count" to it.roundToLong()) }.orEmpty()
    val ink = if (control.enabled) ChromeColors.Ink else ChromeColors.Ink.copy(alpha = 0.38f)
    val shape = RoundedCornerShape(6.dp)
    Box {
        Box(Modifier.width(width).heightIn(min = ChromeTouchTarget).clip(shape)
            .clickable(enabled = control.enabled, role = Role.DropdownList) { expanded = true }
            .testTag(tag).semantics { contentDescription = description },
            contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().height(ChromeButtonSize).border(1.dp, ChromeColors.Divider, shape)
                .padding(horizontal = FrequencyChipPadding),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 12.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Medium, color = ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (measured != null) Text("≈" + formatTickFrequency(measured.roundToLong().toDouble()), fontSize = 9.sp,
                        lineHeight = 1.2.em, color = ChromeColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("tool-frequency-measured"))
                }
                EditorIconGlyph(EditorIcon.EXPAND, Modifier.size(FrequencyChipChevron), tint = ink, enabled = control.enabled)
            }
        }
        val scroll = rememberScrollState()
        TickFrequencyScroll(expanded, control, scroll)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, properties = ChromePopupProperties,
            containerColor = chromeMenuContainerColor(), shape = RoundedCornerShape(8.dp), scrollState = scroll,
            modifier = Modifier.exportEditorTestTags().testTag("tick-frequency-menu")) {
            ChromeMenuHeading(strings("simulation_tick_frequency"))
            TickFrequencyMenuItems(control) { expanded = false }
        }
    }
}

/** Opens a frequency list with the checked rate in view, as Preferences' drop-down lists do. */
@Composable
private fun TickFrequencyScroll(expanded: Boolean, control: TickFrequencyControl, scroll: ScrollState) {
    val item = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(expanded) {
        val index = TickFrequencies.choicesWith(control.current).indexOfFirst { TickFrequencies.same(it.hz, control.current) }
        if (expanded && index > 2) scroll.scrollTo((index - 2) * item)
    }
}

/** The rates as radio items; a stored rate that is not one of Evolution's stays listed, checked, as custom. */
@Composable
private fun TickFrequencyMenuItems(control: TickFrequencyControl, onChosen: () -> Unit) {
    val strings = rememberLocalizer()
    Column(Modifier.selectableGroup()) {
        TickFrequencies.choicesWith(control.current).forEach { choice ->
            val selected = TickFrequencies.same(choice.hz, control.current)
            val label = formatTickFrequency(choice.hz).let { if (choice.custom) strings("simulation_tick_frequency_custom", "value" to it) else it }
            DropdownMenuItem(
                text = { Text(label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = { onChosen(); if (!selected) control.onChoose(choice.hz) },
                enabled = control.enabled,
                colors = chromeMenuItemColors(),
                leadingIcon = { RadioButton(selected = selected, onClick = null, enabled = control.enabled, modifier = Modifier.size(20.dp)) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 160.dp).testTag("tick-frequency-${choice.hz}")
                    .semantics { this.selected = selected; role = Role.RadioButton },
            )
        }
    }
}

/**
 * The controls the bar leaves out, in bar order, then [SimulationControls.menuExtras]. The frequency opens its list
 * inside the same menu. Like any menu it closes once a command runs; the controls repeated most stay on the bar.
 */
@Composable
private fun SimulationOverflow(controls: SimulationControls, hidden: List<SimulationControl>, tag: String) {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    var frequencies by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    TickFrequencyScroll(expanded && frequencies, controls.frequency, scroll)
    Box {
        ChromeButton(EditorToolbarAction("$tag-overflow", "⋯", localizedString("a11y_toolbar_more", "name" to controls.title),
            icon = EditorIcon.MORE) { frequencies = false; expanded = true }, tag = "$tag-overflow")
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, properties = ChromePopupProperties,
            containerColor = chromeMenuContainerColor(), shape = RoundedCornerShape(8.dp), scrollState = scroll,
            modifier = Modifier.exportEditorTestTags().testTag("$tag-menu")) {
            if (frequencies) {
                DropdownMenuItem(text = { Text(strings("simulation_tick_frequency"), fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                    onClick = { frequencies = false }, colors = chromeMenuItemColors(),
                    leadingIcon = { EditorIconGlyph(EditorIcon.BACK, Modifier.size(14.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("menu-back").semantics { contentDescription = strings("menu_back") })
                HorizontalDivider(color = ChromeColors.Divider)
                TickFrequencyMenuItems(controls.frequency) { expanded = false }
            } else {
                hidden.forEach { control ->
                    val action = controls.action(control)
                    if (action != null) ChromeMenuItems(listOf(action)) { expanded = false }
                    else DropdownMenuItem(
                        text = { Text(strings("simulation_tick_frequency_value", "value" to formatTickFrequency(controls.frequency.current)),
                            fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        onClick = { frequencies = true }, enabled = controls.frequency.enabled, colors = chromeMenuItemColors(),
                        leadingIcon = { Spacer(Modifier.size(18.dp)) },
                        trailingIcon = { EditorIconGlyph(EditorIcon.NEXT, Modifier.size(14.dp)) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 200.dp).testTag("menu-frequency"))
                }
                if (controls.menuExtras.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ChromeColors.Divider)
                    ChromeMenuItems(controls.menuExtras) { expanded = false }
                }
            }
        }
    }
}
