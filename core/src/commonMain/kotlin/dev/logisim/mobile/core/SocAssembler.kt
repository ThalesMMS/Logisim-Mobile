package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

sealed interface SocAssemblyResult {
    data class Assembled(val image: SocExecutableImage) : SocAssemblyResult
    data class Rejected(val line: Int?, val notice: LocalizedText) : SocAssemblyResult
}

/** Evolution's RV32IM assembler (soc.rv32im.RV32imAssembler on soc.util.Assembler). */
object Rv32Assembler {
    fun assemble(source: String, address: UInt): SocAssemblyResult = SocAssembler.assemble(source, address, Rv32AssemblerUnits)
}

/** Evolution's Nios II assembler (soc.nios2.Nios2Assembler on soc.util.Assembler). */
object Nios2Assembler {
    fun assemble(source: String, address: UInt): SocAssemblyResult = SocAssembler.assemble(source, address, Nios2AssemblerUnits)
}

/** Token kinds of Evolution's soc.util.AssemblerToken. */
internal object AsmKind {
    const val LABEL = 1
    const val INSTRUCTION = 2
    const val ASM_INSTRUCTION = 3
    const val BRACKETED_REGISTER = 4
    const val REGISTER = 5
    const val DEC_NUMBER = 6
    const val HEX_NUMBER = 7
    const val MAYBE_LABEL = 8
    const val STRING = 9
    const val SEPARATOR = 10
    const val BRACKET_OPEN = 11
    const val BRACKET_CLOSE = 12
    const val LABEL_IDENTIFIER = 13
    const val MATH_SUBTRACT = 14
    const val MATH_ADD = 15
    const val PARAMETER_LABEL = 16
    const val MATH_MUL = 17
    const val MATH_DIV = 18
    const val MATH_REM = 19
    const val MATH_SHIFT_LEFT = 20
    const val MATH_SHIFT_RIGHT = 21
    const val PROGRAM_COUNTER = 22
    const val MACRO = 23
    const val MACRO_PARAMETER = 24
    const val CUSTOM_REGISTER = 256
    const val CONTROL_REGISTER = 257
    val MATH = setOf(MATH_ADD, MATH_SUBTRACT, MATH_MUL, MATH_DIV, MATH_REM, MATH_SHIFT_LEFT, MATH_SHIFT_RIGHT)
}

internal class AsmFailure(val line: Int, val notice: LocalizedText) : Exception()

internal class AsmToken(type: Int, value: String, val line: Int) {
    var type = type
        private set
    var value = value
        private set
    // Evolution's AssemblerToken constructor returns before setting isLabel for a hex literal
    // without exactly one "X" (also each macro copy of an already stripped literal); reading it
    // then unboxes null and throws.
    private var label: Boolean? = type == AsmKind.LABEL
    val isLabel: Boolean get() = label ?: throw AsmFailure(line, message("soc_asm_immediate", "value" to value))

    init {
        if (type == AsmKind.HEX_NUMBER) {
            val digits = hexDigits(value)
            if (digits == null) label = null else this.value = digits
        }
        if (type == AsmKind.STRING) {
            var start = 0
            var end = value.length
            if (value.startsWith("\"")) start = 1
            if (value.length > 1 && value.last() == '"' && value[value.length - 2] != '\\') end--
            this.value = if (start >= end) "" else value.substring(start, end)
        }
    }

    fun setType(kind: Int) {
        type = kind
        if (kind == AsmKind.LABEL || kind == AsmKind.LABEL_IDENTIFIER || kind == AsmKind.PARAMETER_LABEL) label = true
    }

    fun setNumber(number: Int) { value = number.toString(); type = AsmKind.DEC_NUMBER }
    fun setHex(text: String) { type = AsmKind.HEX_NUMBER; value = text }
    fun append(text: String) { value += text }
    val isNumber get() = type == AsmKind.DEC_NUMBER || type == AsmKind.HEX_NUMBER

    /** AssemblerMacro.getMacroTokens: new AssemblerToken(type, value, offset). */
    fun copy() = AsmToken(type, value, line)

    /** Integer.parseUnsignedInt(value.substring(1)) of a macro parameter such as "@2". */
    fun parameterNumber(): Int = value.drop(1).toUIntOrNull()?.toInt()
        ?: throw AsmFailure(line, message("soc_asm_macro_parameter", "value" to value))

