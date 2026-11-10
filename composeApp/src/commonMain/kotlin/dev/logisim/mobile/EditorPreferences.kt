package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaGenerationOptions
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.CreationDefaults
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.hexadecimalDisplay
import dev.logisim.mobile.core.localization.*

enum class EditorLocale(val tag: String, val language: AppLanguage? = null) {
    SYSTEM("system"),
    PORTUGUESE("pt-BR", AppLanguage.PORTUGUESE),
    ENGLISH("en", AppLanguage.ENGLISH),
    GERMAN("de", AppLanguage.GERMAN),
    GREEK("el", AppLanguage.GREEK),
    SPANISH("es", AppLanguage.SPANISH),
    FRENCH("fr", AppLanguage.FRENCH),
    ITALIAN("it", AppLanguage.ITALIAN),
    JAPANESE("ja", AppLanguage.JAPANESE),
    DUTCH("nl", AppLanguage.DUTCH),
    POLISH("pl", AppLanguage.POLISH),
    RUSSIAN("ru", AppLanguage.RUSSIAN),
    TURKISH("tr", AppLanguage.TURKISH),
    CHINESE("zh", AppLanguage.CHINESE);

    companion object {
        val available: List<EditorLocale> get() = entries.filter { it.language?.isAvailable != false }
    }
}
enum class EditorSignalRadix { BINARY, HEXADECIMAL, UNSIGNED_DECIMAL, SIGNED_DECIMAL }
enum class EditorGateShape { ANSI, IEC, DIN40700 }

/** AppPreferences.getDefaultGateShape(): rectangular (IEC) gates when the system language is Russian, ANSI otherwise. */
fun defaultEditorGateShape(language: AppLanguage = systemAppLanguage()): EditorGateShape =
    if (language == AppLanguage.RUSSIAN) EditorGateShape.IEC else EditorGateShape.ANSI
enum class EditorShortcutScheme { PLATFORM, CONTROL }
enum class EditorToolbarPlacement { TOP, BOTTOM, LEFT, RIGHT, DOWN_MIDDLE, HIDDEN }
enum class EditorAfterPlacement { EDIT, KEEP_TOOL }

/** Evolution's FollowSystemTheme plus the light or dark LookAndFeel chosen when it is off. */
enum class EditorThemeMode { SYSTEM, LIGHT, DARK }

/** Evolution's RSyntaxTextArea themes for HDL code (AppPreferences.EDITOR_THEMES). */
enum class HdlEditorTheme(val key: String, val label: String) {
    DEFAULT("default", "Default"), DARK("dark", "Dark"), MONOKAI("monokai", "Monokai"), ECLIPSE("eclipse", "Eclipse"),
    IDEA("idea", "IDEA"), VS("vs", "Visual Studio"), DRUID("druid", "Druid"),
}

/** AppPreferences.HdlType ([key] is its stored value): the language HDL export and the FPGA build generate; NONE disables them. */
enum class EditorHdlType(val key: String, val language: HdlLanguage?) {
    VHDL("VHDL", HdlLanguage.VHDL), VERILOG("Verilog", HdlLanguage.VERILOG), NONE("None", null);
}

/** AppPreferences.PinAppearance: the connection dot diameter in Evolution pixels. */
enum class EditorPinAppearance(val key: String, val diameter: Int) {
    DOT_SMALL("dot-small", 4), DOT_MEDIUM("dot-medium", 6), DOT_BIG("dot-big", 8), DOT_BIGGER("dot-bigger", 10),
}

/** AppPreferences.DefaultAppearance; [value] is the StdAttr.APPEARANCE option stored in files. */
enum class EditorDefaultAppearance(val value: String) {
    CLASSIC("classic"), FPGA("evolution"), EVOLUTION("logisim_evolution");

    /** AppPreferences.getDefaultAppearance(): components fall back to classic for the FPGA style. */
    val componentValue: String get() = if (this == EVOLUTION) value else CLASSIC.value
}

