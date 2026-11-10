package dev.logisim.mobile.core

internal data class HdlVectorCase(
    val inputs: Map<String, ULong>,
    val outputs: Map<String, ULong>,
    val hdlOutputs: Map<String, ULong> = outputs,
    val errorOutputs: Set<String> = emptySet(),
)
internal data class HdlFixtureCase(val id: String, val project: CircuitProject, val vectors: List<HdlVectorCase>)

internal object HdlFixtureCases {
    fun all(): List<HdlFixtureCase> = listOf(andBus(), wideAdder(), splitterBus(), rom(), flipFlop(), register(), counter(), hierarchy(), xorOneHot(), comparator(), multiplier(false), multiplier(true), shifter("ar"), shifter("rl"), shifter("rr"), subtractor(), mux(), demux(), decoder(), bitSelector(), priorityEncoder(), counterMaskedLoad(), originalCounter(), constant(), wideMultiplier(false), wideMultiplier(true), wideCounter(), originalMultiplier(), ttl7400()) + ttlBasicGates() + originalCounterSignedLoads() +
        MemoryReadBehavior.entries.flatMap { listOf(ram(it), ram(it, falling = true)) } + arithmeticAndStorage() +
        HdlLineRamFixtures.all()

    private fun arithmeticAndStorage(): List<HdlFixtureCase> =
        listOf(1, 5).flatMap { width -> listOf("low1", "high1", "low0", "high0").map { bitFinder(width, it) } } +
            listOf(exponentiator(false), exponentiator(true), squareRoot(1), squareRoot(64),
                shiftRegister(parallel = true, classic = true), shiftRegister(parallel = true, classic = false),
                shiftRegister(parallel = false, classic = true),
                random(42, 64, 0xd0764d4f4476689fuL, 0x519e4174576f3791uL, 0xfbe07cfb0c24ed8cuL),
                random(0, 8, 0x3auL, 0x25uL, 0xacuL)) +
            MemoryReadBehavior.entries.flatMap { listOf(byteMaskedRam(it), byteMaskedRam(it, falling = true)) }

    private fun componentFixture(
        id: String,
        component: CircuitComponent,
        vectors: List<Pair<List<ULong>, List<ULong>>>,
    ): HdlFixtureCase {
        val layout = component.layout()
        val inputs = layout.inputs.mapIndexed { index, port ->
            CircuitComponent("i$index", ComponentKind.INPUT, port.position, "input_$index", mapOf("width" to port.width.toString()))
        }
        val outputs = layout.outputs.mapIndexed { index, port ->
            CircuitComponent("o$index", ComponentKind.OUTPUT, port.position, "output_$index", mapOf("width" to port.width.toString()))
        }
        val document = CircuitDocument(id, listOf(component) + inputs + outputs)
        return HdlFixtureCase(id, CircuitProject(listOf(document)), vectors.map { (input, output) ->
            HdlVectorCase(inputs.mapIndexed { index, pin -> pin.id to input[index] }.toMap(),
                outputs.mapIndexed { index, pin -> pin.id to output[index] }.toMap())
        })
    }

    fun andBus(): HdlFixtureCase = componentFixture("and_bus",
        CircuitComponent("gate", ComponentKind.AND, GridPoint(300, 200), attributes = mapOf("width" to "8")),
        listOf(0uL, 1uL, 127uL, 128uL, 170uL, 255uL).flatMap { a ->
            listOf(0uL, 1uL, 85uL, 255uL).map { b -> listOf(a, b) to listOf(a and b) }
        })

    fun ttl7400(): HdlFixtureCase = componentFixture("ttl_7400",
        CircuitComponent("chip", ComponentKind("TTL_7400"), GridPoint(300, 200)),
        listOf(
            listOf(0uL, 0uL, 0uL, 1uL, 1uL, 0uL, 1uL, 1uL) to listOf(1uL, 1uL, 1uL, 0uL),
            listOf(0uL, 1uL, 1uL, 0uL, 1uL, 1uL, 0uL, 0uL) to listOf(1uL, 1uL, 0uL, 1uL),
            listOf(1uL, 0uL, 1uL, 1uL, 0uL, 0uL, 0uL, 1uL) to listOf(1uL, 0uL, 1uL, 1uL),
            listOf(1uL, 1uL, 0uL, 0uL, 0uL, 1uL, 1uL, 0uL) to listOf(0uL, 1uL, 1uL, 1uL),
        ))

    fun ttlBasicGates(): List<HdlFixtureCase> = listOf(
        ttlBinaryGate("7402", listOf(1uL, 0uL, 0uL, 0uL)),
        ttlBinaryGate("7408", listOf(0uL, 0uL, 0uL, 1uL)),
        ttlBinaryGate("7432", listOf(0uL, 1uL, 1uL, 1uL)),
        ttlBinaryGate("7486", listOf(0uL, 1uL, 1uL, 0uL)),
        componentFixture("ttl_7404", CircuitComponent("chip", ComponentKind("TTL_7404"), GridPoint(300, 200)),
            (0..6).map { highPin ->
                List(6) { if (it == highPin) 1uL else 0uL } to List(6) { if (it == highPin) 0uL else 1uL }
            }),
    )

