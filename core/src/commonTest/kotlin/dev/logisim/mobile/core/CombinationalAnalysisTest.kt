package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombinationalAnalysisTest {
    @Test
    fun parsesDesktopNotationAndItsSymbolSpecificPrecedence() {
        val aliases = listOf("!A", "~A", "¬A", "NOT A", "not A", "A'")
        for (source in aliases) {
            assertTrue(BooleanExpressionParser.parse(source).evaluate(mapOf("A" to false)), source)
            assertFalse(BooleanExpressionParser.parse(source).evaluate(mapOf("A" to true)), source)
        }
        val values = mapOf("A" to false, "B" to false, "C" to true)
        assertFalse(BooleanExpressionParser.parse("A & B + C").evaluate(values))
        assertFalse(BooleanExpressionParser.parse("A * B ^ C").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("A AND B OR C").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("A and B or C").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("A ⊙ B").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("A == B").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("A != C").evaluate(values))
        assertTrue(BooleanExpressionParser.parse("⊤ & !⊥").evaluate(emptyMap()))
    }

    @Test
    fun supportsImplicitAndAssignmentsBusNamesAndNamedKeywords() {
        val expression = BooleanExpressionParser.parse("A(B + C')")
        assertTrue(expression.evaluate(mapOf("A" to true, "B" to false, "C" to false)))
        assertFalse(expression.evaluate(mapOf("A" to false, "B" to true, "C" to false)))
        val assignment = BooleanExpressionParser.parseAssignment("Saída := A:3 ⊕ ônibus[0]")
        assertEquals("Saída", assignment.output)
        assertEquals(setOf("A[3]", "ônibus[0]"), assignment.expression.variables())
        assertEquals(setOf("A[1]"), BooleanExpressionParser.parse("A[ 01 ] | A:01").variables())
        assertTrue(assignment.expression.evaluate(mapOf("A[3]" to true, "ônibus[0]" to false)))
        assertTrue(BooleanExpressionParser.parse("AND & B", setOf("AND", "B"))
            .evaluate(mapOf("AND" to true, "B" to true)))
        val unusual = BooleanExpression.Binary(BooleanOperator.AND,
            BooleanExpression.Variable("Input A"), BooleanExpression.Variable("OR"))
        assertTrue(BooleanExpressionParser.parse(unusual.format()).evaluate(mapOf("Input A" to true, "OR" to true)))
        assertEquals("Output Y", BooleanExpressionParser.parseAssignment("`Output Y` := `Input A`").output)
    }

    @Test
    fun rejectsMalformedAndExcessiveExpressions() {
        listOf("", "A +", "(A", "A)", "2", "A[bad]", "A[-1]").forEach {
            assertFailsWith<BooleanExpressionException>(it) { BooleanExpressionParser.parse(it) }
        }
        assertFailsWith<BooleanExpressionException> { BooleanExpressionParser.parse("!".repeat(140) + "A") }
        assertFailsWith<BooleanExpressionException> { BooleanExpressionParser.parse("A" + "'".repeat(16_000)) }
        assertFailsWith<BooleanExpressionException> { BooleanExpressionParser.parse(List(150) { "A" }.joinToString(" & ")) }
    }

    @Test
    fun truthTableUsesMostSignificantInputFirstAndEditsOneCell() {
        val table = table("A & !B")
        assertEquals(listOf(TruthCell.ZERO, TruthCell.ZERO, TruthCell.ONE, TruthCell.ZERO), table.rows.map { it.single() })
        assertEquals(mapOf("A" to true, "B" to false), table.assignment(2))
        val edited = table.withCell(0, 0, TruthCell.DONT_CARE)
        assertEquals(TruthCell.DONT_CARE, edited.rows[0][0])
        assertEquals(TruthCell.ZERO, table.rows[0][0])
        assertEquals(table.rows.drop(1), edited.rows.drop(1))
    }

    @Test
    fun minimizesBothNormalFormsAndUsesDontCares() {
        val and = BooleanMinimizer.minimize(table("A & B"))
        assertEquals(1, and.implicants.size)
        assertEquals(2, and.literalCount)
        val or = BooleanMinimizer.minimize(table("A | B"), form = NormalForm.POS)
        assertEquals(1, or.implicants.size)
        assertEquals(2, or.literalCount)
        val xor = BooleanMinimizer.minimize(table("A ^ B"))
        assertEquals(2, xor.implicants.size)
        assertEquals(4, xor.literalCount)
        val dc = TruthTable(listOf("A", "B", "C"), listOf("Y"), (0..7).map {
            listOf(when (it) { 1, 3 -> TruthCell.ONE; 5, 7 -> TruthCell.DONT_CARE; else -> TruthCell.ZERO })
        })
        val simplified = BooleanMinimizer.minimize(dc)
        assertEquals(1, simplified.literalCount)
        assertEquals("C", simplified.expression.format())
    }

    @Test
    fun constantsAndAllDontCareMatchDesktopConventions() {
        val empty = TruthTable(emptyList(), listOf("Y"), listOf(listOf(TruthCell.ONE)))
        assertEquals("1", BooleanMinimizer.minimize(empty).expression.format())
        val dc = TruthTable(listOf("A"), listOf("Y"), List(2) { listOf(TruthCell.DONT_CARE) })
        assertEquals("0", BooleanMinimizer.minimize(dc, form = NormalForm.SOP).expression.format())
        assertEquals("1", BooleanMinimizer.minimize(dc, form = NormalForm.POS).expression.format())
    }

    @Test
    fun everyThreeInputFunctionMatchesItsExactSopAndPos() {
        for (function in 0..255) {
            val table = TruthTable(listOf("A", "B", "C"), listOf("Y"), (0..7).map { row ->
                listOf(if (function and (1 shl row) == 0) TruthCell.ZERO else TruthCell.ONE)
            })
            for (form in NormalForm.entries) {
                val reduced = BooleanMinimizer.minimize(table, form = form)
                for (row in 0..7) assertEquals(table.rows[row][0] == TruthCell.ONE,
                    reduced.expression.evaluate(table.assignment(row)), "function=$function form=$form row=$row")
                val minimum = bruteForceMinimum(table, form)
                assertEquals(minimum.first, reduced.implicants.size, "terms function=$function form=$form")
                assertEquals(minimum.second, reduced.literalCount, "literals function=$function form=$form")
            }
        }
    }

    @Test
    fun karnaughRowsAndColumnsFollowGrayCodeAndCoverMembership() {
        val table = TruthTable(listOf("A", "B", "C", "D"), listOf("Y"), (0..15).map {
            listOf(if (it and 1 == 1) TruthCell.ONE else TruthCell.ZERO)
        })
        val reduced = BooleanMinimizer.minimize(table)
        val map = KarnaughMap.from(table, cover = reduced)
        assertEquals(listOf("00", "01", "11", "10"), map.rowLabels)
        assertEquals(listOf("00", "01", "11", "10"), map.columnLabels)
        assertEquals(listOf(0, 1, 3, 2), map.cells.first().map { it.truthRow })
        assertEquals(listOf(8, 9, 11, 10), map.cells.last().map { it.truthRow })
        assertEquals(8, map.cells.flatten().count { it.groups == listOf(0) })
    }

    @Test
    fun generatedMultiOutputCircuitMatchesTheTableInTheSimulatorAndCodec() {
        val specification = TruthTable.fromExpressions(linkedMapOf(
            "sum" to BooleanExpressionParser.parse("A ^ B ^ C"),
            "carry" to BooleanExpressionParser.parse("(A & B) | (A & C) | (B & C)"),
        ), inputs = listOf("A", "B", "C"))
        for (form in NormalForm.entries) {
            val generated = AnalysisCircuitGenerator.generate(specification, form = form)
            assertTrue(AnalysisCircuitGenerator.verify(specification, generated), form.toString())
            val decoded = CircCodec.read(CircCodec.write(generated)) as CircImportResult.Success
            assertTrue(AnalysisCircuitGenerator.verify(specification, decoded.document))
            val editor = CircuitEditor(DemoCircuits.andGate())
            editor.replace(generated)
            assertTrue(editor.undo())
            assertEquals(DemoCircuits.andGate(), editor.document)
            assertTrue(editor.redo())
            assertEquals(generated, editor.document)
        }
    }

    @Test
    fun nandOnlyGenerationMatchesBothNormalFormsAfterCircRoundTrip() {
        val specification = TruthTable.fromExpressions(linkedMapOf(
            "AND" to BooleanExpressionParser.parse("A & B"),
            "XOR" to BooleanExpressionParser.parse("A ^ B"),
            "one" to BooleanExpression.Constant(true),
        ), inputs = listOf("A", "B"))
        val nonGates = setOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.CONSTANT, ComponentKind.TUNNEL)
        for (form in NormalForm.entries) {
            val generated = AnalysisCircuitGenerator.generate(specification, form = form, nandOnly = true)
            val decoded = CircCodec.read(CircCodec.write(generated)) as CircImportResult.Success
            val gates = decoded.document.components.filterNot { it.kind in nonGates }
            assertTrue(gates.isNotEmpty())
            assertTrue(gates.all { it.kind == ComponentKind.NAND && it.inputPorts().size == 2 })
            assertTrue(AnalysisCircuitGenerator.verify(specification, decoded.document), form.toString())
        }
    }

    @Test
    fun nandOnlyExpressionGenerationSupportsDirectXorAndXnor() {
        val expressions = linkedMapOf(
            "xor" to BooleanExpressionParser.parse("A ^ B"),
            "xnor" to BooleanExpressionParser.parse("A ⊙ B"),
        )
        val table = TruthTable.fromExpressions(expressions, inputs = listOf("A", "B"))
        val generated = AnalysisCircuitGenerator.generateExpressions(table.inputs, expressions, nandOnly = true)
        val wiring = setOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.TUNNEL)
        assertEquals(setOf(ComponentKind.NAND), generated.components.map { it.kind }.toSet() - wiring)
        assertTrue(AnalysisCircuitGenerator.verify(table, generated))
    }

    @Test
    fun analyzesOriginalAndCircuitAndRejectsStorage() {
        assertEquals(listOf(TruthCell.ZERO, TruthCell.ZERO, TruthCell.ZERO, TruthCell.ONE),
            CircuitAnalyzer.truthTable(CircuitProject(listOf(DemoCircuits.andGate()))).rows.map { it.single() })
        assertFailsWith<IllegalArgumentException> {
            CircuitAnalyzer.truthTable(CircuitProject(listOf(DemoCircuits.flipFlop())))
        }
        val plugin = ComponentKind("TEST_STATEFUL_PLUGIN")
        val project = CircuitProject(listOf(CircuitDocument(components = listOf(CircuitComponent("memory", plugin, GridPoint(0, 0))))))
        assertFailsWith<IllegalArgumentException> { CircuitAnalyzer.truthTable(project) }
        val registry = ComponentRegistry(listOf(ComponentDefinition(plugin, "Test storage", "Test", isCombinational = false)))
        assertFailsWith<IllegalArgumentException> { CircuitAnalyzer.truthTable(project, registry = registry) }
    }

    @Test
    fun generatedCircuitVerificationRequiresADrivenOutputEvenForDontCareRows() {
        val specification = TruthTable(emptyList(), listOf("Y"), listOf(listOf(TruthCell.DONT_CARE)))
        val disconnected = CircuitDocument(
            components = listOf(CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100), label = "Y")),
        )
        assertFalse(AnalysisCircuitGenerator.verify(specification, disconnected))
    }

    @Test
    fun limitsAndCancellationFailBeforeReturningAnApproximateResult() {
        assertFailsWith<AnalysisLimitException> {
            TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parse("A & B")), limits = AnalysisLimits(maxInputBits = 1))
        }
        assertFailsWith<AnalysisLimitException> {
            BooleanMinimizer.minimize(table("A ^ B"), limits = AnalysisLimits(maxSearchSteps = 1))
        }
        assertFailsWith<AnalysisCancelledException> {
            BooleanMinimizer.minimize(table("A & B"), cancelled = { true })
        }
        assertFailsWith<AnalysisCancelledException> {
            AnalysisCircuitGenerator.generate(table("A & B"), cancelled = { true })
        }
        assertFailsWith<AnalysisLimitException> {
            TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parse("A & B")),
                limits = AnalysisLimits(maxSearchSteps = 3))
        }
    }

    private fun table(expression: String) = TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parse(expression)),
        inputs = listOf("A", "B"))

    private fun bruteForceMinimum(table: TruthTable, form: NormalForm): Pair<Int, Int> {
        val target = if (form == NormalForm.SOP) TruthCell.ONE else TruthCell.ZERO
        val targets = table.rows.indices.filter { table.rows[it][0] == target }.fold(0) { bits, row -> bits or (1 shl row) }
        if (targets == 0) return 0 to 0
        val cubes = mutableListOf<Pair<Int, Int>>()
        for (mask in 0..7) for (bits in 0..7) {
            if (bits and mask != 0) continue
            val covered = (0..7).filter { (it xor bits) and mask.inv() and 7 == 0 }.fold(0) { value, row -> value or (1 shl row) }
            if (covered != 0 && covered and targets.inv() == 0) cubes += covered to (3 - mask.countOneBits())
        }
        val best = Array<Pair<Int, Int>?>(256) { null }
        best[0] = 0 to 0
        for (covered in 0..255) {
            val previous = best[covered] ?: continue
            for ((cube, literals) in cubes) {
                val next = covered or cube
                val cost = previous.first + 1 to previous.second + literals
                val old = best[next]
                if (old == null || cost.first < old.first || cost.first == old.first && cost.second < old.second) best[next] = cost
            }
        }
        return requireNotNull(best[targets])
    }
}