    /** Java's Integer.parseInt / parseUnsignedInt(16); a malformed literal fails the assembly. */
    fun number(): Int = when (type) {
        AsmKind.DEC_NUMBER -> value.toIntOrNull()
        AsmKind.HEX_NUMBER -> (if ('X' in value.uppercase()) hexDigits(value) else value)?.toUIntOrNull(16)?.toInt()
        else -> 0
    } ?: throw AsmFailure(line, message("soc_asm_immediate", "value" to value))

    fun long(): Long = when (type) {
        AsmKind.DEC_NUMBER -> value.toLongOrNull()
        AsmKind.HEX_NUMBER -> (if ('X' in value.uppercase()) hexDigits(value) else value)?.toULongOrNull(16)?.toLong()
        else -> 0L
    } ?: throw AsmFailure(line, message("soc_asm_immediate", "value" to value))

    private fun hexDigits(text: String): String? {
        // Java's String.split("X") drops trailing empty strings.
        val parts = text.uppercase().split('X').dropLastWhile(String::isEmpty)
        return if (parts.size == 2) parts[1] else null
    }
}

/** One instruction with its comma-separated parameters, as soc.util.AssemblerAsmInstruction. */
internal class AsmInstruction(val instruction: AsmToken, val size: Int) {
    val opcode get() = instruction.value
    val parameters = mutableListOf<List<AsmToken>>()
    var programCounter = -1L
    var bytes: ByteArray? = null
    val count get() = parameters.size
    fun parameter(index: Int) = parameters[index]

    fun fail(token: AsmToken, key: String, vararg arguments: Pair<String, Any?>): Nothing =
        throw AsmFailure(token.line, message(key, *arguments))
    fun failOperands(expected: Int): Nothing = fail(instruction, "soc_asm_operand_count", "value" to opcode, "count" to expected)
    fun failRegister(token: AsmToken): Nothing = fail(token, "soc_asm_register", "value" to token.value)
    fun failImmediate(token: AsmToken): Nothing = fail(token, "soc_asm_immediate", "value" to token.value)

    fun words(vararg words: Int) {
        val result = ByteArray(size)
        for ((index, word) in words.withIndex()) for (byte in 0..3) {
            if (index * 4 + byte < size) result[index * 4 + byte] = (word ushr (8 * byte)).toByte()
        }
        bytes = result
    }

    fun replacePcAndDoCalc(pc: Long) {
        for (index in parameters.indices) {
            val parameter = parameters[index]
            var found = false
            for (token in parameter) if (token.type == AsmKind.PROGRAM_COUNTER) {
                found = true
                token.setHex(pc.toString(16).uppercase().padStart(8, '0'))
            }
            if (!found || parameter.size <= 1) continue
            val removed = mutableSetOf<Int>()
            for (i in parameter.indices) {
                if (parameter[i].type !in AsmKind.MATH) continue
                if (i == 0 || !parameter[i - 1].isNumber) continue
                if (i + 1 >= parameter.size || !parameter[i + 1].isNumber) {
                    fail(parameter[i], "soc_asm_immediate", "value" to opcode)
                }
                removed += i - 1
                val before = parameter[i - 1].long()
                val after = parameter[i + 1].long()
                removed += i
                val result = when (parameter[i].type) {
                    AsmKind.MATH_ADD -> before + after
                    AsmKind.MATH_SUBTRACT -> before - after
                    AsmKind.MATH_SHIFT_LEFT -> before shl after.toInt()
                    AsmKind.MATH_SHIFT_RIGHT -> before shr after.toInt()
                    AsmKind.MATH_MUL -> before * after
                    AsmKind.MATH_DIV -> if (after == 0L) fail(parameter[i + 1], "soc_asm_immediate", "value" to "0") else before / after
                    else -> if (after == 0L) fail(parameter[i + 1], "soc_asm_immediate", "value" to "0") else before % after
                }
                // Evolution writes String.format("0x%X", result): a negative long has 16 hex digits.
                parameter[i + 1].setHex(result.toULong().toString(16).uppercase())
            }
            parameters[index] = parameter.filterIndexed { i, _ -> i !in removed }
        }
    }
}

/** The architecture part of an Evolution assembler: token words, opcodes and encoders. */
internal interface SocAssemblerUnits {
    val architecture: SocArchitecture
    /** Identifiers highlighted as registers (Token.OPERATOR), including "pc". */
    val registers: Set<String>
    val opcodes: Set<String>
    val acceptedParameterTypes: Set<Int>
    fun instructionSize(opcode: String): Int
    fun retype(tokens: List<AsmToken>) = Unit
    /** Encodes [instruction] into its bytes or throws [AsmFailure]. */
    fun assemble(instruction: AsmInstruction)
}

