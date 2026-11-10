package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** Access widths use the desktop bus identifiers, not the number of bytes. */
enum class SocAccessSize(val bytes: Int, val desktopId: Int) {
    BYTE(1, 1), HALF_WORD(2, 2), WORD(4, 3)
}

enum class SocBusError(val desktopId: Int) {
    NO_RESPONDER(1), NO_SLAVES(2), MULTIPLE_RESPONDERS(3), NON_ATOMIC_READ_WRITE(4),
    NO_BUS_CONNECTED(5), MISALIGNED_ADDRESS(6), UNSUPPORTED_ACCESS(7), READ_ONLY(8),
    WRITE_ONLY(9), REGISTER_NOT_FOUND(10);

    val notice: LocalizedText get() = message("soc_bus_error_${name.lowercase()}")
}

data class SocBusRequest(
    val address: UInt,
    val writeData: UInt = 0u,
    val size: SocAccessSize = SocAccessSize.WORD,
    val read: Boolean = true,
    val write: Boolean = false,
    val atomic: Boolean = false,
    val initiator: String = "",
    val hidden: Boolean = false,
)

data class SocBusReply(val data: UInt = 0u, val error: SocBusError? = null, val responder: String? = null) {
    val successful: Boolean get() = error == null
}

/** One machine owns an implementation; processor definitions never share mutable memory. */
interface SocCpuMemory {
    fun read(address: UInt, size: SocAccessSize = SocAccessSize.WORD): SocBusReply
    fun write(address: UInt, value: UInt, size: SocAccessSize = SocAccessSize.WORD): SocBusReply
}

data class SocInstructionTrace(
    val pc: UInt,
    val instruction: UInt,
    val assembly: String,
    val registerWrites: Map<Int, UInt> = emptyMap(),
    val diagnostic: String? = null,
    val notice: LocalizedText? = diagnostic?.let(LocalizedText::literal),
)

interface SocBusDevice {
    val id: String
    val startAddress: UInt
    val sizeInBytes: ULong
    val irq: Boolean get() = false
    fun accepts(address: UInt): Boolean = address.toULong() >= startAddress.toULong() &&
        address.toULong() < startAddress.toULong() + sizeInBytes
    fun transact(request: SocBusRequest): SocBusReply
    fun reset()
    fun copyDevice(): SocBusDevice
}

interface SocBusSniffer {
    fun observe(request: SocBusRequest, reply: SocBusReply)
}

data class SocBusTrace(val sequence: Long, val request: SocBusRequest, val reply: SocBusReply)

/** Each circuit instance receives a separate address space and separate device copies. */
class SocAddressSpace(
    val id: String,
    devices: List<SocBusDevice> = emptyList(),
    val traceLimit: Int = 10000,
) : SocCpuMemory {
    private val attached = devices.toMutableList()
    private val history = ArrayDeque<SocBusTrace>()
    private var sequence = 0L
    init {
        require(id.isNotBlank())
        require(traceLimit in 0..100000)
        require(devices.map { it.id }.distinct().size == devices.size)
    }
    val devices: List<SocBusDevice> get() = attached.toList()
    val trace: List<SocBusTrace> get() = history.toList()
    val interruptLines: Map<String, Boolean> get() = attached.associate { it.id to it.irq }

    fun attach(device: SocBusDevice) {
        require(attached.none { it.id == device.id }) { "Duplicate SoC device: ${device.id}." }
        attached += device
    }

    fun transact(request: SocBusRequest): SocBusReply {
        val responders = attached.filter { it.accepts(request.address) }
        val reply = when {
            attached.isEmpty() -> SocBusReply(error = SocBusError.NO_SLAVES)
            request.read && request.write && !request.atomic -> SocBusReply(error = SocBusError.NON_ATOMIC_READ_WRITE)
            responders.isEmpty() -> SocBusReply(error = SocBusError.NO_RESPONDER)
            responders.size > 1 -> SocBusReply(error = SocBusError.MULTIPLE_RESPONDERS)
            else -> responders.single().transact(request).copy(responder = responders.single().id)
        }
        if (!request.hidden) {
            attached.forEach { if (it is SocVgaDevice && it.reloadPending) it.refreshFrom(this) }
            if (reply.successful) attached.filterIsInstance<SocBusSniffer>().forEach { it.observe(request, reply) }
            if (traceLimit > 0) {
                history.addLast(SocBusTrace(sequence++, request, reply))
                while (history.size > traceLimit) history.removeFirst()
            }
        }
        return reply
    }

    override fun read(address: UInt, size: SocAccessSize): SocBusReply = transact(SocBusRequest(address, size = size))
    override fun write(address: UInt, value: UInt, size: SocAccessSize): SocBusReply =
        transact(SocBusRequest(address, value, size, read = false, write = true))

    fun reset() { attached.forEach { it.reset() }; history.clear(); sequence = 0 }
    fun clearTrace() { history.clear(); sequence = 0 }
    fun copy(): SocAddressSpace = SocAddressSpace(id, attached.map { it.copyDevice() }, traceLimit).also {
        it.history.addAll(history); it.sequence = sequence
    }

    fun withDevices(devices: List<SocBusDevice>): SocAddressSpace = SocAddressSpace(id, devices, traceLimit).also {
        it.history.addAll(history); it.sequence = sequence
    }

    fun load(image: SocExecutableImage): SocBusReply {
        val targets = image.segments.map { segment ->
            val responders = attached.filter { it.accepts(segment.address) }
            val memory = responders.singleOrNull() as? SocMemoryDevice
                ?: return SocBusReply(error = if (responders.size > 1) SocBusError.MULTIPLE_RESPONDERS else SocBusError.NO_RESPONDER)
            val start = segment.address.toULong()
            val end = start + segment.memorySize
            if (end > memory.startAddress.toULong() + memory.sizeInBytes)
                return SocBusReply(error = SocBusError.NO_RESPONDER)
            if (attached.any { device ->
                    device !== memory && device.sizeInBytes > 0uL && device.startAddress.toULong() < end &&
                        start < device.startAddress.toULong() + device.sizeInBytes
                }) return SocBusReply(error = SocBusError.MULTIPLE_RESPONDERS)
            memory to segment
        }
        targets.forEach { (memory, segment) -> memory.load(segment.address, segment.bytes, segment.memorySize) }
        return SocBusReply()
    }
}

class SocBusRouter(spaces: List<SocAddressSpace>) {
    private val buses = spaces.associateBy { it.id }
    val duplicateIds: Set<String> = spaces.groupBy { it.id }.filterValues { it.size > 1 }.keys
    fun bus(id: String): SocAddressSpace? = if (id in duplicateIds) null else buses[id]
    fun transact(id: String, request: SocBusRequest): SocBusReply = when {
        id in duplicateIds -> SocBusReply(error = SocBusError.MULTIPLE_RESPONDERS)
        else -> buses[id]?.transact(request) ?: SocBusReply(error = SocBusError.NO_BUS_CONNECTED)
    }
}
