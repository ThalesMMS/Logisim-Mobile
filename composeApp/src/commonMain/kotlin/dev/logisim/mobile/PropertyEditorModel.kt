package dev.logisim.mobile

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.AttributeEntry
import dev.logisim.mobile.core.AttributeKey
import dev.logisim.mobile.core.AttributePresentation
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.formatEngineeringQuantity
import dev.logisim.mobile.core.localization.*

/**
 * How the inspector edits a value: BOOLEAN, DIRECTION and CHOICE offer their values, COLOR adds a palette and
 * FONT a family/style/size chooser (Evolution's color and font choosers); the others take typed text. EXTERNAL is a
 * large value (memory contents, a PLA table, HDL or Tcl source, an embedded image): the table shows a summary and
 * opens its dedicated editor, as Evolution's "(click to edit)" cells do, with the text editor kept as a fallback.
 */
enum class PropertyInputKind { BOOLEAN, DIRECTION, CHOICE, COLOR, FONT, INTEGER, DECIMAL, TEXT, EXTERNAL }

/** One value an option attribute accepts, with the display name Evolution's combo box shows for it. */
data class PropertyOption(val value: String, val label: LocalizedText)

data class PropertyEditorField(
    val name: String,
    val kind: PropertyInputKind,
    val value: String?,
    val readOnly: Boolean = false,
    /** The accepted values, in display order, of a BOOLEAN, DIRECTION or CHOICE field. */
    val options: List<PropertyOption> = emptyList(),
    /** The display name: Evolution's name for this component's attribute. */
    val label: LocalizedText = localizedAttributeName(name),
    /** A short explanation of the accepted text shown under the field, such as the number prefixes. */
    val hint: LocalizedText? = null,
    /** What an EXTERNAL value holds ("16 words × 8 bits"), shown instead of its raw text. */
    val summary: LocalizedText? = null,
    /** The whole numbers an INTEGER or numeric CHOICE field accepts, named in its hint and invalid message. */
    val range: LongRange? = null,
    /** Common values offered beside typed entry, such as the usual bit widths or Evolution's NE555 resistances. */
    val presets: List<PropertyOption> = emptyList(),
    /** Which soft keyboard typed entry uses. */
    val entry: PropertyTextEntry = PropertyTextEntry.TEXT,
    /** Shown before the value in the table, without being stored: a DIP switch's "0x". */
    val displayPrefix: String = "",
    /**
     * An NE555 resistance or capacitance, which the table shows with its unit ("71.66 kΩ") as Evolution's
     * EngineeringAttribute does, while the file keeps the whole ohms or microfarads.
     */
    val quantity: AttributeEntry? = null,
) {
    val mixed: Boolean get() = value == null
}

/** The kind of text a field's typed entry takes, which chooses its soft keyboard (C5). */
enum class PropertyTextEntry {
    TEXT,
    /** Text spanning lines (memory contents, sources): the return key adds a line. */
    MULTILINE,
    /** A whole number that is never negative: the digit pad. */
    DIGITS,
    /** A number that may be negative or have an exponent, which digit pads often lack keys for. */
    SIGNED,
    /** Hexadecimal digits or a 0x, 0b or octal prefix. */
    HEX,
    /** A number with SI prefixes and units, such as "4.7k" or "10uF". */
    QUANTITY,
}

/** Whether the field's text spans lines, so the keyboard's return key adds a line instead of applying. */
internal val PropertyEditorField.multiline: Boolean get() = entry == PropertyTextEntry.MULTILINE

/**
 * The soft keyboard of [field]'s typed entry: the digit pad only for non-negative whole numbers, letters for signs,
 * exponents, hexadecimal digits and units, never autocorrect, and Done to apply except in multi-line text.
 */
internal fun keyboardOptionsFor(field: PropertyEditorField): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = when (field.entry) {
        PropertyTextEntry.DIGITS -> KeyboardType.Number
        PropertyTextEntry.SIGNED, PropertyTextEntry.HEX, PropertyTextEntry.QUANTITY -> KeyboardType.Ascii
        PropertyTextEntry.TEXT, PropertyTextEntry.MULTILINE -> KeyboardType.Text
    },
    imeAction = if (field.multiline) ImeAction.Default else ImeAction.Done,
)

