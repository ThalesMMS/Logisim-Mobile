package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.rememberLocalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Hue (0..360), saturation and value (0..1) of a color, kept apart from its RGB so slider moves do not drift. */
internal data class Hsv(val hue: Double, val saturation: Double, val value: Double)

/**
 * A color as Evolution's color attributes and the editor preferences hold it: 0xRRGGBB plus an optional alpha byte.
 * [hex] is lower-case `#rrggbb`, or `#rrggbbaa` with the alpha last, like the attribute text and [ThemeTextColor].
 */
internal data class ColorPickerModel(val rgb: Int, val alpha: Int? = null) {
    init {
        require(rgb in 0..0xFFFFFF) { "rgb" }
        require(alpha == null || alpha in 0..255) { "alpha" }
    }

    val hex: String get() = "#" + rgb.toString(16).padStart(6, '0') + (alpha?.toString(16)?.padStart(2, '0') ?: "")
    val rgbHex: String get() = "#" + rgb.toString(16).padStart(6, '0')
    val color: Color get() = Color(((((alpha ?: 0xFF).toLong()) shl 24) or rgb.toLong()).toInt())

    /** Rec. 601 luma below the midpoint: a mark drawn on it should be white. */
    val isDark: Boolean get() = (299 * (rgb shr 16 and 0xFF) + 587 * (rgb shr 8 and 0xFF) + 114 * (rgb and 0xFF)) / 1000 < 128

    fun toHsv(): Hsv {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val max = maxOf(r, g, b)
        val delta = (max - minOf(r, g, b)).toDouble()
        val hue = when {
            delta == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / delta).mod(6.0))
            max == g -> 60.0 * ((b - r) / delta + 2.0)
            else -> 60.0 * ((r - g) / delta + 4.0)
        }
        return Hsv(if (hue >= 360.0) hue - 360.0 else hue, if (max == 0) 0.0 else delta / max, max / 255.0)
    }

    companion object {
        /** `#rrggbb` or `#rrggbbaa` in either case; the `#` may be left out, as the preferences field allowed. */
        fun parse(raw: String): ColorPickerModel? {
            val digits = raw.trim().removePrefix("#")
            if (digits.length != 6 && digits.length != 8) return null
            if (digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
            return ColorPickerModel(digits.substring(0, 6).toInt(16), if (digits.length == 8) digits.substring(6).toInt(16) else null)
        }

        fun fromHsv(hsv: Hsv, alpha: Int? = null): ColorPickerModel {
            val hue = hsv.hue.coerceIn(0.0, 360.0).let { if (it >= 360.0) 0.0 else it }
            val saturation = hsv.saturation.coerceIn(0.0, 1.0)
            val value = hsv.value.coerceIn(0.0, 1.0)
            val chroma = value * saturation
            val sector = hue / 60.0
            val x = chroma * (1 - abs(sector.mod(2.0) - 1))
            val (r, g, b) = when (sector.toInt()) {
                0 -> Triple(chroma, x, 0.0)
                1 -> Triple(x, chroma, 0.0)
                2 -> Triple(0.0, chroma, x)
                3 -> Triple(0.0, x, chroma)
                4 -> Triple(x, 0.0, chroma)
                else -> Triple(chroma, 0.0, x)
            }
            val m = value - chroma
            fun channel(component: Double) = ((component + m) * 255.0).roundToInt().coerceIn(0, 255)
            return ColorPickerModel((channel(r) shl 16) or (channel(g) shl 8) or channel(b), alpha)
        }
    }
}

/**
 * A color sample that names its color for TalkBack: "<name>: <#hex>", plus whether it is the custom or the default
 * value when [customized] is known.
 */
