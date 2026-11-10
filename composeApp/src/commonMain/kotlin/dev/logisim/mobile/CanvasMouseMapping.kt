package dev.logisim.mobile

import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import dev.logisim.mobile.core.ProjectMouseAction
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseEditTool
import dev.logisim.mobile.core.ProjectMouseGesture

internal fun projectMouseGesture(buttons: PointerButtons, modifiers: PointerKeyboardModifiers): ProjectMouseGesture? {
    if (modifiers.isMetaPressed) return null
    val primary = buttons.isPrimaryPressed
    val middle = buttons.isTertiaryPressed
    val secondary = buttons.isSecondaryPressed
    val button = when {
        primary && !middle && !secondary -> ProjectMouseButton.PRIMARY
        middle && !primary && !secondary -> ProjectMouseButton.MIDDLE
        secondary && !primary && !middle -> ProjectMouseButton.SECONDARY
        else -> return null
    }
    return ProjectMouseGesture(button, modifiers.isCtrlPressed, modifiers.isAltPressed, modifiers.isShiftPressed)
}

internal fun mappedEditorMode(action: ProjectMouseAction?, fallback: EditorMode): EditorMode = when (action) {
    is ProjectMouseAction.Place -> EditorMode.PLACE
    is ProjectMouseAction.Text -> EditorMode.TEXT
    is ProjectMouseAction.Edit -> when (action.tool) {
        ProjectMouseEditTool.POKE -> EditorMode.INTERACT
        ProjectMouseEditTool.EDIT -> EditorMode.EDIT
        ProjectMouseEditTool.SELECT -> EditorMode.SELECT
        ProjectMouseEditTool.WIRING -> EditorMode.WIRE
        ProjectMouseEditTool.TEXT, ProjectMouseEditTool.MENU -> fallback
    }
    null -> fallback
}
