package dev.logisim.mobile

import dev.logisim.mobile.core.AnalysisLimits
import dev.logisim.mobile.core.BooleanExpressionParser
import dev.logisim.mobile.core.BooleanMinimizer
import dev.logisim.mobile.core.NormalForm
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthCell.DONT_CARE
import dev.logisim.mobile.core.TruthCell.ONE
import dev.logisim.mobile.core.TruthCell.ZERO
import dev.logisim.mobile.core.TruthTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalysisSessionTest {
    private fun table(vararg assignments: String, inputs: List<String>? = null): TruthTable {
        val expressions = assignments.map { BooleanExpressionParser.parseAssignment(it) }.associate { it.output to it.expression }
        return if (inputs == null) TruthTable.fromExpressions(expressions) else TruthTable.fromExpressions(expressions, inputs)
    }

    private class CountingSpool : AnalysisTableSpool {
        var closed = 0
        override fun append(record: ByteArray) = Unit
        override fun seal() = Unit
        override fun read(index: Int, record: ByteArray) = Unit
        override fun close() { closed++ }
    }

    @Test
    fun anotherDocumentResetsTheSessionAndClosesTheImportedTable() {
        val session = AnalysisSession()
        session.bind(1L)
        session.load(table("Y := A & B"))
        session.edit(session.table!!.withCell(0, 0, ONE))
        session.inputText = "P, Q"
        // The same document keeps everything, as when the sheet is closed and reopened.
        session.bind(1L)
        assertEquals(ONE, session.table!!.rows[0][0])
        assertEquals("P, Q", session.inputText)

        val spool = CountingSpool()
        val imported = ImportedPagedTruthTable(List(14) { "I$it" }, listOf("Y"), IntArray(1 shl 14), 1, spool)
        session.loadPage(imported.analysisPage(0) { false })
        assertSame(imported, session.retainedImport)
        session.bind(2L)
        assertEquals(1, spool.closed)
        assertNull(session.retainedImport)
        assertNull(session.page)
        assertNull(session.table)
        assertFalse(session.canUndo)
        assertEquals("A, B", session.inputText)
        assertEquals(2L, session.documentIdentity)

        // Tearing the tool down closes a retained import once, with its page.
        val again = CountingSpool()
        session.loadPage(ImportedPagedTruthTable(List(14) { "I$it" }, listOf("Y"), IntArray(1 shl 14), 1, again).analysisPage(0) { false })
        session.close()
        session.close()
        assertEquals(1, again.closed)
        assertNull(session.page)
    }

    @Test
    fun undoAndRedoRestoreTheExactRows() {
        val session = AnalysisSession()
        val original = table("Y := A ^ B")
        session.load(original)
        assertFalse(session.dirty)
        val first = original.withCell(0, 0, ONE)
        session.edit(first)
        val second = first.withCell(3, 0, DONT_CARE)
        session.edit(second)
        assertTrue(session.dirty)
        session.undo()
        assertEquals(first.rows, session.table!!.rows)
        session.undo()
        assertSame(original, session.table)
        // Back to the table as it was read: nothing to confirm before replacing it.
        assertFalse(session.dirty)
        assertFalse(session.canUndo)
        session.redo()
        session.redo()
        assertEquals(second.rows, session.table!!.rows)
        assertFalse(session.canRedo)
        // A new edit drops the redo branch.
        session.undo()
        session.edit(first.withCell(1, 0, ZERO))
        assertFalse(session.canRedo)
    }

    @Test
    fun replacementsAndStructureChangesAreUndoableAndClearDerivedState() {
        val session = AnalysisSession()
        val created = table("Y := A & B")
        session.load(created)
        session.edit(created.withCell(0, 0, ONE))
        session.selecting = true
        session.block = TruthTableBlock(TruthTableCellPosition(0, 0), TruthTableCellPosition(1, 2))
        val read = table("S := A ^ B", "Cout := A & B")
        session.load(read, circuit = "Adder")
        assertFalse(session.dirty)
        assertEquals("Adder", session.sourceCircuit)
        assertNull(session.block)
        session.outputIndex = 1
        session.editStructure(read.withOutputAdded("Z", 0))
        // The selected output follows its column.
        assertEquals(2, session.outputIndex)
        session.editStructure(session.table!!.withOutputRemoved(2))
        assertEquals(1, session.outputIndex)
        session.undo()
        session.undo()
        assertSame(read, session.table)
        session.undo()
        assertEquals(ONE, session.table!!.rows[0][0])
        assertEquals(listOf("Y"), session.table!!.outputs)
        assertEquals(0, session.outputIndex)

        // New table returns to the form with the current names, and Undo brings the table back.
        session.newTable()
        assertNull(session.table)
        assertEquals("A, B", session.inputText)
        assertEquals("Y", session.outputText)
        session.undo()
        assertEquals(listOf("Y"), session.table!!.outputs)
    }

    @Test
    fun historyIsBoundedByTableSize() {
        val small = AnalysisSession()
        var current = table("Y := A & B")
        small.load(current)
        repeat(80) {
            current = current.withCell(it % 4, 0, current.rows[it % 4][0].next())
            small.edit(current)
        }
        assertEquals(50, small.historySize)

        val large = AnalysisSession(historyCells = 4096 * 8)
        var wide = TruthTable(List(12) { "I$it" }, List(4) { "O$it" }, List(4096) { List(4) { ZERO } })
        large.load(wide)
        repeat(10) {
            wide = wide.withCell(it, 0, ONE)
            large.edit(wide)
        }
        assertEquals(2, large.historySize)
    }

    @Test
    fun derivedExpressionsParseBackIntoTheSameTable() {
        val adder = table("S := A ^ B ^ Cin", "Cout := A & B | Cin & (A ^ B)")
        for (form in NormalForm.entries) {
            val text = adder.derivedExpressions(form)!!
            assertTrue(text.startsWith("S := ") && "\nCout := " in text, text)
            assertEquals(adder, TruthTable.fromExpressions(text.lines().map { BooleanExpressionParser.parseAssignment(it) }
                .associate { it.output to it.expression }, inputs = adder.inputs))
        }
        // Names the parser needs quoted round-trip too, and constant outputs become 0 or 1.
        val odd = TruthTable(listOf("carry in", "B"), listOf("S[0]", "AND", "zero"), listOf(
            listOf(ZERO, ZERO, ZERO), listOf(ONE, ZERO, ZERO), listOf(ONE, ZERO, ZERO), listOf(ZERO, ONE, ZERO),
        ))
        val text = odd.derivedExpressions()!!
        assertTrue(text.startsWith("`S[0]` := ") && "\n`AND` := " in text && text.endsWith("zero := 0"), text)
        assertEquals(odd, TruthTable.fromExpressions(text.lines().map { BooleanExpressionParser.parseAssignment(it, odd.inputs.toSet()) }
            .associate { it.output to it.expression }, inputs = odd.inputs))
        // Above exact minimization the canonical form is used; above eight inputs nothing is derived.
        val seven = TruthTable(List(7) { "I$it" }, listOf("Y"), List(128) { listOf(if (it % 3 == 0) ONE else ZERO) })
        val canonical = seven.derivedExpressions(limits = AnalysisLimits(maxMinimizationInputBits = 6))!!
        assertEquals(seven, TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parseAssignment(canonical).expression), inputs = seven.inputs))
        assertNull(TruthTable(List(9) { "I$it" }, listOf("Y"), List(512) { listOf(ZERO) }).derivedExpressions())
    }

    @Test
    fun appliedExpressionsReplaceOnlyTheirOutputsAndKeepTheInputOrder() {
        val current = TruthTable(listOf("B", "A"), listOf("Y", "Z"), listOf(
            listOf(ZERO, DONT_CARE), listOf(ONE, DONT_CARE), listOf(ONE, ZERO), listOf(ZERO, ONE),
        ))
        val applied = applyAnalysisExpressions(current, "Z := A & B\nW := !A")
        assertEquals(listOf("B", "A"), applied.inputs)
        assertEquals(listOf("Y", "Z", "W"), applied.outputs)
        for (row in applied.rows.indices) {
            val values = applied.assignment(row)
            assertEquals(current.rows[row][0], applied.rows[row][0])
            assertEquals(if (values.getValue("A") && values.getValue("B")) ONE else ZERO, applied.rows[row][1])
            assertEquals(if (values.getValue("A")) ZERO else ONE, applied.rows[row][2])
        }
        // Without a table the expressions make one, in the order the variables appear.
        assertEquals(listOf("A", "B"), applyAnalysisExpressions(null, "Y := A & B").inputs)
        assertFailsWith<IllegalArgumentException> { applyAnalysisExpressions(current, "Y := A\nY := B") }
    }

    @Test
    fun appliedExpressionsRefuseNamesThatAreNotInputs() {
        val adder = table("S := A ^ B ^ Cin", "Cout := A & B | Cin & (A ^ B)")
        fun refused(current: TruthTable?, text: String): String = assertFailsWith<LocalizedArgumentException> {
            applyAnalysisExpressions(current, text)
        }.localizedDescription(message(Messages.AnalysisFailed)).resolve(AppLanguage.ENGLISH)
        // Evolution's Parser (badVariableName): a typo or an output is not an input, and no input is added.
        assertEquals("“Cn” is not an input variable.", refused(adder, "S := A ^ B ^ Cn"))
        assertEquals("“S” is not an input variable.", refused(adder, "Cout := A & B | S"))
        assertEquals("“Y” is not an input variable.", refused(null, "Y := A & B\nZ := Y | C"))
        assertEquals(adder.inputs, applyAnalysisExpressions(adder, "Cout := A & B").inputs)
        // W2-06 R11-2: an output named like an input would give Generate two pins with the same label.
        assertEquals("“A” is an input variable and cannot also be an output.", refused(adder, "A := B & Cin"))
        assertEquals(listOf("S", "Cout", "T"), applyAnalysisExpressions(adder, "T := B & Cin").outputs)
    }

    /** W2-06 R11-1 and R11-4: a notation change keeps its control until forms in that notation arrive. */
    @Test
    fun aNotationChangeStaysPendingUntilFormsInThatNotationArrive() {
        val session = AnalysisSession()
        val adder = table("S := A ^ B ^ Cin", "Cout := A & B | Cin & (A ^ B)")
        session.load(adder)
        fun forms(form: NormalForm) = adder.outputs.indices.map { BooleanMinimizer.minimize(adder, it, form) }
        // Nothing shown yet: changing the notation has nothing to minimize again.
        assertFalse(session.changeForm(NormalForm.POS))
        assertFalse(session.formsPending)
        assertTrue(session.formsReady(forms(NormalForm.POS), NormalForm.POS))
        assertTrue(session.changeForm(NormalForm.SOP))
        assertTrue(session.formsPending)
        assertTrue(session.forms.isEmpty())
        // A run that ends with the old notation (it was still finishing) does not end the change.
        assertFalse(session.formsReady(forms(NormalForm.POS), NormalForm.POS))
        assertTrue(session.formsPending)
        // A second change before the forms arrive still counts as shown forms to recompute.
        assertTrue(session.changeForm(NormalForm.POS))
        assertTrue(session.formsReady(forms(NormalForm.POS), NormalForm.POS))
        assertFalse(session.formsPending)
        assertEquals(adder.outputs.size, session.forms.size)
        // A table edit ends both the forms and a pending change.
        session.changeForm(NormalForm.SOP)
        session.edit(adder.withCell(0, 0, ONE))
        assertFalse(session.formsPending)
        assertTrue(session.forms.isEmpty())
    }

    @Test
    fun theTapValueIsKeptWithTheAnalysisAndResetForAnotherDocument() {
        val session = AnalysisSession()
        session.bind(1L)
        session.tapValue = DONT_CARE
        session.load(table("Y := A & B"))
        assertEquals(DONT_CARE, session.tapValue)
        session.bind(2L)
        assertNull(session.tapValue)
    }

    @Test
    fun undoBringsBackEachTableWithItsOwnBaselineAndFile() {
        val session = AnalysisSession()
        val created = table("Y := A & B")
        val file = EvolutionTruthTableFile.read(EvolutionTruthTableFile.write(created, EvolutionTableFormat.CSV))
        session.load(file.table, file)
        // A cell turned back to its value is not an edit.
        session.edit(session.table!!.withCell(0, 0, ONE))
        assertTrue(session.dirty)
        session.edit(session.table!!.withCell(0, 0, ZERO))
        assertFalse(session.dirty)
        // A cell edit keeps the imported file; a new variable drops it, and Undo brings it back.
        assertSame(file, session.tableFile)
        session.editStructure(session.table!!.withInputAdded("C", 2))
        assertNull(session.tableFile)
        session.undo()
        assertSame(file, session.tableFile)

        // Undoing a replacement: the table that was never edited still needs no confirmation.
        session.load(table("S := A ^ B", "Cout := A & B"), circuit = "Adder")
        session.undo()
        assertFalse(session.dirty)
        assertSame(file, session.tableFile)
        assertNull(session.sourceCircuit)
        session.redo()
        assertEquals("Adder", session.sourceCircuit)
        assertFalse(session.dirty)

        // An edited table stays edited after its replacement is undone.
        session.undo()
        session.edit(session.table!!.withCell(3, 0, DONT_CARE))
        session.newTable()
        session.undo()
        assertTrue(session.dirty)
    }

    @Test
    fun undoFromAPagedTableReturnsToTheEditableOne() {
        val session = AnalysisSession()
        val read = table("S := A ^ B", "Cout := A & B")
        session.load(read, circuit = "Adder")
        val spool = CountingSpool()
        session.loadPage(ImportedPagedTruthTable(List(14) { "I$it" }, listOf("Y"), IntArray(1 shl 14), 1, spool).analysisPage(0) { false })
        assertNull(session.table)
        session.undo()
        assertSame(read, session.table)
        assertNull(session.page)
        assertEquals(1, spool.closed)
        assertEquals("Adder", session.sourceCircuit)
        assertFalse(session.dirty)
        // The page's spool is gone: there is nothing to redo.
        assertFalse(session.canRedo)
    }

    /** W3-05 (C76): a paged table keeps neither the sample nor an earlier table's expressions. */
    @Test
    fun aPagedTableClearsTheExpressionsOfTheTableItReplaced() {
        val session = AnalysisSession()
        session.load(table("S := A ^ B", "Cout := A & B"))
        session.expressionText = "S := A | B"
        session.expressionsEdited = true
        session.loadPage(ImportedPagedTruthTable(List(14) { "I$it" }, listOf("Y"), IntArray(1 shl 14), 1, CountingSpool())
            .analysisPage(0) { false })
        assertEquals("", session.expressionText)
        assertFalse(session.expressionsEdited)
        assertNull(session.expressionsHint)
        // A fresh session's paged table does not show the sample either.
        val fresh = AnalysisSession()
        fresh.loadPage(ImportedPagedTruthTable(List(13) { "I$it" }, listOf("Y"), IntArray(1 shl 13), 1, CountingSpool())
            .analysisPage(0) { false })
        assertEquals("", fresh.expressionText)
    }

    @Test
    fun anotherNotationDropsTheShownForms() {
        val session = AnalysisSession()
        val adder = table("S := A ^ B", "Cout := A & B")
        session.load(adder)
        assertFalse(session.changeForm(NormalForm.POS))
        session.forms = adder.outputs.indices.map { BooleanMinimizer.minimize(adder, it, NormalForm.POS) }
        assertTrue(session.changeForm(NormalForm.SOP))
        assertEquals(NormalForm.SOP, session.form)
        assertTrue(session.forms.isEmpty())
    }

    @Test
    fun expressionsHintSaysWhyNothingWasFilledIn() {
        // 8 inputs, above exact minimization: the canonical sum of 128 minterms exceeds the parser's limits.
        val long = TruthTable(List(8) { "I$it" }, listOf("Y"), List(256) { listOf(if (it % 2 == 0) ONE else ZERO) })
        assertNull(long.derivedExpressions(limits = AnalysisLimits(maxMinimizationInputBits = 6)))
        assertEquals("analysis_expressions_too_long", long.derivedExpressionsHint().key)
        assertEquals("analysis_expressions_too_large",
            TruthTable(List(9) { "I$it" }, listOf("Y"), List(512) { listOf(ZERO) }).derivedExpressionsHint().key)
    }

    @Test
    fun newColumnNamesAreFree() {
        assertEquals("C", nextAnalysisName(listOf("A", "B", "Y"), input = true))
        assertEquals("Z", nextAnalysisName(listOf("A", "Y"), input = false))
        assertEquals("X2", nextAnalysisName(('A'..'Z').map { it.toString() }, input = true))
        assertEquals("`a b`", analysisOutputName("a b"))
        assertEquals("Cout", analysisOutputName("Cout"))
        assertEquals(TruthCell.ZERO, TruthCell.DONT_CARE.next())
    }
}