    private fun ttlBinaryGate(model: String, truthTable: List<ULong>): HdlFixtureCase {
        val inputs = listOf(listOf(0uL, 0uL), listOf(0uL, 1uL), listOf(1uL, 0uL), listOf(1uL, 1uL))
        val vectors = (0..3).flatMap { drivenGate ->
            inputs.indices.map { row ->
                (0..3).flatMap { gate -> inputs[if (gate == drivenGate) row else 0] } to
                    List(4) { gate -> truthTable[if (gate == drivenGate) row else 0] }
            }
        }
        return componentFixture("ttl_$model",
            CircuitComponent("chip", ComponentKind("TTL_$model"), GridPoint(300, 200)), vectors)
    }

    fun wideAdder(): HdlFixtureCase = componentFixture("wide_adder",
        CircuitComponent("adder", ComponentKind.ADDER, GridPoint(300, 200), attributes = mapOf("width" to "64")),
        listOf(0uL, 1uL, 0x7fffffffffffffffuL, 0x8000000000000000uL, ULong.MAX_VALUE).flatMap { a ->
            listOf(0uL, 1uL, ULong.MAX_VALUE).flatMap { b -> (0uL..1uL).map { carry ->
                val first = a + b
                val sum = first + carry
                listOf(a, b, carry) to listOf(sum, if (first < a || sum < first) 1uL else 0uL)
            } }
        })

