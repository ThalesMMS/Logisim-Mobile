package dev.logisim.mobile.core

/**
 * FPGA preferences that change generation and review, like Evolution's FPGA options panel.
 * Evolution's defaults are uppercase VHDL keywords and every warning reported.
 */
data class FpgaGenerationOptions(
    val vhdlKeywordsUpperCase: Boolean = true,
    val suppressGatedClockWarnings: Boolean = false,
    val suppressOpenPinWarnings: Boolean = false,
) {
    companion object {
        /** The emitter's own casing, used by callers that do not apply the preference. */
        val Generated = FpgaGenerationOptions(vhdlKeywordsUpperCase = false)
    }
}

/**
 * Applies Evolution's `Vhdl.getVhdlKeyword` casing to generated VHDL. Comments, string, bit-string and
 * character literals and extended identifiers keep their text.
 */
object VhdlKeywordCase {
    /** Evolution `Vhdl.RESERVED_VHDL_WORDS` at d58a61e0. */
    val keywords: Set<String> = setOf(
        "abs", "all", "access", "after", "alias", "and", "architecture", "array", "assert", "attribute",
        "begin", "block", "body", "buffer", "bus", "case", "component", "configuration", "constant",
        "disconnect", "downto", "else", "elsif", "end", "endcase", "endgenerate", "endif", "endprocess",
        "entity", "exit", "file", "for", "function", "generate", "generic", "group", "guarded", "if",
        "integer", "impure", "in", "inertial", "inout", "is", "label", "library", "linkage", "literal",
        "loop", "map", "mod", "nand", "new", "next", "nor", "not", "null", "of", "on", "open", "or",
        "others", "out", "package", "port", "postponed", "procedure", "process", "pure", "range", "record",
        "register", "reject", "rem", "report", "return", "rol", "ror", "select", "severity", "signal",
        "shared", "sla", "sll", "sra", "srl", "subtype", "then", "to", "transport", "type", "unaffected",
        "units", "until", "use", "variable", "wait", "when", "while", "with", "xnor", "xor",
    )

    fun apply(source: String, upperCase: Boolean): String {
        val out = StringBuilder(source.length)
        var index = 0
        var previous = ' '
        while (index < source.length) {
            val char = source[index]
            when {
                char == '-' && source.getOrNull(index + 1) == '-' -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    out.append(source, index, end)
                    index = end
                    continue
                }
                char == '/' && source.getOrNull(index + 1) == '*' -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    out.append(source, index, end)
                    index = end
                    continue
                }
                char == '"' || char == '\\' -> {
                    var end = index + 1
                    while (end < source.length) {
                        if (source[end] == char) {
                            if (source.getOrNull(end + 1) == char) end += 2 else break
                        } else if (source[end] == '\n') break else end++
                    }
                    end = minOf(end + 1, source.length)
                    out.append(source, index, end)
                    index = end
                    previous = char
                    continue
                }
                // A tick after a name or ')' starts an attribute; otherwise 'c' is a character literal.
                char == '\'' && !(previous.isLetterOrDigit() || previous == '_' || previous == ')') &&
                    source.getOrNull(index + 2) == '\'' -> {
                    out.append(source, index, index + 3)
                    index += 3
                    previous = '\''
                    continue
                }
                char.isLetter() -> {
                    var end = index + 1
                    while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
                    val word = source.substring(index, end)
                    val lower = word.lowercase()
                    out.append(if (lower in keywords) (if (upperCase) word.uppercase() else lower) else word)
                    index = end
                    previous = word.last()
                    continue
                }
                else -> {
                    out.append(char)
                    if (!char.isWhitespace()) previous = char else previous = ' '
                }
            }
            index++
        }
        return out.toString()
    }
}
