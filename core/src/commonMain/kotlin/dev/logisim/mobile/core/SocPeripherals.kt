package dev.logisim.mobile.core

class SocMemoryDevice(
    override val id: String,
    startAddress: UInt = 0u,
    override val sizeInBytes: ULong = 1024u,
    seed: Long = 0,
) : SocBusDevice {
    override val startAddress = startAddress and 3u.inv()
    private val words = mutableMapOf<UInt, UInt>()
    private var random = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
    init { require(sizeInBytes in 4uL..0x100000000uL && this.startAddress.toULong() + sizeInBytes <= 0x100000000uL) }
    val storedWords: Map<UInt, UInt> get() = words.toMap()
    private fun word(address: UInt): UInt = words[address and 3u.inv()] ?: run {
        random = (random * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        (random ushr 16).toUInt()
    }
    override fun transact(request: SocBusRequest): SocBusReply {
        val shift = when (request.size) { SocAccessSize.WORD -> 0; SocAccessSize.HALF_WORD -> ((request.address shr 1) and 1u).toInt() * 16; SocAccessSize.BYTE -> (request.address and 3u).toInt() * 8 }
        val mask = when (request.size) { SocAccessSize.WORD -> UInt.MAX_VALUE; SocAccessSize.HALF_WORD -> 65535u; SocAccessSize.BYTE -> 255u }
        val value = if (request.read) (word(request.address) shr shift) and mask else 0u
        if (request.write) {
            val data = if (request.size == SocAccessSize.WORD) request.writeData else
                (word(request.address) and (mask shl shift).inv()) or ((request.writeData and mask) shl shift)
            words[request.address and 3u.inv()] = data
        }
        return SocBusReply(value)
    }
    fun load(address: UInt, bytes: ByteArray, memorySize: ULong = bytes.size.toULong()) {
        require(memorySize >= bytes.size.toULong() && address.toULong() >= startAddress.toULong() &&
            address.toULong() + memorySize <= startAddress.toULong() + sizeInBytes)
        require(memorySize <= 64u * 1024u * 1024u) { "Executable segment exceeds the loading limit." }
        for (i in 0 until memorySize.toInt()) {
            val target = address + i.toUInt()
            val base = target and 3u.inv()
            val shift = (target and 3u).toInt() * 8
            val data = if (i < bytes.size) bytes[i].toUByte().toUInt() else 0u
            words[base] = ((words[base] ?: 0u) and (255u shl shift).inv()) or (data shl shift)
        }
    }
    override fun reset() { }
    override fun copyDevice(): SocMemoryDevice = SocMemoryDevice(id, startAddress, sizeInBytes).also {
        it.words.putAll(words); it.random = random
    }
}

enum class SocPioDirection { INPUT, OUTPUT, BIDIRECTIONAL, SEPARATE_IN_OUT }
enum class SocCaptureEdge { RISING, FALLING, ANY }

data class SocPioSnapshot(val output: UInt, val capture: UInt, val direction: UInt, val interruptMask: UInt, val inputs: UInt)

class SocPioDevice(
    override val id: String,
    override val startAddress: UInt = 0u,
    val width: Int = 1,
    val direction: SocPioDirection = SocPioDirection.INPUT,
    val resetValue: UInt = 0u,
    val bitOperations: Boolean = false,
    val synchronousCapture: Boolean = false,
    val captureEdge: SocCaptureEdge = SocCaptureEdge.RISING,
    val clearCaptureBits: Boolean = false,
    val generatesIrq: Boolean = false,
    val edgeIrq: Boolean = false,
) : SocBusDevice {
    override val sizeInBytes = 24uL
    private val mask = if (width >= 32) UInt.MAX_VALUE else (1u shl width) - 1u
    private var output = resetValue
    private var capture = 0u
    private var directions = 0u
    private var interruptMask = 0u
    private var inputs = 0u
    private var oldInputs: UInt? = null
    init { require(width in 1..64) }
    val snapshot: SocPioSnapshot get() = SocPioSnapshot(output, capture, directions, interruptMask, inputs)
    override val irq: Boolean get() = generatesIrq && direction != SocPioDirection.OUTPUT &&
        ((if (edgeIrq && synchronousCapture) capture else inputs) and interruptMask) != 0u
    val outputBits: UInt get() = output and mask
    val drivenBits: UInt get() = when (direction) {
        SocPioDirection.INPUT -> 0u
        SocPioDirection.BIDIRECTIONAL -> directions and mask
        else -> mask
    }
    fun setInputs(value: UInt) {
        inputs = value and mask
        val previous = oldInputs
        if (previous != null && synchronousCapture) {
            capture = capture or when (captureEdge) {
                SocCaptureEdge.ANY -> previous xor inputs
                SocCaptureEdge.RISING -> previous.inv() and inputs
                SocCaptureEdge.FALLING -> previous and inputs.inv()
            }
        }
        oldInputs = inputs
    }
    override fun transact(request: SocBusRequest): SocBusReply {
        if (request.size != SocAccessSize.WORD) return SocBusReply(error = SocBusError.UNSUPPORTED_ACCESS)
        val offset = request.address - startAddress
        var data = 0u
        var error: SocBusError? = null
        when (offset) {
            0u -> {
                if (request.write) if (direction == SocPioDirection.INPUT) error = SocBusError.READ_ONLY else output = request.writeData
                if (request.read) if (direction == SocPioDirection.OUTPUT) error = SocBusError.WRITE_ONLY else data = inputs
            }
            4u -> if (direction != SocPioDirection.BIDIRECTIONAL) error = SocBusError.REGISTER_NOT_FOUND else {
                if (request.read) data = directions
                if (request.write) directions = request.writeData
            }
            8u -> if (!generatesIrq || direction == SocPioDirection.OUTPUT) error = SocBusError.REGISTER_NOT_FOUND else {
                if (request.read) data = interruptMask else if (request.write) interruptMask = request.writeData
            }
            12u -> if (!synchronousCapture) error = SocBusError.REGISTER_NOT_FOUND else {
                if (request.read) data = capture
                if (request.write) capture = if (clearCaptureBits) capture and request.writeData.inv() else 0u
            }
            16u, 20u -> if (!bitOperations) error = SocBusError.REGISTER_NOT_FOUND else {
                if (request.read) error = SocBusError.WRITE_ONLY
                output = if (offset == 20u) output and request.writeData.inv() else output or request.writeData
            }
            else -> error = SocBusError.MISALIGNED_ADDRESS
        }
        return SocBusReply(data, error)
    }
    override fun reset() { output = resetValue; capture = 0u; directions = 0u; interruptMask = 0u; inputs = 0u; oldInputs = null }
    override fun copyDevice(): SocPioDevice = SocPioDevice(id, startAddress, width, direction, resetValue, bitOperations,
        synchronousCapture, captureEdge, clearCaptureBits, generatesIrq, edgeIrq).also {
        it.output = output; it.capture = capture; it.directions = directions; it.interruptMask = interruptMask
        it.inputs = inputs; it.oldInputs = oldInputs
    }
}

class SocJtagUartDevice(
    override val id: String,
    override val startAddress: UInt = 0u,
    val writeCapacity: Int = 64,
    val readCapacity: Int = 64,
    val writeThreshold: Int = 8,
    val readThreshold: Int = 8,
) : SocBusDevice {
    override val sizeInBytes = 8uL
    private val writeFifo = ArrayDeque<Int>()
    private val readFifo = ArrayDeque<Int>()
    private var readEnable = false
    private var writeEnable = false
    private var activity = false
    init { require(writeCapacity in 8..32768 && readCapacity in 8..32768) }
    val writeQueue: List<Int> get() = writeFifo.toList()
    val readQueue: List<Int> get() = readFifo.toList()
    private val readPending get() = readEnable && readCapacity - readFifo.size <= readThreshold
    private val writePending get() = writeEnable && writeFifo.size <= writeThreshold
    override val irq: Boolean get() = readPending || writePending
    fun receive(value: Int): Boolean {
        if (readFifo.size >= readCapacity) return false
        readFifo.addLast(value and 255); activity = true; return true
    }
    fun transmit(): Int? = if (writeFifo.isEmpty()) null else writeFifo.removeFirst().also { activity = true }
    override fun transact(request: SocBusRequest): SocBusReply {
        if (request.size != SocAccessSize.WORD) return SocBusReply(error = SocBusError.UNSUPPORTED_ACCESS)
        var result = 0u
        when (request.address - startAddress) {
            0u -> {
                if (request.read && readFifo.isNotEmpty()) result = readFifo.removeFirst().toUInt() or 0x8000u or (readFifo.size.toUInt() shl 16)
                if (request.write && writeFifo.size < writeCapacity) writeFifo.addLast((request.writeData and 255u).toInt())
            }
            4u -> {
                if (request.read) result = (if (readEnable) 1u else 0u) or (if (writeEnable) 2u else 0u) or
                    (if (readPending) 0x100u else 0u) or (if (writePending) 0x200u else 0u) or
                    (if (activity) 0x400u else 0u) or ((writeCapacity - writeFifo.size).toUInt() shl 16)
                if (request.write) { readEnable = request.writeData and 1u != 0u; writeEnable = request.writeData and 2u != 0u; if (request.writeData and 0x400u != 0u) activity = false }
            }
            else -> return SocBusReply(error = SocBusError.MISALIGNED_ADDRESS)
        }
        return SocBusReply(result)
    }
    override fun reset() { writeFifo.clear(); readFifo.clear(); readEnable = false; writeEnable = false; activity = false }
    override fun copyDevice(): SocJtagUartDevice = SocJtagUartDevice(id, startAddress, writeCapacity, readCapacity, writeThreshold, readThreshold).also {
        it.writeFifo.addAll(writeFifo); it.readFifo.addAll(readFifo); it.readEnable = readEnable; it.writeEnable = writeEnable; it.activity = activity
    }
}

class SocDmaDevice(
    override val id: String,
    startAddress: UInt = 0u,
    val burstSize: Int = 16,
    val sourceBus: String = "",
    val destinationBus: String = "",
) : SocBusDevice {
    override val startAddress = startAddress and 3u.inv()
    override val sizeInBytes = 24uL
    private var source = 0u
    private var destination = 0u
    private var length = 0u
    private var control = 0u
    private var busy = false
    private var done = false
    private var completed = 0u
    private var interrupt = false
    override val irq: Boolean get() = interrupt
    val bytesDone get() = completed
    val isBusy get() = busy
    val isDone get() = done
    init { require(burstSize in 1..1024 && burstSize and (burstSize - 1) == 0) }
    fun clock(router: SocBusRouter, controlBus: String) {
        if (!busy) return
        val srcBus = sourceBus.ifBlank { controlBus }
        val dstBus = destinationBus.ifBlank { controlBus }
        if (router.bus(srcBus) == null || router.bus(dstBus) == null) return
        var transferred = 0u
        val words = minOf(burstSize.toUInt(), (length - completed) / 4u)
        for (index in 0 until words.toInt()) {
            val offset = completed + index.toUInt() * 4u
            val read = router.transact(srcBus, SocBusRequest(if (control and 8u == 0u) source + offset else source,
                initiator = id, hidden = true))
            if (!read.successful) break
            val write = router.transact(dstBus, SocBusRequest(if (control and 4u == 0u) destination + offset else destination,
                read.data, read = false, write = true, initiator = id))
            if (!write.successful) break
            transferred += 4u
        }
        completed += transferred
        if (completed >= length) { busy = false; done = true; interrupt = control and 2u != 0u }
    }
    override fun transact(request: SocBusRequest): SocBusReply {
        if (request.size != SocAccessSize.WORD) return SocBusReply(error = SocBusError.UNSUPPORTED_ACCESS)
        var data = 0u
        when (request.address - startAddress) {
            0u -> { if (request.read) data = source; if (request.write && !busy) source = request.writeData and 3u.inv() }
            4u -> { if (request.read) data = destination; if (request.write && !busy) destination = request.writeData and 3u.inv() }
            8u -> { if (request.read) data = length; if (request.write && !busy) length = request.writeData and 3u.inv() }
            12u -> {
                if (request.read) data = control
                if (request.write) {
                    control = request.writeData and 14u
                    if (request.writeData and 1u != 0u && !busy && length > 0u) { busy = true; done = false; completed = 0u; interrupt = false }
                }
            }
            16u -> { if (request.read) data = (if (busy) 1u else 0u) or (if (done) 2u else 0u); if (request.write && request.writeData and 2u != 0u) { done = false; interrupt = false } }
            20u -> { if (request.read) data = completed; if (request.write) return SocBusReply(data, SocBusError.READ_ONLY) }
            else -> return SocBusReply(error = SocBusError.MISALIGNED_ADDRESS)
        }
        return SocBusReply(data)
    }
    override fun reset() { source = 0u; destination = 0u; length = 0u; control = 0u; busy = false; done = false; completed = 0u; interrupt = false }
    override fun copyDevice(): SocDmaDevice = SocDmaDevice(id, startAddress, burstSize, sourceBus, destinationBus).also {
        it.source = source; it.destination = destination; it.length = length; it.control = control
        it.busy = busy; it.done = done; it.completed = completed; it.interrupt = interrupt
    }
}

class SocVgaDevice(
    override val id: String,
    override val startAddress: UInt = 0u,
    val bufferAddress: UInt = 0u,
    private val initialMode: Int = 0,
    val softwareModes: UInt = 7u,
) : SocBusDevice, SocBusSniffer {
    override val sizeInBytes = 4uL
    var mode: Int = initialMode
        private set
    private val pixels = mutableMapOf<Int, UInt>()
    /**
     * Evolution redraws the whole frame from the bus when the display starts, after a reset and after a
     * mode change. The address space performs that reload at its next transaction.
     */
    var reloadPending = true
        private set
    val width get() = dimensions[mode].first
    val height get() = dimensions[mode].second
    val argbPixels: Map<Int, UInt> get() = pixels.toMap()
    init { require(initialMode in dimensions.indices) }
    fun pixel(x: Int, y: Int): UInt { require(x in 0 until width && y in 0 until height); return pixels[y * width + x] ?: 0xFF000000u }
    override fun transact(request: SocBusRequest): SocBusReply {
        if (request.size != SocAccessSize.WORD) return SocBusReply(error = SocBusError.UNSUPPORTED_ACCESS)
        val result = if (request.read) softwareModes else 0u
        if (request.write) {
            val requested = dimensions.indices.firstOrNull {
                val mask = 1u shl it
                request.writeData == mask && softwareModes and mask != 0u
            } ?: initialMode
            if (requested != mode) {
                mode = requested
                pixels.clear()
                reloadPending = true
            }
        }
        return SocBusReply(result)
    }
    override fun observe(request: SocBusRequest, reply: SocBusReply) {
        if (!request.write || !reply.successful) return
        val address = request.address.toULong()
        val start = bufferAddress.toULong()
        if (address >= start && address < start + (width * height * 4).toULong()) {
            pixels[((address - start) / 4u).toInt()] = request.writeData or 0xFF000000u
        }
    }
    fun refreshFrom(bus: SocAddressSpace) {
        for (index in 0 until width * height) {
            val reply = bus.transact(SocBusRequest(bufferAddress + index.toUInt() * 4u, initiator = id, hidden = true))
            pixels[index] = (if (reply.successful) reply.data else 0u) or 0xFF000000u
        }
        reloadPending = false
    }
    override fun reset() { pixels.clear(); reloadPending = true }
    override fun copyDevice(): SocVgaDevice = SocVgaDevice(id, startAddress, bufferAddress, initialMode, softwareModes).also {
        it.mode = mode
        it.pixels.putAll(pixels)
        it.reloadPending = reloadPending
    }
    companion object { val dimensions = listOf(160 to 120, 320 to 240, 640 to 480, 800 to 600, 1024 to 768) }
}
