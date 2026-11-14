package dev.logisim.mobile

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.Localizer
import dev.logisim.mobile.localization.rememberLocalizer

/**
 * [SimulationControlBarMinWidth] in whole pixels, as the bar's Row lays it out: Run, Tick, Reset and the overflow button
 * at 48 dp, the divider's 4 dp paddings and 1 dp line, the 2 dp gaps and the 2 dp paddings each round on their own. At
 * densities such as 2.75 that is a few pixels more than the dp total rounded once, which left the overflow button
 * under 48 dp when the floor applied.
 */
internal fun simulationControlBarMinWidthPx(density: Density): Int = with(density) {
    val buttons = 4
    val divider = 4.dp.roundToPx() * 2 + 1.dp.roundToPx()
    val gap = 2.dp.roundToPx()
    val padding = 2.dp.roundToPx()
    buttons * ChromeTouchTarget.roundToPx() + divider + gap * buttons + padding * 2
}

/** The width of [ChromeMenuBar] showing [menus] in the current font: each title's text and its 10 dp paddings. */
@Composable
internal fun chromeMenuBarWidth(menus: List<EditorMenu>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 14.sp))
    val titles = menus.map { it.title }
    return remember(titles, measurer, density, style) {
        with(density) { titles.sumOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp() } + 20.dp * titles.size
    }
}

/** The circuit picker's chevron after the header title, and the gap before it. */
internal val HeaderTitleChevronSize = 12.dp
internal val HeaderTitleChevronGap = 4.dp

/**
 * What the header title keeps beside the simulation group: the picker's 48 dp target plus its chevron, so the name
 * keeps the room it had before the chevron. The simulation bar never grows into it; only a count kept whole may take
 * the chevron's part, where the bar is at its floor. At 100 % that shows a few letters of the name; on a 600 dp window
 * at 200 % with a seven-digit count, the target alone ([HeaderTitleText] then drops the chevron).
 */
internal val HeaderTitleMinWidth = ChromeTouchTarget + HeaderTitleChevronSize + HeaderTitleChevronGap

/** What the header title keeps beside the menu bar when no simulation controls share the header: two touch targets. */
internal val HeaderTitleRoomWithoutControls = HeaderTitleMinWidth + ChromeTouchTarget

private val HeaderTitleStyle = TextStyle(fontSize = 14.sp, lineHeight = 1.25.em, fontWeight = FontWeight.SemiBold)

/**
 * The header title: the circuit [title], cut with an ellipsis, then the circuit picker's chevron. The chevron only
 * decorates the picker, which the title's click label names, so where it would leave the name less than its first
 * glyph and the ellipsis it gives way and the name keeps the whole width. Where the whole width cannot show that glyph
 * either (the title's bare target at a large font), the chevron stays rather than leave the target blank.
 */
@Composable
internal fun HeaderTitleText(title: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(HeaderTitleStyle)
    val density = LocalDensity.current
    // The narrowest the name may get and still be read: its first character and the ellipsis, or all of a short name.
    val minimal = remember(title, style, density, measurer) {
        val first = title.take(if (title.length > 1 && title[0].isHighSurrogate()) 2 else 1)
        minOf(measurer.measure(title, style, maxLines = 1).size.width, measurer.measure("$first…", style, maxLines = 1).size.width)
    }
    Layout(
        content = {
            Text(title, style = style, color = ChromeColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("editor-header-title-text"))
            EditorIconGlyph(EditorIcon.EXPAND, Modifier.size(HeaderTitleChevronSize).testTag("editor-header-title-chevron"))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = HeaderTitleChevronGap.roundToPx()
        val room = HeaderTitleChevronSize.roundToPx() + gap
        val bounded = constraints.hasBoundedWidth
        // Where even the whole width cannot show a glyph, the chevron stays as the picker's only visible sign.
        val chevron = !bounded || constraints.maxWidth - room >= minimal || constraints.maxWidth < minimal
        val textMax = if (chevron && bounded) constraints.maxWidth - room else constraints.maxWidth
        val text = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = textMax))
        val icon = if (chevron) measurables[1].measure(Constraints()) else null
        val width = (text.width + if (icon != null) room else 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(text.height, icon?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            text.placeRelative(0, (height - text.height) / 2)
            icon?.placeRelative(text.width + gap, (height - icon.height) / 2)
        }
    }
}