sealed interface PropertyEditResult {
    data class Valid(val updates: Map<String, String>) : PropertyEditResult
    data class Invalid(val notice: LocalizedText) : PropertyEditResult {
        constructor(message: String) : this(LocalizedText.literal(message))
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

object PropertyEditorModel {
    /**
     * The attribute table of [components]. With [darkTheme], a Text color shows as the canvas draws it, as
     * TextColorAttribute.toDisplayString does; [validate] stores the inverse of the typed color (#340).
     */
    fun fields(
        registry: ComponentRegistry,
        components: List<CircuitComponent>,
        darkTheme: Boolean,
        circuit: List<CircuitComponent> = emptyList(),
    ): List<PropertyEditorField> =
        fields(registry, components, circuit).map { field ->
            if (darkTheme && field.isThemeTextColor(components)) field.copy(value = field.value?.let { ThemeTextColor.display(it, true) }) else field
        }

    private fun PropertyEditorField.isThemeTextColor(components: List<CircuitComponent>) =
        name == TEXT_COLOR && !readOnly && components.all { it.kind == ComponentKind.TEXT }

    private const val TEXT_COLOR = "color"

    /**
     * The attribute table of [components]. [circuit] holds the other components of their circuit that a value can
     * refer to: a SoC component chooses one of the circuit's SoC buses (C13).
     */
    fun fields(
        registry: ComponentRegistry,
        components: List<CircuitComponent>,
        circuit: List<CircuitComponent> = emptyList(),
    ): List<PropertyEditorField> {
        if (components.isEmpty()) return emptyList()
        val definitions = components.map { registry.definition(it.kind) }
        // What each definition presents for each of its attributes; null hides the attribute (Evolution's
        // attribute sets list only the attributes that apply).
        val presented = components.mapIndexed { index, component ->
            definitions[index]?.let { definition -> definition.schema.keys.associate { it.name to definition.presentAttribute(component, it.name) } }.orEmpty()
        }
        val hidden = presented.flatMap { map -> map.filterValues { it == null }.keys }.toSet()
        val firstKeys = definitions.first()?.schema?.keys.orEmpty()
        fun keyOf(index: Int, name: String) = definitions[index]?.schema?.keys?.firstOrNull { it.name == name }
        // The whole numbers every selected component accepts for a numeric attribute, whichever comes first; an
        // attribute without a range accepts every Int (or Long).
        fun sharedBounds(key: AttributeKey<*>): LongRange? {
            var shared = numericBounds(key, presented[0][key.name]) ?: return null
            for (index in definitions.indices) {
                val bounds = keyOf(index, key.name)?.let { numericBounds(it, presented[index][key.name]) } ?: return null
                shared = maxOf(shared.first, bounds.first)..minOf(shared.last, bounds.last)
            }
            return shared
        }
        // A multi-selection edits an attribute only when every schema accepts the same values for it; numbers edit
        // together when some value fits every range, offering what every range accepts.
        val editable = firstKeys.filter { key ->
            key.name != "label" && key.name !in hidden && definitions.indices.all { index ->
                val first = presented[0][key.name]
                val other = keyOf(index, key.name) ?: return@all false
                val mine = presented[index][other.name]
                inputKind(other, mine) == inputKind(key, first) &&
                    if (numericBounds(key, first) != null) numericBounds(other, mine) != null
                    else optionValues(other, mine)?.toSet() == optionValues(key, first)?.toSet()
            } && sharedBounds(key)?.isEmpty() != true
        }
        val editableNames = editable.map { it.name }.toSet()
        val known = editable.map { key ->
            val values = components.mapIndexed { index, component ->
                presented[index][key.name]?.value ?: component.attributes[key.name] ?: definitions[index]!!.schema.defaults.getValue(key.name)
            }
            val presentation = presented[0][key.name]
            val range = sharedBounds(key)?.takeUnless { it == INT_VALUES || it == LONG_VALUES }
            val busReference = key.name in SocComponents.busReferences && components.all { it.kind in SocComponents.kinds }
            val kind = when {
                busReference -> PropertyInputKind.CHOICE
                // Evolution's integer-range attributes offer a combo box of at most 32 values.
                range != null && !range.isEmpty() && range.last - range.first < SMALL_RANGE_VALUES -> PropertyInputKind.CHOICE
                else -> inputKind(key, presentation)
            }
            val value = values.distinct().singleOrNull()
            // Values stored as 0x... (Evolution's hex integers) also accept 0b, 0 (octal) and decimal input.
            val hex = kind == PropertyInputKind.TEXT && formatRaw(key)?.startsWith("0x") == true
            PropertyEditorField(key.name, kind, value,
                options = when {
                    busReference -> socBusOptions(circuit, values)
                    kind == PropertyInputKind.CHOICE && range != null ->
                        range.map { PropertyOption(it.toString(), LocalizedText.literal(it.toString())) }
                    else -> options(key, presentation)
                },
                label = key.displayName(),
                hint = when {
                    key.entry == AttributeEntry.RESISTANCE -> message("inspector_hint_resistance")
                    key.entry == AttributeEntry.CAPACITANCE -> message("inspector_hint_capacitance")
                    key.entry == AttributeEntry.HEX_DIGITS -> message("inspector_hint_hex_digits")
                    hex -> message("inspector_hint_integer_prefixes")
                    kind == PropertyInputKind.INTEGER && range != null -> message("inspector_hint_range", "min" to range.first, "max" to range.last)
                    else -> null
                },
                summary = if (kind == PropertyInputKind.EXTERNAL && value != null) externalSummary(key.name, value, components.first()) else null,
                range = range,
                presets = when {
                    kind != PropertyInputKind.INTEGER -> emptyList()
                    // Evolution's NE555 presets, shown with their units ("71.66 kΩ") and stored as whole ohms or µF.
                    key.presets.isNotEmpty() -> key.presets.map { raw ->
                        PropertyOption(raw, LocalizedText.literal(raw.toLongOrNull()?.let { formatEngineeringQuantity(it, key.entry) } ?: raw))
                    }
                    range != null && range.last <= 64 ->
                        BIT_WIDTH_PRESETS.filter { it in range }.map { PropertyOption(it.toString(), LocalizedText.literal(it.toString())) }
                    else -> emptyList()
                },
                entry = textEntry(key, kind, range, hex),
                displayPrefix = if (key.entry == AttributeEntry.HEX_DIGITS) "0x" else "",
                quantity = key.entry.takeIf { it == AttributeEntry.RESISTANCE || it == AttributeEntry.CAPACITANCE })
        }
        val remainingNames = components.flatMap { it.attributes.keys }.distinct().sorted()
            .filter { it != "label" && it !in editableNames && it !in hidden && !it.startsWith(INTERNAL_ATTRIBUTE_PREFIX) }
        return known + remainingNames.map { name ->
            val values = components.map { it.attributes[name] }
            PropertyEditorField(name, PropertyInputKind.TEXT, values.distinct().singleOrNull(), readOnly = true)
        }
    }

