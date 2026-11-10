package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocMemoryMapTest {
    @Test
    fun rv32FixtureListsItsDevicesAndGapsBeforeTheFirstTransaction() {
        val fixture = listOf(File("fixtures/soc/program-rv32.circ"), File("../fixtures/soc/program-rv32.circ"))
            .first { it.isFile }
        val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture.readText(), registry)).project
        val document = project.mainCircuit
        val components = document.components
        val simulation = CircuitSimulator(registry = registry).settle(document)
        val bus = components.single { it.kind == SocComponents.BUS }
        val ram = components.single { it.kind == SocComponents.MEMORY }
        val pio = components.single { it.kind == SocComponents.PIO }
        val cpu = components.single { it.kind == SocComponents.RV32 }
        val runtime = SocRuntimeScope(components, simulation.componentStates)
        assertTrue(assertIs<SocBusComponentState>(simulation.componentStates[bus.id]).bus.trace.isEmpty())

        assertEquals(listOf(
            SocMemoryMapRow(0x00000000u, 0x000003FFu, SocMemoryMapDevice(ram, ram.id)),
            SocMemoryMapRow(0x00000400u, 0x00000FFFu),
            SocMemoryMapRow(0x00001000u, 0x00001017u, SocMemoryMapDevice(pio, pio.id)),
            SocMemoryMapRow(0x00001018u, 0x0BFFFFFFu),
            SocMemoryMapRow(0x0C000000u, 0x0C3FFFFFu, SocMemoryMapDevice(cpu, "${cpu.id}/plic")),
            SocMemoryMapRow(0x0C400000u, UInt.MAX_VALUE),
        ), runtime.memoryMap(bus))
        assertTrue(runtime.updatedStates().isEmpty())
    }

    @Test
    fun nestedOverlapsKeepTheWholeRangeMappedAndAnotherBusHasItsOwnMap() {
        val first = CircuitComponent("first", SocComponents.BUS, GridPoint(0, 0))
        val second = CircuitComponent("second", SocComponents.BUS, GridPoint(0, 200))
        val ram = CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 400), attributes =
            mapOf("SocBusSelection" to first.id))
        val nested = CircuitComponent("nested", SocComponents.PIO, GridPoint(0, 500), attributes =
            mapOf("SocBusSelection" to first.id, "StartAddress" to "0x100"))
        val top = CircuitComponent("top", SocComponents.PIO, GridPoint(0, 600), attributes =
            mapOf("SocBusSelection" to first.id, "StartAddress" to "0xFFFFFFFC"))
        val otherPio = nested.copy(id = "other", attributes = nested.attributes + ("SocBusSelection" to second.id))
        val runtime = SocRuntimeScope(listOf(first, second, ram, nested, top, otherPio), emptyMap())

        assertEquals(listOf(
            SocMemoryMapRow(0u, 0x3FFu, SocMemoryMapDevice(ram, ram.id)),
            SocMemoryMapRow(0x100u, 0x117u, SocMemoryMapDevice(nested, nested.id)),
            SocMemoryMapRow(0x400u, 0xFFFFFFFBu),
            SocMemoryMapRow(0xFFFFFFFCu, UInt.MAX_VALUE, SocMemoryMapDevice(top, top.id)),
        ), runtime.memoryMap(first))
        assertEquals(listOf(
            SocMemoryMapRow(0u, 0xFFu),
            SocMemoryMapRow(0x100u, 0x117u, SocMemoryMapDevice(otherPio, otherPio.id)),
            SocMemoryMapRow(0x118u, UInt.MAX_VALUE),
        ), runtime.memoryMap(second))
        assertEquals(listOf(SocMemoryMapRow(0u, UInt.MAX_VALUE)),
            SocRuntimeScope(listOf(second), emptyMap()).memoryMap(second))
    }
}
