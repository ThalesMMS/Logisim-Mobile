package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExternalIoRuntimeEventsTest {
    private val definition = ExternalIoComponents.definitions().single { it.kind == ExternalIoComponents.Telnet }
    private val component = CircuitComponent("terminal", ExternalIoComponents.Telnet, GridPoint(0, 0))
    private fun evaluate(state: TelnetState, vararg events: ComponentEvent): TelnetState {
        val attributes = definition.schema.parse(component.attributes)
        val result = definition.behavior!!.evaluate(ComponentEvaluationContext(component, attributes, emptyMap(), emptySet(),
            state, events = events.toList()))
        return assertIs<TelnetState>(result.state)
    }

    @Test
    fun networkRoundAppliesBytesLinkAndOutputAcknowledgementAtomically() {
        val previous = TelnetState(pendingOutput = listOf(TelnetPacket(1, listOf(65)), TelnetPacket(2, listOf(66))))
        val result = evaluate(previous, TelnetRuntimeEvents(7, listOf(TelnetLinkChanged(TelnetLinkState.Connected),
            TelnetBytesReceived(listOf(0, 127, 128, 255)), TelnetOutputAccepted(1))))
        assertEquals(TelnetLinkState.Connected, result.link)
        assertEquals(listOf(0, 127, 128, 255), result.received)
        assertEquals(listOf(TelnetPacket(2, listOf(66))), result.pendingOutput)
        assertEquals(7L, result.runtimeSequence)
    }

    @Test
    fun retryOfAlreadyAppliedRoundDoesNotDuplicateInput() {
        val round = TelnetRuntimeEvents(1, listOf(TelnetBytesReceived(listOf(65))))
        val received = evaluate(TelnetState(), round)
        assertEquals(listOf(65), evaluate(received, round).received)
        assertEquals(listOf(65), evaluate(received, TelnetRuntimeEvents(1, listOf(TelnetBytesReceived(listOf(66))))).received)
    }

    @Test
    fun orderedRoundsKeepEveryByteAndRejectOldRounds() {
        val result = evaluate(TelnetState(),
            TelnetRuntimeEvents(4, listOf(TelnetBytesReceived(listOf(1)))),
            TelnetRuntimeEvents(5, listOf(TelnetBytesReceived(listOf(2)))),
            TelnetRuntimeEvents(3, listOf(TelnetBytesReceived(listOf(99)))))
        assertEquals(listOf(1, 2), result.received)
        assertEquals(5L, result.runtimeSequence)
    }

    @Test
    fun batchAcceptsOnlyNetworkEventsAndPositiveSequences() {
        assertFailsWith<IllegalArgumentException> { TelnetRuntimeEvents(0, emptyList()) }
        assertFailsWith<IllegalArgumentException> { TelnetRuntimeEvents(1, listOf(PortIoTogglePin(0))) }
    }

    @Test
    fun videoCursorUsesWallClockPhaseAndSchedulesOnlyWhenEnabled() {
        var epochMillis = 2_250L
        val video = ExternalIoComponents.definitions(EpochClock { epochMillis })
            .single { it.kind == ExternalIoComponents.RgbVideo }
        val component = CircuitComponent("video", ExternalIoComponents.RgbVideo, GridPoint(0, 0))
        val inputs = mapOf("clock" to LogicVector.of(1, 0u), "write" to LogicVector.of(1, 0u),
            "reset" to LogicVector.of(1, 0u), "x" to LogicVector.of(1, 1u),
            "y" to LogicVector.of(1, 1u), "data" to LogicVector.of(4, 9u))
        fun sample(elapsed: Long, automatic: Boolean, cursor: String = "Blinking Dot"): ComponentEvaluation {
            val attributes = video.schema.parse(mapOf("width" to "2", "height" to "2", "cursor" to cursor,
                "color" to VideoColorFormat.XTERM16.fileValue))
            val result = video.behavior!!.evaluate(ComponentEvaluationContext(component, attributes, inputs,
                inputs.keys, elapsedTimeMillis = elapsed, autoPropagating = automatic))
            val state = assertIs<VideoState>(result.state)
            assertEquals(1, state.cursorX)
            assertEquals(1, state.cursorY)
            assertEquals(9, state.cursorColor)
            assertEquals(VideoFrame.BLACK, state.frame.argbAt(1, 1))
            return result
        }
        val on = sample(250, true)
        assertEquals(true, assertIs<VideoState>(on.state).cursorVisible)
        assertEquals(1_000L, on.nextWakeupMillis)
        epochMillis = 2_750L
        val stillOn = sample(750, true)
        assertEquals(true, assertIs<VideoState>(stillOn.state).cursorVisible)
        assertEquals(1_000L, stillOn.nextWakeupMillis)
        epochMillis = 3_250L
        val off = sample(1_250, true)
        assertEquals(false, assertIs<VideoState>(off.state).cursorVisible)
        assertEquals(2_000L, off.nextWakeupMillis)
        epochMillis = 3_750L
        val stillOff = sample(1_750, false)
        assertEquals(false, assertIs<VideoState>(stillOff.state).cursorVisible)
        assertEquals(null, stillOff.nextWakeupMillis)
        epochMillis = 4_000L
        val disabled = sample(2_000, true, "No Cursor")
        assertEquals(false, assertIs<VideoState>(disabled.state).cursorVisible)
        assertEquals(null, disabled.nextWakeupMillis)
    }

}
