package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorBuiltinLibrariesTest {
    private val ttl = "#TTL"
    private val ttlName = message("TTL")

    private fun fixture(ttlDeclaration: String = "", extra: String = "") = """
        <project source="5.1.0-dev" version="1.0">
          <lib name="0" desc="#Wiring"/>
          $ttlDeclaration
          $extra
          <main name="main"/>
          <circuit name="main"/>
        </project>
    """.trimIndent()

    private fun EditorState.paletteKinds(): List<ComponentKind> =
        registryComponentGroups(componentRegistry, project).flatMap { it.second }

    @Test
    fun projectPaletteUsesLibraryDeclarationsAcrossEditsUndoAndReopen() {
        val state = EditorState(fixture("<lib name=\"1\" desc=\"#TTL\"/>"))
        val chip = state.componentRegistry.resolve(ttl, "7400", emptyMap())!!.kind
        assertTrue(chip in state.paletteKinds())
        state.place(chip)
        assertTrue(state.setBuiltinLibrary(ttl, false, ttlName))
        assertEquals(EditorMode.INTERACT, state.mode)
        assertFalse(chip in state.paletteKinds())
        assertFalse(state.exportXml().contains("#TTL"))
        val reopened = EditorState(state.exportXml())
        assertFalse(chip in reopened.paletteKinds())

        state.undo()
        assertTrue(chip in state.paletteKinds())
        state.redo()
        assertFalse(chip in state.paletteKinds())

        assertTrue(reopened.setBuiltinLibrary(ttl, true, ttlName))
        assertTrue(chip in reopened.paletteKinds())
        assertTrue(chip in EditorState(reopened.exportXml()).paletteKinds())
    }

    @Test
    fun cannotUnloadLibraryUsedInAnotherCircuitOrProjectTool() {
        for (use in listOf(
            """<circuit name="other"><comp name="7400" lib="1" loc="(50,50)"/></circuit>""",
            """<toolbar><tool lib="1" name="7400"/></toolbar>""",
            """<mappings><tool lib="1" name="7400" map="Button2"/></mappings>""",
        )) {
            val state = EditorState(fixture("<lib name=\"1\" desc=\"#TTL\"/>", use))
            assertTrue(state.documentWritable)
            val before = state.exportXml()
            assertFalse(state.setBuiltinLibrary(ttl, false, ttlName))
            assertEquals("active_library_in_use", state.statusText.key)
            assertEquals(before, state.exportXml())
        }
    }

    @Test
    fun freshProjectStartsWithDefaultBuiltinsAndBlocksUnloadAfterPlacement() {
        val state = EditorState(null)
        state.newDocument()
        val chip = state.componentRegistry.resolve(ttl, "7400", emptyMap())!!.kind
        assertTrue(chip in state.paletteKinds())
        assertTrue(state.insertComponent(chip, GridPoint(100, 100)))
        assertFalse(state.setBuiltinLibrary(ttl, false, ttlName))
    }
}
