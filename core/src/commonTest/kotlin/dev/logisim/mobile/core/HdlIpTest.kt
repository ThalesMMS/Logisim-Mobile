package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlIpTest {
    private val source = """
        library ieee;
        use ieee.std_logic_1164.all;
        entity Example is
          generic (WIDTH : positive := 4; ACTIVE : boolean := true);
          port (a, b : in std_logic_vector(WIDTH - 1 downto 0); clock : in std_logic; y : out std_logic_vector(0 to WIDTH-1));
        end Example;
        architecture rtl of Example is begin y <= a and b; end rtl;
    """.trimIndent()

    private fun component(source: String = this.source, attributes: Map<String, String> = emptyMap()) = CircuitComponent(
        "hdl", HdlIpLibrary.VHDL, GridPoint(100, 200), attributes = mapOf("content" to source) + attributes)

    private fun evaluate(component: CircuitComponent, state: ComponentState = EmptyComponentState,
        events: List<ComponentEvent> = emptyList(), a: LogicVector = LogicVector.low(4), eventTime: Long = 0): ComponentEvaluation =
        HdlIpBehavior.evaluate(ComponentEvaluationContext(component, HdlIpLibrary.Schema.parse(component.attributes),
            inputs = mapOf("a" to a, "b" to LogicVector.low(4), "clock" to LogicVector.low(1)),
            connectedInputs = setOf("a", "b", "clock"), state = state, events = events, eventTime = eventTime))

    @Test fun readsGenericWidthsPreservesSourceAndMatchesDesktopGeometry() {
        val component = component(attributes = mapOf("generic.WIDTH" to "8", "label" to "preserved", "future" to "untouched"))
        val design = HdlIpLibrary.parse(component)
        assertTrue(design.isExecutable, design.diagnostics.toString())
        assertEquals(source, design.source)
        assertEquals("Example", design.topLevel)
        assertEquals(mapOf("WIDTH" to "8", "ACTIVE" to "true"), design.parameters)
        assertEquals(listOf("a", "b", "clock", "y"), design.ports.map { it.name })
        assertEquals(listOf(8, 8, 1, 8), design.ports.map { it.width })
        val layout = HdlIpLibrary.layout(component, design)
        assertEquals(GridPoint(100, 240), layout.ports[0].position)
        assertEquals(GridPoint(100, 260), layout.ports[2].position)
        assertEquals(GridPoint(240, 240), layout.ports[3].position)
        assertEquals(ComponentBounds(100, 200, 240, 270), layout.bounds)
        assertEquals("untouched", HdlIpLibrary.Schema.parse(component.attributes).raw["future"])
        assertTrue(HdlIpDeclarations.vhdl(source.replace("WIDTH - 1", "WIDTH\n - 1")).isExecutable)
        assertEquals("#HDL-IP", HdlIpLibrary.definitions.first().library)
        assertEquals("VHDL Entity", HdlIpLibrary.definitions.first().fileName)
        assertEquals("BLIFCircuit", HdlIpLibrary.definitions.last().fileName)
        val desktopOverride = HdlIpLibrary.parse(component(attributes = mapOf("vhdl_WIDTH" to "6")))
        assertTrue(desktopOverride.isExecutable, desktopOverride.diagnostics.toString())
        assertEquals(6, desktopOverride.ports.first().width)
        val conflict = HdlIpLibrary.parse(component(attributes = mapOf("vhdl_WIDTH" to "6", "generic.width" to "8")))
        assertFalse(conflict.isExecutable)
        assertTrue(conflict.diagnostics.any { it.code == "conflicting-generic" })
        assertTrue(HdlIpLibrary.parse(component(attributes = mapOf("vhdl_WIDTH" to "6", "generic.width" to "6"))).isExecutable)
    }

    @Test fun rejectsUnresolvedWidthsLinkageAndUnknownGenericInsteadOfInventingPorts() {
        val design = HdlIpDeclarations.vhdl("""
            entity chip is port (bidir : linkage std_logic; y : out std_logic_vector(UNKNOWN-1 downto 0)); end entity;
        """.trimIndent(), mapOf("MISSING" to "3"))
        assertFalse(design.isExecutable)
        assertTrue(design.diagnostics.any { it.code == "unsupported-width" })
        assertTrue(design.diagnostics.any { it.code == "unsupported-linkage" })
        assertTrue(design.diagnostics.any { it.code == "unknown-generic" })
        assertEquals(listOf("bidir"), design.ports.map { it.name })
    }

    @Test fun bidirectionalPortSurvivesRoundTripAndReadsExternalDriversWithoutEcho() {
        val source = """
            entity bidir is port (b : inout std_logic); end bidir;
        """.trimIndent()
        val hdl = component(source)
        val bus = GridPoint(100, 240)
        val external = CircuitComponent("external", ComponentKind.INPUT, GridPoint(0, 240))
        val probe = CircuitComponent("probe", ComponentKind.OUTPUT, GridPoint(200, 240))
        val original = CircuitDocument("main", listOf(hdl, external, probe),
            listOf(WireSegment(external.position, bus), WireSegment(bus, probe.position)))
        val project = CircuitProject(listOf(original))
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project)))
        val document = imported.project.mainCircuit
        val reloaded = document.components.first { it.kind == HdlIpLibrary.VHDL }
        val externalId = document.components.first { it.kind == ComponentKind.INPUT }.id
        val design = HdlIpLibrary.parse(reloaded)
        assertTrue(design.isExecutable)
        assertEquals(source, design.source)
        val terminal = HdlIpLibrary.layout(reloaded, design).ports.single()
        assertEquals(PortDirection.INOUT, terminal.direction)
        assertEquals(bus, terminal.position)
        val request = assertIs<CompanionJsonArray>(design.companionRequest(start = true).parameters["ports"])
        assertEquals(CompanionJsonString("inout"), assertIs<CompanionJsonObject>(request.values.single()).fields["direction"])
        val floating = LogicVector.unknown(1)
        val high = LogicVector.high(1)
        val low = LogicVector.low(1)
        val stimuli = mapOf(externalId to floating)
        val simulator = CircuitSimulator()
        val initial = simulator.settle(document, vectorInputs = stimuli)
        val sampleAt = initial.propagationTime + 1
        val inputs = mapOf("b" to floating)
        val transport = hdlStepRequest("0".repeat(32), inputs, 1, setOf("b"))
        assertEquals(CompanionJsonString("Z"), assertIs<CompanionJsonObject>(transport.parameters["inputs"]).fields["b"])
        val driven = simulator.settle(document, initial, vectorInputs = stimuli, events = mapOf(reloaded.id to listOf(
            CompanionHdlStarted("session", 1),
            CompanionHdlRequest("session", 1, 0, sampleAt, inputs),
            CompanionHdlReply("session", 1, 0, sampleAt, mapOf("b" to high)),
        )))
        assertEquals(high, driven.portVectors[probe.position])
        assertEquals(inputs, assertIs<HdlIpState>(driven.componentStates[reloaded.id]).sampleInputs)
        val readExternal = simulator.settle(document, driven, vectorInputs = mapOf(externalId to low))
        assertEquals(low, readExternal.portVectors[probe.position])
        assertEquals(mapOf("b" to low), assertIs<HdlIpState>(readExternal.componentStates[reloaded.id]).sampleInputs)

        val pull = CircuitComponent("weak", WiringLibrary.PullResistor, bus, attributes = mapOf("pull" to "1"))
        val weak = simulator.settle(document.copy(components = document.components + pull), vectorInputs = stimuli)
        assertEquals(high, weak.portVectors[probe.position])
        assertEquals(mapOf("b" to LogicVector.error(1)), assertIs<HdlIpState>(weak.componentStates[reloaded.id]).sampleInputs)
    }

    @Test fun genericArithmeticIsBoundedAndInvalidRangesStayUnavailable() {
        val design = HdlIpDeclarations.vhdl("""
            entity chip is generic (W : integer := (2+3)*2); port (i : in std_logic_vector(W/2-1 downto 0); o : out std_logic); end;
        """.trimIndent())
        assertTrue(design.isExecutable, design.diagnostics.toString())
        assertEquals(5, design.ports.first().width)
        for (range in listOf("W/0 downto 0", "1000000000*1000000000 downto 0", "0 downto 4", "64 downto 0")) {
            val invalid = HdlIpDeclarations.vhdl("entity chip is port (i : in std_logic_vector($range)); end;")
            assertFalse(invalid.isExecutable, range)
            assertTrue(invalid.diagnostics.any { it.code == "unsupported-width" }, range)
        }
        val unknownLiteral = HdlIpDeclarations.vhdl("""
            entity chip is generic (MASK : std_logic_vector(1 downto 0) := "--");
            port (i : in std_logic; o : out std_logic); end;
        """.trimIndent())
        assertTrue(unknownLiteral.isExecutable, unknownLiteral.diagnostics.toString())
        assertEquals("\"--\"", unknownLiteral.parameters["MASK"])
    }

    @Test fun numericGenericsWithoutValueAndTimeGenericsFollowEvolution() {
        val defaults = HdlIpDeclarations.vhdl("""
            entity chip is generic (W : positive; N : natural; MODE : integer; T : time);
            port (i : in std_logic_vector(W downto 0); o : out std_logic); end;
        """.trimIndent())
        assertTrue(defaults.isExecutable, defaults.diagnostics.toString())
        assertEquals(mapOf("W" to "1", "N" to "0", "MODE" to "0", "T" to "0"), defaults.parameters)
        assertEquals(2, defaults.ports.first().width)
        assertTrue(HdlIpDeclarations.vhdl("entity chip is generic (B : boolean); port (o : out std_logic); end;")
            .diagnostics.any { it.code == "missing-generic" })
        for ((literal, femtoseconds) in listOf("7" to "7", "3 fs" to "3", "2 ps" to "2000", "5 ns" to "5000000", "5ns" to "5000000",
                "1 us" to "1000000000", "3 us" to (3 * 1_000_000_000).toString())) {
            val design = HdlIpDeclarations.vhdl("entity d is generic (DELAY : time := $literal); port (a : in std_logic; y : out std_logic); end;")
            assertTrue(design.isExecutable, "$literal ${design.diagnostics}")
            assertEquals(femtoseconds, design.parameters["DELAY"], literal)
            assertEquals(setOf("DELAY"), design.timeParameters)
            assertEquals("$femtoseconds fs", design.vhdlGenericActuals["DELAY"])
        }
        assertTrue(HdlIpDeclarations.vhdl("entity d is generic (DELAY : time := 5 ms); port (y : out std_logic); end;")
            .diagnostics.any { it.code == "unsupported-generic" })
        val component = component("entity d is generic (DELAY : time := 5 ns); port (a : in std_logic; y : out std_logic); end;",
            mapOf("vhdl_DELAY" to "250"))
        val overridden = HdlIpLibrary.parse(component)
        assertEquals("250", overridden.parameters["DELAY"])
        val request = assertIs<CompanionJsonObject>(overridden.companionRequest(start = true).parameters["parameters"])
        assertEquals(CompanionJsonString("250 fs"), request.fields["DELAY"])
    }

    @Test fun blifUsesDesktopParserPortsAndErrors() {
        val source = """
            .model chip
            .inputs z bus[3] a bus[0] bus[1] both
            .outputs result[1] result[0] both
            .subckt AND A=a B=z Y=result[0]
            .gate BUS A=a B=z Y=result[1]
            .end
        """.trimIndent()
        val design = HdlIpDeclarations.blif(source)
        assertTrue(design.isExecutable, design.diagnostics.toString())
        // Scalars, then buses; a name in both lists is an output only.
        assertEquals(listOf("a", "z", "bus", "both", "result"), design.ports.map { it.name })
        assertEquals(listOf(1, 1, 4, 1, 2), design.ports.map { it.width })
        assertEquals(listOf(PortDirection.INPUT, PortDirection.INPUT, PortDirection.INPUT, PortDirection.OUTPUT,
            PortDirection.OUTPUT), design.ports.map { it.direction })
        assertEquals(source, design.source)
        // Evolution splits words on single spaces only, so a tab is part of the directive.
        assertEquals(emptyList(), HdlIpDeclarations.blif(".model\tchip\n.inputs\tx\n.end").ports)
        assertEquals("", HdlIpDeclarations.blif(".inputs x\n.outputs y\n.conn x y").topLevel)
        assertTrue(HdlIpDeclarations.blif(".inputs x\n.outputs y\n.conn x y").isExecutable)
        for ((invalid, detail) in listOf(
            ".model a\n.model b" to "many tops (try 'synth -flatten -top a')",
            ".names a b y\n11 1" to "odd .names (try 'write_blif -icells -conn out.blif')",
            ".subckt LUT A=a Y=y" to "unknown gate LUT\ntry basing your script on https://github.com/YosysHQ/yosys/tree/main/examples/cmos",
            ".inputs a[64]" to "width 65 must be at most 64",
            ".subckt BUF A=x Y=\$true" to "Cannot set driving gate for an externally driven cell!",
        )) {
            val rejected = HdlIpDeclarations.blif(invalid)
            assertEquals(listOf("parse-error"), rejected.diagnostics.map { it.code }, invalid)
            assertEquals(detail, rejected.diagnostics.single().message, invalid)
            assertFalse(rejected.isExecutable)
            // Evolution keeps its template when a source does not parse.
            assertEquals("lightswitch", rejected.topLevel)
            assertEquals(invalid, rejected.source)
        }
        assertEquals(1, HdlIpLibrary.layout(CircuitComponent("b", HdlIpLibrary.BLIF, GridPoint(0, 0)),
            HdlIpDeclarations.blif(".inputs x\n.outputs x\n.end")).ports.size)
    }

    @Test fun blifKeepsIndexedSingleBitPortsAfterProjectRoundTrip() {
        val source = """
            .model indexed
            .inputs a[0]
            .outputs y[0]
            .subckt NOT A=a[0] Y=y[0]
            .end
        """.trimIndent()
        val component = CircuitComponent("hdl", HdlIpLibrary.BLIF, GridPoint(100, 100),
            attributes = mapOf("content" to source))
        val project = CircuitProject(listOf(CircuitDocument("main", components = listOf(component))))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project)))
            .project.mainCircuit.components.single()
        val design = HdlIpLibrary.parse(reopened)
        assertTrue(design.isExecutable, design.diagnostics.toString())
        assertEquals(source, design.source)
        assertEquals(listOf("a[0]", "y[0]"), design.ports.map { it.name })
        assertEquals(listOf(1, 1), design.ports.map { it.width })
        val ambiguous = HdlIpDeclarations.blif(source.replace(".inputs a[0]", ".inputs a a[0] a[1]"))
        assertEquals(listOf("duplicate-port"), ambiguous.diagnostics.map { it.code })
    }

    @Test fun noKnownOutputExistsBeforeExplicitStartAndMatchingReply() {
        val component = component()
        val initial = evaluate(component)
        assertEquals("????", initial.outputs.getValue("y").display)
        assertEquals(HdlIpStatus.UNAVAILABLE, assertIs<HdlIpState>(initial.state).status)
        val started = evaluate(component, initial.state, listOf(CompanionHdlStarted("s", 1)))
        assertEquals("????", started.outputs.getValue("y").display)
        val inputs = mapOf("a" to LogicVector.low(4), "b" to LogicVector.low(4), "clock" to LogicVector.low(1))
        val pending = evaluate(component, started.state, listOf(CompanionHdlRequest("s", 1, 0, 1, inputs)))
        assertEquals(HdlIpStatus.PENDING, assertIs<HdlIpState>(pending.state).status)
        assertEquals("????", pending.outputs.getValue("y").display)
        val reply = evaluate(component, pending.state, listOf(CompanionHdlReply("s", 1, 0, 1, mapOf("y" to LogicVector.of(4, 10u)))))
        assertEquals("1010", reply.outputs.getValue("y").display)
        assertEquals(1L, assertIs<HdlIpState>(reply.state).timeNs)
        assertEquals(1L, reply.delay)
    }

    @Test fun staleSessionSequenceTimeAndChangedInputsCannotRestoreOldValues() {
        val component = component()
        val inputs = mapOf("a" to LogicVector.low(4), "b" to LogicVector.low(4), "clock" to LogicVector.low(1))
        val pending = evaluate(component, events = listOf(CompanionHdlStarted("s", 1), CompanionHdlRequest("s", 1, 2, 5, inputs)))
        for (reply in listOf(CompanionHdlReply("other", 1, 2, 5, mapOf("y" to LogicVector.high(4))),
            CompanionHdlReply("s", 2, 2, 5, mapOf("y" to LogicVector.high(4))),
            CompanionHdlReply("s", 1, 1, 5, mapOf("y" to LogicVector.high(4))),
            CompanionHdlReply("s", 1, 2, 4, mapOf("y" to LogicVector.high(4))))) {
            assertEquals("????", evaluate(component, pending.state, listOf(reply)).outputs.getValue("y").display)
        }
        val valid = CompanionHdlReply("s", 1, 2, 5, mapOf("y" to LogicVector.high(4)))
        assertEquals("????", evaluate(component, pending.state, listOf(valid), LogicVector.high(4)).outputs.getValue("y").display)
        val accepted = evaluate(component, pending.state, listOf(valid))
        assertEquals("1111", accepted.outputs.getValue("y").display)
        assertEquals(5L, accepted.delay)
        assertEquals(0L, evaluate(component, accepted.state, eventTime = 5).delay)
        assertEquals("????", evaluate(component, accepted.state, a = LogicVector.high(4)).outputs.getValue("y").display)
    }

    @Test fun failuresAndMalformedRepliesProduceErrorsAndRequireFreshRequest() {
        val component = component()
        val inputs = mapOf("a" to LogicVector.low(4), "b" to LogicVector.low(4), "clock" to LogicVector.low(1))
        val pending = evaluate(component, events = listOf(CompanionHdlStarted("s", 1), CompanionHdlRequest("s", 1, 0, 1, inputs)))
        val failed = evaluate(component, pending.state, listOf(CompanionHdlFailed("s", 1, 0, "compiler failure")))
        assertEquals("EEEE", failed.outputs.getValue("y").display)
        assertTrue(failed.diagnostics.contains("compiler failure"))
        val late = evaluate(component, failed.state, listOf(CompanionHdlReply("s", 1, 0, 1, mapOf("y" to LogicVector.low(4)))))
        assertEquals("EEEE", late.outputs.getValue("y").display)
        val malformed = evaluate(component, pending.state, listOf(CompanionHdlReply("s", 1, 0, 1, mapOf("y" to LogicVector.low(1)))))
        assertEquals("EEEE", malformed.outputs.getValue("y").display)
        val unavailable = evaluate(component, events = listOf(CompanionHdlUnavailable(1, "NVC is unavailable")))
        assertEquals("EEEE", unavailable.outputs.getValue("y").display)
        assertTrue(unavailable.diagnostics.contains("NVC is unavailable"))
        val oldFailure = evaluate(component, pending.state, listOf(CompanionHdlUnavailable(1, "old failure")))
        assertEquals("????", oldFailure.outputs.getValue("y").display)
    }

    @Test fun transientInputChangesInvalidateAPendingSampleEvenIfInputsReturn() {
        val component = component()
        val inputs = mapOf("a" to LogicVector.low(4), "b" to LogicVector.low(4), "clock" to LogicVector.low(1))
        val request = CompanionHdlRequest("s", 1, 0, 5, inputs)
        val pending = evaluate(component, events = listOf(CompanionHdlStarted("s", 1), request))
        val changed = evaluate(component, pending.state, a = LogicVector.high(4))
        assertEquals(null, assertIs<HdlIpState>(changed.state).requestedTimeNs)
        val returned = evaluate(component, changed.state)
        val stale = evaluate(component, returned.state, listOf(request,
            CompanionHdlReply("s", 1, 0, 5, mapOf("y" to LogicVector.high(4)))))
        assertEquals("????", stale.outputs.getValue("y").display)
        assertEquals(HdlIpStatus.PENDING, assertIs<HdlIpState>(stale.state).status)
    }

    @Test fun resetEditsAndIndependentInstancesInvalidateSessions() {
        val component = component()
        val first = evaluate(component, events = listOf(CompanionHdlStarted("s", 1)))
        val second = evaluate(component)
        assertEquals(null, assertIs<HdlIpState>(second.state).sessionId)
        val reset = HdlIpBehavior.resetState(component, HdlIpLibrary.Schema.parse(component.attributes), first.state)
        assertEquals(null, assertIs<HdlIpState>(reset).sessionId)
        assertEquals(2L, reset.epoch)
        val stale = evaluate(component, reset, listOf(CompanionHdlStarted("s", 1)))
        assertEquals(null, assertIs<HdlIpState>(stale.state).sessionId)
        val restarted = evaluate(component, reset, listOf(CompanionHdlStarted("new", 3)))
        assertEquals("new", assertIs<HdlIpState>(restarted.state).sessionId)
        val edited = evaluate(component(source.replace("a and b", "a or b")), first.state)
        assertEquals(null, assertIs<HdlIpState>(edited.state).sessionId)
        assertEquals("????", edited.outputs.getValue("y").display)
        val aliasEdited = evaluate(component(attributes = mapOf("vhdl_WIDTH" to "4")), first.state)
        assertEquals(null, assertIs<HdlIpState>(aliasEdited.state).sessionId)
    }
}
