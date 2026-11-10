package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

data class Rv32CpuState(
    val pc: UInt = 0u,
    val registers: List<UInt> = List(32) { 0u },
    val knownRegisters: UInt = 1u,
    val csrs: Map<Int, UInt> = Rv32Cpu.implementedCsrs.associateWith { 0u },
    val stopped: Boolean = false,
    val instructions: Long = 0,
    val trace: List<SocInstructionTrace> = emptyList(),
) {
    init { require(registers.size == 32) }
    fun register(index: Int): UInt = if (index == 0) 0u else registers[index]
    fun withRegister(index: Int, value: UInt): Rv32CpuState {
        require(index in 0..31)
        if (index == 0) return this
        return copy(registers = registers.mapIndexed { i, old -> if (i == index) value else old },
            knownRegisters = knownRegisters or (1u shl index))
    }
}

data class Rv32StepResult(val state: Rv32CpuState, val trace: SocInstructionTrace?, val error: String? = null, val notice: LocalizedText? = error?.let(LocalizedText::literal))

/** RV32I/M and the CSR/interrupt subset implemented by the desktop simulator. */
object Rv32Cpu {
    val implementedCsrs = listOf(0xF11, 0xF12, 0xF13, 0xF14, 0x300, 0x301, 0x304, 0x305,
        0x340, 0x341, 0x342, 0x343, 0x344, 0x7B0, 0x7B1, 0x7A0, 0x7A1, 0x7A2, 0x7A4)
    private val readOnlyCsrs = setOf(0xF11, 0xF12, 0xF13, 0xF14, 0x7A2)

    fun reset(entry: UInt = 0u, previous: Rv32CpuState? = null): Rv32CpuState = Rv32CpuState(
        pc = entry, registers = previous?.registers ?: List(32) { 0u })

