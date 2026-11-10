package dev.logisim.mobile

import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.FpgaCircuitRules
import dev.logisim.mobile.core.FpgaGenerationOptions
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.HdlLanguage
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.VhdlKeywordCase
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.layout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Preferences campaign (#236, #243). Writes, in the row format of scripts/oracle/preferences/PreferencesOracle.java,
 * what Mobile does with each Evolution preference: defaults and choices in Evolution's vocabulary, the colours the
 * renderers fall back to, the chord each command answers to, and the outputs the options change (value characters,
 * Wire Poke readings, VHDL keyword case, pin dots, FPGA review warnings). scripts/oracle/preferences/compare.py
 * joins these rows with Evolution's per matrix row. Set LOGISIM_REGENERATE_FIXTURES=1 to rewrite the fixtures.
 */
class EditorPreferencesEvolutionTest {
    private val directory = listOf(File("fixtures/preferences"), File("../fixtures/preferences")).first(File::isDirectory)
    private val regenerate = System.getenv("LOGISIM_REGENERATE_FIXTURES") == "1"

    private fun check(name: String, content: String) {
        val file = File(directory, name)
        if (regenerate) file.writeText(content)
        assertEquals(file.readText(), content, name)
    }

    @Test
    fun drcFixturesAreTheProjectsMobileReviews() {
        drcProjects.forEach { (name, project) -> check(name, CircCodec.writeProject(project)) }
    }

    @Test
    fun mobileRowsMatchTheRecordedFile() {
        check("mobile.tsv", mobileRows().joinToString("") { it.joinToString("\t") + "\n" })
    }

    private fun mobileRows(): List<List<String>> = buildList {
        fun row(section: String, key: String, field: String, value: Any?) = add(listOf(section, key, field, value.toString()))
        val defaults = EditorPreferences()

        // Scalar preferences, in Evolution's stored vocabulary.
        fun pref(name: String, value: Any?) = row("pref", name, "default", value)
        pref("GATE_SHAPE", gateShape(defaults.gateShape))
        row("pref", "GATE_SHAPE", "options", EditorGateShape.entries.joinToString(",", transform = ::gateShape))
        // The gate shape default follows the system language, like AppPreferences.getDefaultGateShape().
        for ((tag, language) in listOf("ru" to AppLanguage.RUSSIAN, "pt" to AppLanguage.PORTUGUESE)) {
            row("locale-default", tag, "GATE_SHAPE", gateShape(defaultEditorGateShape(language)))
        }
        // New projects use the empty template, Evolution's default.templ (plain) or the template the user installed (custom).
        row("template", "TEMPLATE_TYPE", "default", ProjectTemplateStore(MemoryStorage(), ComponentCatalog.registry).type().name.lowercase())
        row("template", "TEMPLATE_TYPE", "options", ProjectTemplateType.entries.sortedBy { it.stored }.joinToString(",") { it.name.lowercase() })
        pref("LOCALE", if (defaults.locale == EditorLocale.SYSTEM) "" else defaults.locale.tag)
        row("pref", "LOCALE", "options", EditorLocale.entries.mapNotNull { it.language?.tag }.joinToString(","))
        pref("HdlType", defaults.hdlType.key)
        row("pref", "HdlType", "options", EditorHdlType.entries.joinToString(",") { it.key })
        pref("SuppressGatedClockWarnings", defaults.fpga.suppressGatedClockWarnings)
        pref("SuppressOpenPinWarnings", defaults.fpga.suppressOpenPinWarnings)
        pref("VhdlKeywordsUpperCase", defaults.fpga.vhdlKeywordsUpperCase)
        pref("SHOW_TICK_RATE", defaults.showTickRate)
        pref("TOOLBAR_PLACEMENT", toolbar(defaults.toolbarPlacement))
        row("pref", "TOOLBAR_PLACEMENT", "options", EditorToolbarPlacement.entries.joinToString(",", transform = ::toolbar))
        pref("USE_HEADER_BAR", defaults.showHeaderBar)
        pref("FollowSystemTheme", defaults.themeMode == EditorThemeMode.SYSTEM)
        pref("LIGHT_EDITOR_THEME", defaults.lightEditorTheme.key)
        row("pref", "LIGHT_EDITOR_THEME", "options", HdlEditorTheme.entries.joinToString(",") { it.key })
        pref("DARK_EDITOR_THEME", defaults.darkEditorTheme.key)
        row("pref", "DARK_EDITOR_THEME", "options", HdlEditorTheme.entries.joinToString(",") { it.key })
        pref("ATTRIBUTE_HALO", defaults.attributeHalo)
        pref("COMPONENT_TIPS", defaults.componentTips)
        pref("MOVE_KEEP_CONNECT", EditorState(null).keepConnectionsWhenMoving)
        pref("ADD_SHOW_GHOSTS", defaults.showPlacementPreview)
        pref("KMAP_LINED_STYLE", defaults.kmapLinedStyle)
        pref("ADD_AFTER", afterAdd(defaults.afterPlacement))
        row("pref", "ADD_AFTER", "options", EditorAfterPlacement.entries.joinToString(",", transform = ::afterAdd))
        pref("PinAppearance", defaults.pinAppearance.key)
        row("pref", "PinAppearance", "options", EditorPinAppearance.entries.joinToString(",") { it.key })
        pref("POKE_WIRE_RADIX1", radix(defaults.wirePokePrimaryRadix))
        row("pref", "POKE_WIRE_RADIX1", "options", WirePokeRadix.entries.joinToString(",", transform = ::radix))
        pref("POKE_WIRE_RADIX2", defaults.wirePokeSecondaryRadix?.let(::radix) ?: "none")
        row("pref", "POKE_WIRE_RADIX2", "options", (WirePokeRadix.entries.map(::radix) + "none").joinToString(","))
        pref("Memory_Startup_Unknown", SimulationProjectOptions().memoryStartupUnknown)
        pref("TICK_FREQUENCY", defaults.defaultTickFrequencyHz)
        pref("LAYOUT_SHOW_GRID", defaults.showGrid)
        pref("APPEARANCE_SHOW_GRID", defaults.appearanceShowGrid)
        pref("SEARCH_DOUBLE_SHIFT", defaults.searchDoubleShift)
        // Creation defaults (#313; their effect on saved projects is the creation-defaults oracle).
        pref("DefaultAppearance", defaults.defaultAppearance.value)
        row("pref", "DefaultAppearance", "options", EditorDefaultAppearance.entries.joinToString(",") { it.value })
        pref("NEW_INPUT_OUTPUT_SHAPES", defaults.newInputOutputShapes)
        pref("NAMED_CIRCUIT_BOXES_FIXED_SIZE", defaults.namedCircuitBoxesFixedSize)
        pref("REMOVE_UNUSED_LIBRARIES", defaults.removeUnusedLibraries)

        // Value characters: the defaults, the choices and the 4-bit 1010 / unknown / error / don't-care rendering.
        val characters = listOf<Triple<String, String, (ValueCharacters, Char) -> ValueCharacters>>(
            Triple("TRUE_CHAR", ValueCharacters.TRUE_CHOICES) { c, x -> c.copy(trueChar = x) },
            Triple("FALSE_CHAR", ValueCharacters.FALSE_CHOICES) { c, x -> c.copy(falseChar = x) },
            Triple("UNKNOWN_CHAR", ValueCharacters.UNKNOWN_CHOICES) { c, x -> c.copy(unknownChar = x) },
            Triple("ERROR_CHAR", ValueCharacters.ERROR_CHOICES) { c, x -> c.copy(errorChar = x) },
            Triple("DONTCARE_CHAR", ValueCharacters.DONT_CARE_CHOICES) { c, x -> c.copy(dontCareChar = x) },
        )
        val chosen = mapOf("TRUE_CHAR" to defaults.valueCharacters.trueChar, "FALSE_CHAR" to defaults.valueCharacters.falseChar,
            "UNKNOWN_CHAR" to defaults.valueCharacters.unknownChar, "ERROR_CHAR" to defaults.valueCharacters.errorChar,
            "DONTCARE_CHAR" to defaults.valueCharacters.dontCareChar)
        for ((name, choices, with) in characters) {
            pref(name, chosen.getValue(name))
            row("pref", name, "options", choices)
            for (choice in choices) {
                val set = with(defaults.valueCharacters, choice)
                row("char", name, choice.toString(), listOf(LogicVector.of(4, 0b1010u).display(set),
                    LogicVector.unknown(4).display(set), LogicVector.error(4).display(set), set.dontCareChar.toString()).joinToString(" "))
            }
        }

        // Colours each renderer falls back to, light and dark (EditorColor.defaultColor, DarkEditorColors).
        for ((name, role) in colorRoles) {
            row("pref", name, "default", hex(role.defaultColor(false).toArgb()))
            row("pref", name, "dark", hex(defaults.effectiveColors(dark = true)[role] ?: role.defaultColor(false).toArgb()))
        }
        row("pref", "TEXT_TOOL_COLOR", "default", defaults.textToolColor.removePrefix("#").uppercase())

        // Shortcuts: default chords and aliases, each resolved back to its command.
        for ((name, command) in shortcuts) {
            val chords = (listOf(command.defaultBinding) + command.defaultAliases).filterNot { it.isUnassigned }.map { binding ->
                chord(binding) + if (editorShortcutFor(binding, emptyMap()) == command) "" else "!unresolved"
            }
            pref(name, if (chords.isEmpty()) "none" else chords.joinToString(" | "))
        }

        // Wire Poke readings for each radix as primary (secondary at its default) and as secondary.
        for (radix in WirePokeRadix.entries) {
            for ((case, value) in pokeCases) {
                row("poke", "POKE_WIRE_RADIX1=${radix(radix)}", case,
                    wirePokeReadings(value, radix, defaults.wirePokeSecondaryRadix).joinToString(" / "))
                row("poke", "POKE_WIRE_RADIX2=${radix(radix)}", case,
                    wirePokeReadings(value, defaults.wirePokePrimaryRadix, radix).joinToString(" / "))
            }
        }

        VhdlKeywordCase.keywords.sorted().forEach { keyword ->
            row("vhdl", keyword, "upper lower", VhdlKeywordCase.apply(keyword, true) + " " + VhdlKeywordCase.apply(keyword, false))
        }

        // Pin dots: diameter and the offset from the terminal, in Evolution pixels (CircuitDrawingStyle.terminalRadius).
        for (appearance in EditorPinAppearance.entries) {
            val radius = CircuitDrawingStyle(pinDotDiameter = appearance.diameter).terminalRadius
            row("pin", appearance.key, "diameter offset", "${fmt(2 * radius)} ${fmt(radius)}")
        }

        // Built-in FPGA boards and the board selected before the user chooses one.
        val builtIn = listOf(File("composeApp/src/commonMain/composeResources/files/fpga-boards/index.txt"),
            File("src/commonMain/composeResources/files/fpga-boards/index.txt")).first(File::isFile)
            .readLines().filter(String::isNotBlank)
        val started = FpgaBoardCatalogStore(MemoryStorage()).load(builtIn).selected?.removePrefix(FpgaBoardCatalogStore.BUILTIN_PREFIX)
        pref("SelectedBoard", started)
        row("board", "SelectedBoard", "after-start", started)
        row("board", "SelectedBoard", "built-in", builtIn.joinToString(","))

        // FPGA review warnings for the DRC fixtures with each suppression setting.
        for ((name, project) in drcProjects) {
            for (gated in listOf(false, true)) for (open in listOf(false, true)) {
                val options = FpgaGenerationOptions(suppressGatedClockWarnings = gated, suppressOpenPinWarnings = open)
                val keys = FpgaCircuitRules.review(project, HdlLanguage.VHDL, options = options).diagnostics
                    .groupingBy { (if (it.severity.name == "ERROR") "error:" else "") + it.notice.key }.eachCount().toSortedMap()
                row("drc", name, "gated=$gated,open=$open", keys)
            }
        }
    }

    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun listNames(): List<String> = files.keys.toList()
        override fun read(name: String): String? = files[name]
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }

    private fun gateShape(shape: EditorGateShape) = when (shape) {
        EditorGateShape.ANSI -> "shaped"; EditorGateShape.IEC -> "rectangular"; EditorGateShape.DIN40700 -> "din40700"
    }

    private fun toolbar(placement: EditorToolbarPlacement) = when (placement) {
        EditorToolbarPlacement.TOP -> "north"; EditorToolbarPlacement.BOTTOM -> "south"; EditorToolbarPlacement.RIGHT -> "east"
        EditorToolbarPlacement.LEFT -> "west"; EditorToolbarPlacement.HIDDEN -> "hidden"; EditorToolbarPlacement.DOWN_MIDDLE -> "downMiddle"
    }

    private fun afterAdd(after: EditorAfterPlacement) = when (after) {
        EditorAfterPlacement.EDIT -> "edit"; EditorAfterPlacement.KEEP_TOOL -> "unchanged"
    }

    private fun radix(radix: WirePokeRadix) = when (radix) {
        WirePokeRadix.BINARY -> "2"; WirePokeRadix.OCTAL -> "8"; WirePokeRadix.SIGNED_DECIMAL -> "10signed"
        WirePokeRadix.UNSIGNED_DECIMAL -> "10unsigned"; WirePokeRadix.HEXADECIMAL -> "16"; WirePokeRadix.FLOATING_POINT -> "float"
    }

    private fun hex(argb: Int) = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private fun fmt(value: Double) = if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()

    private fun chord(binding: EditorShortcutBinding): String {
        val key = when (binding.key) {
            "↑" -> "UP"; "↓" -> "DOWN"; "→" -> "RIGHT"; "←" -> "LEFT"; "Insert" -> "INSERT"; "=" -> "EQUALS"; "+" -> "PLUS"
            "Num+" -> "ADD"; "-" -> "MINUS"; "Num-" -> "SUBTRACT"; else -> binding.key.uppercase()
        }
        return listOfNotNull("menu".takeIf { binding.primary }, "shift".takeIf { binding.shift }, key).joinToString("+")
    }

    private val colorRoles = listOf(
        "CANVAS_BG_COLOR" to EditorColor.CANVAS, "GRID_BG_COLOR" to EditorColor.GRID_BACKGROUND,
        "GRID_DOT_COLOR" to EditorColor.GRID_DOT, "GRID_ZOOMED_DOT_COLOR" to EditorColor.GRID_ZOOMED_DOT,
        "COMPONENT_COLOR" to EditorColor.COMPONENT, "COMPONENT_SECONDARY_COLOR" to EditorColor.COMPONENT_SECONDARY,
        "COMPONENT_GHOST_COLOR" to EditorColor.COMPONENT_GHOST, "COMPONENT_ICON_COLOR" to EditorColor.COMPONENT_ICON,
        "TRUE_COLOR" to EditorColor.SIGNAL_HIGH, "FALSE_COLOR" to EditorColor.SIGNAL_LOW,
        "UNKNOWN_COLOR" to EditorColor.SIGNAL_UNKNOWN, "ERROR_COLOR" to EditorColor.SIGNAL_ERROR,
        "NIL_COLOR" to EditorColor.SIGNAL_FLOATING, "BUS_COLOR" to EditorColor.SIGNAL_BUS, "STROKE_COLOR" to EditorColor.STROKE,
        "WIDTH_ERROR_COLOR" to EditorColor.WIDTH_ERROR, "WIDTH_ERROR_CAPTION_COLOR" to EditorColor.WIDTH_ERROR_CAPTION,
        "WIDTH_ERROR_HIGHLIGHT_COLOR" to EditorColor.WIDTH_ERROR_HIGHLIGHT,
        "WIDTH_ERROR_BACKGROUND_COLOR" to EditorColor.WIDTH_ERROR_BACKGROUND, "CLOCK_FREQUENCY_COLOR" to EditorColor.CLOCK_FREQUENCY,
        "KMAP_CELL_TEXT_COLOR" to EditorColor.KMAP_CELL_TEXT, "TABLE_CURSOR_COLOR" to EditorColor.TABLE_CURSOR,
        "TABLE_HIGHLIGHT_COLOR" to EditorColor.TABLE_HIGHLIGHT, "TABLE_SELECTION_COLOR" to EditorColor.TABLE_SELECTION,
        "FPGA_DEFINE_COLOR" to EditorColor.FPGA_DEFINE, "FPGA_DEFINE_HIGHLIGHT_COLOR" to EditorColor.FPGA_DEFINE_HIGHLIGHT,
        "FPGA_DEFINE_RESIZE_COLOR" to EditorColor.FPGA_DEFINE_RESIZE, "FPGA_DEFINE_MOVE_COLOR" to EditorColor.FPGA_DEFINE_MOVE,
        "FPGA_MAPPED_COLOR" to EditorColor.FPGA_MAPPED, "FPGA_SELECTED_MAPPED_COLOR" to EditorColor.FPGA_SELECTED_MAPPED,
        "FPGA_SELECTABLE_MAPPED_COLOR" to EditorColor.FPGA_SELECTABLE_MAPPED, "FPGA_SELECT_COLOR" to EditorColor.FPGA_SELECT,
    ) + EditorColor.kmapGroups.mapIndexed { index, role -> "KMAP${index + 1}_COLOR" to role }

    private val shortcuts = listOf(
        "HOTKEY_SIM_AUTO_PROPAGATE" to EditorShortcut.RUN, "HOTKEY_SIM_RESET" to EditorShortcut.RESET,
        "HOTKEY_SIM_STEP" to EditorShortcut.STEP_EVENT, "HOTKEY_SIM_TICK_HALF" to EditorShortcut.STEP_CLOCK,
        "HOTKEY_SIM_TICK_FULL" to EditorShortcut.STEP_CLOCK_CYCLE, "HOTKEY_SIM_TICK_ENABLED" to EditorShortcut.AUTOMATIC_TICKS,
        "HOTKEY_EDIT_UNDO" to EditorShortcut.UNDO, "HOTKEY_EDIT_REDO" to EditorShortcut.REDO,
        "HOTKEY_WINDOW_CLOSE" to EditorShortcut.WINDOW_CLOSE, "HOTKEY_FILE_EXPORT" to EditorShortcut.FILE_EXPORT,
        "HOTKEY_FILE_PRINT" to EditorShortcut.FILE_PRINT, "HOTKEY_SEARCH" to EditorShortcut.FIND_ACTION,
        "HOTKEY_DIR_NORTH" to EditorShortcut.FACE_NORTH, "HOTKEY_DIR_SOUTH" to EditorShortcut.FACE_SOUTH,
        "HOTKEY_DIR_EAST" to EditorShortcut.FACE_EAST, "HOTKEY_DIR_WEST" to EditorShortcut.FACE_WEST,
        "HOTKEY_EDIT_MENU_DUPLICATE" to EditorShortcut.DUPLICATE, "HOTKEY_EDIT_TOOL_DUPLICATE" to EditorShortcut.EDIT_TOOL_DUPLICATE,
        "HOTKEY_PROJ_MOVE_UP" to EditorShortcut.PROJ_MOVE_UP, "HOTKEY_PROJ_MOVE_DOWN" to EditorShortcut.PROJ_MOVE_DOWN,
        "HOTKEY_AUTO_LABEL_OPEN" to EditorShortcut.PLACEMENT_AUTO_LABEL, "HOTKEY_AUTO_LABEL_TOGGLE" to EditorShortcut.AUTO_LABEL_TOGGLE,
        "HOTKEY_AUTO_LABEL_VIEW" to EditorShortcut.AUTO_LABEL_VIEW, "HOTKEY_AUTO_LABEL_HIDE" to EditorShortcut.AUTO_LABEL_HIDE,
        "HOTKEY_AUTO_LABEL_SELF_NUMBERED_STOP" to EditorShortcut.AUTO_LABEL_SELF_NUMBERED_STOP,
        "HOTKEY_ADD_TOOL_ROTATE" to EditorShortcut.ADD_TOOL_ROTATE,
        "HOTKEY_GATE_MODIFIER_SIZE_SMALL" to EditorShortcut.GATE_MODIFIER_SIZE_SMALL,
        "HOTKEY_GATE_MODIFIER_SIZE_MEDIUM" to EditorShortcut.GATE_MODIFIER_SIZE_MEDIUM,
        "HOTKEY_GATE_MODIFIER_SIZE_WIDE" to EditorShortcut.GATE_MODIFIER_SIZE_WIDE,
        "HOTKEY_GATE_MODIFIER_INPUT_ADD" to EditorShortcut.GATE_MODIFIER_INPUT_ADD,
        "HOTKEY_GATE_MODIFIER_INPUT_SUB" to EditorShortcut.GATE_MODIFIER_INPUT_SUB,
    ) + (1..14).map { "HOTKEY_TOOL_SELECT_$it" to EditorShortcut.valueOf("TOOL_SELECT_$it") }

    private val pokeCases = linkedMapOf(
        "w1=1" to LogicVector.of(1, 1u), "w8=A5" to LogicVector.of(8, 0xA5u), "w8=05" to LogicVector.of(8, 0x05u),
        "w12=F80" to LogicVector.of(12, 0xF80u), "w32=3FC00000" to LogicVector.of(32, 0x3FC00000u),
        "w64=C000000000000000" to LogicVector.of(64, 0xC000000000000000uL),
        // Bits 3..0 = 1, unknown, 0, unknown (Value.createUnsafe(4, 0, 0b0101, 0b1000)); fromBits takes the LSB first.
        "w4=1x0x" to LogicVector.fromBits(listOf(LogicValue.UNKNOWN, LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.HIGH)),
        "w4=error" to LogicVector.error(4),
    )

    /** A clock gated by an AND with an open input (FpgaGenerationOptionsTest) and a clock taken from an input pin. */
    private val drcProjects: List<Pair<String, CircuitProject>> by lazy {
        val flip = CircuitComponent("flip", ComponentKind.D_FLIP_FLOP, GridPoint(300, 200))
        val ports = flip.layout().ports.associateBy { it.name }
        val gate = CircuitComponent("gate", ComponentKind.AND, ports.getValue("clock").position)
        val gated = CircuitDocument("main", listOf(flip, gate,
            CircuitComponent("clock", ComponentKind.CLOCK, gate.layout().inputs.first().position),
            CircuitComponent("data", ComponentKind.INPUT, ports.getValue("d").position),
            CircuitComponent("result", ComponentKind.OUTPUT, ports.getValue("q").position)))
        val pinClock = CircuitDocument("main", listOf(flip,
            CircuitComponent("clk", ComponentKind.INPUT, ports.getValue("clock").position),
            CircuitComponent("data", ComponentKind.INPUT, ports.getValue("d").position),
            CircuitComponent("result", ComponentKind.OUTPUT, ports.getValue("q").position)))
        // Evolution's DRC stops at unlabelled components, so every component carries a label (labels equal to a component name, such as "clock", are cleared on load).
        fun labelled(document: CircuitDocument) = CircuitProject(listOf(document.copy(components = document.components.map { it.copy(label = "u_${it.id}") })))
        listOf("drc-gated.circ" to labelled(gated), "drc-pin-clock.circ" to labelled(pinClock))
    }
}
