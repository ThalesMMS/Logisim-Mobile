package dev.logisim.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.localization.rememberLocalizer

/** Evolution's font styles in the order of its font chooser. */
internal enum class FontStyleChoice(val bold: Boolean, val italic: Boolean) {
    PLAIN(false, false), BOLD(true, false), ITALIC(false, true), BOLD_ITALIC(true, true);

    companion object {
        fun of(bold: Boolean, italic: Boolean): FontStyleChoice = entries.first { it.bold == bold && it.italic == italic }
    }
}

/**
 * A font as Evolution's FontAttribute writes it, `Family style size` ([AnnotationFont.serialized]). The chooser keeps a
 * family it does not list (a desktop file may name "DejaVu Sans") and reports a change only when the font differs, so an
 * imported string is written back exactly as it was read.
 */
internal data class FontChoice(val family: String, val style: FontStyleChoice, val size: Int) {
    init { require(family.isNotBlank() && size in SIZE_RANGE) }

    val font: AnnotationFont get() = AnnotationFont(family, size, style.bold, style.italic)
    val serialized: String get() = font.serialized

    companion object {
        val SIZE_RANGE = 1..256
        /** Evolution's size presets. */
        val SIZE_PRESETS = listOf(8, 10, 12, 14, 16, 18, 24)

        fun from(font: AnnotationFont) = FontChoice(font.family, FontStyleChoice.of(font.bold, font.italic), font.size)

        fun parse(raw: String): FontChoice? = AnnotationFont.parse(raw)?.let(::from)

        /** The families offered: Java's logical families, then [current] when it is none of them. */
        fun families(current: String): List<String> = if (current in FONT_FAMILIES) FONT_FAMILIES else FONT_FAMILIES + current

        fun isCustomFamily(family: String) = family !in FONT_FAMILIES

        /** The text to store after the chooser offered [next] for [current]: null when the font is unchanged. */
        fun update(current: String, next: String): String? {
            val before = parse(current)
            val after = parse(next) ?: return null
            return if (before == after) null else after.serialized
        }
    }
}

/**
 * A font chooser for a `Family style size` attribute, built from the same controls as the appearance editor's text: the
 * family menu ("Custom: X" for a family read from a file), Bold and Italic chips, and the size stepper with Evolution's
 * presets. It reports only real changes, so an untouched value round-trips byte for byte. Tags start with [tag].
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FontChoiceField(current: String, onChange: (String) -> Unit, tag: String = "font") {
    val strings = rememberLocalizer()
    val choice = FontChoice.parse(current) ?: FontChoice("SansSerif", FontStyleChoice.PLAIN, 12)
    fun offer(next: FontChoice) {
        FontChoice.update(current, next.serialized)?.let(onChange)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FontFamilyMenu(strings("font_family"), choice.family, { offer(choice.copy(family = it)) }, tag = "$tag-family")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bold, label) in listOf(true to "font_style_bold", false to "font_style_italic")) {
                val on = if (bold) choice.style.bold else choice.style.italic
                FilterChip(selected = on, onClick = {
                    val style = if (bold) FontStyleChoice.of(!on, choice.style.italic) else FontStyleChoice.of(choice.style.bold, !on)
                    offer(choice.copy(style = style))
                }, label = { Text(strings(label)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (bold) "$tag-style-b" else "$tag-style-i"))
            }
        }
        FontSizeStepper(strings("font_size"), choice.size, choice.size.toString(), { offer(choice.copy(size = it)) }, tag = "$tag-size")
    }
}

/** A family menu listing Java's logical families, and "Custom: X" for a family read from a file. */
@Composable
internal fun FontFamilyMenu(label: String, family: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, tag: String = "font-family-menu") {
    val strings = rememberLocalizer()
    var open by remember { mutableStateOf(false) }
    fun display(value: String) = if (FontChoice.isCustomFamily(value)) strings("font_custom_family", "family" to value) else value
    Box(modifier) {
        ChoiceField(label, display(family), onClick = { open = true }, tag = tag)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontChoice.families(family).forEach { value ->
                DropdownMenuItem(text = { Text(display(value)) }, onClick = {
                    open = false
                    if (value != family) onChange(value)
                }, leadingIcon = { SelectedMark(value == family) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = value == family }.testTag("$tag-$value"))
            }
        }
    }
}

/** A size stepper with Evolution's presets; [display] keeps a non-integer size read from a file visible until changed. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FontSizeStepper(label: String, size: Int, display: String, onChange: (Int) -> Unit, tag: String = "font-size") {
    val strings = rememberLocalizer()
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ChromeColors.Muted)
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = size > FontChoice.SIZE_RANGE.first, onClick = { onChange(size - 1) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings("font_size_decrease") }) { Text("−", fontSize = 18.sp) }
            Text(display, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 28.dp).testTag("$tag-value")
                .semantics { contentDescription = "$label $display" })
            TextButton(enabled = size < FontChoice.SIZE_RANGE.last, onClick = { onChange(size + 1) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings("font_size_increase") }) { Text("+", fontSize = 18.sp) }
            FontChoice.SIZE_PRESETS.forEach { preset ->
                val chosen = display == preset.toString()
                Box(Modifier.size(48.dp).clickable(role = Role.RadioButton) { if (!chosen) onChange(preset) }
                    .semantics { selected = chosen }.testTag("$tag-preset-$preset"), contentAlignment = Alignment.Center) {
                    Text(preset.toString(), fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** A labelled field that opens a menu: the role, the current value and a drop-down arrow, as TalkBack announces them. */
@Composable
internal fun ChoiceField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    val strings = rememberLocalizer()
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.DropdownList, onClickLabel = strings("preferences_choice_change"), onClick = onClick)
        .semantics(mergeDescendants = true) { stateDescription = value }
        .then(if (tag == null) Modifier else Modifier.testTag(tag))
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = ChromeColors.Muted)
            Text(value, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics {})
        }
        DropDownArrow(ChromeColors.Muted)
    }
}

/** Material's ArrowDropDown: the trailing sign that a row opens a menu. */
@Composable
internal fun DropDownArrow(tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(24.dp).clearAndSetSemantics {}) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 7 / 24, size.height * 10 / 24)
            lineTo(size.width * 17 / 24, size.height * 10 / 24)
            lineTo(size.width / 2, size.height * 15 / 24)
            close()
        }
        drawPath(path, tint)
    }
}

/** The leading mark of the current menu item; other items keep the same indent. */
@Composable
internal fun SelectedMark(selected: Boolean) {
    if (selected) EditorIconGlyph(EditorIcon.CHECK, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    else Spacer(Modifier.size(20.dp))
}
