package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-02: Evolution's menu grouping (C24), the File examples (C146), circuit commands (C31) and file names (C140, C141). */
class EditorMenusTest {
    private val strings = Localizer(AppLanguage.ENGLISH)
    private val hierarchy = checkNotNull(CircuitExamples.find("hierarchy")).xml

    @Test
    fun toolsSitInEvolutionsMenus() {
        assertEquals("file", editorToolMenu("visual-export"))
        assertEquals("file", editorToolMenu("portable-projects"))
        assertEquals("simulate", editorToolMenu("trace"))
        assertEquals("simulate", editorToolMenu("test-vectors"))
        assertEquals("simulate", editorToolMenu(ASSEMBLY_VIEWER_ID))
        assertEquals("fpga", editorToolMenu("fpga"))
        assertEquals("fpga", editorToolMenu("hdl-import"))
        assertEquals("fpga", editorToolMenu(jarCompanionToolId))
        assertEquals("help", editorToolMenu("help-examples"))
        assertEquals("project", editorToolMenu("combinational-analysis"))
        assertEquals("project", editorToolMenu("accessibility"))
        // Extension tools default to Project; Preferences has its own File item.
        assertEquals("project", editorToolMenu("some-extension"))
        assertNull(editorToolMenu("preferences"))
    }

    @Test
    fun circuitCommandsFollowTheCircuitInView() {
        val state = EditorState(hierarchy)
        var removed: String? = null
        var added = 0
        fun actions() = circuitCommandActions(state, strings, writable = true, onAdd = { added++ }, onRemove = { removed = it })
            .associateBy { it.id }
        val main = state.project.mainCircuit
        val other = state.project.circuits.first { it.id != main.id }
        var commands = actions()
        assertEquals(listOf("add-circuit", "move-circuit-up", "move-circuit-down", "set-main-circuit", "remove-circuit"),
            commands.keys.toList())
        assertFalse(commands.getValue("set-main-circuit").enabled, "the main circuit is already main")
        // Remove stays enabled for the main circuit: its dialog explains why it stays.
        assertTrue(commands.getValue("remove-circuit").enabled)
        commands.getValue("remove-circuit").onClick()
        assertEquals(main.id, removed)
        commands.getValue("add-circuit").onClick()
        assertEquals(1, added)
        state.selectCircuit(other.name)
        commands = actions()
        assertTrue(commands.getValue("set-main-circuit").enabled)
        commands.getValue("set-main-circuit").onClick()
        assertEquals(other.name, state.project.mainCircuitName)
        val index = state.project.circuits.indexOfFirst { it.id == other.id }
        val move = if (index > 0) "move-circuit-up" else "move-circuit-down"
        actions().getValue(move).onClick()
        assertTrue(state.project.circuits.indexOfFirst { it.id == other.id } != index)
        // A read-only project offers none of them.
        assertTrue(circuitCommandActions(state, strings, writable = false, onAdd = {}, onRemove = {}).none { it.enabled })
    }

    @Test
    fun removalExplainsTheMainAndUsedCircuits() {
        val state = EditorState(hierarchy)
        val main = state.project.mainCircuit
        val used = state.project.circuits.first { it.id != main.id }
        assertEquals(message("circuit_remove_main_error", "name" to main.name), circuitRemovalProblem(state.project, main.id))
        assertEquals(message("circuit_remove_used_error", "name" to used.name), circuitRemovalProblem(state.project, used.id))
        assertTrue(state.createCircuit("alu"))
        val alu = state.project.circuits.first { it.name == "alu" }
        assertNull(circuitRemovalProblem(state.project, alu.id))
    }

    @Test
    fun removalOfTheOnlyCircuitGivesEvolutionsLastCircuitError() {
        // ProjectCircuitActions.doRemoveCircuit checks the last circuit first (circuitRemoveLastError): with one circuit
        // there is no other to set as main.
        val state = EditorState(null)
        assertEquals(1, state.project.circuits.size)
        val only = state.project.circuits.single()
        assertEquals(message("circuit_remove_last_error"), circuitRemovalProblem(state.project, only.id))
        // With a second circuit, the main one gets its own reason again.
        assertTrue(state.createCircuit("alu"))
        assertEquals(message("circuit_remove_main_error", "name" to only.name), circuitRemovalProblem(state.project, only.id))
    }