enum class EditorColor(val key: String) {
    CANVAS("canvas"), GRID_BACKGROUND("gridBackground"), GRID_DOT("gridDot"), GRID_ZOOMED_DOT("gridZoomedDot"),
    COMPONENT("component"), COMPONENT_SECONDARY("componentSecondary"), COMPONENT_GHOST("componentGhost"),
    COMPONENT_ICON("componentIcon"),
    SIGNAL_HIGH("signalHigh"), SIGNAL_LOW("signalLow"), SIGNAL_UNKNOWN("signalUnknown"), SIGNAL_ERROR("signalError"),
    SIGNAL_FLOATING("signalFloating"), SIGNAL_BUS("signalBus"),
    // Evolution FPGA options: board editor and FPGA Commander map colours.
    FPGA_DEFINE("fpgaDefine"), FPGA_DEFINE_HIGHLIGHT("fpgaDefineHighlight"), FPGA_DEFINE_RESIZE("fpgaDefineResize"),
    FPGA_DEFINE_MOVE("fpgaDefineMove"), FPGA_MAPPED("fpgaMapped"), FPGA_SELECTED_MAPPED("fpgaSelectedMapped"),
    FPGA_SELECTABLE_MAPPED("fpgaSelectableMapped"), FPGA_SELECT("fpgaSelect"),
    // Evolution Window and Simulation options: text tool, wire stroke, width errors and clock frequency.
    TEXT_TOOL("textTool"), STROKE("stroke"), WIDTH_ERROR("widthError"), WIDTH_ERROR_CAPTION("widthErrorCaption"),
    WIDTH_ERROR_HIGHLIGHT("widthErrorHighlight"), WIDTH_ERROR_BACKGROUND("widthErrorBackground"),
    CLOCK_FREQUENCY("clockFrequency"),
    // Combinational analysis: K-map text, truth table caret and the 16 K-map group colors (CoverColor).
    KMAP_CELL_TEXT("kmapCellText"), TABLE_CURSOR("tableCursor"), TABLE_HIGHLIGHT("tableHighlight"),
    TABLE_SELECTION("tableSelection"),
    KMAP1("kmap1"), KMAP2("kmap2"), KMAP3("kmap3"), KMAP4("kmap4"), KMAP5("kmap5"), KMAP6("kmap6"), KMAP7("kmap7"),
    KMAP8("kmap8"), KMAP9("kmap9"), KMAP10("kmap10"), KMAP11("kmap11"), KMAP12("kmap12"), KMAP13("kmap13"),
    KMAP14("kmap14"), KMAP15("kmap15"), KMAP16("kmap16");

    internal val preferenceKey: String get() = "color.$key"

    companion object {
        /** CoverColor's KMAP1..16_COLOR, indexed by [dev.logisim.mobile.core.KarnaughGroup.colorIndex]. */
        val kmapGroups: List<EditorColor> = listOf(KMAP1, KMAP2, KMAP3, KMAP4, KMAP5, KMAP6, KMAP7, KMAP8, KMAP9, KMAP10,
            KMAP11, KMAP12, KMAP13, KMAP14, KMAP15, KMAP16)
    }
}

internal fun parseEditorColor(raw: String): Int? {
    val digits = raw.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return digits.toIntOrNull(16)
}

internal fun editorColorHex(rgb: Int): String = "#" + rgb.toString(16).uppercase().padStart(6, '0')

