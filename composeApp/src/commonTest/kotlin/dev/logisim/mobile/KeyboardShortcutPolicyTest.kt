package dev.logisim.mobile

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardShortcutPolicyTest {
    @Test
    fun androidPlatformSchemeUsesControl() {
        assertModifierStates(EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID,
            expected = listOf(false, true, false, true))
    }

    @Test
    fun iosPlatformSchemeUsesMeta() {
        assertModifierStates(EditorShortcutScheme.PLATFORM, ShortcutPlatform.IOS,
            expected = listOf(false, false, true, true))
    }

    @Test
    fun androidControlSchemeUsesControlIncludingWhenMetaIsAlsoPressed() {
        assertModifierStates(EditorShortcutScheme.CONTROL, ShortcutPlatform.ANDROID,
            expected = listOf(false, true, false, true))
    }

    @Test
    fun iosControlSchemeUsesControlIncludingWhenMetaIsAlsoPressed() {
        assertModifierStates(EditorShortcutScheme.CONTROL, ShortcutPlatform.IOS,
            expected = listOf(false, true, false, true))
    }

    @Test
    fun selectedModifierKeepsShiftUndoAndRedoCommands() {
        val shortcut = isPrimaryShortcutModifier(EditorShortcutScheme.PLATFORM, false, true, ShortcutPlatform.IOS)
        val undo = editorShortcutBinding(Key.Z, shortcut, shift = false, alt = false)!!
        val redo = editorShortcutBinding(Key.Z, shortcut, shift = true, alt = false)!!
        assertEquals(CanvasCommand.UNDO, editorShortcutFor(undo, emptyMap())?.canvasCommand)
        assertEquals(CanvasCommand.REDO, editorShortcutFor(redo, emptyMap())?.canvasCommand)
        assertEquals(CanvasCommand.REDO, editorShortcutFor(EditorShortcutBinding("Y"), emptyMap())?.canvasCommand)
        val controlOnly = isPrimaryShortcutModifier(EditorShortcutScheme.CONTROL, false, true, ShortcutPlatform.IOS)
        assertNull(editorShortcutBinding(Key.C, controlOnly, shift = false, alt = false))
    }

    private fun assertModifierStates(
        scheme: EditorShortcutScheme,
        platform: ShortcutPlatform,
        expected: List<Boolean>,
    ) {
        val modifierStates = listOf(false to false, true to false, false to true, true to true)
        for ((index, modifiers) in modifierStates.withIndex()) {
            val (ctrlPressed, metaPressed) = modifiers
            assertEquals(expected[index],
                isPrimaryShortcutModifier(scheme, ctrlPressed, metaPressed, platform),
                "$scheme on $platform with Ctrl=$ctrlPressed and Meta=$metaPressed")
        }
    }
}
