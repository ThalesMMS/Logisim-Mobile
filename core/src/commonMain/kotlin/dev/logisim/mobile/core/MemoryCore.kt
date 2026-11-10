package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

internal const val EVOLUTION_PAGE_SIZE = 4096L

internal fun MemoryImage.hasFirstPageWords() = cells.keys.any { it < EVOLUTION_PAGE_SIZE }

private fun evolutionStorageBits(dataBits: Int) = when {
    dataBits <= 8 -> 8
    dataBits <= 16 -> 16
    dataBits <= 32 -> 32
    else -> 64
}

enum class MemoryBus(val serialized: String) {
    SHARED("bidir"), SEPARATE("bibus"), CONTROLLED("bibusControlled")
}

enum class MemoryEnables(val serialized: String) { BYTE("byte"), LINE("line") }
enum class MemoryTrigger(val serialized: String) { RISING("rising"), FALLING("falling"), HIGH("high"), LOW("low") }
enum class MemoryReadBehavior(val serialized: String) { AFTER_WRITE("raw"), BEFORE_WRITE("war") }

enum class OriginalRamInterface(val serialized: String) {
    COMBINED("combined"), ASYNCHRONOUS("asynch"), SEPARATE("separate")
}

internal const val ORIGINAL_RAM_ATTRIBUTE = "logisim-mobile-original-ram"
internal const val ORIGINAL_ROM_ATTRIBUTE = "logisim-mobile-original-rom"

data class MemoryConfiguration(
    val addressBits: Int = 8,
    val dataBits: Int = 8,
    val readOnly: Boolean = false,
    val dualPort: Boolean = false,
    val bus: MemoryBus = MemoryBus.SEPARATE,
    val enables: MemoryEnables = MemoryEnables.BYTE,
    val trigger: MemoryTrigger = MemoryTrigger.RISING,
    val asynchronousRead: Boolean = false,
    val readBehavior: MemoryReadBehavior = MemoryReadBehavior.AFTER_WRITE,
    val byteEnables: Boolean = false,
    val lineSize: Int = 1,
    val allowMisaligned: Boolean = false,
    val clearPin: Boolean = false,
    val nonvolatile: Boolean = false,
    val classic: Boolean = false,
    val originalInterface: OriginalRamInterface? = null,
    val originalRom: Boolean = false,
) {
    init {
        requireLocalized(addressBits in 2..24) { message(Messages.MemoryConfigAddressWidth) }
        requireLocalized(dataBits in 1..64) { message(Messages.MemoryConfigDataWidth) }
        requireLocalized(lineSize in setOf(1, 2, 4, 8)) { message(Messages.MemoryConfigLineSize) }
        requireLocalized(!readOnly || !dualPort) { message(Messages.MemoryConfigRomDual) }
        require(!originalRom || readOnly && lineSize == 1)
    }

    val portCount: Int get() = if (dualPort) 2 else 1
    val synchronous: Boolean get() = trigger == MemoryTrigger.RISING || trigger == MemoryTrigger.FALLING
    val dataLines: Int get() = if (readOnly || enables == MemoryEnables.LINE) lineSize else 1
    val byteEnableCount: Int get() = if (!readOnly && enables == MemoryEnables.BYTE && synchronous && byteEnables && dataBits > 8)
        (dataBits + 7) / 8 else 0
    val lineEnableCount: Int get() = if (!readOnly && enables == MemoryEnables.LINE && lineSize > 1) lineSize else 0
    val hasClock: Boolean get() = !readOnly && (enables == MemoryEnables.LINE || synchronous)
    val hasOutputEnable: Boolean get() = !readOnly && (bus == MemoryBus.SHARED || enables == MemoryEnables.BYTE)

    companion object {
        fun fromAttributes(kind: String, raw: Map<String, String>): MemoryConfiguration {
            fun integer(name: String, default: Int): Int = raw[name]?.let {
                it.toIntOrNull() ?: throw LocalizedArgumentException(message(Messages.MemoryConfigAttributeInvalid, "name" to name, "value" to it))
            } ?: default
            fun boolean(name: String, default: Boolean = false): Boolean = raw[name]?.let {
                it.toBooleanStrictOrNull() ?: throw LocalizedArgumentException(message(Messages.MemoryConfigAttributeInvalid, "name" to name, "value" to it))
            } ?: default
            fun <T> option(name: String, default: T, options: List<T>, text: (T) -> String): T =
                raw[name]?.let { value -> options.firstOrNull { text(it) == value }
                    ?: throw LocalizedArgumentException(message(Messages.MemoryConfigAttributeInvalid, "name" to name, "value" to value)) } ?: default
            if (kind == "RAM" && raw[ORIGINAL_RAM_ATTRIBUTE] == "true" && "databus" !in raw) {
                val mode = OriginalRamInterface.entries.firstOrNull { it.serialized == raw["bus"] }
                    ?: OriginalRamInterface.COMBINED
                return MemoryConfiguration(
                    addressBits = integer("addrWidth", 8), dataBits = integer("dataWidth", 8),
                    bus = if (mode == OriginalRamInterface.SEPARATE) MemoryBus.SEPARATE else MemoryBus.SHARED,
                    trigger = if (mode == OriginalRamInterface.ASYNCHRONOUS) MemoryTrigger.LOW else MemoryTrigger.RISING,
                    asynchronousRead = true, clearPin = true, classic = true, originalInterface = mode,
                )
            }
            if (kind == "ROM" && raw[ORIGINAL_ROM_ATTRIBUTE] == "true") {
                return MemoryConfiguration(
                    addressBits = integer("addrWidth", 8), dataBits = integer("dataWidth", 8),
                    readOnly = true, classic = true, originalRom = true,
                )
            }
            val line = raw["line"] ?: "single"
            val lineSize = mapOf("single" to 1, "dual" to 2, "quad" to 4, "octo" to 8)[line]
                ?: throw LocalizedArgumentException(message(Messages.MemoryConfigAttributeInvalid, "name" to "line", "value" to line))
            val appearance = raw["appearance"] ?: "logisim_evolution"
            requireLocalized(appearance in setOf("classic", "evolution", "logisim_evolution")) {
                message(Messages.MemoryConfigAttributeInvalid, "name" to "appearance", "value" to appearance)
            }
            val readOnly = kind == "ROM"
            return MemoryConfiguration(
                addressBits = integer("addrWidth", 8), dataBits = integer("dataWidth", 8),
                readOnly = readOnly, dualPort = kind == "DualRAM",
                bus = option("databus", MemoryBus.SEPARATE, MemoryBus.entries, MemoryBus::serialized),
                enables = option("enables", MemoryEnables.BYTE, MemoryEnables.entries, MemoryEnables::serialized),
                trigger = option("trigger", MemoryTrigger.RISING, MemoryTrigger.entries, MemoryTrigger::serialized),
                asynchronousRead = boolean("asyncread"),
                readBehavior = option("readbehav", MemoryReadBehavior.AFTER_WRITE, MemoryReadBehavior.entries, MemoryReadBehavior::serialized),
                byteEnables = option("byteenables", false, listOf(false, true)) { if (it) "byteEnables" else "NobyteEnables" },
                lineSize = lineSize, allowMisaligned = boolean("misaligned"), clearPin = boolean("clearpin"),
                nonvolatile = option("type", false, listOf(false, true)) { if (it) "nonvolatile" else "volatile" },
                classic = appearance == "classic",
            )
        }
    }
}

