package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarBinding
import dev.logisim.mobile.core.ProjectToolbarItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