internal object SocAssembler {
    private const val MAX_SOURCE_LENGTH = 128 * 1024
    private val directives = setOf(
        ".ascii", ".align", ".file", ".globl", ".local", ".comm", ".common", ".ident",
        ".section", ".size", ".text", ".data", ".rodata", ".bss", ".string", ".p2align",
        ".asciz", ".equ", ".macro", ".endm", ".type", ".option", ".byte", ".2byte", ".half",
        ".short", ".4byte", ".word", ".long", ".8byte", ".dword", ".quad", ".balign",
        ".zero", ".org")
    private val bytes = setOf(".byte")
    private val shorts = setOf(".half", ".2byte", ".short")
    private val ints = setOf(".word", ".4byte", ".long")
    private val longs = setOf(".dword", ".8byte", ".quad")
    private val strings = setOf(".ascii", ".asciz", ".string")
    val baseParameterTypes = setOf(AsmKind.BRACKETED_REGISTER, AsmKind.DEC_NUMBER, AsmKind.HEX_NUMBER,
        AsmKind.PARAMETER_LABEL, AsmKind.REGISTER, AsmKind.SEPARATOR, AsmKind.MAYBE_LABEL, AsmKind.PROGRAM_COUNTER) + AsmKind.MATH

    fun assemble(source: String, address: UInt, units: SocAssemblerUnits): SocAssemblyResult {
        if (address % 4u != 0u) return SocAssemblyResult.Rejected(null, message("soc_asm_alignment"))
        if (source.length > MAX_SOURCE_LENGTH) return SocAssemblyResult.Rejected(null, message("soc_asm_source_limit"))
        return try {
            assembleTokens(source, address.toLong(), units)
        } catch (failure: AsmFailure) {
            SocAssemblyResult.Rejected(failure.line, failure.notice)
        }
    }

    private enum class Lex { NULL, WHITESPACE, STRING, COMMENT, CHAR, IDENTIFIER, DECIMAL, HEX, PREPROCESSOR, SHIFT_LEFT, SHIFT_RIGHT }

