package dev.logisim.mobile.core

/**
 * An assembly listing (.lss) followed by a labeled Register, as in Evolution's assembly window.
 *
 * Evolution highlights the first line that matches `^ +<address>:` without regard to case, where
 * `<address>` is the register value in hexadecimal without leading zeros. Lines indented with tabs
 * or without indentation, and addresses written with leading zeros, never match.
 */
class AssemblyListing private constructor(
    val lines: List<String>,
    private val addresses: Map<ULong, Int>,
) {
    fun lineAt(address: ULong?): Int? = address?.let(addresses::get)

    companion object {
        const val MAX_BYTES = 1_048_576
        private val instruction = Regex("""^ +([0-9a-fA-F]+):""")

        fun read(bytes: ByteArray): AssemblyListing? {
            if (bytes.size !in 1..MAX_BYTES) return null
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿")
            } catch (_: CharacterCodingException) {
                return null
            }
            if ('\u0000' in text) return null
            val lines = text.lines()
            val addresses = mutableMapOf<ULong, Int>()
            for ((index, line) in lines.withIndex()) {
                val digits = instruction.find(line)?.groupValues?.get(1) ?: continue
                // A register value prints without leading zeros and has at most 64 bits.
                if ((digits.length > 1 && digits[0] == '0') || digits.length > 16) continue
                val address = digits.toULong(16)
                // Listings can contain overlapping sections. The desktop selects the first match.
                if (address !in addresses) addresses[address] = index
            }
            return if (addresses.isEmpty()) null else AssemblyListing(lines, addresses)
        }
    }
}

/** Registers the window can follow: labeled ones, sorted by label. A repeated label keeps its first register. */
fun CircuitDocument.assemblyRegisters(): List<CircuitComponent> =
    components.filter { it.kind == ComponentKind.REGISTER && it.label.isNotEmpty() }
        .distinctBy { it.label }.sortedBy { it.label }

fun SimulationState.assemblyRegisterAddress(componentId: String): ULong? =
    vectorValues[componentId]?.toULongOrNull()
