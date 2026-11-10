package dev.logisim.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Frame colors of the editor chrome for Evolution's light (FlatIntelliJ) and dark (FlatDarcula) themes. */
@Immutable
internal data class ChromePalette(
    val bar: Color,
    val panel: Color,
    val heading: Color,
    val divider: Color,
    val selected: Color,
    val selectedBorder: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val warning: Color,
    val paper: Color,
    val surface: Color,
) {
    companion object {
        val Light = ChromePalette(
            bar = Color(0xFFF4F4F4), panel = Color(0xFFFAFAFA), heading = Color(0xFFE6E6E6), divider = Color(0xFFCFCFCF),
            selected = Color(0xFFD3E2F1), selectedBorder = Color(0xFF86A9CE), ink = Color(0xFF1E1E1E), muted = Color(0xFF666666),
            accent = Color(0xFF285580), warning = Color(0xFFFFF0CF), paper = Color(0xFFEFEFEF), surface = Color.White,
        )
        val Dark = ChromePalette(
            bar = Color(0xFF3C3F41), panel = Color(0xFF313335), heading = Color(0xFF45494A), divider = Color(0xFF555555),
            selected = Color(0xFF2F4F6F), selectedBorder = Color(0xFF5A80A8), ink = Color(0xFFDDDDDD), muted = Color(0xFFA0A0A0),
            accent = Color(0xFF8DB6E2), warning = Color(0xFF5A4A1E), paper = Color(0xFF2B2B2B), surface = Color(0xFF313335),
        )
    }
}

internal val LocalChromePalette = staticCompositionLocalOf { ChromePalette.Light }
internal val LocalEditorDarkTheme = staticCompositionLocalOf { false }

internal val chromePalette: ChromePalette
    @Composable @ReadOnlyComposable get() = LocalChromePalette.current

/** Resolves the theme preference against the platform's current light/dark mode. */
@Composable
internal fun EditorThemeMode.resolveDark(): Boolean = when (this) {
    EditorThemeMode.SYSTEM -> isSystemInDarkTheme()
    EditorThemeMode.LIGHT -> false
    EditorThemeMode.DARK -> true
}

@Composable
internal fun LogisimTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) ChromePalette.Dark else ChromePalette.Light
    CompositionLocalProvider(LocalChromePalette provides palette, LocalEditorDarkTheme provides dark) {
        MaterialTheme(colorScheme = logisimColorScheme(dark), content = content)
    }
}

/** Material colors of the chrome; menus, dialogs and sheets draw from these in both modes. */
internal fun logisimColorScheme(dark: Boolean): ColorScheme {
    val palette = if (dark) ChromePalette.Dark else ChromePalette.Light
    return if (dark) darkColorScheme(
        primary = palette.accent, secondary = palette.accent, surface = palette.surface, background = palette.paper,
        onSurface = palette.ink, onBackground = palette.ink, surfaceContainerHigh = palette.panel,
        surfaceContainer = palette.panel, surfaceContainerLow = palette.paper,
    ) else lightColorScheme(
        primary = palette.accent, secondary = palette.accent, surface = palette.surface, background = palette.paper,
        onSurface = palette.ink, onBackground = palette.ink,
    )
}

/** Personal preferences that editor tools read and change in place, like Evolution's AppPreferences monitors. */
internal class EditorPreferencesAccess(val preferences: EditorPreferences, val onChange: (EditorPreferences) -> Unit)

internal val LocalEditorPreferences = staticCompositionLocalOf<EditorPreferencesAccess?> { null }

/** The value characters of the editor preferences, or Evolution's defaults outside the editor. */
@androidx.compose.runtime.Composable
internal fun currentValueCharacters(): dev.logisim.mobile.core.ValueCharacters =
    LocalEditorPreferences.current?.preferences?.valueCharacters ?: dev.logisim.mobile.core.ValueCharacters.Default
