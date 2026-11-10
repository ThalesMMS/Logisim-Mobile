package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Rv32AssemblerTest {
    @Test
    fun assembledSourceLoadsThroughTheSelectedCpuAndStepsToSeven() {
        val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
        val simulator = CircuitSimulator(registry = registry)
        val bus = mapOf("SocBusSelection" to "shared")
        val circuit = CircuitDocument("main", listOf(
            CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "shared")),
            CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = bus),
            CircuitComponent("other", SocComponents.RV32, GridPoint(800, 0), attributes = bus),
            CircuitComponent("selected", SocComponents.RV32, GridPoint(1600, 0), attributes = bus),
        ))
        val program = assertIs<SocAssemblyResult.Assembled>(Rv32Assembler.assemble("addi x5, x0, 7", 0x100u))
        var simulation = simulator.settle(circuit, events = mapOf("selected" to listOf(SocLoadAssemblyEvent(program))))
        fun cpu(id: String) = assertIs<SocRv32ComponentState>(simulation.componentStates.getValue(id)).cpu
        val ram = assertIs<SocMemoryDevice>(assertIs<SocDeviceComponentState>(simulation.componentStates.getValue("ram")).device)
        assertEquals(0x00700293u, ram.storedWords[0x100u])
        assertEquals(0x100u, cpu("selected").pc)
        assertEquals(0u, cpu("other").pc)
        assertTrue(cpu("selected").stopped)

        simulation = simulator.settle(circuit, simulation, events = mapOf("selected" to listOf(SocStepEvent)))
        assertEquals(7u, cpu("selected").register(5))
        assertEquals(0x104u, cpu("selected").pc)
        assertEquals(1L, cpu("selected").instructions)
        assertTrue(cpu("selected").stopped)
        assertEquals(0L, cpu("other").instructions)
    }

    @Test
    fun encodesBranchLabelsAbiRegistersAndMemoryOperandsLikeTheDesktop() {
        val source = """
            .text
            addi ra, zero, 0
            addi sp, zero, 1
            addi gp, zero, 6
            loop: add ra, ra, sp
            addi sp, sp, 1
            blt sp, gp, loop
            lui tp, 1
            sw ra, 0(tp)
            sw ra, 0x100(zero)
            lw t0, 0x100(zero)
        """.trimIndent()
        val expected = listOf(0x00000093, 0x00100113, 0x00600193, 0x002080B3, 0x00110113,
            0xFE314CE3.toInt(), 0x00001237, 0x00122023, 0x10102023, 0x10002283)
        val assembled = assertIs<SocAssemblyResult.Assembled>(Rv32Assembler.assemble(source, 0u))
        assertContentEquals(expected.flatMap { word -> List(4) { (word ushr (8 * it)).toByte() } }.toByteArray(),
            assembled.image.segments.single().bytes)
    }

    @Test
    fun rejectsInvalidSourceWithTheOffendingLineAndOperand() {
        fun rejects(source: String, text: String) {
            // A label line: Evolution reads following identifiers as parameters of a preceding instruction.
            val error = assertIs<SocAssemblyResult.Rejected>(Rv32Assembler.assemble("start:\n$source", 0u))
            assertEquals(2, error.line)
            assertTrue(error.notice.resolve(AppLanguage.ENGLISH).contains(text))
        }
        rejects("addi x32, x0, 7", "x32")
        rejects("addi x5, x0, 2048", "2048")
        rejects("beq x0, x0, missing", "missing")
        rejects("unrecognized x5", "unrecognized")
        assertIs<SocAssemblyResult.Rejected>(Rv32Assembler.assemble("nop\nnop", UInt.MAX_VALUE - 3u))
    }

    private fun words(result: SocAssemblyResult): List<String> {
        val image = assertIs<SocAssemblyResult.Assembled>(result, result.toString()).image
        return image.segments.flatMap { segment ->
            segment.bytes.toList().chunked(4).mapIndexed { index, bytes ->
                val word = bytes.withIndex().sumOf { (shift, byte) -> (byte.toLong() and 0xFF) shl (8 * shift) }
                (segment.address + (index * 4).toUInt()).toString(16).padStart(8, '0') + ":" + word.toString(16).padStart(8, '0')
            }
        }
    }

    @Test
    fun pseudoInstructionsAndDataSectionsFollowEvolution() {
        // Expected words captured from Evolution d58a61e0 (scripts/oracle/soc-debugger).
        assertEquals(listOf("00000000:00000013", "00000004:02a00293", "00000008:00028313", "0000000c:fff34393",
            "00000010:0040006f", "00000014:00008067"),
            words(Rv32Assembler.assemble("  nop\n  li x5, 42\n  mv x6, x5\n  not x7, x6\n  j end\nend:\n  ret\n", 0u)))
        assertEquals(listOf("00000000:00100093", "00000004:12345678"),
            words(Rv32Assembler.assemble(".text\n  addi x1, x0, 1\n.data\nvalue:\n  .word 0x12345678\n", 0u)))
        for ((statement, word) in listOf("jalr x0, x1, 0" to "00008067", "jalr x1, x2, 8" to "008100e7", "jr x1" to "00008067",
                "snez x1, x2" to "002030b3", "seqz x1, x2" to "00113093")) {
            assertEquals(listOf("00000000:00000013", "00000004:$word"), words(Rv32Assembler.assemble("start:\n  addi x0, x0, 0\n  $statement\n", 0u)), statement)
        }
        assertEquals(listOf("00000000:00008067"), words(Rv32Assembler.assemble("jr ra", 0u)))
        // Syntax Evolution's assembler rejects.
        for (source in listOf("jalr x0, 0(x1)", "fence", "addi fp, tp, 2", "addi x1, x0, 0b101", "ADDI X1, X0, 1", "call start", ".globl main")) {
            assertIs<SocAssemblyResult.Rejected>(Rv32Assembler.assemble("start:\n$source\n", 0u), source)
        }
    }

    @Test
    fun nios2AssemblerEncodesLikeEvolution() {
        // Words captured from Evolution d58a61e0's Nios2Assembler (scripts/oracle/soc-debugger), after "start: nop".
        val cases = listOf(
            "ldw r2, 4(sp)" to listOf("d8800117"), "movia r2, 0x12348765" to listOf("00848d74", "10a1d944"),
            "cmpgt r2, r3, r4" to listOf("20c4803a"), "roli r2, r3, 31" to listOf("180417fa"), "ret" to listOf("f800283a"),
            "bgt r2, r3, start" to listOf("18bffe16"), "rdctl r2, ctl3" to listOf("000530fa"), "wrctl ctl4, r2" to listOf("1001713a"),
            "custom 255, c1, c2, c3" to listOf("10c3fff2"),
        )
        for ((statement, expected) in cases) {
            val words = words(Nios2Assembler.assemble("start:\n  nop\n  $statement\n", 0u))
            assertEquals(listOf("00000000:0001883a") + expected.mapIndexed { index, word -> "0000000${4 + 4 * index}:$word" }, words, statement)
        }
        assertEquals(SocArchitecture.NIOS_II, assertIs<SocAssemblyResult.Assembled>(Nios2Assembler.assemble("nop", 0u)).image.architecture)
        for (source in listOf("ldw r2, 4(ctl1)", "add r2, r3, r32", "ADD r2, r3, r4", "slli r2, r3, 32")) {
            assertIs<SocAssemblyResult.Rejected>(Nios2Assembler.assemble("start:\n$source\n", 0u), source)
        }
    }
}
