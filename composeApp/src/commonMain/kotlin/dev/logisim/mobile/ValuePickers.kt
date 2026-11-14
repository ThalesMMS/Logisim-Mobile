package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

/*
 * Value pickers shared by the property inspector and other editors: Evolution's color chooser swatches for
 * `#rrggbb[aa]` attribute text, and its font chooser (family, style, size) for `Family style size` text.
 * They edit the same serialized text the attribute stores.
 */

internal fun hexColor(raw: String): Color? {
    if (!isHexColor(raw)) return null
    val rgb = raw.substring(1, 7).toLong(16)
    val alpha = if (raw.length == 9) raw.substring(7, 9).toInt(16) else 0xff
    return Color(((alpha.toLong() shl 24) or rgb).toInt())
}

@Composable
internal fun ColorSwatch(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Box(modifier.size(size).background(color, RoundedCornerShape(3.dp)).border(1.dp, ChromeColors.Divider, RoundedCornerShape(3.dp)))
}

/** Evolution's color chooser swatches, as #rrggbb; a chosen swatch keeps the value's alpha. */
internal val PALETTE = listOf(
    "#000000", "#404040", "#808080", "#c0c0c0", "#ffffff", "#f00000", "#ff8000", "#ffff00",
    "#00f000", "#00d0d0", "#0000ff", "#8000ff", "#ff00ff", "#804000", "#008000", "#000080",
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ColorPalette(current: String, onChoose: (String) -> Unit) {
    val strings = rememberLocalizer()
    val alpha = current.takeIf { isHexColor(it) && it.length == 9 }?.substring(7).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PALETTE.forEach { swatch ->
                    val chosen = current.take(7).equals(swatch, ignoreCase = true)
                    Box(Modifier.size(32.dp).clickable(role = Role.RadioButton, onClickLabel = swatch) { onChoose(swatch + alpha) }
                        .semantics { contentDescription = swatch; selected = chosen }
                        .testTag("color-swatch-$swatch"), contentAlignment = Alignment.Center) {
                        ColorSwatch(hexColor(swatch)!!, size = 28.dp)
                        if (chosen) Text("✓", color = if (swatch in DARK_SWATCHES) Color.White else Color.Black, fontSize = 14.sp)
                    }
                }
        }
        hexColor(current)?.let { preview ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorSwatch(preview, size = 24.dp)
                Text(strings("color_hex_hint"), fontSize = 12.sp, color = ChromeColors.Muted)
            }
        }
    }
}

private val DARK_SWATCHES = setOf("#000000", "#404040", "#808080", "#f00000", "#0000ff", "#8000ff", "#804000", "#008000", "#000080")

internal fun AnnotationFontParse(raw: String) = dev.logisim.mobile.core.AnnotationFont.parse(raw)

/** Evolution's font chooser: a family, a style and a size, written back as "Family style size". */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FontChooser(current: String, onChange: (String) -> Unit) {
    val strings = rememberLocalizer()
    val font = AnnotationFontParse(current) ?: dev.logisim.mobile.core.AnnotationFont("SansSerif", 16, bold = true)
    fun write(next: dev.logisim.mobile.core.AnnotationFont) = onChange(next.serialized)
    var familyMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings("font_family"), fontSize = 12.sp, color = ChromeColors.Muted)
        Box {
            TextButton(onClick = { familyMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("font-family")) {
                Text(font.family + "  ▾")
            }
            DropdownMenu(expanded = familyMenu, onDismissRequest = { familyMenu = false }) {
                (FONT_FAMILIES + font.family).distinct().forEach { family ->
                    DropdownMenuItem(text = { Text(family) }, leadingIcon = { Text(if (family == font.family) "✓" else "", Modifier.width(20.dp)) },
                        onClick = { familyMenu = false; write(font.copy(family = family)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Text(strings("font_style"), fontSize = 12.sp, color = ChromeColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to false, true to false, false to true, true to true).forEach { (bold, italic) ->
                val chosen = font.bold == bold && font.italic == italic
                FilterChip(selected = chosen, onClick = { write(font.copy(bold = bold, italic = italic)) },
                    label = { Text(strings(fontStyleLabel(bold, italic)), fontSize = 12.sp) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("font-style-${if (bold) "b" else ""}${if (italic) "i" else ""}"))
            }
        }
        Text(strings("font_size"), fontSize = 12.sp, color = ChromeColors.Muted)
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = font.size > 1, onClick = { write(font.copy(size = font.size - 1)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings("font_size_decrease") }) { Text("−", fontSize = 18.sp) }
            Text(font.size.toString(), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("font-size"))
            TextButton(enabled = font.size < 256, onClick = { write(font.copy(size = font.size + 1)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings("font_size_increase") }) { Text("+", fontSize = 18.sp) }
            FONT_SIZES.forEach { size ->
                TextButton(onClick = { write(font.copy(size = size)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("font-size-preset-$size")) {
                    Text(size.toString(), fontWeight = if (size == font.size) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/**
 * The common values of a typed number, such as the usual bit widths or Evolution's NE555 resistances, as one row of
 * chips under the field: one tap puts the stored value in the field, ready to apply (C5, C6). The heading stays at
 * the start of the row, wrapped to two short lines, and only the chips scroll sideways, so they keep most of the
 * width. One row keeps the field and the dialog's buttons where they are, also beside the keyboard in a short window.
 * Nothing is shown when the field has no presets.
 */
@Composable
internal fun PropertyValuePresets(field: PropertyEditorField, current: String, onChoose: (String) -> Unit) {
    if (field.presets.isEmpty()) return
    val strings = rememberLocalizer()
    Row(Modifier.fillMaxWidth().testTag("inspector-presets-${field.name}"),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(strings("inspector_presets"), fontSize = 12.sp, lineHeight = 14.sp, color = ChromeColors.Muted, maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = PresetHeadingMaxWidth).testTag("inspector-presets-heading-${field.name}"))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            field.presets.forEach { preset ->
                val chosen = preset.value == current
                FilterChip(selected = chosen, onClick = { onChoose(preset.value) }, label = { Text(strings(preset.label)) },
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { selected = chosen }
                        .testTag("inspector-preset-${field.name}-${preset.value}"))
            }
        }
    }
}

/** The widest the "Common values" heading gets beside the chips; longer translations wrap to a second line. */
private val PresetHeadingMaxWidth = 72.dp

/** Java's logical font families, which Evolution's font chooser lists first. */
internal val FONT_FAMILIES = listOf("SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput")
private val FONT_SIZES = listOf(10, 12, 16, 20)

/** Evolution's color attribute text: `#rrggbb`, or `#rrggbbaa` with alpha last. */
internal fun isHexColor(raw: String): Boolean = HEX_COLOR.matches(raw)

private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

/** A font attribute (`SansSerif bold 16`) shown with a localized style, such as "SansSerif · Negrito · 16". */
internal fun localizedFontValue(raw: String): LocalizedText? {
    val font = AnnotationFont.parse(raw) ?: return null
    return LocalizedText.join(listOf(LocalizedText.literal(font.family), fontStyleLabel(font.bold, font.italic),
        LocalizedText.literal(font.size.toString())), " · ")
}

internal fun fontStyleLabel(bold: Boolean, italic: Boolean): LocalizedText = message(when {
    bold && italic -> "font_style_bold_italic"
    bold -> "font_style_bold"
    italic -> "font_style_italic"
    else -> "font_style_plain"
})
