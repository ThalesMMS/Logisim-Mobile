package dev.logisim.mobile.core.localization

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitNetworks
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreLocalizationTest {
    @Test
    fun projectValidationRetainsTheMessageAndImportedName() {
        val name = "Processador {count} → seção العربية"
        val error = assertFailsWith<LocalizedArgumentException> {
            CircuitProject(listOf(CircuitDocument("main")), mainCircuitName = name)
        }
        assertEquals(Messages.ErrorMainCircuitMissing, error.text.key)
        assertEquals("Main circuit does not exist: $name.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Circuito principal inexistente: $name.", error.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun invalidBitOperationsExposeStableMessages() {
        val error = assertFailsWith<LocalizedArgumentException> { LogicVector.low(8).bit(8) }
        assertEquals(Messages.ErrorBitIndex, error.text.key)
        assertEquals("Bit outside the value width: 8.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Bit fora da largura do valor: 8.", error.text.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun networkDiagnosticsCanBeRenderedAgainAfterChangingLanguage() {
        val wide = CircuitComponent("wide", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8"))
        val narrow = CircuitComponent("narrow", ComponentKind.OUTPUT, GridPoint(100, 0), attributes = mapOf("width" to "4"))
        val networks = CircuitNetworks(CircuitDocument(components = listOf(wide, narrow),
            wires = listOf(WireSegment(wide.position, narrow.position))))
        val notice = networks.notices.single()
        assertEquals(Messages.ErrorNetworkWidth, notice.key)
        assertEquals("Incompatible widths on the network at (0,0): 4 / 8 bits.",
            notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Larguras incompatíveis na rede em (0,0): 4 / 8 bits.",
            notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals("座標 (0,0) のネットでビット幅が一致していません：4 / 8 ビット。",
            notice.resolve(AppLanguage.JAPANESE))
        assertEquals("Niezgodne szerokości w sieci połączeń w punkcie (0,0) (w bitach): 4 / 8.",
            notice.resolve(AppLanguage.POLISH))
        assertEquals("(0,0) 处的电气网络位宽不兼容：4 / 8 位。",
            notice.resolve(AppLanguage.CHINESE))
        assertEquals(notice.resolve(AppLanguage.PORTUGUESE), networks.diagnostics.single())
    }

    @Test
    fun simulationWarningsKeepTechnicalIdentitiesAndNames() {
        val name = "ALU {kind} → teste"
        val component = CircuitComponent("mux-1", ComponentKind.MULTIPLEXER, GridPoint(100, 100))
        val registry = ComponentCatalog.registry.withDefinitions(listOf(
            ComponentCatalog.registry.requireDefinition(ComponentKind.MULTIPLEXER).copy(behavior = null)))
        val simulation = CircuitSimulator(registry = registry).settle(CircuitDocument(name, listOf(component)))
        val warning = simulation.notices.single { it.key == Messages.CoreSimulationUnsupportedComponent }
        assertEquals("Simulation is unavailable for MULTIPLEXER (mux-1) in circuit $name.",
            warning.resolve(AppLanguage.ENGLISH))
        assertEquals("Simulação indisponível para MULTIPLEXER (mux-1) no circuito $name.",
            warning.resolve(AppLanguage.PORTUGUESE))
        assertTrue(simulation.diagnostics.contains(warning.resolve(AppLanguage.PORTUGUESE)))
    }
}
