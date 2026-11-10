package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

data class Nios2CustomRequest(
    val dataA: UInt,
    val dataB: UInt,
    val n: Int,
    val a: Int,
    val b: Int,
    val c: Int,
    val readRA: Boolean,
    val readRB: Boolean,
    val writeRC: Boolean,
    val start: Boolean = true,
)

/** A null done pin represents an undefined/error signal; an undefined result reads as zero. */
data class Nios2CustomResponse(val done: Boolean?, val result: UInt? = null)

data class Nios2CpuState(
    val pc: UInt = 0u,
    val registers: List<UInt> = List(32) { 0u },
    val knownRegisters: UInt = 1u,
    val status: UInt = 0x00800000u,
    val estatus: UInt = 0u,
    val bstatus: UInt = 0u,
    val ienable: UInt = 0u,
    val ipending: UInt = 0u,
    val pendingCustom: Nios2CustomRequest? = null,
    val stopped: Boolean = false,
    val instructions: Long = 0,
    val trace: List<SocInstructionTrace> = emptyList(),
) {
    init { require(registers.size == 32) }
    val controlRegisters: Map<Int, UInt> get() = mapOf(0 to status, 1 to estatus,
        2 to bstatus, 3 to ienable, 4 to ipending)
    fun register(index: Int): UInt {
        require(index in 0..31)
        return if (index == 0) 0u else registers[index]
    }
    fun withRegister(index: Int, value: UInt): Nios2CpuState {
        require(index in 0..31)
        if (index == 0) return this
        return copy(registers = registers.mapIndexed { i, old -> if (i == index) value else old },
            knownRegisters = knownRegisters or (1u shl index))
    }
    fun controlRegister(index: Int): UInt = controlRegisters[index] ?: 0u
}

data class Nios2StepResult(val state: Nios2CpuState, val trace: SocInstructionTrace?, val error: String? = null, val notice: LocalizedText? = error?.let(LocalizedText::literal))

/** The instruction encodings, control registers and custom handshake implemented by desktop Nios II. */
object Nios2Cpu {
    private const val TRACE_LIMIT = 256
    val registerNames = listOf("zero", "at", "r2", "r3", "r4", "r5", "r6", "r7",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15", "r16", "r17", "r18",
        "r19", "r20", "r21", "r22", "r23", "et", "bt", "gp", "sp", "fp", "ea", "sstat", "ra")

    fun reset(entry: UInt = 0u, previous: Nios2CpuState? = null): Nios2CpuState =
        Nios2CpuState(pc = entry, registers = previous?.registers ?: List(32) { 0u })