    /** Attributes the mobile port stores for its own bookkeeping; they stay in the file but are not shown. */
    private const val INTERNAL_ATTRIBUTE_PREFIX = "logisim-mobile-"

    /** Evolution's IntegerRangeAttribute uses a combo box up to this many values and a text field beyond. */
    private const val SMALL_RANGE_VALUES = 32

    /** Bit widths offered beside typed entry, like the first entries of Evolution's BitWidth combo box. */
    private val BIT_WIDTH_PRESETS = listOf(1L, 2, 3, 4, 5, 6, 7, 8, 16, 24, 32, 64)

    /** Text attributes whose values span lines: memory contents, PLA tables, HDL and Tcl sources, annotation text. */
    private val MULTILINE_ATTRIBUTES = setOf("contents", "Contents", "table", "content", "text", "source", "tclsource")

    private fun textEntry(key: AttributeKey<*>, kind: PropertyInputKind, range: LongRange?, hex: Boolean): PropertyTextEntry = when {
        kind == PropertyInputKind.TEXT || kind == PropertyInputKind.EXTERNAL -> when {
            key.name in MULTILINE_ATTRIBUTES -> PropertyTextEntry.MULTILINE
            hex || key.entry == AttributeEntry.HEX_DIGITS -> PropertyTextEntry.HEX
            else -> PropertyTextEntry.TEXT
        }
        key.entry == AttributeEntry.RESISTANCE || key.entry == AttributeEntry.CAPACITANCE -> PropertyTextEntry.QUANTITY
        kind == PropertyInputKind.INTEGER -> if (range != null && range.first >= 0) PropertyTextEntry.DIGITS else PropertyTextEntry.SIGNED
        kind == PropertyInputKind.DECIMAL -> PropertyTextEntry.SIGNED
        else -> PropertyTextEntry.TEXT
    }

