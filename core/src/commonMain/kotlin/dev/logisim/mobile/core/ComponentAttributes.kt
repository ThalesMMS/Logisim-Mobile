package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*
import kotlin.math.absoluteValue

enum class Direction(val serialized: String) {
    EAST("east"), NORTH("north"), WEST("west"), SOUTH("south");

    companion object {
        fun parse(value: String): Direction? = entries.firstOrNull { it.serialized == value }
    }
}

internal fun parseEvolutionHexInteger(raw: String): UInt? {
    val text = raw.lowercase()
    val negative = text.startsWith('-')
    val magnitude = if (negative) text.drop(1) else text
    val (digits, radix) = when {
        magnitude.startsWith("0x") -> magnitude.drop(2) to 16
        magnitude.startsWith("0b") -> magnitude.drop(2) to 2
        magnitude.startsWith('0') && magnitude.length > 1 -> magnitude.drop(1) to 8
        else -> magnitude to 10
    }
    if (!negative) return digits.toUIntOrNull(radix)
    if (digits.startsWith('+')) return null
    val value = digits.toLongOrNull(radix) ?: return null
    return if (value in 0..2147483648L) (-value).toUInt() else null
}

class AttributeKey<T> private constructor(
    val name: String,
    val default: T,
    val parse: (String) -> T?,
    val format: (T) -> String,
    /**
     * The serialized values an editor offers, in display order, when the attribute accepts only these, like
     * Evolution's option attributes (Attributes.forOption), which it edits with a combo box. Null for open values.
     */
    val options: List<String>?,
    /** Message keys for option values whose display name differs from [attributeOptionKey]'s. */
    val optionKeys: Map<String, String>,
    /** The message key of the display name when it differs from the one shared by the XML name. */
    val labelKey: String?,
    val validate: (T) -> Boolean,
    /** The accepted whole numbers of an integer attribute, when they form one range; editors show and offer them. */
    val range: LongRange? = null,
    /** How editors offer typed entry beyond what the value type implies; parsing and formatting never change. */
    val entry: AttributeEntry = AttributeEntry.AUTOMATIC,
    /** Serialized values editors offer beside typed entry, like the presets of Evolution's editable combo boxes. */
    val presets: List<String> = emptyList(),
) {
    constructor(
        name: String,
        default: T,
        parse: (String) -> T?,
        format: (T) -> String,
        validate: (T) -> Boolean = { true },
    ) : this(name, default, parse, format, null, emptyMap(), null, validate)

    init {
        requireLocalized(name.isNotBlank()) { message(Messages.AttributeNameRequired) }
        requireLocalized(validate(default)) { message(Messages.AttributeDefaultInvalid, "name" to name) }
        requireLocalized(options == null || (format(default) in options && options.all(::accepts))) {
            message(Messages.AttributeDefaultInvalid, "name" to name)
        }
    }

    internal val defaultRaw: String get() = format(default)
    internal fun accepts(raw: String): Boolean = parse(raw)?.let(validate) == true
    internal fun validateRaw(raw: String) {
        val parsed = parse(raw)
        requireLocalized(parsed != null && validate(parsed)) { message(Messages.AttributeRawInvalid, "name" to name, "raw" to raw) }
    }

    companion object {
        fun int(name: String, default: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE) =
            AttributeKey(name, default, String::toIntOrNull, Int::toString, null, emptyMap(), null, { it in range },
                range.takeUnless { it == Int.MIN_VALUE..Int.MAX_VALUE }?.let { it.first.toLong()..it.last.toLong() })

        fun long(name: String, default: Long, range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE) =
            AttributeKey(name, default, String::toLongOrNull, Long::toString, null, emptyMap(), null, { it in range },
                range.takeUnless { it == Long.MIN_VALUE..Long.MAX_VALUE })

        fun double(name: String, default: Double, validate: (Double) -> Boolean = { it.isFinite() }) =
            AttributeKey(name, default, String::toDoubleOrNull, Double::toString, validate)

        fun boolean(name: String, default: Boolean) =
            AttributeKey(name, default, String::toBooleanStrictOrNull, Boolean::toString)

        fun choice(name: String, default: String, choices: Set<String>) =
            AttributeKey(name, default, { it }, { it }, choices.toList(), emptyMap(), null, { it in choices })

        /** An integer limited to [values], such as a gate's size; editors offer the values as options. */
        fun intChoice(name: String, default: Int, values: List<Int>, optionKeys: Map<String, String> = emptyMap()) =
            AttributeKey(name, default, String::toIntOrNull, Int::toString, values.map(Int::toString), optionKeys, null, { it in values })

        fun text(name: String, default: String = "", validate: (String) -> Boolean = { true }) =
            AttributeKey(name, default, { it }, { it }, validate)

        fun direction(name: String = "facing", default: Direction = Direction.EAST) =
            AttributeKey(name, default, Direction::parse, Direction::serialized)
    }

    /**
     * This attribute with its own display name, for an XML name that means different things in different
     * components, such as "color" (an LED's On color, a Text's Color, an RGB Video's Color model).
     */
    fun labeled(messageKey: String): AttributeKey<T> =
        AttributeKey(name, default, parse, format, options, optionKeys, messageKey, validate, range, entry, presets)

    /**
     * This attribute with typed entry described by [entry], such as a quantity with unit suffixes, and [presets]
     * offered beside it, like Evolution's EngineeringAttribute combo box.
     */
    fun entered(entry: AttributeEntry, presets: List<T> = emptyList()): AttributeKey<T> =
        AttributeKey(name, default, parse, format, options, optionKeys, labelKey, validate, range, entry, presets.map(format))

    /**
     * This attribute offering [values] in editors, like Evolution's combo box for an attribute whose values form a
     * short list (a floating-point width, a FIFO size); [parse], [format] and [validate] stay as they are.
     */
    fun offering(values: List<T>): AttributeKey<T> =
        AttributeKey(name, default, parse, format, values.map(format), optionKeys, labelKey, validate, range, entry, presets)

    /** The display name editors show for this attribute. */
    fun displayName(): LocalizedText = labelKey?.let { LocalizedText(it, fallback = name) } ?: localizedAttributeName(name)

    /** The display name of one of [options]: Evolution's option label, or the value itself when none exists. */
    fun optionLabel(value: String): LocalizedText =
        LocalizedText(optionKeys[value] ?: attributeOptionKey(name, value), fallback = value)
}