    fun splitterBus(): HdlFixtureCase {
        val split = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(300, 200),
            attributes = mapOf("incoming" to "8", "fanout" to "2"))
        val branches = split.splitterPorts()
        val document = CircuitDocument("splitter_bus", listOf(split,
            CircuitComponent("a", ComponentKind.INPUT, split.position, "input", mapOf("width" to "8")),
            CircuitComponent("lo", ComponentKind.OUTPUT, branches[1], "low", mapOf("width" to "4")),
            CircuitComponent("hi", ComponentKind.OUTPUT, branches[2], "high", mapOf("width" to "4"))))
        return HdlFixtureCase("splitter_bus", CircuitProject(listOf(document)), (0..255).map {
            HdlVectorCase(mapOf("a" to it.toULong()), mapOf("lo" to (it and 15).toULong(), "hi" to (it shr 4).toULong()))
        })
    }

    fun rom(): HdlFixtureCase = componentFixture("rom_read",
        CircuitComponent("memory", ComponentKind.ROM, GridPoint(300, 200), attributes = mapOf(
            "appearance" to "classic", "addrWidth" to "4", "dataWidth" to "8",
            "contents" to "addr/data: 4 8\n0f 1e 1d 2c e0 f0 4*00 01 04 03 02")),
        listOf(15, 30, 29, 44, 224, 240, 0, 0, 0, 0, 1, 4, 3, 2, 0, 0).mapIndexed { address, word ->
            listOf(address.toULong()) to listOf(word.toULong())
        })

    private fun bitFinder(width: Int, mode: String): HdlFixtureCase {
        val allBits = (1uL shl width) - 1uL
        val singleMatch = if (mode.endsWith('1')) 1uL else allBits - 1uL
        val noMatch = if (mode.endsWith('1')) 0uL else allBits
        val twoMatches = if (mode.endsWith('1')) 0x11uL else allBits xor 0x11uL
        val frames = listOf(listOf(noMatch) to listOf(0uL, 0uL), listOf(singleMatch) to listOf(1uL, 0uL))
        val vectors = if (width == 1) frames else frames + (listOf(twoMatches) to listOf(1uL, if (mode.startsWith("high")) 4uL else 0uL))
        return componentFixture("finder_${mode}_$width",
            CircuitComponent("finder", ComponentKind("BIT_FINDER"), GridPoint(300, 200),
                attributes = mapOf("width" to "$width", "type" to mode)), vectors)
    }

    private fun exponentiator(signed: Boolean): HdlFixtureCase {
        val frames = if (signed) listOf(
            listOf(0xduL, 3uL) to listOf(5uL, 0xeuL),
            listOf(0xduL, 0xfuL) to listOf(0uL, 0uL),
            listOf(1uL, 0xfuL) to listOf(1uL, 0uL),
            listOf(0uL, 0uL) to listOf(1uL, 0uL),
        ) else listOf(
            listOf(0xfuL, 3uL) to listOf(0xfuL, 2uL),
            listOf(5uL, 4uL) to listOf(1uL, 7uL),
            listOf(3uL, 8uL) to listOf(1uL, 0xauL),
            listOf(0uL, 0uL) to listOf(1uL, 0uL),
        )
        return componentFixture("exponentiator_${if (signed) "signed" else "unsigned"}",
            CircuitComponent("power", ComponentKind("EXPONENTIATOR"), GridPoint(300, 200),
                attributes = mapOf("width" to "4", "mode" to if (signed) "twosComplement" else "unsigned")), frames)
    }

    private fun squareRoot(width: Int): HdlFixtureCase {
        val vectors = if (width == 1) listOf(
            listOf(0uL, 0uL) to listOf(0uL, 0uL),
            listOf(1uL, 0uL) to listOf(1uL, 0uL),
            listOf(0uL, 1uL) to listOf(1uL, 1uL),
            listOf(1uL, 1uL) to listOf(1uL, 0uL),
        ) else listOf(
            listOf(16uL, 0uL) to listOf(4uL, 0uL),
            listOf(0uL, 1uL) to listOf(0x100000000uL, 0uL),
            listOf(ULong.MAX_VALUE, ULong.MAX_VALUE) to listOf(ULong.MAX_VALUE, 0xfffffffffffffffeuL),
        )
        return componentFixture("square_root_$width",
            CircuitComponent("root", ComponentKind("SQUARE_ROOT"), GridPoint(300, 200),
                attributes = mapOf("width" to "$width")), vectors)
    }

    private fun shiftRegister(parallel: Boolean, classic: Boolean): HdlFixtureCase {
        val kind = SequentialLibrary.ShiftRegister
        val component = CircuitComponent("shift", kind, GridPoint(300, 200), attributes = mapOf(
            "width" to if (parallel) "4" else "1", "length" to if (parallel) "3" else "2",
            "parallel" to "$parallel", "appearance" to if (classic) "classic" else "evolution",
            "trigger" to if (classic) "rising" else "falling",
        ))
        val active = if (classic) 1uL else 0uL
        val idle = 1uL - active
        fun frame(serial: ULong, shift: ULong, clock: ULong, clear: ULong, load: ULong, expected: List<ULong>) =
            (listOf(serial, shift, clock, clear) + if (parallel) listOf(load, 2uL, 4uL, 8uL) else emptyList()) to expected
        val vectors = if (parallel) {
            fun outputs(first: ULong, middle: ULong, last: ULong) =
                listOf(last, first, middle) + if (classic) listOf(last) else emptyList()
            listOf(
                frame(0uL, 0uL, idle, 1uL, 0uL, outputs(0uL, 0uL, 0uL)),
                frame(0uL, 0uL, active, 0uL, 1uL, outputs(2uL, 4uL, 8uL)),
                frame(1uL, 1uL, idle, 0uL, 0uL, outputs(2uL, 4uL, 8uL)),
                frame(1uL, 1uL, active, 0uL, 0uL, outputs(1uL, 2uL, 4uL)),
                frame(7uL, 0uL, idle, 0uL, 0uL, outputs(1uL, 2uL, 4uL)),
                frame(7uL, 0uL, active, 0uL, 0uL, outputs(1uL, 2uL, 4uL)),
                frame(0uL, 0uL, active, 1uL, 0uL, outputs(0uL, 0uL, 0uL)),
            )
        } else listOf(
            frame(0uL, 0uL, idle, 1uL, 0uL, listOf(0uL)),
            frame(1uL, 1uL, active, 0uL, 0uL, listOf(0uL)),
            frame(0uL, 1uL, idle, 0uL, 0uL, listOf(0uL)),
            frame(0uL, 1uL, active, 0uL, 0uL, listOf(1uL)),
        )
        return componentFixture("shift_register_${if (parallel) "parallel" else "serial"}_${if (classic) "classic" else "evolution"}", component, vectors)
    }

    private fun random(seed: Int, width: Int, initial: ULong, next: ULong, second: ULong): HdlFixtureCase {
        val component = CircuitComponent("random", SequentialLibrary.Random, GridPoint(300, 200),
            attributes = mapOf("width" to "$width", "seed" to "$seed"))
        val layout = component.layout()
        val inputs = layout.inputs.map { port -> CircuitComponent(port.name, ComponentKind.INPUT, port.position) }
        val output = CircuitComponent("q", ComponentKind.OUTPUT, layout.outputs.single().position, attributes = mapOf("width" to "$width"))
        fun frame(clock: ULong, enabled: ULong, reset: ULong, expected: ULong? = null) =
            HdlVectorCase(mapOf("clock" to clock, "enable" to enabled, "reset" to reset), expected?.let { mapOf("q" to it) }.orEmpty())
        val id = if (seed == 0) "random_default" else "random_seeded"
        return HdlFixtureCase(id, CircuitProject(listOf(CircuitDocument(id, listOf(component, output) + inputs))), listOf(
            frame(0uL, 0uL, 0uL),
            frame(1uL, 0uL, 0uL, initial),
            frame(0uL, 0uL, 0uL, initial),
            frame(1uL, 0uL, 0uL, initial),
            frame(0uL, 0uL, 0uL, initial),
            frame(1uL, 0uL, 0uL, initial),
            frame(0uL, 1uL, 0uL, initial),
            frame(1uL, 1uL, 0uL, next),
            frame(0uL, 1uL, 0uL, next),
            frame(1uL, 1uL, 0uL, second),
            frame(0uL, 0uL, 0uL, second),
            frame(1uL, 0uL, 0uL, second),
        ) + if (seed == 0) emptyList() else listOf(
            frame(0uL, 0uL, 0uL, second),
            frame(1uL, 0uL, 1uL, initial),
        ))
    }

    fun ram(readBehavior: MemoryReadBehavior, falling: Boolean = false): HdlFixtureCase {
        val id = if (falling) "ram_falling_${readBehavior.serialized}" else "ram_${readBehavior.serialized}"
        val ram = CircuitComponent("ram", ComponentKind.RAM, GridPoint(300, 200), attributes = mapOf(
            "addrWidth" to "4", "readbehav" to readBehavior.serialized,
            "trigger" to if (falling) "falling" else "rising",
        ))
        val layout = ram.layout()
        val address = MemoryLayouts.addressName(0)
        val data = MemoryLayouts.inputName(0, 0)
        val write = MemoryLayouts.writeEnableName(0)
        val clock = MemoryLayouts.clockName(0)
        val pins = layout.inputs.filter { it.name in listOf(address, data, write, clock) }.map { port ->
            CircuitComponent(port.name, ComponentKind.INPUT, port.position, attributes = mapOf("width" to port.width.toString()))
        }
        val output = CircuitComponent("q", ComponentKind.OUTPUT, layout.outputs.single().position, attributes = mapOf("width" to "8"))
        fun frame(a: ULong, d: ULong, we: ULong, clk: ULong, q: ULong? = null) =
            HdlVectorCase(mapOf(address to a, data to d, write to we, clock to if (falling) 1uL - clk else clk),
                q?.let { mapOf("q" to it) }.orEmpty())
        val firstWrite = if (readBehavior == MemoryReadBehavior.AFTER_WRITE) 0xa5uL else 0uL
        val secondWrite = if (readBehavior == MemoryReadBehavior.AFTER_WRITE) 0x3cuL else 0uL
        return HdlFixtureCase(id, CircuitProject(listOf(CircuitDocument(id, listOf(ram, output) + pins))), listOf(
            frame(0uL, 0uL, 0uL, 0uL),
            frame(1uL, 0xa5uL, 1uL, 1uL, firstWrite),
            frame(2uL, 0xffuL, 1uL, 1uL, firstWrite),
            frame(2uL, 0xffuL, 1uL, 0uL, firstWrite),
            frame(2uL, 0x3cuL, 1uL, 1uL, secondWrite),
            frame(1uL, 0xffuL, 0uL, 0uL, secondWrite),
            frame(1uL, 0xffuL, 0uL, 1uL, 0xa5uL),
            frame(2uL, 0uL, 0uL, 0uL, 0xa5uL),
            frame(2uL, 0uL, 0uL, 1uL, 0x3cuL),
            frame(3uL, 0uL, 0uL, 0uL, 0x3cuL),
            frame(3uL, 0uL, 0uL, 1uL, 0uL),
        ))
    }

    private fun byteMaskedRam(readBehavior: MemoryReadBehavior, falling: Boolean = false): HdlFixtureCase {
        val id = if (falling) "ram_masked_falling_${readBehavior.serialized}" else "ram_masked_${readBehavior.serialized}"
        val ram = CircuitComponent("ram", ComponentKind.RAM, GridPoint(300, 200), attributes = mapOf(
            "addrWidth" to "4", "dataWidth" to "12", "readbehav" to readBehavior.serialized, "byteenables" to "byteEnables",
            "trigger" to if (falling) "falling" else "rising",
        ))
        val layout = ram.layout()
        val inputs = layout.inputs.map { port ->
            CircuitComponent(port.name, ComponentKind.INPUT, port.position, attributes = mapOf("width" to port.width.toString()))
        }
        val dataOut = layout.outputs.single()
        val output = CircuitComponent("q", ComponentKind.OUTPUT, dataOut.position, attributes = mapOf("width" to dataOut.width.toString()))
        val vectors = mutableListOf<HdlVectorCase>()
        val raw = readBehavior == MemoryReadBehavior.AFTER_WRITE
        val activeClock = if (falling) 0uL else 1uL
        fun cycle(address: Int, data: Int, write: Boolean, mask: Int, outputEnable: Boolean, runtime: Int, rtl: Int = runtime) {
            val clock = MemoryLayouts.clockName(0)
            val controls = mapOf(
                MemoryLayouts.addressName(0) to address.toULong(),
                MemoryLayouts.inputName(0, 0) to data.toULong(),
                MemoryLayouts.writeEnableName(0) to if (write) 1uL else 0uL,
                MemoryLayouts.outputEnableName(0) to if (outputEnable) 1uL else 0uL,
                MemoryLayouts.byteEnableName(0, 0) to (mask and 1).toULong(),
                MemoryLayouts.byteEnableName(0, 1) to ((mask shr 1) and 1).toULong(),
            )
            val previous = vectors.lastOrNull()
            vectors += HdlVectorCase(controls + (clock to (1uL - activeClock)), previous?.outputs.orEmpty(), previous?.hdlOutputs.orEmpty())
            vectors += HdlVectorCase(controls + (clock to activeClock), mapOf("q" to runtime.toULong()), mapOf("q" to rtl.toULong()))
        }

        cycle(2, 0xa5c, true, 3, true, if (raw) 0xa5c else 0)
        cycle(2, 0xf3d, true, 1, true, if (raw) 0xa3d else 0xa5c, if (raw) 0xa3d else 0x05c)
        cycle(2, 0x123, true, 2, true, if (raw) 0x13d else 0xa3d, if (raw) 0x13d else 0xa5c)
        cycle(2, 0, false, 3, true, 0x13d)
        // The circuit simulator reads the full word even when the RTL holds disabled output bytes.
        cycle(3, 0xfff, true, 0, true, 0, 0x13d)
        cycle(3, 0, false, 3, false, 0, 0x13d)
        cycle(3, 0, false, 3, true, 0)
        cycle(2, 0, false, 3, true, 0x13d)
        cycle(3, 0xbcd, true, 3, false, 0x13d)
        cycle(3, 0, false, 1, true, 0xbcd, 0x1cd)
        cycle(3, 0, false, 3, true, 0xbcd)
        return HdlFixtureCase(id, CircuitProject(listOf(CircuitDocument(id, listOf(ram, output) + inputs))), vectors)
    }

    fun flipFlop(): HdlFixtureCase = componentFixture("d_flip_flop",
        CircuitComponent("ff", ComponentKind.D_FLIP_FLOP, GridPoint(300, 200), attributes = mapOf("appearance" to "classic")),
        listOf(
            listOf(0uL, 0uL, 1uL, 0uL) to listOf(0uL, 1uL),
            listOf(1uL, 0uL, 0uL, 0uL) to listOf(0uL, 1uL),
            listOf(1uL, 1uL, 0uL, 0uL) to listOf(1uL, 0uL),
            listOf(0uL, 1uL, 0uL, 0uL) to listOf(1uL, 0uL),
            listOf(0uL, 0uL, 0uL, 0uL) to listOf(1uL, 0uL),
            listOf(0uL, 1uL, 0uL, 0uL) to listOf(0uL, 1uL),
            listOf(0uL, 0uL, 0uL, 1uL) to listOf(1uL, 0uL),
            listOf(0uL, 0uL, 1uL, 1uL) to listOf(0uL, 1uL),
        ))

    fun register(): HdlFixtureCase = componentFixture("falling_register",
        CircuitComponent("reg", ComponentKind.REGISTER, GridPoint(300, 200), attributes = mapOf(
            "appearance" to "classic", "width" to "8", "trigger" to "falling")),
        listOf(
            listOf(0uL, 0uL, 1uL, 1uL) to listOf(0uL),
            listOf(0x5auL, 1uL, 1uL, 0uL) to listOf(0uL),
            listOf(0x5auL, 0uL, 1uL, 0uL) to listOf(0x5auL),
            listOf(0xa5uL, 1uL, 0uL, 0uL) to listOf(0x5auL),
            listOf(0xa5uL, 0uL, 0uL, 0uL) to listOf(0x5auL),
            listOf(0x3cuL, 1uL, 1uL, 0uL) to listOf(0x5auL),
            listOf(0x3cuL, 0uL, 1uL, 0uL) to listOf(0x3cuL),
            listOf(0uL, 0uL, 1uL, 1uL) to listOf(0uL),
        ))

    fun counter(): HdlFixtureCase {
        val vectors = mutableListOf<Pair<List<ULong>, List<ULong>>>()
        vectors += listOf(0uL, 0uL, 0uL, 1uL, 1uL, 1uL) to listOf(0uL, 0uL)
        var q = 0uL
        repeat(12) {
            vectors += listOf(0uL, 0uL, 0uL, 0uL, 1uL, 1uL) to listOf(q, if (q == 9uL) 1uL else 0uL)
            q = if (q == 9uL) 0uL else q + 1uL
            vectors += listOf(0uL, 1uL, 0uL, 0uL, 1uL, 1uL) to listOf(q, if (q == 9uL) 1uL else 0uL)
        }
        vectors += listOf(7uL, 0uL, 1uL, 0uL, 0uL, 0uL) to listOf(q, 0uL)
        vectors += listOf(7uL, 1uL, 1uL, 0uL, 0uL, 0uL) to listOf(7uL, 0uL)
        vectors += listOf(0uL, 0uL, 0uL, 0uL, 1uL, 0uL) to listOf(7uL, 0uL)
        vectors += listOf(0uL, 1uL, 0uL, 0uL, 1uL, 0uL) to listOf(6uL, 0uL)
        return componentFixture("counter_wrap", CircuitComponent("count", ComponentKind.COUNTER,
            GridPoint(300, 200), attributes = mapOf("appearance" to "classic", "width" to "4", "max" to "0x9")), vectors)
    }

    fun originalCounter(): HdlFixtureCase {
        val fixture = componentFixture("original_counter_controls", originalCounterComponent(4, 15uL),
            listOf(
                listOf(5uL, 0uL, 0uL, 1uL, 0uL) to listOf(0uL, 0uL),
                listOf(5uL, 0uL, 0uL, 0uL, 0uL) to listOf(0uL, 0uL),
                listOf(5uL, 1uL, 0uL, 0uL, 0uL) to listOf(0uL, 0uL),
                listOf(5uL, 0uL, 0uL, 0uL, 1uL) to listOf(0uL, 0uL),
                listOf(5uL, 1uL, 0uL, 0uL, 1uL) to listOf(1uL, 0uL),
                listOf(5uL, 0uL, 1uL, 0uL, 0uL) to listOf(1uL, 0uL),
                listOf(5uL, 1uL, 1uL, 0uL, 0uL) to listOf(5uL, 0uL),
                listOf(5uL, 0uL, 0uL, 0uL, 0uL) to listOf(5uL, 0uL),
                listOf(5uL, 1uL, 0uL, 0uL, 0uL) to listOf(5uL, 0uL),
                listOf(5uL, 0uL, 1uL, 0uL, 1uL) to listOf(5uL, 0uL),
                listOf(5uL, 1uL, 1uL, 0uL, 1uL) to listOf(4uL, 0uL),
                listOf(5uL, 0uL, 1uL, 1uL, 1uL) to listOf(0uL, 0uL),
                listOf(5uL, 0uL, 1uL, 0uL, 1uL) to listOf(0uL, 1uL),
                listOf(5uL, 1uL, 1uL, 0uL, 1uL) to listOf(15uL, 0uL),
                listOf(5uL, 0uL, 0uL, 0uL, 1uL) to listOf(15uL, 1uL),
                listOf(5uL, 1uL, 0uL, 0uL, 1uL) to listOf(0uL, 0uL),
            ))
        return fixture.copy(project = fixture.project.copy(sourceVersion = "2.7.1"))
    }

    fun originalCounterSignedLoads(): List<HdlFixtureCase> = listOf(
        Triple(0x80000000uL, 1uL, 0uL),
        Triple(0x7fffffffuL, 0x80000000uL, 0x80000000uL),
    ).mapIndexed { index, (maximum, data, expected) ->
        val fixture = componentFixture("original_counter_signed_load_$index", originalCounterComponent(32, maximum),
            listOf(
                listOf(data, 0uL, 1uL, 1uL, 0uL) to listOf(0uL, 0uL),
                listOf(data, 0uL, 1uL, 0uL, 0uL) to listOf(0uL, 0uL),
                listOf(data, 1uL, 1uL, 0uL, 0uL) to listOf(expected, 0uL),
            ))
        fixture.copy(project = fixture.project.copy(sourceVersion = "2.7.1"))
    }

    private fun originalCounterComponent(width: Int, maximum: ULong): CircuitComponent {
        val imported = CircCodec.readProject("""
            <project source="2.7.1"><lib name="4" desc="#Memory"/>
              <circuit name="main"><comp lib="4" name="Counter" loc="(300,200)">
                <a name="width" val="$width"/><a name="max" val="0x${maximum.toString(16)}"/>
              </comp></circuit>
            </project>
        """.trimIndent()) as CircProjectImportResult.Success
        return imported.project.mainCircuit.components.single()
    }

    fun xorOneHot(): HdlFixtureCase = componentFixture("xor_one_hot",
        CircuitComponent("xor", ComponentKind.XOR, GridPoint(300, 200),
            attributes = mapOf("width" to "4", "inputs" to "3", "negate1" to "true")),
        listOf(0uL, 3uL, 5uL, 15uL).flatMap { a -> listOf(0uL, 7uL, 15uL).flatMap { rawB ->
            listOf(0uL, 10uL, 15uL).map { c ->
                val b = rawB xor 15uL
                listOf(a, rawB, c) to listOf(((a and b.inv() and c.inv()) or (a.inv() and b and c.inv()) or (a.inv() and b.inv() and c)) and 15uL)
            }
        } })

    fun comparator(): HdlFixtureCase = componentFixture("signed_comparator",
        CircuitComponent("compare", ComponentKind("COMPARATOR"), GridPoint(300, 200), attributes = mapOf("width" to "8")),
        listOf(-128, -1, 0, 1, 127).flatMap { a -> listOf(-128, -1, 0, 1, 127).map { b ->
            listOf((a and 255).toULong(), (b and 255).toULong()) to
                listOf(if (a > b) 1uL else 0uL, if (a == b) 1uL else 0uL, if (a < b) 1uL else 0uL)
        } })

    fun multiplier(signed: Boolean): HdlFixtureCase = componentFixture(if (signed) "signed_multiplier" else "unsigned_multiplier",
        CircuitComponent("multiply", ComponentKind("MULTIPLIER"), GridPoint(300, 200), attributes = mapOf(
            "width" to "8", "mode" to if (signed) "twosComplement" else "unsigned")),
        listOf(0, 1, 127, 128, 255).flatMap { rawA -> listOf(0, 1, 2, 128, 255).flatMap { rawB ->
            listOf(0, 1, 255).map { rawCarry ->
                fun number(raw: Int) = if (signed && raw >= 128) raw - 256 else raw
                val product = number(rawA) * number(rawB) + number(rawCarry)
                listOf(rawA.toULong(), rawB.toULong(), rawCarry.toULong()) to
                    listOf((product and 255).toULong(), ((product shr 8) and 255).toULong())
            }
        } })

    fun originalMultiplier(): HdlFixtureCase {
        val project = (CircCodec.readProject("""
            <project source="2.7.1">
              <lib name="0" desc="#Arithmetic"/>
              <circuit name="main">
                <comp lib="0" loc="(300,200)" name="Multiplier"><a name="width" val="32"/></comp>
              </circuit>
            </project>
        """.trimIndent()) as CircProjectImportResult.Success).project
        val fixture = componentFixture("original_multiplier", project.mainCircuit.components.single(), listOf(
            listOf(0x80000000uL, 2uL, 0uL) to listOf(0uL, 0xffffffffuL),
            listOf(2uL, 0x80000000uL, 0uL) to listOf(0uL, 0xffffffffuL),
            listOf(0uL, 0uL, 0xffffffffuL) to listOf(0xffffffffuL, 0xffffffffuL),
        ))
        return fixture.copy(project = fixture.project.copy(sourceVersion = project.sourceVersion))
    }

    fun shifter(mode: String): HdlFixtureCase = componentFixture("shift_$mode",
        CircuitComponent("shift", ComponentKind("SHIFTER"), GridPoint(300, 200), attributes = mapOf("width" to "8", "shift" to mode)),
        listOf(0, 1, 127, 128, 255).flatMap { raw -> (0..7).map { distance ->
            val value = when (mode) {
                "ar" -> (if (raw >= 128) raw - 256 else raw) shr distance
                "rl" -> (raw shl distance) or (raw shr ((8 - distance) % 8))
                else -> (raw shr distance) or (raw shl ((8 - distance) % 8))
            }
            listOf(raw.toULong(), distance.toULong()) to listOf((value and 255).toULong())
        } })

    fun subtractor(): HdlFixtureCase = componentFixture("subtractor_borrow",
        CircuitComponent("subtract", ComponentKind.SUBTRACTOR, GridPoint(300, 200), attributes = mapOf("width" to "8")),
        listOf(0, 1, 127, 128, 255).flatMap { a -> listOf(0, 1, 128, 255).flatMap { b -> (0..1).map { borrow ->
            val difference = a - b - borrow
            listOf(a.toULong(), b.toULong(), borrow.toULong()) to listOf((difference and 255).toULong(), if (difference < 0) 1uL else 0uL)
        } } })

    fun wideMultiplier(signed: Boolean): HdlFixtureCase = componentFixture(if (signed) "signed_multiplier_64" else "unsigned_multiplier_64",
        CircuitComponent("multiply", ComponentKind("MULTIPLIER"), GridPoint(300, 200), attributes = mapOf(
            "width" to "64", "mode" to if (signed) "twosComplement" else "unsigned")),
        if (signed) listOf(
            listOf(ULong.MAX_VALUE, 1uL, 0uL) to listOf(ULong.MAX_VALUE, ULong.MAX_VALUE),
            listOf(ULong.MAX_VALUE, ULong.MAX_VALUE, 0uL) to listOf(1uL, 0uL),
            listOf(0x8000000000000000uL, 0x8000000000000000uL, 0uL) to listOf(0uL, 0x4000000000000000uL),
            listOf(0uL, 0uL, ULong.MAX_VALUE) to listOf(ULong.MAX_VALUE, ULong.MAX_VALUE),
        ) else listOf(
            listOf(ULong.MAX_VALUE, ULong.MAX_VALUE, 0uL) to listOf(1uL, ULong.MAX_VALUE - 1uL),
            listOf(0x8000000000000000uL, 2uL, 0uL) to listOf(0uL, 1uL),
            listOf(0uL, 0uL, ULong.MAX_VALUE) to listOf(ULong.MAX_VALUE, 0uL),
        ))

    fun wideCounter(): HdlFixtureCase = componentFixture("counter_default_64",
        CircuitComponent("counter", ComponentKind.COUNTER, GridPoint(300, 200),
            attributes = mapOf("width" to "64", "max" to "0xffffffffffffffff")),
        listOf(
            listOf(0uL, 0uL, 0uL, 1uL, 1uL, 1uL) to listOf(0uL, 0uL),
            listOf(ULong.MAX_VALUE, 0uL, 1uL, 0uL, 0uL, 1uL) to listOf(0uL, 0uL),
            listOf(ULong.MAX_VALUE, 1uL, 1uL, 0uL, 0uL, 1uL) to listOf(ULong.MAX_VALUE, 1uL),
            listOf(0uL, 0uL, 0uL, 0uL, 1uL, 1uL) to listOf(ULong.MAX_VALUE, 1uL),
            listOf(0uL, 1uL, 0uL, 0uL, 1uL, 1uL) to listOf(0uL, 0uL),
        ))

    private fun namedFixture(id: String, component: CircuitComponent, frames: List<Pair<Map<String, ULong>, List<ULong>>>): HdlFixtureCase =
        componentFixture(id, component, frames.map { (inputs, output) -> component.layout().inputs.map { inputs.getValue(it.name) } to output })

    fun mux(): HdlFixtureCase = namedFixture("multiplexer_enabled",
        CircuitComponent("mux", ComponentKind.MULTIPLEXER, GridPoint(300, 200), attributes = mapOf("width" to "4", "select" to "2", "enable" to "true")),
        (0..3).flatMap { select -> (0..1).map { enabled ->
            mapOf("in0" to 1uL, "in1" to 3uL, "in2" to 7uL, "in3" to 15uL, "select" to select.toULong(), "enable" to enabled.toULong()) to
                listOf(if (enabled == 0) 0uL else listOf(1uL, 3uL, 7uL, 15uL)[select])
        } })

    fun demux(): HdlFixtureCase = namedFixture("demultiplexer_enabled",
        CircuitComponent("demux", ComponentKind("DEMULTIPLEXER"), GridPoint(300, 200), attributes = mapOf("width" to "4", "select" to "2", "enable" to "true")),
        (0..3).flatMap { select -> (0..1).map { enabled ->
            mapOf("data" to 11uL, "select" to select.toULong(), "enable" to enabled.toULong()) to List(4) { if (it == select && enabled == 1) 11uL else 0uL }
        } })

    fun decoder(): HdlFixtureCase = namedFixture("decoder_enabled",
        CircuitComponent("decoder", ComponentKind.DECODER, GridPoint(300, 200), attributes = mapOf("select" to "2", "enable" to "true")),
        (0..3).flatMap { select -> (0..1).map { enabled ->
            mapOf("select" to select.toULong(), "enable" to enabled.toULong()) to List(4) { if (it == select && enabled == 1) 1uL else 0uL }
        } })

    fun bitSelector(): HdlFixtureCase = namedFixture("selector_padded_groups",
        CircuitComponent("selector", ComponentKind("BIT_SELECTOR"), GridPoint(300, 200), attributes = mapOf("width" to "10", "group" to "4")),
        listOf(0uL, 1uL, 341uL, 1023uL).flatMap { data -> (0..3).map { select ->
            mapOf("data" to data, "select" to select.toULong()) to listOf((data shr (select * 4)) and 15uL)
        } })

    fun priorityEncoder(): HdlFixtureCase = namedFixture("priority_encoder_valid",
        CircuitComponent("encoder", ComponentKind("PRIORITY_ENCODER"), GridPoint(300, 200), attributes = mapOf("select" to "2")),
        (1..15).flatMap { mask -> (0..1).map { enabled ->
            val selected = (0..3).last { mask and (1 shl it) != 0 }
            (0..3).associate { "in$it" to ((mask shr it) and 1).toULong() } + ("enable" to enabled.toULong()) to
                listOf(if (enabled == 1) selected.toULong() else 0uL, 0uL, enabled.toULong())
        } })

    fun counterMaskedLoad(): HdlFixtureCase = componentFixture("counter_masked_load",
        CircuitComponent("counter", ComponentKind.COUNTER, GridPoint(300, 200), attributes = mapOf("width" to "4", "max" to "9")),
        listOf(
            listOf(0uL, 0uL, 0uL, 1uL, 1uL, 1uL) to listOf(0uL, 0uL),
            listOf(15uL, 0uL, 1uL, 0uL, 0uL, 1uL) to listOf(0uL, 0uL),
            listOf(15uL, 1uL, 1uL, 0uL, 0uL, 1uL) to listOf(9uL, 1uL),
            listOf(12uL, 0uL, 1uL, 0uL, 0uL, 1uL) to listOf(9uL, 1uL),
            listOf(12uL, 1uL, 1uL, 0uL, 0uL, 1uL) to listOf(8uL, 0uL),
        ))

    fun constant(): HdlFixtureCase = componentFixture("decimal_constant",
        CircuitComponent("constant", ComponentKind.CONSTANT, GridPoint(300, 200), attributes = mapOf("width" to "8", "value" to "10")),
        listOf(emptyList<ULong>() to listOf(10uL)))

    fun hierarchy(): HdlFixtureCase {
        val child = flipFlop().project.mainCircuit.copy(name = "child", id = "child")
        val childPins = child.components.filter { it.kind in setOf(ComponentKind.INPUT, ComponentKind.OUTPUT) }.sortedBy { it.id }
        val components = mutableListOf<CircuitComponent>()
        val names = mutableMapOf<String, String>()
        repeat(2) { instance ->
            val explicit = childPins.mapIndexed { index, pin ->
                ComponentPort(pin.id, GridPoint(if (pin.kind == ComponentKind.INPUT) -40 else 40, index * 20),
                    if (pin.kind == ComponentKind.INPUT) PortDirection.INPUT else PortDirection.OUTPUT, pin.width)
            }
            val block = CircuitComponent("instance$instance", ComponentKind.SUBCIRCUIT, GridPoint(500 + instance * 500, 100),
                circuitName = "child", explicitPorts = explicit)
            components += block
            explicit.forEachIndexed { index, port ->
                val pin = childPins[index]
                val id = "${instance}_${pin.id}"
                names["$instance/${pin.id}"] = id
                components += CircuitComponent(id, pin.kind, block.relativePoint(port.position), id, mapOf("width" to pin.width.toString()))
            }
        }
        val frames = listOf(
            listOf(listOf(0uL, 0uL, 1uL, 0uL), listOf(0uL, 0uL, 1uL, 0uL)) to listOf(0uL, 0uL),
            listOf(listOf(1uL, 0uL, 0uL, 0uL), listOf(0uL, 0uL, 0uL, 0uL)) to listOf(0uL, 0uL),
            listOf(listOf(1uL, 1uL, 0uL, 0uL), listOf(0uL, 0uL, 0uL, 0uL)) to listOf(1uL, 0uL),
            listOf(listOf(0uL, 0uL, 0uL, 0uL), listOf(1uL, 1uL, 0uL, 0uL)) to listOf(1uL, 1uL),
            listOf(listOf(0uL, 1uL, 0uL, 0uL), listOf(1uL, 0uL, 0uL, 0uL)) to listOf(0uL, 1uL),
        )
        return HdlFixtureCase("independent_instances", CircuitProject(listOf(CircuitDocument("main", components), child), "main"), frames.map { (input, output) ->
            HdlVectorCase(buildMap { repeat(2) { instance -> repeat(4) { bit -> put(names.getValue("$instance/i$bit"), input[instance][bit]) } } },
                buildMap { repeat(2) { instance -> put(names.getValue("$instance/o0"), output[instance]); put(names.getValue("$instance/o1"), output[instance] xor 1uL) } })
        })
    }
}
