package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreationDefaultsTest {
    private val registry = ComponentCatalog.registry

    private fun appearance(kind: ComponentKind, defaults: CreationDefaults) =
        registry.createNew(kind, "c", GridPoint(0, 0), defaults).attributes["appearance"]

    @Test
    fun evolutionDefaultsGiveNewComponentsTheEvolutionAndNewPinAppearances() {
        val defaults = CreationDefaults()
        assertEquals("logisim_evolution", appearance(ComponentKind.D_FLIP_FLOP, defaults))
        assertEquals("logisim_evolution", appearance(ComponentKind.REGISTER, defaults))
        assertEquals("NewPins", appearance(ComponentKind.INPUT, defaults))
        assertEquals("NewPins", appearance(ComponentKind.OUTPUT, defaults))
        assertEquals("NewPins", appearance(ComponentKind.PROBE, defaults))
        assertEquals("NewPins", appearance(ComponentKind.CLOCK, defaults))
    }

    @Test
    fun classicAndHolyCrossDefaultsUseClassicComponentsAndOldPins() {
        // AppPreferences.getDefaultAppearance(): only Logisim-evolution maps to itself; the FPGA style uses classic.
        for (style in listOf("classic", "evolution")) {
            val defaults = CreationDefaults(componentAppearance = "classic", circuitAppearance = style, newInputOutputShapes = false)
            assertEquals("classic", appearance(ComponentKind.D_FLIP_FLOP, defaults))
            assertEquals("classic", appearance(ComponentKind.COUNTER, defaults))
            assertEquals("classic", appearance(ComponentKind.INPUT, defaults))
            assertEquals("classic", appearance(ComponentKind.PROBE, defaults))
        }
        // Components without an appearance attribute keep their catalog defaults.
        assertEquals(registry.create(ComponentKind.AND, "c", GridPoint(0, 0)),
            registry.createNew(ComponentKind.AND, "c", GridPoint(0, 0), CreationDefaults(componentAppearance = "classic")))
    }

    @Test
    fun newCircuitsTakeTheCircuitAppearanceAndFixedBoxSize() {
        assertEquals(mapOf("appearance" to "logisim_evolution", "circuitnamedboxfixedsize" to "true"), CreationDefaults().circuitAttributes())
        assertEquals(mapOf("appearance" to "evolution", "circuitnamedboxfixedsize" to "false"),
            CreationDefaults(circuitAppearance = "evolution", namedCircuitBoxesFixedSize = false).circuitAttributes())
        val pins = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), label = "a"),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(100, 0), label = "y"),
        )
        fun width(defaults: CreationDefaults): Int {
            val circuit = CircuitDocument("sub", pins, emptyList(), attributes = defaults.circuitAttributes())
            val bounds = checkNotNull(SymbolAppearance.rendered(circuit)).ports.map { it.position.x }
            return bounds.max() - bounds.min()
        }
        // DefaultEvolutionAppearance: fixed boxes are 200 units wide regardless of the labels.
        assertEquals(220, width(CreationDefaults()))
        assertTrue(width(CreationDefaults(namedCircuitBoxesFixedSize = false)) < 220)
    }

    @Test
    fun editorPlacementAppliesDefaultsOnlyToNewComponents() {
        val existing = registry.create(ComponentKind.D_FLIP_FLOP, "old", GridPoint(0, 0), mapOf("appearance" to "classic"))
        val editor = CircuitEditor(CircuitDocument("main", listOf(existing), emptyList()), registry)
        val id = editor.add(ComponentKind.D_FLIP_FLOP, GridPoint(100, 0), CreationDefaults())
        val components = editor.document.components.associateBy { it.id }
        assertEquals("classic", components.getValue("old").attributes["appearance"])
        assertEquals("logisim_evolution", components.getValue(id).attributes["appearance"])
    }

    @Test
    fun removesUnusedLibrariesOnlyWhenRequested() {
        val xml = """
            <project source="3.8.0" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#Gates" name="1"/>
              <lib desc="#Plexers" name="2"/>
              <lib desc="#Arithmetic" name="3"/>
              <lib desc="#Memory" name="4"><tool name="ROM"><a name="appearance" val="classic"/></tool></lib>
              <lib desc="#Base" name="5"/>
              <main name="main"/>
              <toolbar><tool lib="5" name="Poke Tool"/><tool lib="3" name="Adder"/></toolbar>
              <circuit name="main">
                <comp lib="1" loc="(100,100)" name="AND Gate"/>
              </circuit>
            </project>
        """.trimIndent()
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        val kept = CircCodec.writeProject(project)
        listOf("#Wiring", "#Gates", "#Plexers", "#Arithmetic", "#Memory", "#Base").forEach { assertTrue("desc=\"$it\"" in kept, it) }

        val pruned = CircCodec.writeProject(project, removeUnusedLibraries = true)
        // XmlWriter keeps #Base and libraries used by components or by toolbar and mouse-mapping tools; ids do not change.
        assertTrue("<lib desc=\"#Gates\" name=\"1\"/>" in pruned)
        assertTrue("<lib desc=\"#Arithmetic\" name=\"3\"/>" in pruned)
        assertTrue("<lib desc=\"#Base\" name=\"5\"/>" in pruned)
        listOf("#Wiring", "#Plexers", "#Memory").forEach { assertFalse("desc=\"$it\"" in pruned, it) }
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(pruned)).project
        assertEquals(project.mainCircuit.components.map { it.kind to it.position }, reopened.mainCircuit.components.map { it.kind to it.position })
        // Like Evolution's repairFloatLibrary, reading a 3.8.0 file adds #FPArithmetic after #Arithmetic (#323).
        assertEquals(mapOf("1" to "#Gates", "3" to "#Arithmetic", "float" to "#FPArithmetic", "5" to "#Base"), reopened.libraries)
    }
}
