package dev.logisim.mobile.core

/** Desktop single-context level-triggered PLIC with source IDs 1 through 32. */
class SocPlicDevice(
    override val id: String,
    startAddress: UInt = 0x0C000000u,
    val sourceCount: Int = 0,
) : SocBusDevice {
    override val startAddress = startAddress and 3u.inv()
    override val sizeInBytes = 0x00400000uL
    private var pending = 0uL
    private var enabled = 0uL
    private var claimed = 0uL
    private var threshold = 0u
    private val sourceMask get() = if (sourceCount == 0) 0uL else ((1uL shl sourceCount) - 1uL) shl 1
    init { require(sourceCount in 0..32) }
    override val irq get() = threshold == 0u && pending and enabled and sourceMask != 0uL
    fun setIrqLines(lines: UInt) {
        val claimedMask = claimed and sourceMask
        val levelUnclaimed = (lines.toULong() shl 1) and sourceMask and claimedMask.inv()
        pending = ((pending and (levelUnclaimed or claimedMask)) or levelUnclaimed) and claimedMask.inv()
    }
    override fun transact(request: SocBusRequest): SocBusReply {
        if (request.size != SocAccessSize.WORD) return SocBusReply(error = SocBusError.UNSUPPORTED_ACCESS)
        val offset = request.address - startAddress
        var data = 0u
        when (offset) {
            0x1000u, 0x1004u -> {
                if (request.write) return SocBusReply(error = SocBusError.READ_ONLY)
                data = if (offset == 0x1000u) pending.toUInt() else (pending shr 32).toUInt()
            }
            0x2000u, 0x2004u -> {
                val upper = offset == 0x2004u
                if (request.read) data = if (upper) (enabled shr 32).toUInt() else enabled.toUInt()
                if (request.write) enabled = (if (upper) (enabled and 0xFFFFFFFFuL) or (request.writeData.toULong() shl 32)
                    else (enabled and 0xFFFFFFFF00000000uL) or request.writeData.toULong()) and sourceMask
            }
            0x200000u -> { if (request.read) data = threshold; if (request.write) threshold = request.writeData }
            0x200004u -> {
                if (request.read && threshold == 0u) {
                    val candidates = pending and enabled and sourceMask
                    val source = (1..sourceCount).firstOrNull { candidates and (1uL shl it) != 0uL }
                    if (source != null) { data = source.toUInt(); pending = pending and (1uL shl source).inv(); claimed = claimed or (1uL shl source) }
                }
                if (request.write && request.writeData in 1u..sourceCount.toUInt()) claimed = claimed and (1uL shl request.writeData.toInt()).inv()
            }
            else -> return SocBusReply(error = SocBusError.MISALIGNED_ADDRESS)
        }
        return SocBusReply(data)
    }
    override fun reset() { pending = 0uL; enabled = 0uL; claimed = 0uL; threshold = 0u }
    override fun copyDevice(): SocPlicDevice = SocPlicDevice(id, startAddress, sourceCount).also {
        it.pending = pending; it.enabled = enabled; it.claimed = claimed; it.threshold = threshold
    }
}