/** User defaults never replace the simulation options stored in an opened project. */
data class EditorPreferences(
    val locale: EditorLocale = EditorLocale.SYSTEM,
    val showGrid: Boolean = true,
    val snapToGrid: Boolean = true,
    val highContrastSignals: Boolean = false,
    val signalRadix: EditorSignalRadix = EditorSignalRadix.BINARY,
    val defaultTickFrequencyHz: Double = DEFAULT_TICK_FREQUENCY_HZ,
    val gateShape: EditorGateShape = defaultEditorGateShape(),
    val shortcutScheme: EditorShortcutScheme = EditorShortcutScheme.PLATFORM,
    val toolbarPlacement: EditorToolbarPlacement = EditorToolbarPlacement.TOP,
    val showPlacementPreview: Boolean = true,
    val afterPlacement: EditorAfterPlacement = EditorAfterPlacement.EDIT,
    val extraValues: Map<String, String> = emptyMap(),
    val wirePokePrimaryRadix: WirePokeRadix = WirePokeRadix.fromSignalRadix(signalRadix),
    val wirePokeSecondaryRadix: WirePokeRadix? = WirePokeRadix.SIGNED_DECIMAL,
    val showTickRate: Boolean = false,
    val showHeaderBar: Boolean = true,
    val colors: Map<EditorColor, Int> = emptyMap(),
    val shortcutOverrides: Map<EditorShortcut, EditorShortcutBinding> = emptyMap(),
    val themeMode: EditorThemeMode = EditorThemeMode.SYSTEM,
    val lightEditorTheme: HdlEditorTheme = HdlEditorTheme.DEFAULT,
    val darkEditorTheme: HdlEditorTheme = HdlEditorTheme.DARK,
    /** Custom colors of the dark theme; [colors] holds the light theme's, like Evolution's ThemeColorPreference. */
    val darkColors: Map<EditorColor, Int> = emptyMap(),
    val attributeHalo: Boolean = true,
    val componentTips: Boolean = true,
    val pinAppearance: EditorPinAppearance = EditorPinAppearance.DOT_SMALL,
    val kmapLinedStyle: Boolean = false,
    val appearanceShowGrid: Boolean = true,
    val defaultAppearance: EditorDefaultAppearance = EditorDefaultAppearance.EVOLUTION,
    val newInputOutputShapes: Boolean = true,
    val namedCircuitBoxesFixedSize: Boolean = true,
    val removeUnusedLibraries: Boolean = false,
    /** Evolution Preferences → FPGA: VHDL keyword case and DRC warning suppression. */
    val fpga: FpgaGenerationOptions = FpgaGenerationOptions(),
    /** Evolution's HdlType, VHDL by default. */
    val hdlType: EditorHdlType = EditorHdlType.VHDL,
    /** Evolution's SEARCH_DOUBLE_SHIFT: tapping Shift twice opens Find Action. */
    val searchDoubleShift: Boolean = true,
    /** Evolution's TRUE/FALSE/UNKNOWN/ERROR/DONTCARE_CHAR, used where values are shown. */
    val valueCharacters: ValueCharacters = ValueCharacters.Default,
) {
    init {
        requireLocalized(defaultTickFrequencyHz.isFinite() && defaultTickFrequencyHz > 0) {
            message(Messages.PreferencesFrequencyInvalid)
        }
    }

    companion object {
        /** Evolution's `AppPreferences.TICK_FREQUENCY` default, which a new project's main circuit takes (#324). */
        const val DEFAULT_TICK_FREQUENCY_HZ = 1.0
    }

    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        EditorThemeMode.SYSTEM -> systemDark
        EditorThemeMode.LIGHT -> false
        EditorThemeMode.DARK -> true
    }

    fun customColors(dark: Boolean): Map<EditorColor, Int> = if (dark) darkColors else colors

    fun withCustomColors(dark: Boolean, value: Map<EditorColor, Int>): EditorPreferences =
        if (dark) copy(darkColors = value) else copy(colors = value)

    /** Sets or, with null, restores [role] of the theme in use; TEXT_TOOL is shared by both themes. */
    fun withRoleColor(dark: Boolean, role: EditorColor, rgb: Int?): EditorPreferences {
        val themed = if (role == EditorColor.TEXT_TOOL) false else dark
        val current = customColors(themed)
        return withCustomColors(themed, if (rgb == null) current - role else current + (role to rgb))
    }

    /** Colors handed to the renderers: the dark theme's built-in values under the user's own. */
    fun effectiveColors(dark: Boolean): Map<EditorColor, Int> = if (dark) DarkEditorColors + darkColors else colors

    fun editorTheme(dark: Boolean): HdlEditorTheme = if (dark) darkEditorTheme else lightEditorTheme

    fun withEditorTheme(dark: Boolean, theme: HdlEditorTheme): EditorPreferences =
        if (dark) copy(darkEditorTheme = theme) else copy(lightEditorTheme = theme)

    /**
     * TEXT_TOOL_COLOR, as the Text color attribute Evolution writes. Unlike the ThemeColorPreference colors it is
     * one preference holding the light-theme reference; the dark theme shows it luma-inverted ([ThemeTextColor]).
     */
    val textToolColor: String get() = "#" + (colors[EditorColor.TEXT_TOOL] ?: 0).toString(16).padStart(6, '0')

    val creationDefaults: CreationDefaults get() = CreationDefaults(
        defaultAppearance.componentValue, defaultAppearance.value, newInputOutputShapes, namedCircuitBoxesFixedSize, removeUnusedLibraries,
    )

    /** [creationDefaults] with TEXT_TOOL_COLOR, the same stored reference in both themes. */
    val creationDefaultsWithTextColor: CreationDefaults get() = creationDefaults.copy(textColor = textToolColor)
}

