package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Mobile's canvas menu against Evolution d58a61e0 MenuTool popups (scripts/oracle/base-tools menu-desktop.tsv, #347). */
class ComponentMenuTest {
    private val evolution = mapOf(
        "AND Gate" to "Rotate Left, Rotate Right, Delete, Show Attributes",
        "PLA" to "Rotate Left, Rotate Right, Delete, Show Attributes, |, Edit PLA Program…",
        "Pin" to "Rotate Left, Rotate Right, Delete, Show Attributes",
        "RAM" to "Delete, Show Attributes, |, Edit Contents…, Clear Contents, Load Image…, Save Image…",
        "ROM" to "Delete, Show Attributes, |, Edit Contents…, Clear Contents, Load Image…, Save Image…",
        "Splitter" to "Rotate Left, Rotate Right, Delete, Show Attributes, |, Distribute Ascending (disabled), Distribute Descending",
        "Text" to "Delete, Show Attributes",
        "child" to "Rotate Left, Rotate Right, Delete, Show Attributes, View child",
    )

    @Test
    fun componentAndSelectionMenusMatchEvolution() {
        val registry = ComponentCatalog.registry.withDefinitions(AnnotationLibrary.definitions())
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(MENU_TOOL, registry)).project
        val names = mapOf(ComponentKind.INPUT to "Pin", GatesLibrary.Pla to "PLA", ComponentKind.RAM to "RAM", ComponentKind.ROM to "ROM",
            ComponentKind.SPLITTER to "Splitter", ComponentKind.TEXT to "Text")
        val mobile = project.mainCircuit.components.associate { component ->
            val name = component.circuitName ?: names[component.kind] ?: "AND Gate"
            name to ComponentMenu.describe(ComponentMenu.component(component, registry), component.circuitName)
        }
        assertEquals(evolution, mobile)
        assertEquals("Delete Selection, Cut Selection, Copy Selection", ComponentMenu.describe(ComponentMenu.selection()))
        assertEquals("Delete (disabled), Show Attributes",
            ComponentMenu.describe(ComponentMenu.component(project.mainCircuit.components.first { it.kind == ComponentKind.TEXT }, registry, canChange = false)))
    }

    /** The other MenuExtenders (#359), scripts/oracle/base-tools menu-desktop.tsv at d58a61e0. */
    @Test
    fun extenderMenusMatchEvolution() {
        val registry = ComponentCatalog.registry
        fun menu(kind: ComponentKind, state: ComponentState? = null) =
            ComponentMenu.describe(ComponentMenu.component(registry.create(kind, "c", GridPoint(100, 100)), registry, state = state))
        val cpu = "Delete, Show Attributes, |, Open assembler, Read ELF file"
        assertEquals(cpu, menu(SocComponents.RV32))
        assertEquals(cpu, menu(SocComponents.NIOS2))
        assertEquals("Delete, Show Attributes, |, Show memory map, Insert a bus transaction", menu(SocComponents.BUS))
        assertEquals("Delete, Show Attributes, |, Export C-templates", menu(SocComponents.PIO))
        assertEquals("Delete, Show Attributes, |, Export C-templates", menu(SocComponents.VGA))
        assertEquals("Delete, Show Attributes", menu(SocComponents.MEMORY))
        assertEquals("Delete, Show Attributes, |, Edit Contents…, Clear Contents", menu(ExtraIoKinds.PlaRom))
        assertEquals("Delete, Show Attributes, Reset to Original Size, Optimize Image Size, Make Outer Background Transparent",
            menu(AnnotationLibrary.Image))
        // SocUpStateInterface.programLoaded adds Show loaded program.
        val program = SocCpuDebugState(program = SocProgramListing(SocExecutableImage(SocArchitecture.RV32IM, 0u,
            listOf(SocExecutableSegment(0u, byteArrayOf(0x13, 0, 0, 0), 4u)))))
        val rv32 = SocEvaluation.initialState(registry.create(SocComponents.RV32, "c", GridPoint(0, 0))) as SocRv32ComponentState
        assertEquals("$cpu, Show loaded program", menu(SocComponents.RV32, rv32.copy(debugger = program)))
        val nios2 = SocEvaluation.initialState(registry.create(SocComponents.NIOS2, "c", GridPoint(0, 0))) as SocNios2ComponentState
        assertEquals("$cpu, Show loaded program", menu(SocComponents.NIOS2, nios2.copy(debugger = program)))
        assertEquals(cpu, menu(SocComponents.NIOS2, nios2))
    }

    @Test
    fun splitterDistributionFollowsComputeDistribution() {
        assertEquals(listOf(0, 0, 0, 1, 1), ComponentMenu.splitterDistribution(2, 5, ascending = true))
        assertEquals(listOf(1, 1, 0, 0, 0), ComponentMenu.splitterDistribution(2, 5, ascending = false))
        assertEquals(listOf(3, 2, 1, 0), ComponentMenu.splitterDistribution(4, 4, ascending = false))
        val splitter = ComponentCatalog.registry.create(ComponentKind.SPLITTER, "s", GridPoint(0, 0), mapOf("fanout" to "2", "incoming" to "4"))
        assertEquals(mapOf("bit0" to "1", "bit1" to "1", "bit2" to "0", "bit3" to "0"), ComponentMenu.redistribution(splitter, ascending = false))
        assertEquals(emptyMap(), ComponentMenu.redistribution(splitter, ascending = true))
        assertEquals(Direction.NORTH, ComponentMenu.rotated(Direction.EAST, left = true))
        assertEquals(Direction.SOUTH, ComponentMenu.rotated(Direction.EAST, left = false))
    }

    private companion object {
        const val MENU_TOOL = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <lib desc="#Base" name="6"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin">
      <a name="label" val="a"/>
    </comp>
    <comp lib="1" loc="(300,100)" name="AND Gate"/>
    <comp lib="0" loc="(100,300)" name="Splitter"/>
    <comp lib="4" loc="(300,400)" name="RAM"/>
    <comp lib="4" loc="(600,400)" name="ROM"/>
    <comp lib="1" loc="(600,100)" name="PLA"/>
    <comp lib="6" loc="(100,600)" name="Text">
      <a name="text" val="note"/>
    </comp>
    <comp loc="(400,700)" name="child"/>
  </circuit>
  <circuit name="child">
    <comp lib="0" loc="(100,100)" name="Pin">
      <a name="label" val="x"/>
    </comp>
  </circuit>
</project>"""
    }
}
