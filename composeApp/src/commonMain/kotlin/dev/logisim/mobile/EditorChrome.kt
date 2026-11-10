package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.logisim.mobile.localization.localizedString

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



/** A square tool button: an Evolution-style glyph, or a short label when the action has no icon. */
@Composable
internal fun ChromeButton(
    action: EditorToolbarAction,
    modifier: Modifier = Modifier,
    size: Dp = ChromeButtonSize,
    tag: String = "tool-${action.id}",
) {
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(size).widthIn(min = size)
                .clip(shape)
                .background(if (selected) ChromeColors.Selected else Color.Transparent)
                .then(if (selected) Modifier.border(1.dp, ChromeColors.SelectedBorder, shape) else Modifier),
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
        icon = if (expanded) EditorIcon.COLLAPSE else EditorIcon.EXPAND) { expanded = !expanded }
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

@Composable
internal fun ChromeMenuItems(actions: List<EditorToolbarAction>, onDismiss: () -> Unit) {
    actions.forEachIndexed { index, action ->
        if (action.separator) {
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

@Composable
private fun ChromeMenuTitle(menu: EditorMenu) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.height(ChromeBarHeight).clip(RoundedCornerShape(6.dp))
            .background(if (expanded) ChromeColors.Selected else Color.Transparent)
            .clickable(role = Role.Button) { expanded = true }
            .testTag("menu-title-${menu.id}")
            .semantics { contentDescription = menu.description }
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
        ChromeButton(EditorToolbarAction("menu", "≡", description, icon = EditorIcon.MENU) { open = null; expanded = true },
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
