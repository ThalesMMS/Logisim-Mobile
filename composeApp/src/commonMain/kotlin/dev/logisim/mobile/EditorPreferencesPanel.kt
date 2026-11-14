package dev.logisim.mobile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.TickFrequencies
import dev.logisim.mobile.core.formatTickFrequency
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer

data class EditorPreferencesCapabilities(
    val gateShape: Boolean = true,
    val shortcutScheme: Boolean = true,
)

@Composable
fun EditorPreferencesPanel(
    preferences: EditorPreferences,
    onChange: (EditorPreferences) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    capabilities: EditorPreferencesCapabilities = EditorPreferencesCapabilities(),
    onOpenProjectOptions: (() -> Unit)? = null,
    projectTemplates: ProjectTemplateStore? = null,
    templatePicker: DocumentAssetActions? = null,
) {
    val strings = rememberLocalizer()
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Messages.PreferencesTitle), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(strings(Messages.PreferencesHelp),
            style = MaterialTheme.typography.bodySmall)
        // Grouped like Evolution's PreferencesFrame tabs (International, Window, Simulation, Layout, FPGA), each a heading
        // TalkBack can jump between (C106). The order only changes where a row appears; no preference key changes.
        PreferenceSection(strings("preferences_section_international"), divider = false)
        PreferenceChoice(strings(Messages.PreferencesLanguage), preferences.locale, EditorLocale.available.map { locale ->
            val label = when (locale) {
                EditorLocale.SYSTEM -> strings(Messages.PreferencesSystemLanguage)
                EditorLocale.PORTUGUESE -> strings(Messages.PreferencesPortuguese)
                EditorLocale.ENGLISH -> strings(Messages.PreferencesEnglish)
                else -> checkNotNull(locale.language).nativeName
            }
            locale to label
        }) { onChange(preferences.copy(locale = it)) }
        if (capabilities.gateShape) {
            PreferenceChoice(strings(Messages.PreferencesGateShapes), preferences.gateShape, listOf(
                EditorGateShape.ANSI to strings(Messages.PreferencesAnsi), EditorGateShape.IEC to strings(Messages.PreferencesIec),
                EditorGateShape.DIN40700 to strings(Messages.PreferencesDin40700),
            )) { onChange(preferences.copy(gateShape = it)) }
        }
        PreferenceSection(strings("preferences_section_window"))
        val dark = LocalEditorDarkTheme.current
        PreferenceChoice(strings(Messages.PreferencesTheme), preferences.themeMode, listOf(
            EditorThemeMode.SYSTEM to strings(Messages.PreferencesThemeSystem),
            EditorThemeMode.LIGHT to strings(Messages.PreferencesThemeLight),
            EditorThemeMode.DARK to strings(Messages.PreferencesThemeDark),
        ), tag = "preferences-theme") { onChange(preferences.copy(themeMode = it)) }
        // WindowOptions edits the code editor theme of the mode in use.
        PreferenceChoice(strings(Messages.PreferencesEditorTheme), preferences.editorTheme(dark),
            HdlEditorTheme.entries.map { it to it.label }, tag = "preferences-editor-theme") {
            onChange(preferences.withEditorTheme(dark, it))
        }
        PreferenceToggle(strings(Messages.PreferencesShowGrid), preferences.showGrid) { onChange(preferences.copy(showGrid = it)) }
        PreferenceToggle(strings(Messages.PreferencesShowTickRate), preferences.showTickRate) {
            onChange(preferences.copy(showTickRate = it))
        }
        PreferenceToggle(strings(Messages.PreferencesShowHeaderBar), preferences.showHeaderBar) {
            onChange(preferences.copy(showHeaderBar = it))
        }
        PreferenceChoice(strings(Messages.PreferencesToolbarPlacement), preferences.toolbarPlacement, listOf(
            EditorToolbarPlacement.TOP to strings(Messages.PreferencesToolbarTop),
            EditorToolbarPlacement.BOTTOM to strings(Messages.PreferencesToolbarBottom),
            EditorToolbarPlacement.LEFT to strings(Messages.PreferencesToolbarLeft),
            EditorToolbarPlacement.RIGHT to strings(Messages.PreferencesToolbarRight),
            EditorToolbarPlacement.DOWN_MIDDLE to strings(Messages.PreferencesToolbarDownMiddle),
            EditorToolbarPlacement.HIDDEN to strings(Messages.PreferencesToolbarHidden),
        )) { onChange(preferences.copy(toolbarPlacement = it)) }
        Text(strings(Messages.PreferencesToolbarCompact), style = MaterialTheme.typography.bodySmall)
        CanvasColorPreferences(preferences, onChange, dark)
        PreferenceSection(strings("preferences_section_editing"))
        PreferenceToggle(strings(Messages.PreferencesSnapGrid), preferences.snapToGrid) { onChange(preferences.copy(snapToGrid = it)) }
        PreferenceToggle(strings(Messages.PreferencesShowPlacementPreview), preferences.showPlacementPreview) {
            onChange(preferences.copy(showPlacementPreview = it))
        }
        PreferenceChoice(strings(Messages.PreferencesAfterPlacement), preferences.afterPlacement, listOf(
            EditorAfterPlacement.EDIT to strings(Messages.PreferencesAfterPlacementEdit),
            EditorAfterPlacement.KEEP_TOOL to strings(Messages.PreferencesAfterPlacementKeepTool),
        )) { onChange(preferences.copy(afterPlacement = it)) }
        if (capabilities.shortcutScheme) {
            PreferenceChoice(strings(Messages.PreferencesShortcutKey), preferences.shortcutScheme, listOf(
                EditorShortcutScheme.PLATFORM to strings(Messages.PreferencesPlatformShortcut), EditorShortcutScheme.CONTROL to strings(Messages.PreferencesControlKey),
            )) { onChange(preferences.copy(shortcutScheme = it)) }
            PreferenceToggle(strings(Messages.PreferencesSearchDoubleShift), preferences.searchDoubleShift,
                "preferences-search-double-shift") {
                onChange(preferences.copy(searchDoubleShift = it))
            }
            EditorShortcutPreferences(preferences, onChange)
        }
        PreferenceSection(strings("preferences_section_simulation"))
        PreferenceChoice(strings(Messages.PreferencesSignalRadix), preferences.signalRadix, listOf(
            EditorSignalRadix.BINARY to strings(Messages.PreferencesBinary), EditorSignalRadix.HEXADECIMAL to strings(Messages.PreferencesHexadecimal),
            EditorSignalRadix.UNSIGNED_DECIMAL to strings(Messages.PreferencesUnsignedDecimal), EditorSignalRadix.SIGNED_DECIMAL to strings(Messages.PreferencesSignedDecimal),
        )) { onChange(preferences.copy(signalRadix = it)) }
        val wireRadixChoices = WirePokeRadix.entries.map { it to strings(it.label) }
        PreferenceChoice(strings(Messages.PreferencesWirePokePrimaryRadix), preferences.wirePokePrimaryRadix,
            wireRadixChoices, tag = "preferences-wire-poke-primary") { onChange(preferences.copy(wirePokePrimaryRadix = it)) }
        PreferenceChoice(strings(Messages.PreferencesWirePokeSecondaryRadix), preferences.wirePokeSecondaryRadix,
            listOf(null to strings(Messages.PreferencesWirePokeNoSecondRadix)) + wireRadixChoices, tag = "preferences-wire-poke-secondary") {
            onChange(preferences.copy(wirePokeSecondaryRadix = it))
        }
        PreferenceToggle(strings(Messages.PreferencesHighContrast), preferences.highContrastSignals) { onChange(preferences.copy(highContrastSignals = it)) }
        ValueCharacterPreferences(preferences, onChange)
        PreferenceSection(strings(Messages.PreferencesSectionLayout))
        PreferenceToggle(strings(Messages.PreferencesAttributeHalo), preferences.attributeHalo, "preferences-attribute-halo") {
            onChange(preferences.copy(attributeHalo = it))
        }
        PreferenceToggle(strings(Messages.PreferencesComponentTips), preferences.componentTips, "preferences-component-tips") {
            onChange(preferences.copy(componentTips = it))
        }
        Text(strings(Messages.PreferencesComponentTipsHelp), style = MaterialTheme.typography.bodySmall)
        PreferenceChoice(strings(Messages.PreferencesPinAppearance), preferences.pinAppearance, listOf(
            EditorPinAppearance.DOT_SMALL to strings(Messages.PreferencesPinDotSmall),
            EditorPinAppearance.DOT_MEDIUM to strings(Messages.PreferencesPinDotMedium),
            EditorPinAppearance.DOT_BIG to strings(Messages.PreferencesPinDotBig),
            EditorPinAppearance.DOT_BIGGER to strings(Messages.PreferencesPinDotBigger),
        ), tag = "preferences-pin-appearance") { onChange(preferences.copy(pinAppearance = it)) }
        PreferenceToggle(strings(Messages.PreferencesKmapLined), preferences.kmapLinedStyle, "preferences-kmap-lined") {
            onChange(preferences.copy(kmapLinedStyle = it))
        }
        PreferenceToggle(strings(Messages.PreferencesAppearanceShowGrid), preferences.appearanceShowGrid, "preferences-appearance-grid") {
            onChange(preferences.copy(appearanceShowGrid = it))
        }
        PreferenceChoice(strings(Messages.PreferencesDefaultAppearance), preferences.defaultAppearance, listOf(
            EditorDefaultAppearance.CLASSIC to strings(Messages.PreferencesAppearanceClassic),
            EditorDefaultAppearance.FPGA to strings(Messages.PreferencesAppearanceHolycross),
            EditorDefaultAppearance.EVOLUTION to strings(Messages.PreferencesAppearanceEvolution),
        ), tag = "preferences-default-appearance") { onChange(preferences.copy(defaultAppearance = it)) }
        PreferenceToggle(strings(Messages.PreferencesNewIoShapes), preferences.newInputOutputShapes, "preferences-new-io-shapes") {
            onChange(preferences.copy(newInputOutputShapes = it))
        }
        PreferenceToggle(strings(Messages.PreferencesNamedBoxesFixed), preferences.namedCircuitBoxesFixedSize, "preferences-named-boxes-fixed") {
            onChange(preferences.copy(namedCircuitBoxesFixedSize = it))
        }
        Text(strings(Messages.PreferencesCreationScope), style = MaterialTheme.typography.bodySmall)
        PreferenceToggle(strings(Messages.PreferencesRemoveUnusedLibraries), preferences.removeUnusedLibraries, "preferences-remove-unused-libraries") {
            onChange(preferences.copy(removeUnusedLibraries = it))
        }
        // Evolution Preferences → FPGA: HDL generation and DRC report options.
        PreferenceSection(strings("preferences_fpga_title"))
        PreferenceChoice(strings("preferences_fpga_hdl_type"), preferences.hdlType, listOf(
            EditorHdlType.VHDL to "VHDL", EditorHdlType.VERILOG to "Verilog", EditorHdlType.NONE to strings("preferences_fpga_hdl_none"),
        ), tag = "preferences-fpga-hdl-type") { onChange(preferences.copy(hdlType = it)) }
        PreferenceToggle(strings("preferences_fpga_vhdl_upper_case"), preferences.fpga.vhdlKeywordsUpperCase, "preferences-fpga-vhdl-upper-case") {
            onChange(preferences.copy(fpga = preferences.fpga.copy(vhdlKeywordsUpperCase = it)))
        }
        PreferenceToggle(strings("preferences_fpga_suppress_gated_clock"), preferences.fpga.suppressGatedClockWarnings, "preferences-fpga-suppress-gated-clock") {
            onChange(preferences.copy(fpga = preferences.fpga.copy(suppressGatedClockWarnings = it)))
        }
        PreferenceToggle(strings("preferences_fpga_suppress_open_pins"), preferences.fpga.suppressOpenPinWarnings, "preferences-fpga-suppress-open-pins") {
            onChange(preferences.copy(fpga = preferences.fpga.copy(suppressOpenPinWarnings = it)))
        }
        PreferenceSection(strings(Messages.PreferencesNewProjects))
        if (projectTemplates != null && templatePicker != null) ProjectTemplateSettings(projectTemplates, templatePicker)
        // Evolution's TICK_FREQUENCY default comes from the Simulate menu's list; it applies on selection like the
        // other preferences. A stored rate outside the list (a hand-edited 3 Hz) stays listed, selected, as custom.
        val defaultFrequency = preferences.defaultTickFrequencyHz
        PreferenceChoice(strings("preferences_default_tick_frequency"),
            TickFrequencies.choicesWith(defaultFrequency).firstOrNull { TickFrequencies.same(it.hz, defaultFrequency) }?.hz ?: defaultFrequency,
            TickFrequencies.choicesWith(defaultFrequency).map { choice ->
                val rate = formatTickFrequency(choice.hz)
                choice.hz to if (choice.custom) strings("simulation_tick_frequency_custom", "value" to rate) else rate
            }, tag = "preferences-default-frequency") { onChange(preferences.copy(defaultTickFrequencyHz = it)) }
        Text(strings(Messages.PreferencesFrequencyScope), style = MaterialTheme.typography.bodySmall)
        onOpenProjectOptions?.let { open ->
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.PreferencesProjectOptions)) }
        }
        HorizontalDivider()
        // It resets every editor preference and cannot be undone, so it asks first. It is also the recovery when the
        // stored preferences could not be read, which the message covers; confirming always calls onRestoreDefaults.
        var confirmRestore by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmRestore = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("preferences-restore-defaults")) {
            Text(strings(Messages.PreferencesRestoreDefaults))
        }
        if (confirmRestore) ConfirmActionDialog(
            title = strings("preferences_restore_defaults_title"),
            text = strings("preferences_restore_defaults_confirm"),
            confirmLabel = strings("confirm_restore"),
            onConfirm = onRestoreDefaults,
            onDismiss = { confirmRestore = false },
        )
    }
}

