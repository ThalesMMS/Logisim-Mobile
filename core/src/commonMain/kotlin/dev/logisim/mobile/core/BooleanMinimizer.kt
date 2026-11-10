package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

enum class NormalForm { SOP, POS }

data class BooleanImplicant(val bits: Int, val dontCareMask: Int, val inputCount: Int) {
    fun covers(row: Int): Boolean = (row xor bits) and dontCareMask.inv() and ((1 shl inputCount) - 1) == 0
    val literals: Int get() = inputCount - dontCareMask.countOneBits()
    fun expression(inputs: List<String>, form: NormalForm): BooleanExpression {
        require(inputs.size == inputCount)
        val terms = inputs.mapIndexedNotNull { index, name ->
            val bit = 1 shl (inputs.lastIndex - index)
            if (dontCareMask and bit != 0) null else {
                val positive = (bits and bit != 0) == (form == NormalForm.SOP)
                val variable = BooleanExpression.Variable(name)
                if (positive) variable else BooleanExpression.Not(variable)
            }
        }
        return combine(terms, if (form == NormalForm.SOP) BooleanOperator.AND else BooleanOperator.OR,
            identity = form == NormalForm.SOP)
    }
}

data class MinimizedBooleanFunction(
    val form: NormalForm,
    val implicants: List<BooleanImplicant>,
    val expression: BooleanExpression,
) {
    val literalCount: Int get() = implicants.sumOf { it.literals }
}

/** Exact prime-implicant cover: fewest terms first, then fewest literals. */
object BooleanMinimizer {
    fun minimize(
        table: TruthTable,
        output: Int = 0,
        form: NormalForm = NormalForm.SOP,
        limits: AnalysisLimits = AnalysisLimits(),
        cancelled: () -> Boolean = { false },
    ): MinimizedBooleanFunction {
        require(output in table.outputs.indices)
        if (table.inputs.size > limits.maxMinimizationInputBits) throw AnalysisLimitException(
            LocalizedText(Messages.AnalysisMinimizationLimit, quantity = limits.maxMinimizationInputBits.toLong()))
        val budget = AnalysisBudget(limits.maxSearchSteps, cancelled)
        budget.step()
        val wanted = if (form == NormalForm.SOP) TruthCell.ONE else TruthCell.ZERO
        val targets = table.rows.indices.filter { table.rows[it][output] == wanted }.toSet()
        if (targets.isEmpty()) return result(emptyList(), table.inputs, form)
        var current = table.rows.indices.filter {
            table.rows[it][output] == wanted || table.rows[it][output] == TruthCell.DONT_CARE
        }.map { BooleanImplicant(it, 0, table.inputs.size) }.toSet()
        val primes = mutableSetOf<BooleanImplicant>()
        while (current.isNotEmpty()) {
            val used = mutableSetOf<BooleanImplicant>()
            val next = mutableSetOf<BooleanImplicant>()
            val terms = current.toList()
            for (i in terms.indices) for (j in i + 1 until terms.size) {
                budget.step()
                val a = terms[i]
                val b = terms[j]
                if (a.dontCareMask != b.dontCareMask) continue
                val difference = a.bits xor b.bits
                if (difference.countOneBits() == 1 && difference and a.dontCareMask == 0) {
                    used += a
                    used += b
                    next += BooleanImplicant(a.bits and difference.inv(), a.dontCareMask or difference, a.inputCount)
                }
            }
            primes += current - used
            current = next
        }
        val candidates = primes.filter { cube -> targets.any(cube::covers) }
            .sortedWith(compareBy<BooleanImplicant> { it.literals }.thenBy { it.dontCareMask }.thenBy { it.bits })
        val byTarget = targets.associateWith { row -> candidates.indices.filter { candidates[it].covers(row) } }
        val essential = byTarget.values.filter { it.size == 1 }.flatten().toSet()
        val initiallyCovered = targets.filter { row -> essential.any { candidates[it].covers(row) } }.toSet()
        var best: Set<Int>? = null
        var bestLiterals = Int.MAX_VALUE
        fun search(selected: Set<Int>, covered: Set<Int>) {
            budget.step()
            val literalCount = selected.sumOf { candidates[it].literals }
            val oldBest = best
            if (oldBest != null && (selected.size > oldBest.size ||
                    selected.size == oldBest.size && literalCount >= bestLiterals && covered.size < targets.size)) return
            if (covered.size == targets.size) {
                if (oldBest == null || selected.size < oldBest.size || selected.size == oldBest.size && literalCount < bestLiterals) {
                    best = selected
                    bestLiterals = literalCount
                }
                return
            }
            val row = (targets - covered).minWith(compareBy<Int> { byTarget.getValue(it).count { index -> index !in selected } }.thenBy { it })
            val choices = byTarget.getValue(row).filter { it !in selected }.sortedWith(
                compareByDescending<Int> { index -> (targets - covered).count(candidates[index]::covers) }
                    .thenBy { candidates[it].literals }.thenBy { it })
            for (index in choices) search(selected + index, covered + targets.filter(candidates[index]::covers))
        }
        search(essential, initiallyCovered)
        return result(requireNotNull(best).sorted().map { candidates[it] }, table.inputs, form)
    }

    private fun result(terms: List<BooleanImplicant>, inputs: List<String>, form: NormalForm): MinimizedBooleanFunction =
        MinimizedBooleanFunction(form, terms, combine(terms.map { it.expression(inputs, form) },
            if (form == NormalForm.SOP) BooleanOperator.OR else BooleanOperator.AND, identity = form == NormalForm.POS))
}

internal fun combine(
    expressions: List<BooleanExpression>,
    operator: BooleanOperator,
    identity: Boolean,
): BooleanExpression = expressions.reduceOrNull { a, b -> BooleanExpression.Binary(operator, a, b) }
    ?: BooleanExpression.Constant(identity)
