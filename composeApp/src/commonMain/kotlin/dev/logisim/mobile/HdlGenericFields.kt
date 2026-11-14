package dev.logisim.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.HdlGenericDeclaration
import dev.logisim.mobile.localization.rememberLocalizer

/*
 * Evolution's VhdlEntityAttributes turns each generic into its own attribute in the table. Here each declared
 * generic gets its own field, and every field reads and writes one NAME=value line of the panel's parameter text.
 * Apply turns that text into generic.<name> attributes exactly as before, so the .circ output does not change. A
 * time field writes Evolution's femtosecond count, never "10 ns", which desktop's Long.parseLong would reject.
 */

/** The value of [name] in NAME=value lines, matched without case as hdlGenericAttributes matches duplicates. */
internal fun hdlGenericValue(text: String, name: String): String? =
    text.lineSequence().firstOrNull { '=' in it && it.substringBefore('=').trim().equals(name, ignoreCase = true) }
        ?.substringAfter('=')?.trim()

/**
 * [text] with the line of [name] set to [value], keeping the line's own spelling of the name and the other lines in
 * order. A new value is a new last line; an empty one removes the line, so the entity's default applies.
 */
internal fun hdlGenericTextWith(text: String, name: String, value: String): String {
    val lines = text.lines().filter { it.isNotBlank() }
    val index = lines.indexOfFirst { '=' in it && it.substringBefore('=').trim().equals(name, ignoreCase = true) }
    val trimmed = value.trim()
    return when {
        index < 0 && trimmed.isEmpty() -> lines
        index < 0 -> lines + "$name=$trimmed"
        trimmed.isEmpty() -> lines.filterIndexed { line, _ -> line != index }
        else -> lines.mapIndexed { line, old -> if (line == index) "${old.substringBefore('=').trim()}=$trimmed" else old }
    }.joinToString("\n")
}

/** The NAME=value lines no declared generic reads: overrides kept from the file, shown and never dropped. */
internal fun hdlGenericExtras(text: String, declarations: List<HdlGenericDeclaration>): List<String> =
    text.lineSequence().filter { it.isNotBlank() }.map { it.substringBefore('=').trim() }
        .filter { name -> declarations.none { it.name.equals(name, ignoreCase = true) } }.toList()

/** The value an empty field stands for: the declared default, or Evolution's VhdlParser 1 or 0 for a number. */
internal fun HdlGenericDeclaration.placeholder(): String = declaredDefault.ifEmpty {
    when (kind) {
        "positive" -> "1"
        "integer", "natural", "time" -> "0"
        else -> ""
    }
}

/** The message key of a numeric value outside the generic's type, or null when it fits or is empty. */
internal fun hdlGenericNumberError(kind: String, value: String): String? {
    if (value.isBlank()) return null
    val number = value.trim().toIntOrNull() ?: return "hdl_tools_sim_generic_invalid_integer"
    return when {
        kind == "natural" && number < 0 -> "hdl_tools_sim_generic_invalid_natural"
        kind == "positive" && number < 1 -> "hdl_tools_sim_generic_invalid_positive"
        else -> null
    }
}

/** The units HdlIpDeclarations reads for a time generic, with their size in femtoseconds. */
private val timeScales = linkedMapOf("fs" to 1L, "ps" to 1_000L, "ns" to 1_000_000L, "us" to 1_000_000_000L)
private val timeUnits = timeScales.keys.toList()
private val timeValue = Regex("\\s*(-?[0-9]+)\\s*([A-Za-z]*)\\s*")

/** A time generic as its field shows it: a whole number and one of fs, ps, ns and us. */
internal data class HdlTimeValue(val number: String, val unit: String)

/**
 * How the time field shows a stored [value]. Evolution's VhdlGenericAttribute stores a whole count of femtoseconds
 * with no unit (HdlIpDeclarations reads a bare number as fs too), so a bare count is shown in [preferred] when that
 * unit divides it, else in the largest unit that does: 10000000 is 10 ns, 5 is 5 fs. Text with a unit, written in the
 * text editor, is shown as written. Null for anything else (1 ms, an expression), which stays plain text.
 */