/** Evolution's DARK_* built-in colors (AppPreferences), applied when the dark theme is active. */
internal val DarkEditorColors: Map<EditorColor, Int> = mapOf(
    EditorColor.CANVAS to 0x2B2B2B, EditorColor.GRID_BACKGROUND to 0x2B2B2B,
    EditorColor.GRID_DOT to 0xAAAAAA, EditorColor.GRID_ZOOMED_DOT to 0x666666,
    EditorColor.COMPONENT to 0xFFFFFF, EditorColor.COMPONENT_SECONDARY to 0xAAAAAA,
    EditorColor.COMPONENT_GHOST to 0x777777, EditorColor.COMPONENT_ICON to 0xFFFFFF,
    EditorColor.SIGNAL_HIGH to 0x00FF00, EditorColor.SIGNAL_LOW to 0x339933,
    EditorColor.SIGNAL_UNKNOWN to 0x3399FF, EditorColor.SIGNAL_ERROR to 0xFF4444,
    EditorColor.SIGNAL_BUS to 0xFFFFFF, EditorColor.SIGNAL_FLOATING to 0xBBBBBB,
    EditorColor.STROKE to 0xFF88FF, EditorColor.KMAP_CELL_TEXT to 0xFFFFFF,
    EditorColor.TABLE_CURSOR to 0x666666, EditorColor.TABLE_HIGHLIGHT to 0x7A6A00, EditorColor.TABLE_SELECTION to 0x323C78,
)

data class EditorPreferencesLoad(
    val preferences: EditorPreferences = EditorPreferences(),
    val notices: List<LocalizedText> = emptyList(),
    val migratedFromVersion: Int? = null,
    val canPersist: Boolean = true,
) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
}

object EditorPreferencesCodec {
    const val VERSION = 2
    private const val HEADER = "LOGISIM-EDITOR-PREFERENCES"
    private const val FPGA_VHDL_UPPER = "fpga.vhdlKeywordsUpperCase"
    private const val FPGA_SUPPRESS_GATED = "fpga.suppressGatedClockWarnings"
    private const val FPGA_SUPPRESS_OPEN = "fpga.suppressOpenPinWarnings"
    private const val FPGA_HDL_TYPE = "fpga.hdlType"
    private val shortcutFields = EditorShortcut.entries.associateWith { "shortcut.${it.name}" }
    // AppPreferences SimTrueChar ... SimDontCareChar.
    private const val TRUE_CHAR = "valueChar.true"
    private const val FALSE_CHAR = "valueChar.false"
    private const val UNKNOWN_CHAR = "valueChar.unknown"
    private const val ERROR_CHAR = "valueChar.error"
    private const val DONT_CARE_CHAR = "valueChar.dontCare"
    private val darkColorFields = EditorColor.entries.associateWith { "darkColor.${it.key}" }
    private val knownKeys = setOf("locale", "showGrid", "snapToGrid", "highContrastSignals", "signalRadix",
        "defaultTickFrequencyHz", "gateShape", "shortcutScheme", "toolbarPlacement",
        "wirePokePrimaryRadix", "wirePokeSecondaryRadix", "showTickRate", "showHeaderBar", "showPlacementPreview", "afterPlacement",
        "themeMode", "lightEditorTheme", "darkEditorTheme", "attributeHalo", "componentTips", "pinAppearance",
        "kmapLinedStyle", "appearanceShowGrid", "defaultAppearance", "newInputOutputShapes", "namedCircuitBoxesFixedSize",
        "removeUnusedLibraries",
        FPGA_VHDL_UPPER, FPGA_SUPPRESS_GATED, FPGA_SUPPRESS_OPEN, FPGA_HDL_TYPE, "searchDoubleShift",
        TRUE_CHAR, FALSE_CHAR, UNKNOWN_CHAR, ERROR_CHAR, DONT_CARE_CHAR,
    ) + EditorColor.entries.map { it.preferenceKey } + darkColorFields.values + shortcutFields.values

