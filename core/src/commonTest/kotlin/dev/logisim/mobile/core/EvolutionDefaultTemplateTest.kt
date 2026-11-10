package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #323 (one #FPArithmetic once Evolution repairs a pre-4.1 file) and #324 (Evolution's default.templ). */
class EvolutionDefaultTemplateTest {
    private val registry = ComponentCatalog.registry
    private val fpAdder = registry.definitions.first { it.library == "#FPArithmetic" && it.fileName == "FPAdder" }

    private fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project

    private fun libraries(xml: String) = CircXmlParser(xml).parse().children.filter { it.name == "lib" }
        .associate { it.attributes.getValue("name") to it.attributes.getValue("desc") }

    @Test
    fun newProjectsTakeTheTemplateLibrariesToolbarAndMappings() {
        val project = EvolutionDefaultTemplate.apply(CircuitProject(listOf(CircuitDocument("main"))), registry)
        assertEquals(EvolutionDefaultTemplate.libraries, project.libraries.values.toList())
        assertFalse("#HDL-IP" in project.libraries.values)
        val toolbar = ProjectToolbar.read(project)
        assertEquals(listOf("Poke Tool", "Edit Tool", "Wiring Tool", "Text Tool", "Image", "sep", "Pin", "Pin", "sep",
            "NOT Gate", "AND Gate", "OR Gate", "XOR Gate", "NAND Gate", "NOR Gate", "sep", "D Flip-Flop", "Register"),
            toolbar.map { it.name })
        assertTrue(toolbar.filterNot { it.isSeparator }.all { ProjectToolbar.binding(it, project, registry) != null })
        val mappings = ProjectMouseMappings.read(project, registry)
        assertEquals(mapOf("Button2" to "Poke Tool", "Button3" to "Menu Tool", "Ctrl Button1" to "Menu Tool"),
            mappings.entries.associate { it.key.fileValue() to it.value.name })
        // Unused libraries go, except the ones the toolbar and mappings use (#Gates and #Memory, as in Evolution).
        val pruned = CircCodec.writeProject(project, registry, removeUnusedLibraries = true)
        assertEquals(setOf("#Wiring", "#Gates", "#Memory", "#Base"), libraries(pruned).values.toSet())
    }

    @Test
    fun preFourOneFilesLeaveTheFloatLibraryToEvolutionsRepair() {
        val circuit = CircuitDocument("main", listOf(registry.create(fpAdder.kind, "fp", GridPoint(100, 100))))
        val project = EvolutionDefaultTemplate.apply(CircuitProject(listOf(circuit)), registry)
        assertEquals("3.8.0", project.sourceVersion)
        val xml = CircCodec.writeProject(project, registry)
        val written = libraries(xml)
        assertFalse("#FPArithmetic" in written.values, xml)
        val arithmetic = written.entries.single { it.value == "#Arithmetic" }.key
        val component = CircXmlParser(xml).parse().children.single { it.name == "circuit" }.children.single { it.name == "comp" }
        assertEquals(arithmetic to "FPAdder", component.attributes["lib"] to component.attributes["name"])

        // Mobile repairs it as Evolution does: the library comes back after #Arithmetic and the component stays an FP adder.
        val reopened = read(xml)
        val names = reopened.libraries.values.toList()
        assertEquals(names.indexOf("#Arithmetic") + 1, names.indexOf("#FPArithmetic"))
        assertEquals(fpAdder.kind, reopened.mainCircuit.components.single().kind)
        assertEquals(xml, CircCodec.writeProject(reopened, registry))
    }

    @Test
    fun filesSavedWithBothLibrariesAreRepairedAndNewerFilesKeepTheirs() {
        val old = """
            <project source="3.8.0" version="1.0">
              <lib desc="#Arithmetic" name="2"/>
              <lib desc="#FPArithmetic" name="3"/>
              <circuit name="main"><comp lib="3" loc="(100,100)" name="FPAdder"/></circuit>
            </project>
        """.trimIndent()
        val resaved = CircCodec.writeProject(read(old), registry)
        assertEquals(mapOf("2" to "#Arithmetic"), libraries(resaved))
        assertTrue("lib=\"2\"" in resaved && "name=\"FPAdder\"" in resaved)

        val newer = old.replace("3.8.0", "5.1.0dev")
        assertEquals(mapOf("2" to "#Arithmetic", "3" to "#FPArithmetic"), libraries(CircCodec.writeProject(read(newer), registry)))

        // A toolbar tool of #FPArithmetic keeps the library declared: Evolution would not find it in #Arithmetic.
        val toolbar = old.replace("<circuit", "<toolbar><tool lib=\"3\" name=\"FPAdder\"/></toolbar>\n  <circuit")
        assertEquals(mapOf("2" to "#Arithmetic", "3" to "#FPArithmetic"), libraries(CircCodec.writeProject(read(toolbar), registry)))
    }
}
