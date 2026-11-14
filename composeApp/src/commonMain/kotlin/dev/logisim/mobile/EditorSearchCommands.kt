package dev.logisim.mobile

internal data class EditorSearchAction(val command: SearchCommand, val action: EditorToolbarAction)

internal fun editorSearchActions(
    menus: List<EditorMenu>,
    scheme: EditorShortcutScheme,
    platform: ShortcutPlatform = currentShortcutPlatform,
    overrides: Map<EditorShortcut, EditorShortcutBinding> = emptyMap(),
): List<EditorSearchAction> = menus.flatMap { menu ->
    menu.items.mapNotNull { action ->
        if (action.separator || action.label.isBlank() || menu.id == "help" && action.id == "find-action") {
            return@mapNotNull null
        }
        val id = "${menu.id}:${action.id}"
        EditorSearchAction(
            SearchCommand(id, action.label, listOf(action.description, action.id), action.enabled,
                path = menu.title, shortcut = menuActionShortcut(id, scheme, platform, overrides)),
            action,
        )
    }
}.distinctBy { it.command.id }

private fun menuActionShortcut(
    id: String,
    scheme: EditorShortcutScheme,
    platform: ShortcutPlatform,
    overrides: Map<EditorShortcut, EditorShortcutBinding>,
): String? {
    val editorShortcut = when (id) {
        "file:new" -> EditorShortcut.NEW
        "file:open" -> EditorShortcut.OPEN
        "file:save" -> EditorShortcut.SAVE
        "file:save-as" -> EditorShortcut.SAVE_AS
        "simulate:run" -> EditorShortcut.RUN
        "simulate:event" -> EditorShortcut.STEP_EVENT
        "simulate:step" -> EditorShortcut.STEP_CLOCK
        "simulate:tick-cycle" -> EditorShortcut.STEP_CLOCK_CYCLE
        "simulate:automatic-ticks" -> EditorShortcut.AUTOMATIC_TICKS
        "simulate:reset" -> EditorShortcut.RESET
        "edit:undo" -> EditorShortcut.UNDO
        "edit:redo" -> EditorShortcut.REDO
        "edit:cut" -> EditorShortcut.CUT
        "edit:copy" -> EditorShortcut.COPY
        "edit:paste" -> EditorShortcut.PASTE
        "edit:duplicate" -> EditorShortcut.DUPLICATE
        "edit:placement-auto-label" -> EditorShortcut.PLACEMENT_AUTO_LABEL
        "edit:select-all" -> EditorShortcut.SELECT_ALL
        "view:fit-selection" -> EditorShortcut.FIT_SELECTION
        // Export drawing sits in File, as Evolution's Export Image (editorToolMenu).
        "file:project-tool-visual-export" -> EditorShortcut.FILE_EXPORT
        else -> null
    }
    if (editorShortcut != null) return effectiveEditorShortcut(editorShortcut, overrides)
        .takeUnless { it.isUnassigned }?.label(scheme, platform)
    return when (id) {
        "edit:delete" -> "Delete"
        "view:fit" -> "0"
        "view:zoom-in" -> "+"
        "view:zoom-out" -> "−"
        else -> null
    }
}
