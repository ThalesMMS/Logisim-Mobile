package dev.logisim.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSearchCommandsTest {
    @Test
    fun discoversMenuActionsWithTheirAvailabilityPathAndActualCallback() {
        var saved = false
        val menus = listOf(
            EditorMenu("file", "File", listOf(
                EditorToolbarAction("save", "Save", enabled = false) {},
                EditorToolbarAction("divider", "", separator = true) {},
                EditorToolbarAction("save-as", "Save As…") { saved = true },
            )),
            EditorMenu("help", "Help", listOf(EditorToolbarAction("find-action", "Find Action…") {})),
        )
        val actions = editorSearchActions(menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID)
        assertEquals(listOf("file:save", "file:save-as"), actions.map { it.command.id })
        assertFalse(actions.first().command.enabled)
        val saveAs = actions.last()
        assertTrue(saveAs.command.enabled)
        assertEquals("File", saveAs.command.path)
        assertEquals("Ctrl+Shift+S", saveAs.command.shortcut)
        saveAs.action.onClick()
        assertTrue(saved)
    }

    @Test
    fun iosDisplaysTheSelectedPrimaryModifierAndKeepsIdenticalItemIdsSeparate() {
        val menus = listOf(
            EditorMenu("file", "File", listOf(EditorToolbarAction("save-as", "Save As…") {})),
            EditorMenu("tool", "Tool", listOf(EditorToolbarAction("save-as", "Save As…") {})),
        )
        val platform = editorSearchActions(menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.IOS)
        assertEquals(listOf("file:save-as", "tool:save-as"), platform.map { it.command.id })
        assertEquals(listOf("⌘+Shift+S", null), platform.map { it.command.shortcut })
        val control = editorSearchActions(menus, EditorShortcutScheme.CONTROL, ShortcutPlatform.IOS)
        assertEquals("Ctrl+Shift+S", control.first().command.shortcut)
        val custom = editorSearchActions(
            menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.IOS,
            overrides = mapOf(EditorShortcut.SAVE_AS to EditorShortcutBinding("F6")),
        )
        assertEquals("⌘+F6", custom.first().command.shortcut)
    }

    @Test
    fun fullCycleSearchResultShowsTheRemappedShortcut() {
        val menus = listOf(EditorMenu("simulate", "Simulate", listOf(EditorToolbarAction("tick-cycle", "Tick Full Cycle") {})))
        val defaults = editorSearchActions(menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID)
        assertEquals("Ctrl+F9", defaults.single().command.shortcut)

        val remapped = editorSearchActions(
            menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID,
            overrides = mapOf(EditorShortcut.STEP_CLOCK_CYCLE to EditorShortcutBinding("U")),
        )
        assertEquals("Ctrl+U", remapped.single().command.shortcut)
    }
}
