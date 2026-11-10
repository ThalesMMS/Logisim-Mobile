package dev.logisim.mobile

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Text(strings(Messages.PreferencesTitle), fontWeight = FontWeight.Bold)
        Text(strings(Messages.PreferencesHelp),
            style = MaterialTheme.typography.bodySmall)
        PreferenceChoice(strings(Messages.PreferencesLanguage), preferences.locale, EditorLocale.available.map { locale ->
            val label = when (locale) {
                EditorLocale.SYSTEM -> strings(Messages.PreferencesSystemLanguage)
                EditorLocale.PORTUGUESE -> strings(Messages.PreferencesPortuguese)
                EditorLocale.ENGLISH -> strings(Messages.PreferencesEnglish)
                else -> checkNotNull(locale.language).nativeName
            }
            locale to label
        }) { onChange(preferences.copy(locale = it)) }
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
        PreferenceToggle(strings(Messages.PreferencesSnapGrid), preferences.snapToGrid) { onChange(preferences.copy(snapToGrid = it)) }
        PreferenceToggle(strings(Messages.PreferencesShowPlacementPreview), preferences.showPlacementPreview) {
            onChange(preferences.copy(showPlacementPreview = it))
        }
        PreferenceChoice(strings(Messages.PreferencesAfterPlacement), preferences.afterPlacement, listOf(
            EditorAfterPlacement.EDIT to strings(Messages.PreferencesAfterPlacementEdit),
            EditorAfterPlacement.KEEP_TOOL to strings(Messages.PreferencesAfterPlacementKeepTool),
        )) { onChange(preferences.copy(afterPlacement = it)) }
        PreferenceToggle(strings(Messages.PreferencesHighContrast), preferences.highContrastSignals) { onChange(preferences.copy(highContrastSignals = it)) }
        CanvasColorPreferences(preferences, onChange, dark)
        PreferenceChoice(strings(Messages.PreferencesSignalRadix), preferences.signalRadix, listOf(
            EditorSignalRadix.BINARY to strings(Messages.PreferencesBinary), EditorSignalRadix.HEXADECIMAL to strings(Messages.PreferencesHexadecimal),
            EditorSignalRadix.UNSIGNED_DECIMAL to strings(Messages.PreferencesUnsignedDecimal), EditorSignalRadix.SIGNED_DECIMAL to strings(Messages.PreferencesSignedDecimal),
        )) { onChange(preferences.copy(signalRadix = it)) }
        ValueCharacterPreferences(preferences, onChange)
        val wireRadixChoices = WirePokeRadix.entries.map { it to strings(it.label) }
        PreferenceChoice(strings(Messages.PreferencesWirePokePrimaryRadix), preferences.wirePokePrimaryRadix,
            wireRadixChoices) { onChange(preferences.copy(wirePokePrimaryRadix = it)) }
        PreferenceChoice(strings(Messages.PreferencesWirePokeSecondaryRadix), preferences.wirePokeSecondaryRadix,
            listOf(null to strings(Messages.PreferencesWirePokeNoSecondRadix)) + wireRadixChoices) {
            onChange(preferences.copy(wirePokeSecondaryRadix = it))
        }
        if (capabilities.gateShape) {
            PreferenceChoice(strings(Messages.PreferencesGateShapes), preferences.gateShape, listOf(
                EditorGateShape.ANSI to strings(Messages.PreferencesAnsi), EditorGateShape.IEC to strings(Messages.PreferencesIec),
                EditorGateShape.DIN40700 to strings(Messages.PreferencesDin40700),
            )) { onChange(preferences.copy(gateShape = it)) }
        }
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
        HorizontalDivider()
        Text(strings(Messages.PreferencesSectionLayout), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        HorizontalDivider()
        // Evolution Preferences → FPGA: HDL generation and DRC report options.
        Text(strings("preferences_fpga_title"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        HorizontalDivider()
        Text(strings(Messages.PreferencesNewProjects), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (projectTemplates != null && templatePicker != null) ProjectTemplateSettings(projectTemplates, templatePicker)
        var frequencyText by remember(preferences.defaultTickFrequencyHz) { mutableStateOf(preferences.defaultTickFrequencyHz.toString()) }
        val frequency = frequencyText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        OutlinedTextField(frequencyText, { frequencyText = it }, singleLine = true,
            label = { Text(strings(Messages.PreferencesDefaultFrequency)) }, isError = frequency == null,
            supportingText = { Text(if (frequency == null) strings(Messages.PreferencesFrequencyHint) else strings(Messages.PreferencesFrequencyScope)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(enabled = frequency != null, onClick = { frequency?.let { onChange(preferences.copy(defaultTickFrequencyHz = it)) } }) { Text(strings(Messages.ActionApply)) }
            })
        onOpenProjectOptions?.let { open ->
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.PreferencesProjectOptions)) }
        }
        HorizontalDivider()
        TextButton(onClick = onRestoreDefaults, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.PreferencesRestoreDefaults)) }
    }
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
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = true }
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .semantics { contentDescription = strings(Messages.PreferencesChoiceDescription, "title" to title, "value" to choices.firstOrNull { it.first == current }?.second.orEmpty()) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(choices.firstOrNull { it.first == current }?.second.orEmpty(), color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChange(value) }) }
        }
    }
}

