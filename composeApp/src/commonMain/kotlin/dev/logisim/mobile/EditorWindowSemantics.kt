package dev.logisim.mobile

import androidx.compose.ui.Modifier

/** Dialogs and sheets have separate semantics roots on Android. */
internal expect fun Modifier.exportEditorTestTags(): Modifier

/**
 * Whether editor menus take keyboard focus. Android menus do, so Back closes them; on iOS a
 * focusable popup over the editor was dismissed as soon as it appeared.
 */
internal expect val editorMenusTakeFocus: Boolean
