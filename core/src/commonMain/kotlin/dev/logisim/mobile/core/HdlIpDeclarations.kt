package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/** A bounded declaration reader, not a VHDL or BLIF compiler. Unsupported declarations stay visible. */
object HdlIpDeclarations {
    private val identifier = Regex("[A-Za-z](?:[A-Za-z0-9]|_(?=[A-Za-z0-9]))*")
    private const val maxSource = 128 * 1024

    fun vhdl(source: String, overrides: Map<String, String> = emptyMap()): HdlIpDesign {
        val diagnostics = mutableListOf<HdlIpDiagnostic>()
        fun error(code: String, notice: LocalizedText) { diagnostics += HdlIpDiagnostic(code, notice) }
        if (source.length > maxSource || source.encodeToByteArray().size > maxSource || '\u0000' in source) return HdlIpDesign(HdlIpBackend.VHDL, source, "", emptyList(),
            diagnostics = listOf(HdlIpDiagnostic("invalid-source", message(Messages.HdlIpInvalidSource, "backend" to "VHDL"))))
        val text = withoutVhdlComments(source)
        val entity = Regex("\\bentity\\s+($identifier)\\s+is\\b", RegexOption.IGNORE_CASE).find(text)
        if (entity == null) return HdlIpDesign(HdlIpBackend.VHDL, source, "", emptyList(), diagnostics =
            listOf(HdlIpDiagnostic("missing-entity", message(Messages.HdlIpMissingEntity))))
        val name = entity.groupValues[1]
        val suffix = text.substring(entity.range.last + 1)
        val end = Regex("\\bend\\b\\s*(?:entity\\b\\s*)?(?:${Regex.escape(name)}\\b\\s*)?;", RegexOption.IGNORE_CASE).find(suffix)
        if (end == null) error("missing-entity-end", message(Messages.HdlIpMissingEntityEnd))
        val declaration = suffix.substring(0, end?.range?.first ?: suffix.length)
        val generics = mutableMapOf<String, String>()
        val integerValues = mutableMapOf<String, Int>()
        val timeGenerics = mutableSetOf<String>()
        val declarations = mutableListOf<HdlGenericDeclaration>()
        val normalizedOverrides = overrides.entries.associate { it.key.lowercase() to it.value }
        if (normalizedOverrides.size != overrides.size) error("duplicate-generic", message(Messages.HdlIpGenericCaseDuplicate))
        val genericBlock = block(declaration, "generic", diagnostics)
        if (genericBlock != null) for (entry in genericBlock.split(';').filter { it.isNotBlank() }) {
            val match = Regex("\\s*($identifier(?:\\s*,\\s*$identifier)*)\\s*:\\s*(integer|natural|positive|boolean|time|std_logic|std_logic_vector\\s*\\([^)]*\\))\\s*(?::=\\s*(.+))?\\s*", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matchEntire(entry)
            if (match == null) { error("unsupported-generic", message(Messages.HdlIpUnsupportedGenericDeclaration, "declaration" to entry.trim())); continue }
            for (genericName in match.groupValues[1].split(',').map { it.trim() }) {
                if (generics.keys.any { it.equals(genericName, true) }) { error("duplicate-generic", message(Messages.HdlIpDuplicateGeneric, "name" to genericName)); continue }
                val kind = match.groupValues[2].lowercase()
                declarations += HdlGenericDeclaration(genericName,
                    if (kind.startsWith("std_logic_vector")) "std_logic_vector" else kind, match.groupValues[3].trim())
                val raw = normalizedOverrides[genericName.lowercase()] ?: match.groupValues[3].trim()
                // Evolution's VhdlParser gives a numeric generic without a value 0, or 1 for positive.
                if (raw.isEmpty() && kind !in numericGenericTypes) { error("missing-generic", message(Messages.HdlIpMissingGeneric, "name" to genericName)); continue }
                val value = if (raw.isEmpty()) { if (kind == "positive") "1" else "0" } else when (kind) {
                    "integer", "natural", "positive" -> integer(raw, integerValues)?.also {
                        if (kind == "natural" && it < 0 || kind == "positive" && it < 1) error("invalid-generic", message(Messages.HdlIpInvalidGeneric, "name" to genericName))
                    }?.toString()
                    "time" -> femtoseconds(raw, integerValues)?.toString()
                    "boolean" -> raw.lowercase().takeIf { it == "true" || it == "false" }
                    "std_logic" -> raw.uppercase().takeIf { Regex("'[UX01ZWLH-]'").matches(it) }
                    else -> raw.uppercase().takeIf { Regex("\"[UX01ZWLH-]{1,64}\"").matches(it) }
                }
                if (value == null) error("unsupported-generic", message(Messages.HdlIpUnsupportedGenericValue, "name" to genericName))
                else {
                    generics[genericName] = value
                    if (kind == "time") timeGenerics += genericName
                    if (kind != "time") value.toIntOrNull()?.let { integerValues[genericName.lowercase()] = it }
                }
            }
        }
        for (key in overrides.keys) if (generics.keys.none { it.equals(key, true) }) error("unknown-generic", message(Messages.HdlIpUnknownGeneric, "name" to key))
        val ports = mutableListOf<HdlIpPort>()
        val portBlock = block(declaration, "port", diagnostics)
        if (portBlock == null) error("missing-ports", message(Messages.HdlIpMissingEntityPorts))
        else for (entry in portBlock.split(';').filter { it.isNotBlank() }) {
            val match = Regex("\\s*($identifier(?:\\s*,\\s*$identifier)*)\\s*:\\s*(inout|in|out|buffer|linkage)\\s+(std_logic|std_logic_vector\\s*\\(\\s*(.+?)\\s+(downto|to)\\s+(.+?)\\s*\\))\\s*(?::=.*)?", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matchEntire(entry)
            if (match == null) { error("unsupported-port", message(Messages.HdlIpUnsupportedPortDeclaration, "declaration" to entry.trim())); continue }
            val mode = match.groupValues[2].lowercase()
            val direction = when (mode) {
                "in" -> PortDirection.INPUT
                "out", "buffer" -> PortDirection.OUTPUT
                else -> PortDirection.INOUT
            }
            if (mode == "linkage") error("unsupported-linkage", message("hdl_ip_unsupported_linkage"))
            val vector = match.groupValues[3].startsWith("std_logic_vector", true)
            val high = if (vector) integer(match.groupValues[4], integerValues) else 0
            val low = if (vector) integer(match.groupValues[6], integerValues) else 0
            val width = if (high == null || low == null) null else when (match.groupValues[5].lowercase()) {
                "to" -> (low.toLong() - high + 1).takeIf { it in 1..64 }?.toInt()
                else -> (high.toLong() - low + 1).takeIf { it in 1..64 }?.toInt()
            }
            if (width == null) { error("unsupported-width", message(Messages.HdlIpUnsupportedWidth, "declaration" to entry.trim())); continue }
            for (portName in match.groupValues[1].split(',').map { it.trim() }) {
                if (ports.any { it.name.equals(portName, true) }) error("duplicate-port", message(Messages.HdlIpDuplicatePort, "name" to portName))
                else ports += HdlIpPort(portName, direction, width, if (vector) "std_logic_vector" else "std_logic")
            }
        }
        if (ports.size > 128) error("too-many-ports", message(Messages.HdlIpPortLimit))
        return HdlIpDesign(HdlIpBackend.VHDL, source, name, ports.take(128), generics, diagnostics, timeGenerics, declarations)
    }

    internal fun blifAccepted(source: String): Boolean =
        source.length <= maxSource && source.encodeToByteArray().size <= maxSource && '\u0000' !in source

    /**
     * Ports and errors of Evolution's BlifParser; generics do not exist for BLIF and are ignored as on the desktop.
     * When the source does not parse, Evolution keeps its bundled template, so the ports and the simulation are
     * the template's. Mobile reports the error and keeps the text, which Evolution would drop on save.
     */
    fun blif(source: String, @Suppress("UNUSED_PARAMETER") overrides: Map<String, String> = emptyMap()): HdlIpDesign {
        val failure = if (!blifAccepted(source)) HdlIpDiagnostic("invalid-source", message(Messages.HdlIpInvalidSource, "backend" to "BLIF"))
            else BlifCircuitBehavior.compile(source).exceptionOrNull()?.let { HdlIpDiagnostic("parse-error", it.message.orEmpty()) }
        val compiled = BlifCircuitBehavior.compilation(source)
        val ports = (compiled.inputs + compiled.outputs).map { HdlIpPort(it.name, it.direction, it.width) }
        // Evolution allows a scalar and a bus with the same base name; Mobile terminals are addressed by name.
        val duplicate = if (ports.map { it.name }.distinct().size == ports.size) null
            else HdlIpDiagnostic("duplicate-port", message(Messages.HdlIpBlifDuplicatePorts))
        return HdlIpDesign(HdlIpBackend.BLIF, source, compiled.name, ports, diagnostics = listOfNotNull(failure, duplicate))
    }

    private fun block(text: String, name: String, diagnostics: MutableList<HdlIpDiagnostic>): String? {
        val start = Regex("\\b$name\\s*\\(", RegexOption.IGNORE_CASE).find(text) ?: return null
        var depth = 1
        var at = start.range.last + 1
        val first = at
        var quoted = false
        while (at < text.length) {
            val value = text[at++]
            if (value == '"') quoted = !quoted
            if (!quoted && value == '(') depth++
            if (!quoted && value == ')' && --depth == 0) return text.substring(first, at - 1)
        }
        diagnostics += HdlIpDiagnostic("unterminated-declaration", message(Messages.HdlIpUnterminatedDeclaration, "name" to name))
        return null
    }

    private fun withoutVhdlComments(source: String): String = buildString(source.length) {
        var at = 0
        var quoted = false
        while (at < source.length) {
            val value = source[at]
            if (!quoted && value == '-' && source.getOrNull(at + 1) == '-') {
                while (at < source.length && source[at] != '\r' && source[at] != '\n') { append(' '); at++ }
            } else {
                append(value)
                if (value == '"') quoted = !quoted
                at++
            }
        }
    }

    private val numericGenericTypes = setOf("integer", "natural", "positive", "time")
    private val timeUnits = mapOf("fs" to 1, "ps" to 1_000, "ns" to 1_000_000, "us" to 1_000_000_000)

    /**
     * A time generic in femtoseconds, as Evolution's VhdlParser stores it: an integer and an
     * optional fs, ps, ns or us unit (fs when absent), multiplied in 32-bit arithmetic.
     */
    private fun femtoseconds(raw: String, values: Map<String, Int>): Int? {
        val match = Regex("(.*?)(?:(?<=[0-9)\\s])([a-z]+))?", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return null
        val unit = match.groupValues[2].lowercase().ifEmpty { "fs" }
        val scale = timeUnits[unit] ?: return null
        return integer(match.groupValues[1].trim(), values)?.times(scale)
    }

    private fun integer(expression: String, values: Map<String, Int>): Int? = try {
        IntegerExpression(expression, values).parse()
    } catch (_: IllegalArgumentException) { null }

    private class IntegerExpression(val source: String, val values: Map<String, Int>) {
        var at = 0
        var nodes = 0
        fun parse(): Int {
            require(source.length <= 256)
            val result = sum()
            spaces()
            require(at == source.length && result in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return result.toInt()
        }
        fun spaces() {
            while (at < source.length && source[at].isWhitespace()) at++
        }
        fun sum(): Long {
            var result = product()
            while (true) {
                spaces()
                val op = source.getOrNull(at)
                if (op != '+' && op != '-') return result
                at++
                val next = product()
                result = bounded(if (op == '+') result + next else result - next)
            }
        }
        fun product(): Long {
            var result = atom()
            while (true) {
                spaces()
                val op = source.getOrNull(at)
                if (op != '*' && op != '/') return result
                at++
                val next = atom()
                require(op != '/' || next != 0L)
                result = bounded(if (op == '*') result * next else result / next)
            }
        }
        fun atom(): Long {
            require(++nodes <= 64)
            spaces()
            if (source.getOrNull(at) == '+' || source.getOrNull(at) == '-') {
                val negative = source[at++] == '-'
                return bounded(atom() * if (negative) -1 else 1)
            }
            if (source.getOrNull(at) == '(') {
                at++
                val value = sum()
                spaces()
                require(source.getOrNull(at++) == ')')
                return value
            }
            val start = at
            while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
            require(at > start)
            val token = source.substring(start, at)
            return bounded(token.toLongOrNull() ?: values[token.lowercase()]?.toLong() ?: throw IllegalArgumentException())
        }
        fun bounded(value: Long): Long {
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value
        }
    }
}
