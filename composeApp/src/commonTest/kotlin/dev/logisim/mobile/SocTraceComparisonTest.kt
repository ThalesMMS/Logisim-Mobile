package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SocBusError
import dev.logisim.mobile.core.SocBusRequest
import dev.logisim.mobile.core.SocComponents
import dev.logisim.mobile.core.SocTransactionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SocTraceComparisonTest {
    @Test
    fun comparesSeparateHistoriesWhenTwoInstancesShareTheSameBusIdentifier() {
        val main = CircuitDocument("Main", listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), label = "Board", circuitName = "Board"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(300, 0), label = "Board", circuitName = "Board"),
        ))
        val board = CircuitDocument("Board", listOf(
            CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "shared")),
            CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = mapOf("SocBusSelection" to "shared")),
        ))
        val project = CircuitProject(listOf(main, board))
        val state = CircuitSimulator().settle(project, events = mapOf(
            "left/bus" to listOf(
                SocTransactionEvent(SocBusRequest(0u, 0x12u, read = false, write = true)),
                SocTransactionEvent(SocBusRequest(0u)),
            ),
            "right/bus" to listOf(
                SocTransactionEvent(SocBusRequest(0u, 0xABu, read = false, write = true)),
                SocTransactionEvent(SocBusRequest(0u)),
                SocTransactionEvent(SocBusRequest(0xFFFF0000u)),
            ),
        ))

        val (left, right) = socTraceBuses(project, state).also { assertEquals(2, it.size) }
        // Same-named instances get distinct readable paths (#241); the bus identifier stays shared.
        assertEquals("Main / Board [1] / shared", left.breadcrumb)
        assertEquals("Main / Board [2] / shared", right.breadcrumb)
        assertEquals("left / bus", left.path.label)
        assertEquals("right / bus", right.path.label)
        assertNotEquals(left.path, right.path)
        assertEquals(listOf("bus", "bus"), left.bus.trace.map { it.request.initiator })
        assertEquals(listOf("bus", "bus", "bus"), right.bus.trace.map { it.request.initiator })
        assertEquals(0x12u, left.bus.trace[1].reply.data)
        assertEquals(0xABu, right.bus.trace[1].reply.data)
        assertEquals("ram", right.bus.trace[1].reply.responder)
        assertEquals(SocBusError.NO_RESPONDER, right.bus.trace[2].reply.error)
    }
}
