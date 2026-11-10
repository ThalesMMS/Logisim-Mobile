package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Immutable sparse words. Zero words do not occupy entries. */
class MemoryImage(
    val addressBits: Int,
    val dataBits: Int,
    cells: Map<Long, ULong> = emptyMap(),
) {
    val capacity: Long
    val mask: ULong
    val cells: Map<Long, ULong>
    val nonZeroWords: Map<Long, ULong> get() = cells

    init {
        requireLocalized(addressBits in 2..24) { message(Messages.MemoryErrorAddressBits) }
        requireLocalized(dataBits in 1..64) { message(Messages.MemoryErrorDataBits) }
        capacity = 1L shl addressBits
        mask = if (dataBits == 64) ULong.MAX_VALUE else (1uL shl dataBits) - 1uL
        for ((address, value) in cells) {
            requireLocalized(address in 0 until capacity) { message(Messages.MemoryErrorAddress, "address" to address) }
            requireLocalized(value and mask.inv() == 0uL) { message(Messages.MemoryErrorValueWidth, "bits" to dataBits).copy(quantity = dataBits.toLong()) }
        }
        this.cells = cells.filterValues { it != 0uL }.toMap()
    }

    operator fun get(address: Long): ULong {
        requireLocalized(address in 0 until capacity) { message(Messages.MemoryErrorAddress, "address" to address) }
        return cells[address] ?: 0uL
    }

    fun withCells(changes: Map<Long, ULong>): MemoryImage {
        if (changes.isEmpty()) return this
        val next = cells.toMutableMap()
        for ((address, value) in changes) {
            requireLocalized(address in 0 until capacity) { message(Messages.MemoryErrorAddress, "address" to address) }
            requireLocalized(value and mask.inv() == 0uL) { message(Messages.MemoryErrorValueWidth, "bits" to dataBits).copy(quantity = dataBits.toLong()) }
            if (value == 0uL) next.remove(address) else next[address] = value
        }
        return MemoryImage(addressBits, dataBits, next)
    }

    /** Resizing explicitly drops inaccessible addresses and high data bits. */
    fun resized(addressBits: Int, dataBits: Int): MemoryImage {
        val target = MemoryImage(addressBits, dataBits)
        return MemoryImage(addressBits, dataBits, cells.filterKeys { it < target.capacity }
            .mapValues { it.value and target.mask })
    }

    fun cleared(): MemoryImage = if (cells.isEmpty()) this else MemoryImage(addressBits, dataBits)

    override fun equals(other: Any?): Boolean = other is MemoryImage &&
        addressBits == other.addressBits && dataBits == other.dataBits && cells == other.cells

    override fun hashCode(): Int = 31 * (31 * addressBits + dataBits) + cells.hashCode()

    override fun toString(): String = "MemoryImage($addressBits, $dataBits, ${cells.size} words)"
}
