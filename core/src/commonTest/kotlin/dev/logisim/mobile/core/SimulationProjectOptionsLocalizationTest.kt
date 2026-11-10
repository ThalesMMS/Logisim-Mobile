package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulationProjectOptionsLocalizationTest {
    @Test
    fun invalidOptionValuesRetainTheirIdentityAndOriginalData() {
        val value = "{name} → שלום"
        val project = CircuitProject(listOf(CircuitDocument("main")), extraXml = listOf(
            "<options><a name=\"autoPropagating\" val=\"$value\"/></options>"))
        val error = assertFailsWith<LocalizedArgumentException> { SimulationProjectOptions.read(project) }
        assertEquals(Messages.SimulationOptionsErrorValue, error.text.key)
        assertEquals("Invalid autoPropagating: $value", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Valor inválido para autoPropagating: $value", error.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun constructorGuardsTranslateWithoutChangingTheirExceptionType() {
        val error = assertFailsWith<LocalizedArgumentException> { SimulationProjectOptions(propagationLimit = 0) }
        assertEquals("The propagation limit must be a positive integer.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("O limite de propagação deve ser um inteiro positivo.", error.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun nonNullValidationKeepsAParsedValueAndADiagnosticOnFailure() {
        assertEquals(8, requireNotNullLocalized("8".toIntOrNull()) { message(Messages.SimulationOptionsErrorPositiveLimit) })
        val error = assertFailsWith<LocalizedArgumentException> {
            requireNotNullLocalized("invalid".toIntOrNull()) { message(Messages.SimulationOptionsErrorPositiveLimit) }
        }
        assertEquals(Messages.SimulationOptionsErrorPositiveLimit, error.text.key)
    }
}