@Composable
private fun CanvasColorPreferences(preferences: EditorPreferences, onChange: (EditorPreferences) -> Unit, dark: Boolean) {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(strings(Messages.PreferencesColors))
    }
    if (!open) return

    var role by remember { mutableStateOf(EditorColor.CANVAS) }
    // Like ThemeColorPreference, the dialog edits the colors of the theme in use.
    val custom = preferences.customColors(dark)
    // TEXT_TOOL_COLOR is one light-reference preference; the dark theme shows and takes its luma inversion (#340).
    val textTool = role == EditorColor.TEXT_TOOL
    val roleColors = if (textTool) preferences.colors else custom
    val stored = roleColors[role]
    val default = DarkEditorColors[role]?.takeIf { dark }?.editorColorOr(Color.Black) ?: role.defaultColor(preferences.highContrastSignals)
    val current = when {
        textTool -> (stored ?: (default.toArgb() and 0xFFFFFF)).let { if (dark) ThemeTextColor.invertRgb(it) else it }
        else -> stored ?: (default.toArgb() and 0xFFFFFF)
    }
    var input by remember(role, current) { mutableStateOf(editorColorHex(current)) }
    val rgb = parseEditorColor(input)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(strings(Messages.PreferencesColors)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings(if (dark) Messages.PreferencesColorsThemeDark else Messages.PreferencesColorsThemeLight),
                    style = MaterialTheme.typography.bodySmall)
                PreferenceChoice(
                    strings(Messages.PreferencesColorRole),
                    role,
                    EditorColor.entries.map { it to it.label(strings) },
                    onChange = { role = it },
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    label = { Text(strings(Messages.PreferencesColorValue)) },
                    isError = rgb == null,
                    supportingText = if (rgb == null) { { Text(strings(Messages.PreferencesColorInvalid)) } } else null,
                    leadingIcon = {
                        Box(Modifier.size(24.dp)
                            .background(rgb?.let { Color(0xFF000000.toInt() or it) } ?: default)
                            .border(1.dp, MaterialTheme.colorScheme.outline))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { onChange(preferences.withRoleColor(dark, role, null)) },
                    enabled = role in roleColors,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings(Messages.PreferencesColorRestore)) }
                TextButton(
                    onClick = { onChange(preferences.withCustomColors(dark, emptyMap())) },
                    enabled = custom.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings(Messages.PreferencesColorsRestore)) }
            }
        },
        confirmButton = {
            TextButton(enabled = rgb != null, onClick = {
                rgb?.let { shown ->
                    val value = if (textTool && dark) {
                        // TextColorChooser keeps the stored RGB when the shown one is unchanged.
                        if (shown == current) stored ?: (default.toArgb() and 0xFFFFFF) else ThemeTextColor.invertRgb(shown)
                    } else shown
                    onChange(preferences.withRoleColor(dark, role, value))
                }
            }) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(strings(Messages.ActionClose)) } },
    )
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
    Text(strings("preferences_value_characters_title"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
