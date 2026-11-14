package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.localizedLabel
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

    /** C26: library rows without a drawn glyph show a curated symbol, never a fragment of the serialized kind name. */
    @Test
    fun libraryRowSymbolsAreCuratedAndDistinctWithinEachLibrary() {
        val definitions = ComponentCatalog.registry.definitions.filter { it.kind.editorIcon() == null && it.library != "#TTL" }
        for (definition in definitions) {
            val label = definition.kind.localizedLabel().resolve(AppLanguage.ENGLISH)
            val symbol = definition.kind.shortSymbol(label)
            assertFalse('_' in symbol, "${definition.kind.name} shows $symbol")
            assertTrue(symbol.length in 1..4, "${definition.kind.name} shows $symbol")
            // Without a label the fallback has nothing to go on: every built-in kind has its own entry.
            assertTrue(definition.kind.shortSymbol() != "?", "${definition.kind.name} has no curated symbol")
        }
        for ((library, members) in definitions.groupBy { it.library }) {
            val symbols = members.map { it.kind.shortSymbol() }
            assertEquals(symbols.size, symbols.toSet().size, "$library repeats a symbol: $symbols")
        }
        // The reported collisions are gone and kind names are unchanged.
        assertEquals(listOf("MUX", "×", "JK", "SR", "T", "DMX", "PE", "SHR", "<<", "VCC", "POR"),
            listOf("MULTIPLEXER", "MULTIPLIER", "JK_FLIP_FLOP", "SR_FLIP_FLOP", "T_FLIP_FLOP", "DEMULTIPLEXER",
                "PRIORITY_ENCODER", "SHIFT_REGISTER", "SHIFTER", "POWER", "POWER_ON_RESET").map { ComponentKind(it).shortSymbol() })
        assertEquals(8, definitions.count { it.library == "#Soc" && it.kind.name.startsWith("SOC") })
    }

    @Test
    fun componentsOutsideTheTableShowTheInitialsOfTheirLabel() {
        val gray = GrayCodeExtension.incrementerKind
        assertEquals("GCI", gray.shortSymbol("Gray code incrementer"))
        assertEquals("CON", ComponentKind("JAR:abc:0").shortSymbol("Contador"))
        assertFalse('_' in ComponentKind("SOME_NEW_KIND").shortSymbol("Some new kind"))
    }
}
