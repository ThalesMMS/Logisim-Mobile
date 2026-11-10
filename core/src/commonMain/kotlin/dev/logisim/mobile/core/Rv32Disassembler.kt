package dev.logisim.mobile.core

/**
 * The trace text of one RV32IM word, as Evolution's execution units write it in
 * getAsmInstruction(): the lower-case mnemonic padded to 10 columns, ABI register names and
 * pc-relative targets ("blt       sp,gp,pc-8"). Pseudo-instructions are chosen the way
 * Evolution decodes them (li, mv, nop, not, seqz, snez, j, jr, ret, beqz, bnez, csrw...),
 * and the units are tried in Evolution's order. Returns null for a word no unit decodes.
 */
object Rv32Disassembler {
    private const val FIELD = 10
    val abiNames = listOf(
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4",
        "a5", "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    )
    private val sprNames = mapOf(
        0xF11 to "mvendorid", 0xF12 to "marchid", 0xF13 to "mimpid", 0xF14 to "mhartid", 0x300 to "mstatus",
        0x301 to "misa", 0x304 to "mie", 0x305 to "mtvec", 0x340 to "mscratch", 0x341 to "mepc", 0x342 to "mcause",
        0x343 to "mtval", 0x344 to "mip", 0x7B0 to "dcsr", 0x7B1 to "dpc", 0x7A0 to "tselect", 0x7A1 to "tdata1",
        0x7A2 to "tdata2", 0x7A4 to "tinfo",
    )

    fun format(instruction: UInt): String? {
        val bits = instruction.toInt()
        val opcode = bits and 0x7F
        val rd = bits ushr 7 and 31
        val funct3 = bits ushr 12 and 7
        val rs1 = bits ushr 15 and 31
        val rs2 = bits ushr 20 and 31
        val funct7 = bits ushr 25
        val iImmediate = bits shr 20
        fun op(name: String) = name.padEnd(FIELD)
        fun r(index: Int) = abiNames[index]
        fun relative(offset: Int) = "pc" + when {
            offset > 0 -> "+$offset"
            offset < 0 -> "$offset"
            else -> ""
        }
        return when (opcode) {
            // RV32imIntegerRegisterImmediateInstructions
            0x37, 0x17 -> op(if (opcode == 0x37) "lui" else "auipc") + "${r(rd)},${bits ushr 12 and 0xFFFFF}"
            0x13 -> when (funct3) {
                1 -> if (funct7 != 0) null else op("slli") + "${r(rd)},${r(rs1)},${rs2}"
                5 -> if (funct7 != 0 && funct7 != 0x20) null
                    else op(if (bits ushr 30 and 1 == 1) "srai" else "srli") + "${r(rd)},${r(rs1)},${rs2}"
                0 -> when {
                    rd == 0 && rs1 == 0 && iImmediate == 0 -> op("nop")
                    rs1 == 0 -> op("li") + "${r(rd)},$iImmediate"
                    iImmediate == 0 -> op("mv") + "${r(rd)},${r(rs1)}"
                    else -> op("addi") + "${r(rd)},${r(rs1)},$iImmediate"
                }
                3 -> if (iImmediate == 1) op("seqz") + "${r(rd)},${r(rs1)}" else op("sltiu") + "${r(rd)},${r(rs1)},$iImmediate"
                4 -> if (iImmediate == -1) op("not") + "${r(rd)},${r(rs1)}" else op("xori") + "${r(rd)},${r(rs1)},$iImmediate"
                else -> op(arrayOf("", "", "slti", "", "", "", "ori", "andi")[funct3]) + "${r(rd)},${r(rs1)},$iImmediate"
            }
            // RV32imIntegerRegisterRegisterOperations, then RV32im_M_ExtensionInstructions
            0x33 -> when {
                funct7 == 1 -> op(arrayOf("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")[funct3]) +
                    "${r(rd)},${r(rs1)},${r(rs2)}"
                funct7 == 0x20 && funct3 != 0 && funct3 != 5 -> null
                funct7 != 0 && funct7 != 0x20 -> null
                funct3 == 3 && rs1 == 0 -> op("snez") + "${r(rd)},${r(rs2)}"
                else -> op(when (funct3) {
                    0 -> if (funct7 == 0x20) "sub" else "add"
                    5 -> if (funct7 == 0x20) "sra" else "srl"
                    else -> arrayOf("", "sll", "slt", "sltu", "xor", "", "or", "and")[funct3]
                }) + "${r(rd)},${r(rs1)},${r(rs2)}"
            }
            // RV32imControlTransferInstructions
            0x6F -> {
                val offset = ((bits shr 31) shl 20) or ((bits ushr 12 and 0xFF) shl 12) or
                    ((bits ushr 20 and 1) shl 11) or ((bits ushr 21 and 0x3FF) shl 1)
                if (rd == 0) op("j") + relative(offset) else op("jal") + "${r(rd)}," + relative(offset)
            }
            0x67 -> when {
                funct3 != 0 -> null
                rd == 0 && rs1 == 1 && iImmediate == 0 -> op("ret")
                else -> (if (rd == 0) op("jr") else op("jalr") + "${r(rd)},") + r(rs1) + if (iImmediate != 0) ",$iImmediate" else ""
            }
            0x63 -> if (funct3 == 2 || funct3 == 3) null else {
                val offset = ((bits shr 31) shl 12) or ((bits ushr 7 and 1) shl 11) or
                    ((bits ushr 25 and 0x3F) shl 5) or ((bits ushr 8 and 15) shl 1)
                when {
                    funct3 == 0 && rs2 == 0 -> op("beqz") + "${r(rs1)}," + relative(offset)
                    funct3 == 1 && rs2 == 0 -> op("bnez") + "${r(rs1)}," + relative(offset)
                    else -> op(arrayOf("beq", "bne", "", "", "blt", "bge", "bltu", "bgeu")[funct3]) +
                        "${r(rs1)},${r(rs2)}," + relative(offset)
                }
            }
            // RV32imLoadAndStoreInstructions
            0x03 -> when (funct3) {
                0, 1, 2, 4, 5 -> op(arrayOf("lb", "lh", "lw", "", "lbu", "lhu")[funct3]) + "${r(rd)},$iImmediate(${r(rs1)})"
                else -> null
            }
            0x23 -> if (funct3 > 2) null else {
                val offset = ((bits shr 25) shl 5) or (bits ushr 7 and 31)
                op(arrayOf("sb", "sh", "sw")[funct3]) + "${r(rs2)},$offset(${r(rs1)})"
            }
            // Rv32imMemoryOrderingInstructions
            0x0F -> if (funct3 != 0) null else {
                val successor = bits ushr 20 and 15
                val predecessor = bits ushr 24 and 15
                if (bits ushr 28 and 15 == 8 && successor == 3 && predecessor == 3) "fence.tso"
                else op("fence") + masks(successor) + "," + masks(predecessor)
            }
            // RV32imEnvironmentCallAndBreakpoints, then RV32im_Zicsr_ExtensionInstructions
            0x73 -> when (val funct12 = bits ushr 20 and 0xFFF) {
                0 -> "ecall"
                1 -> "ebreak"
                0x302 -> "mret"
                else -> {
                    val operation = when (funct3) { 1 -> 0; 2 -> 1; 3 -> 2; 5 -> 3; 6 -> 4; 7 -> 5; else -> return null }
                    val names = arrayOf("csrrw", "csrrs", "csrrc", "csrrwi", "csrrsi", "csrrci")
                    val name = if (rd == 0) names[operation].replace("csrr", "csr") else names[operation]
                    val spr = sprNames[funct12] ?: "0x" + funct12.toString(16).uppercase().padStart(3, '0')
                    val source = if (operation < 3) r(rs1) else "0x" + rs1.toString(16).uppercase().padStart(2, '0')
                    op(name) + (if (rd != 0) "${r(rd)}," else "") + "$spr,$source"
                }
            }
            else -> null
        }
    }

    private fun masks(value: Int) = buildString {
        if (value and 8 != 0) append('i')
        if (value and 4 != 0) append('o')
        if (value and 2 != 0) append('r')
        if (value and 1 != 0) append('w')
    }
}