/**
 * Whether the full menu bar, [menuBar] wide, fits a header [width] wide beside the explorer button, [trailingButtons]
 * buttons and [trailingDividers] dividers at the end and, between them, either the simulation bar's floor, the
 * counter's [counterMin] and the title's [HeaderTitleMinWidth] ([simulationInHeader]) or [HeaderTitleRoomWithoutControls]. The
 * window width alone ignored the font scale and the language, so at 200% the bar squeezed the title and the counter
 * to nothing; where it does not fit, the menus fold into the compact menu.
 */
internal fun headerFitsMenuBar(
    width: Dp,
    menuBar: Dp,
    trailingButtons: Int,
    trailingDividers: Int,
    simulationInHeader: Boolean,
    counterMin: Dp,
): Boolean {
    val children = 3 + trailingButtons + trailingDividers
    val fixed = 8.dp + ChromeTouchTarget * (1 + trailingButtons) + 9.dp * trailingDividers + 2.dp * (children - 1)
    val middle = if (simulationInHeader) SimulationControlBarMinWidth + counterMin + HeaderTitleMinWidth
        else HeaderTitleRoomWithoutControls
    return fixed + menuBar + middle <= width
}

/** Undo and Redo as Evolution names them: "Undo <action>" in the Edit menu and the tooltip, "Can't undo" when empty. */
internal class EditorHistoryLabels(val undoLabel: String, val undoDescription: String, val redoLabel: String, val redoDescription: String)

internal fun editorHistoryLabels(strings: Localizer, undo: LocalizedText?, redo: LocalizedText?): EditorHistoryLabels =
    EditorHistoryLabels(
        undoLabel = undo?.let { strings("edit_undo_item", "action" to it) } ?: strings("edit_cant_undo"),
        // Assistive technology hears the plain name while there is nothing to undo, with the disabled state.
        undoDescription = undo?.let { strings("a11y_undo_action", "action" to it) } ?: strings(Messages.ActionUndo),
        redoLabel = redo?.let { strings("edit_redo_item", "action" to it) } ?: strings("edit_cant_redo"),
        redoDescription = redo?.let { strings("a11y_redo_action", "action" to it) } ?: strings(Messages.ActionRedo),
    )

/**
 * Evolution's header circuit picker (UnifiedHeaderBar.showCircuitMenu): every circuit of [project], the one in view
 * bold and selected, the main circuit starred, then Add circuit… and, in a workspace, Projects…, which returns to the
 * project list the title used to open.
 */
@Composable
internal fun HeaderCircuitPicker(
    expanded: Boolean,
    onDismiss: () -> Unit,
    project: CircuitProject,
    active: CircuitNavigationTarget,
    onNavigate: (CircuitNavigationTarget) -> Unit,
    onAddCircuit: (() -> Unit)?,
    onOpenProjects: (() -> Unit)?,
    projectsEnabled: Boolean,
) {
    val strings = rememberLocalizer()
    DropdownMenu(expanded, onDismissRequest = onDismiss, properties = PopupProperties(focusable = editorMenusTakeFocus),
        containerColor = chromeMenuContainerColor(), shape = RoundedCornerShape(8.dp),
        modifier = Modifier.exportEditorTestTags().testTag("editor-circuit-picker")) {
        val rows = remember(project) { ProjectNavigation.rows(project).filter { it.depth == 0 && it.kind == ProjectNavigationKind.CIRCUIT } }
        rows.forEach { row ->
            val current = row.target.circuitId == active.circuitId
            val name = if (row.isMain) strings("a11y_main_circuit", "name" to row.label) else row.label
            DropdownMenuItem(
                text = { Text(row.label, fontSize = 14.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = { onDismiss(); onNavigate(row.target) },
                colors = chromeMenuItemColors(),
                leadingIcon = { if (current) EditorIconGlyph(EditorIcon.CHECK, Modifier.size(18.dp)) else androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp)) },
                trailingIcon = if (row.isMain) ({ Text("★", fontSize = 12.sp, color = ChromeColors.Accent) }) else null,
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 220.dp).testTag("circuit-picker-${row.target.circuitId}")
                    .semantics { selected = current; contentDescription = name },
            )
        }
        if (onAddCircuit != null) {
            HorizontalDivider(color = ChromeColors.Divider)
            ChromeMenuItems(listOf(EditorToolbarAction("circuit-picker-add", strings("project_add_circuit"), onClick = onAddCircuit)), onDismiss)
        }
        if (onOpenProjects != null) {
            HorizontalDivider(color = ChromeColors.Divider)
            ChromeMenuItems(listOf(EditorToolbarAction("circuit-picker-projects", strings("menu_projects"),
                strings("a11y_open_projects"), enabled = projectsEnabled, onClick = onOpenProjects)), onDismiss)
        }
    }
}
