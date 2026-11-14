package dev.logisim.mobile.core

/**
 * A project name for the catalog that no listed project uses yet: [base], then "[base] 2", "[base] 3"... as a
 * second "Untitled" window is numbered in Evolution. Names compare without case or surrounding spaces; only the
 * catalog's display name is involved, never a circuit name or the .circ content.
 */
fun uniqueProjectName(base: String, existing: Collection<String>): String {
    val trimmed = base.trim()
    val taken = existing.mapTo(mutableSetOf()) { it.trim().lowercase() }
    if (trimmed.lowercase() !in taken) return trimmed
    var number = 2
    while ("$trimmed $number".lowercase() in taken) number++
    return "$trimmed $number"
}
