package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorLocalizationTest {
    @Test
    fun editorStatusCanChangeLanguageAfterTheActionAndKeepsUserLabels() {
        val state = EditorState(null)
        state.afterPlacement = EditorAfterPlacement.KEEP_TOOL
        state.place(ComponentKind.INPUT)
        state.tap(GridPoint(600, 600))
        assertEquals("Input inserted. Tap to insert another.", state.statusText.resolve(AppLanguage.ENGLISH))
        assertEquals("Entrada inserido. Toque para inserir outro.", state.statusText.resolve(AppLanguage.PORTUGUESE))
        val label = "Entrada_do_usuario" + "_very_long_label".repeat(30)
        state.updateSelectedLabel(label)
        state.selectComponent(state.selectedId)
        assertEquals("$label selected.", state.statusText.resolve(AppLanguage.ENGLISH))
        assertEquals("$label selecionado.", state.statusText.resolve(AppLanguage.PORTUGUESE))
        assertEquals(label, state.selected?.label)
    }

    @Test
    fun searchLocalizesDisplayTextAndKeepsTargetsAndNamesStable() {
        val component = CircuitComponent("id", ComponentKind.INPUT, GridPoint(20, 30))
        val project = CircuitProject(listOf(CircuitDocument("Principal → שלום", listOf(component))))
        val english = ProjectSearchIndex.build(project, language = AppLanguage.ENGLISH).search("", ProjectSearchScope.PROJECT)
        val portuguese = ProjectSearchIndex.build(project, language = AppLanguage.PORTUGUESE).search("", ProjectSearchScope.PROJECT)
        assertEquals(english.map { it.id to it.target }, portuguese.map { it.id to it.target })
        assertEquals("Principal → שלום", english.first().title)
        assertEquals("Circuit Principal → שלום", english.first().subtitle)
        assertEquals("Circuito Principal → שלום", portuguese.first().subtitle)
        assertEquals("Input", english.last().title)
        assertEquals("Entrada", portuguese.last().title)
        assertEquals("Circuit Principal → שלום · Input · (20, 30)", english.last().subtitle)
    }

    @Test
    fun failuresKeepStructuredMessagesUntilThePresentationBoundary() {
        val failure = DocumentOpenResult.Failure(message(Messages.ErrorFileConflict))
        val state = EditorState(null)
        state.reportError(failure.notice)
        assertTrue(state.importErrorText!!.resolve(AppLanguage.ENGLISH).startsWith("Another app"))
        assertTrue(state.importErrorText!!.resolve(AppLanguage.PORTUGUESE).startsWith("O arquivo"))
        val raw = "Provider error {name} → שלום"
        assertEquals(raw, DocumentWriteResult.Failure(raw).notice.resolve(AppLanguage.ENGLISH))
        assertFalse(state.renameCircuit("missing", "name"))
        assertEquals(Messages.ErrorCircuitMissing, state.statusText.key)
    }

    @Test
    fun selectionAndClipboardCountsUseSingularAndPluralFormsIndependently() {
        val one = LocalizedText(Messages.ProjectComponentCount, quantity = 1)
        val many = LocalizedText(Messages.ProjectWireCount, quantity = 2)
        val selected = message(Messages.StatusSelectionCounts, "components" to one, "wires" to many)
        assertEquals("1 component and 2 wires selected.", selected.resolve(AppLanguage.ENGLISH))
        assertEquals("1 componente e 2 fios selecionados.", selected.resolve(AppLanguage.PORTUGUESE))
        assertNotEquals(selected.resolve(AppLanguage.ENGLISH), selected.resolve(AppLanguage.PORTUGUESE))
    }
}
