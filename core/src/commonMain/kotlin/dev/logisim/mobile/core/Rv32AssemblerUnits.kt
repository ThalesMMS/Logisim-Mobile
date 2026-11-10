package dev.logisim.mobile.core

/**
 * The execution units of Evolution's RV32imAssembler, encoding as their setAsmInstruction():
 * integer register-immediate (with nop, li, mv, not, seqz), register-register (with snez),
 * control transfer (with j, jr, ret, beqz, bnez), loads and stores, memory ordering (not
 * assembled by Evolution), environment calls, the M extension and Zicsr (with csrw...).
 */
internal object Rv32AssemblerUnits : SocAssemblerUnits {
    override val architecture = SocArchitecture.RV32IM
    private val sprs = listOf(0xF11, 0xF12, 0xF13, 0xF14, 0x300, 0x301, 0x304, 0x305,
        0x340, 0x341, 0x342, 0x343, 0x344, 0x7B0, 0x7B1, 0x7A0, 0x7A1, 0x7A2, 0x7A4)
    private val sprNames = listOf("mvendorid", "marchid", "mimpid", "mhartid", "mstatus", "misa", "mie", "mtvec",
        "mscratch", "mepc", "mcause", "mtval", "mip", "dcsr", "dpc", "tselect", "tdata1", "tdata2", "tinfo")
    override val registers = Rv32Disassembler.abiNames.toSet() + sprNames + "pc" + (0..31).map { "x$it" }
    private val immediateOps = listOf("addi", "slli", "slti", "sltiu", "xori", "srli", "ori", "andi", "srai",
        "lui", "auipc", "nop", "li", "seqz", "not", "mv")
    private val registerOps = listOf("add", "sll", "slt", "sltu", "xor", "srl", "or", "and", "sub", "sra", "snez")
    private val controlOps = listOf("beq", "bne", "jal", "jalr", "blt", "bge", "bltu", "bgeu", "j", "jr", "ret", "bnez", "beqz")
    private val memoryOps = listOf("lb", "lh", "lw", "lbu", "lhu", "sb", "sh", "sw")
    private val orderingOps = listOf("fence", "fence.tso")
    private val environmentOps = listOf("ecall", "ebreak", "mret")
    private val multiplyOps = listOf("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")
    private val csrOps = listOf("csrrw", "csrrs", "csrrc", "csrrwi", "csrrsi", "csrrci", "csrw", "csrs", "csrc", "csrwi", "csrsi", "csrci")
    override val opcodes = (immediateOps + registerOps + controlOps + memoryOps + orderingOps + environmentOps + multiplyOps + csrOps).toSet()
    override val acceptedParameterTypes = SocAssembler.baseParameterTypes
    override fun instructionSize(opcode: String) = 4

    private fun registerIndex(name: String): Int {
        val register = name.lowercase()
        Rv32Disassembler.abiNames.indexOf(register).takeIf { it >= 0 }?.let { return it }
        if (register.startsWith("x") && register.length < 4) return register.drop(1).toUIntOrNull()?.toInt() ?: -1
        return -1
    }

    private fun AsmInstruction.register(index: Int): Int {
        val parameter = parameter(index)
        if (parameter.size != 1 || parameter[0].type != AsmKind.REGISTER) failRegister(parameter[0])
        return registerIndex(parameter[0].value).takeIf { it in 0..31 } ?: failRegister(parameter[0])
    }

    private fun AsmInstruction.immediate(index: Int): Int {
        val parameter = parameter(index)
        if (parameter.size != 1 || !parameter[0].isNumber) failImmediate(parameter[0])
        return parameter[0].number()
    }

    private fun AsmInstruction.lastToken() = parameter(count - 1)[0]

    private fun iType(opcode: Int, rd: Int, funct3: Int, rs: Int, immediate: Int) =
        (opcode and 0x7F) or ((rd and 31) shl 7) or ((funct3 and 7) shl 12) or ((rs and 31) shl 15) or ((immediate and 0xFFF) shl 20)
    private fun rType(opcode: Int, rd: Int, funct3: Int, rs1: Int, rs2: Int, funct7: Int) =
        (opcode and 0x7F) or ((rd and 31) shl 7) or ((funct3 and 7) shl 12) or ((rs1 and 31) shl 15) or ((rs2 and 31) shl 20) or ((funct7 and 0x7F) shl 25)
    private fun sType(opcode: Int, rs1: Int, rs2: Int, funct3: Int, immediate: Int) =
        (opcode and 0x7F) or ((funct3 and 7) shl 12) or ((rs1 and 31) shl 15) or ((rs2 and 31) shl 20) or
            ((immediate and 31) shl 7) or (((immediate shr 5) and 0x7F) shl 25)
    private fun jType(opcode: Int, rd: Int, immediate: Int) = (opcode and 0x7F) or ((rd and 31) shl 7) or
        (((immediate shr 12) and 0xFF) shl 12) or (((immediate shr 11) and 1) shl 20) or
        (((immediate shr 1) and 0x3FF) shl 21) or (((immediate shr 20) and 1) shl 31)
    private fun bType(opcode: Int, funct3: Int, rs1: Int, rs2: Int, immediate: Int) = (opcode and 0x7F) or
        ((funct3 and 7) shl 12) or ((rs1 and 31) shl 15) or ((rs2 and 31) shl 20) or (((immediate shr 11) and 1) shl 7) or
        (((immediate shr 1) and 0xF) shl 8) or (((immediate shr 5) and 0x3F) shl 25) or (((immediate shr 12) and 1) shl 31)

