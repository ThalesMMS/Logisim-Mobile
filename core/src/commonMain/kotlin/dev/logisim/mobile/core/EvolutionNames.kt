package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/**
 * Evolution d58a61e0 rules for circuit names and component labels (#321), with Evolution's default HDL (VHDL).
 *
 * - Entry: `SyntaxChecker.getErrorMessage` refuses a circuit name (`CircuitAttributes`) or a label
 *   (`InstanceComponent`) that is not `[A-Za-z]\w*`, has `__`, ends with `_` or is a VHDL keyword. A label
 *   equal to its component's name, and a circuit name equal to one of its pin labels, are refused too.
 * - Load: `XmlReader.ensureLogisimCompatibility` renames every circuit name and every library component label
 *   that fails `XmlReader.labelVHDLInvalid` with `generateValidVHDLLabel`. Evolution's suffix is random; Mobile
 *   derives it from the old text so a file reopens with the same names.
 */
object EvolutionNames {
    private const val SUFFIX_LENGTH = 8

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isWordChar(c: Char) = isAsciiLetter(c) || c in '0'..'9' || c == '_'

    /** `XmlReader.labelVHDLInvalid`: what Evolution renames when it opens a file. */
    fun renamedOnLoad(label: String): Boolean =
        label.isEmpty() || !isAsciiLetter(label[0]) || !label.all(::isWordChar) || label.endsWith("_") || "__" in label

    /** The reason Evolution's editor refuses [value] as a circuit name or label, or null when it accepts it. */
    fun entryProblem(value: String): LocalizedText? = when {
        value.isEmpty() -> null
        value[0] in '0'..'9' -> message("evolution_name_starts_with_digit")
        !isAsciiLetter(value[0]) || !value.all(::isWordChar) ->
            message("evolution_name_invalid_character", "character" to firstInvalid(value))
        "__" in value -> message("evolution_name_double_underscore")
        value.lowercase() in VhdlKeywordCase.keywords -> message("evolution_name_vhdl_keyword", "name" to value)
        value.endsWith("_") -> message("evolution_name_ends_with_underscore")
        else -> null
    }

    private fun firstInvalid(value: String): String {
        val index = if (!isAsciiLetter(value[0])) 0 else value.indexOfFirst { !isWordChar(it) }
        val end = if (value[index].isHighSurrogate() && index + 1 < value.length) index + 2 else index + 1
        return value.substring(index, end)
    }

    /** `InstanceComponent`: a label must be acceptable and differ from the component's name (case-insensitive in VHDL). */
    fun labelProblem(label: String, componentName: String?): LocalizedText? =
        entryProblem(label) ?: if (label.isNotEmpty() && componentName != null && label.equals(componentName, ignoreCase = true))
            message("evolution_label_matches_component", "name" to componentName) else null

    /** `CircuitAttributes`: a circuit name must be acceptable and differ from the labels of its pins. */
    fun circuitNameProblem(name: String, circuit: CircuitDocument?): LocalizedText? =
        entryProblem(name) ?: circuit?.components?.firstOrNull { component ->
            (component.kind == ComponentKind.INPUT || component.kind == ComponentKind.OUTPUT) &&
                component.label.isNotEmpty() && component.label.equals(name, ignoreCase = true)
        }?.let { message("evolution_circuit_name_matches_pin", "name" to name) }

    /** `XmlReader.generateValidVHDLLabel(initial, suffix)`. */
    fun validLabel(initial: String, suffix: String = stableSuffix(initial)): String {
        val trimmed = initial.trim { it <= ' ' }
        var label = trimmed.ifEmpty { "L_" }.replace("!", "NOT_").replace("~", "NOT_")
        if (!isAsciiLetter(label[0])) label = "L_$label"
        label = buildString {
            var index = 0
            while (index < label.length) {
                val c = label[index]
                append(if (isWordChar(c)) c else '_')
                index += if (c.isHighSurrogate() && index + 1 < label.length && label[index + 1].isLowSurrogate()) 2 else 1
            }
        }
        label = label.replace(Regex("_+"), "_").removeSuffix("_")
        if (label != trimmed) label = ("${label}_$suffix").replace('-', '_')
        return label
    }

    /** Eight hexadecimal digits (like Evolution's UUID prefix) derived from [initial] with FNV-1a. */
    fun stableSuffix(initial: String): String {
        var hash = 0x811c9dc5u
        for (byte in initial.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toUInt()) * 0x01000193u
        }
        return hash.toString(16).padStart(SUFFIX_LENGTH, '0')
    }
}

/** Projects from original Logisim (2.7.1 and earlier) keep its free-form names; every other project follows Evolution. */
fun CircuitProject.followsEvolutionNames(): Boolean =
    runCatching { !CircFormatProfile.parse(sourceVersion).originalLogisim }.getOrDefault(true)