    /** soc.data.AssemblerHighlighter's token maker followed by Assembler.checkAndBuildTokens for one line. */
    private fun lexLine(text: String, line: Int, units: SocAssemblerUnits): List<AsmToken> {
        val raw = mutableListOf<Pair<Lex, String>>()
        var current = Lex.NULL
        var start = 0
        var escape = false
        fun emit(from: Int, to: Int, kind: Lex) {
            if (kind != Lex.WHITESPACE && kind != Lex.NULL && kind != Lex.COMMENT) raw += kind to text.substring(from, to + 1)
        }
        val punctuation = "(){}[,:+-*/%]"
        for ((index, c) in text.withIndex()) {
            // MAYBE_SHIFT states are negative in Evolution and count as LITERAL_CHAR.
            val kind = if (current == Lex.SHIFT_LEFT || current == Lex.SHIFT_RIGHT) Lex.CHAR else current
            var next: Lex? = null
            var finished = false
            var repeat = false
            when {
                kind == Lex.COMMENT -> next = Lex.COMMENT
                kind == Lex.STRING && (c != '"' || escape) -> { escape = c == '\\'; next = Lex.STRING }
                c == ' ' || c == '\t' -> { if (kind != Lex.NULL && kind != Lex.WHITESPACE) emit(start, index - 1, kind); next = Lex.WHITESPACE }
                c == '"' -> if (kind == Lex.STRING) { emit(start, index, kind); finished = true } else {
                    if (kind != Lex.NULL) emit(start, index - 1, kind)
                    escape = false
                    next = Lex.STRING
                }
                c == '#' -> { if (kind != Lex.NULL) emit(start, index - 1, kind); next = Lex.COMMENT }
                c == '<' || c == '>' -> {
                    val shift = if (c == '<') Lex.SHIFT_LEFT else Lex.SHIFT_RIGHT
                    if (current != shift) { if (kind != Lex.NULL) emit(start, index - 1, kind); next = shift }
                    else { emit(start, index, kind); finished = true }
                }
                c == '@' -> {
                    if (kind != Lex.NULL) emit(start, index - 1, kind)
                    if (kind == Lex.PREPROCESSOR) repeat = true else next = Lex.PREPROCESSOR
                }
                c in punctuation -> {
                    if (kind != Lex.NULL) emit(start, index - 1, kind)
                    if (kind == Lex.CHAR) repeat = true else next = Lex.CHAR
                }
                (c == 'x' || c == 'X') && kind == Lex.DECIMAL -> next = Lex.HEX
                kind == Lex.IDENTIFIER -> next = Lex.IDENTIFIER
                c in '0'..'9' -> next = when (kind) {
                    Lex.PREPROCESSOR -> Lex.PREPROCESSOR
                    Lex.HEX -> Lex.HEX
                    else -> { if (kind != Lex.NULL && kind != Lex.DECIMAL) emit(start, index - 1, kind); Lex.DECIMAL }
                }
                kind == Lex.HEX && (c in 'a'..'f' || c in 'A'..'F') -> next = Lex.HEX
                else -> { if (kind != Lex.NULL) emit(start, index - 1, kind); next = Lex.IDENTIFIER }
            }
            when {
                finished -> { start = index + 1; current = Lex.NULL }
                repeat -> start = index
                else -> {
                    if (next != current && !(next == Lex.HEX && current == Lex.DECIMAL)) start = index
                    current = next!!
                }
            }
        }
        // A pending MAYBE_SHIFT token has a negative type that checkAndBuildTokens ignores.
        if (current != Lex.NULL && current != Lex.SHIFT_LEFT && current != Lex.SHIFT_RIGHT) emit(start, text.length - 1, current)

        val tokens = mutableListOf<AsmToken>()
        for ((kind, lexeme) in raw) {
            val token = when (kind) {
                Lex.CHAR -> when (lexeme) {
                    "," -> AsmKind.SEPARATOR
                    "(" -> AsmKind.BRACKET_OPEN
                    ")" -> AsmKind.BRACKET_CLOSE
                    ":" -> AsmKind.LABEL_IDENTIFIER
                    "-" -> AsmKind.MATH_SUBTRACT
                    "+" -> AsmKind.MATH_ADD
                    "*" -> AsmKind.MATH_MUL
                    "%" -> AsmKind.MATH_REM
                    "/" -> AsmKind.MATH_DIV
                    "<<" -> AsmKind.MATH_SHIFT_LEFT
                    ">>" -> AsmKind.MATH_SHIFT_RIGHT
                    // "[", "]", "{", "}" and lone shifts are rejected with rounded-bracket assemblers.
                    else -> throw AsmFailure(line, message("soc_asm_instruction", "value" to lexeme))
                }.let { AsmToken(it, "", line) }
                Lex.DECIMAL -> AsmToken(AsmKind.DEC_NUMBER, lexeme, line)
                Lex.HEX -> AsmToken(AsmKind.HEX_NUMBER, lexeme, line)
                Lex.STRING -> AsmToken(AsmKind.STRING, lexeme, line)
                Lex.PREPROCESSOR -> AsmToken(AsmKind.MACRO_PARAMETER, lexeme, line)
                else -> AsmToken(when (lexeme) {
                    in units.opcodes -> AsmKind.INSTRUCTION
                    in units.registers -> if (lexeme == "pc") AsmKind.PROGRAM_COUNTER else AsmKind.REGISTER
                    in directives -> AsmKind.ASM_INSTRUCTION
                    else -> AsmKind.MAYBE_LABEL
                }, lexeme, line)
            }
            tokens += token
        }
        val result = mutableListOf<AsmToken>()
        for ((index, token) in tokens.withIndex()) {
            if (token.type != AsmKind.LABEL_IDENTIFIER) { result += token; continue }
            val before = tokens.getOrNull(index - 1)
            if (before?.type != AsmKind.MAYBE_LABEL) throw AsmFailure(line, message("soc_asm_instruction", "value" to ":"))
            before.setType(AsmKind.LABEL)
        }
        return result
    }

    private class Section(val name: String?, var start: Long) {
        var end = start
        val data = mutableMapOf<Long, Byte>()
        val instructions = linkedMapOf<Long, AsmInstruction>()
        fun addByte(value: Byte) { if (value.toInt() != 0) data[end] = value; end++ }
    }

