package dev.logisim.mobile.core

import kotlin.test.*
import dev.logisim.mobile.core.localization.AppLanguage

class SocCopyTest {
    private val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
    private val bus = CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "original-bus"))
    private val memory = CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = mapOf("SocBusSelection" to "original-bus"))
    private val dma = CircuitComponent("dma", SocComponents.DMA, GridPoint(0, 300), attributes = mapOf("SocBusSelection" to "original-bus", "DmaSrcBus" to "original-bus", "DmaDstBus" to "external-bus"))

    @Test fun copyingBusAndDevicesRemapsOnlyInternalReferences() {
        val editor = CircuitEditor(CircuitDocument("main", listOf(bus, memory, dma)), registry)
        val ids = editor.paste(editor.copy(setOf("bus", "ram", "dma")), GridPoint(0, 500))
        val copies = editor.document.components.filter { it.id in ids }
        val copiedBus = copies.single { it.kind == SocComponents.BUS }.attributes.getValue("SocBusIdentifier")
        assertNotEquals("original-bus", copiedBus)
        assertEquals(copiedBus, copies.single { it.kind == SocComponents.MEMORY }.attributes["SocBusSelection"])
        val copiedDma = copies.single { it.kind == SocComponents.DMA }
        assertEquals(copiedBus, copiedDma.attributes["SocBusSelection"])
        assertEquals(copiedBus, copiedDma.attributes["DmaSrcBus"])
        assertEquals("external-bus", copiedDma.attributes["DmaDstBus"])
        assertFalse(CircuitSimulator(registry = registry).settle(editor.document).oscillating)
        editor.undo()
        assertEquals(3, editor.document.components.size)
        editor.redo()
        assertEquals(copiedBus, editor.document.components.first { it.id in ids && it.kind == SocComponents.BUS }.attributes["SocBusIdentifier"])
    }

    @Test fun copyingADeviceAloneKeepsItsExternalBusBinding() {
        val editor = CircuitEditor(CircuitDocument("main", listOf(bus, memory)), registry)
        val ids = editor.paste(editor.copy(setOf("ram")), GridPoint(0, 500))
        assertEquals("original-bus", editor.document.components.single { it.id in ids }.attributes["SocBusSelection"])
    }

    @Test fun importedDuplicateBusIdentifiersProduceDiagnosticsWithoutCrashing() {
        val document = CircuitDocument("main", listOf(bus, bus.copy(id = "duplicate", position = GridPoint(0, 500)), memory))
        val state = CircuitSimulator(registry = registry).settle(document, events = mapOf("bus" to listOf(SocTransactionEvent(SocBusRequest(0u)))))
        assertFalse(state.oscillating)
        val notice = assertNotNull(state.notices.firstOrNull { it.key == "soc_duplicate_bus" })
        assertEquals("original-bus", notice.arguments["id"])
        assertEquals("Duplicate SoC bus identifier: original-bus", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Identificador de barramento SoC duplicado: original-bus", notice.resolve(AppLanguage.PORTUGUESE))
        val router = SocBusRouter(listOf(SocAddressSpace("same"), SocAddressSpace("same")))
        assertEquals(SocBusError.MULTIPLE_RESPONDERS, router.transact("same", SocBusRequest(0u)).error)
    }
}
