package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

enum class SocArchitecture(val elfMachine: Int) { NIOS_II(113), RV32IM(243) }

data class SocExecutableSegment(val address: UInt, val bytes: ByteArray, val memorySize: ULong, val executable: Boolean = true) {
    init {
        require(memorySize >= bytes.size.toULong())
        require(memorySize <= SocElfLoader.MAX_SEGMENT_BYTES.toULong())
        require(address.toULong() + memorySize <= 0x100000000uL)
    }
}

data class SocExecutableImage(val architecture: SocArchitecture, val entryPoint: UInt, val segments: List<SocExecutableSegment>)

sealed interface SocExecutableResult {
    data class Loaded(val image: SocExecutableImage) : SocExecutableResult
    data class Rejected(val reason: String, val notice: LocalizedText = LocalizedText.literal(reason)) : SocExecutableResult
}

/** ELF32 executable loading uses physical PT_LOAD addresses, as the desktop loader does. */
object SocElfLoader {
    const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
    fun read(bytes: ByteArray, architecture: SocArchitecture): SocExecutableResult {
        fun reject(reason: String, key: String, vararg arguments: Pair<String, Any?>) = SocExecutableResult.Rejected(reason, message(key, *arguments))
        if (bytes.size < 52) return reject("ELF header is truncated.", "soc_elf_header_truncated")
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte())
            return reject("File is not an ELF executable.", "soc_elf_not_elf")
        if (bytes[4] != 1.toByte()) return reject("Only ELF32 executables are supported.", "soc_elf_class")
        if (bytes[5] != 1.toByte()) return reject("Processor requires a little-endian executable.", "soc_elf_endian")
        if (bytes[6] != 1.toByte()) return reject("Unsupported ELF identification version.", "soc_elf_version")
        fun u16(offset: Int) = bytes[offset].toUByte().toInt() or (bytes[offset + 1].toUByte().toInt() shl 8)
        fun u32(offset: Int) = (bytes[offset].toUByte().toUInt()) or (bytes[offset + 1].toUByte().toUInt() shl 8) or
            (bytes[offset + 2].toUByte().toUInt() shl 16) or (bytes[offset + 3].toUByte().toUInt() shl 24)
        if (u16(16) != 2) return reject("ELF file is not executable.", "soc_elf_not_executable")
        if (u16(18) != architecture.elfMachine) return reject("ELF architecture does not match ${architecture.name}.", "soc_elf_architecture", "architecture" to architecture.name)
        if (u32(20) != 1u || u16(40) < 52) return reject("Invalid ELF header.", "soc_elf_header_invalid")
        val offset = u32(28).toULong()
        val entrySize = u16(42)
        val count = u16(44)
        if (count == 0 || entrySize < 32) return reject("ELF has no valid program headers.", "soc_elf_no_headers")
        if (offset + count.toULong() * entrySize.toULong() > bytes.size.toULong()) return reject("ELF program header table is truncated.", "soc_elf_table_truncated")
        val segments = mutableListOf<SocExecutableSegment>()
        var total = 0uL
        for (index in 0 until count) {
            val at = (offset + index.toULong() * entrySize.toULong()).toInt()
            if (u32(at) != 1u) continue
            val fileOffset = u32(at + 4).toULong()
            val address = u32(at + 12)
            val fileSize = u32(at + 16).toULong()
            val memorySize = u32(at + 20).toULong()
            if (fileSize > memorySize) return reject("ELF segment file size exceeds its memory size.", "soc_elf_size")
            if (memorySize > MAX_SEGMENT_BYTES.toULong() || total + memorySize > MAX_SEGMENT_BYTES.toULong()) return reject("ELF load exceeds the 64 MiB limit.", "soc_elf_limit")
            if (fileOffset + fileSize > bytes.size.toULong()) return reject("ELF loadable segment is truncated.", "soc_elf_segment_truncated")
            if (address.toULong() + memorySize > 0x100000000uL) return reject("ELF segment wraps the address space.", "soc_elf_address_wrap")
            if (memorySize == 0uL) continue
            segments += SocExecutableSegment(address, bytes.copyOfRange(fileOffset.toInt(), (fileOffset + fileSize).toInt()),
                memorySize, executable = u32(at + 24) and 1u != 0u)
            total += memorySize
        }
        if (segments.isEmpty()) return reject("ELF has no loadable segments.", "soc_elf_no_segments")
        return SocExecutableResult.Loaded(SocExecutableImage(architecture, u32(24), segments))
    }
}
