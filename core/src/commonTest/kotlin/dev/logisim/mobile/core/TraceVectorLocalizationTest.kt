package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TraceVectorLocalizationTest {
    @Test
    fun parseErrorsKeepTheirIdentityAndParametersUntilPresentation() {
        val error = assertFailsWith<VectorFormatException> { TestVectorFormat.read("A[8]\n256\n") }
        assertEquals("vector_error_line", error.text.key)
        assertEquals("Line 2: The value 256 does not fit the 8 bits of A.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Linha 2: O valor 256 não cabe nos 8 bits de A.", error.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun bindingAndTraceValidationErrorsCanBeResolvedAgainInAnotherLanguage() {
        val circuit = CircuitDocument("main")
        val error = assertFailsWith<VectorBindingException> {
            TestVectorRunSession(CircuitProject(listOf(circuit)), "main", TestVectorFormat.read("User{count}\n0\n"))
        }
        assertEquals("Column User{count} has no matching pin or clock in main.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A coluna User{count} não corresponde a um pino ou clock em main.", error.text.resolve(AppLanguage.PORTUGUESE))
        val traceError = assertFailsWith<LocalizedArgumentException> { TraceRecorder(capacity = 0) }
        assertEquals("Trace capacity must be positive.", traceError.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A capacidade do registro deve ser positiva.", traceError.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun recordedDifferencesAndRunStatusDoNotFreezeTheCurrentLanguage() {
        val difference = VectorDifference("Output{count}", "0011", "????", oscillating = true)
        assertEquals("Output{count}: expected 0011, observed ????; the circuit did not settle", difference.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Output{count}: esperado 0011, observado ????; o circuito não estabilizou", difference.notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals("Paused", VectorRunStatus.PAUSED.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Pausado", VectorRunStatus.PAUSED.notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals("events", TraceTimeUnit.EVENTS.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("eventos", TraceTimeUnit.EVENTS.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun selectedSignalLabelsAndTraceSummariesFollowThePresentationLanguage() {
        val input = CircuitComponent("a", ComponentKind.INPUT, GridPoint(10, 20))
        val signal = TraceSignals.fromProject(CircuitProject(listOf(CircuitDocument("main", listOf(input))))).single()
        assertEquals("Input (10,20)", signal.labelText.resolve(AppLanguage.ENGLISH))
        assertEquals("Entrada (10,20)", signal.labelText.resolve(AppLanguage.PORTUGUESE))
        val summary = message("trace_summary", "retained" to 3, "capacity" to 10, "dropped" to 7, "unit" to TraceTimeUnit.EVENTS.notice)
        assertEquals("3/10 samples · 7 discarded · events", summary.resolve(AppLanguage.ENGLISH))
        assertEquals("3/10 amostras · 7 descartadas · eventos", summary.resolve(AppLanguage.PORTUGUESE))
    }
}