data class MemoryPortInput(
    val address: LogicVector,
    val data: List<LogicVector> = emptyList(),
    val outputEnable: LogicValue = LogicValue.UNKNOWN,
    val writeEnable: LogicValue = LogicValue.UNKNOWN,
    val clock: LogicValue = LogicValue.UNKNOWN,
    val lineEnables: List<LogicValue> = emptyList(),
    val byteEnables: List<LogicValue> = emptyList(),
    val chipSelect: LogicValue = LogicValue.UNKNOWN,
)

data class MemoryRuntimeState(
    val image: MemoryImage,
    val clocks: List<LogicValue>,
    val outputs: List<List<LogicVector>>,
    val currentAddresses: List<Long>,
    internal val lineOverflowWords: Map<Long, ULong> = emptyMap(),
    // Resizing an allocated page can shorten it, even when all its words are zero.
    internal val firstPageSize: Long? = EVOLUTION_PAGE_SIZE.takeIf { lineOverflowWords.isNotEmpty() || image.hasFirstPageWords() },
    // The Evolution page's signed backing type can contain bits above the current word width.
    internal val rawHighBits: Map<Long, ULong> = emptyMap(),
) {
    internal fun replaceVisibleImage(replacement: MemoryImage): MemoryRuntimeState {
        return copy(image = replacement,
            firstPageSize = firstPageSize ?: EVOLUTION_PAGE_SIZE.takeIf { replacement.hasFirstPageWords() },
            rawHighBits = rawHighBits.filterKeys { image[it] == replacement[it] })
    }

    internal fun clearContents(): MemoryRuntimeState {
        val changedFirstPage = image.hasFirstPageWords() || lineOverflowWords.isNotEmpty()
        val retainedHighBits = if (rawHighBits.isEmpty()) rawHighBits else {
            val changedPages = image.cells.keys.mapTo(mutableSetOf()) { it / EVOLUTION_PAGE_SIZE }
            if (changedFirstPage) changedPages += 0L
            rawHighBits.filterKeys { it / EVOLUTION_PAGE_SIZE !in changedPages }
        }
        return copy(image = image.cleared(), lineOverflowWords = emptyMap(), rawHighBits = retainedHighBits,
            firstPageSize = if (changedFirstPage) null else firstPageSize)
    }

    internal fun importVisibleImage(loaded: MemoryImageImportResult.Success): MemoryRuntimeState {
        val firstPageChanged = image.hasFirstPageWords() || rawHighBits.keys.any { it < EVOLUTION_PAGE_SIZE }
        val pageSize = when {
            loaded.firstPageAllocated -> firstPageSize ?: EVOLUTION_PAGE_SIZE
            firstPageChanged && lineOverflowWords.isEmpty() -> null
            else -> firstPageSize
        }
        return copy(image = loaded.image, firstPageSize = pageSize, rawHighBits = emptyMap())
    }

    internal fun reconfigured(configuration: MemoryConfiguration): MemoryRuntimeState {
        if (image.addressBits == configuration.addressBits && image.dataBits == configuration.dataBits) {
            return initial(configuration, image).copy(lineOverflowWords = lineOverflowWords,
                firstPageSize = firstPageSize, rawHighBits = rawHighBits)
        }
        if (configuration.readOnly || configuration.originalInterface != null) {
            return initial(configuration, image.resized(configuration.addressBits, configuration.dataBits))
        }

        val target = MemoryImage(configuration.addressBits, configuration.dataBits)
        val newStorageMask = ULong.MAX_VALUE shr (64 - evolutionStorageBits(target.dataBits))
        val signShift = 64 - evolutionStorageBits(image.dataBits)
        val stored = image.cells.toMutableMap()
        stored.putAll(lineOverflowWords)
        for ((address, bits) in rawHighBits) stored[address] = (stored[address] ?: 0uL) or bits
        val resizedCells = mutableMapOf<Long, ULong>()
        val resizedHighBits = mutableMapOf<Long, ULong>()
        for ((address, bits) in stored) {
            if (address >= target.capacity) continue
            // Evolution reads the old primitive with sign extension before storing in the new type.
            val signedWord = (bits shl signShift).toLong() shr signShift
            val resizedWord = signedWord.toULong() and newStorageMask
            val word = resizedWord and target.mask
            val highBits = resizedWord and target.mask.inv()
            if (word != 0uL) resizedCells[address] = word
            if (highBits != 0uL) resizedHighBits[address] = highBits
        }
        val resized = MemoryImage(target.addressBits, target.dataBits, resizedCells)
        return initial(configuration, resized).copy(rawHighBits = resizedHighBits,
            firstPageSize = firstPageSize?.let { minOf(resized.capacity, EVOLUTION_PAGE_SIZE) })
    }

    companion object {
        fun initial(configuration: MemoryConfiguration, image: MemoryImage = MemoryImage(configuration.addressBits, configuration.dataBits)) =
            MemoryRuntimeState(image,
                List(configuration.portCount) { LogicValue.LOW },
                List(configuration.portCount) { List(configuration.dataLines) { LogicVector.unknown(configuration.dataBits) } },
                List(configuration.portCount) { 0L })
    }
}

