package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

class TclComponentsTest {
    private val digest = ExtensionDigest.sha256("puts approved".encodeToByteArray())
    private val source = "entity demo is port(sysclk_i : in std_logic; val_i : in std_logic_vector(3 downto 0); cpt_o : out std_logic_vector(3 downto 0)); end demo;"
    private val component = CircuitComponent("tcl", TclComponents.GENERIC, GridPoint(100, 200), attributes = mapOf("content" to source, "filePath" to "keep/script.tcl"))
    private val definition = TclComponents.definitions().single { it.kind == TclComponents.GENERIC }
    private val behavior = requireNotNull(definition.behavior)
    private fun context(state: ComponentState = EmptyComponentState, clock: LogicVector = LogicVector.low(1), value: LogicVector = LogicVector.of(4, 3uL), events: List<ComponentEvent> = emptyList()) =
        ComponentEvaluationContext(component, definition.schema.parse(component.attributes), mapOf("sysclk_i" to clock, "val_i" to value), setOf("sysclk_i", "val_i"), state, events = events)

    @Test
    fun redsPinningMatchesAllFortyTwoDesktopTerminals() {
        val reds = CircuitComponent("reds", TclComponents.CONSOLE_REDS, GridPoint(100, 200))
        val layout = TclInterfaces.reds.layout(reds)
        assertEquals(42, layout.ports.size)
        assertEquals(23, TclInterfaces.reds.inputs.size)
        assertEquals(19, TclInterfaces.reds.outputs.size)
        assertEquals(ComponentBounds(100, 200, 240, 470), layout.bounds)
        assertEquals(ComponentTerminal("Hex0_obs", GridPoint(100, 240), PortDirection.INPUT, 4, "Hex0_obs"), layout.ports.first())
        assertEquals(ComponentTerminal("sysclk_i", GridPoint(100, 450), PortDirection.INPUT, 1, "sysclk_i"), layout.ports[21])
        assertEquals(ComponentTerminal("rst_o", GridPoint(240, 420), PortDirection.OUTPUT, 1, "rst_o"), layout.ports.last())
    }

    @Test
    fun genericParsesLiteralVhdlPortsWithoutChangingClockNames() {
        val face = TclInterfaces.parseGeneric("""
            library ieee; use ieee.std_logic_1164.all;
            entity Demo is port(
                sysclk_i, reset_i : in std_logic;
                value_i : in std_logic_vector(0 to 7);
                result_o : out STD_LOGIC_VECTOR(15 downto 0)
            ); end Demo;
        """.trimIndent())
        assertEquals(listOf("sysclk_i", "reset_i", "value_i", "result_o"), face.ports.map { it.name })
        assertEquals(listOf(1, 1, 8, 16), face.ports.map { it.width })
        assertTrue(face.hasDesktopClock)
        val desktopDefault = TclInterfaces.parseGeneric(TclInterfaces.DEFAULT_GENERIC)
        assertEquals("horloge_i", desktopDefault.ports.first().name)
        assertFalse(desktopDefault.hasDesktopClock)
    }

    @Test
    fun genericRejectsUnresolvedWidthsDuplicatesAndUnsupportedTypes() {
        for (ports in listOf("a : in integer", "a : in std_logic_vector(WIDTH-1 downto 0)",
            "a : in std_logic_vector(0 downto 3)", "a : in std_logic_vector(64 downto 0)",
            "a : in std_logic; A : out std_logic")) {
            assertFailsWith<IllegalArgumentException> { TclInterfaces.parseGeneric("entity x is port($ports); end x;") }
        }
        assertFailsWith<IllegalArgumentException> { TclInterfaces.parseGeneric("entity x is generic(N:integer:=4); port(a:in std_logic);end x;") }
    }

