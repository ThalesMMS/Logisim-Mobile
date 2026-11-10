package dev.logisim.mobile.core

import kotlin.math.IEEErem
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** The eighteen registered desktop FPArithmetic components and their serialized identities. */
object FloatingPointLibrary {
    private val fpWidth = AttributeKey("fpwidth", 32, String::toIntOrNull, Int::toString) {
        it in FloatingPointNumbers.widths
    }
    private val fpWidthIn = AttributeKey("fpwidthin", 32, String::toIntOrNull, Int::toString) {
        it in FloatingPointNumbers.widths
    }
    private val fpWidthOut = AttributeKey("fpwidthout", 32, String::toIntOrNull, Int::toString) {
        it in FloatingPointNumbers.widths
    }
    private val intWidth = AttributeKey.int("width", 8, 1..64)
    private val constant = AttributeKey("value", 0.0, FloatingPointNumbers::parse, Double::toString)
    private val multiplyMode = AttributeKey.choice("multiplyMode", "multiply", setOf("multiply", "fusedMultiplyAdd"))
    private val exponentMode = AttributeKey.choice("mode", "arb", setOf("arb", "exp", "expm1"))
    private val logMode = AttributeKey.choice("mode", "logxy", setOf("logxy", "log", "log10", "log1p"))
    private val roundingMode = AttributeKey.choice("mode", "round", setOf("ceil", "floor", "round", "rint", "truncate"))
    private val integerMode = AttributeKey.choice("mode", "twosComplement", setOf("twosComplement", "unsigned"))
    private val trigonometryMode = AttributeKey.choice("type", "trig", setOf("trig", "arc", "hyp"))

    val fileNames: List<String> = listOf(
        "FPConstant", "FPAdder", "FPSubtractor", "FPMultiplier", "FPDivider", "FPNegator",
        "FPExponentiator", "FPLogarithm", "FPSquareRoot", "FPAbsolute", "FPComparator", "FPMinMax",
        "FPRound", "FPTrigonometry", "FPClassificator", "FPToFP", "FPToInt", "IntToFP",
    )