internal fun hdlTimeShown(value: String, preferred: String? = null): HdlTimeValue? {
    if (value.isBlank()) return HdlTimeValue("", preferred ?: "ns")
    val match = timeValue.matchEntire(value) ?: return null
    val unit = match.groupValues[2].lowercase()
    if (unit.isNotEmpty()) return if (unit in timeScales) HdlTimeValue(match.groupValues[1], unit) else null
    val count = match.groupValues[1].toLongOrNull() ?: return null
    val shown = preferred?.takeIf { it in timeScales && count % timeScales.getValue(it) == 0L }
        ?: if (count == 0L) "fs" else timeUnits.last { count % timeScales.getValue(it) == 0L }
    return HdlTimeValue((count / timeScales.getValue(shown)).toString(), shown)
}

/**
 * The stored value of [number] [unit]: the femtosecond count Evolution's VhdlGenericAttribute parses with
 * Long.parseLong, or null when it is not a whole number or falls outside Evolution's Integer range.
 */
internal fun hdlTimeStored(number: String, unit: String): String? {
    val count = number.trim().toLongOrNull() ?: return null
    val scale = timeScales[unit] ?: return null
    if (count > Int.MAX_VALUE / scale || count < Int.MIN_VALUE / scale) return null
    return (count * scale).toString()
}

private val stdLogicValues = listOf("U", "X", "0", "1", "Z", "W", "L", "H", "-")

/** One field per declared generic, the extra parameters that are kept, and the switch to the text editor. */
@Composable
internal fun HdlGenericFields(
    declarations: List<HdlGenericDeclaration>,
    text: String,
    enabled: Boolean,
    onText: (String) -> Unit,
    onEditAsText: () -> Unit,
) {
    val strings = rememberLocalizer()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings("hdl_tools_sim_generics_heading"), style = MaterialTheme.typography.titleSmall)
        Text(strings("hdl_tools_sim_generics_help"), style = MaterialTheme.typography.bodySmall)
        for (declaration in declarations) {
            val value = hdlGenericValue(text, declaration.name).orEmpty()
            HdlGenericField(declaration, value, enabled) { onText(hdlGenericTextWith(text, declaration.name, it)) }
        }
        hdlGenericExtras(text, declarations).takeIf { it.isNotEmpty() }?.let { names ->
            Text(strings("hdl_tools_sim_generics_extra", "names" to names.joinToString()), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onEditAsText, enabled = enabled) { Text(strings("hdl_tools_sim_generics_as_text")) }
    }
}

@Composable
private fun HdlGenericField(declaration: HdlGenericDeclaration, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val strings = rememberLocalizer()
    val label = strings("hdl_tools_sim_generic_label", "name" to declaration.name, "type" to declaration.kind)
    val tag = "hdl-sim-generic-${declaration.name}"
    val code = TextStyle(fontFamily = FontFamily.Monospace)
    fun defaultOption(shown: String) = strings("hdl_tools_sim_generic_default_option", "value" to shown)
    // The placeholder shows only while the field has focus, so the default is also written under it.
    val default: (@Composable () -> Unit)? = declaration.placeholder().takeIf { it.isNotEmpty() }?.let { shown ->
        { Text(strings("hdl_tools_sim_generic_default", "value" to shown)) }
    }
    when (declaration.kind) {
        "integer", "natural", "positive" -> {
            val error = hdlGenericNumberError(declaration.kind, value)
            OutlinedTextField(value, onValue, label = { Text(label) }, placeholder = { Text(declaration.placeholder()) },
                enabled = enabled, singleLine = true, isError = error != null,
                supportingText = error?.let { { Text(strings(it)) } } ?: default, textStyle = code,
                keyboardOptions = if (declaration.kind == "integer") FieldKeyboards.Code else FieldKeyboards.Number,
                modifier = Modifier.fillMaxWidth().testTag(tag))
        }
        "boolean" -> ChoiceDropdown(label, listOf("", "true", "false"), value.lowercase().takeIf { it in setOf("true", "false") } ?: "",
            optionLabel = { if (it.isEmpty()) defaultOption(declaration.placeholder()) else it }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), tag = tag) { onValue(it) }
        "std_logic" -> {
            val options = listOf("") + stdLogicValues.map { "'$it'" }
            ChoiceDropdown(label, options, value.uppercase().takeIf { it in options } ?: "",
                optionLabel = { if (it.isEmpty()) defaultOption(declaration.placeholder()) else it }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), tag = tag) { onValue(it) }
        }
        "std_logic_vector" -> {
            // The bits without VHDL's quotes, which the stored value keeps.
            OutlinedTextField(value.removeSurrounding("\""), { typed ->
                val bits = typed.uppercase().filter { it in "UX01ZWLH-" }.take(64)
                onValue(if (bits.isEmpty()) "" else "\"$bits\"")
            }, label = { Text(label) }, placeholder = { Text(declaration.placeholder().removeSurrounding("\"")) },
                enabled = enabled, singleLine = true, textStyle = code, keyboardOptions = FieldKeyboards.Hex, supportingText = default,
                modifier = Modifier.fillMaxWidth().testTag(tag))
        }
        "time" -> {
            if (hdlTimeShown(value) == null) {
                // An expression the fields cannot split stays editable as written.
                OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true, textStyle = code,
                    keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag(tag))
            } else {
                HdlTimeGenericField(declaration, value, label, tag, enabled, default, onValue)
            }
        }
        else -> OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true, textStyle = code,
            keyboardOptions = FieldKeyboards.Code, modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

