package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.LocalizedArgumentException

enum class BooleanOperator { AND, OR, XOR, XNOR }

sealed interface BooleanExpression {
    fun evaluate(values: Map<String, Boolean>): Boolean
    fun variables(): Set<String>
    fun format(): String

    data class Constant(val value: Boolean) : BooleanExpression {
        override fun evaluate(values: Map<String, Boolean>) = value
        override fun variables() = emptySet<String>()
        override fun format() = if (value) "1" else "0"
    }

    data class Variable(val name: String) : BooleanExpression {
        init { require(name.isNotBlank()) }
        override fun evaluate(values: Map<String, Boolean>): Boolean =
            values[name] ?: throw LocalizedArgumentException(message(Messages.AnalysisInputUndefined, "name" to name))
        override fun variables() = setOf(name)
        override fun format(): String = if (name.matches(Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\[[0-9]+])?")) &&
            name.uppercase() !in setOf("AND", "OR", "XOR", "NOT", "EQUALS")) name else "`${name.replace("`", "``")}`"
    }

    data class Not(val expression: BooleanExpression) : BooleanExpression {
        override fun evaluate(values: Map<String, Boolean>) = !expression.evaluate(values)
        override fun variables() = expression.variables()
        override fun format() = "!${expression.format()}"
    }

    data class Binary(
        val operator: BooleanOperator,
        val left: BooleanExpression,
        val right: BooleanExpression,
    ) : BooleanExpression {
        override fun evaluate(values: Map<String, Boolean>): Boolean {
            val a = left.evaluate(values)
            val b = right.evaluate(values)
            return when (operator) {
                BooleanOperator.AND -> a && b
                BooleanOperator.OR -> a || b
                BooleanOperator.XOR -> a != b
                BooleanOperator.XNOR -> a == b
            }
        }
        override fun variables() = left.variables() + right.variables()
        override fun format(): String {
            val op = when (operator) {
                BooleanOperator.AND -> "&"
                BooleanOperator.OR -> "|"
                BooleanOperator.XOR -> "^"
                BooleanOperator.XNOR -> "="
            }
            return "(${left.format()} $op ${right.format()})"
        }
    }
}

data class BooleanAssignment(val output: String, val expression: BooleanExpression)

class BooleanExpressionException(reason: LocalizedText, val position: Int) :
    LocalizedArgumentException(message(Messages.AnalysisParsePosition, "reason" to reason, "position" to (position + 1))) {
    constructor(message: String, position: Int) : this(LocalizedText.literal(message), position)
}

/** Operator precedence follows the desktop analyzer, including its notation-specific aliases. */
object BooleanExpressionParser {
    fun parse(text: String, inputNames: Set<String> = emptySet()): BooleanExpression =
        Parser(text, inputNames).parse()

    fun parseAssignment(text: String, inputNames: Set<String> = emptySet()): BooleanAssignment {
        val separator = Regex("^\\s*(`(?:[^`]|``)+`|[\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*:(?:=)?").find(text)
            ?: throw BooleanExpressionException(message(Messages.AnalysisAssignmentRequired), 0)
        val rawOutput = separator.groupValues[1]
        val output = if (rawOutput.startsWith('`')) rawOutput.drop(1).dropLast(1).replace("``", "`") else rawOutput
        return BooleanAssignment(output, parse(text.substring(separator.range.last + 1), inputNames))
    }

    private enum class Kind { NAME, ZERO, ONE, NOT, POST_NOT, BINARY, OPEN, CLOSE, END }
    private data class Token(
        val kind: Kind,
        val text: String,
        val position: Int,
        val operator: BooleanOperator? = null,
        val precedence: Int = 0,
    )

    private class Parser(private val source: String, private val knownNames: Set<String>) {
        private var offset = 0
        private var token = next()
        private var nodes = 0

        fun parse(): BooleanExpression {
            if (source.length > 16_384) fail(message(Messages.AnalysisExpressionLength))
            val expression = expression(0, 0)
            if (token.kind != Kind.END) fail(message(Messages.AnalysisUnexpectedSymbol, "symbol" to token.text))
            val pending = ArrayDeque<Pair<BooleanExpression, Int>>()
            pending.add(expression to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                if (depth > 128) fail(message(Messages.AnalysisExpressionComplexity))
                when (node) {
                    is BooleanExpression.Not -> pending.add(node.expression to depth + 1)
                    is BooleanExpression.Binary -> { pending.add(node.left to depth + 1); pending.add(node.right to depth + 1) }
                    else -> Unit
                }
            }
            return expression
        }