    override fun assemble(instruction: AsmInstruction) {
        val opcode = instruction.opcode.lowercase()
        instruction.words(when (opcode) {
            in immediateOps -> registerImmediate(instruction, immediateOps.indexOf(opcode))
            in registerOps -> registerRegister(instruction, registerOps.indexOf(opcode))
            in controlOps -> control(instruction, controlOps.indexOf(opcode))
            in memoryOps -> loadStore(instruction, memoryOps.indexOf(opcode))
            // RV32imMemoryOrderingInstructions: "not supported yet" by Evolution's assembler.
            in orderingOps -> instruction.fail(instruction.instruction, "soc_asm_instruction", "value" to opcode)
            in environmentOps -> {
                if (instruction.count != 0) instruction.failOperands(0)
                iType(0x73, 0, 0, 0, if (opcode == "mret") 0x302 else environmentOps.indexOf(opcode))
            }
            in multiplyOps -> {
                if (instruction.count != 3) instruction.failOperands(3)
                rType(0x33, instruction.register(0), multiplyOps.indexOf(opcode), instruction.register(1), instruction.register(2), 1)
            }
            in csrOps -> csr(instruction, csrOps.indexOf(opcode))
            else -> instruction.fail(instruction.instruction, "soc_asm_instruction", "value" to opcode)
        })
    }

    // RV32imIntegerRegisterImmediateInstructions; operation indices follow its AsmOpcodes.
    private fun registerImmediate(instruction: AsmInstruction, op: Int): Int {
        var operation = op
        var destination = 0
        var source = 0
        var immediate = 0
        when (operation) {
            11 -> { if (instruction.count != 0) instruction.failOperands(0); operation = 0 } // nop
            9, 10, 12 -> { // lui, auipc, li: rd, imm
                if (instruction.count != 2) instruction.failOperands(2)
                val token = instruction.parameter(1)
                destination = instruction.register(0)
                immediate = instruction.immediate(1)
                if (operation == 12) operation = 0
                if (operation == 9 && token[0].isLabel) immediate = (immediate shr 12) and 0xFFFFF
                if (operation == 10 && token[0].isLabel) {
                    immediate = (immediate.toLong() - instruction.programCounter).toInt()
                    immediate = (immediate shr 12) and 0xFFFFF
                }
            }
            15, 14, 13 -> { // mv, not, seqz: rd, rs
                if (instruction.count != 2) instruction.failOperands(2)
                destination = instruction.register(0)
                source = instruction.register(1)
                when (operation) {
                    15 -> { immediate = 0; operation = 0 }
                    14 -> { immediate = -1; operation = 4 }
                    else -> { immediate = 1; operation = 3 }
                }
            }
            else -> {
                if (instruction.count != 3) instruction.failOperands(3)
                destination = instruction.register(0)
                source = instruction.register(1)
                immediate = instruction.immediate(2)
            }
        }
        return when (operation) {
            0, 2, 3, 7, 6, 4 -> {
                if (immediate > 2047 || immediate < -2048) instruction.failImmediate(instruction.lastToken())
                iType(0x13, destination, operation, source, immediate)
            }
            1, 5, 8 -> {
                if (immediate > 31 || immediate < 0) instruction.failImmediate(instruction.lastToken())
                if (operation == 8) iType(0x13, destination, 5, source, immediate or (1 shl 10))
                else iType(0x13, destination, operation, source, immediate)
            }
            else -> {
                if (immediate < 0 || immediate >= (1 shl 20)) instruction.failImmediate(instruction.lastToken())
                (if (operation == 9) 0x37 else 0x17) or ((destination and 31) shl 7) or ((immediate and 0xFFFFF) shl 12)
            }
        }
    }

    // RV32imIntegerRegisterRegisterOperations.
    private fun registerRegister(instruction: AsmInstruction, op: Int): Int {
        val snez = op == 10
        if (instruction.count != (if (snez) 2 else 3)) instruction.failOperands(if (snez) 2 else 3)
        val destination = instruction.register(0)
        var source1 = instruction.register(1)
        val source2 = if (snez) source1 else instruction.register(2)
        var operation = op
        if (snez) { source1 = 0; operation = 3 }
        val funct7 = if (operation == 8 || operation == 9) 0x20 else 0
        val funct3 = when (operation) { 8 -> 0; 9 -> 5; else -> operation }
        return rType(0x33, destination, funct3, source1, source2, funct7)
    }

