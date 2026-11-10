package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectSearchTest {
    private fun component(id: String, label: String, kind: ComponentKind = ComponentKind.INPUT, child: String? = null) =
        CircuitComponent(id, kind, GridPoint(20, 20), label, circuitName = child)

    private fun hierarchy(): CircuitProject {
        val leaf = CircuitDocument("Somador", listOf(component("data", "Entrada ágil")), id = "leaf-id")
        val mid = CircuitDocument("Unidade", listOf(component("nested", "Cálculo", ComponentKind.SUBCIRCUIT, leaf.name)), id = "mid-id")
        val main = CircuitDocument("Principal", listOf(
            component("left", "Unidade esquerda", ComponentKind.SUBCIRCUIT, mid.name),
            component("right", "Unidade direita", ComponentKind.SUBCIRCUIT, mid.name),
        ), id = "main-id")
        return CircuitProject(listOf(main, mid, leaf), main.name)
    }

    @Test
    fun findsAccentedAndDecomposedNamesWithoutLosingOriginalLabels() {
        val index = ProjectSearchIndex.build(hierarchy())
        val matches = index.search("ENTRADA A\u0301GIL", ProjectSearchScope.PROJECT)
        assertEquals(3, matches.size)
        assertTrue(matches.all { it.title == "Entrada ágil" })
        assertEquals(listOf(emptyList(), listOf("left", "nested"), listOf("right", "nested")),
            matches.map { assertIs<ProjectSearchTarget.Project>(it.target).location.instancePath })
    }

    @Test
    fun indexesReflectTheSuppliedProjectSnapshotAfterEditsAndUndo() {
        val project = hierarchy()
        val changed = project.copy(circuits = project.circuits.map { circuit ->
            if (circuit.id == "leaf-id") circuit.copy(components = circuit.components.map { it.copy(label = "Resultado") }) else circuit
        })
        val originalIndex = ProjectSearchIndex.build(project)
        val changedIndex = ProjectSearchIndex.build(changed)
        assertTrue(originalIndex.search("resultado").isEmpty())
        assertEquals(3, changedIndex.search("resultado").size)
        assertTrue(changedIndex.search("entrada agil").isEmpty())
        assertEquals(3, originalIndex.search("entrada agil").size)
    }

    @Test
    fun separatesLibraryProjectAndAvailableCommands() {
        val index = ProjectSearchIndex.build(hierarchy(), library = listOf(
            SearchLibraryEntry(ComponentKind.ADDER, "Somador", "Aritmética", listOf("soma", "addition")),
        ), commands = listOf(
            SearchCommand("new", "Novo circuito", listOf("criar")),
            SearchCommand("undo", "Desfazer", enabled = false),
        ))
        assertIs<ProjectSearchTarget.Library>(index.search("addition", ProjectSearchScope.LIBRARY).single().target)
        assertIs<ProjectSearchTarget.Command>(index.search("criar", ProjectSearchScope.COMMANDS).single().target)
        assertFalse(index.search("desfazer", ProjectSearchScope.COMMANDS).single().enabled)
        assertTrue(index.search("addition", ProjectSearchScope.PROJECT).isEmpty())
        assertEquals(1, index.search("somador", ProjectSearchScope.LIBRARY).size)
    }

    @Test
    fun ranksActionInitialsAndSearchesTheMenuPathAndShortcut() {
        val index = ProjectSearchIndex.build(hierarchy(), commands = listOf(
            SearchCommand("file:save", "Save", enabled = false, path = "File", shortcut = "Ctrl+S"),
            SearchCommand("file:save-as", "Save As…", path = "File", shortcut = "Ctrl+Shift+S"),
            SearchCommand("view:fit-selection", "Fit Selection", path = "View"),
        ))
        val saveAs = index.search("SA", ProjectSearchScope.COMMANDS).first()
        assertEquals("Save As…", saveAs.title)
        assertEquals("File · Ctrl+Shift+S", saveAs.subtitle)
        assertEquals(saveAs, index.search("file ctrl+shift+s", ProjectSearchScope.COMMANDS).single())
        assertEquals("Fit Selection", index.search("fs", ProjectSearchScope.COMMANDS).single().title)
    }

    @Test
    fun limitsLargeLibrariesButKeepsExactMatchesAheadOfEarlierPartialMatches() {
        val project = CircuitProject(listOf(CircuitDocument("main")))
        val entries = List(20_000) { number ->
            SearchLibraryEntry(ComponentKind.INPUT, if (number == 19_999) "Porta" else "Porta $number", "Biblioteca", id = "test-$number")
        }
        val index = ProjectSearchIndex.build(project, entries)
        val result = index.search("porta", ProjectSearchScope.LIBRARY, limit = 100)
        assertEquals(100, result.size)
        assertEquals("Porta", result.first().title)
        assertEquals("Porta 0", result[1].title)
        assertEquals(100, index.search("", limit = 100).size)
        assertTrue(index.search("porta", limit = 0).isEmpty())
    }

    @Test
    fun tokenOrderIsIrrelevantAndAllTokensMustMatch() {
        val index = ProjectSearchIndex.build(hierarchy())
        assertEquals(3, index.search("agil entrada somador").size)
        val left = index.search("esquerda entrada agil").single()
        assertEquals(listOf("left", "nested"), assertIs<ProjectSearchTarget.Project>(left.target).location.instancePath)
        assertTrue(index.search("agil inexistente").isEmpty())
        assertFalse(index.search("   \t\n").isEmpty())
    }
}