    private val INT_VALUES = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    private val LONG_VALUES = Long.MIN_VALUE..Long.MAX_VALUE

    /** The whole numbers an integer attribute without an option list accepts: its range, or every Int or Long. */
    private fun numericBounds(key: AttributeKey<*>, presentation: AttributePresentation?): LongRange? = when {
        key.options != null || presentation?.options != null -> null
        key.default is Int -> key.range ?: INT_VALUES
        key.default is Long -> key.range ?: LONG_VALUES
        else -> null
    }

    /** Core schemas reject a value naming the attribute by its XML name ("labelfont"); the editor names it as shown. */
    private val SCHEMA_REJECTIONS = setOf(Messages.AttributeRawInvalid, Messages.AttributeValueInvalid)

    /**
     * Evolution's SoC bus selection: Not connected, then the circuit's SoC buses by label, or by kind and location
     * (`SoC Bus@20,400`) when unlabeled. The stored value is the bus's SocBusIdentifier, which every bus placed on
     * mobile has; a bus saved without one is offered by its mobile id and gets an identifier when chosen
     * (SocComponents.attach). A value naming no bus in the circuit stays offered as it is, so the row shows it and
     * editing another attribute keeps it.
     */
    private fun socBusOptions(circuit: List<CircuitComponent>, values: List<String>): List<PropertyOption> {
        val buses = circuit.filter { it.kind == SocComponents.BUS }.mapNotNull { bus ->
            val id = runCatching { SocComponents.busId(bus) }.getOrNull() ?: return@mapNotNull null
            PropertyOption(id, if (bus.label.isNotBlank()) LocalizedText.literal(bus.label)
                else message("soc_bus_option_unlabeled", "name" to bus.kind.localizedLabel(), "x" to bus.position.x, "y" to bus.position.y))
        }.distinctBy { it.value }
        val dangling = values.filter { value -> value.isNotEmpty() && buses.none { it.value == value } }.distinct()
            .map { PropertyOption(it, LocalizedText.literal(it)) }
        return listOf(PropertyOption("", message("soc_bus_not_connected"))) + buses + dangling
    }

