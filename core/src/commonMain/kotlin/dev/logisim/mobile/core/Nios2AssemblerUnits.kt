package dev.logisim.mobile.core

/**
 * The execution units of Evolution's Nios2Assembler, encoding as their setAsmInstruction():
 * custom, data transfer, arithmetic and logic (nop, mov, movhi, movi, movui, movia, subi),
 * comparison (cmpgt, cmple... rewritten as Evolution does), shift and rotate, program control
 * (bgt, ble... with swapped operands) and other control instructions.
 */
internal object Nios2AssemblerUnits : SocAssemblerUnits {
    override val architecture = SocArchitecture.NIOS_II
    private val abiNames = listOf("zero", "at", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12",
        "r13", "r14", "r15", "r16", "r17", "r18", "r19", "r20", "r21", "r22", "r23", "et", "bt", "gp", "sp", "fp", "ea", "sstat", "ra")
    override val registers = abiNames.toSet() + "pc" + (0..31).flatMap { listOf("r$it", "c$it", "ctl$it") }

    private val transferOps = listOf("ldw", "ldh", "ldhu", "ldb", "ldbu", "ldwio", "ldhio", "ldhuio", "ldbio", "ldbuio",
        "stw", "sth", "stb", "stwio", "sthio", "stbio")
    private val transferCodes = listOf(0x17, 0x0F, 0x0B, 0x07, 0x03, 0x37, 0x2F, 0x2B, 0x27, 0x23, 0x15, 0x0d, 0x05, 0x35, 0x2d, 0x25)
    private val arithmeticOps = listOf("and", "or", "xor", "nor", "add", "sub", "mul", "div", "divu", "mulxss", "mulxuu", "mulxsu",
        "andi", "ori", "xori", "andhi", "orhi", "xorhi", "addi", "subi", "muli", "nop", "mov", "movhi", "movi", "movui", "movia")
    private val arithmeticCodes = listOf(0x0e, 0x16, 0x1E, 0x06, 0x31, 0x39, 0x27, 0x18, 0x24, 0x1F, 0x07, 0x17,
        0x0C, 0x14, 0x1C, 0x2C, 0x34, 0x3C, 0x04, 0x04, 0x24)
    private val signedArithmetic = setOf(18, 19, 20)
    private val compareOps = listOf("cmpeq", "cmpne", "cmpge", "cmpgeu", "cmplt", "cmpltu", "cmpgt", "cmpgtu", "cmple", "cmpleu",
        "cmpeqi", "cmpnei", "cmpgei", "cmpgeui", "cmplti", "cmpltui", "cmpgti", "cmpgtui", "cmplei", "cmpleui")
    private val compareCodes = listOf(0x20, 0x18, 0x08, 0x28, 0x10, 0x30)
    private val shiftOps = listOf("rol", "ror", "sll", "sra", "srl", "roli", "slli", "srai", "srli")
    private val shiftCodes = listOf(0x03, 0x0B, 0x13, 0x3B, 0x1B, 0x02, 0x12, 0x3A, 0x1A)
    private val programOps = listOf("callr", "ret", "jmp", "call", "jmpi", "br", "bge", "bgeu", "blt", "bltu", "beq", "bne",
        "bgt", "bgtu", "ble", "bleu")
    private val programCodes = listOf(0x3A, 0x3A, 0x3A, 0x00, 0x01, 0x06, 0x0E, 0x2E, 0x16, 0x36, 0x26, 0x1E)
    private val otherOps = listOf("trap", "eret", "break", "bret", "rdctl", "wrctl", "flushd", "flushda", "flushi", "initd",
        "initda", "initi", "flushp", "sync")
    private val otherCodes = listOf(0x3A, 0x3A, 0x3A, 0x3A, 0x3A, 0x3A, 0x3B, 0x1B, 0x3A, 0x33, 0x13, 0x3A, 0x3A, 0x3A)
    private val otherOpx = listOf(0x2D, 0x01, 0x34, 0x09, 0x26, 0x2E, -1, -1, 0x0C, -1, -1, 0x29, 0x04, 0x36)
    override val opcodes = (listOf("custom") + transferOps + arithmeticOps + compareOps + shiftOps + programOps + otherOps).toSet()
    override val acceptedParameterTypes = SocAssembler.baseParameterTypes + AsmKind.CUSTOM_REGISTER + AsmKind.CONTROL_REGISTER
    override fun instructionSize(opcode: String) = if (opcode.lowercase() == "movia") 8 else 4