/** A confirmation for an action that cannot be undone: a destructive confirm and Cancel. */
@Composable
internal fun ConfirmActionDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val strings = rememberLocalizer()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exportEditorTestTags().testTag("confirm-dialog"),
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-dialog-confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-dialog-cancel")) {
                Text(strings(Messages.ActionCancel))
            }
        },
    )
}

/** A preference group's title, after a divider: a heading, so TalkBack moves between groups by heading. */
@Composable
private fun PreferenceSection(title: String, divider: Boolean = true) {
    if (divider) HorizontalDivider()
    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.semantics { heading() })
}

@Composable
private fun PreferenceToggle(title: String, checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .then(if (tag == null) Modifier else Modifier.testTag(tag)),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> PreferenceChoice(title: String, current: T, choices: List<Pair<T, String>>, tag: String? = null, onChange: (T) -> Unit) {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    val currentIndex = choices.indexOfFirst { it.first == current }
    val currentLabel = choices.getOrNull(currentIndex)?.second.orEmpty()
    val menuScroll = rememberScrollState()
    val itemHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    // Like PrefOptionList's combo box: a drop-down list whose open menu shows the current value checked and in view.
    LaunchedEffect(expanded) { if (expanded && currentIndex > 2) menuScroll.scrollTo((currentIndex - 2) * itemHeight) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.DropdownList, onClickLabel = strings("preferences_choice_change")) { expanded = true }
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            // The "Title: value" description is what the end-to-end harnesses read. It already holds the value, so no
            // state description repeats it: TalkBack says "Theme: Dark, drop-down list" once.
            .semantics(mergeDescendants = true) {
                contentDescription = strings(Messages.PreferencesChoiceDescription, "title" to title, "value" to currentLabel)
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(currentLabel, color = MaterialTheme.colorScheme.primary)
            DropDownArrow(MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, scrollState = menuScroll) {
            choices.forEachIndexed { index, (value, label) ->
                val chosen = value == current
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; if (!chosen) onChange(value) },
                    leadingIcon = { SelectedMark(chosen) },
                    modifier = Modifier.semantics { selected = chosen }
                        .then(if (tag == null) Modifier else Modifier.testTag("$tag-option-$index")))
            }
        }
    }
}

