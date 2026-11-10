package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AnalysisLocalizationTest {
    @Test
    fun parserFailureRetainsItsIdentityAndPositionInBothLanguages() {
        val failure = assertFailsWith<BooleanExpressionException> { BooleanExpressionParser.parse("A & )") }
        assertEquals(4, failure.position)
        assertEquals(Messages.AnalysisParsePosition, failure.text.key)
        assertEquals("Expected a name, constant, or parenthesis. Position 5.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Esperado nome, constante ou parêntese. Posição 5.", failure.text.resolve(AppLanguage.PORTUGUESE))
        assertEquals(failure.text, failure.localizedDescription(message(Messages.AnalysisFailed)))
    }

    @Test
    fun joinedNestedNoticesResolveAtPresentationAndPreserveLiteralNames() {
        val userName = "name {reason} → שלום · " + "long name ".repeat(30)
        val inputFailure = assertFailsWith<LocalizedArgumentException> {
            BooleanExpression.Variable(userName).evaluate(emptyMap())
        }
        val parts = mutableListOf(inputFailure.text, message(Messages.AnalysisCancelled))
        val joined = LocalizedText.join(parts, " | ")
        parts.clear()
        val error = message(Messages.AnalysisCircuitFailed, "reason" to joined)
        assertEquals("The circuit could not be analyzed: Input not defined: $userName. | Analysis cancelled.",
            error.resolve(AppLanguage.ENGLISH))
        assertEquals("O circuito não pôde ser analisado: Entrada não definida: $userName. | Análise cancelada.",
            error.resolve(AppLanguage.PORTUGUESE))
        assertEquals("", LocalizedText.join(emptyList()).resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun tableValidationAndLimitsExposeTranslatableParameters() {
        val tableFailure = assertFailsWith<LocalizedArgumentException> {
            TruthTable(emptyList(), listOf("Y"), emptyList())
        }
        assertEquals("The table needs 1 row.", tableFailure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A tabela precisa de 1 linha.", tableFailure.text.resolve(AppLanguage.PORTUGUESE))
        val outputFailure = assertFailsWith<LocalizedArgumentException> {
            TruthTable(emptyList(), listOf("Y"), listOf(emptyList()))
        }
        assertEquals("Each row needs 1 output.", outputFailure.text.resolve(AppLanguage.ENGLISH))
        val limit = assertFailsWith<AnalysisLimitException> {
            TruthTable.fromExpressions(mapOf("Y" to BooleanExpression.Variable("A")),
                limits = AnalysisLimits(maxInputBits = 0))
        }
        assertEquals(Messages.AnalysisInputBitsLimit, limit.text.key)
        assertEquals("Input bit limit: 0. Row limit: 1.", limit.text.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun generatedAndCancelledFailuresKeepTheirPublicExceptionTypes() {
        val cancelled = assertFailsWith<AnalysisCancelledException> {
            TruthTable.fromExpressions(mapOf("Y" to BooleanExpression.Constant(true)), cancelled = { true })
        }
        assertEquals("Analysis cancelled.", cancelled.text.resolve(AppLanguage.ENGLISH))
        val limit = assertFailsWith<AnalysisLimitException> {
            AnalysisCircuitGenerator.generateExpressions(listOf("A"), mapOf("Y" to BooleanExpression.Variable("A")),
                limits = AnalysisLimits(maxGeneratedComponents = 1))
        }
        assertIs<LocalizedArgumentException>(limit)
        assertEquals("The generated circuit exceeds 1 component.", limit.text.resolve(AppLanguage.ENGLISH))
        assertEquals("O circuito gerado excede 1 componente.", limit.text.resolve(AppLanguage.PORTUGUESE))
    }
}