    fun validate(
        registry: ComponentRegistry,
        components: List<CircuitComponent>,
        name: String,
        typed: String,
        darkTheme: Boolean = false,
        circuit: List<CircuitComponent> = emptyList(),
    ): PropertyEditResult {
        if (components.isEmpty()) return PropertyEditResult.Invalid(message(Messages.InspectorSelectRequired))
        val field = fields(registry, components, circuit).firstOrNull { it.name == name }
        // Messages name the attribute as the table shows it ("Data bits"), never its XML name ("width").
        if (field == null || field.readOnly) return PropertyEditResult.Invalid(message(Messages.InspectorNotEditable,
            "name" to (field?.label ?: localizedAttributeName(name))))
        val raw = if (darkTheme && field.isThemeTextColor(components)) ThemeTextColor.store(typed, field.value, true) else typed
        val invalid = PropertyEditResult.Invalid(field.range?.let { range ->
            message("inspector_value_invalid_range", "name" to field.label, "raw" to raw, "min" to range.first, "max" to range.last)
        } ?: message(Messages.InspectorValueInvalid, "name" to field.label, "raw" to raw))
        // A number chosen from a range is checked like typed entry, so "08" still means 8.
        if (field.kind == PropertyInputKind.CHOICE && field.range == null && field.options.none { it.value == raw }) return invalid
        // The editor accepts only fonts it can read (Evolution's "Family style size"); reading a file stays lenient.
        if (field.kind == PropertyInputKind.FONT && AnnotationFont.parse(raw) == null) return invalid
        val key = registry.requireDefinition(components.first().kind).schema.keys.first { it.name == name }
        val value = try { normalize(key, raw) } catch (_: IllegalArgumentException) { null } ?: return invalid
        // A multi-selection's range is what every selected component accepts.
        if (field.range != null && value.toLongOrNull()?.let { it in field.range } != true) return invalid
        return try {
            components.forEach { registry.reconfigure(it, mapOf(name to value)) }
            PropertyEditResult.Valid(mapOf(name to value))
        } catch (error: IllegalArgumentException) {
            val rejection = (error as? LocalizedFailure)?.text
            if (rejection != null && rejection.key in SCHEMA_REJECTIONS && rejection.arguments["name"] == name) invalid
            else PropertyEditResult.Invalid(error.localizedDescription(message(Messages.InspectorValueInvalidShort, "name" to field.label)))
        }
    }

    fun unregisteredKinds(registry: ComponentRegistry, components: List<CircuitComponent>): List<String> =
        components.filter { registry.definition(it.kind) == null }.map { it.kind.name }.distinct()

    private fun optionValues(key: AttributeKey<*>, presentation: AttributePresentation?): List<String>? =
        presentation?.options ?: key.options

    private fun options(key: AttributeKey<*>, presentation: AttributePresentation?): List<PropertyOption> =
        when (val kind = inputKind(key, presentation)) {
            PropertyInputKind.CHOICE -> optionValues(key, presentation).orEmpty().map {
                PropertyOption(it, presentation?.optionLabels?.get(it) ?: key.optionLabel(it))
            }
            else -> standardOptions(kind)
        }

    /** The values of a BOOLEAN or DIRECTION field, in the order the inspector lists them. */
    fun standardOptions(kind: PropertyInputKind): List<PropertyOption> = when (kind) {
        // Evolution's BooleanAttribute shows Yes and No (data booleanTrueOption, booleanFalseOption).
        PropertyInputKind.BOOLEAN -> listOf(PropertyOption("true", message("inspector_boolean_yes")),
            PropertyOption("false", message("inspector_boolean_no")))
        // Evolution's DirectionAttribute lists North, South, East, West.
        PropertyInputKind.DIRECTION -> listOf("north" to Messages.DirectionNorth, "south" to Messages.DirectionSouth,
            "east" to Messages.DirectionEast, "west" to Messages.DirectionWest).map { (raw, label) -> PropertyOption(raw, message(label)) }
        else -> emptyList()
    }

    private fun inputKind(key: AttributeKey<*>, presentation: AttributePresentation? = null): PropertyInputKind = when {
        optionValues(key, presentation) != null -> PropertyInputKind.CHOICE
        key.default is AnnotationFont || key.name == "labelfont" -> PropertyInputKind.FONT
        (key.default is String || key.default is UInt) && formatRaw(key)?.let(::isHexColor) == true -> PropertyInputKind.COLOR
        key.name in LARGE_VALUE_ATTRIBUTES && inputKindOf(key.default) == PropertyInputKind.TEXT -> PropertyInputKind.EXTERNAL
        else -> inputKindOf(key.default)
    }

    /**
     * The large text attributes (C12): memory and PLA ROM contents, the PLA table, HDL-IP and Tcl source, and an
     * Image annotation's data URI (up to about 1.3 MB).
     */
    private val LARGE_VALUE_ATTRIBUTES = setOf("contents", "Contents", "table", "content", "image")