    private fun assembleTokens(source: String, base: Long, units: SocAssemblerUnits): SocAssemblyResult {
        val tokens = source.lineSequence().withIndex().flatMap { (index, text) -> lexLine(text, index + 1, units) }.toMutableList()
        // Second pass: collect the labels.
        val labels = mutableMapOf<String, Long>()
        for (token in tokens) if (token.type == AsmKind.LABEL) {
            if (token.value in labels) throw AsmFailure(token.line, message("soc_asm_duplicate_label", "value" to token.value))
            labels[token.value] = -1
        }
        // Third pass: references to known labels.
        for (token in tokens) if (token.type == AsmKind.MAYBE_LABEL && token.value in labels) token.setType(AsmKind.PARAMETER_LABEL)
        // Fourth pass: fold math left to right, merge strings, bracketed registers.
        val removed = mutableSetOf<AsmToken>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type in AsmKind.MATH) {
                if (i + 1 >= tokens.size) throw AsmFailure(token.line, message("soc_asm_immediate", "value" to ""))
                val before = tokens.getOrNull(i - 1)?.takeIf { it.isNumber || it.type == AsmKind.PROGRAM_COUNTER }
                val after = tokens[i + 1]
                if (!after.isNumber && after.type != AsmKind.PROGRAM_COUNTER) {
                    throw AsmFailure(token.line, message("soc_asm_immediate", "value" to after.value))
                }
                if (after.type == AsmKind.PROGRAM_COUNTER || before?.type == AsmKind.PROGRAM_COUNTER) {
                    i++
                } else {
                    val left = before?.number() ?: 0
                    val right = after.number()
                    val value = when (token.type) {
                        AsmKind.MATH_ADD -> left + right
                        AsmKind.MATH_SHIFT_LEFT -> left shl right
                        AsmKind.MATH_SHIFT_RIGHT -> left shr right
                        AsmKind.MATH_SUBTRACT -> left - right
                        AsmKind.MATH_MUL -> left * right
                        else -> if (right == 0) throw AsmFailure(after.line, message("soc_asm_immediate", "value" to "0"))
                            else if (token.type == AsmKind.MATH_DIV) left / right else left % right
                    }
                    after.setNumber(value)
                    if (before != null) removed += before
                    removed += token
                    i++
                }
            } else if (token.type == AsmKind.STRING && i + 1 < tokens.size) {
                while (i + 1 < tokens.size && tokens[i + 1].type == AsmKind.STRING) {
                    i++
                    removed += tokens[i]
                    token.append(tokens[i].value)
                }
            } else if (token.type == AsmKind.BRACKET_OPEN && i + 2 < tokens.size) {
                val second = tokens[i + 1]
                val third = tokens[i + 2]
                if (second.type == AsmKind.REGISTER && third.type == AsmKind.BRACKET_CLOSE) {
                    second.setType(AsmKind.BRACKETED_REGISTER)
                    removed += token
                    removed += third
                    i += 2
                }
            }
            i++
        }
        tokens.removeAll { it in removed }
        units.retype(tokens)
        val macros = extractMacros(tokens, labels, units)
        val sections = assembleSections(tokens, labels, macros, base, units)

        val segments = sections.filter { it.end > it.start }.map { section ->
            if (section.end > 0x100000000L) throw AsmFailure(tokens.lastOrNull()?.line ?: 1, message("soc_asm_address_wrap"))
            val size = (section.end - section.start).toInt()
            SocExecutableSegment(section.start.toUInt(), ByteArray(size) { section.data[section.start + it] ?: 0 }, size.toULong())
        }
        if (segments.isEmpty()) return SocAssemblyResult.Rejected(null, message("soc_asm_empty"))
        val entry = labels["_start"]?.takeIf { it >= 0 }
            ?: sections.flatMap { it.instructions.keys }.minOrNull() ?: segments.first().address.toLong()
        return SocAssemblyResult.Assembled(SocExecutableImage(units.architecture, entry.toUInt(), segments))
    }

    /** soc.util.AssemblerInfo.assemble: sections, directives, labels, defines and encoding. */
    /** soc.util.AssemblerMacro: a body, its parameter count, local labels and the current call's parameters. */
    private class Macro(val name: String, val parameterCount: Int) {
        val tokens = mutableListOf<AsmToken>()
        val localLabels = mutableMapOf<String, Long>()
        val parameters = mutableListOf<List<AsmToken>>()
        var sizing = false
        var size = -1L

        /** getMacroTokens: parameters are the caller's own tokens, the body is copied. */
        fun expand(): List<AsmToken> = tokens.flatMap {
            if (it.type == AsmKind.MACRO_PARAMETER) parameters[it.parameterNumber() - 1] else listOf(it.copy())
        }
    }

    private fun macroFailure(token: AsmToken, key: String, vararg arguments: Pair<String, Any?>): Nothing =
        throw AsmFailure(token.line, message(key, *arguments))

    /** Sixth pass of Assembler.assemble: detect and remove the macro definitions, mark calls, resolve local labels. */
    private fun extractMacros(tokens: MutableList<AsmToken>, labels: MutableMap<String, Long>,
                              units: SocAssemblerUnits): Map<String, Macro> {
        val macros = mutableMapOf<String, Macro>()
        val removed = mutableSetOf<AsmToken>()
        var index = 0
        while (index < tokens.size) {
            val directive = tokens[index++]
            if (directive.type != AsmKind.ASM_INSTRUCTION || directive.value != ".macro") continue
            removed += directive
            val name = tokens.getOrNull(index++) ?: macroFailure(directive, "soc_asm_macro_definition", "value" to directive.value)
            removed += name
            if (name.type != AsmKind.MAYBE_LABEL) macroFailure(directive, "soc_asm_macro_definition", "value" to name.value)
            val count = tokens.getOrNull(index++) ?: macroFailure(directive, "soc_asm_macro_definition", "value" to name.value)
            removed += count
            if (!count.isNumber) macroFailure(directive, "soc_asm_macro_definition", "value" to name.value)
            val macro = Macro(name.value, count.number())
            var end = false
            while (!end && index < tokens.size) {
                val token = tokens[index++]
                if (token.type == AsmKind.ASM_INSTRUCTION) {
                    if (token.value == ".endm") end = true else macroFailure(token, "soc_asm_macro_directive", "value" to token.value)
                } else {
                    macro.tokens += token
                    if (token.type == AsmKind.LABEL) {
                        // Labels are local to the macro.
                        labels.remove(token.value)
                        macro.localLabels[token.value] = -1
                    }
                }
                removed += token
            }
            if (!end) macroFailure(directive, "soc_asm_macro_definition", "value" to name.value)
            for (token in macro.tokens) if (token.type == AsmKind.MACRO_PARAMETER) {
                val number = token.parameterNumber()
                if (number < 1 || number > macro.parameterCount) macroFailure(token, "soc_asm_macro_parameter", "value" to token.value)
            }
            // A later definition with the same name replaces the earlier one, as Evolution's HashMap.put.
            macros[macro.name] = macro
        }
        tokens.removeAll { it in removed }
        for (token in tokens) if (token.type == AsmKind.MAYBE_LABEL && token.value in macros) token.setType(AsmKind.MACRO)
        // checkForMacros
        for (macro in macros.values) for (token in macro.tokens) if (token.type == AsmKind.MAYBE_LABEL) {
            if (token.value == macro.name) macroFailure(token, "soc_asm_macro_recursion", "value" to macro.name)
            if (token.value in macros) token.setType(AsmKind.MACRO)
        }
        fun size(macro: Macro, hierarchy: MutableList<AsmToken>): Long {
            if (macro.size >= 0) return macro.size
            // Macros calling each other: Evolution marks every call in the chain.
            if (macro.sizing) macroFailure(hierarchy.first(), "soc_asm_macro_recursion", "value" to hierarchy.first().value)
            macro.sizing = true
            var pc = 0L
            val iterator = macro.tokens.iterator()
            while (iterator.hasNext()) {
                val token = iterator.next()
                when (token.type) {
                    AsmKind.INSTRUCTION -> pc += units.instructionSize(token.value)
                    AsmKind.MACRO -> { hierarchy += token; pc += size(macros.getValue(token.value), hierarchy) }
                    AsmKind.LABEL -> if (token.value in macro.localLabels) { macro.localLabels[token.value] = pc; iterator.remove() }
                }
            }
            macro.sizing = false
            macro.size = pc
            return pc
        }
        // replaceLabels: a local label becomes pc +/- its offset from the instruction that uses it.
        for (macro in macros.values) {
            val hierarchy = mutableListOf<AsmToken>()
            size(macro, hierarchy)
            hierarchy.clear()
            var pc = 0L
            var next = 0L
            var i = 0
            while (i < macro.tokens.size) {
                val token = macro.tokens[i]
                if (token.type == AsmKind.INSTRUCTION) { pc = next; next += units.instructionSize(token.value) }
                else if (token.type == AsmKind.MACRO) { pc = next; next += size(macros.getValue(token.value), hierarchy) }
                if (token.type == AsmKind.PARAMETER_LABEL && token.value !in labels) {
                    val target = macro.localLabels[token.value] ?: macroFailure(token, "soc_asm_target", "value" to token.value)
                    var offset = target - pc
                    val negative = offset < 0
                    if (negative) offset = -offset
                    token.setHex("0x" + offset.toString(16).uppercase())
                    macro.tokens.add(i, AsmToken(if (negative) AsmKind.MATH_SUBTRACT else AsmKind.MATH_ADD, "", token.line))
                    macro.tokens.add(i, AsmToken(AsmKind.PROGRAM_COUNTER, "pc", token.line))
                }
                i++
            }
        }
        return macros
    }

    private fun assembleSections(tokens: List<AsmToken>, labels: MutableMap<String, Long>, macros: Map<String, Macro>,
                                 base: Long, units: SocAssemblerUnits): List<Section> {
        val sections = mutableListOf<Section>()
        val defines = mutableMapOf<String, Int>()
        var current = -1
        fun active(): Section {
            if (current < 0) { sections += Section(null, base); current = 0 }
            return sections[current]
        }
        fun addSection(name: String, token: AsmToken) {
            if (sections.any { it.name == name }) throw AsmFailure(token.line, message("soc_asm_instruction", "value" to name))
            val section = Section(name, if (current < 0) base else sections[current].end)
            sections += section
            current = sections.lastIndex
        }
        fun unsupported(token: AsmToken): Nothing = throw AsmFailure(token.line, message("soc_asm_instruction", "value" to token.value))
        fun expectNumber(directive: AsmToken, index: Int): AsmToken =
            tokens.getOrNull(index)?.takeIf { it.isNumber } ?: throw AsmFailure(directive.line, message("soc_asm_immediate", "value" to directive.value))

        /** The comma-separated parameters after [index]; returns them with the number of tokens consumed. */
        fun parameters(list: List<AsmToken>, index: Int, owner: AsmToken, done: Int): Pair<List<List<AsmToken>>, Int> {
            val result = mutableListOf<List<AsmToken>>()
            val parameter = mutableListOf<AsmToken>()
            var skip = 0
            while (index + skip + 1 < list.size && list[index + skip + 1].type in units.acceptedParameterTypes) {
                val next = list[index + ++skip]
                if (next.type == AsmKind.SEPARATOR) {
                    if (parameter.isEmpty()) throw AsmFailure(next.line, message("soc_asm_operand_count", "value" to owner.value, "count" to done + result.size + 1))
                    result += parameter.toList()
                    parameter.clear()
                } else parameter += next
            }
            if (parameter.isNotEmpty()) result += parameter.toList()
            return result to skip
        }
        /** AssemblerInfo.handleInstruction. */
        fun instruction(list: List<AsmToken>, index: Int): Int {
            val token = list[index]
            val instruction = AsmInstruction(token, units.instructionSize(token.value))
            val (parameters, skip) = parameters(list, index, token, 0)
            instruction.parameters += parameters
            val section = active()
            instruction.replacePcAndDoCalc(section.end)
            section.instructions[section.end] = instruction
            section.end += instruction.size
            return skip
        }
        /** AssemblerInfo.handleMacros: bind the call's parameters and assemble the expanded body. */
        fun expandMacro(list: List<AsmToken>, index: Int): Int {
            val call = list[index]
            val macro = macros[call.value] ?: return 0
            macro.parameters.clear()
            val (parameters, skip) = parameters(list, index, call, 0)
            macro.parameters += parameters
            if (macro.parameters.size != macro.parameterCount) {
                throw AsmFailure(call.line, message("soc_asm_macro_parameters", "value" to macro.name, "count" to macro.parameterCount))
            }
            val body = macro.expand()
            var i = 0
            while (i < body.size) {
                val token = body[i]
                when (token.type) {
                    AsmKind.INSTRUCTION -> i += instruction(body, i)
                    AsmKind.MACRO -> i += expandMacro(body, i)
                    else -> unsupported(token)
                }
                i++
            }
            return skip
        }

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when (token.type) {
                AsmKind.ASM_INSTRUCTION -> when (val name = token.value) {
                    ".section" -> {
                        val next = tokens.getOrNull(index + 1)
                        if (next == null || next.type !in setOf(AsmKind.MAYBE_LABEL, AsmKind.LABEL_IDENTIFIER, AsmKind.ASM_INSTRUCTION)) unsupported(token)
                        addSection(next.value, next)
                        index++
                    }
                    ".text", ".data", ".rodata", ".bss" -> addSection(name, token)
                    ".org" -> {
                        val address = expectNumber(token, index + 1).number().toUInt().toLong()
                        val section = active()
                        if (section.start == section.end) { section.start = address; section.end = address } else section.end = address
                        index++
                    }
                    ".zero" -> {
                        val count = expectNumber(token, index + 1).number()
                        if (count < 0) throw AsmFailure(token.line, message("soc_asm_immediate", "value" to count))
                        if (count > 0) active().end += count
                        index++
                    }
                    in strings -> {
                        val next = tokens.getOrNull(index + 1)?.takeIf { it.type == AsmKind.STRING } ?: unsupported(token)
                        val section = active()
                        var position = 0
                        val text = next.value
                        while (position < text.length) {
                            if (text[position] == '\\') {
                                position++
                                if (position < text.length) section.addByte(when (val escaped = text[position]) {
                                    'b' -> 8; 't' -> 9; 'n' -> 10; 'f' -> 12; 'r' -> 13; else -> escaped.code
                                }.toByte())
                            } else {
                                section.data[section.end] = text[position].code.toByte()
                                section.end++
                            }
                            position++
                        }
                        if (name != ".ascii") section.addByte(0)
                        index++
                    }
                    in bytes, in shorts, in ints, in longs -> {
                        val range = when (name) { in bytes -> 8; in shorts -> 16; in ints -> 32; else -> -1 }
                        var skip = 0
                        do {
                            skip++
                            val value = tokens.getOrNull(index + skip)?.takeIf { it.isNumber }
                                ?: throw AsmFailure(token.line, message("soc_asm_immediate", "value" to (tokens.getOrNull(index + skip)?.value ?: name)))
                            var number = value.long()
                            if (range > 0 && number >= (1L shl range)) throw AsmFailure(value.line, message("soc_asm_immediate", "value" to value.value))
                            val section = active()
                            repeat(if (range < 0) 8 else range shr 3) {
                                section.addByte((number and 0xFF).toByte())
                                number = number shr 8
                            }
                            val separator = tokens.getOrNull(index + skip + 1)?.type == AsmKind.SEPARATOR
                            if (separator) skip++
                        } while (separator && index + skip < tokens.size)
                        index += skip
                    }
                    ".equ" -> {
                        val label = tokens.getOrNull(index + 1)
                        if (label == null || (label.type != AsmKind.MAYBE_LABEL && label.type != AsmKind.PARAMETER_LABEL)) unsupported(token)
                        val value = tokens.getOrNull(index + 2)?.takeIf { it.isNumber }
                            ?: throw AsmFailure(token.line, message("soc_asm_immediate", "value" to label.value))
                        if (label.type == AsmKind.PARAMETER_LABEL) throw AsmFailure(label.line, message("soc_asm_duplicate_label", "value" to label.value))
                        defines[label.value] = value.number()
                        index += 2
                    }
                    else -> unsupported(token)
                }
                AsmKind.LABEL -> {
                    if ((labels[token.value] ?: -1) >= 0) throw AsmFailure(token.line, message("soc_asm_duplicate_label", "value" to token.value))
                    labels[token.value] = active().end
                }
                AsmKind.INSTRUCTION -> index += instruction(tokens, index)
                AsmKind.MACRO -> index += expandMacro(tokens, index)
                else -> unsupported(token)
            }
            index++
        }
        for (section in sections) for (instruction in section.instructions.values) for (parameter in instruction.parameters) for (token in parameter) {
            when (token.type) {
                AsmKind.PARAMETER_LABEL -> token.setHex((labels[token.value] ?: throw AsmFailure(token.line, message("soc_asm_target", "value" to token.value)))
                    .toString(16).uppercase().padStart(8, '0'))
            }
        }
        for (section in sections) for (instruction in section.instructions.values) for (parameter in instruction.parameters) for (token in parameter) {
            if (token.type == AsmKind.MAYBE_LABEL) {
                val value = defines[token.value] ?: throw AsmFailure(token.line, message("soc_asm_target", "value" to token.value))
                token.setHex(value.toUInt().toString(16).uppercase().padStart(8, '0'))
            }
        }
        // Evolution's overlap test, kept with its exact (partial) conditions.
        for (first in sections.indices) for (second in first + 1 until sections.size) {
            val a = sections[first]
            val b = sections[second]
            if ((a.start > b.start && a.start < b.end) || (a.end > b.start && a.end < b.end)) {
                throw AsmFailure(tokens.firstOrNull()?.line ?: 1, message("soc_asm_instruction", "value" to (a.name ?: b.name ?: "")))
            }
        }
        for (section in sections) for ((address, instruction) in section.instructions) {
            instruction.programCounter = address
            units.assemble(instruction)
            val encoded = instruction.bytes ?: throw AsmFailure(instruction.instruction.line, message("soc_asm_instruction", "value" to instruction.opcode))
            for (offset in encoded.indices) section.data[address + offset] = encoded[offset]
        }
        return sections
    }
}