data class MemoryTransition(val state: MemoryRuntimeState, val notices: List<LocalizedText> = emptyList()) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
}

/** Stateful RAM and combinational ROM rules; the caller schedules outputs after the memory delay. */
object MemoryCore {
    const val DELAY: Long = 10

    fun evaluate(
        configuration: MemoryConfiguration,
        previous: MemoryRuntimeState,
        ports: List<MemoryPortInput>,
        clear: LogicValue = LogicValue.LOW,
    ): MemoryTransition {
        require(ports.size == configuration.portCount)
        require(previous.image.addressBits == configuration.addressBits && previous.image.dataBits == configuration.dataBits)
        require(previous.clocks.size == configuration.portCount && previous.outputs.size == configuration.portCount)
        configuration.originalInterface?.let { mode ->
            return evaluateOriginal(mode, previous, ports.single(), clear)
        }
        if (configuration.originalRom) {
            return evaluateOriginalRom(previous, ports.single())
        }
        val width = configuration.dataBits
        if (!configuration.readOnly && configuration.clearPin && clear == LogicValue.HIGH) {
            val output = if (configuration.bus == MemoryBus.SHARED) LogicVector.unknown(width) else LogicVector.low(width)
            return MemoryTransition(previous.clearContents().copy(
                outputs = List(configuration.portCount) { List(configuration.dataLines) { output } }))
        }

        var image = previous.image
        // A short Evolution RAM bank can allocate a full backing page. Keep the
        // words beyond the declared depth in the simulator, outside the memory image.
        var lineOverflowWords = previous.lineOverflowWords
        var firstPageSize = previous.firstPageSize
        var rawHighBits = previous.rawHighBits
        val addresses = previous.currentAddresses.toMutableList()
        val clocks = previous.clocks.toMutableList()
        val outputs = previous.outputs.map { it.toList() }.toMutableList()
        val writeEnabled = ports.mapIndexed { index, input ->
            val sample = if (configuration.dualPort && configuration.enables == MemoryEnables.BYTE && !configuration.synchronous)
                input.writeEnable else input.clock
            val edge = triggered(previous.clocks[index], sample, configuration.trigger)
            if (configuration.hasClock || configuration.dualPort) clocks[index] = sample
            val write = when {
                configuration.readOnly -> false
                configuration.enables == MemoryEnables.LINE || configuration.dualPort -> input.writeEnable == LogicValue.HIGH && edge
                configuration.trigger == MemoryTrigger.HIGH -> input.writeEnable == LogicValue.HIGH
                configuration.trigger == MemoryTrigger.LOW -> input.writeEnable == LogicValue.LOW
                else -> input.writeEnable == LogicValue.HIGH && edge
            }
            write to edge
        }
        val notices = mutableListOf<LocalizedText>()
        if (configuration.dualPort && writeEnabled.all { it.first } &&
            ports.all { validAddress(it.address, image) != null } &&
            ports[0].address.toULongOrNull() == ports[1].address.toULongOrNull()) {
            notices += message(Messages.MemoryRuntimeDualWriteCollision)
        }

        for ((portIndex, input) in ports.withIndex()) {
            val address = validAddress(input.address, image)
            if (address != null) addresses[portIndex] = address
            val lineMode = configuration.readOnly || configuration.enables == MemoryEnables.LINE
            val misaligned = address != null && address % configuration.dataLines != 0L && !configuration.allowMisaligned
            val oldWord = image[addresses[portIndex]]
            var newWord = oldWord
            if (writeEnabled[portIndex].first && address != null && !misaligned) {
                if (lineMode) {
                    val edits = mutableMapOf<Long, ULong>()
                    repeat(configuration.dataLines) { line ->
                        if (configuration.dataLines == 1 || input.lineEnables.getOrNull(line) == LogicValue.HIGH) {
                            val lineAddress = address + line
                            val word = storedWord(input.data.getOrNull(line), image.mask)
                            if (firstPageSize == null && word != 0uL && lineAddress < EVOLUTION_PAGE_SIZE) {
                                firstPageSize = EVOLUTION_PAGE_SIZE
                            }
                            if (lineAddress < image.capacity) {
                                if (lineAddress in rawHighBits && word != image[lineAddress]) rawHighBits = rawHighBits - lineAddress
                                edits[lineAddress] = word
                            } else if (firstPageSize != null && lineAddress < firstPageSize) {
                                lineOverflowWords = if (word == 0uL) lineOverflowWords - lineAddress
                                    else lineOverflowWords + (lineAddress to word)
                            }
                        }
                    }
                    image = image.withCells(edits)
                } else {
                    val data = storedWord(input.data.firstOrNull(), image.mask)
                    if (configuration.byteEnableCount == 0) newWord = data
                    else repeat(configuration.byteEnableCount) { byte ->
                        if (input.byteEnables.getOrNull(byte) == LogicValue.HIGH) {
                            val mask = (0xffuL shl (byte * 8)) and image.mask
                            newWord = (newWord and mask.inv()) or (data and mask)
                        }
                    }
                    if (firstPageSize == null && newWord != 0uL && address < EVOLUTION_PAGE_SIZE) {
                        firstPageSize = EVOLUTION_PAGE_SIZE
                    }
                    if (address in rawHighBits && newWord != oldWord) rawHighBits = rawHighBits - address
                    image = image.withCells(mapOf(address to newWord))
                }
            }

            if (lineMode) {
                val enabled = configuration.readOnly || configuration.bus != MemoryBus.SHARED || input.outputEnable != LogicValue.LOW
                outputs[portIndex] = List(configuration.dataLines) { line ->
                    when {
                        !enabled -> LogicVector.unknown(width)
                        input.address.isError || misaligned -> LogicVector.error(width)
                        address == null -> LogicVector.unknown(width)
                        else -> {
                            val lineAddress = address + line
                            val word = if (lineAddress < image.capacity) image[lineAddress]
                                else lineOverflowWords[lineAddress] ?: 0uL
                            LogicVector.of(width, word)
                        }
                    }
                }
            } else if (input.outputEnable == LogicValue.LOW) {
                if (configuration.bus != MemoryBus.SEPARATE) outputs[portIndex] = listOf(LogicVector.unknown(width))
            } else {
                val next = when {
                    input.address.isError -> LogicVector.error(width)
                    address == null -> LogicVector.unknown(width)
                    !configuration.synchronous || configuration.asynchronousRead -> LogicVector.of(width, newWord)
                    writeEnabled[portIndex].second -> LogicVector.of(width,
                        if (configuration.readBehavior == MemoryReadBehavior.AFTER_WRITE) newWord else oldWord)
                    else -> null
                }
                if (next != null) outputs[portIndex] = listOf(next)
            }
        }
        return MemoryTransition(MemoryRuntimeState(image, clocks, outputs, addresses, lineOverflowWords, firstPageSize, rawHighBits), notices)
    }

