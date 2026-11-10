package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryComponentsTest {
    private val registry = ComponentRegistry(MemoryComponents.definitions())

    @Test
    fun allThreeTypesResolveWithNamedTerminals() {
        val rom = registry.resolve("#Memory", "ROM", emptyMap())
        val ram = registry.resolve("#Memory", "RAM", emptyMap())
        val dual = registry.resolve("#Memory", "DualRAM", emptyMap())
        assertEquals(ComponentKind.ROM, assertNotNull(rom).kind)
        assertEquals(ComponentKind.RAM, assertNotNull(ram).kind)
        assertEquals(MemoryComponents.DualRam, assertNotNull(dual).kind)
        val component = registry.create(MemoryComponents.DualRam, "dual", GridPoint(0, 0),
            mapOf("dataWidth" to "16", "byteenables" to "byteEnables"))
        val layout = registry.layout(component)
        assertNotNull(layout.ports.firstOrNull { it.name == "byteEnable1_1" })
        assertEquals(2, layout.ports.count { it.name.startsWith("address") })
    }

    @Test
    fun ramEditsAffectSessionStateAndNeverDocumentAttributes() {
        val component = registry.create(ComponentKind.RAM, "ram", GridPoint(0, 0), mapOf("addrWidth" to "4"))
        val definition = registry.requireDefinition(component.kind)
        val attributes = registry.attributes(component)
        val initial = definition.behavior!!.initialState(component, attributes)
        val changed = definition.behavior.evaluate(ComponentEvaluationContext(component, attributes,
            mapOf("address0" to LogicVector.low(4)), setOf("address0"), initial,
            events = listOf(MemoryCellsChanged(mapOf(2L to 0xabuL)))))
        assertEquals(0xabuL, MemoryComponents.image(changed.state)!![2])
        assertEquals(0uL, MemoryComponents.image(initial)!![2])
        assertFalse(component.attributes.containsKey("contents"))
        assertTrue(changed.diagnostics.isEmpty())
    }

    @Test
    fun invalidRamEditPreservesTheEntirePriorImage() {
        val component = registry.create(ComponentKind.RAM, "ram", GridPoint(0, 0), mapOf("addrWidth" to "4"))
        val definition = registry.requireDefinition(component.kind)
        val attributes = registry.attributes(component)
        val initial = definition.behavior!!.initialState(component, attributes)
        val changed = definition.behavior.evaluate(ComponentEvaluationContext(component, attributes,
            emptyMap(), emptySet(), initial, events = listOf(MemoryCellsChanged(mapOf(1L to 2uL, 20L to 3uL)))))
        assertEquals(MemoryComponents.image(initial), MemoryComponents.image(changed.state))
        assertEquals(1, changed.diagnostics.size)
    }

    @Test
    fun originalRomReleasesItsDataWhenDeselectedAndHoldsItWithoutAnAddress() {
        val configuration = MemoryConfiguration(addressBits = 2, dataBits = 8, readOnly = true, originalRom = true)
        val image = MemoryImage(2, 8, mapOf(0L to 0xa5uL, 1L to 0x5auL))
        val initial = MemoryRuntimeState.initial(configuration, image)
        fun read(previous: MemoryRuntimeState, address: LogicVector, select: LogicValue) =
            MemoryCore.evaluate(configuration, previous, listOf(MemoryPortInput(address, chipSelect = select))).state
        val enabled = read(initial, LogicVector.low(2), LogicValue.UNKNOWN)
        assertEquals(0xa5uL, enabled.outputs.single().single().toULongOrNull())
        val floatingAddress = read(enabled, LogicVector.unknown(2), LogicValue.HIGH)
        assertEquals(0xa5uL, floatingAddress.outputs.single().single().toULongOrNull())
        val disabled = read(floatingAddress, LogicVector.unknown(2), LogicValue.LOW)
        assertTrue(disabled.outputs.single().single().isUnknown)
        assertEquals(-1L, disabled.currentAddresses.single())
        val reenabled = read(disabled, LogicVector.of(2, 1uL), LogicValue.HIGH)
        assertEquals(0x5auL, reenabled.outputs.single().single().toULongOrNull())
    }

    @Test
    fun movingRomBetweenProjectVersionsPreservesItsTerminalProfile() {
        fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        fun original(source: String) = read("""
            <project source="$source">
              <lib desc="#Memory" name="0"/>
              <circuit name="main"><comp lib="0" loc="(350,260)" name="ROM"/></circuit>
            </project>
        """.trimIndent())
        val legacy = original("2.7.1")
        val modern = original("3.8.0")
        val expected = mapOf(
            "address0" to GridPoint(210, 260), "chipSelect" to GridPoint(260, 300), "dataOut0_0" to GridPoint(350, 260),
        )
        fun ports(project: CircuitProject) = project.mainCircuit.components.single().layout().ports.associate { it.name to it.position }
        assertEquals(expected, ports(legacy))
        assertEquals(expected, ports(read(CircCodec.writeProject(legacy.copy(sourceVersion = modern.sourceVersion)))))
        assertEquals(
            mapOf("address0" to GridPoint(350, 270), "dataOut0_0" to GridPoint(590, 320)),
            ports(read(CircCodec.writeProject(modern.copy(sourceVersion = legacy.sourceVersion)))),
        )
    }

    @Test
    fun romContentIsDocumentOwnedAndSessionEditsAreRefused() {
        val image = MemoryImage(4, 8, mapOf(2L to 0xabuL))
        val component = registry.create(ComponentKind.ROM, "rom", GridPoint(0, 0),
            mapOf("addrWidth" to "4", "contents" to MemoryImageCodec.encodeCirc(image)))
        val definition = registry.requireDefinition(component.kind)
        val attributes = registry.attributes(component)
        val state = definition.behavior!!.evaluate(ComponentEvaluationContext(component, attributes,
            mapOf("address0" to LogicVector.of(4, 2uL)), setOf("address0"),
            events = listOf(MemoryCellsChanged(mapOf(2L to 7uL)))))
        assertEquals(0xabuL, state.outputs.getValue("dataOut0_0").toULongOrNull())
        assertEquals(0xabuL, MemoryComponents.image(state.state)!![2])
        assertEquals(1, state.diagnostics.size)
    }
}