    fun step(
        previous: Rv32CpuState,
        memory: SocCpuMemory,
        machineExternalInterrupt: Boolean = false,
        beforeFetch: (UInt) -> Boolean = { true },
    ): Rv32StepResult {
        if (previous.stopped) return Rv32StepResult(previous, null)
        var state = previous.copy(csrs = previous.csrs + (0x344 to
            if (machineExternalInterrupt) (previous.csrs[0x344] ?: 0u) or 0x800u
            else (previous.csrs[0x344] ?: 0u) and 0x800u.inv()))
        if (((state.csrs[0x300] ?: 0u) and 8u) != 0u &&
            ((state.csrs[0x304] ?: 0u) and 0x800u) != 0u && machineExternalInterrupt) {
            val status = state.csrs[0x300] ?: 0u
            state = state.copy(pc = state.csrs[0x305] ?: 0u, csrs = state.csrs + mapOf(
                0x341 to state.pc, 0x342 to 0x8000000Bu,
                0x300 to ((status and 8u.inv()) or (if ((status and 8u) != 0u) 0x80u else 0u)),
            ))
        }
        val pc = state.pc
        if (!beforeFetch(pc)) return Rv32StepResult(state.copy(stopped = true), null)
        val fetched = memory.read(pc, SocAccessSize.WORD)
        if (!fetched.successful) return failed(state, pc, 0u, "fetch", "Instruction fetch: ${fetched.error}", false, message("soc_instruction_fetch", "error" to fetched.error?.notice))
        val instruction = fetched.data
        val bits = instruction.toInt()
        val opcode = bits and 0x7F
        val rd = (bits ushr 7) and 31
        val function = (bits ushr 12) and 7
        val rs1 = (bits ushr 15) and 31
        val rs2 = (bits ushr 20) and 31
        val function7 = bits ushr 25
        val a = state.register(rs1)
        val b = state.register(rs2)
        val immediate = bits shr 20
        var nextPc = pc + 4u
        var value: UInt? = null
        var mnemonic = "invalid"
        var diagnostic: String? = null
        var notice: LocalizedText? = null
        var error: String? = null
        when (opcode) {
            0x37 -> { mnemonic = "lui"; value = instruction and 0xFFFFF000u }
            0x17 -> { mnemonic = "auipc"; value = pc + (instruction and 0xFFFFF000u) }
            0x6F -> {
                mnemonic = "jal"
                val offset = ((bits shr 31) shl 20) or ((bits ushr 12 and 0xFF) shl 12) or
                    ((bits ushr 20 and 1) shl 11) or ((bits ushr 21 and 0x3FF) shl 1)
                value = nextPc; nextPc = pc + offset.toUInt()
            }
            0x67 -> if (function == 0) {
                mnemonic = "jalr"; value = nextPc; nextPc = (a + immediate.toUInt()) and 1u.inv()
            }
            0x63 -> {
                val take = when (function) {
                    0 -> { mnemonic = "beq"; a == b }
                    1 -> { mnemonic = "bne"; a != b }
                    4 -> { mnemonic = "blt"; a.toInt() < b.toInt() }
                    5 -> { mnemonic = "bge"; a.toInt() >= b.toInt() }
                    6 -> { mnemonic = "bltu"; a < b }
                    7 -> { mnemonic = "bgeu"; a >= b }
                    else -> false
                }
                val offset = ((bits shr 31) shl 12) or ((bits ushr 7 and 1) shl 11) or
                    ((bits ushr 25 and 0x3F) shl 5) or ((bits ushr 8 and 15) shl 1)
                if (take) nextPc = pc + offset.toUInt()
            }
            0x13 -> when (function) {
                0 -> { mnemonic = "addi"; value = a + immediate.toUInt() }
                2 -> { mnemonic = "slti"; value = boolean(a.toInt() < immediate) }
                3 -> { mnemonic = "sltiu"; value = boolean(a < immediate.toUInt()) }
                4 -> { mnemonic = "xori"; value = a xor immediate.toUInt() }
                6 -> { mnemonic = "ori"; value = a or immediate.toUInt() }
                7 -> { mnemonic = "andi"; value = a and immediate.toUInt() }
                1 -> if (function7 == 0) { mnemonic = "slli"; value = a shl rs2 }
                5 -> when (function7) {
                    0 -> { mnemonic = "srli"; value = a shr rs2 }
                    32 -> { mnemonic = "srai"; value = (a.toInt() shr rs2).toUInt() }
                }
            }
            0x33 -> if (function7 == 1) {
                mnemonic = arrayOf("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")[function]
                value = when (function) {
                    0 -> (a.toULong() * b.toULong()).toUInt()
                    1 -> ((a.toInt().toLong() * b.toInt().toLong()) shr 32).toUInt()
                    2 -> ((a.toInt().toLong() * b.toLong()) shr 32).toUInt()
                    3 -> ((a.toULong() * b.toULong()) shr 32).toUInt()
                    4 -> if (b == 0u) UInt.MAX_VALUE else (a.toInt().toLong() / b.toInt().toLong()).toUInt()
                    5 -> if (b == 0u) UInt.MAX_VALUE else a / b
                    6 -> if (b == 0u) a else (a.toInt().toLong() % b.toInt().toLong()).toUInt()
                    else -> if (b == 0u) a else a % b
                }
            } else if (function7 == 0 || (function7 == 32 && function in listOf(0, 5))) {
                val shift = (b and 31u).toInt()
                when (function) {
                    0 -> if (function7 == 32) { mnemonic = "sub"; value = a - b }
                        else { mnemonic = "add"; value = a + b }
                    1 -> { mnemonic = "sll"; value = a shl shift }
                    2 -> { mnemonic = "slt"; value = boolean(a.toInt() < b.toInt()) }
                    3 -> { mnemonic = "sltu"; value = boolean(a < b) }
                    4 -> { mnemonic = "xor"; value = a xor b }
                    5 -> if (function7 == 32) { mnemonic = "sra"; value = (a.toInt() shr shift).toUInt() }
                        else { mnemonic = "srl"; value = a shr shift }
                    6 -> { mnemonic = "or"; value = a or b }
                    7 -> { mnemonic = "and"; value = a and b }
                }
            }
            0x03 -> {
                val size = when (function) { 0, 4 -> SocAccessSize.BYTE; 1, 5 -> SocAccessSize.HALF_WORD; 2 -> SocAccessSize.WORD; else -> null }
                if (size != null) {
                    mnemonic = when (function) { 0 -> "lb"; 1 -> "lh"; 2 -> "lw"; 4 -> "lbu"; else -> "lhu" }
                    val reply = memory.read(a + immediate.toUInt(), size)
                    if (!reply.successful) { error = "Data read: ${reply.error}"; notice = message("soc_data_read", "error" to reply.error?.notice) }
                    else value = when (function) {
                        0 -> (reply.data.toInt() shl 24 shr 24).toUInt()
                        1 -> (reply.data.toInt() shl 16 shr 16).toUInt()
                        4 -> reply.data and 255u
                        5 -> reply.data and 65535u
                        else -> reply.data
                    }
                }
            }
            0x23 -> {
                val size = when (function) { 0 -> SocAccessSize.BYTE; 1 -> SocAccessSize.HALF_WORD; 2 -> SocAccessSize.WORD; else -> null }
                if (size != null) {
                    mnemonic = when (function) { 0 -> "sb"; 1 -> "sh"; else -> "sw" }
                    val offset = ((bits shr 25) shl 5) or (bits ushr 7 and 31)
                    val reply = memory.write(a + offset.toUInt(), b, size)
                    if (!reply.successful) { error = "Data write: ${reply.error}"; notice = message("soc_data_write", "error" to reply.error?.notice) }
                }
            }
            0x0F -> if (function == 0) {
                mnemonic = if ((bits ushr 20 and 0xFFF) == 0x833) "fence.tso" else "fence"
                diagnostic = "Memory ordering is a no-op in the desktop's single-threaded model."
                notice = message("soc_ordering_noop")
            }
            0x73 -> if ((bits ushr 20) in listOf(0, 1, 0x302)) {
                when (bits ushr 20) {
                    0 -> { mnemonic = "ecall"; diagnostic = "ECALL has no environment handler, matching the desktop model."; notice = message("soc_environment_noop", "instruction" to "ECALL") }
                    1 -> { mnemonic = "ebreak"; diagnostic = "EBREAK has no environment handler, matching the desktop model."; notice = message("soc_environment_noop", "instruction" to "EBREAK") }
                    0x302 -> {
                        mnemonic = "mret"
                        val status = state.csrs[0x300] ?: 0u
                        val updated = (status and 8u.inv()) or (if (status and 0x80u != 0u) 8u else 0u) or 0x80u
                        state = state.copy(csrs = state.csrs + (0x300 to updated))
                        nextPc = state.csrs[0x341] ?: 0u
                    }
                }
            } else if (function in listOf(1, 2, 3, 5, 6, 7)) {
                val csr = bits ushr 20
                if (csr !in implementedCsrs) { error = "CSR 0x${csr.toString(16)} is not implemented."; notice = message("soc_csr_unsupported", "csr" to csr.toString(16)) }
                mnemonic = when (function) { 1 -> "csrrw"; 2 -> "csrrs"; 3 -> "csrrc"; 5 -> "csrrwi"; 6 -> "csrrsi"; else -> "csrrci" }
                val old = state.csrs[csr] ?: 0u
                value = old
                if (csr in implementedCsrs && csr !in readOnlyCsrs) {
                    val operand = if (function >= 5) rs1.toUInt() else a
                    val updated = when (function) { 1, 5 -> operand; 2, 6 -> old or operand; else -> old and operand.inv() }
                    state = state.copy(csrs = state.csrs + (csr to updated))
                }
            }
        }
        if (mnemonic == "invalid") return failed(state, pc, instruction, mnemonic, "Invalid RV32 instruction.", true, message("soc_rv_invalid"))
        // The trace shows Evolution's assembly text for the decoded word.
        val assembly = Rv32Disassembler.format(instruction) ?: mnemonic
        if (error != null) return failed(state, pc, instruction, assembly, error, false, notice ?: LocalizedText.literal(error))
        val writes = if (value != null && rd != 0) mapOf(rd to value) else emptyMap()
        if (value != null) state = state.withRegister(rd, value)
        val trace = SocInstructionTrace(pc, instruction, assembly, writes, diagnostic, notice)
        state = state.copy(pc = nextPc, instructions = state.instructions + 1, trace = (listOf(trace) + state.trace).take(256))
        return Rv32StepResult(state, trace)
    }

    private fun failed(state: Rv32CpuState, pc: UInt, instruction: UInt, assembly: String, error: String, advance: Boolean, notice: LocalizedText): Rv32StepResult {
        val trace = SocInstructionTrace(pc, instruction, assembly, diagnostic = error, notice = notice)
        return Rv32StepResult(state.copy(pc = pc + if (advance) 4u else 0u, stopped = true,
            trace = (listOf(trace) + state.trace).take(256)), trace, error, notice)
    }

    private fun boolean(value: Boolean): UInt = if (value) 1u else 0u
}
