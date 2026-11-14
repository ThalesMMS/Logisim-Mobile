package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.EvolutionDefaultTemplate
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarBinding
import dev.logisim.mobile.core.ProjectToolbarItem
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorProjectToolbarTest {
    @Test
    fun importedToolbarPlacesConfiguredPinsAndEditsUseProjectHistory() {
        val state = EditorState("""
            <project source="2.7.1">
              <lib name="0" desc="#Wiring"/>
              <toolbar><tool lib="0" name="Pin"><a name="output" val="true"/><a name="width" val="4"/></tool></toolbar>
              <circuit name="main"/>
            </project>
        """.trimIndent())
        val items = ProjectToolbar.read(state.project)
        val binding = assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(items.single(), state.project, state.componentRegistry))
        state.place(binding.component)
        val point = GridPoint(80, 80)
        state.tap(point)
        val placed = state.document.components.single()
        assertEquals(point, placed.position)
        assertEquals(ComponentKind.OUTPUT, placed.kind)
        assertEquals("4", placed.attributes["width"])

        val next = ProjectToolbar.apply(state.project, listOf(ProjectToolbarItem.Separator) + items)
        state.applyProject(next)
        val reopened = EditorState(state.exportXml())
        assertTrue(ProjectToolbar.read(reopened.project).first().isSeparator)
        state.undo()
        assertEquals(listOf("Pin"), ProjectToolbar.read(state.project).map { it.name })
        assertEquals(placed, state.document.components.single())
    }

    private val strings = Localizer(AppLanguage.ENGLISH)

    private fun actions(state: EditorState) =
        editorToolbarActions(state, projectToolbarEntries(state.project, state.componentRegistry), strings, "Move")

    @Test
    fun aProjectWithoutToolbarOffersTheBaseToolsWithoutWritingThem() {
        val state = EditorState(EditingNot)
        val original = CircCodec.writeProject(state.project, state.componentRegistry)
        val tools = actions(state)
        assertEquals(listOf("mobile-tool-poke", "mobile-tool-edit", "mobile-tool-select", "move", "mobile-tool-wire",
            "mobile-tool-text"), tools.map { it.id })
        assertEquals(listOf("Poke Tool", "Edit Tool", "Select", "Move", "Wire", "Text tool"), tools.map { it.label })
        assertEquals(true, tools.first().selected)

        for ((id, mode) in listOf("mobile-tool-edit" to EditorMode.EDIT, "mobile-tool-wire" to EditorMode.WIRE,
                "mobile-tool-select" to EditorMode.SELECT, "mobile-tool-text" to EditorMode.TEXT, "mobile-tool-poke" to EditorMode.INTERACT)) {
            actions(state).single { it.id == id }.onClick()
            assertEquals(mode, state.mode, id)
        }
        // Drawing with the injected Wire tool edits the circuit; the injected tools never reach <toolbar>.
        actions(state).single { it.id == "mobile-tool-wire" }.onClick()
        state.drawEditToolWire(GridPoint(300, 100), GridPoint(300, 150))
        assertEquals(3, state.document.wires.size)
        state.undo()
        assertEquals(original, state.exportXml())
        assertFalse(state.exportXml().contains("<toolbar"))
        assertTrue(ProjectToolbar.read(state.project).isEmpty())
    }

    @Test
    fun theDefaultToolbarKeepsItsToolsAndShortcutNumbers() {
        val state = EditorState(null)
        state.newDocument(EvolutionDefaultTemplate.apply(state.project, state.componentRegistry))
        val entries = projectToolbarEntries(state.project, state.componentRegistry)
        val tools = actions(state)
        assertTrue(tools.none { it.id.startsWith(MobileBaseTool.ID_PREFIX) })
        val targets = ToolbarShortcutTargets().apply { actions = tools }
        // Ctrl+N selects the Nth tool of the project's toolbar, as Evolution's KeyboardToolSelection.
        entries.map { it.second }.filter { it != null }.forEachIndexed { index, binding ->
            assertTrue(targets.select(index), "tool $index")
            when (binding) {
                is ProjectToolbarBinding.Mode -> assertEquals(binding.editorMode(), state.mode, "tool $index")
                is ProjectToolbarBinding.Text -> assertEquals(EditorMode.TEXT, state.mode, "tool $index")
                is ProjectToolbarBinding.Place -> assertEquals(binding.component.kind, state.placingKind, "tool $index")
                null -> Unit
            }
            targets.actions = actions(state)
        }
    }

    @Test
    fun numberedToolsSkipTheBaseToolsAndCtrlOneIsANoOpOnAnEmptyToolbar() {
        val empty = EditorState(EditingNot)
        val targets = ToolbarShortcutTargets().apply { actions = actions(empty) }
        assertFalse(targets.select(0))
        assertEquals(EditorMode.INTERACT, empty.mode)

        // A toolbar with components but no tool mode gets the base tools first; Ctrl+1 is still its first tool.
        val partial = EditorState("""
            <project source="3.8.0">
              <lib name="0" desc="#Wiring"/>
              <toolbar><tool lib="0" name="Pin"/></toolbar>
              <circuit name="main"/>
            </project>
        """.trimIndent())
        val tools = actions(partial)
        assertEquals(listOf("mobile-tool-poke", "mobile-tool-edit", "mobile-tool-select", "move", "mobile-tool-wire",
            "mobile-tool-text", MobileBaseTool.SEPARATOR_ID, "project-tool-0"), tools.map { it.id })
        assertTrue(ToolbarShortcutTargets().apply { actions = tools }.select(0))
        assertEquals(EditorMode.PLACE, partial.mode)
        assertEquals(ComponentKind.INPUT, partial.placingKind)
    }

    @Test
    fun aHiddenToolbarFoldsOnlyTheProjectsOwnToolsIntoTheEditMenu() {
        val state = EditorState(null)
        state.newDocument(EvolutionDefaultTemplate.apply(state.project, state.componentRegistry))
        val text = assertNotNull(projectTextTool(projectToolbarEntries(state.project, state.componentRegistry)))
        assertTrue(actions(state).any { it.id == text.actionId && it.icon == EditorIcon.TEXT })
        val folded = hiddenToolbarMenuActions(actions(state), text.actionId)
        // Poke, Edit, Wiring, Move and the Text Tool are in the Edit menu's tool group already; the components are not.
        assertTrue(folded.none { it.icon == EditorIcon.POKE || it.icon == EditorIcon.SELECT || it.icon == EditorIcon.WIRE || it.id == "move" })
        assertTrue(folded.none { it.icon == EditorIcon.TEXT }, "one Text tool, the tool group's")
        assertTrue(folded.isNotEmpty())
        assertFalse(folded.first().separator)
        assertFalse(folded.last().separator)
        assertTrue(folded.zipWithNext().none { (a, b) -> a.separator && b.separator })
        assertTrue(hiddenToolbarMenuActions(actions(EditorState(EditingNot))).isEmpty())
    }

    @Test
    fun aToolbarWithItsOwnTextToolButNoModesGetsNoSecondTextTool() {
        val state = EditorState("""
            <project source="3.8.0">
              <lib name="0" desc="#Wiring"/>
              <lib name="1" desc="#Base"/>
              <toolbar><tool lib="1" name="Text Tool"/><tool lib="0" name="Pin"/></toolbar>
              <circuit name="main"/>
            </project>
        """.trimIndent())
        val entries = projectToolbarEntries(state.project, state.componentRegistry)
        val text = assertNotNull(projectTextTool(entries))
        assertEquals("project-tool-0", text.actionId)
        assertEquals(MobileBaseTool.entries - MobileBaseTool.TEXT, mobileBaseTools(entries))
        val tools = actions(state)
        assertEquals(listOf("mobile-tool-poke", "mobile-tool-edit", "mobile-tool-select", "move", "mobile-tool-wire",
            MobileBaseTool.SEPARATOR_ID, "project-tool-0", "project-tool-1"), tools.map { it.id })
        assertEquals(1, tools.count { it.icon == EditorIcon.TEXT })
        tools.single { it.id == text.actionId }.onClick()
        assertEquals(EditorMode.TEXT, state.mode)
        assertEquals(text.prototype, state.placingTemplate)
    }

    private companion object {
        /** fixtures/user-scenarios/editing-not.circ: a project without <toolbar>. */
        val EditingNot = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin">
      <a name="facing" val="east"/><a name="type" val="input"/><a name="width" val="1"/>
      <a name="appearance" val="NewPins"/><a name="label" val="E2E_IN"/>
      <a name="behavior" val="simple"/><a name="initial" val="0x0"/>
      <a name="labelfont" val="SansSerif bold 16"/><a name="labelloc" val="west"/><a name="radix" val="2"/>
    </comp>
    <comp lib="1" loc="(200,100)" name="NOT Gate">
      <a name="facing" val="east"/><a name="width" val="1"/><a name="size" val="30"/>
      <a name="out" val="01"/><a name="label" val="E2E_NOT"/>
    </comp>
    <comp lib="0" loc="(300,100)" name="Pin">
      <a name="facing" val="west"/><a name="type" val="output"/><a name="width" val="1"/>
      <a name="appearance" val="NewPins"/><a name="label" val="E2E_OUT"/>
      <a name="behavior" val="simple"/><a name="initial" val="0x0"/>
      <a name="labelfont" val="SansSerif bold 16"/><a name="labelloc" val="west"/><a name="radix" val="2"/>
    </comp>
    <wire from="(100,100)" to="(170,100)"/>
    <wire from="(200,100)" to="(300,100)"/>
  </circuit>
</project>
"""
    }
}