    fun write(preferences: EditorPreferences): String {
        val colors = preferences.colors.entries.associate { (role, color) -> role.preferenceKey to editorColorHex(color) } +
            preferences.darkColors.entries.associate { (role, color) -> darkColorFields.getValue(role) to editorColorHex(color) }
        val shortcuts = preferences.shortcutOverrides.mapKeys { shortcutFields.getValue(it.key) }.mapValues { it.value.serialized() }
        val fields = preferences.extraValues.filterKeys { it !in knownKeys } + linkedMapOf(
            "locale" to preferences.locale.tag,
            "showGrid" to preferences.showGrid.toString(),
            "showTickRate" to preferences.showTickRate.toString(),
            "snapToGrid" to preferences.snapToGrid.toString(),
            "highContrastSignals" to preferences.highContrastSignals.toString(),
            "signalRadix" to preferences.signalRadix.name,
            "wirePokePrimaryRadix" to preferences.wirePokePrimaryRadix.name,
            "wirePokeSecondaryRadix" to (preferences.wirePokeSecondaryRadix?.name ?: "NONE"),
            "defaultTickFrequencyHz" to preferences.defaultTickFrequencyHz.toString(),
            "gateShape" to preferences.gateShape.name,
            "shortcutScheme" to preferences.shortcutScheme.name,
            "toolbarPlacement" to preferences.toolbarPlacement.name,
            "showHeaderBar" to preferences.showHeaderBar.toString(),
            "showPlacementPreview" to preferences.showPlacementPreview.toString(),
            "afterPlacement" to preferences.afterPlacement.name,
            "themeMode" to preferences.themeMode.name,
            "lightEditorTheme" to preferences.lightEditorTheme.key,
            "darkEditorTheme" to preferences.darkEditorTheme.key,
            "attributeHalo" to preferences.attributeHalo.toString(),
            "componentTips" to preferences.componentTips.toString(),
            "pinAppearance" to preferences.pinAppearance.key,
            "kmapLinedStyle" to preferences.kmapLinedStyle.toString(),
            "appearanceShowGrid" to preferences.appearanceShowGrid.toString(),
            "defaultAppearance" to preferences.defaultAppearance.value,
            "newInputOutputShapes" to preferences.newInputOutputShapes.toString(),
            "namedCircuitBoxesFixedSize" to preferences.namedCircuitBoxesFixedSize.toString(),
            "removeUnusedLibraries" to preferences.removeUnusedLibraries.toString(),
            FPGA_VHDL_UPPER to preferences.fpga.vhdlKeywordsUpperCase.toString(),
            FPGA_SUPPRESS_GATED to preferences.fpga.suppressGatedClockWarnings.toString(),
            FPGA_SUPPRESS_OPEN to preferences.fpga.suppressOpenPinWarnings.toString(),
            FPGA_HDL_TYPE to preferences.hdlType.key,
            "searchDoubleShift" to preferences.searchDoubleShift.toString(),
            TRUE_CHAR to preferences.valueCharacters.trueChar.toString(),
            FALSE_CHAR to preferences.valueCharacters.falseChar.toString(),
            UNKNOWN_CHAR to preferences.valueCharacters.unknownChar.toString(),
            ERROR_CHAR to preferences.valueCharacters.errorChar.toString(),
            DONT_CARE_CHAR to preferences.valueCharacters.dontCareChar.toString(),
        ) + colors + shortcuts
        return buildString {
            append(HEADER).append('\n').append(VERSION).append('\n')
            fields.keys.sorted().forEach { key -> append(escape(key)).append('=').append(escape(fields.getValue(key))).append('\n') }
        }
    }