    /** A summary of a large value, computed from its text and the component's dimensions; never the raw text. */
    internal fun externalSummary(name: String, value: String, component: CircuitComponent): LocalizedText {
        if (value.isBlank()) return message("inspector_summary_empty")
        fun lines() = LocalizedText("inspector_summary_lines", quantity = value.lineSequence().count { it.isNotBlank() }.toLong())
        return when (name) {
            // Evolution's memory image header, "addr/data: 8 8".
            "contents" -> Regex("""addr/data:\s*(\d+)\s+(\d+)""").find(value.take(64))?.destructured?.let { (address, data) ->
                val bits = address.toIntOrNull()?.takeIf { it in 0..62 } ?: return@let null
                message("inspector_summary_memory", "words" to (1L shl bits), "bits" to data)
            } ?: lines()
            "Contents" -> message("inspector_summary_matrix", "inputs" to component.attributeInt("inputs", 4),
                "terms" to component.attributeInt("and", 4), "outputs" to component.attributeInt("outputs", 4))
            "image" -> {
                val header = value.take(64).substringBefore(',', "")
                val type = header.substringAfter("data:", "").substringBefore(';').substringAfter('/').substringBefore('+')
                    .uppercase().ifBlank { "?" }
                val payload = value.length - value.indexOf(',').coerceAtLeast(0) - 1
                val bytes = if (";base64" in header) payload / 4L * 3 - value.takeLast(2).count { it == '=' } else payload.toLong()
                message("inspector_summary_image", "type" to type, "size" to byteSizeText(bytes.coerceAtLeast(0)))
            }
            else -> lines()
        }
    }

    /** At most this much of a long value is drawn in the table; the rest stays in the attribute. */
    internal const val DISPLAY_LIMIT = 160

    @Suppress("UNCHECKED_CAST")
    private fun formatRaw(key: AttributeKey<*>): String? = runCatching { (key as AttributeKey<Any?>).format(key.default) }.getOrNull()

    private fun inputKindOf(default: Any?): PropertyInputKind = when (default) {
        is Boolean -> PropertyInputKind.BOOLEAN
        is Direction -> PropertyInputKind.DIRECTION
        is Int, is Long -> PropertyInputKind.INTEGER
        is Float, is Double -> PropertyInputKind.DECIMAL
        else -> PropertyInputKind.TEXT
    }

    private fun <T> normalize(key: AttributeKey<T>, raw: String): String? {
        val parsed = key.parse(raw) ?: return null
        return if (key.validate(parsed)) key.format(parsed) else null
    }
}

internal fun PropertyEditorField.localizedValue(): LocalizedText = when {
    value == null -> message(Messages.InspectorMixedValues)
    kind == PropertyInputKind.EXTERNAL -> summary ?: LocalizedText.literal(value.take(PropertyEditorModel.DISPLAY_LIMIT))
    value.length > PropertyEditorModel.DISPLAY_LIMIT -> LocalizedText.literal(value.take(PropertyEditorModel.DISPLAY_LIMIT) + "…")
    else -> options.firstOrNull { it.value == value }?.label ?: when {
        kind == PropertyInputKind.BOOLEAN && value == "true" -> message("inspector_boolean_yes")
        kind == PropertyInputKind.BOOLEAN && value == "false" -> message("inspector_boolean_no")
        kind == PropertyInputKind.DIRECTION -> when (value) {
            "east" -> message(Messages.DirectionEast)
            "north" -> message(Messages.DirectionNorth)
            "west" -> message(Messages.DirectionWest)
            "south" -> message(Messages.DirectionSouth)
            else -> LocalizedText.literal(value)
        }
        kind == PropertyInputKind.CHOICE -> localizedAttributeOption(name, value)
        kind == PropertyInputKind.FONT -> localizedFontValue(value) ?: LocalizedText.literal(value)
        quantity != null -> LocalizedText.literal(value.toLongOrNull()?.let { formatEngineeringQuantity(it, quantity) } ?: value)
        else -> LocalizedText.literal(displayPrefix + value)
    }
}

