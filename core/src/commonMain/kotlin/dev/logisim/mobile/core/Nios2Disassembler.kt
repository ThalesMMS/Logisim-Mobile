package dev.logisim.mobile.core

/**
 * The trace text of one Nios II word, as the desktop execution units write it: the mnemonic padded to 10 columns
 * and the register names of [Nios2Cpu.registerNames] ("addi      r5,zero,7"), with the pseudo-instructions
 * Evolution chooses from the encoding alone (movi, movui, movhi, mov, nop). It reads no register, so a program
 * listing and the instruction trace show the same text. Returns null for a word the processor rejects.
 */
object Nios2Disassembler {
    private const val FIELD = 10

    fun format(instruction: UInt): String? {
        val names = Nios2Cpu.registerNames
        val bits = instruction.toInt()
        val opcode = bits and 63
        val ra = bits ushr 27 and 31
        val rb = bits ushr 22 and 31
        val rc = bits ushr 17 and 31
        val opx = bits ushr 11 and 63
        val imm5 = bits ushr 6 and 31
        val imm16 = bits ushr 6 and 65535
        val signedImmediate = (imm16 shl 16) shr 16
        fun text(mnemonic: String, arguments: String = "") = if (arguments.isEmpty()) mnemonic else mnemonic.padEnd(FIELD) + arguments
        fun immediate(mnemonic: String, signed: Boolean) =
            text(mnemonic, "${names[rb]},${names[ra]},${if (signed) signedImmediate else imm16}")
        return when (opcode) {
            0x00, 0x01 -> text(if (opcode == 0) "call" else "jmpi", ((instruction shr 6) shl 2).toString())
            0x06, 0x0E, 0x2E, 0x16, 0x36, 0x26, 0x1E -> {
                val mnemonic = when (opcode) {
                    0x06 -> if (ra == 0 && rb == 0) "br" else return null
                    0x0E -> "bge"
                    0x2E -> "bgeu"
                    0x16 -> "blt"
                    0x36 -> "bltu"
                    0x26 -> "beq"
                    else -> "bne"
                }
                val offset = signedImmediate + 4
                text(mnemonic, (if (opcode == 0x06) "" else "${names[ra]},${names[rb]},") +
                    "pc${if (offset >= 0) "+" else ""}$offset")
            }
            0x04 -> if (ra == 0) text("movi", "${names[rb]},$signedImmediate") else immediate("addi", true)
            0x0C -> immediate("andi", false)
            0x14 -> if (ra == 0) text("movui", "${names[rb]},$imm16") else immediate("ori", false)
            0x1C -> immediate("xori", false)
            0x24 -> immediate("muli", true)
            0x2C -> immediate("andhi", false)
            0x34 -> if (ra == 0) text("movhi", "${names[rb]},$imm16") else immediate("orhi", false)
            0x3C -> immediate("xorhi", false)
            0x20 -> immediate("cmpeqi", true)
            0x18 -> immediate("cmpnei", true)
            0x08 -> immediate("cmpgei", true)
            0x28 -> immediate("cmpgeui", false)
            0x10 -> immediate("cmplti", true)
            0x30 -> immediate("cmpltui", false)
            in loadStoreNames -> text(loadStoreNames.getValue(opcode), "${names[rb]},$signedImmediate(${names[ra]})")
            0x32 -> {
                val custom = bits ushr 6 and 0x7FF
                fun register(flag: Int, index: Int) = "${if (custom and flag != 0) "c" else "r"}$index"
                text("custom", "${custom and 255},${register(0x100, rc)},${register(0x400, ra)},${register(0x200, rb)}")
            }
            0x13, 0x1B, 0x33, 0x3B -> if (rb != 0) null else
                text(when (opcode) { 0x13 -> "initda"; 0x1B -> "flushda"; 0x33 -> "initd"; else -> "flushd" }, "$signedImmediate(${names[ra]})")
            0x3A -> rType(names, opx, imm5, ra, rb, rc)
            else -> null
        }
    }

    private fun rType(names: List<String>, opx: Int, imm5: Int, ra: Int, rb: Int, rc: Int): String? {
        fun text(mnemonic: String, arguments: String = "") = if (arguments.isEmpty()) mnemonic else mnemonic.padEnd(FIELD) + arguments
        val registers = "${names[rc]},${names[ra]},${names[rb]}"
        when (opx) {
            0x02, 0x12, 0x3A, 0x1A -> return if (rb != 0) null else
                text(when (opx) { 0x02 -> "roli"; 0x12 -> "slli"; 0x3A -> "srai"; else -> "srli" }, "${names[rc]},${names[ra]},$imm5")
            0x2D -> return if (ra == 0 && rb == 0 && rc == 29) text("trap", if (imm5 == 0) "" else imm5.toString()) else null
            0x34 -> return if (ra == 0 && rb == 0 && rc == 30) text("break", if (imm5 == 0) "" else imm5.toString()) else null
            0x26 -> return if (ra == 0 && rb == 0) text("rdctl", "${names[rc]},ctl$imm5") else null
            0x2E -> return if (rb == 0 && rc == 0) text("wrctl", "ctl$imm5,${names[ra]}") else null
        }
        if (imm5 != 0) return null
        registerOperations[opx]?.let { mnemonic ->
            if (mnemonic != "add") return text(mnemonic, registers)
            return when {
                ra == 0 && rb == 0 && rc == 0 -> "nop"
                rb == 0 -> text("mov", "${names[rc]},${names[ra]}")
                else -> text("add", registers)
            }
        }
        return when (opx) {
            0x1D -> if (rc == 31 && rb == 0) text("callr", names[ra]) else null
            0x05 -> if (ra == 31 && rb == 0 && rc == 0) "ret" else null
            0x0D -> if (rb == 0 && rc == 0) text("jmp", names[ra]) else null
            0x01 -> if (ra == 29 && rb == 30 && rc == 0) "eret" else null
            0x09 -> if (ra == 30 && rb == 0 && rc == 30) "bret" else null
            0x04, 0x0C, 0x29 -> if (rb == 0 && rc == 0) {
                if (opx == 0x04) "flushp" else text(if (opx == 0x0C) "flushi" else "initi", names[ra])
            } else null
            0x36 -> if (ra == 0 && rb == 0 && rc == 0) "sync" else null
            else -> null
        }
    }

    private val registerOperations = mapOf(0x0E to "and", 0x16 to "or", 0x1E to "xor", 0x06 to "nor", 0x31 to "add",
        0x39 to "sub", 0x27 to "mul", 0x18 to "div", 0x24 to "divu", 0x1F to "mulxss", 0x07 to "mulxuu", 0x17 to "mulxsu",
        0x20 to "cmpeq", 0x08 to "cmpge", 0x28 to "cmpgeu", 0x10 to "cmplt", 0x30 to "cmpltu", 0x03 to "rol",
        0x0B to "ror", 0x13 to "sll", 0x3B to "sra", 0x1B to "srl")

    internal val loadStoreNames = mapOf(0x17 to "ldw", 0x0F to "ldh", 0x0B to "ldhu", 0x07 to "ldb", 0x03 to "ldbu",
        0x37 to "ldwio", 0x2F to "ldhio", 0x2B to "ldhuio", 0x27 to "ldbio", 0x23 to "ldbuio",
        0x15 to "stw", 0x0D to "sth", 0x05 to "stb", 0x35 to "stwio", 0x2D to "sthio", 0x25 to "stbio")
}
