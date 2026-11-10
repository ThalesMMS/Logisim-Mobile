package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

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

class AttributeKey<T>(
    val name: String,
    val default: T,
    val parse: (String) -> T?,
    val format: (T) -> String,
    val validate: (T) -> Boolean = { true },
) {
    init {
        requireLocalized(name.isNotBlank()) { message(Messages.AttributeNameRequired) }
        requireLocalized(validate(default)) { message(Messages.AttributeDefaultInvalid, "name" to name) }
    }

    internal val defaultRaw: String get() = format(default)
    internal fun accepts(raw: String): Boolean = parse(raw)?.let(validate) == true
    internal fun validateRaw(raw: String) {
        val parsed = parse(raw)
        requireLocalized(parsed != null && validate(parsed)) { message(Messages.AttributeRawInvalid, "name" to name, "raw" to raw) }
    }

    companion object {
        fun int(name: String, default: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE) =
            AttributeKey(name, default, String::toIntOrNull, Int::toString) { it in range }

        fun long(name: String, default: Long, range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE) =
            AttributeKey(name, default, String::toLongOrNull, Long::toString) { it in range }

        fun double(name: String, default: Double, validate: (Double) -> Boolean = { it.isFinite() }) =
            AttributeKey(name, default, String::toDoubleOrNull, Double::toString, validate)

        fun boolean(name: String, default: Boolean) =
            AttributeKey(name, default, String::toBooleanStrictOrNull, Boolean::toString)

        fun choice(name: String, default: String, choices: Set<String>) =
            AttributeKey(name, default, { it }, { it }) { it in choices }

        fun text(name: String, default: String = "", validate: (String) -> Boolean = { true }) =
            AttributeKey(name, default, { it }, { it }, validate)

        fun direction(name: String = "facing", default: Direction = Direction.EAST) =
            AttributeKey(name, default, Direction::parse, Direction::serialized)
    }
}

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