@Composable
internal fun ColorSwatchButton(
    name: String,
    color: ColorPickerModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customized: Boolean? = null,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    val strings = rememberLocalizer()
    val description = strings("color_swatch_description", "name" to name, "hex" to color.hex)
    val state = customized?.let { strings(if (it) "color_state_custom" else "color_state_default") }
    Box(modifier.size(maxOf(size, 48.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics {
            contentDescription = description
            if (state != null) stateDescription = state
        }, contentAlignment = Alignment.Center) {
        ColorSample(color, size)
    }
}

/** The swatch drawn over a checkerboard, so a translucent color reads as translucent. */
@Composable
internal fun ColorSample(color: ColorPickerModel, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.size(size).background(Color.White, shape).border(1.dp, ChromeColors.Divider, shape)) {
        if ((color.alpha ?: 0xFF) < 0xFF) Row {
            Box(Modifier.size(size / 2).background(Color(0xFFCCCCCC)))
            Box(Modifier.size(size / 2))
        }
        Box(Modifier.size(size).background(color.color, shape))
    }
}

/** Material base hues beside Evolution's chooser swatches, as `#rrggbb`. */
internal val MATERIAL_SWATCHES = listOf("#f44336", "#e91e63", "#9c27b0", "#3f51b5", "#2196f3", "#009688", "#4caf50", "#ffc107")

/**
 * Evolution's ColorChooserButton dialog: swatches (Evolution's chooser colors, Material hues, the current and the default
 * color) that apply at once, hue/saturation/brightness sliders, an alpha slider when the attribute keeps alpha, and the
 * hex text as an advanced input. [onPick] receives the chosen color; [onRestoreDefault], when given, replaces picking
 * [default] (the preferences remove the custom value instead of storing the default).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ColorPickerDialog(
    title: String,
    initial: ColorPickerModel,
    default: ColorPickerModel?,
    alphaEnabled: Boolean,
    onPick: (ColorPickerModel) -> Unit,
    onDismiss: () -> Unit,
    onRestoreDefault: (() -> Unit)? = null,
) {
    val strings = rememberLocalizer()
    // Without alpha in the value the color stays `#rrggbb` (opaque) until the alpha slider moves, so an untouched pick
    // writes the same text the attribute had.
    val start = remember(initial, alphaEnabled) { if (alphaEnabled) initial else initial.copy(alpha = null) }
    var model by remember(start) { mutableStateOf(start) }
    var hsv by remember(start) { mutableStateOf(start.toHsv()) }
    var hex by remember(start) { mutableStateOf(start.hex) }
    fun pickNow(next: ColorPickerModel) {
        onPick(if (alphaEnabled) next else next.copy(alpha = null))
        onDismiss()
    }
    fun restoreDefault() {
        if (onRestoreDefault != null) { onRestoreDefault(); onDismiss() } else default?.let(::pickNow)
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exportEditorTestTags().testTag("color-picker"),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSample(model, 40.dp, Modifier.testTag("color-picker-preview"))
                    Text(model.hex, fontFamily = FontFamily.Monospace)
                }
                // A swatch tap applies the color at once, like Evolution's swatch panel; the alpha is kept.
                SwatchGrid(PALETTE, model) { pickNow(it.copy(alpha = model.alpha)) }
                Text(strings("color_picker_material"), style = MaterialTheme.typography.labelMedium)
                SwatchGrid(MATERIAL_SWATCHES, model) { pickNow(it.copy(alpha = model.alpha)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorSwatchButton(strings("color_picker_current"), initial, { pickNow(start) }, Modifier.testTag("color-swatch-current"), size = 28.dp)
                        Text(strings("color_picker_current"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (default != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorSwatchButton(strings("color_picker_default"), default, ::restoreDefault, Modifier.testTag("color-swatch-default"), size = 28.dp)
                        Text(strings("color_picker_default"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                ColorSlider(strings("color_picker_hue"), hsv.hue.toFloat(), 0f..360f, "${hsv.hue.roundToInt()}°", "color-picker-hue") {
                    hsv = hsv.copy(hue = it.toDouble()); model = ColorPickerModel.fromHsv(hsv, model.alpha); hex = model.hex
                }
                ColorSlider(strings("color_picker_saturation"), hsv.saturation.toFloat(), 0f..1f, "${(hsv.saturation * 100).roundToInt()}%", "color-picker-saturation") {
                    hsv = hsv.copy(saturation = it.toDouble()); model = ColorPickerModel.fromHsv(hsv, model.alpha); hex = model.hex
                }
                ColorSlider(strings("color_picker_value"), hsv.value.toFloat(), 0f..1f, "${(hsv.value * 100).roundToInt()}%", "color-picker-value") {
                    hsv = hsv.copy(value = it.toDouble()); model = ColorPickerModel.fromHsv(hsv, model.alpha); hex = model.hex
                }
                if (alphaEnabled) {
                    val alpha = model.alpha ?: 0xFF
                    ColorSlider(strings("color_picker_alpha"), alpha.toFloat(), 0f..255f, "${(alpha * 100 / 255.0).roundToInt()}%", "color-picker-alpha") {
                        model = model.copy(alpha = it.roundToInt().coerceIn(0, 255)); hex = model.hex
                    }
                }
                Text(strings("color_picker_advanced"), style = MaterialTheme.typography.labelMedium)
                val parsed = ColorPickerModel.parse(hex)?.takeIf { alphaEnabled || it.alpha == null }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        ColorPickerModel.parse(text)?.takeIf { alphaEnabled || it.alpha == null }?.let { next ->
                            model = next
                            hsv = model.toHsv()
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None),
                    label = { Text(strings(Messages.PreferencesColorValue)) },
                    isError = parsed == null,
                    supportingText = { Text(if (parsed == null) strings(Messages.PreferencesColorInvalid) else if (alphaEnabled) strings("color_hex_hint") else "") },
                    modifier = Modifier.fillMaxWidth().testTag("color-picker-hex"),
                )
                if (default != null) TextButton(onClick = ::restoreDefault, modifier = Modifier.heightIn(min = 48.dp).testTag("color-picker-restore")) {
                    Text(strings("color_picker_restore_default"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { pickNow(model) }, enabled = ColorPickerModel.parse(hex)?.let { alphaEnabled || it.alpha == null } == true,
                modifier = Modifier.heightIn(min = 48.dp).testTag("color-picker-apply")) { Text(strings(Messages.ActionApply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings(Messages.ActionCancel)) }
        },
    )
}

/** The spoken names of the chooser swatches; TalkBack reads "Red: #f00000" instead of the hex digits alone. */
internal val SWATCH_NAMES: Map<String, String> = mapOf(
    "#000000" to "color_name_black", "#404040" to "color_name_dark_gray", "#808080" to "color_name_gray",
    "#c0c0c0" to "color_name_light_gray", "#ffffff" to "color_name_white", "#f00000" to "color_name_red",
    "#ff8000" to "color_name_orange", "#ffff00" to "color_name_yellow", "#00f000" to "color_name_green",
    "#00d0d0" to "color_name_cyan", "#0000ff" to "color_name_blue", "#8000ff" to "color_name_violet",
    "#ff00ff" to "color_name_magenta", "#804000" to "color_name_brown", "#008000" to "color_name_dark_green",
    "#000080" to "color_name_navy", "#f44336" to "color_name_red", "#e91e63" to "color_name_pink",
    "#9c27b0" to "color_name_purple", "#3f51b5" to "color_name_indigo", "#2196f3" to "color_name_blue",
    "#009688" to "color_name_teal", "#4caf50" to "color_name_green", "#ffc107" to "color_name_amber",
)

/** The touch size of one swatch; a row holds as many as fit, up to eight. */
private val SWATCH_CELL = 48.dp

/**
 * Swatches in rows of full 48 dp touch targets: five or six on a phone dialog, eight where they fit. A tap applies the
 * color at once, so neighbouring swatches must not share a target. The current color is marked and announced as selected.
 */
@Composable
private fun SwatchGrid(swatches: List<String>, current: ColorPickerModel, onChoose: (ColorPickerModel) -> Unit) {
    val strings = rememberLocalizer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / SWATCH_CELL).toInt().coerceIn(1, 8)
        Column {
            swatches.chunked(columns).forEach { row ->
                Row {
                    row.forEach { swatch ->
                        val sample = checkNotNull(ColorPickerModel.parse(swatch))
                        val chosen = sample.rgb == current.rgb
                        val description = SWATCH_NAMES[swatch]?.let { strings("color_swatch_description", "name" to strings(it), "hex" to swatch) }
                            ?: swatch
                        Box(Modifier.size(SWATCH_CELL).clickable(role = Role.Button) { onChoose(sample) }
                            .semantics { contentDescription = description; selected = chosen }.testTag("color-swatch-$swatch"),
                            contentAlignment = Alignment.Center) {
                            ColorSample(sample, SWATCH_CELL - 8.dp)
                            if (chosen) EditorIconGlyph(EditorIcon.CHECK, Modifier.size(SWATCH_CELL / 2),
                                tint = if (sample.isDark) Color.White else Color.Black)
                        }
                    }
                }
            }
        }
    }
}

/** A slider whose spoken state is the value shown beside it (120°, 50%), not a percent of its range. */
@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, tag: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(display, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
        }
        Slider(value, onChange, valueRange = range, modifier = Modifier.fillMaxWidth().widthIn(min = 120.dp).heightIn(min = 48.dp)
            .testTag(tag).semantics { contentDescription = label; stateDescription = display })
    }
}
