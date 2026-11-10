package dev.logisim.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import kotlin.math.max
import kotlin.math.min

data class CircuitSignalPalette(
    val background: Color,
    val low: Color,
    val high: Color,
    val unknown: Color,
    val error: Color,
    val bus: Color,
) {
    fun color(value: LogicValue?): Color = when (value) {
        LogicValue.LOW -> low
        LogicValue.HIGH -> high
        LogicValue.UNKNOWN, null -> unknown
        LogicValue.ERROR -> error
    }

    fun color(value: LogicVector?): Color = when {
        value == null -> unknown
        value.errorMask != 0uL -> error
        value.unknownMask != 0uL -> unknown
        value.width > 1 -> bus
        else -> color(value.bit(0))
    }

    companion object {
        /** AppPreferences DEFAULT_FALSE/TRUE/UNKNOWN/ERROR/BUS_COLOR of the light theme. */
        val Classic = CircuitSignalPalette(Color.White, Color(0xFF006400), Color(0xFF00D200), Color(0xFF2828FF), Color(0xFFC00000), Color.Black)
        val HighContrast = CircuitSignalPalette(Color.White, Color(0xFF005A00), Color(0xFF008A00), Color(0xFF0000B0), Color(0xFFBA0000), Color.Black)
    }
}

/** AppPreferences DEFAULT_COMPONENT_COLOR: the ink of components, labels and exports in the light theme. */
internal val DefaultComponentInk = Color(0xFF000000)

/** AppPreferences DEFAULT_NIL_COLOR. */
internal val DefaultNilColor = Color(0xFF808080)

internal fun EditorColor.defaultColor(highContrastSignals: Boolean): Color {
    val signals = if (highContrastSignals) CircuitSignalPalette.HighContrast else CircuitSignalPalette.Classic
    return when (this) {
        EditorColor.CANVAS, EditorColor.GRID_BACKGROUND -> signals.background
        // AppPreferences DEFAULT_GRID_DOT/GRID_ZOOMED_DOT/COMPONENT*_COLOR.
        EditorColor.GRID_DOT -> Color(0xFF777777)
        EditorColor.GRID_ZOOMED_DOT -> Color(0xFFCCCCCC)
        EditorColor.COMPONENT -> DefaultComponentInk
        EditorColor.COMPONENT_SECONDARY -> Color(0xFF999999)
        EditorColor.COMPONENT_GHOST -> Color(0xFF999999)
        EditorColor.COMPONENT_ICON -> Color(0xFF000000)
        EditorColor.SIGNAL_HIGH -> signals.high
        EditorColor.SIGNAL_LOW -> signals.low
        EditorColor.SIGNAL_UNKNOWN -> signals.unknown
        // DEFAULT_NIL_COLOR: wires whose bundle has no width (Value.NIL).
        EditorColor.SIGNAL_FLOATING -> DefaultNilColor
        EditorColor.SIGNAL_ERROR -> signals.error
        EditorColor.SIGNAL_BUS -> signals.bus
        // Evolution AppPreferences DEFAULT_FPGA_* colours.
        EditorColor.FPGA_DEFINE -> Color(0xFFFF0000)
        EditorColor.FPGA_DEFINE_HIGHLIGHT -> Color(0xFF00FF00)
        EditorColor.FPGA_DEFINE_RESIZE -> Color(0xFF00FFFF)
        EditorColor.FPGA_DEFINE_MOVE -> Color(0xFFFF00FF)
        EditorColor.FPGA_MAPPED -> Color(0xFF005000)
        EditorColor.FPGA_SELECTED_MAPPED -> Color(0xFFFF0000)
        EditorColor.FPGA_SELECTABLE_MAPPED -> Color(0xFF00A000)
        EditorColor.FPGA_SELECT -> Color(0xFF0000FF)
        // AppPreferences DEFAULT_* values of the light theme; DarkEditorColors holds the dark ones.
        EditorColor.TEXT_TOOL -> Color(0xFF000000)
        EditorColor.STROKE -> Color(0xFFFF00FF)
        EditorColor.WIDTH_ERROR -> Color(0xFFFF7B00)
        EditorColor.WIDTH_ERROR_CAPTION -> Color(0xFF550000)
        EditorColor.WIDTH_ERROR_HIGHLIGHT -> Color(0xFFFFFF00)
        EditorColor.WIDTH_ERROR_BACKGROUND -> Color(0xFFFFE6D2)
        EditorColor.CLOCK_FREQUENCY -> Color(0xFFFF00B4)
        EditorColor.KMAP_CELL_TEXT -> Color(0xFF0000FF)
        EditorColor.TABLE_CURSOR -> Color(0xFFFFFFFF)
        EditorColor.TABLE_HIGHLIGHT -> Color(0xFFFFFFC0)
        EditorColor.TABLE_SELECTION -> Color(0xFFC0C0FF)
        EditorColor.KMAP1 -> Color(0xFF800000)
        EditorColor.KMAP2 -> Color(0xFFE6194B)
        EditorColor.KMAP3 -> Color(0xFFFABEBE)
        EditorColor.KMAP4 -> Color(0xFFAA6E28)
        EditorColor.KMAP5 -> Color(0xFFF58230)
        EditorColor.KMAP6 -> Color(0xFFFFD7B4)
        EditorColor.KMAP7 -> Color(0xFF808000)
        EditorColor.KMAP8 -> Color(0xFFFFFF19)
        EditorColor.KMAP9 -> Color(0xFFD2F53C)
        EditorColor.KMAP10 -> Color(0xFF000080)
        EditorColor.KMAP11 -> Color(0xFF911EB4)
        EditorColor.KMAP12 -> Color(0xFF3CB4AF)
        EditorColor.KMAP13 -> Color(0xFF0082CB)
        EditorColor.KMAP14 -> Color(0xFFE6BEFF)
        EditorColor.KMAP15 -> Color(0xFFAAFFC3)
        EditorColor.KMAP16 -> Color(0xFFF032E6)
    }
}