    fun read(serialized: String?): EditorPreferencesLoad {
        if (serialized.isNullOrBlank()) return EditorPreferencesLoad()
        val lines = serialized.lineSequence().map { it.removeSuffix("\r") }.toList()
        if (lines.firstOrNull() != HEADER) return EditorPreferencesLoad(
            notices = listOf(message(Messages.PreferencesFormatUnknown)), canPersist = false,
        )
        val version = lines.getOrNull(1)?.toIntOrNull()
        if (version == null || version !in 1..VERSION) return EditorPreferencesLoad(
            notices = listOf(message(Messages.PreferencesVersionUnsupported, "version" to lines.getOrNull(1).orEmpty())),
            canPersist = false,
        )
        val fields = mutableMapOf<String, String>()
        for (line in lines.drop(2).filter { it.isNotBlank() }) {
            val divider = line.indexOf('=')
            if (divider <= 0) return malformed()
            val key = unescape(line.substring(0, divider)) ?: return malformed()
            val value = unescape(line.substring(divider + 1)) ?: return malformed()
            if (key.isEmpty() || fields.put(key, value) != null) return malformed()
        }
        if (version == 1) migrateVersionOne(fields)
        val notices = mutableListOf<LocalizedText>()
        val defaults = EditorPreferences()
        fun invalid(key: String) { notices += message(Messages.PreferencesValueInvalid, "key" to key) }
        fun boolean(key: String, fallback: Boolean): Boolean = fields[key]?.let {
            when (it) { "true" -> true; "false" -> false; else -> { invalid(key); fallback } }
        } ?: fallback
        fun <T> choice(key: String, options: List<T>, value: (T) -> String, fallback: T): T {
            val raw = fields[key] ?: return fallback
            val index = options.indexOfFirst { value(it) == raw }
            return if (index >= 0) options[index] else fallback.also { invalid(key) }
        }
        val signalRadix = choice("signalRadix", EditorSignalRadix.entries, { it.name }, defaults.signalRadix)
        val frequency = fields["defaultTickFrequencyHz"]?.let { raw ->
            raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: defaults.defaultTickFrequencyHz.also { invalid("defaultTickFrequencyHz") }
        } ?: defaults.defaultTickFrequencyHz
        fun colors(field: (EditorColor) -> String) = buildMap {
            for (role in EditorColor.entries) {
                val raw = fields[field(role)] ?: continue
                val color = parseEditorColor(raw)
                if (color == null) invalid(field(role)) else put(role, color)
            }
        }
        fun character(key: String, choices: String, fallback: Char): Char {
            val raw = fields[key] ?: return fallback
            return raw.singleOrNull()?.takeIf { it in choices } ?: fallback.also { invalid(key) }
        }
        val valueDefaults = defaults.valueCharacters
        val valueCharacters = ValueCharacters(
            character(TRUE_CHAR, ValueCharacters.TRUE_CHOICES, valueDefaults.trueChar),
            character(FALSE_CHAR, ValueCharacters.FALSE_CHOICES, valueDefaults.falseChar),
            character(UNKNOWN_CHAR, ValueCharacters.UNKNOWN_CHOICES, valueDefaults.unknownChar),
            character(ERROR_CHAR, ValueCharacters.ERROR_CHOICES, valueDefaults.errorChar),
            character(DONT_CARE_CHAR, ValueCharacters.DONT_CARE_CHOICES, valueDefaults.dontCareChar),
        )
        val colors = colors { it.preferenceKey }.toMutableMap()
        // Before #340 the dark theme kept its own TEXT_TOOL entry, chosen as the color to show; it becomes the
        // single light reference that the dark theme shows luma-inverted.
        val darkColors = colors { darkColorFields.getValue(it) }.toMutableMap()
        darkColors.remove(EditorColor.TEXT_TOOL)?.let { shown ->
            if (EditorColor.TEXT_TOOL !in colors) colors[EditorColor.TEXT_TOOL] = ThemeTextColor.invertRgb(shown)
        }
        val shortcutOverrides = shortcutFields.mapNotNull { (command, field) ->
            fields[field]?.let { encoded ->
                EditorShortcutBinding.parse(encoded)
                    ?.takeIf { it.primary == command.defaultBinding.primary && it.reservedCanvasAction() == null }
                    ?.let { command to it } ?: run { invalid(field); null }
            }
        }.toMap().toMutableMap()
        // Validate the complete set so persisted reassignments can reuse a chord freed by another command.
        while (true) {
            val conflicts = shortcutOverrides.filter { (command, binding) ->
                editorShortcutFor(binding, shortcutOverrides, excluding = command) != null
            }.keys
            if (conflicts.isEmpty()) break
            conflicts.forEach { command ->
                shortcutOverrides.remove(command)
                invalid(shortcutFields.getValue(command))
            }
        }
        return EditorPreferencesLoad(
            preferences = EditorPreferences(
                locale = choice("locale", EditorLocale.available, { it.tag }, defaults.locale),
                showGrid = boolean("showGrid", defaults.showGrid),
                showTickRate = boolean("showTickRate", defaults.showTickRate),
                snapToGrid = boolean("snapToGrid", defaults.snapToGrid),
                highContrastSignals = boolean("highContrastSignals", defaults.highContrastSignals),
                signalRadix = signalRadix,
                wirePokePrimaryRadix = choice("wirePokePrimaryRadix", WirePokeRadix.entries, { it.name },
                    WirePokeRadix.fromSignalRadix(signalRadix)),
                wirePokeSecondaryRadix = choice("wirePokeSecondaryRadix", WirePokeRadix.entries + listOf(null),
                    { it?.name ?: "NONE" }, defaults.wirePokeSecondaryRadix),
                defaultTickFrequencyHz = frequency,
                gateShape = choice("gateShape", EditorGateShape.entries, { it.name }, defaults.gateShape),
                shortcutScheme = choice("shortcutScheme", EditorShortcutScheme.entries, { it.name }, defaults.shortcutScheme),
                shortcutOverrides = shortcutOverrides,
                toolbarPlacement = choice("toolbarPlacement", EditorToolbarPlacement.entries, { it.name }, defaults.toolbarPlacement),
                showPlacementPreview = boolean("showPlacementPreview", defaults.showPlacementPreview),
                afterPlacement = choice("afterPlacement", EditorAfterPlacement.entries, { it.name }, defaults.afterPlacement),
                extraValues = fields.filterKeys { it !in knownKeys },
                showHeaderBar = boolean("showHeaderBar", defaults.showHeaderBar),
                colors = colors,
                darkColors = darkColors,
                themeMode = choice("themeMode", EditorThemeMode.entries, { it.name }, defaults.themeMode),
                lightEditorTheme = choice("lightEditorTheme", HdlEditorTheme.entries, { it.key }, defaults.lightEditorTheme),
                darkEditorTheme = choice("darkEditorTheme", HdlEditorTheme.entries, { it.key }, defaults.darkEditorTheme),
                attributeHalo = boolean("attributeHalo", defaults.attributeHalo),
                componentTips = boolean("componentTips", defaults.componentTips),
                pinAppearance = choice("pinAppearance", EditorPinAppearance.entries, { it.key }, defaults.pinAppearance),
                kmapLinedStyle = boolean("kmapLinedStyle", defaults.kmapLinedStyle),
                appearanceShowGrid = boolean("appearanceShowGrid", defaults.appearanceShowGrid),
                defaultAppearance = choice("defaultAppearance", EditorDefaultAppearance.entries, { it.value }, defaults.defaultAppearance),
                newInputOutputShapes = boolean("newInputOutputShapes", defaults.newInputOutputShapes),
                namedCircuitBoxesFixedSize = boolean("namedCircuitBoxesFixedSize", defaults.namedCircuitBoxesFixedSize),
                removeUnusedLibraries = boolean("removeUnusedLibraries", defaults.removeUnusedLibraries),
                fpga = FpgaGenerationOptions(
                    vhdlKeywordsUpperCase = boolean(FPGA_VHDL_UPPER, defaults.fpga.vhdlKeywordsUpperCase),
                    suppressGatedClockWarnings = boolean(FPGA_SUPPRESS_GATED, defaults.fpga.suppressGatedClockWarnings),
                    suppressOpenPinWarnings = boolean(FPGA_SUPPRESS_OPEN, defaults.fpga.suppressOpenPinWarnings),
                ),
                hdlType = choice(FPGA_HDL_TYPE, EditorHdlType.entries, { it.key }, defaults.hdlType),
                searchDoubleShift = boolean("searchDoubleShift", defaults.searchDoubleShift),
                valueCharacters = valueCharacters,
            ),
            notices = notices,
            migratedFromVersion = version.takeIf { it != VERSION },
        )
    }

