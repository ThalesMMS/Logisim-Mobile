package dev.logisim.mobile.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizedTextTest {
    @Test
    fun deviceTagsSelectReferenceLanguagesAndUnknownLanguagesUseEnglish() {
        assertEquals(setOf("de", "el", "en", "es", "fr", "pt", "ru", "it", "nl", "ja", "pl", "tr", "zh"),
            AppLanguage.entries.map { it.tag }.toSet())
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromTag("pt-BR"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromTag("PT_pt"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("ar-EG"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals("Undo", LocalizedText(Messages.ActionUndo).resolve(AppLanguage.fromTag("ar-EG")))
        assertEquals("Annuler", LocalizedText(Messages.ActionUndo).resolve(AppLanguage.fromTag("fr-FR")))
        assertEquals("Desfazer", LocalizedText(Messages.ActionUndo).resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun pluralMessagesDistinguishOneFromZeroAndSeveral() {
        fun project(count: Long, language: AppLanguage) =
            LocalizedText(Messages.ProjectCircuitCount, quantity = count).resolve(language)
        assertEquals("Projeto · 0 circuitos", project(0, AppLanguage.PORTUGUESE))
        assertEquals("Projeto · 1 circuito", project(1, AppLanguage.PORTUGUESE))
        assertEquals("Projeto · 2 circuitos", project(2, AppLanguage.PORTUGUESE))
        assertEquals("Project · 1 circuit", project(1, AppLanguage.ENGLISH))
        assertEquals("Project · 12 circuits", project(12, AppLanguage.ENGLISH))
    }

    @Test
    fun publishedLanguagesResolveLabelsDiagnosticsAndAccessibleNames() {
        assertEquals("Öffnen", message(Messages.ActionOpen).resolve(AppLanguage.GERMAN))
        assertEquals("開く", message(Messages.ActionOpen).resolve(AppLanguage.JAPANESE))
        assertEquals("Enregistrer", message(Messages.ActionSave).resolve(AppLanguage.FRENCH))
        assertEquals("Duplicar selección", message(Messages.A11yDuplicateSelection).resolve(AppLanguage.SPANISH))
        assertEquals("CPU {source} entfernen",
            message(Messages.A11yExplorerRemove, "name" to "CPU {source}").resolve(AppLanguage.GERMAN))
        assertEquals("元に戻す", message(Messages.ActionUndo).resolve(AppLanguage.JAPANESE))
        val diagnostic = message(Messages.ErrorMainCircuitMissing, "name" to "alu {test}")
        assertEquals("Die Hauptschaltung existiert nicht: alu {test}.", diagnostic.resolve(AppLanguage.GERMAN))
        val component = message(Messages.A11ySelectComponent, "component" to "A {circuit}", "circuit" to "CPU")
        assertEquals("CPU の部品 A {circuit} を選択", component.resolve(AppLanguage.JAPANESE))
    }

    @Test
    fun japaneseAndRussianUseTheirOwnIntegerPluralForms() {
        fun project(count: Long, language: AppLanguage) =
            LocalizedText(Messages.ProjectCircuitCount, quantity = count).resolve(language)
        assertEquals("プロジェクト · 回路 1 個", project(1, AppLanguage.JAPANESE))
        assertEquals("プロジェクト · 回路 3 個", project(3, AppLanguage.JAPANESE))
        assertEquals("Проект · 21 схема", project(21, AppLanguage.RUSSIAN))
        assertEquals("Проект · 22 схемы", project(22, AppLanguage.RUSSIAN))
        assertEquals("Проект · 12 схем", project(12, AppLanguage.RUSSIAN))
        assertEquals("Проект · 0 схем", project(0, AppLanguage.RUSSIAN))
    }

    @Test
    fun substitutionTreatsCircuitNamesAsData() {
        val text = message(Messages.A11ySelectComponent,
            "component" to "A {circuit} → B", "circuit" to "CPU & RAM <8 bits>")
        assertEquals("Select component A {circuit} → B in CPU & RAM <8 bits>", text.resolve())
        assertEquals("Selecionar componente A {circuit} → B em CPU & RAM <8 bits>",
            text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun languageChangesDoNotChangeTheIdentityOrParametersOfADiagnostic() {
        val diagnostic = message(Messages.ErrorMainCircuitMissing, "name" to "Principal")
        assertEquals("error_main_circuit_missing", diagnostic.key)
        assertEquals("Main circuit does not exist: Principal.", diagnostic.resolve())
        assertEquals("Circuito principal inexistente: Principal.", diagnostic.resolve(AppLanguage.PORTUGUESE))
        assertEquals(mapOf("name" to "Principal"), diagnostic.arguments)
    }

    @Test
    fun longHelpAndDirectionalSymbolsAreNotTruncatedOrReversed() {
        val longName = "Somador → registrador ← entrada ".repeat(30)
        val result = message(Messages.A11yOpenCircuit, "name" to longName).resolve(AppLanguage.PORTUGUESE)
        assertEquals("Abrir circuito $longName", result)
        assertTrue(result.length > 800)
        assertTrue(LocalizedText(Messages.EditorHelpViewport).resolve().contains("+ and −"))
        assertTrue(LocalizedText(Messages.EditorHelpViewport).resolve(AppLanguage.PORTUGUESE).contains("+ e −"))
    }

    @Test
    fun everyBundledMessageHasCompleteFormattingInPublishedLanguages() {
        val tokens = Regex("\\{([a-z][a-zA-Z0-9_]*)\\}")
        assertTrue(appMessageCatalog.size >= 200)
        appMessageCatalog.forEach { (key, template) ->
            val names = tokens.findAll(template.english.other).map { it.groupValues[1] }.toSet()
            val arguments = names.associateWith { "‹$it›" }
            for (language in AppLanguage.entries) {
                if (language != AppLanguage.ENGLISH && language != AppLanguage.PORTUGUESE) {
                    assertTrue(additionalTranslation(language.tag, key) != null, "$key: missing ${language.tag}")
                }
                for (quantity in listOf(0L, 1L, 12L)) {
                    val result = LocalizedText(key, arguments, quantity).resolve(language)
                    assertTrue(result.isNotBlank(), key)
                    assertFalse(tokens.containsMatchIn(result), "$key: $result")
                }
            }
        }
    }

    @Test
    fun anUnknownExtensionKeyRemainsIdentifiable() {
        assertEquals("plugin_missing_label", LocalizedText("plugin_missing_label").resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun nestedComponentNamesFollowLanguageChangesWhileUserLabelsStayLiteral() {
        val status = message(Messages.StatusPlace, "name" to LocalizedText(Messages.ComponentInput))
        assertEquals("Tap the grid to insert Input.", status.resolve(AppLanguage.ENGLISH))
        assertEquals("Toque na grade para inserir Entrada.", status.resolve(AppLanguage.PORTUGUESE))
        val custom = message(Messages.StatusPlace, "name" to LocalizedText.literal("A → B"))
        assertEquals("Toque na grade para inserir A → B.", custom.resolve(AppLanguage.PORTUGUESE))
    }
}
