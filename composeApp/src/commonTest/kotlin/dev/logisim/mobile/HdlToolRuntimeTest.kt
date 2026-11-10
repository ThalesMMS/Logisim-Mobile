package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HdlToolRuntimeTest {
    @Test fun approvedInverterFollowsInputWithNoPanelOrSelection() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.observe()
            assertEquals(emptyList(), fixture.host.inputs)
            fixture.approve()
            fixture.observe()
            fixture.idle()
            assertEquals(LogicValue.HIGH, fixture.output)

            fixture.drive(LogicValue.HIGH)
            fixture.observe()
            fixture.idle()
            assertEquals(LogicValue.LOW, fixture.output)
            fixture.observe()
            assertEquals(listOf("0", "1"), fixture.host.inputs)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test fun inputThatChangesAndReturnsDuringRoundTripReplaysEdgesWithoutPublishingOldSamples() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.approve()
            val reply = CompletableDeferred<Unit>()
            fixture.host.reply = reply
            val firstTarget = fixture.simulation.propagationTime + 1
            fixture.observe()
            withTimeout(2_000) { fixture.host.stepStarted.await() }
            fixture.drive(LogicValue.HIGH)
            fixture.drive(LogicValue.LOW)
            reply.complete(Unit)
            fixture.idle()
            assertEquals(LogicValue.UNKNOWN, fixture.output)
            assertEquals(0, fixture.publishedSamples)

            fixture.host.reply = null
            val nextTarget = maxOf(firstTarget, fixture.simulation.propagationTime) + 1
            fixture.observe()
            fixture.idle()
            assertEquals(LogicValue.UNKNOWN, fixture.output)
            assertEquals(0, fixture.publishedSamples)
            val finalTarget = maxOf(nextTarget, fixture.simulation.propagationTime) + 1
            fixture.observe()
            fixture.idle()
            assertEquals(LogicValue.HIGH, fixture.output)
            assertEquals(listOf("0", "1", "0"), fixture.host.inputs)
            assertEquals(listOf(firstTarget, nextTarget, finalTarget), fixture.host.times)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test fun overflowingTheInputHistoryStopsTheSessionWithADiagnostic() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.approve()
            val reply = CompletableDeferred<Unit>()
            fixture.host.reply = reply
            fixture.observe()
            withTimeout(2_000) { fixture.host.stepStarted.await() }
            repeat(257) { fixture.drive(if (it % 2 == 0) LogicValue.HIGH else LogicValue.LOW) }
            reply.complete(Unit)
            fixture.idle()
            fixture.observe()

            withTimeout(2_000) { fixture.host.cancelled.await() }
            assertEquals(listOf("0"), fixture.host.inputs)
            assertEquals(0, fixture.publishedSamples)
            assertNull(fixture.runtime.session)
            assertEquals("The HDL-IP input queue overflowed. The session stopped because some transitions could not be sent. Start another session.",
                (fixture.simulation.componentStates["hdl"] as HdlIpState).failure)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test fun oldReplyCannotRestoreOutputAfterResetOrStop() = runBlocking {
        for (reset in listOf(true, false)) {
            val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try {
                fixture.approve()
                val reply = CompletableDeferred<Unit>()
                fixture.host.reply = reply
                fixture.observe()
                withTimeout(2_000) { fixture.host.stepStarted.await() }

                if (reset) {
                    fixture.reset()
                    fixture.observe()
                } else fixture.runtime.invalidate()

                reply.complete(Unit)
                withTimeout(2_000) { fixture.host.cancelled.await() }
                fixture.idle()
                assertEquals(LogicValue.UNKNOWN, fixture.output)
                assertEquals(0, fixture.publishedSamples)
                assertNull(fixture.runtime.session)
                assertNull((fixture.simulation.componentStates["hdl"] as HdlIpState).sessionId)
            } finally {
                fixture.runtime.close()
            }
        }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val host = Host()
        val runtime = HdlToolRuntime(dispatcher)
        private val source = """
            library ieee;
            use ieee.std_logic_1164.all;
            entity inverter is port(a: in std_logic; y: out std_logic); end;
            architecture rtl of inverter is begin y <= not a; end;
        """.trimIndent()
        private val component = CircuitComponent("hdl", HdlIpLibrary.VHDL, GridPoint(100, 100),
            attributes = mapOf("content" to source))
        private val circuit = CircuitDocument("main", components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(100, 140)),
            component,
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(240, 140)),
        ))
        private val project = CircuitProject(listOf(circuit))
        private val simulator = CircuitSimulator()
        private var inputs = mapOf("a" to LogicValue.LOW)
        var simulation by mutableStateOf(simulator.settle(project, inputs = inputs))
            private set
        var publishedSamples = 0
            private set
        val output get() = simulation.componentValues.getValue("y")
        private val context: EditorToolContext
            get() = EditorToolContext(project, circuit, simulation, emptyList(), {}, {}, {}, {}, {}, injectEvents = { events ->
                if (events.values.flatten().any { it is CompanionHdlReply }) publishedSamples++
                simulation = simulator.settle(project, simulation, inputs = inputs, events = events)
                true
            })

        init { runtime.latestContext = { context } }

        fun approve() {
            val target = HdlToolTarget.from(context, component)
            val digest = CompanionInput.fromBytes("source.hdl", source.encodeToByteArray()).sha256
            host.digest = digest
            runtime.session = HdlToolSession(target, host.client, host.session, 1, digest)
            runtime.send(target, CompanionHdlStarted(host.session, 1))
        }

        fun observe() = runtime.synchronize(context, host.client)

        fun drive(value: LogicValue) {
            inputs = mapOf("a" to value)
            simulation = simulator.settle(project, simulation, inputs = inputs)
        }

        fun reset() {
            simulation = simulator.settle(project, simulation, inputs = inputs, reset = true)
        }

        suspend fun idle() = withTimeout(2_000) {
            while (runtime.busy) delay(1)
            // Allow an invalidated worker to consume its released reply.
            delay(1)
        }
    }

    private class Host : CompanionTransport {
        val session = "a".repeat(32)
        lateinit var digest: String
        val inputs = mutableListOf<String>()
        val times = mutableListOf<Long>()
        var reply: CompletableDeferred<Unit>? = null
        val stepStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8001"),
            CompanionToken.parse("synthetic-test-token-with-more-than32chars")), this)

        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            val body = CompanionJsonCodec.parseObject(request.body!!.decodeToString())
            val operation = body.string("operation")
            val params = body.obj("parameters")
            assertEquals(session, params.string("sessionId"))
            val result = when (operation) {
                "hdl.step" -> {
                    val input = params.obj("inputs").string("a")
                    inputs += input
                    val time = (times.lastOrNull() ?: 0) + params.long("advanceNs")
                    times += time
                    stepStarted.complete(Unit)
                    reply?.await()
                    val output = if (input == "0") "1" else "0"
                    """{"ok":true,"sessionId":"$session","sourceSha256":"$digest","timeNs":$time,"outputs":{"y":{"logic":"$output"}}}"""
                }
                "hdl.cancel" -> { cancelled.complete(Unit); """{"ok":true}""" }
                else -> error(operation)
            }
            val response = """{"jobId":"${"b".repeat(32)}","operation":"$operation","state":"succeeded","result":$result,"error":null,"logs":[]}"""
            return CompanionHttpResponse(200, response.encodeToByteArray(), "application/json")
        }
    }
}