/**
 * A time generic as a whole number and a unit. The value written is always Evolution's femtosecond count, so the
 * .circ attribute stays one desktop reads; the unit is the one the user works in, kept while it divides the count.
 */
@Composable
private fun HdlTimeGenericField(
    declaration: HdlGenericDeclaration,
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    default: (@Composable () -> Unit)?,
    onValue: (String) -> Unit,
) {
    val strings = rememberLocalizer()
    // Before anything is typed, the unit of the declared default ("5 ns"), else ns.
    val initialUnit = timeValue.matchEntire(declaration.placeholder())?.groupValues?.get(2)?.lowercase()?.takeIf { it in timeScales } ?: "ns"
    var unit by remember(declaration.name) { mutableStateOf(hdlTimeShown(value, initialUnit)?.unit ?: initialUnit) }
    var typed by remember(declaration.name) { mutableStateOf(hdlTimeShown(value, unit)?.number.orEmpty()) }
    var seen by remember(declaration.name) { mutableStateOf(value) }
    if (value != seen) {
        // Changed outside this field (the text editor, or another component selected): show the new value.
        seen = value
        hdlTimeShown(value, unit)?.let { unit = it.unit; typed = it.number }
    }
    fun write(number: String, newUnit: String) {
        typed = number
        unit = newUnit
        // A number Evolution cannot store is kept in the field with an error, and the stored value is left alone.
        val stored = if (number.isEmpty()) "" else hdlTimeStored(number, newUnit) ?: return
        seen = stored
        if (stored != value) onValue(stored)
    }
    val invalid = typed.isNotEmpty() && hdlTimeStored(typed, unit) == null
    val error: (@Composable () -> Unit)? = if (!invalid) null else {
        { Text(strings(if (typed.toLongOrNull() == null) "hdl_tools_sim_generic_invalid_integer" else "hdl_tools_sim_generic_time_range")) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(typed, { input ->
            write(input.filterIndexed { index, char -> char.isDigit() || char == '-' && index == 0 }.take(19), unit)
        }, label = { Text(label) }, placeholder = { Text(declaration.placeholder()) }, enabled = enabled,
            singleLine = true, textStyle = TextStyle(fontFamily = FontFamily.Monospace), keyboardOptions = FieldKeyboards.Number,
            supportingText = error ?: default, isError = invalid, modifier = Modifier.weight(1f).testTag(tag))
        ChoiceDropdown(strings("hdl_tools_sim_generic_unit"), timeUnits, unit, optionLabel = { it },
            enabled = enabled, modifier = Modifier.width(120.dp), tag = "$tag-unit") { write(typed, it) }
    }
}
