package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.localization.localizedString

internal data class EditorToolbarAction(
    val id: String,
    val label: String,
    val description: String = label,
    val enabled: Boolean = true,
    val selected: Boolean? = null,
    val separator: Boolean = false,
    val icon: EditorIcon? = null,
    val iconColor: Int? = null,
    val onClick: () -> Unit,
)

/** A toolbar places whole controls; remaining commands stay available in its menu. */
@Composable
internal fun EditorToolbar(
    actions: List<EditorToolbarAction>,
    tag: String,
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    orientation: EditorToolbarOrientation = EditorToolbarOrientation.HORIZONTAL,
) {
    if (orientation == EditorToolbarOrientation.VERTICAL) {
        Column(modifier.fillMaxHeight().testTag(tag).verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            leading?.invoke()
            actions.forEach { action ->
                if (action.separator) HorizontalDivider()
                else ToolbarButton(action, Modifier.fillMaxWidth(), maxLines = 2)
            }
        }
        return
    }
    SubcomposeLayout(modifier.fillMaxWidth().testTag(tag).padding(horizontal = 4.dp)) { constraints ->
        val gap = 2.dp.roundToPx()
        val leadingItem = leading?.let { content ->
            subcompose("identity") { Box { content() } }.single().measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = minOf(180.dp.roundToPx(), constraints.maxWidth / 2)),
            )
        }
        val leadingWidth = leadingItem?.let { it.width + gap } ?: 0
        val available = (constraints.maxWidth - leadingWidth).coerceAtLeast(0)
        val buttonConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = available)
        val buttons = actions.map { action ->
            subcompose("action:${action.id}") {
                if (action.separator) Box(Modifier.padding(horizontal = 4.dp)) { VerticalDivider(Modifier.height(24.dp)) }
                else ToolbarButton(action)
            }.single().measure(buttonConstraints)
        }
        val overflowWidth = minOf(48.dp.roundToPx(), available)
        val count = toolbarVisibleActionCount(buttons.map { it.width }, available, overflowWidth, gap)
        val overflow = if (count < actions.size) {
            subcompose("overflow") {
                ToolbarOverflow(actions.drop(count), tag, title)
            }.single().measure(buttonConstraints.copy(minWidth = overflowWidth, maxWidth = overflowWidth))
        } else null
        val height = constraints.constrainHeight(maxOf(
            leadingItem?.height ?: 0, buttons.take(count).maxOfOrNull { it.height } ?: 0, overflow?.height ?: 0,
        ))
        layout(constraints.maxWidth, height) {
            leadingItem?.placeRelative(0, (height - leadingItem.height) / 2)
            var x = leadingWidth
            buttons.take(count).forEach { button ->
                button.placeRelative(x, (height - button.height) / 2)
                x += button.width + gap
            }
            overflow?.placeRelative(x, (height - overflow.height) / 2)
        }
    }
}

@Composable
private fun ToolbarButton(action: EditorToolbarAction, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val buttonModifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
        contentDescription = action.description
        action.selected?.let { selected = it }
    }
    if (action.selected != null) {
        Button(onClick = action.onClick, enabled = action.enabled, modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (action.selected) ChromeColors.Selected else MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(1.dp)) {
            Text(action.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    } else {
        TextButton(onClick = action.onClick, enabled = action.enabled, modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Text(action.label, color = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolbarOverflow(actions: List<EditorToolbarAction>, tag: String, title: String) {
    var expanded by remember { mutableStateOf(false) }
    val description = localizedString("a11y_toolbar_more", "name" to title)
    val iconColor = MaterialTheme.colorScheme.onSurface
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .testTag("$tag-overflow").semantics { contentDescription = description }, contentPadding = PaddingValues(12.dp)) {
            Canvas(Modifier.size(24.dp)) {
                for (offset in listOf(-6, 0, 6)) drawCircle(iconColor, radius = 1.5.dp.toPx(),
                    center = Offset(center.x + offset.dp.toPx(), center.y))
            }
        }
        if (expanded) EditorToolbarActionsDialog(actions, tag, title) { expanded = false }
    }
}

@Composable
internal fun EditorToolbarActionsDialog(
    actions: List<EditorToolbarAction>,
    tag: String,
    title: String,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.editorDialogBounds().exportEditorTestTags(), title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("$tag-menu").verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    if (action.separator) HorizontalDivider()
                    else TextButton(onClick = { onDismiss(); action.onClick() }, enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            contentDescription = action.description
                            action.selected?.let { selected = it }
                        }) { Text(action.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(localizedString("action_close"))
        } })
}
