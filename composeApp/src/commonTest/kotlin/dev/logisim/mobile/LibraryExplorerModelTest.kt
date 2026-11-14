package dev.logisim.mobile

import androidx.compose.runtime.saveable.SaverScope
import dev.logisim.mobile.core.ComponentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Library tab's collapsible groups and recently used components (C18). */
class LibraryExplorerModelTest {
    private val gates = "#Gates" to listOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.NOT)
    private val wiring = "#Wiring" to listOf(ComponentKind.INPUT, ComponentKind.OUTPUT)
    private val memory = "#Memory" to listOf(ComponentKind.D_FLIP_FLOP, ComponentKind.REGISTER)

    @Test
    fun wiringAndGatesStartExpandedAndGroupsToggle() {
        val expanded = LibraryGroups.DefaultExpanded
        assertTrue("#Wiring" in expanded && "#Gates" in expanded && LibraryGroups.RECENT in expanded)
        assertFalse(LibraryGroups.TTL in expanded)
        val folded = LibraryGroups.toggle(expanded, "#Gates")
        assertFalse("#Gates" in folded)
        assertEquals(expanded, LibraryGroups.toggle(folded, "#Gates"))
    }

    @Test
    fun recentKeepsTheLastSixDistinctKindsNewestFirst() {
        val kinds = listOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.NOT, ComponentKind.XOR, ComponentKind.NAND,
            ComponentKind.NOR, ComponentKind.XNOR)
        val recent = kinds.fold(emptyList<ComponentKind>(), LibraryRecents::record)
        assertEquals(kinds.reversed().take(LibraryRecents.LIMIT), recent)
        assertEquals(listOf(ComponentKind.OR) + recent.filter { it != ComponentKind.OR }.take(5), LibraryRecents.record(recent, ComponentKind.OR))
    }

    @Test
    fun recentDropsComponentsOfAnUnloadedLibrary() {
        val recent = listOf(ComponentKind.D_FLIP_FLOP, ComponentKind.AND)
        assertEquals(recent, LibraryRecents.visible(recent, listOf(gates, memory)))
        assertEquals(listOf(ComponentKind.AND), LibraryRecents.visible(recent, listOf(gates)))
    }

    @Test
    fun recentFollowsTheProjectsLibraryDeclarations() {
        val state = EditorState("""<project source="5.1.0-dev" version="1.0">
            <lib name="0" desc="#Wiring"/><lib name="1" desc="#TTL"/><main name="main"/><circuit name="main"/></project>""")
        val chip = state.componentRegistry.resolve("#TTL", "7400", emptyMap())!!.kind
        val recent = listOf(chip, ComponentKind.INPUT)
        assertEquals(recent, LibraryRecents.visible(recent, registryComponentGroups(state.componentRegistry, state.project)))
        assertTrue(state.setBuiltinLibrary("#TTL", false, dev.logisim.mobile.core.localization.LocalizedText.literal("TTL")))
        assertEquals(listOf(ComponentKind.INPUT), LibraryRecents.visible(recent, registryComponentGroups(state.componentRegistry, state.project)))
        // Recent components are session state: the file is unchanged by them.
        assertFalse(state.exportXml().contains("recent", ignoreCase = true))
    }

    @Test
    fun entriesStartWithManageAndShowRowsOnlyForExpandedGroups() {
        val entries = libraryListEntries(listOf(wiring, gates, memory), recent = emptyList(), LibraryGroups.DefaultExpanded)
        assertEquals(LibraryListEntry.Manage, entries.first())
        assertEquals(listOf("#Wiring", "#Gates", "#Memory"), entries.filterIsInstance<LibraryListEntry.Group>().map { it.group })
        assertEquals(wiring.second + gates.second, entries.filterIsInstance<LibraryListEntry.Component>().map { it.kind })
        val memoryHeader = entries.filterIsInstance<LibraryListEntry.Group>().single { it.group == "#Memory" }
        assertFalse(memoryHeader.expanded)
        assertEquals(2, memoryHeader.size)
        assertEquals(entries.size, entries.map { it.key }.toSet().size, "Keys are unique")
        assertEquals("group:#Gates", entries.filterIsInstance<LibraryListEntry.Group>().single { it.group == "#Gates" }.key)
    }

    @Test
    fun recentlyUsedHeadsTheGroupsAndKeepsItsOwnKeys() {
        val entries = libraryListEntries(listOf(wiring, gates), recent = listOf(ComponentKind.AND, ComponentKind.D_FLIP_FLOP),
            LibraryGroups.DefaultExpanded)
        assertEquals(LibraryListEntry.Group(LibraryGroups.RECENT, 1, true), entries[1])
        assertEquals(LibraryListEntry.Component(LibraryGroups.RECENT, ComponentKind.AND), entries[2])
        assertEquals(2, entries.count { it is LibraryListEntry.Component && it.kind == ComponentKind.AND })
        assertEquals(entries.size, entries.map { it.key }.toSet().size, "A recent row and its library row have distinct keys")
        val folded = libraryListEntries(listOf(wiring, gates), listOf(ComponentKind.AND), LibraryGroups.toggle(LibraryGroups.DefaultExpanded, LibraryGroups.RECENT))
        assertEquals(1, folded.count { it is LibraryListEntry.Component && it.kind == ComponentKind.AND })
    }

    @Test
    fun explorerStateSurvivesSavingAndRestoring() {
        val state = LibraryExplorerState()
        state.section = ExplorerSection.PROJECT
        state.toggle("#TTL")
        state.used(ComponentKind.AND)
        state.used(ComponentKind("TTL_7400"))
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(LibraryExplorerState.Saver) { scope.save(state) }!!
        val restored = LibraryExplorerState.Saver.restore(saved)!!
        assertEquals(ExplorerSection.PROJECT, restored.section)
        assertEquals(state.expanded, restored.expanded)
        assertEquals(listOf(ComponentKind("TTL_7400"), ComponentKind.AND), restored.recent)
        val fresh = LibraryExplorerState.Saver.restore(with(LibraryExplorerState.Saver) { scope.save(LibraryExplorerState()) }!!)!!
        assertEquals(null, fresh.section)
        assertEquals(LibraryGroups.DefaultExpanded, fresh.expanded)
        assertEquals(emptyList(), fresh.recent)
    }
}
