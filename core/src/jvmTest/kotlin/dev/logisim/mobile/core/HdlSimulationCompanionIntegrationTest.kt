package dev.logisim.mobile.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HdlSimulationCompanionIntegrationTest {
    private val registry = ComponentCatalog.registry

    @Test fun defaultVhdlModelExecutesWithoutInventingDrivenOutputs() = runBlocking {
        HdlCompanionTestHost.start("nvc").use { host ->
            val component = CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(120, 120))
            val design = HdlIpLibrary.parse(component)
            val started = host.execute(design.companionRequest(start = true)).hdlSample(emptyMap())
            assertEquals("ac26d7b45b555ad6aba99e439e769431b9bccdf65e48fa3c561e21752902ac26", started.sourceSha256)
            val inputs = mapOf("horloge_i" to LogicVector.high(1), "val_i" to LogicVector.of(4, 10u))
            val sample = host.execute(hdlStepRequest(started.sessionId, inputs, 5))
                .hdlSample(mapOf("max_o" to 1, "cpt_o" to 4), started.sessionId)
            assertEquals("?", sample.outputs.getValue("max_o").display)
            assertEquals("????", sample.outputs.getValue("cpt_o").display)
        }
    }

    private fun circuit(source: String) = CircuitDocument("main", components = listOf(
        CircuitComponent("a-drive", ComponentKind.INPUT, GridPoint(100, 140)),
        CircuitComponent("b-drive", ComponentKind.INPUT, GridPoint(100, 150)),
        CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(100, 100), attributes = mapOf("content" to source)),
        CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(240, 140)),
    ))

    @Test fun authenticatedHttpNvcSampleDrivesMotorAtSampleTimeAndResetClearsIt() = runBlocking {
        HdlCompanionTestHost.start("nvc").use { host ->
            val source = host.root.resolve("fixtures/hdl/cosim/and_gate.vhd").readText()
            val document = circuit(source)
            val design = HdlIpLibrary.parse(document.components.single { it.id == "hdl" })
            assertTrue(design.isExecutable, design.diagnostics.toString())
            val started = host.execute(design.companionRequest(start = true)).hdlSample(emptyMap())
            assertEquals(0L, started.timeNs)
            assertEquals(companionSha256(source.encodeToByteArray()), started.sourceSha256)
            val inputs = mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1))
            val sample = host.execute(hdlStepRequest(started.sessionId, inputs, 5))
                .hdlSample(mapOf("y" to 1), started.sessionId)
            assertEquals(started.sourceSha256, sample.sourceSha256)
            assertEquals(5L, sample.timeNs)
            assertEquals("1", sample.outputs.getValue("y").display)
            val simulator = CircuitSimulator(registry = registry)
            val drive = mapOf("a-drive" to LogicValue.HIGH, "b-drive" to LogicValue.HIGH)
            val initial = simulator.settle(document, inputs = drive)
            assertEquals(LogicValue.UNKNOWN, initial.componentValues["sink"])
            val ready = simulator.settle(document, initial, inputs = drive, events = mapOf("hdl" to listOf(
                CompanionHdlStarted(started.sessionId, 1),
                CompanionHdlRequest(started.sessionId, 1, 0, sample.timeNs, inputs),
                CompanionHdlReply(started.sessionId, 1, 0, sample.timeNs, sample.outputs),
            )))
            assertEquals(sample.timeNs, ready.propagationTime)
            assertEquals(LogicValue.HIGH, ready.componentValues["sink"])
            val reset = host.execute(hdlSessionRequest(started.sessionId, reset = true))
                .hdlSample(emptyMap(), started.sessionId)
            assertEquals(0L, reset.timeNs)
            assertTrue(reset.outputs.isEmpty())
            val cleared = simulator.settle(document, ready, inputs = drive, reset = true)
            assertEquals(LogicValue.UNKNOWN, cleared.componentValues["sink"])
            assertEquals(null, assertIs<HdlIpState>(cleared.componentStates["hdl"]).sessionId)
            val cancelled = host.execute(hdlSessionRequest(started.sessionId, reset = false))
            assertTrue(assertIs<CompanionJsonObject>(cancelled.result).boolean("ok"))
        }
    }

    @Test fun authenticatedVhdlInoutSampleDrivesAndReleasesTheMobileBus() = runBlocking {
        HdlCompanionTestHost.start("nvc").use { host ->
            val source = """
                library ieee; use ieee.std_logic_1164.all;
                entity bidir is port(enable: in std_logic; b: inout std_logic; seen: out std_logic); end;
                architecture rtl of bidir is
                begin b <= '1' when enable = '1' else 'Z'; seen <= b; end;
            """.trimIndent()
            val hdl = CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(100, 100), attributes = mapOf("content" to source))
            val document = CircuitDocument("main", listOf(hdl,
                CircuitComponent("enable", ComponentKind.INPUT, GridPoint(100, 140)),
                CircuitComponent("external", ComponentKind.INPUT, GridPoint(100, 150)),
                CircuitComponent("bus", ComponentKind.OUTPUT, GridPoint(100, 150)),
                CircuitComponent("seen", ComponentKind.OUTPUT, GridPoint(240, 140)),
            ))
            val design = HdlIpLibrary.parse(hdl)
            val started = host.execute(design.companionRequest(start = true)).hdlSample(emptyMap())
            val simulator = CircuitSimulator(registry = registry)
            val session = started.sessionId
            var state = simulator.settle(document, events = mapOf(hdl.id to listOf(CompanionHdlStarted(session, 1))))
            var remoteTime = started.timeNs
            val floating = LogicValue.UNKNOWN
            val low = LogicValue.LOW
            val high = LogicValue.HIGH
            val scenarios = listOf(Triple(high, floating, high), Triple(low, floating, floating),
                Triple(low, low, low), Triple(high, low, LogicValue.ERROR))
            for ((sequence, scenario) in scenarios.withIndex()) {
                val (enable, external, expected) = scenario
                val drives = mapOf("enable" to enable, "external" to external)
                state = simulator.settle(document, state, inputs = drives)
                val inputs = assertIs<HdlIpState>(state.componentStates[hdl.id]).sampleInputs
                assertEquals(external, inputs.getValue("b").bit(0))
                val time = maxOf(state.propagationTime, remoteTime) + 1
                val sample = host.execute(hdlStepRequest(session, inputs, time - remoteTime, setOf("b")))
                    .hdlSample(mapOf("b" to 1, "seen" to 1), session)
                assertEquals(time, sample.timeNs)
                remoteTime = sample.timeNs
                state = simulator.settle(document, state, inputs = drives, events = mapOf(hdl.id to listOf(
                    CompanionHdlRequest(session, 1, sequence.toLong(), time, inputs),
                    CompanionHdlReply(session, 1, sequence.toLong(), time, sample.outputs),
                )))
                assertEquals(expected, state.componentValues["bus"])
                assertEquals(expected, state.componentValues["seen"])
            }
        }
    }

    @Test fun realCompilerFailureCannotProduceAKnownMotorOutput() = runBlocking {
        HdlCompanionTestHost.start("nvc").use { host ->
            val source = host.root.resolve("fixtures/hdl/cosim/and_gate.vhd").readText().replace("y <= a and b;", "y <= ;")
            val document = circuit(source)
            val design = HdlIpLibrary.parse(document.components.single { it.id == "hdl" })
            assertTrue(design.isExecutable, design.diagnostics.toString())
            val job = host.execute(design.companionRequest(start = true))
            assertEquals(CompanionJobState.SUCCEEDED, job.state)
            val envelope = assertIs<CompanionJsonObject>(job.result)
            assertFalse(envelope.boolean("ok"))
            assertTrue(envelope.obj("outputs").fields.isEmpty())
            val failure = assertFailsWith<HdlCosimulationFailure> { job.hdlSample(mapOf("y" to 1)) }
            assertEquals("analysis_failed", failure.code)
            val simulator = CircuitSimulator(registry = registry)
            val failed = simulator.settle(document, inputs = mapOf("a-drive" to LogicValue.HIGH, "b-drive" to LogicValue.HIGH),
                events = mapOf("hdl" to listOf(CompanionHdlUnavailable(1, failure.notice))))
            assertEquals(LogicValue.ERROR, failed.componentValues["sink"])
        }
    }
}