/** Preferences → Canvas colors: the roles grouped as in Evolution's Window, Simulation, FPGA and analysis options. */
internal object CanvasColorChoice {
    val groups: List<Pair<String, List<EditorColor>>> = listOf(
        "preferences_colors_group_canvas" to listOf(EditorColor.CANVAS, EditorColor.GRID_BACKGROUND, EditorColor.GRID_DOT,
            EditorColor.GRID_ZOOMED_DOT, EditorColor.COMPONENT, EditorColor.COMPONENT_SECONDARY, EditorColor.COMPONENT_GHOST,
            EditorColor.COMPONENT_ICON),
        "preferences_colors_group_signals" to listOf(EditorColor.SIGNAL_HIGH, EditorColor.SIGNAL_LOW, EditorColor.SIGNAL_UNKNOWN,
            EditorColor.SIGNAL_ERROR, EditorColor.SIGNAL_FLOATING, EditorColor.SIGNAL_BUS, EditorColor.STROKE, EditorColor.WIDTH_ERROR,
            EditorColor.WIDTH_ERROR_CAPTION, EditorColor.WIDTH_ERROR_HIGHLIGHT, EditorColor.WIDTH_ERROR_BACKGROUND,
            EditorColor.CLOCK_FREQUENCY),
        "preferences_colors_group_fpga" to listOf(EditorColor.FPGA_DEFINE, EditorColor.FPGA_DEFINE_HIGHLIGHT, EditorColor.FPGA_DEFINE_RESIZE,
            EditorColor.FPGA_DEFINE_MOVE, EditorColor.FPGA_MAPPED, EditorColor.FPGA_SELECTED_MAPPED, EditorColor.FPGA_SELECTABLE_MAPPED,
            EditorColor.FPGA_SELECT),
        "preferences_colors_group_text" to listOf(EditorColor.TEXT_TOOL),
        "preferences_colors_group_analysis" to listOf(EditorColor.KMAP_CELL_TEXT, EditorColor.TABLE_CURSOR, EditorColor.TABLE_HIGHLIGHT,
            EditorColor.TABLE_SELECTION),
    )