internal fun Map<EditorColor, Int>.fpgaColor(role: EditorColor): Color = color(role, role.defaultColor(false))

/** A color whose default does not depend on the signal palette, over [effectiveColors][EditorPreferences.effectiveColors]. */
internal fun Map<EditorColor, Int>.themeColor(role: EditorColor): Color = color(role, role.defaultColor(false))

internal fun Map<EditorColor, Int>.color(role: EditorColor, fallback: Color): Color =
    get(role).editorColorOr(fallback)

internal fun Int?.editorColorOr(fallback: Color): Color = this?.let { Color(0xFF000000.toInt() or it) } ?: fallback

internal fun Map<EditorColor, Int>.signalColor(
    vector: LogicVector?,
    scalar: LogicValue?,
    floating: Boolean,
    defaults: CircuitSignalPalette,
): Color {
    if (vector?.errorMask?.let { it != 0uL } == true || scalar == LogicValue.ERROR) return color(EditorColor.SIGNAL_ERROR, defaults.error)
    // Value.getColor(): a bundle without width is NIL whatever its value.
    if (floating) return color(EditorColor.SIGNAL_FLOATING, DefaultNilColor)
    if (vector != null) {
        if (vector.width > 1) {
            if (EditorColor.SIGNAL_BUS in this || vector.unknownMask == 0uL) return color(EditorColor.SIGNAL_BUS, defaults.bus)
            return color(EditorColor.SIGNAL_UNKNOWN, defaults.unknown)
        }
    }
    return when (vector?.bit(0) ?: scalar) {
        LogicValue.LOW -> color(EditorColor.SIGNAL_LOW, defaults.low)
        LogicValue.HIGH -> color(EditorColor.SIGNAL_HIGH, defaults.high)
        LogicValue.ERROR -> color(EditorColor.SIGNAL_ERROR, defaults.error)
        LogicValue.UNKNOWN, null -> color(EditorColor.SIGNAL_UNKNOWN, defaults.unknown)
    }
}

fun circuitContrastRatio(foreground: Color, background: Color): Double {
    require(foreground.alpha == 1f && background.alpha == 1f)
    val a = foreground.luminance().toDouble()
    val b = background.luminance().toDouble()
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}