/** How an editor offers typed entry for an attribute, beyond what its value type implies. */
enum class AttributeEntry {
    AUTOMATIC,
    /** An NE555 quantity with SI prefixes and units: whole ohms ("4.7k") or microfarads ("10uF"). */
    RESISTANCE, CAPACITANCE,
    /** Hexadecimal digits stored without a prefix, such as a DIP switch's positions ("ff"). */
    HEX_DIGITS,
}

/**
 * [stored] ohms or microfarads as Evolution's EngineeringAttribute shows them: the largest prefix whose value is at
 * least one, never above megaohm or microfarad, with trailing zeros dropped ("71.66 kΩ", "1000 µF"). Null for other
 * entries. SI symbols are the same in every language.
 */
fun formatEngineeringQuantity(stored: Long, entry: AttributeEntry): String? {
    val resistance = when (entry) {
        AttributeEntry.RESISTANCE -> true
        AttributeEntry.CAPACITANCE -> false
        else -> return null
    }
    // Ohms are stored with exponent 0 and shown as Ω, kΩ or MΩ; microfarads (exponent -6) are always shown as µF.
    val exponent = if (!resistance) 0 else listOf(6, 3).firstOrNull { e -> stored.absoluteValue >= pow10(e) } ?: 0
    val symbol = if (!resistance) "µF" else when (exponent) { 6 -> "MΩ"; 3 -> "kΩ"; else -> "Ω" }
    if (exponent == 0) return "$stored $symbol"
    val digits = stored.absoluteValue.toString().padStart(exponent + 1, '0')
    val point = digits.length - exponent
    val body = (digits.substring(0, point) + "." + digits.substring(point)).trimEnd('0').trimEnd('.')
    return (if (stored < 0) "-" else "") + body + " " + symbol
}

private fun pow10(exponent: Int): Long = (1..exponent).fold(1L) { value, _ -> value * 10 }

/** Validates known attributes without discarding fields owned by a newer file version. */
class AttributeSchema(val keys: List<AttributeKey<*>> = emptyList()) {
    private val byName = keys.associateBy { it.name }

    init { requireLocalized(byName.size == keys.size) { message(Messages.AttributeSchemaDuplicate) } }

    val defaults: Map<String, String> get() = keys.associate { it.name to it.defaultRaw }

    fun parse(raw: Map<String, String>): AttributeValues {
        raw.forEach { (name, value) -> byName[name]?.validateRaw(value) }
        return AttributeValues(raw.toMap(), defaults + raw, byName.keys)
    }
}

class AttributeValues internal constructor(
    val raw: Map<String, String>,
    private val effective: Map<String, String>,
    private val names: Set<String>,
) {
    operator fun <T> get(key: AttributeKey<T>): T {
        requireLocalized(key.name in names) { message(Messages.AttributeUndeclared, "name" to key.name) }
        val value = key.parse(effective.getValue(key.name))
        requireLocalized(value != null && key.validate(value)) { message(Messages.AttributeValueInvalid, "name" to key.name) }
        return value
    }

    fun string(name: String): String? = effective[name]
}

object CommonAttributes {
    val Width = AttributeKey.int("width", 1, 1..64)
    val Facing = AttributeKey.direction()
    val Inputs = AttributeKey.int("inputs", 2, 1..64)
    val Size = AttributeKey.int("size", 50, 1..1024)
    val Label = AttributeKey.text("label")
}