    private fun migrateVersionOne(fields: MutableMap<String, String>) {
        fun rename(old: String, current: String) { fields.remove(old)?.let { fields.putIfMissing(current, it) } }
        rename("grid", "showGrid")
        rename("snap", "snapToGrid")
        rename("highContrast", "highContrastSignals")
        rename("tickFrequency", "defaultTickFrequencyHz")
        fields.remove("language")?.let { language ->
            fields.putIfMissing("locale", when (language) { "pt", "pt_BR", "pt-BR" -> "pt-BR"; "en", "en-US" -> "en"; else -> language })
        }
        fields.remove("radix")?.let { radix ->
            fields.putIfMissing("signalRadix", when (radix) {
                "2" -> "BINARY"; "16" -> "HEXADECIMAL"; "10unsigned" -> "UNSIGNED_DECIMAL"; "10signed" -> "SIGNED_DECIMAL"; else -> radix
            })
        }
    }

    private fun MutableMap<String, String>.putIfMissing(key: String, value: String) {
        if (key !in this) this[key] = value
    }

    private fun malformed() = EditorPreferencesLoad(
        notices = listOf(message(Messages.PreferencesSavedInvalid)), canPersist = false,
    )

    private fun escape(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val number = byte.toInt() and 255
            if (number in 48..57 || number in 65..90 || number in 97..122 || number == 45 || number == 46 || number == 95) append(number.toChar())
            else { append('%'); append("0123456789ABCDEF"[number shr 4]); append("0123456789ABCDEF"[number and 15]) }
        }
    }

    private fun unescape(value: String): String? {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                val number = high * 16 + low
                bytes += number.toByte(); index += 3
            } else {
                if (character.code > 127) return null
                bytes += character.code.toByte(); index++
            }
        }
        return try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) } catch (_: IllegalArgumentException) { null }
    }
}