    fun definitions(): List<ComponentDefinition> = fileNames.map { name ->
        val schema = schema(name)
        ComponentDefinition(
            kind = ComponentKind(name), fileName = name, library = "#FPArithmetic",
            defaultAttributes = schema.defaults, schema = schema,
            layout = { component, values -> layout(name, component.position, values) },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation =
                    evaluateNamed(name, context.attributes, context.inputs).copy(state = context.state)
            },
            supportsRotation = false,
            isCombinational = true,
        )
    }

    /** Ordered outputs follow desktop port indices, for the differential runner. */
    fun evaluate(fileName: String, attributes: Map<String, String>, inputs: List<LogicVector>): List<LogicVector> {
        val values = schema(fileName).parse(attributes)
        val layout = layout(fileName, GridPoint(0, 0), values)
        val named = layout.inputs.mapIndexed { index, port ->
            port.name to inputs.getOrElse(index) { LogicVector.unknown(port.width) }
        }.toMap()
        val output = evaluateNamed(fileName, values, named)
        return layout.outputs.map { output.outputs.getValue(it.name) }
    }

    fun delay(fileName: String, attributes: Map<String, String>): Long {
        val values = schema(fileName).parse(attributes)
        return if (fileName == "FPConstant") 1 else 2L + when (fileName) {
            "FPToFP" -> values[fpWidthOut]
            "FPToInt", "IntToFP" -> values[intWidth]
            else -> values[fpWidth]
        }
    }

    private fun schema(name: String): AttributeSchema = AttributeSchema(when (name) {
        "FPConstant" -> listOf(fpWidth, constant)
        "FPMultiplier" -> listOf(fpWidth, multiplyMode)
        "FPExponentiator" -> listOf(fpWidth, exponentMode)
        "FPLogarithm" -> listOf(fpWidth, logMode)
        "FPRound" -> listOf(fpWidth, roundingMode)
        "FPTrigonometry" -> listOf(fpWidth, trigonometryMode)
        "FPToFP" -> listOf(fpWidthIn, fpWidthOut)
        "FPToInt" -> listOf(intWidth, fpWidth, roundingMode)
        "IntToFP" -> listOf(intWidth, fpWidth, integerMode)
        else -> {
            require(name in fileNames) { "Unknown floating point component: $name." }
            listOf(fpWidth)
        }
    })

    private fun layout(name: String, origin: GridPoint, values: AttributeValues): ComponentLayout {
        val width = if (name == "FPToFP") values[fpWidthOut] else values[fpWidth]
        val inputs = mutableListOf<ComponentTerminal>()
        val outputs = mutableListOf<ComponentTerminal>()
        fun input(id: String, x: Int, y: Int, bits: Int = width) {
            inputs += ComponentTerminal(id, GridPoint(origin.x + x, origin.y + y), PortDirection.INPUT, bits)
        }
        fun output(id: String, x: Int, y: Int, bits: Int = width, label: String = id) {
            outputs += ComponentTerminal(id, GridPoint(origin.x + x, origin.y + y), PortDirection.OUTPUT, bits, label)
        }
        when (name) {
            "FPConstant" -> output("out", 0, 0)
            "FPAdder", "FPSubtractor", "FPMultiplier", "FPDivider", "FPComparator", "FPMinMax" -> {
                input("a", -40, -10)
                input("b", -40, 10)
                if (name == "FPMultiplier" && values[multiplyMode] == "fusedMultiplyAdd") input("c", -20, -20)
            }
            "FPExponentiator" -> {
                if (values[exponentMode] == "arb") {
                    input("exponent", -40, 10)
                    input("base", -40, -10)
                } else input("exponent", -40, 0)
            }
            "FPLogarithm" -> {
                if (values[logMode] == "logxy") {
                    input("in", -40, -10)
                    input("base", -40, 10)
                } else input("in", -40, 0)
            }
            "FPToFP" -> input("in", -40, 0, values[fpWidthIn])
            "IntToFP" -> input("in", -40, 0, values[intWidth])
            else -> input("in", -40, 0)
        }
        when (name) {
            "FPConstant" -> Unit
            "FPComparator" -> {
                output("gt", 0, -10, 1, ">")
                output("eq", 0, 0, 1, "=")
                output("lt", 0, 10, 1, "<")
            }
            "FPMinMax" -> {
                output("min", 0, -10)
                output("max", 0, 10)
            }
            "FPTrigonometry" -> {
                output("sin", 0, -10)
                output("tan", 0, 0)
                output("cos", 0, 10)
            }
            "FPClassificator" -> {
                listOf("negative", "zero", "subnormal", "normal", "infinite", "nan").zip(listOf(-30, -20, -10, 0, 10, 30))
                    .forEach { (id, y) -> output(id, 0, y, 1) }
            }
            "FPToInt" -> output("out", 0, 0, values[intWidth])
            "FPDivider" -> {
                output("out", 0, 0)
                output("remainder", -10, 20)
            }
            else -> output("out", 0, 0)
        }
        if (name != "FPConstant" && name != "FPClassificator") output("error", -20, 20, 1, "ERR")
        val bounds = when (name) {
            "FPConstant" -> {
                val text = if (width == 64) values[constant].toString() else FloatingPointNumbers.decode(
                    FloatingPointNumbers.encode(width, values[constant])).toFloat().toString()
                val w = maxOf(30, 8 + 7 * text.length)
                ComponentBounds(origin.x - w, origin.y - 10, origin.x, origin.y + 10)
            }
            "FPClassificator" -> ComponentBounds(origin.x - 40, origin.y - 40, origin.x, origin.y + 40)
            else -> ComponentBounds(origin.x - 40, origin.y - 20, origin.x, origin.y + 20)
        }
        return ComponentLayout(inputs + outputs, bounds)
    }

    private fun evaluateNamed(name: String, values: AttributeValues, inputs: Map<String, LogicVector>): ComponentEvaluation {
        val width = if (name == "FPToFP") values[fpWidthOut] else values[fpWidth]
        fun vector(id: String, bits: Int = width) = inputs[id] ?: LogicVector.unknown(bits)
        fun number(id: String = "in") = FloatingPointNumbers.decode(vector(id))
        fun bit(value: Boolean) = LogicVector.fromBit(if (value) LogicValue.HIGH else LogicValue.LOW)
        fun encoded(number: Double) = FloatingPointNumbers.encode(width, number)
        fun result(number: Double, error: Boolean = number.isNaN()) = mapOf("out" to encoded(number), "error" to bit(error))
        val a = if ("a" in inputs) number("a") else number()
        val b = number("b")
        val outputs = when (name) {
            "FPConstant" -> mapOf("out" to encoded(values[constant]))
            "FPAdder" -> result(a + b)
            "FPSubtractor" -> result(a - b)
            "FPMultiplier" -> result(if (values[multiplyMode] == "fusedMultiplyAdd")
                FloatingPointExact.fma(a, b, number("c")) else a * b)
            "FPDivider" -> {
                val quotient = a / b
                val remainder = a.IEEErem(b)
                mapOf("out" to encoded(quotient), "remainder" to encoded(remainder),
                    "error" to bit(quotient.isNaN() || remainder.isNaN()))
            }
            "FPNegator" -> result(a * -1.0)
            "FPSquareRoot" -> result(sqrt(a))
            "FPAbsolute" -> result(abs(a))
            "FPExponentiator" -> {
                val exponent = number("exponent")
                result(when (values[exponentMode]) {
                    "exp" -> exp(exponent)
                    "expm1" -> expm1(exponent)
                    else -> number("base").pow(exponent)
                })
            }
            "FPLogarithm" -> result(when (values[logMode]) {
                "log" -> ln(a)
                "log10" -> log10(a)
                "log1p" -> ln1p(a)
                else -> ln(a) / ln(number("base"))
            })
            "FPComparator" -> mapOf("gt" to bit(a > b), "eq" to bit(a == b), "lt" to bit(a < b),
                "error" to bit(a.isNaN() || b.isNaN()))
            "FPMinMax" -> mapOf("min" to encoded(min(a, b)), "max" to encoded(max(a, b)),
                "error" to bit(a.isNaN() || b.isNaN()))
            "FPRound" -> result(rounded(a, values[roundingMode]), a.isNaN())
            "FPTrigonometry" -> {
                val outputs = when (values[trigonometryMode]) {
                    "arc" -> listOf(asin(a), atan(a), acos(a))
                    "hyp" -> listOf(sinh(a), tanh(a), cosh(a))
                    else -> listOf(sin(a), tan(a), cos(a))
                }
                mapOf("sin" to encoded(outputs[0]), "tan" to encoded(outputs[1]), "cos" to encoded(outputs[2]),
                    "error" to bit(a.isNaN() || outputs.any { it.isNaN() }))
            }
            "FPClassificator" -> {
                val input = vector("in")
                val raw = input.toULongOrNull() ?: ULong.MAX_VALUE
                val fractionBits = when (width) { 8 -> 3; 16 -> 10; 32 -> 23; else -> 52 }
                val exponentBits = when (width) { 8 -> 4; 16 -> 5; 32 -> 8; else -> 11 }
                val maximumExponent = (1uL shl exponentBits) - 1uL
                val exponent = (raw shr fractionBits) and maximumExponent
                val fraction = raw and ((1uL shl fractionBits) - 1uL)
                mapOf("negative" to bit(raw and (1uL shl (width - 1)) != 0uL), "zero" to bit(a == 0.0),
                    "subnormal" to bit(exponent == 0uL && fraction != 0uL),
                    "normal" to bit(exponent > 0uL && exponent < maximumExponent),
                    "infinite" to bit(a.isInfinite()), "nan" to bit(a.isNaN()))
            }
            "FPToFP" -> result(number())
            "FPToInt" -> mapOf("out" to LogicVector.of(values[intWidth], rounded(a, values[roundingMode]).toLong().toULong()),
                "error" to bit(a.isNaN()))
            "IntToFP" -> {
                val input = vector("in", values[intWidth])
                val raw = input.toULongOrNull()
                val converted = when {
                    raw == null -> Double.NaN
                    values[integerMode] == "unsigned" -> raw.toDouble()
                    input.width == 64 -> raw.toLong().toDouble()
                    raw and (1uL shl (input.width - 1)) != 0uL ->
                        (raw or (ULong.MAX_VALUE shl input.width)).toLong().toDouble()
                    else -> raw.toDouble()
                }
                result(converted)
            }
            else -> error("Unknown floating point component: $name.")
        }
        return ComponentEvaluation(outputs, delay = delay(name, values.raw))
    }

    private fun rounded(number: Double, mode: String): Double = when (mode) {
        "ceil" -> ceil(number)
        "floor" -> floor(number)
        "round" -> FloatingPointNumbers.roundToLong(number).toDouble()
        "rint" -> round(number)
        else -> number.toLong().toDouble()
    }
}
