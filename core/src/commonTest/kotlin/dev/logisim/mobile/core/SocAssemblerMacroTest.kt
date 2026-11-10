package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Evolution's soc.util.AssemblerMacro; expected words come from Evolution d58a61e0 (scripts/oracle/soc-debugger). */
class SocAssemblerMacroTest {
    private fun words(result: SocAssemblyResult): List<Int> {
        val bytes = assertIs<SocAssemblyResult.Assembled>(result).image.segments.single().bytes
        return List(bytes.size / 4) { index -> (0..3).fold(0) { word, byte -> word or ((bytes[index * 4 + byte].toInt() and 0xFF) shl (8 * byte)) } }
    }

    private fun rv32(vararg lines: String) = Rv32Assembler.assemble(lines.joinToString("\n", postfix = "\n"), 0u)

    private fun rejected(result: SocAssemblyResult, line: Int, key: String) {
        val rejection = assertIs<SocAssemblyResult.Rejected>(result)
        assertEquals(line to key, rejection.line to rejection.notice.key)
    }

    @Test
    fun expandsParametersAsTheIssueExample() {
        assertEquals(listOf(0x00128293), words(rv32(".macro inc 1", "  addi @1, @1, 1", ".endm", "start:", "  inc x5")))
        assertEquals(listOf(0x007302b3, 0x406283b3, 0x00058533, 0x40b50033),
            words(rv32(".macro add3 3", "  add @1, @2, @3", "  sub @3, @1, @2", ".endm", "start:", "  add3 x5, x6, x7", "  add3 a0, a1, zero")))
    }

    @Test
    fun resolvesLocalLabelsPerExpansionAndNestedMacros() {
        assertEquals(listOf(0x00000013, 0x0062c463, 0x00030293, 0x0083c463, 0x00040393),
            words(rv32(".macro clamp 2", "  blt @1, @2, done", "  mv @1, @2", "done:", ".endm", "start:", "  nop", "  clamp x5, x6", "  clamp x7, x8")))
        assertEquals(listOf(0x00300293, 0xfff28293.toInt(), 0xfe029ee3.toInt(), 0x00100073),
            words(rv32(".macro countdown 1", "again:", "  addi @1, @1, -1", "  bnez @1, again", ".endm", "start:", "  li x5, 3", "  countdown x5", "  ebreak")))
        assertEquals(listOf(0x00128293, 0x00130313, 0x00628063, 0x00138393),
            words(rv32(".macro inc 1", "  addi @1, @1, 1", ".endm", ".macro inc2 2", "  inc @1", "  inc @2", "skip:", "  beq @1, @2, skip", ".endm",
                "start:", "  inc2 x5, x6", "  inc x7")))
    }

    @Test
    fun keepsEvolutionsTokenQuirks() {
        // A copied label in the body is no longer a label for lui; a parameter label still is.
        assertEquals(0x020002b7, words(rv32(".macro hi 1", "  lui @1, target", ".endm", "start:", "  hi x5", ".org 0x2000", "target:", "  nop")).first())
        // A hex literal copied from the body has no label flag: Evolution throws on lui.
        rejected(rv32(".macro hi 1", "  lui @1, 0x12", ".endm", "start:", "  hi x5"), 2, "soc_asm_immediate")
        assertEquals(listOf(0x000122b7), words(rv32(".macro hi 1", "  lui @1, 18", ".endm", "start:", "  hi x5")))
        // A later definition replaces an earlier one with the same name.
        assertEquals(listOf(0x00200093), words(rv32(".macro m 0", "  addi x1, x0, 1", ".endm", ".macro m 0", "  addi x1, x0, 2", ".endm", "start:", "  m")))
        // The pc parameter token is shared by both uses, as Evolution's getMacroTokens.
        rejected(rv32(".macro twice 1", "  beq x0, x0, @1", "  beq x0, x0, @1", ".endm", "start:", "  twice pc+8"), 6, "soc_asm_immediate")
    }

    @Test
    fun reportsMacroErrorsOnEvolutionsLines() {
        rejected(rv32(".macro inc 1", "  addi @1, @1, 1", "start:", "  nop"), 1, "soc_asm_macro_definition")
        rejected(rv32("start:", "  nop", ".macro foo", "  nop", ".endm"), 3, "soc_asm_macro_definition")
        rejected(rv32(".macro bad 0", "  .word 5", ".endm", "start:", "  nop"), 2, "soc_asm_macro_directive")
        rejected(rv32(".macro bad 2", "  add @1, @2, @3", ".endm", "start:", "  nop"), 2, "soc_asm_macro_parameter")
        rejected(rv32(".macro add3 3", "  add @1, @2, @3", ".endm", "start:", "  add3 x5, x6"), 5, "soc_asm_macro_parameters")
        rejected(rv32(".macro loop 0", "  nop", "  loop", ".endm", "start:", "  loop"), 3, "soc_asm_macro_recursion")
        rejected(rv32(".macro a 0", "  b", ".endm", ".macro b 0", "  a", ".endm", "start:", "  nop"), 2, "soc_asm_macro_recursion")
        rejected(rv32(".macro m 0", "inner:", "  nop", ".endm", "start:", "  m", "  j inner"), 7, "soc_asm_target")
        rejected(rv32(".macro m1 0", "here:", "  nop", ".endm", ".macro m2 0", "here:", "  nop", ".endm", "start:", "  m1"), 6, "soc_asm_duplicate_label")
    }

    @Test
    fun expandsNios2Macros() {
        val source = listOf(".macro countdown 1", "again:", "  subi @1, @1, 1", "  bne @1, zero, again", ".endm",
            "_start:", "  movi r5, 3", "  countdown r5", "  break").joinToString("\n", postfix = "\n")
        assertEquals(listOf(0x014000c4, 0x29400044, 0x283ffe1e, 0x003da03a), words(Nios2Assembler.assemble(source, 0u)))
    }
}