    @Test
    fun theFileMenuListsEveryExampleUnderOneHeading() {
        val opened = mutableListOf<String>()
        val actions = exampleMenuActions(strings, enabled = true) { opened += it.id }
        assertTrue(actions.first().separator)
        assertEquals(message("menu_open_example").resolve(AppLanguage.ENGLISH), actions.first().label)
        val examples = actions.drop(1)
        assertEquals(HelpContent.examples.map { "example-${it.id}" }, examples.map { it.id })
        assertEquals(HelpContent.examples.map { it.title.resolve(AppLanguage.ENGLISH) }, examples.map { it.label })
        // The SAP-1 and AND items keep the descriptions automation and TalkBack users know.
        assertEquals(message("a11y_load_sap1").resolve(AppLanguage.ENGLISH), examples.first { it.id == "example-sap1" }.description)
        assertEquals(message("a11y_load_and").resolve(AppLanguage.ENGLISH), examples.first { it.id == "example-and" }.description)
        examples.forEach { it.onClick() }
        assertEquals(HelpContent.examples.map { it.id }, opened)
        assertTrue(exampleMenuActions(strings, enabled = false) {}.drop(1).none { it.enabled })
    }

    @Test
    fun exportDrawingKeepsItsShortcutInTheFileMenu() {
        val menus = listOf(EditorMenu("file", "File", listOf(EditorToolbarAction("project-tool-visual-export", "Export drawing") {})))
        val action = editorSearchActions(menus, EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID).single()
        assertEquals("file:project-tool-visual-export", action.command.id)
        assertEquals(effectiveEditorShortcut(EditorShortcut.FILE_EXPORT, emptyMap()).label(EditorShortcutScheme.PLATFORM, ShortcutPlatform.ANDROID),
            action.command.shortcut)
    }

    private class Documents(override var currentReference: DocumentReference? = null) : DocumentActions {
        val created = mutableListOf<String>()
        val shared = mutableListOf<String>()
        var saves = 0
        override fun retain(reference: DocumentReference?) { currentReference = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) = Unit
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) { created += suggestedName }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) { saves++ }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) { shared += suggestedName }
    }

    @Test
    fun saveAsAndShareSuggestTheProjectName() {
        val state = EditorState(null)
        val documents = Documents()
        val session = EditorDocuments(state, documents, projectName = { "Lab 3" }, defaultName = { "circuit" })
        session.saveAs()
        session.share()
        assertEquals(listOf("Lab 3.circ"), documents.created)
        assertEquals(listOf("Lab 3.circ"), documents.shared)
        // A linked file keeps its own name; no project name falls back to the main circuit, then the default.
        documents.currentReference = DocumentReference("content://a", "adder-lab3.circ", true, mode = DocumentOpenMode.EDIT_IN_PLACE)
        assertEquals("adder-lab3.circ", session.suggestedName())
        documents.currentReference = null
        assertEquals("${state.project.mainCircuitName}.circ", EditorDocuments(state, documents).suggestedName())
        assertEquals("a_b.circ", EditorDocuments(state, documents, projectName = { "a/b" }).suggestedName())
    }

    @Test
    fun saveOpensSaveAsUntilAFileIsLinked() {
        val state = EditorState(null)
        val documents = Documents()
        val session = EditorDocuments(state, documents, projectName = { "Lab 3" })
        assertFalse(session.canSave)
        session.saveOrSaveAs()
        assertEquals(listOf("Lab 3.circ"), documents.created)
        assertEquals(0, documents.saves)
        documents.currentReference = DocumentReference("content://a", "Lab 3.circ", true, mode = DocumentOpenMode.EDIT_IN_PLACE)
        assertTrue(session.canSave)
        session.saveOrSaveAs()
        assertEquals(1, documents.saves)
        assertEquals(1, documents.created.size)
    }
}