    private fun evaluateOriginalRom(previous: MemoryRuntimeState, input: MemoryPortInput): MemoryTransition {
        val image = previous.image
        if (input.chipSelect == LogicValue.LOW) {
            return MemoryTransition(previous.copy(
                outputs = listOf(listOf(LogicVector.unknown(image.dataBits))), currentAddresses = listOf(-1L),
            ))
        }
        // The original holds the previous data value when no valid address is driven.
        val address = validAddress(input.address, image) ?: return MemoryTransition(previous)
        return MemoryTransition(previous.copy(
            outputs = listOf(listOf(LogicVector.of(image.dataBits, image[address]))), currentAddresses = listOf(address),
        ))
    }

    private fun evaluateOriginal(
        mode: OriginalRamInterface,
        previous: MemoryRuntimeState,
        input: MemoryPortInput,
        clear: LogicValue,
    ): MemoryTransition {
        val clearing = clear == LogicValue.HIGH
        var image = if (clearing) previous.image.cleared() else previous.image
        val sampled = previous.copy(image = image, clocks = listOf(input.clock))
        val floating = LogicVector.unknown(image.dataBits)
        if (input.chipSelect == LogicValue.LOW) {
            return MemoryTransition(sampled.copy(outputs = listOf(listOf(floating)), currentAddresses = listOf(-1L)))
        }
        val address = validAddress(input.address, image) ?: return MemoryTransition(sampled)
        val outputEnabled = input.outputEnable != LogicValue.LOW
        val writeEnabled = if (mode == OriginalRamInterface.SEPARATE) input.writeEnable != LogicValue.LOW else !outputEnabled
        val writeCycle = mode == OriginalRamInterface.ASYNCHRONOUS ||
            triggered(previous.clocks.single(), input.clock, MemoryTrigger.RISING)
        if (!clearing && writeEnabled && writeCycle) {
            image = image.withCells(mapOf(address to storedWord(input.data.firstOrNull(), image.mask)))
        }
        val output = if (outputEnabled) LogicVector.of(image.dataBits, image[address]) else floating
        return MemoryTransition(sampled.copy(image = image, outputs = listOf(listOf(output)), currentAddresses = listOf(address)))
    }

    fun reset(configuration: MemoryConfiguration, state: MemoryRuntimeState): MemoryRuntimeState {
        val contents = if (configuration.readOnly || configuration.nonvolatile) state else state.clearContents()
        return MemoryRuntimeState.initial(configuration, contents.image)
            .copy(lineOverflowWords = contents.lineOverflowWords, firstPageSize = contents.firstPageSize, rawHighBits = contents.rawHighBits)
    }

    private fun validAddress(value: LogicVector, image: MemoryImage): Long? =
        value.toULongOrNull()?.takeIf { it < image.capacity.toULong() }?.toLong()

    private fun storedWord(value: LogicVector?, mask: ULong): ULong = (value?.toULongOrNull() ?: ULong.MAX_VALUE) and mask

    internal fun triggered(old: LogicValue, current: LogicValue, trigger: MemoryTrigger): Boolean = when (trigger) {
        MemoryTrigger.RISING -> old == LogicValue.LOW && current == LogicValue.HIGH
        MemoryTrigger.FALLING -> old == LogicValue.HIGH && current == LogicValue.LOW
        MemoryTrigger.HIGH -> current == LogicValue.HIGH
        MemoryTrigger.LOW -> current == LogicValue.LOW
    }
}
