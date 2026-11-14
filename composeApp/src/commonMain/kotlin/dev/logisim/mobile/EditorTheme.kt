package dev.logisim.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Frame colors of the editor chrome for Evolution's light (FlatIntelliJ) and dark (FlatDarcula) themes. Two values
 * depart from those themes for WCAG AA on a phone: the dark muted text is lighter (#B8B8B8, 4.5:1 on every chrome
 * surface) and the selected border is the accent (3:1 against the bar and the selected tint), and [warningInk] and
 * [outline] are text and boundary colors the desktop themes leave to Swing.
 */
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
    /** Warning text, such as diagnostics warnings; [warning] is its background tint. */
    val warningInk: Color,
    /** Component boundaries: text field borders and unchecked switches, 3:1 on every surface. */
    val outline: Color,
    /** Passing results, such as test vector rows: AA text contrast on every surface (C62). */
    val passInk: Color = Color(0xFF08792F),
    /** The trace cursor line, 3:1 on the panel (C62). */
    val traceCursor: Color = Color(0xFF8C2999),
) {
    companion object {
        val Light = ChromePalette(
            bar = Color(0xFFF4F4F4), panel = Color(0xFFFAFAFA), heading = Color(0xFFE6E6E6), divider = Color(0xFFCFCFCF),
            selected = Color(0xFFD3E2F1), selectedBorder = Color(0xFF285580), ink = Color(0xFF1E1E1E), muted = Color(0xFF666666),
            accent = Color(0xFF285580), warning = Color(0xFFFFF0CF), paper = Color(0xFFEFEFEF), surface = Color.White,
            warningInk = Color(0xFF8A5A00), outline = Color(0xFF7A7A7A),
            passInk = Color(0xFF08792F), traceCursor = Color(0xFF8C2999),
        )
        val Dark = ChromePalette(
            bar = Color(0xFF3C3F41), panel = Color(0xFF313335), heading = Color(0xFF45494A), divider = Color(0xFF555555),
            selected = Color(0xFF2F4F6F), selectedBorder = Color(0xFF8DB6E2), ink = Color(0xFFDDDDDD), muted = Color(0xFFB8B8B8),
            accent = Color(0xFF8DB6E2), warning = Color(0xFF5A4A1E), paper = Color(0xFF2B2B2B), surface = Color(0xFF313335),
            warningInk = Color(0xFFE0B050), outline = Color(0xFF999999),
            passInk = Color(0xFF6CC488), traceCursor = Color(0xFFD08AE0),
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
        // Text outside a Surface (the side panels, sheet headings) takes the chrome ink, not Compose's default
        // black, which is unreadable on the dark chrome.
        MaterialTheme(colorScheme = logisimColorScheme(dark)) {
            CompositionLocalProvider(LocalContentColor provides palette.ink, content = content)
        }
    }
}

/**
 * Material colors of the chrome. Every slot comes from the chrome palette, so no Material baseline lavender or pink
 * reaches dialogs (surfaceContainerHigh), menus (surfaceContainer), sheets (surfaceContainerLow), tonal buttons and
 * selections (secondaryContainer), unselected memory words (surfaceVariant) or warnings (tertiary). Material's error
 * reds and the black scrim are kept. surfaceTint is the surface, so tonal elevation does not tint sheets with the accent.
 */
internal fun logisimColorScheme(dark: Boolean): ColorScheme {
    val palette = if (dark) ChromePalette.Dark else ChromePalette.Light
    val inverse = if (dark) ChromePalette.Light else ChromePalette.Dark
    val baseline = if (dark) darkColorScheme() else lightColorScheme()
    return baseline.copy(
        primary = palette.accent, onPrimary = palette.surface,
        primaryContainer = palette.selected, onPrimaryContainer = palette.ink, inversePrimary = inverse.accent,
        secondary = palette.accent, onSecondary = palette.surface,
        secondaryContainer = palette.selected, onSecondaryContainer = palette.ink,
        tertiary = palette.warningInk, onTertiary = palette.surface,
        tertiaryContainer = palette.warning, onTertiaryContainer = palette.ink,
        background = palette.paper, onBackground = palette.ink,
        surface = palette.surface, onSurface = palette.ink,
        surfaceVariant = palette.heading, onSurfaceVariant = palette.muted, surfaceTint = palette.surface,
        inverseSurface = inverse.surface, inverseOnSurface = inverse.ink,
        outline = palette.outline, outlineVariant = palette.divider,
        surfaceBright = if (dark) palette.heading else palette.surface, surfaceDim = if (dark) palette.paper else palette.heading,
        surfaceContainerLowest = palette.surface, surfaceContainerLow = palette.paper, surfaceContainer = palette.panel,
        surfaceContainerHigh = palette.panel, surfaceContainerHighest = palette.heading,
        primaryFixed = palette.selected, primaryFixedDim = palette.selected, onPrimaryFixed = palette.ink,
        onPrimaryFixedVariant = palette.accent,
        secondaryFixed = palette.selected, secondaryFixedDim = palette.selected, onSecondaryFixed = palette.ink,
        onSecondaryFixedVariant = palette.accent,
        tertiaryFixed = palette.warning, tertiaryFixedDim = palette.warning, onTertiaryFixed = palette.ink,
        onTertiaryFixedVariant = palette.warningInk,
    )
}

/**
 * The cue of the current or selected row in a list: the selected tint and a 3 dp accent bar on the leading edge.
 * The accent reaches 3:1 against the tint and the panels in both themes; the tint alone is about 1.2:1.
 */
internal fun Modifier.leadingSelectionCue(selected: Boolean, accent: Color, tint: Color): Modifier =
    if (!selected) this else background(tint).drawBehind {
        val width = SelectionCueWidth.toPx()
        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
        drawRect(accent, topLeft = Offset(left, 0f), size = Size(width, size.height))
    }

/** [leadingSelectionCue] in the chrome palette's accent and selected tint. */
@Composable
internal fun Modifier.leadingSelectionCue(selected: Boolean): Modifier =
    leadingSelectionCue(selected, chromePalette.accent, chromePalette.selected)

internal val SelectionCueWidth = 3.dp

/** Personal preferences that editor tools read and change in place, like Evolution's AppPreferences monitors. */
internal class EditorPreferencesAccess(val preferences: EditorPreferences, val onChange: (EditorPreferences) -> Unit)

internal val LocalEditorPreferences = staticCompositionLocalOf<EditorPreferencesAccess?> { null }

/** The value characters of the editor preferences, or Evolution's defaults outside the editor. */
@androidx.compose.runtime.Composable
internal fun currentValueCharacters(): dev.logisim.mobile.core.ValueCharacters =
    LocalEditorPreferences.current?.preferences?.valueCharacters ?: dev.logisim.mobile.core.ValueCharacters.Default