        private fun expression(minimumPrecedence: Int, depth: Int): BooleanExpression {
            if (depth > 128 || ++nodes > 2_048) fail(message(Messages.AnalysisExpressionComplexity))
            var left = when (token.kind) {
                Kind.NOT -> { advance(); BooleanExpression.Not(expression(14, depth + 1)) }
                Kind.NAME -> BooleanExpression.Variable(token.text).also { advance() }
                Kind.ZERO -> BooleanExpression.Constant(false).also { advance() }
                Kind.ONE -> BooleanExpression.Constant(true).also { advance() }
                Kind.OPEN -> {
                    advance()
                    expression(0, depth + 1).also {
                        if (token.kind != Kind.CLOSE) fail(message(Messages.AnalysisMissingClose))
                        advance()
                    }
                }
                else -> fail(message(Messages.AnalysisExpectedOperand))
            }
            while (true) {
                if (token.kind == Kind.POST_NOT && 14 >= minimumPrecedence) {
                    if (++nodes > 2_048) fail(message(Messages.AnalysisExpressionComplexity))
                    advance()
                    left = BooleanExpression.Not(left)
                    continue
                }
                val implicit = token.kind in setOf(Kind.NAME, Kind.ZERO, Kind.ONE, Kind.OPEN, Kind.NOT)
                val precedence = if (implicit) 13 else token.precedence
                if ((!implicit && token.kind != Kind.BINARY) || precedence < minimumPrecedence) break
                val operator = if (implicit) BooleanOperator.AND else token.operator!!
                if (!implicit) advance()
                left = BooleanExpression.Binary(operator, left, expression(precedence + 1, depth + 1))
                if (++nodes > 2_048) fail(message(Messages.AnalysisExpressionComplexity))
            }
            return left
        }

        private fun advance() { token = next() }
        private fun fail(message: LocalizedText): Nothing = throw BooleanExpressionException(message, token.position)

        private fun next(): Token {
            while (offset < source.length && source[offset].isWhitespace()) offset++
            val start = offset
            if (offset == source.length) return Token(Kind.END, "", offset)
            val c = source[offset++]
            if (c == '`') {
                val name = StringBuilder()
                while (offset < source.length) {
                    val letter = source[offset++]
                    if (letter != '`') name.append(letter)
                    else if (source.getOrNull(offset) == '`') { name.append('`'); offset++ }
                    else {
                        if (name.isEmpty()) throw BooleanExpressionException(message(Messages.AnalysisEmptyName), start)
                        return Token(Kind.NAME, name.toString(), start)
                    }
                }
                throw BooleanExpressionException(message(Messages.AnalysisUnterminatedName), start)
            }
            if (c.isLetter() || c == '_' || c == '$') {
                while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] in "_$")) offset++
                var name = source.substring(start, offset)
                if (offset < source.length && source[offset] == '[') {
                    val end = source.indexOf(']', offset + 1)
                    val bit = if (end < 0) null else source.substring(offset + 1, end).trim().toIntOrNull()?.takeIf { it >= 0 }
                    if (bit == null) throw BooleanExpressionException(message(Messages.AnalysisInvalidBusIndex), offset)
                    name += "[$bit]"
                    offset = end + 1
                } else if (offset + 1 < source.length && source[offset] == ':' && source[offset + 1].isDigit()) {
                    offset++
                    val bitStart = offset
                    while (offset < source.length && source[offset].isDigit()) offset++
                    val bit = source.substring(bitStart, offset).toIntOrNull()
                        ?: throw BooleanExpressionException(message(Messages.AnalysisInvalidBusIndex), bitStart)
                    name += "[$bit]"
                }
                if (name !in knownNames) when (name.uppercase()) {
                    "NOT" -> return Token(Kind.NOT, name, start)
                    "AND" -> return Token(Kind.BINARY, name, start, BooleanOperator.AND, 3)
                    "XOR" -> return Token(Kind.BINARY, name, start, BooleanOperator.XOR, 2)
                    "OR" -> return Token(Kind.BINARY, name, start, BooleanOperator.OR, 1)
                    "EQUALS" -> return Token(Kind.BINARY, name, start, BooleanOperator.XNOR, 9)
                }
                return Token(Kind.NAME, name, start)
            }
            fun binary(operator: BooleanOperator, precedence: Int, width: Int = 1): Token {
                offset = start + width
                return Token(Kind.BINARY, source.substring(start, offset), start, operator, precedence)
            }
            return when (c) {
                '0', '⊥' -> Token(Kind.ZERO, c.toString(), start)
                '1', '⊤' -> Token(Kind.ONE, c.toString(), start)
                '(' -> Token(Kind.OPEN, "(", start)
                ')' -> Token(Kind.CLOSE, ")", start)
                '\'' -> Token(Kind.POST_NOT, "'", start)
                '!' -> if (source.getOrNull(offset) == '=') binary(BooleanOperator.XOR, 9, 2)
                    else Token(Kind.NOT, "!", start)
                '~', '-', '¬', '˜' -> Token(Kind.NOT, c.toString(), start)
                '⋅', '∙', '·' -> binary(BooleanOperator.AND, 13)
                '^', '⊕' -> binary(BooleanOperator.XOR, 12)
                '⊙' -> binary(BooleanOperator.XNOR, 10)
                '+', '∨', '⋁' -> binary(BooleanOperator.OR, 9)
                '*', '∧', '⋀' -> binary(BooleanOperator.AND, 9)
                '≠', '≢', '⊻' -> binary(BooleanOperator.XOR, 9)
                '=', '≡', '⇔', '↔' -> binary(BooleanOperator.XNOR, 9,
                    if (c == '=' && source.getOrNull(offset) == '=') 2 else 1)
                '&' -> if (source.getOrNull(offset) == '&') binary(BooleanOperator.AND, 5, 2)
                    else binary(BooleanOperator.AND, 8)
                '|' -> if (source.getOrNull(offset) == '|') binary(BooleanOperator.OR, 4, 2)
                    else binary(BooleanOperator.OR, 6)
                else -> throw BooleanExpressionException(message(Messages.AnalysisInvalidSymbol, "symbol" to c), start)
            }
        }
    }
}
