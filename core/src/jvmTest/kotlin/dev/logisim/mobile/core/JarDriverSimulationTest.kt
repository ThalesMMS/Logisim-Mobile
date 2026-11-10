package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarDriverSimulationTest {
    @Test
    fun approvedWorkerDrivesPropagateOnNativeWiresAndRejectStaleReplies() {
        val source = listOf(File("companion/tests/fixtures/jar-drive-contention.circ"),
            File("../companion/tests/fixtures/jar-drive-contention.circ")).first(File::isFile).readText()
        val receipt = CompanionJsonCodec.parseObject(
            checkNotNull(javaClass.getResourceAsStream("/jar/driver-contention-observed.json")).bufferedReader().use { it.readText() }
        )
        assertEquals(receipt.string("projectSha256"), companionSha256(source.encodeToByteArray()))
        assertEquals("d58a61e0c290361722b43df5b1a6b86c1f7c7f0d", receipt.string("evolutionRevision"))
        val trace = receipt.obj("instanceTrace").readJarInstanceTrace(4, expectDriverTrace = true)
        val drives = requireNotNull(trace.drives)
        val pinFrames = receipt.array("inputs").map { (it as CompanionJsonObject).fields.mapValues { (_, value) ->
            value.readJarSignal(1)
        } }
        val outputFrames = receipt.array("frames").map { (it as CompanionJsonObject).fields.mapValues { (_, value) ->
            value.readJarSignal(1)
        } }
        val epoch = Any()
        var active = true
        val bound = resolveJarEditorGeometry(source, "dev.logisim.fixture.DriveLibrary", trace,
            ComponentCatalog.registry, epoch) { active }
        val project = bound.project
        val simulator = CircuitSimulator(registry = bound.registry)
        val left = bound.instances.getValue("/200,100").eventAddress
        val right = bound.instances.getValue("/200,180").eventAddress
        val pins = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }
        val outputPin = project.mainCircuit.components.single { it.kind == ComponentKind.OUTPUT }
        var firstReply: Map<String, List<ComponentEvent>>? = null
        var state = SimulationState()
        for (index in pinFrames.indices) {
            val revision = 10L + index
            val observed = trace.copy(frames = listOf(trace.frames[index]), drives = listOf(drives[index]))
            val sample = JarLiveSample("driver-test-job", index.toLong(), revision, outputFrames[index], observed,
                pinFrames[index], epoch)
            state = simulator.settle(project, state, vectorInputs = pins.associate { it.id to pinFrames[index].getValue(it.label) },
                events = bound.inputRevisionEvents(project, revision))
            val events = bound.outputEvents(project, state, revision, sample)
            if (index == 0) firstReply = events
            if (index == 1) {
                // A pending request must keep the previously applied drive on both sources.
                assertEquals("E", state.portVectors.getValue(outputPin.position).display)
                assertEquals("0", state.componentOutputs.getValue(left).getValue("p2").display)
                assertEquals("1", state.componentOutputs.getValue(right).getValue("p2").display)
                assertFailsWith<IllegalArgumentException> { bound.outputEvents(project, state, revision + 1, sample) }

                val wrongAddresses = mapOf(left to events.getValue(right), right to events.getValue(left))
                val wrongEpoch = events.mapValues { (_, values) -> values.map {
                    (it as JarDriveReplyEvent).copy(sessionIdentity = Any())
                } }
                for (rejected in listOf(requireNotNull(firstReply), wrongAddresses, wrongEpoch)) {
                    state = simulator.settle(project, state, events = rejected)
                    assertEquals("E", state.portVectors.getValue(outputPin.position).display)
                }
            }
            state = simulator.settle(project, state, events = events)
            assertFalse(state.oscillating)
            assertEquals(outputFrames[index].getValue("Y"), state.portVectors.getValue(outputPin.position))
            for ((remote, native) in listOf("/200,100" to left, "/200,180" to right)) {
                when (val drive = drives[index].getValue(remote)[2]) {
                    JarTerminalDrive.Released -> assertTrue("p2" !in state.componentOutputs.getValue(native))
                    is JarTerminalDrive.Driven -> assertEquals(drive.value, state.componentOutputs.getValue(native).getValue("p2"))
                    null -> error("The worker trace omitted an output drive.")
                }
            }
            // The release remains absent when the next settle restores the component state.
            state = simulator.settle(project, state)
            if (index > 0) assertTrue("p2" !in state.componentOutputs.getValue(if (index == 1) left else right))
        }
        val reset = simulator.settle(project, state, reset = true)
        val resetEvents = bound.inputRevisionEvents(project, 20).mapValues { (address, events) ->
            events + requireNotNull(firstReply).getValue(address)
        }
        val resetAttempt = simulator.settle(project, reset, events = resetEvents)
        assertTrue(resetAttempt.componentOutputs.getValue(left).isEmpty())
        assertTrue(resetAttempt.componentOutputs.getValue(right).isEmpty())
        active = false
        state = simulator.settle(project, state, events = requireNotNull(firstReply))
        assertTrue(state.componentOutputs.getValue(left).isEmpty())
        assertTrue(state.componentOutputs.getValue(right).isEmpty())
        assertEquals("?", state.portVectors.getValue(outputPin.position).display)
    }
}