    /** The stored colors of [role]: TEXT_TOOL_COLOR is one light-reference preference shared by both themes (#340). */
    private fun roleColors(preferences: EditorPreferences, dark: Boolean, role: EditorColor) =
        if (role == EditorColor.TEXT_TOOL) preferences.colors else preferences.customColors(dark)

    fun stored(preferences: EditorPreferences, dark: Boolean, role: EditorColor): Int? = roleColors(preferences, dark, role)[role]

    fun customized(preferences: EditorPreferences, dark: Boolean, role: EditorColor): Boolean = role in roleColors(preferences, dark, role)

    /** The built-in color of the theme: Evolution's DARK_* value in the dark theme, else the light default. */
    fun defaultRgb(preferences: EditorPreferences, dark: Boolean, role: EditorColor): Int =
        DarkEditorColors[role]?.takeIf { dark } ?: (role.defaultColor(preferences.highContrastSignals).toArgb() and 0xFFFFFF)

    private fun display(dark: Boolean, role: EditorColor, rgb: Int) =
        if (role == EditorColor.TEXT_TOOL && dark) ThemeTextColor.invertRgb(rgb) else rgb

    /** The color the theme shows; the dark theme shows TEXT_TOOL luma-inverted. */
    fun shown(preferences: EditorPreferences, dark: Boolean, role: EditorColor): Int =
        display(dark, role, stored(preferences, dark, role) ?: defaultRgb(preferences, dark, role))

