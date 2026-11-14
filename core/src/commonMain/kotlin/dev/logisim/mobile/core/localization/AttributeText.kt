package dev.logisim.mobile.core.localization

/** Attribute names in XML remain stable; only their presentation label is translated. */
fun localizedAttributeName(name: String): LocalizedText {
    // Evolution names the indexed families "Negate %s" (1-based, GateAttributes) and "Bit %s" (SplitterAttributes).
    INDEXED_ATTRIBUTE.matchEntire(name)?.let { match ->
        val index = match.groupValues[2].toInt()
        return when (match.groupValues[1]) {
            "negate" -> LocalizedText("attribute_label_negate_n", mapOf("n" to (index + 1).toString()), fallback = name)
            else -> LocalizedText("attribute_label_bit_n", mapOf("n" to index.toString()), fallback = name)
        }
    }
    return LocalizedText("attribute_label_${name.lowercase()}", fallback = name)
}

private val INDEXED_ATTRIBUTE = Regex("(negate|bit)([0-9]{1,2})")

/** The message key of one value of an option attribute, such as `attribute_option_labelloc_west`. */
fun attributeOptionKey(name: String, value: String): String =
    "attribute_option_" + name.lowercase() + "_" + value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/**
 * The display name of a serialized option value, like Evolution's AttributeOption.toDisplayString. The stored
 * value never changes; an option without a message shows the value itself.
 */
fun localizedAttributeOption(name: String, value: String): LocalizedText =
    LocalizedText(attributeOptionKey(name, value), fallback = value)
