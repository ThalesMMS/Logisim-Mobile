package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtraIoLocalizationTest {
    @Test
    fun selectedComponentValuesCanBePresentedInEitherLanguage() {
        val value = message("extra_io_slider_value", "value" to "00xE")
        assertEquals("Value: 00xE", value.resolve(AppLanguage.ENGLISH))
        assertEquals("Valor: 00xE", value.resolve(AppLanguage.PORTUGUESE))
        val contact = message("extra_io_switch_contact", "contact" to 2)
        assertEquals("Select contact 2", contact.resolve(AppLanguage.ENGLISH))
        assertEquals("Selecionar contato 2", contact.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun halfCycleSamplesChooseTheSingularAndPluralAtPresentation() {
        val singular = LocalizedText("extra_io_half_cycle_samples", quantity = 1)
        assertEquals("1 half-cycle sample", singular.resolve(AppLanguage.ENGLISH))
        assertEquals("1 amostra de meio-ciclo", singular.resolve(AppLanguage.PORTUGUESE))
        val plural = singular.copy(quantity = 2)
        assertEquals("2 half-cycle samples", plural.resolve(AppLanguage.ENGLISH))
        assertEquals("2 amostras de meio-ciclo", plural.resolve(AppLanguage.PORTUGUESE))
        assertEquals("0 half-cycle samples", singular.copy(quantity = 0).resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun malformedImportedPlaContentsKeepTypedDiagnostics() {
        val cases = listOf(
            Triple("0**2", "extra_io_pla_cell_invalid", "Invalid PLA ROM cell."),
            Triple("0*abc", "extra_io_pla_repeat_invalid", "Invalid PLA ROM repetition."),
            Triple("0 0*2147483647", "extra_io_pla_contents_exceeded", "The contents exceed the PLA ROM matrix."),
            Triple("3", "extra_io_pla_value_invalid", "Invalid value in the PLA ROM matrix."),
        )
        for ((contents, key, english) in cases) {
            val failure = assertFailsWith<LocalizedArgumentException> { PlaRomTable.decode(2, 2, 1, contents) }
            assertEquals(key, failure.text.key)
            assertEquals(english, failure.text.resolve(AppLanguage.ENGLISH))
        }
        val failure = assertFailsWith<LocalizedArgumentException> { PlaRomTable.decode(2, 2, 1, "3") }
        assertEquals("Valor inválido na matriz da PLA ROM.", failure.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun invalidComponentParametersDoNotLoseTheMessageIdentity() {
        val failure = assertFailsWith<LocalizedArgumentException> { ExtraSliderState(256) }
        assertEquals("extra_io_slider_position_invalid", failure.text.key)
        assertEquals("The slider position must be between 0 and 255.", failure.text.resolve(AppLanguage.ENGLISH))
        assertEquals("A posição do controle deslizante deve estar entre 0 e 255.", failure.text.resolve(AppLanguage.PORTUGUESE))
    }
}