    // RV32imControlTransferInstructions: BEQ, BNE, JAL, JALR, BLT, BGE, BLTU, BGEU, J, JR, RET, BNEZ, BEQZ.
    private fun control(instruction: AsmInstruction, op: Int): Int {
        var operation = op
        var destination = 0
        var source1 = 0
        var source2 = 0
        var immediate = 0
        when (operation) {
            10 -> { // ret
                if (instruction.count != 0) instruction.failOperands(0)
                operation = 3
                source1 = 1
            }
            12, 11, 9, 2 -> { // beqz, bnez, jr, jal: reg[, imm]
                if (instruction.count == 0 || instruction.count > 2) instruction.failOperands(2)
                destination = instruction.register(0)
                if (instruction.count == 2) immediate = instruction.immediate(1)
                if (operation == 9) { source1 = destination; destination = 0; operation = 3 }
                if (operation == 12 || operation == 11) {
                    source1 = destination
                    destination = 0
                    operation = if (operation == 12) 0 else 1
                }
            }
            8 -> { // j
                if (instruction.count != 1) instruction.failOperands(1)
                immediate = instruction.immediate(0)
                operation = 2
            }
            else -> {
                if (instruction.count < 2 || instruction.count > 3) instruction.failOperands(3)
                destination = instruction.register(0)
                source1 = instruction.register(1)
                source2 = source1
                if (instruction.count == 3) immediate = instruction.immediate(2)
                if (operation != 3) { source2 = source1; source1 = destination; destination = 0 }
            }
        }
        return when (operation) {
            2 -> {
                immediate = (immediate.toLong() - instruction.programCounter).toInt()
                if (immediate >= (1 shl 19) || immediate < -(1 shl 19)) instruction.failImmediate(instruction.lastToken())
                jType(0x6F, destination, immediate)
            }
            3 -> {
                if (immediate >= (1 shl 10) || immediate < -(1 shl 10)) instruction.failImmediate(instruction.lastToken())
                iType(0x67, destination, 0, source1, immediate)
            }
            else -> {
                immediate = (immediate.toLong() - instruction.programCounter).toInt()
                if (immediate >= (1 shl 11) || immediate < -(1 shl 11)) instruction.failImmediate(instruction.lastToken())
                bType(0x63, operation, source1, source2, immediate)
            }
        }
    }

    // RV32imLoadAndStoreInstructions: LB, LH, LW, LBU, LHU, SB, SH, SW with offset(base).
    private fun loadStore(instruction: AsmInstruction, operation: Int): Int {
        if (instruction.count != 2) instruction.failOperands(2)
        val first = instruction.parameter(0)
        if (first.size != 1 || first[0].type != AsmKind.REGISTER) instruction.failRegister(first[0])
        val address = instruction.parameter(1)
        if (address.size != 2 || !address[0].isNumber || address[1].type != AsmKind.BRACKETED_REGISTER) {
            instruction.fail(address[0], "soc_asm_address", "value" to address.joinToString("") { it.value })
        }
        val register = registerIndex(first[0].value).takeIf { it in 0..31 } ?: instruction.failRegister(first[0])
        val base = registerIndex(address[1].value).takeIf { it in 0..31 } ?: instruction.failRegister(address[1])
        val immediate = address[0].number()
        if (immediate >= (1 shl 11) || immediate < -(1 shl 11)) instruction.failImmediate(address[0])
        return if (operation >= 5) sType(0x23, base, register, operation - 5, immediate)
        else iType(0x03, register, if (operation == 3 || operation == 4) operation + 1 else operation, base, immediate)
    }

    // RV32im_Zicsr_ExtensionInstructions: csrrw... rd, csr, rs|uimm and csrw... csr, rs|uimm.
    private fun csr(instruction: AsmInstruction, op: Int): Int {
        fun unknown(): Nothing = instruction.fail(instruction.instruction, "soc_asm_instruction", "value" to instruction.opcode)
        val short = instruction.count == 2
        if (!short && instruction.count != 3) unknown()
        if (short && op < 6 || !short && op > 5) unknown()
        val operation = if (short) op - 6 else op
        var word = 0x73
        if (!short) {
            val destination = instruction.parameter(0)
            if (destination.size != 1 || destination[0].type != AsmKind.REGISTER) unknown()
            val register = registerIndex(destination[0].value).takeIf { it >= 0 } ?: unknown()
            word = word or (register shl 7)
        }
        val csr = instruction.parameter(if (short) 0 else 1)
        if (csr.size != 1) unknown()
        word = word or when {
            csr[0].type == AsmKind.REGISTER -> sprs[sprNames.indexOf(csr[0].value.lowercase()).takeIf { it >= 0 } ?: unknown()] shl 20
            csr[0].isNumber -> csr[0].number() shl 20
            else -> unknown()
        }
        val source = instruction.parameter(if (short) 1 else 2)
        if (source.size != 1) unknown()
        word = word or when {
            source[0].type == AsmKind.REGISTER -> (registerIndex(source[0].value).takeIf { it >= 0 } ?: unknown()) shl 15
            source[0].isNumber -> (source[0].number().takeIf { it in 0..31 } ?: unknown()) shl 15
            else -> unknown()
        }
        return word or ((if (operation < 3) operation + 1 else operation + 2) shl 12)
    }
}
