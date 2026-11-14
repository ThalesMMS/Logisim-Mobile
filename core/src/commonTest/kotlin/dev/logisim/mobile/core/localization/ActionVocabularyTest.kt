package dev.logisim.mobile.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C161: one Portuguese word per command, and command labels are translated. Words that read the same in both
 * languages are listed explicitly, so a new action key left in English fails here instead of shipping.
 */
class ActionVocabularyTest {
    private fun pt(key: String) = message(key).resolve(AppLanguage.PORTUGUESE)
    private fun en(key: String) = message(key).resolve(AppLanguage.ENGLISH)

    /** The same in both languages by design: "OK". */
    private val sameInBothLanguages = setOf("action_ok")

    @Test
    fun actionLabelsAreTranslatedToPortuguese() {
        val keys = appMessageCatalog.keys.filter { it.startsWith("action_") } - sameInBothLanguages
        val untranslated = keys.filter { key ->
            val template = appMessageCatalog.getValue(key)
            template.english.other == template.portuguese.other
        }.sorted()
        assertEquals(emptyList(), untranslated, "Portuguese text equals the English text")
    }

    @Test
    fun deleteUsesEvolutionsWordEverywhere() {
        // Evolution's gui_pt editClearItem and compDeleteItem are "Apagar", selDeleteItem "Apagar seleção".
        assertEquals("Apagar", pt("action_delete"))
        assertEquals(pt("action_delete"), pt("component_menu_delete"))
        assertEquals(pt("component_menu_delete_selection"), pt("a11y_delete_selection"))
        assertEquals(en("action_delete"), en("component_menu_delete"))
        // C161 drift: every Portuguese Delete command says "Apagar", never "Excluir".
        for (key in listOf("analysis_delete_input", "analysis_delete_output", "workspace_delete_permanently",
            "workspace_delete_permanently_title")) {
            assertTrue(pt(key).startsWith("Apagar"), "$key: ${pt(key)}")
        }
        assertFalse("Excluir" in pt("analysis_karnaugh_reduce_delete"), pt("analysis_karnaugh_reduce_delete"))
    }

    @Test
    fun simulationResetIsTranslated() {
        assertEquals("Reiniciar", pt("action_reset"))
    }

    @Test
    fun companionIsAProductNameInBothLanguages() {
        // "Companion" names the paired host program, not a command: more than 40 Portuguese texts use the word as-is,
        // such as tcl_host "Companion: {host}", so the tool titles keep it rather than a one-off translation.
        for (key in listOf("additional_companion_title", "companion_ui_title")) {
            assertEquals("Companion", pt(key), key)
            assertEquals(en(key), pt(key), key)
        }
        assertEquals("Companion: host", message("tcl_host", "host" to "host").resolve(AppLanguage.PORTUGUESE))
    }
}
