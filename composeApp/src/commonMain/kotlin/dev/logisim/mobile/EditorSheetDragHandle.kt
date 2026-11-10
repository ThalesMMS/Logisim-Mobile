package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.localization.localizedString

@Composable
internal fun ColumnScope.EditorSheetDragHandle(inspector: Boolean, onDismiss: () -> Unit) {
    val description = localizedString(if (inspector) "a11y_inspector_handle" else "a11y_sheet_drag_handle")
    val dismissLabel = localizedString(if (inspector) "a11y_inspector_dismiss" else "a11y_sheet_close")
    Box(
        modifier = Modifier.align(Alignment.CenterHorizontally).size(48.dp)
            .testTag(if (inspector) "editor-inspector-drag-handle" else "editor-sheet-drag-handle")
            .clickable(role = Role.Button, onClickLabel = dismissLabel, onClick = onDismiss)
            .semantics {
                contentDescription = description
                dismiss(dismissLabel) { onDismiss(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraLarge))
    }
}