    @Test
    fun importingAndSettlingNeverStartsTclOrReadsItsFileReference() {
        val result = behavior.evaluate(context())
        val state = assertIs<TclRuntimeState>(result.state)
        assertEquals(TclRuntimePhase.STOPPED, state.phase)
        assertTrue(state.frames.isEmpty())
        assertTrue(result.outputs.getValue("cpt_o").isUnknown)
        assertTrue(result.diagnostics.single().contains("explicitly"))
        assertEquals("keep/script.tcl", component.attributes["filePath"])
    }

    @Test
    fun realCircAttributesRoundTripWithStoppedComponentState() {
        val xml = """<project source="3.8.0" version="1.0"><lib name="9" desc="#TCL"/><main name="main"/><circuit name="main">
            <comp name="TclGeneric" lib="9" loc="(100,200)"><a name="filePath" val="keep/script.tcl"/><a name="content" val="$source"/><a name="label" val="Preserved Tcl"/></comp>
            </circuit></project>""".trimIndent()
        val registry = ComponentCatalog.registry.withDefinitions(TclComponents.definitions())
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        val imported = project.mainCircuit.components.single()
        assertEquals(TclComponents.GENERIC, imported.kind)
        assertEquals(source, imported.attributes["content"])
        assertEquals("keep/script.tcl", imported.attributes["filePath"])
        val serialized = CircCodec.writeProject(project, registry)
        val roundTrip = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(serialized, registry)).project.mainCircuit.components.single()
        assertEquals(imported.attributes, roundTrip.attributes)
        val simulator = CircuitSimulator(registry = registry)
        val settled = simulator.settle(project)
        assertEquals(TclRuntimePhase.STOPPED, (settled.componentStates.getValue(imported.id) as TclRuntimeState).phase)
    }

    @Test
    fun incompatibleGenericSourceIsPreservedAndDiagnosedWithoutBreakingImportGeometry() {
        val invalid = component.copy(attributes = component.attributes + ("content" to "entity bad is port(value_i: in integer); end bad;"))
        val layout = definition.layout(invalid, definition.schema.parse(invalid.attributes))
        assertTrue(layout.ports.isEmpty())
        val result = behavior.evaluate(context().copy(component = invalid))
        assertEquals(TclRuntimePhase.FAILED, (result.state as TclRuntimeState).phase)
        assertTrue(result.diagnostics.single().contains("Unsupported Tcl"))
        assertEquals(Messages.TclRuntimeInterface, result.notices.single().key)
        assertEquals("keep/script.tcl", invalid.attributes["filePath"])
        assertTrue(invalid.attributes.getValue("content").contains("integer"))
    }

    @Test
    fun explicitEnableQueuesFramesAndResponseAppliesWithDesktopDelay() {
        val queued = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest))))
        val state = assertIs<TclRuntimeState>(queued.state)
        assertEquals(TclRuntimePhase.WAITING, state.phase)
        assertEquals(1, state.frames.size)
        val applied = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Response(
            state.generation, digest, mapOf("cpt_o" to LogicVector.of(4, 9uL)), setOf(0)))))
        assertEquals(9uL, applied.outputs.getValue("cpt_o").toULongOrNull())
        assertEquals(1L, applied.delay)
        assertTrue(assertIs<TclRuntimeState>(applied.state).requestedTick)
        val acknowledged = behavior.evaluate(context(applied.state, events = listOf(TclComponentEvent.TickRequestAcknowledged)))
        assertFalse(assertIs<TclRuntimeState>(acknowledged.state).requestedTick)
    }

    @Test
    fun clockEdgesAndInputChangesQueueOnceAndStaleResponsesAreIgnored() {
        var state = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        val firstGeneration = state.generation
        state = behavior.evaluate(context(state, clock = LogicVector.high(1))).state as TclRuntimeState
        assertEquals(2, state.frames.size)
        val generation = state.generation
        state = behavior.evaluate(context(state, clock = LogicVector.high(1), events = listOf(
            TclComponentEvent.Response(firstGeneration, digest, mapOf("cpt_o" to LogicVector.high(4)))))).state as TclRuntimeState
        assertTrue(state.outputs.getValue("cpt_o").isUnknown)
        assertEquals(generation, state.generation)
        assertEquals(2, state.frames.size)
        state = behavior.evaluate(context(state, clock = LogicVector.low(1))).state as TclRuntimeState
        assertEquals(3, state.frames.size)
    }

    @Test
    fun completedGenerationCannotBeReplayedToRequestADuplicateTickOrPublishFailure() {
        val pending = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        val response = TclComponentEvent.Response(pending.generation, digest, mapOf("cpt_o" to LogicVector.of(4, 9uL)), setOf(0))
        val ready = behavior.evaluate(context(pending, events = listOf(response))).state
        val acknowledged = behavior.evaluate(context(ready, events = listOf(TclComponentEvent.TickRequestAcknowledged))).state
        val duplicate = behavior.evaluate(context(acknowledged, events = listOf(response,
            TclComponentEvent.Failure(pending.generation, "Late duplicate failure"))))
        val state = duplicate.state as TclRuntimeState
        assertEquals(TclRuntimePhase.READY, state.phase)
        assertFalse(state.requestedTick)
        assertEquals(9uL, state.outputs.getValue("cpt_o").toULongOrNull())
    }

    @Test
    fun intermediateTickRequestsAreQueuedOnceAcrossFullHistoryReplays() {
        var state = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        state = behavior.evaluate(context(state, clock = LogicVector.high(1))).state as TclRuntimeState
        state = behavior.evaluate(context(state, clock = LogicVector.low(1))).state as TclRuntimeState
        state = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Response(state.generation, digest,
            mapOf("cpt_o" to LogicVector.of(4, 3uL)), setOf(1))))).state as TclRuntimeState
        assertEquals(1, state.pendingTickRequests)
        assertEquals(3, state.completedFrameCount)
        state = behavior.evaluate(context(state, events = listOf(TclComponentEvent.TickRequestAcknowledged))).state as TclRuntimeState
        state = behavior.evaluate(context(state, clock = LogicVector.high(1))).state as TclRuntimeState
        state = behavior.evaluate(context(state, clock = LogicVector.high(1), events = listOf(TclComponentEvent.Response(state.generation, digest,
            mapOf("cpt_o" to LogicVector.of(4, 4uL)), setOf(1, 3))))).state as TclRuntimeState
        assertEquals(1, state.pendingTickRequests)
        assertEquals(4, state.completedFrameCount)
        state = behavior.evaluate(context(state, clock = LogicVector.low(1))).state as TclRuntimeState
        state = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Response(state.generation, digest,
            mapOf("cpt_o" to LogicVector.of(4, 5uL)), setOf(1, 3, 4))))).state as TclRuntimeState
        assertEquals(2, state.pendingTickRequests)
        state = behavior.evaluate(context(state, events = listOf(TclComponentEvent.TickRequestAcknowledged))).state as TclRuntimeState
        assertEquals(1, state.pendingTickRequests)
        state = behavior.evaluate(context(state, clock = LogicVector.high(1))).state as TclRuntimeState
        state = behavior.evaluate(context(state, clock = LogicVector.high(1), events = listOf(
            TclComponentEvent.Failure(state.generation, "Host disconnected")))).state as TclRuntimeState
        assertEquals(0, state.pendingTickRequests)
    }

    @Test
    fun automaticBudgetLeavesAdditionalRunRequestsAvailableForManualApplication() {
        var state = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest, "approved")))).state as TclRuntimeState
        repeat(16) { index ->
            state = behavior.evaluate(context(state, clock = LogicVector.of(1, (index + 1).toULong()))).state as TclRuntimeState
        }
        val clock = LogicVector.low(1)
        state = behavior.evaluate(context(state, clock = clock, events = listOf(TclComponentEvent.Response(
            state.generation, digest, mapOf("cpt_o" to LogicVector.of(4, 3uL)), (0..16).toSet(),
        )))).state as TclRuntimeState
        assertEquals(17, state.pendingTickRequests)
        repeat(16) {
            val request = state.tickRequest("approved")
            state = behavior.evaluate(context(state, clock = clock, events = listOf(request))).state as TclRuntimeState
        }
        assertEquals(1, state.pendingTickRequests)
        assertEquals(0, state.automaticTicks?.remaining)
        val excess = state.tickRequest("approved")
        assertFalse(excess.matches(state))
        assertEquals(state, behavior.evaluate(context(state, clock = clock, events = listOf(excess))).state)
        val manual = behavior.evaluate(context(state, clock = clock, events = listOf(excess.copy(authorization = null)))).state as TclRuntimeState
        assertEquals(0, manual.pendingTickRequests)
    }

    @Test
    fun anAppliedRunCannotConsumeANewerRequestWithTheSamePendingCount() {
        val initial = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest, "approved")))).state as TclRuntimeState
        val pending = behavior.evaluate(context(initial, events = listOf(TclComponentEvent.Response(initial.generation,
            digest, mapOf("cpt_o" to LogicVector.of(4, 1uL)), setOf(0))))).state as TclRuntimeState
        val first = pending.tickRequest("approved")
        val consumed = behavior.evaluate(context(pending, events = listOf(first))).state as TclRuntimeState
        assertEquals(0, consumed.pendingTickRequests)
        val newer = consumed.copy(pendingTickRequests = 1)
        assertEquals(newer, behavior.evaluate(context(newer, events = listOf(first))).state)
        val applied = behavior.evaluate(context(newer, events = listOf(newer.tickRequest("approved")))).state as TclRuntimeState
        assertEquals(0, applied.pendingTickRequests)
        assertEquals(2, applied.consumedTickRequests)
    }

    @Test
    fun invalidOrFailedRepliesStopWithUnknownOutputsAndVisibleDiagnostics() {
        val state = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        val invalid = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Response(
            state.generation, digest, mapOf("cpt_o" to LogicVector.high(1))))))
        assertEquals(TclRuntimePhase.FAILED, (invalid.state as TclRuntimeState).phase)
        assertTrue(invalid.outputs.getValue("cpt_o").isUnknown)
        val failed = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Failure(state.generation, "Host offline"))))
        assertEquals(listOf("Host offline"), failed.diagnostics)
        val stopped = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Disable,
            TclComponentEvent.Response(state.generation, digest, mapOf("cpt_o" to LogicVector.high(4))))))
        assertEquals(TclRuntimePhase.STOPPED, (stopped.state as TclRuntimeState).phase)
        assertTrue(stopped.outputs.getValue("cpt_o").isUnknown)
    }

    @Test
    fun desktopDefaultClockMismatchIsExplicitAndHistoryIsBounded() {
        val default = component.copy(attributes = emptyMap())
        val badClock = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest))).copy(component = default))
        assertEquals(TclRuntimePhase.FAILED, (badClock.state as TclRuntimeState).phase)
        assertTrue(badClock.diagnostics.single().contains("horloge_i"))
        var state = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        fun nextFrame(state: TclRuntimeState, index: Int) = behavior.evaluate(context(state,
            clock = LogicVector.of(1, (index + 1).toULong()), value = LogicVector.of(4, index.toULong()))).state as TclRuntimeState
        repeat(257) { index -> state = nextFrame(state, index) }
        assertEquals(TclRuntimePhase.WAITING, state.phase)
        assertEquals(258, state.frames.size)
        val face = TclInterfaces.parseGeneric(source)
        assertEquals(1024, face.replayFrameLimit)
        assertEquals(258, TclStepRequest("long", TclSourceFile("script.tcl", "expr 1"), face, state.frames).frames.size)

        val reset = behavior.evaluate(context(state, events = listOf(TclComponentEvent.Enable(digest),
            TclComponentEvent.Response(state.generation, digest, mapOf("cpt_o" to LogicVector.high(4)))))).state as TclRuntimeState
        assertEquals(1, reset.frames.size)
        assertEquals(0, reset.completedFrameCount)
        assertTrue(reset.outputs.getValue("cpt_o").isUnknown)

        for (index in 257 until face.replayFrameLimit) state = nextFrame(state, index)
        assertEquals(TclRuntimePhase.HISTORY_LIMIT, state.phase)
        assertEquals(face.replayFrameLimit, state.frames.size)

        val largeInterface = TclInterface(listOf(TclSignalPort("sysclk_i", PortDirection.INPUT, 1)) +
            List(511) { TclSignalPort("bus_${it}_" + "a".repeat(100), PortDirection.INOUT, 64) })
        assertEquals(256, largeInterface.replayFrameLimit)
    }

    @Test
    fun sourceRequestsValidateNamesSizesAndFrameInterfaces() {
        assertFailsWith<IllegalArgumentException> { TclSourceFile("../script.tcl", "expr 1") }
        assertFailsWith<IllegalArgumentException> { TclSourceFile("script.tcl", "x".repeat(256 * 1024 + 1)) }
        val script = TclSourceFile("script.tcl", "expr 1")
        assertFailsWith<IllegalArgumentException> { TclConsoleRequest("id", script, listOf(script)) }
        val face = TclInterfaces.parseGeneric(source)
        assertFailsWith<IllegalArgumentException> { TclStepRequest("id", script, face, listOf(TclInputFrame(emptyMap()))) }
        val request = TclStepRequest("id", script, face, listOf(TclInputFrame(mapOf("sysclk_i" to LogicVector.unknown(1), "val_i" to LogicVector.of(4, 7uL)))))
        assertEquals(listOf(mapOf("sysclk_i" to "U", "val_i" to "0111")), request.wireParameters()["frames"])
    }

    @Test
    fun cancellationGenerationCannotPublishLateConsoleResults() {
        val state = TclSubmissionState()
        val first = state.begin("request1")
        assertEquals("request1", state.cancel())
        assertFalse(state.complete("request1", first))
        val second = state.begin("request2")
        assertFalse(state.accepts("request1", first))
        assertTrue(state.complete("request2", second))
        assertEquals(null, state.activeRequestId)
    }

    @Test
    fun runtimeNoticesKeepCanonicalIdentityWhileNativeFailureDetailsRemainLiteral() {
        val evaluation = behavior.evaluate(context())
        val stopped = evaluation.state as TclRuntimeState
        assertEquals(Messages.TclRuntimeStopped, evaluation.notices.single().key)
        assertEquals(Messages.TclRuntimeStopped, stopped.notice?.key)
        assertTrue(stopped.notice!!.resolve(AppLanguage.PORTUGUESE).contains("parado"))
        val pending = behavior.evaluate(context(events = listOf(TclComponentEvent.Enable(digest)))).state as TclRuntimeState
        val failed = behavior.evaluate(context(pending, events = listOf(TclComponentEvent.Failure(
            pending.generation, "native callback detail", message(Messages.TclErrorExecution))))).state as TclRuntimeState
        assertEquals("native callback detail", failed.message)
        assertEquals(Messages.TclErrorExecution, failed.notice?.key)
        assertEquals("Falha na execução Tcl.", failed.notice!!.resolve(AppLanguage.PORTUGUESE))
        assertEquals("Tcl execution failed.", failed.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Componente Tcl genérico", message(Messages.ComponentTclGeneric).resolve(AppLanguage.PORTUGUESE))
        assertEquals("Tcl console (Reds)", message(Messages.ComponentTclConsoleReds).resolve(AppLanguage.ENGLISH))
    }
}