    fun shownDefault(preferences: EditorPreferences, dark: Boolean, role: EditorColor): Int =
        display(dark, role, defaultRgb(preferences, dark, role))

    /**
     * Stores a [picked] color as shown. For the dark TEXT_TOOL, TextColorChooser keeps the stored RGB when the shown one
     * is unchanged and otherwise stores the inversion of the pick. Picking the color a default role already shows (the
     * Current swatch, or Apply without a change) stores nothing, so the role keeps following its default.
     */
    fun pick(preferences: EditorPreferences, dark: Boolean, role: EditorColor, picked: Int): EditorPreferences {
        if (!customized(preferences, dark, role) && picked == shown(preferences, dark, role)) return preferences
        val value = if (role == EditorColor.TEXT_TOOL && dark) {
            if (picked == shown(preferences, dark, role)) stored(preferences, dark, role) ?: defaultRgb(preferences, dark, role)
            else ThemeTextColor.invertRgb(picked)
        } else picked
        return preferences.withRoleColor(dark, role, value)
    }

    fun restore(preferences: EditorPreferences, dark: Boolean, role: EditorColor): EditorPreferences = preferences.withRoleColor(dark, role, null)
}

@Composable
private fun CanvasColorPreferences(preferences: EditorPreferences, onChange: (EditorPreferences) -> Unit, dark: Boolean) {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    val sectionState = strings(if (open) "preferences_section_expanded" else "preferences_section_collapsed")
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("preferences-colors")
        .semantics { stateDescription = sectionState }) {
        Text(strings(Messages.PreferencesColors))
        EditorIconGlyph(if (open) EditorIcon.COLLAPSE else EditorIcon.EXPAND, Modifier.padding(start = 6.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
    if (!open) return

    // Like ThemeColorPreference, the rows edit the colors of the theme in use.
    val custom = preferences.customColors(dark)
    var editing by remember { mutableStateOf<EditorColor?>(null) }
    var confirmRestoreAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("preferences-colors-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(strings(if (dark) Messages.PreferencesColorsThemeDark else Messages.PreferencesColorsThemeLight) + " " +
            strings("preferences_colors_help"), style = MaterialTheme.typography.bodySmall)
        for ((group, roles) in CanvasColorChoice.groups) {
            Text(strings(group), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            for (role in roles) CanvasColorRow(role.label(strings), role, CanvasColorChoice.shown(preferences, dark, role),
                CanvasColorChoice.customized(preferences, dark, role), onEdit = { editing = role },
                onRestore = { onChange(CanvasColorChoice.restore(preferences, dark, role)) })
        }
        Text(strings("preferences_colors_group_kmap"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        KmapColorGrid(preferences, dark) { editing = it }
        TextButton(
            onClick = { if (custom.size > 1) confirmRestoreAll = true else onChange(preferences.withCustomColors(dark, emptyMap())) },
            enabled = custom.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("preferences-colors-restore"),
        ) { Text(strings("preferences_colors_restore_theme")) }
    }
    if (confirmRestoreAll) ConfirmActionDialog(
        title = strings("preferences_colors_restore_confirm_title"),
        // The light theme's map also holds TEXT_TOOL_COLOR, which both themes share; the dark theme's does not. The light
        // text names it only when it is one of the colors being restored.
        text = strings(when {
            dark -> "preferences_colors_restore_confirm_dark"
            EditorColor.TEXT_TOOL in custom -> "preferences_colors_restore_confirm_light"
            else -> "preferences_colors_restore_confirm_light_plain"
        }, "count" to custom.size),
        confirmLabel = strings("confirm_restore"),
        onConfirm = { onChange(preferences.withCustomColors(dark, emptyMap())) },
        onDismiss = { confirmRestoreAll = false },
    )
    editing?.let { role ->
        ColorPickerDialog(
            title = role.label(strings),
            initial = ColorPickerModel(CanvasColorChoice.shown(preferences, dark, role)),
            default = ColorPickerModel(CanvasColorChoice.shownDefault(preferences, dark, role)),
            alphaEnabled = false,
            // The Current swatch or an unchanged Apply on a default role is not a change.
            onPick = { picked -> CanvasColorChoice.pick(preferences, dark, role, picked.rgb).takeIf { it != preferences }?.let(onChange) },
            onDismiss = { editing = null },
            onRestoreDefault = { onChange(CanvasColorChoice.restore(preferences, dark, role)) },
        )
    }
}

@Composable
private fun CanvasColorRow(label: String, role: EditorColor, rgb: Int, customized: Boolean, onEdit: () -> Unit, onRestore: () -> Unit) {
    val strings = rememberLocalizer()
    val color = ColorPickerModel(rgb)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = strings("preferences_choice_change"), onClick = onEdit)
            .semantics(mergeDescendants = true) {
                contentDescription = strings("color_swatch_description", "name" to label, "hex" to color.hex)
                stateDescription = strings(if (customized) "color_state_custom" else "color_state_default")
            }.testTag("preferences-color-${role.key}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorSample(color, 40.dp)
            Text(label, modifier = Modifier.weight(1f))
        }
        if (customized) Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onRestore)
            .semantics { contentDescription = strings(Messages.PreferencesColorRestore) + ": " + label }
            .testTag("preferences-color-restore-${role.key}"), contentAlignment = Alignment.Center) {
            EditorIconGlyph(EditorIcon.UNDO, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** CoverColor's 16 K-map group colors as a compact grid: 8 columns where they fit, else 4. */
@Composable
private fun KmapColorGrid(preferences: EditorPreferences, dark: Boolean, onEdit: (EditorColor) -> Unit) {
    val strings = rememberLocalizer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 48.dp * 8) 8 else 4
        Column {
            EditorColor.kmapGroups.chunked(columns).forEach { row ->
                Row {
                    row.forEach { role ->
                        val color = ColorPickerModel(CanvasColorChoice.shown(preferences, dark, role))
                        val customized = CanvasColorChoice.customized(preferences, dark, role)
                        val label = role.label(strings)
                        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = strings("preferences_choice_change")) { onEdit(role) }
                            .semantics {
                                contentDescription = strings("color_swatch_description", "name" to label, "hex" to color.hex)
                                stateDescription = strings(if (customized) "color_state_custom" else "color_state_default")
                            }.testTag("preferences-color-${role.key}"), contentAlignment = Alignment.Center) {
                            ColorSample(color, 40.dp, if (customized) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

private fun EditorColor.label(strings: dev.logisim.mobile.localization.Localizer): String =
    EditorColor.kmapGroups.indexOf(this).takeIf { it >= 0 }?.let { strings("preferences_color_kmap_group", "number" to it + 1) }
        ?: strings(labelKey())

/** SimOptions' SymbolChooser rows: each value offers Evolution's fixed set of characters. */
@Composable
private fun ValueCharacterPreferences(preferences: EditorPreferences, onChange: (EditorPreferences) -> Unit) {
    val strings = rememberLocalizer()
    val current = preferences.valueCharacters
    @Composable fun row(key: String, tag: String, choices: String, value: Char, update: (Char) -> dev.logisim.mobile.core.ValueCharacters) {
        PreferenceChoice(strings(key), value, choices.map { it to it.toString() }, tag = tag) {
            onChange(preferences.copy(valueCharacters = update(it)))
        }
    }
    // A label inside Simulation, not a section of its own: the rows after it are SimOptions' symbol choosers only.
    Text(strings("preferences_value_characters_title"), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    row("preferences_true_char", "preferences-true-char", dev.logisim.mobile.core.ValueCharacters.TRUE_CHOICES, current.trueChar) {
        current.copy(trueChar = it)
    }
    row("preferences_false_char", "preferences-false-char", dev.logisim.mobile.core.ValueCharacters.FALSE_CHOICES, current.falseChar) {
        current.copy(falseChar = it)
    }
    row("preferences_unknown_char", "preferences-unknown-char", dev.logisim.mobile.core.ValueCharacters.UNKNOWN_CHOICES,
        current.unknownChar) { current.copy(unknownChar = it) }
    row("preferences_error_char", "preferences-error-char", dev.logisim.mobile.core.ValueCharacters.ERROR_CHOICES, current.errorChar) {
        current.copy(errorChar = it)
    }
    row("preferences_dont_care_char", "preferences-dont-care-char", dev.logisim.mobile.core.ValueCharacters.DONT_CARE_CHOICES,
        current.dontCareChar) { current.copy(dontCareChar = it) }
}

private fun EditorColor.labelKey(): String = when (this) {
    EditorColor.CANVAS -> Messages.PreferencesColorCanvas
    EditorColor.GRID_BACKGROUND -> Messages.PreferencesColorGridBackground
    EditorColor.GRID_DOT -> Messages.PreferencesColorGridDot
    EditorColor.GRID_ZOOMED_DOT -> Messages.PreferencesColorGridZoomedDot
    EditorColor.COMPONENT -> Messages.PreferencesColorComponent
    EditorColor.COMPONENT_SECONDARY -> Messages.PreferencesColorComponentSecondary
    EditorColor.COMPONENT_GHOST -> Messages.PreferencesColorComponentGhost
    EditorColor.COMPONENT_ICON -> Messages.PreferencesColorComponentIcon
    EditorColor.SIGNAL_HIGH -> Messages.PreferencesColorSignalHigh
    EditorColor.SIGNAL_LOW -> Messages.PreferencesColorSignalLow
    EditorColor.SIGNAL_UNKNOWN -> Messages.PreferencesColorSignalUnknown
    EditorColor.SIGNAL_ERROR -> Messages.PreferencesColorSignalError
    EditorColor.SIGNAL_FLOATING -> Messages.PreferencesColorSignalFloating
    EditorColor.SIGNAL_BUS -> Messages.PreferencesColorSignalBus
    EditorColor.FPGA_DEFINE -> "preferences_color_fpga_define"
    EditorColor.FPGA_DEFINE_HIGHLIGHT -> "preferences_color_fpga_define_highlight"
    EditorColor.FPGA_DEFINE_RESIZE -> "preferences_color_fpga_define_resize"
    EditorColor.FPGA_DEFINE_MOVE -> "preferences_color_fpga_define_move"
    EditorColor.FPGA_MAPPED -> "preferences_color_fpga_mapped"
    EditorColor.FPGA_SELECTED_MAPPED -> "preferences_color_fpga_selected_mapped"
    EditorColor.FPGA_SELECTABLE_MAPPED -> "preferences_color_fpga_selectable_mapped"
    EditorColor.FPGA_SELECT -> "preferences_color_fpga_select"
    EditorColor.TEXT_TOOL -> "preferences_color_text_tool"
    EditorColor.STROKE -> "preferences_color_stroke"
    EditorColor.WIDTH_ERROR -> "preferences_color_width_error"
    EditorColor.WIDTH_ERROR_CAPTION -> "preferences_color_width_error_caption"
    EditorColor.WIDTH_ERROR_HIGHLIGHT -> "preferences_color_width_error_highlight"
    EditorColor.WIDTH_ERROR_BACKGROUND -> "preferences_color_width_error_background"
    EditorColor.CLOCK_FREQUENCY -> "preferences_color_clock_frequency"
    EditorColor.KMAP_CELL_TEXT -> "preferences_color_kmap_cell_text"
    EditorColor.TABLE_CURSOR -> "preferences_color_table_cursor"
    EditorColor.TABLE_HIGHLIGHT -> "preferences_color_table_highlight"
    EditorColor.TABLE_SELECTION -> "preferences_color_table_selection"
    EditorColor.KMAP1, EditorColor.KMAP2, EditorColor.KMAP3, EditorColor.KMAP4, EditorColor.KMAP5, EditorColor.KMAP6,
    EditorColor.KMAP7, EditorColor.KMAP8, EditorColor.KMAP9, EditorColor.KMAP10, EditorColor.KMAP11, EditorColor.KMAP12,
    EditorColor.KMAP13, EditorColor.KMAP14, EditorColor.KMAP15, EditorColor.KMAP16 -> "preferences_color_kmap_group"
}
