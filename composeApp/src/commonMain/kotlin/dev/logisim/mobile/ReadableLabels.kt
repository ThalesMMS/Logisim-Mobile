package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionOperationCapability
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/**
 * The display name of a built-in library descriptor, as Evolution's Library.getDisplayName shows it ("#Gates" is
 * "Gates"). A descriptor without a message keeps its name without the "#". The descriptor written to the .circ and
 * used to group components never changes.
 */
internal fun builtinLibraryName(library: String): LocalizedText = when (library) {
    "#Wiring" -> message(Messages.LibraryWiring)
    "#Gates" -> message(Messages.LibraryGates)
    "#Plexers" -> message(Messages.LibraryPlexers)
    "#Arithmetic" -> message(Messages.LibraryArithmetic)
    "#FPArithmetic" -> message(Messages.LibraryFloat)
    "#Memory" -> message(Messages.LibraryMemory)
    "#I/O" -> message(Messages.LibraryIo)
    "#Input/Output-Extra" -> message(Messages.LibraryExtra)
    "#TTL" -> message(Messages.LibraryTtl)
    "#Base" -> message(Messages.LibraryAnnotations)
    else -> LocalizedText.literal(library.removePrefix("#"))
}

/** A byte count as people read it: "512 bytes", "3 KiB", "1.5 MiB" (binary units, one decimal below 10). */
internal fun byteSizeText(bytes: Long): LocalizedText {
    val kib = 1024L
    val mib = kib * kib
    fun scaled(unit: Long, whole: String, fraction: String): LocalizedText {
        val tenths = (bytes * 10 + unit / 2) / unit
        return if (tenths >= 100 || tenths % 10 == 0L) message(whole, "whole" to (bytes + unit / 2) / unit)
            else message(fraction, "whole" to tenths / 10, "fraction" to tenths % 10)
    }
    return when {
        bytes < kib -> LocalizedText("byte_size_bytes", quantity = bytes)
        bytes < mib -> scaled(kib, "byte_size_kib", "byte_size_kib_fraction")
        else -> scaled(mib, "byte_size_mib", "byte_size_mib_fraction")
    }
}