interface PreferencesStorage {
    fun load(): String?
    fun save(serialized: String)
}

sealed interface PreferencesSaveResult {
    data object Success : PreferencesSaveResult
    data class Failure(val notice: LocalizedText) : PreferencesSaveResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

class EditorPreferencesRepository(private val storage: PreferencesStorage) {
    private var writable = true
    private var loaded = false

    fun load(): EditorPreferencesLoad = try {
        loaded = true
        EditorPreferencesCodec.read(storage.load()).also { writable = it.canPersist }
    } catch (error: Exception) {
        writable = false
        EditorPreferencesLoad(notices = listOf(message(Messages.PreferencesReadFailed, "reason" to error.localizedDescription(LocalizedText.literal("")))), canPersist = false)
    }

    fun save(preferences: EditorPreferences): PreferencesSaveResult {
        if (!loaded) load()
        if (!writable) return PreferencesSaveResult.Failure(message(Messages.PreferencesOriginalPreserved))
        return write(preferences)
    }

    fun restoreDefaults(): PreferencesSaveResult = write(EditorPreferences()).also {
        if (it == PreferencesSaveResult.Success) writable = true
    }

    private fun write(preferences: EditorPreferences): PreferencesSaveResult = try {
        storage.save(EditorPreferencesCodec.write(preferences))
        PreferencesSaveResult.Success
    } catch (error: Exception) {
        PreferencesSaveResult.Failure(message(Messages.PreferencesSaveFailed, "reason" to error.localizedDescription(LocalizedText.literal(""))))
    }
}

fun formatEditorSignal(
    value: LogicVector,
    radix: EditorSignalRadix,
    characters: ValueCharacters = ValueCharacters.Default,
): String = when (radix) {
    EditorSignalRadix.BINARY -> value.display(characters)
    EditorSignalRadix.UNSIGNED_DECIMAL -> value.toULongOrNull()?.toString() ?: value.display(characters)
    EditorSignalRadix.SIGNED_DECIMAL -> value.toULongOrNull()?.let { number ->
        if (value.width == 64 || value.bit(value.width - 1) == LogicValue.LOW) number.toLong().toString()
        else (number.toLong() - (1L shl value.width)).toString()
    } ?: value.display(characters)
    EditorSignalRadix.HEXADECIMAL -> value.hexadecimalDisplay(characters)
}
