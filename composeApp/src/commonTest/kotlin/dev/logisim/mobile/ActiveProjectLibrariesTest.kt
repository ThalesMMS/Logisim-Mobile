package dev.logisim.mobile

import dev.logisim.mobile.core.CircBundleCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitBundle
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.ProjectCircuitLibraries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActiveProjectLibrariesTest {
    private val main = """<project source="2.7.1"><main name="main"/><circuit name="main"/></project>"""
    private val dependencyRoot = """<project source="2.7.1"><lib name="dep" desc="file#child.circ"/>
        <main name="main"/><circuit name="main"><comp lib="dep" name="Child" loc="(50,50)"/></circuit></project>"""
    private val child = """<project source="2.7.1"><main name="Child"/><circuit name="Child"/></project>"""

    @Test
    fun loadAndPlaceKeepActiveDocumentAndRefuseUnloadUntilLastReferenceIsRemoved() {
        val state = EditorState(main)
        val identity = state.documentIdentity
        val latin1 = ("""<?xml version="1.0" encoding="ISO-8859-1"?>""" +
            main.replace("<circuit name=\"main\"/>", """<circuit name="main"><a name="clabel" val="ação"/></circuit>"""))
            .map { it.code.toByte() }.toByteArray()
        val source = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(
            DocumentAsset("Chip.circ", "application/octet-stream", latin1), ComponentCatalog.registry,
        )).project
        assertEquals("ação", source.mainCircuit.attributes["clabel"])
        assertTrue(state.attachLibrary(source, "Chip"))
        assertTrue(state.attachLibrary(source, "Chip"))
        val (first, second) = ProjectCircuitLibraries.list(state.project)
        assertEquals(listOf("Chip: main", "Chip: main (2)"), listOf(first, second).map { it.circuits.single().name })
        assertEquals(identity, state.documentIdentity)
        assertEquals("main", state.document.name)
        assertTrue(state.placeLibraryCircuit(first.circuits.single()))
        state.tap(GridPoint(100, 100))
        val instance = state.document.components.single()
        assertEquals("Chip: main", instance.circuitName)
        val before = state.exportXml()
        assertFalse(state.detachLibrary(first.id))
        assertEquals("active_library_in_use", state.statusText.key)
        assertEquals(before, state.exportXml())

        val reopened = EditorState(before)
        assertEquals(listOf(first.id, second.id), ProjectCircuitLibraries.list(reopened.project).map { it.id })
        assertTrue(reopened.removeComponent(instance.id))
        assertTrue(reopened.detachLibrary(first.id))
        assertEquals(listOf(second.id), ProjectCircuitLibraries.list(reopened.project).map { it.id })
        reopened.undo()
        assertEquals(listOf(first.id, second.id), ProjectCircuitLibraries.list(reopened.project).map { it.id })
    }

    @Test
    fun reloadingAnAuthorizedLibraryKeepsItsInstanceAndWiresAndRejectsIncompatibleSources() {
        val parent = """<project source="2.7.1"><lib name="0" desc="#Wiring"/>
            <main name="main"/><circuit name="main">
            <wire from="(300,100)" to="(500,100)"/>
            <comp lib="0" name="Pin" loc="(500,100)"><a name="label" val="Y"/>
            <a name="output" val="true"/><a name="facing" val="west"/></comp>
            </circuit><circuit name="Supply"/></project>"""
        val low = """<project source="2.7.1"><lib name="0" desc="#Wiring"/>
            <main name="Supply"/><circuit name="Supply">
            <comp lib="0" name="Constant" loc="(100,100)"><a name="value" val="0x0"/></comp>
            <wire from="(100,100)" to="(300,100)"/>
            <comp lib="0" name="Pin" loc="(300,100)"><a name="label" val="O"/>
            <a name="output" val="true"/><a name="facing" val="west"/></comp>
            </circuit></project>"""
        fun read(xml: String) = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(
            DocumentAsset("Library.circ", "application/octet-stream", xml.encodeToByteArray()),
            ComponentCatalog.registry,
        )).project
        val loaded = EditorState(parent)
        assertTrue(loaded.attachLibrary(read(low), "Library: Gates"))
        assertTrue(loaded.attachLibrary(read(low), "Library: Gates"))
        val attached = ProjectCircuitLibraries.list(loaded.project).last()
        assertTrue(loaded.placeLibraryCircuit(attached.circuits.single()))
        loaded.tap(GridPoint(300, 100))
        // Libraries saved before Reload did not retain the original name separately.
        val state = EditorState(loaded.exportXml().replace(Regex(""" logisim-mobile-library-source-name="[^"]*""""), ""))
        val output = state.document.components.single { it.label == "Y" }
        val instance = state.document.components.single { it.circuitName != null }
        val wires = state.document.wires
        fun value() = state.rootSimulation.componentValues[output.id]
        assertEquals(LogicValue.LOW, value())

        val high = low.replace("0x0", "0x1")
        assertTrue(state.reloadLibrary(attached.id, read(high)), state.statusText.toString())
        assertEquals(attached.circuits.single().id, ProjectCircuitLibraries.list(state.project).last().circuits.single().id)
        assertEquals(instance.id, state.document.components.single { it.circuitName != null }.id)
        assertEquals(wires, state.document.wires)
        assertEquals(LogicValue.HIGH, value())
        val refreshed = state.exportXml()
        assertEquals(LogicValue.HIGH, EditorState(refreshed).rootSimulation.componentValues[output.id])

        for (invalid in listOf(high.replace("Supply", "Renamed"), high.replace("""val="O"""", """val="Different""""))) {
            assertFalse(state.reloadLibrary(attached.id, read(invalid)))
            assertEquals("active_library_reload_incompatible", state.statusText.key)
            assertEquals(refreshed, state.exportXml())
            assertEquals(LogicValue.HIGH, value())
        }

        state.undo()
        assertEquals(LogicValue.LOW, value())
    }

    @Test
    fun portableDependenciesLoadAndUnloadAsAGroupWhileUnresolvedFilesAreRejected() {
        val state = EditorState(main)
        val before = state.exportXml()
        for (xml in listOf(dependencyRoot, dependencyRoot.substringBefore("<comp") + "</circuit></project>")) {
            val unresolved = readActiveProjectLibrary(DocumentAsset(
                "Library.circ", "application/octet-stream", xml.encodeToByteArray(),
            ), ComponentCatalog.registry)
            assertIs<CircProjectImportResult.Failure>(unresolved)
        }
        assertEquals(before, state.exportXml())

        val bundle = CircuitBundle("main.circ", CircBundleCodec.create(state.project).files + mapOf(
            "main.circ" to dependencyRoot.encodeToByteArray(),
            "child.circ" to child.encodeToByteArray(),
        ))
        val source = assertIs<CircProjectImportResult.Success>(readActiveProjectLibrary(DocumentAsset(
            "Library.lsebdl", PORTABLE_PROJECT_MIME_TYPE, CircBundleCodec.write(bundle),
        ), ComponentCatalog.registry)).project
        assertTrue(state.attachLibrary(source, "Library"))
        val library = ProjectCircuitLibraries.list(state.project).single()
        val (entry, dependency) = library.circuits
        assertEquals(dependency.name, entry.components.single().circuitName)
        assertTrue(state.detachLibrary(library.id))
        assertEquals(before, state.exportXml())
    }
}
