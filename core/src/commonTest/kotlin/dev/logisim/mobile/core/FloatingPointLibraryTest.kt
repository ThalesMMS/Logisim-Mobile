package dev.logisim.mobile.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FloatingPointLibraryTest {
    private fun value(width: Int, number: Double) = FloatingPointNumbers.encode(width, number)
    private fun evaluate(name: String, width: Int = 32, numbers: List<Double> = emptyList(),
                         attributes: Map<String, String> = emptyMap()): List<LogicVector> =
        FloatingPointLibrary.evaluate(name, mapOf("fpwidth" to width.toString()) + attributes,
            numbers.map { value(width, it) })

    @Test
    fun everyRegisteredComponentProvidesAllFourFormats() {
        val definitions = FloatingPointLibrary.definitions()
        assertEquals(18, definitions.size)
        assertEquals(18, definitions.map { it.fileName }.distinct().size)
        val registry = ComponentRegistry(definitions)
        for (definition in definitions) for (width in FloatingPointNumbers.widths) {
            val component = registry.create(definition.kind, "fp", GridPoint(100, 100),
                mapOf("fpwidth" to "$width", "fpwidthin" to "$width", "fpwidthout" to "$width"))
            val layout = registry.layout(component)
            assertTrue(layout.outputs.isNotEmpty(), "${definition.fileName}/$width")
            assertEquals(layout.ports.size, layout.ports.map { it.name }.distinct().size)
        }
    }

    @Test
    fun conversionAndClassificationLayoutsMatchTheDesktopCoordinates() {
        val registry = ComponentRegistry(FloatingPointLibrary.definitions())
        fun layout(name: String, attrs: Map<String, String>) = registry.layout(
            registry.create(ComponentKind(name), "fp", GridPoint(100, 100), attrs))
        val toInt = layout("FPToInt", mapOf("fpwidth" to "8", "width" to "64"))
        assertEquals(listOf(8, 64, 1), toInt.ports.map { it.width })
        assertEquals(listOf(GridPoint(60, 100), GridPoint(100, 100), GridPoint(80, 120)), toInt.ports.map { it.position })
        val toFp = layout("FPToFP", mapOf("fpwidthin" to "16", "fpwidthout" to "32"))
        assertEquals(listOf(16, 32, 1), toFp.ports.map { it.width })
        val classification = layout("FPClassificator", mapOf("fpwidth" to "64"))
        assertEquals(ComponentBounds(60, 60, 100, 140), classification.bounds)
        assertEquals(listOf(GridPoint(100, 70), GridPoint(100, 80), GridPoint(100, 90),
            GridPoint(100, 100), GridPoint(100, 110), GridPoint(100, 130)), classification.outputs.map { it.position })
        val fused = layout("FPMultiplier", mapOf("multiplyMode" to "fusedMultiplyAdd"))
        assertEquals(listOf(GridPoint(60, 90), GridPoint(60, 110), GridPoint(80, 80)), fused.inputs.map { it.position })
    }

    @Test
    fun literalEncodingsPreserveZeroSubnormalInfinityAndNan() {
        val cases = listOf(
            Triple(8, 0x38uL, 0x78uL), Triple(16, 0x3c00uL, 0x7c00uL),
            Triple(32, 0x3f800000uL, 0x7f800000uL), Triple(64, 0x3ff0000000000000uL, 0x7ff0000000000000uL),
        )
        for ((width, one, infinity) in cases) {
            assertEquals(one, value(width, 1.0).value)
            assertEquals(1uL shl (width - 1), value(width, -0.0).value)
            assertEquals(infinity, value(width, Double.POSITIVE_INFINITY).value)
            val subnormal = LogicVector.of(width, 1uL)
            assertEquals(subnormal, value(width, FloatingPointNumbers.decode(subnormal)))
            assertTrue(FloatingPointNumbers.decode(value(width, Double.NaN)).isNaN())
        }
    }

    @Test
    fun smallFormatsRoundTiesToEvenAndPreserveNegativeUnderflow() {
        assertEquals(0x38uL, value(8, 1.0625).value)
        assertEquals(0x3auL, value(8, 1.1875).value)
        assertEquals(0uL, value(8, 2.0.pow(-10)).value)
        assertEquals(0x80uL, value(8, -2.0.pow(-10)).value)
        assertEquals(0x3c00uL, value(16, 1.0 + 2.0.pow(-11)).value)
        assertEquals(0x3c02uL, value(16, 1.0 + 3.0 * 2.0.pow(-11)).value)
        assertEquals(0x7bffuL, value(16, 65504.0).value)
        assertEquals(0x7c00uL, value(16, 65520.0).value)
        assertEquals(0x77uL, value(8, 240.0).value)
        assertEquals(0x78uL, value(8, 248.0).value)
    }

    @Test
    fun everySmallFormatEncodingRoundTripsOrRemainsNan() {
        for (width in listOf(8, 16)) for (bits in 0 until (1 shl width)) {
            val original = LogicVector.of(width, bits.toULong())
            val decoded = FloatingPointNumbers.decode(original)
            val encoded = value(width, decoded)
            if (decoded.isNaN()) assertTrue(FloatingPointNumbers.decode(encoded).isNaN())
            else assertEquals(original, encoded, "FP$width encoding $bits")
        }
    }

    @Test
    fun arithmeticAndRemainderAreNotIntegerOrTruncatingOperations() {
        for (width in FloatingPointNumbers.widths) {
            assertEquals(value(width, 5.0), evaluate("FPAdder", width, listOf(3.0, 2.0))[0])
            assertEquals(value(width, 1.0), evaluate("FPSubtractor", width, listOf(3.0, 2.0))[0])
            assertEquals(value(width, 6.0), evaluate("FPMultiplier", width, listOf(3.0, 2.0))[0])
            assertEquals(listOf(value(width, 1.5), value(width, -1.0), LogicVector.low(1)),
                evaluate("FPDivider", width, listOf(3.0, 2.0)))
            assertEquals(value(width, 2.0), evaluate("FPSquareRoot", width, listOf(4.0))[0])
            assertEquals(value(width, 2.0), evaluate("FPAbsolute", width, listOf(-2.0))[0])
            assertEquals(value(width, -0.0), evaluate("FPNegator", width, listOf(0.0))[0])
            assertEquals(listOf(LogicVector.high(1), LogicVector.low(1), LogicVector.low(1), LogicVector.low(1)),
                evaluate("FPComparator", width, listOf(3.0, 2.0)))
        }
    }

    @Test
    fun fusedMultiplyAddRoundsOnlyOnceEvenAcrossOverflowAndUnderflow() {
        val attributes = mapOf("multiplyMode" to "fusedMultiplyAdd")
        val result = evaluate("FPMultiplier", 64,
            listOf(1.0 + 2.0.pow(-27), 1.0 - 2.0.pow(-27), -1.0), attributes)
        assertEquals(value(64, -2.0.pow(-54)), result[0])
        assertEquals(value(64, Double.MAX_VALUE),
            evaluate("FPMultiplier", 64, listOf(Double.MAX_VALUE, 2.0, -Double.MAX_VALUE), attributes)[0])
        assertEquals(2uL, evaluate("FPMultiplier", 64, listOf(Double.MIN_VALUE, 0.5, Double.MIN_VALUE), attributes)[0].value)
        assertEquals(value(64, -0.0), evaluate("FPMultiplier", 64, listOf(-0.0, 3.0, -0.0), attributes)[0])
        assertEquals(value(64, 0.0), evaluate("FPMultiplier", 64, listOf(-0.0, 3.0, 0.0), attributes)[0])
    }

    @Test
    fun invalidOperandsExposeNanAndErrorPins() {
        for (width in FloatingPointNumbers.widths) {
            val divided = evaluate("FPDivider", width, listOf(1.0, 0.0))
            assertTrue(FloatingPointNumbers.decode(divided[0]).isInfinite())
            assertTrue(FloatingPointNumbers.decode(divided[1]).isNaN())
            assertEquals(LogicVector.high(1), divided[2])
            assertEquals(LogicVector.high(1), evaluate("FPSquareRoot", width, listOf(-1.0))[1])
            val unknown = FloatingPointLibrary.evaluate("FPAdder", mapOf("fpwidth" to "$width"),
                listOf(LogicVector.unknown(width), value(width, 1.0)))
            assertTrue(FloatingPointNumbers.decode(unknown[0]).isNaN())
            assertEquals(LogicVector.high(1), unknown[1])
        }
    }

    @Test
    fun roundingModesKeepTheDesktopDifferences() {
        assertEquals(value(64, 3.0), evaluate("FPRound", 64, listOf(2.5))[0])
        assertEquals(value(64, -2.0), evaluate("FPRound", 64, listOf(-2.5))[0])
        assertEquals(value(64, 2.0), evaluate("FPRound", 64, listOf(2.5), mapOf("mode" to "rint"))[0])
        assertEquals(value(64, -0.0), evaluate("FPRound", 64, listOf(-0.1), mapOf("mode" to "rint"))[0])
        assertEquals(0L, FloatingPointNumbers.roundToLong(Double.fromBits(0x3fdfffffffffffffL)))
        assertEquals(listOf(value(64, 0.0), LogicVector.high(1)), evaluate("FPRound", 64, listOf(Double.NaN)))
        assertEquals(value(64, Long.MAX_VALUE.toDouble()), evaluate("FPRound", 64, listOf(Double.POSITIVE_INFINITY))[0])
        assertEquals(value(64, Double.POSITIVE_INFINITY),
            evaluate("FPRound", 64, listOf(Double.POSITIVE_INFINITY), mapOf("mode" to "ceil"))[0])
    }

    @Test
    fun integerConversionsPreserveSaturationMaskingAndSignedness() {
        assertEquals(listOf(LogicVector.of(8, 255uL), LogicVector.low(1)),
            evaluate("FPToInt", 64, listOf(Double.POSITIVE_INFINITY)))
        assertEquals(listOf(LogicVector.low(8), LogicVector.low(1)),
            evaluate("FPToInt", 64, listOf(Double.NEGATIVE_INFINITY)))
        assertEquals(listOf(LogicVector.low(8), LogicVector.high(1)), evaluate("FPToInt", 64, listOf(Double.NaN)))
        val signed = FloatingPointLibrary.evaluate("IntToFP", mapOf("width" to "64", "fpwidth" to "64"),
            listOf(LogicVector.of(64, ULong.MAX_VALUE)))
        val unsigned = FloatingPointLibrary.evaluate("IntToFP", mapOf("width" to "64", "fpwidth" to "64", "mode" to "unsigned"),
            listOf(LogicVector.of(64, ULong.MAX_VALUE)))
        assertEquals(value(64, -1.0), signed[0])
        assertEquals(value(64, 2.0.pow(64)), unsigned[0])
        for (from in FloatingPointNumbers.widths) for (to in FloatingPointNumbers.widths) {
            val converted = FloatingPointLibrary.evaluate("FPToFP", mapOf("fpwidthin" to "$from", "fpwidthout" to "$to"),
                listOf(value(from, -1.5)))
            assertEquals(listOf(value(to, -1.5), LogicVector.low(1)), converted)
        }
    }

    @Test
    fun classificationUsesRawSignAndTheDesktopUndefinedPolicy() {
        for (width in FloatingPointNumbers.widths) {
            val attrs = mapOf("fpwidth" to "$width")
            assertEquals(listOf(1, 1, 0, 0, 0, 0), classify(attrs, value(width, -0.0)))
            assertEquals(listOf(0, 0, 1, 0, 0, 0), classify(attrs, LogicVector.of(width, 1uL)))
            assertEquals(listOf(0, 0, 0, 1, 0, 0), classify(attrs, value(width, 1.0)))
            assertEquals(listOf(1, 0, 0, 0, 1, 0), classify(attrs, value(width, Double.NEGATIVE_INFINITY)))
            assertEquals(listOf(1, 0, 0, 0, 0, 1), classify(attrs, LogicVector.unknown(width)))
        }
    }

    private fun classify(attributes: Map<String, String>, input: LogicVector) =
        FloatingPointLibrary.evaluate("FPClassificator", attributes, listOf(input)).map { it.value.toInt() }

    @Test
    fun advancedOperationsAndModesAreAvailable() {
        for (width in FloatingPointNumbers.widths) {
            assertEquals(value(width, 8.0), evaluate("FPExponentiator", width, listOf(3.0, 2.0))[0])
            assertEquals(value(width, 1.0), evaluate("FPExponentiator", width, listOf(0.0), mapOf("mode" to "exp"))[0])
            assertEquals(value(width, 0.0), evaluate("FPExponentiator", width, listOf(0.0), mapOf("mode" to "expm1"))[0])
            assertEquals(value(width, 3.0), evaluate("FPLogarithm", width, listOf(8.0, 2.0))[0])
            assertEquals(value(width, 0.0), evaluate("FPLogarithm", width, listOf(0.0), mapOf("mode" to "log1p"))[0])
            assertEquals(listOf(value(width, 0.0), value(width, 0.0), value(width, 1.0), LogicVector.low(1)),
                evaluate("FPTrigonometry", width, listOf(0.0)))
            assertEquals(listOf(value(width, 0.0), value(width, 0.0), value(width, 1.0), LogicVector.low(1)),
                evaluate("FPTrigonometry", width, listOf(0.0), mapOf("type" to "hyp")))
            assertEquals(listOf(value(width, -0.0), value(width, 0.0), LogicVector.low(1)),
                evaluate("FPMinMax", width, listOf(0.0, -0.0)))
        }
    }

    @Test
    fun schemasRejectUnsupportedFormatsAndOptions() {
        assertFailsWith<IllegalArgumentException> { evaluate("FPAdder", 24, listOf(1.0, 2.0)) }
        assertFailsWith<IllegalArgumentException> { evaluate("FPRound", attributes = mapOf("mode" to "bankers")) }
        assertEquals(18L, FloatingPointLibrary.delay("FPAdder", mapOf("fpwidth" to "16")))
        assertEquals(10L, FloatingPointLibrary.delay("FPToInt", mapOf("fpwidth" to "64", "width" to "8")))
        assertEquals(1.5, FloatingPointNumbers.parse("1.5f"))
        assertEquals(6.0, FloatingPointNumbers.parse("0x1.8p2"))
    }
}