    fun step(
        previous: Nios2CpuState,
        memory: SocCpuMemory,
        irqLines: UInt = 0u,
        exceptionVector: UInt = 0x14u,
        breakVector: UInt = 0x30u,
        customResponse: Nios2CustomResponse = Nios2CustomResponse(false),
        beforeFetch: (UInt) -> Boolean = { true },
    ): Nios2StepResult {
        if (previous.stopped) return Nios2StepResult(previous, null)
        var state = previous.copy(ipending = irqLines)
        val writes = mutableMapOf<Int, UInt>()
        fun write(index: Int, value: UInt) {
            state = state.withRegister(index, value)
            if (index != 0) writes[index] = value
        }
        state.pendingCustom?.let { custom ->
            if (customResponse.done == null) {
                return Nios2StepResult(state.copy(stopped = true, pendingCustom = null), null,
                    "Custom instruction DONE pin is undefined.", message("soc_custom_done_unknown"))
            }
            state = state.copy(pendingCustom = custom.copy(start = false))
            if (!customResponse.done) return Nios2StepResult(state, null)
            if (!custom.writeRC) write(custom.c, customResponse.result ?: 0u)
            state = state.copy(pendingCustom = null)
        }
        if (state.status and 1u != 0u && state.ienable and state.ipending != 0u) {
            write(29, state.pc)
            state = state.copy(estatus = state.status, status = state.status and 1u.inv(), pc = exceptionVector)
        }
        val pc = state.pc
        if (!beforeFetch(pc)) return Nios2StepResult(state.copy(stopped = true), null)
        val fetched = memory.read(pc, SocAccessSize.WORD)
        if (!fetched.successful) return failed(state, pc, 0u, "fetch", "Instruction fetch: ${fetched.error}", false, message("soc_instruction_fetch", "error" to fetched.error?.notice))
        val instruction = fetched.data
        val bits = instruction.toInt()
        val opcode = bits and 63
        val ra = bits ushr 27 and 31
        val rb = bits ushr 22 and 31
        val rc = bits ushr 17 and 31
        val opx = bits ushr 11 and 63
        val imm5 = bits ushr 6 and 31
        val imm16 = bits ushr 6 and 65535
        val signedImmediate = (imm16 shl 16) shr 16
        val a = state.register(ra)
        val b = state.register(rb)
        var nextPc = pc + 4u
        var mnemonic: String? = null
        var arguments = ""
        var result: UInt? = null
        var destination = rb
        var error: String? = null
        var notice: LocalizedText? = null
        fun rOperands() { arguments = "${registerNames[rc]},${registerNames[ra]},${registerNames[rb]}" }
        fun iOperands(signed: Boolean = false) {
            arguments = "${registerNames[rb]},${registerNames[ra]},${if (signed) signedImmediate else imm16}"
        }
        fun interrupt() {
            state = state.copy(estatus = state.status, status = state.status and 1u.inv())
            nextPc = exceptionVector
        }
        when (opcode) {
            0x00, 0x01 -> {
                mnemonic = if (opcode == 0) "call" else "jmpi"
                if (opcode == 0) write(31, nextPc)
                nextPc = (instruction shr 6) shl 2
                arguments = nextPc.toString()
            }
            0x06, 0x0E, 0x2E, 0x16, 0x36, 0x26, 0x1E -> {
                val take = when (opcode) {
                    0x06 -> { mnemonic = if (ra == 0 && rb == 0) "br" else null; true }
                    0x0E -> { mnemonic = "bge"; a.toInt() >= b.toInt() }
                    0x2E -> { mnemonic = "bgeu"; a >= b }
                    0x16 -> { mnemonic = "blt"; a.toInt() < b.toInt() }
                    0x36 -> { mnemonic = "bltu"; a < b }
                    0x26 -> { mnemonic = "beq"; a == b }
                    else -> { mnemonic = "bne"; a != b }
                }
                val offset = signedImmediate + 4
                arguments = (if (opcode == 0x06) "" else "${registerNames[ra]},${registerNames[rb]},") +
                    "pc${if (offset >= 0) "+" else ""}$offset"
                if (take) nextPc += signedImmediate.toUInt()
            }
            0x04 -> { mnemonic = if (ra == 0) "movi" else "addi"; result = a + signedImmediate.toUInt(); iOperands(true) }
            0x0C -> { mnemonic = "andi"; result = a and imm16.toUInt(); iOperands() }
            0x14 -> { mnemonic = if (ra == 0) "movui" else "ori"; result = a or imm16.toUInt(); iOperands() }
            0x1C -> { mnemonic = "xori"; result = a xor imm16.toUInt(); iOperands() }
            // Desktop MULI falls through to the high-product path; fixtures preserve that behavior.
            0x24 -> { mnemonic = "muli"; result = ((a.toInt().toLong() * signedImmediate) shr 32).toUInt(); iOperands(true) }
            0x2C -> { mnemonic = "andhi"; result = a and (imm16.toUInt() shl 16); iOperands() }
            0x34 -> { mnemonic = if (ra == 0) "movhi" else "orhi"; result = a or (imm16.toUInt() shl 16); iOperands() }
            0x3C -> { mnemonic = "xorhi"; result = a xor (imm16.toUInt() shl 16); iOperands() }
            0x20, 0x18, 0x08, 0x28, 0x10, 0x30 -> {
                val condition = when (opcode) {
                    0x20 -> { mnemonic = "cmpeqi"; a == signedImmediate.toUInt() }
                    0x18 -> { mnemonic = "cmpnei"; a != signedImmediate.toUInt() }
                    0x08 -> { mnemonic = "cmpgei"; a.toInt() >= signedImmediate }
                    0x28 -> { mnemonic = "cmpgeui"; a >= imm16.toUInt() }
                    0x10 -> { mnemonic = "cmplti"; a.toInt() < signedImmediate }
                    else -> { mnemonic = "cmpltui"; a < imm16.toUInt() }
                }
                result = if (condition) 1u else 0u
                iOperands(opcode != 0x28 && opcode != 0x30)
            }
            0x17, 0x0F, 0x0B, 0x07, 0x03, 0x37, 0x2F, 0x2B, 0x27, 0x23,
            0x15, 0x0D, 0x05, 0x35, 0x2D, 0x25 -> {
                mnemonic = loadStoreNames.getValue(opcode)
                val size = when (opcode and 0x1F) {
                    0x07, 0x03, 0x05 -> SocAccessSize.BYTE
                    0x0F, 0x0B, 0x0D -> SocAccessSize.HALF_WORD
                    else -> SocAccessSize.WORD
                }
                val address = a + signedImmediate.toUInt()
                arguments = "${registerNames[rb]},$signedImmediate(${registerNames[ra]})"
                if (opcode and 7 == 5) {
                    val value = when (size) { SocAccessSize.BYTE -> b and 255u; SocAccessSize.HALF_WORD -> b and 65535u; SocAccessSize.WORD -> b }
                    val reply = memory.write(address, value, size)
                    if (!reply.successful) { error = "Data write: ${reply.error}"; notice = message("soc_data_write", "error" to reply.error?.notice) }
                } else {
                    val reply = memory.read(address, size)
                    if (!reply.successful) { error = "Data read: ${reply.error}"; notice = message("soc_data_read", "error" to reply.error?.notice) } else {
                        result = when (opcode and 0x1F) {
                            0x07 -> ((reply.data.toInt() shl 24) shr 24).toUInt()
                            0x03 -> reply.data and 255u
                            0x0F -> ((reply.data.toInt() shl 16) shr 16).toUInt()
                            0x0B -> reply.data and 65535u
                            else -> reply.data
                        }
                    }
                }
            }
            0x32 -> {
                mnemonic = "custom"
                val customBits = bits ushr 6 and 0x7FF
                val request = Nios2CustomRequest(a, b, customBits and 255, ra, rb, rc,
                    customBits and 0x400 != 0, customBits and 0x200 != 0, customBits and 0x100 != 0)
                state = state.copy(pendingCustom = request)
                arguments = "${request.n},${if (request.writeRC) "c" else "r"}$rc," +
                    "${if (request.readRA) "c" else "r"}$ra,${if (request.readRB) "c" else "r"}$rb"
            }
            0x13, 0x1B, 0x33, 0x3B -> if (rb == 0) {
                mnemonic = when (opcode) { 0x13 -> "initda"; 0x1B -> "flushda"; 0x33 -> "initd"; else -> "flushd" }
                arguments = "$signedImmediate(${registerNames[ra]})"
            }
            0x3A -> {
                destination = rc
                rOperands()
                if (imm5 == 0) when (opx) {
                    0x0E -> { mnemonic = "and"; result = a and b }
                    0x16 -> { mnemonic = "or"; result = a or b }
                    0x1E -> { mnemonic = "xor"; result = a xor b }
                    0x06 -> { mnemonic = "nor"; result = (a or b).inv() }
                    0x31 -> { mnemonic = "add"; result = a + b }
                    0x39 -> { mnemonic = "sub"; result = a - b }
                    0x27 -> { mnemonic = "mul"; result = (a.toInt().toLong() * b.toInt()).toUInt() }
                    0x18, 0x24 -> {
                        mnemonic = if (opx == 0x18) "div" else "divu"
                        if (b == 0u) { error = "Division by zero."; notice = message("soc_division_zero") }
                        else result = if (opx == 0x18) (a.toInt() / b.toInt()).toUInt() else a / b
                    }
                    0x1F -> { mnemonic = "mulxss"; result = ((a.toInt().toLong() * b.toInt()) shr 32).toUInt() }
                    0x07 -> { mnemonic = "mulxuu"; result = ((a.toULong() * b.toULong()) shr 32).toUInt() }
                    0x17 -> { mnemonic = "mulxsu"; result = ((a.toInt().toLong() * b.toLong()) shr 32).toUInt() }
                    0x20 -> { mnemonic = "cmpeq"; result = bit(a == b) }
                    0x08 -> { mnemonic = "cmpge"; result = bit(a.toInt() >= b.toInt()) }
                    0x28 -> { mnemonic = "cmpgeu"; result = bit(a >= b) }
                    0x10 -> { mnemonic = "cmplt"; result = bit(a.toInt() < b.toInt()) }
                    0x30 -> { mnemonic = "cmpltu"; result = bit(a < b) }
                    0x03 -> { mnemonic = "rol"; result = rotateLeft(a, (b and 31u).toInt()) }
                    0x0B -> { mnemonic = "ror"; result = rotateLeft(a, 32 - (b and 31u).toInt()) }
                    0x13 -> { mnemonic = "sll"; result = a shl (b and 31u).toInt() }
                    0x3B -> { mnemonic = "sra"; result = (a.toInt() shr (b and 31u).toInt()).toUInt() }
                    0x1B -> { mnemonic = "srl"; result = a shr (b and 31u).toInt() }
                    0x1D -> if (rc == 31 && rb == 0) { mnemonic = "callr"; write(31, nextPc); nextPc = a; arguments = registerNames[ra] }
                    0x05 -> if (ra == 31 && rb == 0 && rc == 0) { mnemonic = "ret"; nextPc = state.register(31); arguments = "" }
                    0x0D -> if (rb == 0 && rc == 0) { mnemonic = "jmp"; nextPc = a; arguments = registerNames[ra] }
                    0x01 -> if (ra == 29 && rb == 30 && rc == 0) { mnemonic = "eret"; state = state.copy(status = state.estatus); nextPc = state.register(29); arguments = "" }
                    0x09 -> if (ra == 30 && rb == 0 && rc == 30) { mnemonic = "bret"; state = state.copy(status = state.bstatus); nextPc = state.register(30); arguments = "" }
                    0x04, 0x0C, 0x29 -> if (rb == 0 && rc == 0) {
                        mnemonic = when (opx) { 0x04 -> "flushp"; 0x0C -> "flushi"; else -> "initi" }
                        arguments = if (opx == 0x04) "" else registerNames[ra]
                    }
                    0x36 -> if (ra == 0 && rb == 0 && rc == 0) { mnemonic = "sync"; arguments = "" }
                }
                when (opx) {
                    0x02, 0x12, 0x3A, 0x1A -> if (rb == 0) {
                        mnemonic = when (opx) { 0x02 -> "roli"; 0x12 -> "slli"; 0x3A -> "srai"; else -> "srli" }
                        arguments = "${registerNames[rc]},${registerNames[ra]},$imm5"
                        result = when (opx) { 0x02 -> rotateLeft(a, imm5); 0x12 -> a shl imm5; 0x3A -> (a.toInt() shr imm5).toUInt(); else -> a shr imm5 }
                    }
                    0x2D -> if (ra == 0 && rb == 0 && rc == 29) {
                        mnemonic = "trap"; arguments = if (imm5 == 0) "" else imm5.toString()
                        write(29, nextPc); interrupt()
                    }
                    0x34 -> if (ra == 0 && rb == 0 && rc == 30) {
                        mnemonic = "break"; arguments = if (imm5 == 0) "" else imm5.toString()
                        state = state.copy(bstatus = state.status, status = state.status and 1u.inv())
                        write(30, nextPc); nextPc = breakVector
                    }
                    0x26 -> if (ra == 0 && rb == 0) {
                        mnemonic = "rdctl"; result = state.controlRegister(imm5)
                        arguments = "${registerNames[rc]},ctl$imm5"
                    }
                    0x2E -> if (rb == 0 && rc == 0) {
                        mnemonic = "wrctl"; arguments = "ctl$imm5,${registerNames[ra]}"
                        state = when (imm5) {
                            0 -> state.copy(status = (a and 1u) or 0x00800000u)
                            1 -> state.copy(estatus = a)
                            2 -> state.copy(bstatus = a)
                            3 -> state.copy(ienable = a)
                            else -> { error = "Control register ctl$imm5 is read-only or unsupported."; notice = message("soc_control_unsupported", "register" to imm5); state }
                        }
                    }
                }
                if (mnemonic == "add") when {
                    ra == 0 && rb == 0 && rc == 0 -> { mnemonic = "nop"; arguments = "" }
                    rb == 0 -> { mnemonic = "mov"; arguments = "${registerNames[rc]},${registerNames[ra]}" }
                }
            }
        }
        if (mnemonic == null) return failed(state, pc, instruction, "invalid", "Invalid Nios II instruction.", true, message("soc_nios_invalid"))
        val assembly = if (arguments.isEmpty()) mnemonic else mnemonic.padEnd(10) + arguments
        if (error != null) return failed(state, pc, instruction, assembly, error, false, notice ?: LocalizedText.literal(error))
        if (result != null) write(destination, result)
        if (mnemonic in setOf("movi", "movui", "movhi")) arguments = "${registerNames[rb]},${if (mnemonic == "movi") signedImmediate else imm16}"
        val finalAssembly = if (arguments.isEmpty()) mnemonic else mnemonic.padEnd(10) + arguments
        val trace = SocInstructionTrace(pc, instruction, finalAssembly, writes)
        state = state.copy(pc = nextPc, instructions = state.instructions + 1,
            trace = (listOf(trace) + state.trace).take(TRACE_LIMIT))
        return Nios2StepResult(state, trace)
    }

    private fun bit(condition: Boolean): UInt = if (condition) 1u else 0u
    private fun rotateLeft(value: UInt, amount: Int): UInt =
        (value shl (amount and 31)) or (value shr ((32 - amount) and 31))

    private fun failed(state: Nios2CpuState, pc: UInt, instruction: UInt, assembly: String,
        error: String, advance: Boolean, notice: LocalizedText): Nios2StepResult {
        val trace = SocInstructionTrace(pc, instruction, assembly, diagnostic = error, notice = notice)
        return Nios2StepResult(state.copy(pc = if (advance) pc + 4u else pc,
            stopped = true, trace = (listOf(trace) + state.trace).take(TRACE_LIMIT)), trace, error, notice)
    }

    private val loadStoreNames = mapOf(0x17 to "ldw", 0x0F to "ldh", 0x0B to "ldhu", 0x07 to "ldb", 0x03 to "ldbu",
        0x37 to "ldwio", 0x2F to "ldhio", 0x2B to "ldhuio", 0x27 to "ldbio", 0x23 to "ldbuio",
        0x15 to "stw", 0x0D to "sth", 0x05 to "stb", 0x35 to "stwio", 0x2D to "sthio", 0x25 to "stbio")
}
