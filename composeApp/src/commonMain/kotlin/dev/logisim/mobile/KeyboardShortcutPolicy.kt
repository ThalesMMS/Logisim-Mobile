package dev.logisim.mobile

enum class ShortcutPlatform { ANDROID, IOS }

expect val currentShortcutPlatform: ShortcutPlatform

fun isPrimaryShortcutModifier(
    scheme: EditorShortcutScheme,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    platform: ShortcutPlatform = currentShortcutPlatform,
): Boolean = when (scheme) {
    EditorShortcutScheme.CONTROL -> ctrlPressed
    EditorShortcutScheme.PLATFORM -> when (platform) {
        ShortcutPlatform.ANDROID -> ctrlPressed
        ShortcutPlatform.IOS -> metaPressed
    }
}
