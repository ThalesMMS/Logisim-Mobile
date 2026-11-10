package dev.logisim.mobile.core

internal object HdlLineRamFixtures {
    private data class Sample(
        val address: Int,
        val clock: ULong,
        val words: List<ULong>?,
        val enabled: Int = 0,
        val data: ULong = 0xffuL,
        val hdlWords: List<ULong>? = words,
    )

    private val lineProfiles = listOf(1 to "single", 2 to "dual", 4 to "quad", 8 to "octo")

    fun all(): List<HdlFixtureCase> = lineProfiles.map { (lines, profile) -> aligned(lines, profile) } +
        listOf(unaligned(), narrowOcto())

    private fun aligned(lines: Int, profile: String): HdlFixtureCase {
        val falling = lines == 4
        val active = if (falling) 0uL else 1uL
        val inactive = 1uL - active
        val allLines = (1 shl lines) - 1
        val lastLine = 1 shl (lines - 1)
        val lastAddress = 16 - lines
        val empty = List(lines) { 0uL }
        val first = List(lines) { if (it == 0) 0xa5uL else 0uL }
        val firstAndLast = List(lines) { if (it == lines - 1) 0x3cuL else first[it] }
        val last = List(lines) { if (it == lines - 1) 0x3cuL else 0uL }
        val samples = buildList {
            add(Sample(0, inactive, empty))
            add(Sample(0, active, first, 1, 0xa5uL))
            add(Sample(0, active, first, allLines))
            add(Sample(lastAddress, active, empty, allLines))
            add(Sample(0, inactive, first, lastLine, 0x3cuL))
            add(Sample(0, active, firstAndLast, lastLine, 0x3cuL))
            if (lines > 1) {
                add(Sample(1, inactive, null))
                add(Sample(1, active, null, allLines))
                add(Sample(0, active, firstAndLast))
            }
            add(Sample(lastAddress, inactive, empty))
            add(Sample(lastAddress, active, last, lastLine, 0x3cuL))
            add(Sample(0, active, firstAndLast))
        }
        return fixture("ram_line_$profile", profile, lines, samples, falling = falling)
    }

    private fun unaligned() = fixture("ram_line_unaligned", "dual", 2, listOf(
        Sample(15, 0uL, listOf(0uL, 0uL)),
        Sample(15, 1uL, listOf(0xa5uL, 0uL), 1, 0xa5uL),
        Sample(15, 0uL, listOf(0xa5uL, 0uL)),
        Sample(15, 1uL, listOf(0xa5uL, 0x3cuL), 2, 0x3cuL, hdlWords = listOf(0xa5uL, 0uL)),
        Sample(0, 1uL, listOf(0uL, 0uL)),
        Sample(14, 1uL, listOf(0uL, 0xa5uL)),
        Sample(1, 0uL, listOf(0uL, 0uL)),
        Sample(1, 1uL, listOf(0uL, 0x3cuL), 2, 0x3cuL),
        Sample(2, 1uL, listOf(0x3cuL, 0uL)),
    ), allowMisaligned = true)

    private fun narrowOcto(): HdlFixtureCase {
        val empty = List(8) { 0uL }
        val written = List(8) { if (it == 3) 0x3cuL else 0uL }
        return fixture("ram_line_octo_shallow", "octo", 8, listOf(
            Sample(0, 0uL, empty),
            Sample(0, 1uL, written, 1 shl 3, 0x3cuL),
            Sample(0, 0uL, written),
            Sample(0, 1uL, written.toMutableList().also { it[7] = 0xffuL }, 1 shl 7, hdlWords = written),
        ), addressBits = 2)
    }

    private fun fixture(
        id: String,
        profile: String,
        lines: Int,
        samples: List<Sample>,
        addressBits: Int = 4,
        falling: Boolean = false,
        allowMisaligned: Boolean = false,
    ): HdlFixtureCase {
        val ram = CircuitComponent("ram", ComponentKind.RAM, GridPoint(300, 200), attributes = mapOf(
            "addrWidth" to addressBits.toString(),
            "appearance" to "classic",
            "enables" to "line",
            "line" to profile,
            "trigger" to if (falling) "falling" else "rising",
            "misaligned" to allowMisaligned.toString(),
        ))
        val layout = ram.layout()
        val pins = layout.inputs.map { port ->
            CircuitComponent(port.name, ComponentKind.INPUT, port.position, attributes = mapOf("width" to port.width.toString()))
        }
        val outputs = layout.outputs.mapIndexed { line, port ->
            CircuitComponent("q$line", ComponentKind.OUTPUT, port.position, attributes = mapOf("width" to "8"))
        }
        val circuit = CircuitDocument(id, listOf(ram) + pins + outputs)
        val vectors = samples.map { sample ->
            val inputs = buildMap {
                put(MemoryLayouts.addressName(0), sample.address.toULong())
                put(MemoryLayouts.writeEnableName(0), if (sample.enabled != 0) 1uL else 0uL)
                repeat(lines) { line ->
                    val enabled = sample.enabled and (1 shl line) != 0
                    put(MemoryLayouts.inputName(0, line), if (enabled) sample.data else 0xffuL)
                    if (lines > 1) put(MemoryLayouts.lineEnableName(0, line), if (enabled) 1uL else 0uL)
                }
                put(MemoryLayouts.clockName(0), sample.clock)
            }
            val words = sample.words
            if (words == null) HdlVectorCase(inputs, emptyMap(), errorOutputs = outputs.map { it.id }.toSet())
            else HdlVectorCase(inputs, outputs.zip(words).associate { (port, word) -> port.id to word },
                outputs.zip(requireNotNull(sample.hdlWords)).associate { (port, word) -> port.id to word })
        }
        return HdlFixtureCase(id, CircuitProject(listOf(circuit)), vectors)
    }
}
