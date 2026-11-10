package dev.logisim.mobile.core

data class SocProgramWord(val address: UInt, val instruction: UInt)

/** A paged view of the file-backed executable words, excluding data and zero-fill. */
class SocProgramListing(image: SocExecutableImage) {
    private data class Block(val address: UInt, val bytes: ByteArray, val first: Int, val size: Int, val byteOffset: Int)

    val size: Int
    private val blocks: List<Block>

    init {
        var first = 0
        blocks = image.segments.filter { it.executable }.sortedBy { it.address }.mapNotNull { segment ->
            val skip = ((0u - segment.address) and 3u).toInt()
            val count = (segment.bytes.size - skip) / 4
            if (count <= 0) null
            else Block(segment.address + skip.toUInt(), segment.bytes.copyOf(), first, count, skip).also { first += count }
        }
        size = first
    }

    operator fun get(index: Int): SocProgramWord {
        require(index in 0 until size)
        val found = blocks.binarySearchBy(index) { it.first }
        val block = blocks[if (found >= 0) found else -found - 2]
        val offset = (index - block.first) * 4
        var word = 0u
        for (byte in 0..3) word = word or (block.bytes[block.byteOffset + offset + byte].toUByte().toUInt() shl (byte * 8))
        return SocProgramWord(block.address + offset.toUInt(), word)
    }

    fun indexOf(address: UInt): Int? {
        for (block in blocks) {
            if (address < block.address) break
            val offset = address - block.address
            if (offset % 4u == 0u && offset / 4u < block.size.toUInt()) return block.first + (offset / 4u).toInt()
        }
        return null
    }
}

data class SocSetBreakpointEvent(val address: UInt, val enabled: Boolean) : ComponentEvent

data class SocCpuDebugState(
    val program: SocProgramListing? = null,
    val breakpoints: Set<UInt> = emptySet(),
    val breakpointHit: UInt? = null,
    private val resumeAt: UInt? = null,
) {
    internal fun reset() = copy(breakpointHit = null, resumeAt = null)

    internal fun setRunning(running: Boolean) =
        if (running) copy(breakpointHit = null, resumeAt = breakpointHit ?: resumeAt)
        else copy(resumeAt = null)

    internal fun setBreakpoint(event: SocSetBreakpointEvent) =
        if (event.enabled) copy(breakpoints = breakpoints + event.address)
        else copy(breakpoints = breakpoints - event.address, breakpointHit = breakpointHit.takeUnless { it == event.address })

    internal fun beforeFetch(address: UInt, singleStep: Boolean) =
        copy(breakpointHit = address.takeIf { !singleStep && it != resumeAt && it in breakpoints }, resumeAt = null)
}
