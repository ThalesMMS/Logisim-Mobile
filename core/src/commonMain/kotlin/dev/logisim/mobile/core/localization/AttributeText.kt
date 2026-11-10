package dev.logisim.mobile.core.localization

/** Attribute names in XML remain stable; only their presentation label is translated. */
fun localizedAttributeName(name: String): LocalizedText =
    LocalizedText("attribute_label_${name.lowercase()}", fallback = name)
