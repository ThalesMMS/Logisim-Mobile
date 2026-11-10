package dev.logisim.mobile.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlImportCompanionIntegrationTest {
    private val registry = ComponentCatalog.registry.withDefinitions(
        GatesLibrary.definitions() + WiringLibrary.definitions() + SequentialLibrary.definitions)
    @Test fun authenticatedImportResolvesDeclaredIncludePaths() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val options = HdlImportOptions(listOf(
                HdlInputFile("top.v", """
                    `include "inc/defs.vh"
                    module top(output y); assign y = `VALUE; endmodule
                """.trimIndent()),
                HdlInputFile("inc/defs.vh", "`define VALUE 1'b1"),
            ), sourceNames = listOf("top.v"), top = "top")
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.message)
            val project = CircuitProject(listOf(CircuitDocument("scratch")))
            val imported = assertIs<HdlImportResult.Success>(job.importHdl(options, project, registry))
            assertTrue(imported.project.extraXml.any { it.contains("file=\"inc/defs.vh\"") })
            val output = imported.project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
            val state = CircuitSimulator(registry = registry).settle(imported.project)
            assertEquals(1uL, state.vectorValues.getValue(output.id).toULongOrNull())

            val traversal = options.copy(sources = options.sources.map {
                it.copy(text = it.text.replace("inc/defs.vh", "inc/../defs.vh"))
            })
            val rejected = host.execute(traversal.request())
            assertEquals(CompanionJobState.FAILED, rejected.state)
            assertEquals("external-file", rejected.error?.code)
        }
    }

    @Test fun authenticatedHttpYosysImportProducesNativeHierarchicalCircuitTruthTable() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val source = """
                module and2(input [1:0] a, b, output [1:0] y);
                  assign y = a & b;
                endmodule
                module top(input [1:0] a, b, output [1:0] y);
                  and2 child(.a(a), .b(b), .y(y));
                endmodule
            """.trimIndent()
            val options = HdlImportOptions(listOf(HdlInputFile("hierarchy.v", source)), top = "top")
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.code)
            val original = CircuitProject(listOf(CircuitDocument("scratch")))
            val result = job.importHdl(options, original, registry)
            val imported = assertIs<HdlImportResult.Success>(result, result.toString())
            val project = imported.project
            assertEquals(setOf("top", "and2"), imported.moduleNames.keys)
            assertTrue(project.circuits.contains(original.mainCircuit))
            assertTrue(project.extraXml.any { it.contains("hierarchy.v") && it.contains("module top") })
            val inputs = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
            val output = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
            val simulator = CircuitSimulator(registry = registry)
            for (a in 0..3) for (b in 0..3) {
                val state = simulator.settle(project, vectorInputs = mapOf(
                    inputs.getValue("a").id to LogicVector.of(2, a.toULong()),
                    inputs.getValue("b").id to LogicVector.of(2, b.toULong()),
                ))
                assertFalse(state.oscillating, state.diagnostics.toString())
                assertEquals((a and b).toULong(), state.vectorValues.getValue(output.id).toULongOrNull(), "$a & $b")
            }
        }
    }

    @Test fun editableInoutDistinguishesExternalReadFromNestedDriveAndRelease() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val source = """
                module child(input en, input [1:0] d, inout [1:0] io, output [1:0] q);
                  assign io = en ? d : 2'bzz;
                  assign q = io;
                endmodule
                module top(input en, input [1:0] d, inout [1:0] io, output [1:0] q);
                  child nested(.en(en), .d(d), .io(io), .q(q));
                endmodule
            """.trimIndent()
            val options = HdlImportOptions(listOf(HdlInputFile("inout.v", source)), top = "top")
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.code)
            val original = CircuitProject(listOf(CircuitDocument("scratch")))
            val imported = assertIs<HdlImportResult.Success>(job.importHdl(options, original, registry)).project
            val reopened = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(imported), registry),
            ).project
            for (project in listOf(imported, reopened)) {
                val inputs = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
                val outputs = project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }.associateBy { it.label }
                assertEquals("tristate", inputs.getValue("io_in").attributes["behavior"])
                assertEquals(2, inputs.getValue("io_in").width)
                assertEquals(2, outputs.getValue("io_out").width)
                val simulator = CircuitSimulator(registry = registry)
                var state = SimulationState()
                fun sample(enable: Boolean, external: LogicVector?, drive: String, read: String) {
                    state = simulator.settle(project, state, vectorInputs = buildMap {
                        put(inputs.getValue("en").id, LogicVector.fromBit(if (enable) LogicValue.HIGH else LogicValue.LOW))
                        put(inputs.getValue("d").id, LogicVector.of(2, 2uL))
                        if (external != null) put(inputs.getValue("io_in").id, external)
                    })
                    assertFalse(state.oscillating, state.diagnostics.toString())
                    assertEquals(drive, state.vectorValues.getValue(outputs.getValue("io_out").id).display)
                    assertEquals(read, state.vectorValues.getValue(outputs.getValue("q").id).display)
                }
                sample(false, null, "??", "??")
                sample(true, null, "10", "10")
                sample(false, null, "??", "??")
                sample(false, LogicVector.of(2, 1uL), "??", "01")
                sample(true, LogicVector.of(2, 1uL), "10", "EE")
                sample(false, LogicVector.of(2, 1uL), "??", "01")
                sample(false, LogicVector.unknown(2), "??", "??")
            }
        }
    }

    @Test fun authenticatedWordImportKeepsNativeArithmeticMuxAndClockedRegistersAfterReload() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val source = host.root.resolve("fixtures/hdl/import/word_pipeline.v").readText()
            val options = HdlImportOptions(listOf(HdlInputFile("word_pipeline.v", source)), top = "word_pipeline")
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.code)
            val result = job.importHdl(options, CircuitProject(listOf(CircuitDocument("scratch"))), registry)
            val imported = assertIs<HdlImportResult.Success>(result, result.toString()).project
            val reopened = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(imported, registry), registry)).project
            for (project in listOf(imported, reopened)) {
                val components = project.mainCircuit.components
                assertEquals(14, components.single { it.kind == ComponentKind("ADDER") }.width)
                assertEquals(14, components.single { it.kind == ComponentKind("SUBTRACTOR") }.width)
                assertEquals(14, components.single { it.kind == ComponentKind.REGISTER }.width)
                assertEquals(listOf(14, 14), components.filter { it.kind == ComponentKind.MULTIPLEXER }.map { it.width })
                assertTrue(components.none { it.kind == ComponentKind.D_FLIP_FLOP })
                val inputs = components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
                val output = components.single { it.kind == ComponentKind.OUTPUT }
                val simulator = CircuitSimulator(registry = registry)
                var state = SimulationState()
                fun sample(clock: Int, reset: Int, enable: Int, subtract: Int, a: Int, b: Int, expected: ULong) {
                    val values = mapOf("clk" to clock, "reset" to reset, "enable" to enable, "subtract" to subtract,
                        "a" to a, "b" to b)
                    state = simulator.settle(project, state, vectorInputs = values.map { (name, value) ->
                        val pin = inputs.getValue(name)
                        pin.id to LogicVector.of(pin.width, value.toULong())
                    }.toMap())
                    assertFalse(state.oscillating, state.diagnostics.toString())
                    assertEquals(expected, state.vectorValues.getValue(output.id).toULongOrNull(), values.toString())
                }
                sample(0, 1, 0, 0, 0, 0, 0uL)
                sample(1, 1, 0, 0, 0, 0, 0uL)
                sample(0, 0, 1, 0, 16383, 3, 0uL)
                sample(1, 0, 1, 0, 16383, 3, 2uL)
                sample(0, 0, 0, 1, 2, 3, 2uL)
                sample(1, 0, 0, 1, 2, 3, 2uL)
                sample(0, 0, 1, 1, 2, 3, 2uL)
                sample(1, 0, 1, 1, 2, 3, 16383uL)
                sample(0, 1, 0, 0, 3, 3, 16383uL)
                sample(1, 1, 0, 0, 3, 3, 0uL)
            }
        }
    }

    @Test fun signedInputsAndActiveLowControlsRetainTheirMeaningOnFallingEdges() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val source = """
                module signed_pipeline(input clk, reset_n, enable_n,
                    input signed [4:0] a, input signed [6:0] b, output reg [13:0] q);
                  always @(negedge clk)
                    if (!reset_n) q <= 14'h3012;
                    else if (!enable_n) q <= a + b;
                endmodule
            """.trimIndent()
            val options = HdlImportOptions(listOf(HdlInputFile("signed_pipeline.v", source)), top = "signed_pipeline")
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.code)
            val result = job.importHdl(options, CircuitProject(listOf(CircuitDocument("scratch"))), registry)
            val project = assertIs<HdlImportResult.Success>(result, result.toString()).project
            val components = project.mainCircuit.components
            val register = components.single { it.kind == ComponentKind.REGISTER }
            assertEquals(14, register.width)
            assertEquals("falling", register.attributes["trigger"])
            val inputs = components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
            val output = components.single { it.kind == ComponentKind.OUTPUT }
            val simulator = CircuitSimulator(registry = registry)
            var state = SimulationState()
            fun sample(clock: Int, resetN: Int, enableN: Int, expected: ULong) {
                val values = mapOf("clk" to clock, "reset_n" to resetN, "enable_n" to enableN, "a" to 27, "b" to 3)
                state = simulator.settle(project, state, vectorInputs = values.map { (name, value) ->
                    val pin = inputs.getValue(name)
                    pin.id to LogicVector.of(pin.width, value.toULong())
                }.toMap())
                assertFalse(state.oscillating, state.diagnostics.toString())
                assertEquals(expected, state.vectorValues.getValue(output.id).toULongOrNull(), values.toString())
            }
            sample(1, 0, 1, 0uL)
            sample(0, 0, 1, 0x3012uL)
            sample(1, 1, 0, 0x3012uL)
            sample(0, 1, 0, 0x3ffeuL)
            sample(1, 0, 1, 0x3ffeuL)
            sample(0, 0, 1, 0x3012uL)
        }
    }

    @Test fun initializedRamAndRomSurviveSynthesisWritesAndCircRoundTrip() = runBlocking {
        HdlCompanionTestHost.start("yosys").use { host ->
            val directory = host.root.resolve("fixtures/hdl/import")
            val source = ("`include \"inc/defs.vh\"\n" + directory.resolve("memory.v").readText())
                .replace("output [3:0] q);", "output [3:0] q, output [3:0] rom_q, output [3:0] sum);")
                .replace("endmodule", """
                    reg [3:0] lookup [0:`ROM_LAST];
                    initial ${'$'}readmemh("words.hex", lookup);
                    assign rom_q = lookup[address];
                    assign sum = q + rom_q;
                    endmodule
                """.trimIndent())
            val options = HdlImportOptions(
                listOf(
                    HdlInputFile("memory.v", source),
                    HdlInputFile("words.hex", directory.resolve("words.hex").readText()),
                    HdlInputFile("inc/defs.vh", "`define ROM_LAST 3"),
                ),
                sourceNames = listOf("memory.v"), top = "memory",
            )
            val job = host.execute(options.request())
            assertEquals(CompanionJobState.SUCCEEDED, job.state, job.error?.toString())
            val original = CircuitProject(listOf(CircuitDocument("scratch")), sourceVersion = "2.7.1")
            val project = assertIs<HdlImportResult.Success>(job.importHdl(options, original, registry)).project
            val reopened = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(project, registry), registry),
            ).project
            for (circuit in listOf(project, reopened)) {
                val components = circuit.mainCircuit.components
                assertEquals("words", components.single { it.kind == ComponentKind.RAM }.label)
                assertEquals("lookup", components.single { it.kind == ComponentKind.ROM }.label)
                assertEquals(4, components.single { it.kind == ComponentKind.ADDER }.width)
                val inputIds = components.filter { it.kind == ComponentKind.INPUT }.associate { it.label to it.id }
                val outputIds = components.filter { it.kind == ComponentKind.OUTPUT }.associate { it.label to it.id }
                val simulator = CircuitSimulator(registry = registry)
                var previous = SimulationState()
                fun sample(address: Int, clock: Int = 0, write: Int = 0, reset: Boolean = false): List<ULong?> {
                    val signals = mapOf(
                        "address" to LogicVector.of(2, address.toULong()),
                        "clk" to LogicVector.of(1, clock.toULong()),
                        "write_enable" to LogicVector.of(1, write.toULong()),
                        "data" to LogicVector.of(4, 12uL),
                    ).mapKeys { inputIds.getValue(it.key) }
                    previous = simulator.settle(circuit, previous, vectorInputs = signals, reset = reset)
                    assertFalse(previous.oscillating, previous.diagnostics.toString())
                    fun output(name: String): ULong? = previous.vectorValues.getValue(outputIds.getValue(name)).toULongOrNull()
                    val ramValue = output("q")
                    val romValue = output("rom_q")
                    assertEquals((checkNotNull(ramValue) + checkNotNull(romValue)) and 15uL, output("sum"))
                    return listOf(ramValue, romValue)
                }
                assertEquals(listOf(listOf(1uL, 1uL), listOf(10uL, 10uL), listOf(5uL, 5uL), listOf(15uL, 15uL)),
                    (0..3).map { sample(it) })
                assertEquals(listOf(10uL, 10uL), sample(1, write = 1))
                assertEquals(listOf(12uL, 10uL), sample(1, clock = 1, write = 1))
                assertEquals(listOf(5uL, 5uL), sample(2, clock = 1, write = 1))
                assertEquals(listOf(12uL, 10uL), sample(1))
                assertEquals(listOf(10uL, 10uL), sample(1, reset = true))
            }
        }
    }
}