    override fun retype(tokens: List<AsmToken>) {
        for (token in tokens) if (token.type == AsmKind.REGISTER) {
            val name = token.value.lowercase()
            if (name.startsWith("ctl")) token.setType(AsmKind.CONTROL_REGISTER)
            else if (name.startsWith("c")) token.setType(AsmKind.CUSTOM_REGISTER)
        }
    }

    private fun registerIndex(name: String): Int {
        val register = name.lowercase()
        abiNames.indexOf(register).takeIf { it >= 0 }?.let { return it }
        val digits = when {
            register.startsWith("r") && register.length < 4 -> register.drop(1)
            register.startsWith("ctl") && register.length < 6 -> register.drop(3)
            register.startsWith("c") && register.length < 4 -> register.drop(1)
            else -> return -1
        }
        return digits.toUIntOrNull()?.toInt() ?: -1
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

    /** offset(base) operand of loads, stores and data-cache instructions. */
    private fun AsmInstruction.address(index: Int): Pair<Int, Int> {
        val parameter = parameter(index)
        if (parameter.size != 2 || !parameter[0].isNumber || parameter[1].type != AsmKind.BRACKETED_REGISTER) {
            fail(parameter[0], "soc_asm_address", "value" to parameter.joinToString("") { it.value })
        }
        val offset = parameter[0].number()
        if (offset >= (1 shl 15) || offset < -(1 shl 15)) failImmediate(parameter[0])
        val name = parameter[1].value.lowercase()
        // Evolution rejects custom (cN) and control (ctlN) registers as a base.
        if (name.startsWith("c") && name.length < 4 && !name.startsWith("ctl") || name.startsWith("ctl") && name.length < 6) failRegister(parameter[1])
        val base = registerIndex(parameter[1].value).takeIf { it in 0..31 } ?: failRegister(parameter[1])
        return offset to base
    }

    private fun iType(a: Int, b: Int, immediate: Int, opcode: Int) =
        (opcode and 0x3F) or ((immediate and 0xFFFF) shl 6) or ((b and 31) shl 22) or ((a and 31) shl 27)
    private fun rType(a: Int, b: Int, c: Int, opx: Int, shift: Int = 0) =
        0x3A or ((opx and 0x3F) shl 11) or ((shift and 31) shl 6) or ((c and 31) shl 17) or ((b and 31) shl 22) or ((a and 31) shl 27)

    private fun AsmInstruction.signed(value: Int, token: AsmToken) { if (value >= (1 shl 15) || value < -(1 shl 15)) failImmediate(token) }
    private fun AsmInstruction.unsigned(value: Int, token: AsmToken) { if (value >= (1 shl 16) || value < 0) failImmediate(token) }

    override fun assemble(instruction: AsmInstruction) {
        val opcode = instruction.opcode.lowercase()
        when (opcode) {
            "custom" -> instruction.words(custom(instruction))
            in transferOps -> {
                if (instruction.count != 2) instruction.failOperands(2)
                val data = instruction.register(0)
                val (offset, base) = instruction.address(1)
                instruction.words(iType(base, data, offset, transferCodes[transferOps.indexOf(opcode)]))
            }
            in arithmeticOps -> arithmetic(instruction, arithmeticOps.indexOf(opcode))
            in compareOps -> instruction.words(compare(instruction, compareOps.indexOf(opcode)))
            in shiftOps -> {
                val operation = shiftOps.indexOf(opcode)
                if (instruction.count != 3) instruction.fail(instruction.instruction, "soc_asm_instruction", "value" to opcode)
                val c = instruction.register(0)
                val a = instruction.register(1)
                if (operation >= 5) {
                    val immediate = instruction.immediate(2)
                    if (immediate > 31 || immediate < 0) instruction.failImmediate(instruction.parameter(2)[0])
                    instruction.words(rType(a, 0, c, shiftCodes[operation], immediate))
                } else instruction.words(rType(a, instruction.register(2), c, shiftCodes[operation]))
            }
            in programOps -> instruction.words(program(instruction, programOps.indexOf(opcode)))
            in otherOps -> instruction.words(other(instruction, otherOps.indexOf(opcode)))
            else -> instruction.fail(instruction.instruction, "soc_asm_instruction", "value" to opcode)
        }
    }

    private fun custom(instruction: AsmInstruction): Int {
        if (instruction.count != 4) instruction.failOperands(4)
        val n = instruction.immediate(0)
        if (n < 0 || n > 255) instruction.failImmediate(instruction.parameter(0)[0])
        fun operand(index: Int): Pair<Int, Boolean> {
            val parameter = instruction.parameter(index)
            val token = parameter[0]
            if (parameter.size != 1 || (token.type != AsmKind.REGISTER && token.type != AsmKind.CUSTOM_REGISTER)) instruction.failRegister(token)
            return (registerIndex(token.value).takeIf { it in 0..31 } ?: instruction.failRegister(token)) to (token.type == AsmKind.CUSTOM_REGISTER)
        }
        val (c, writeC) = operand(1)
        val (a, readA) = operand(2)
        val (b, readB) = operand(3)
        var opx = n and 0xFF
        if (writeC) opx = opx or (1 shl 8)
        if (readB) opx = opx or (1 shl 9)
        if (readA) opx = opx or (1 shl 10)
        return 0x32 or ((opx and 0x7FF) shl 6) or ((c and 31) shl 17) or ((b and 31) shl 22) or ((a and 31) shl 27)
    }

    private fun arithmetic(instruction: AsmInstruction, op: Int) {
        when (op) {
            21 -> { // nop
                if (instruction.count != 0) instruction.failOperands(0)
                instruction.words(rType(0, 0, 0, arithmeticCodes[4]))
            }
            26 -> { // movia rB, imm: orhi with the carry-adjusted high half, then addi
                if (instruction.count != 2) instruction.failOperands(2)
                val destination = instruction.register(0)
                val immediate = instruction.immediate(1)
                val high = (((immediate shr 16) and 0xFFFF) + ((immediate shr 15) and 1)) and 0xFFFF
                instruction.words(iType(0, destination, high, 0x34), iType(destination, destination, immediate and 0xFFFF, 0x04))
            }
            22 -> { // mov rC, rA
                if (instruction.count != 2) instruction.failOperands(2)
                val destination = instruction.register(0)
                instruction.words(rType(instruction.register(1), 0, destination, arithmeticCodes[4]))
            }
            23, 24, 25 -> { // movhi, movi, movui
                if (instruction.count != 2) instruction.failOperands(2)
                val destination = instruction.register(0)
                val immediate = instruction.immediate(1)
                val token = instruction.parameter(1)[0]
                val code = if (op == 24) { instruction.signed(immediate, token); 0x04 }
                    else { instruction.unsigned(immediate, token); if (op == 23) 0x34 else 0x14 }
                instruction.words(iType(0, destination, immediate, code))
            }
            else -> {
                if (instruction.count != 3) instruction.failOperands(3)
                val destination = instruction.register(0)
                val source = instruction.register(1)
                if (op >= 12) {
                    val immediate = instruction.immediate(2)
                    val token = instruction.parameter(2)[0]
                    // subi encodes addi with the immediate unchanged, as Evolution does.
                    if (op in signedArithmetic) instruction.signed(immediate, token) else instruction.unsigned(immediate, token)
                    instruction.words(iType(source, destination, immediate, arithmeticCodes[op]))
                } else instruction.words(rType(source, instruction.register(2), destination, arithmeticCodes[op]))
            }
        }
    }

    private fun compare(instruction: AsmInstruction, op: Int): Int {
        if (instruction.count != 3) instruction.failOperands(3)
        var operation = op
        val destination = instruction.register(0)
        var a = instruction.register(1)
        var b = 0
        var immediate = 0
        if (operation >= 10) immediate = instruction.immediate(2) else b = instruction.register(2)
        when (operation) {
            6 -> { operation = 4; a = b.also { b = a } }     // cmpgt -> cmplt
            16 -> { operation = 12; immediate++ }            // cmpgti -> cmpgei
            7 -> { operation = 5; a = b.also { b = a } }     // cmpgtu -> cmpltu
            17 -> { operation = 13; immediate++ }            // cmpgtui -> cmpgeui
            8 -> { operation = 2; a = b.also { b = a } }     // cmple -> cmpge
            18 -> { operation = 14; immediate++ }            // cmplei -> cmplti
            9 -> { operation = 3; a = b.also { b = a } }     // cmpleu -> cmpgeu
            19 -> { operation = 15; immediate++ }            // cmpleui -> cmpltui
        }
        val token = instruction.parameter(2)[0]
        if (operation in setOf(10, 11, 12, 14)) instruction.signed(immediate, token) else instruction.unsigned(immediate, token)
        return if (operation >= 10) iType(a, destination, immediate, compareCodes[operation - 10])
        else rType(a, b, destination, compareCodes[operation])
    }

    private fun program(instruction: AsmInstruction, op: Int): Int {
        var operation = op
        var a = 0
        var b = 0
        var immediate = 0
        val pc = instruction.programCounter
        fun relative(token: AsmToken, value: Int): Int {
            val offset = value.toUInt().toLong() - pc - 4L
            if (offset >= (1L shl 15) || offset < -(1L shl 15)) instruction.failImmediate(token)
            return offset.toInt()
        }
        when (operation) {
            2, 0 -> { // jmp, callr
                if (instruction.count != 1) instruction.failOperands(1)
                a = instruction.register(0)
            }
            1 -> if (instruction.count != 0) instruction.failOperands(0)
            3, 4 -> { // call, jmpi
                if (instruction.count != 1) instruction.failOperands(1)
                immediate = instruction.immediate(0) shr 2
                if (immediate >= (1 shl 26) || immediate < 0) instruction.failImmediate(instruction.parameter(0)[0])
            }
            5 -> { // br
                if (instruction.count != 1) instruction.failOperands(1)
                immediate = relative(instruction.parameter(0)[0], instruction.immediate(0))
            }
            else -> {
                if (instruction.count != 3) instruction.failOperands(3)
                a = instruction.register(0)
                b = instruction.register(1)
                immediate = relative(instruction.parameter(2)[0], instruction.immediate(2))
            }
        }
        when (operation) {
            12 -> { operation = 8; a = b.also { b = a } }   // bgt -> blt
            13 -> { operation = 9; a = b.also { b = a } }   // bgtu -> bltu
            14 -> { operation = 6; a = b.also { b = a } }   // ble -> bge
            15 -> { operation = 7; a = b.also { b = a } }   // bleu -> bgeu
        }
        return when (operation) {
            0 -> rType(a, 0, 0x1f, 0x1d)
            1 -> rType(0x1f, 0, 0, 0x05)
            2 -> rType(a, 0, 0, 0x0d)
            3, 4 -> (programCodes[operation] and 0x3F) or ((immediate and 0x3FFFFFF) shl 6)
            else -> iType(a, b, immediate, programCodes[operation])
        }
    }

    private fun other(instruction: AsmInstruction, operation: Int): Int {
        var a = 0
        var immediate = 0
        when (operation) {
            2, 0 -> when (instruction.count) { // break, trap [imm5]
                0 -> Unit
                1 -> {
                    immediate = instruction.immediate(0)
                    if (immediate > 0x1F || immediate < 0) instruction.failImmediate(instruction.parameter(0)[0])
                }
                else -> instruction.failOperands(1)
            }
            5, 4 -> { // wrctl ctlN, rA / rdctl rC, ctlN
                if (instruction.count != 2) instruction.failOperands(2)
                val registerIndex = if (operation == 5) 1 else 0
                a = instruction.register(registerIndex)
                val control = instruction.parameter(1 - registerIndex)
                if (control.size != 1 || control[0].type != AsmKind.CONTROL_REGISTER) instruction.failRegister(control[0])
                immediate = registerIndex(control[0].value).takeIf { it in 0..31 } ?: instruction.failRegister(control[0])
            }
            11, 8 -> { // initi, flushi rA
                if (instruction.count != 1) instruction.failOperands(1)
                a = instruction.register(0)
            }
            9, 10, 7, 6 -> { // initd, initda, flushda, flushd offset(rA)
                if (instruction.count != 1) instruction.failOperands(1)
                val (offset, base) = instruction.address(0)
                immediate = offset
                a = base
            }
            else -> if (instruction.count != 0) instruction.failOperands(0)
        }
        return when (operation) {
            0 -> rType(0, 0, 0x1d, 0x2d, immediate)
            1 -> rType(0x1d, 0x1e, 0, 0x01)
            2 -> rType(0, 0, 0x1e, 0x34, immediate)
            3 -> rType(0x1E, 0, 0x1E, 0x09)
            4 -> rType(0, 0, a, 0x26, immediate)
            5 -> rType(a, 0, 0, 0x2E, immediate)
            // flushp has no operand; Evolution reuses the unit's last rA, which is 0 in a fresh unit.
            12, 11, 8 -> rType(a, 0, 0, otherOpx[operation])
            13 -> rType(0, 0, 0, 0x36)
            else -> iType(a, 0, immediate, otherCodes[operation])
        }
    }
}
