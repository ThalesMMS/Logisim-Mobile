package dev.logisim.mobile

import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarEditorControllerTest {
    @Test
    fun nativePokeKeepsPreviousDriveUntilTheOrderedWorkerReply() = runBlocking {
        withTimeout(4_000) {
            val xml = repoFile("companion/tests/fixtures/jar-drive-contention.circ").readText()
            val observed = CompanionJsonCodec.parseObject(
                repoFile("core/src/jvmTest/resources/jar/driver-contention-observed.json").readText()
            )
            assertEquals(observed.string("projectSha256"), CompanionInput.fromBytes("project.circ", xml.encodeToByteArray()).sha256)
            val worker = RecordedWorker(observed)
            val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("https://worker.invalid"),
                CompanionToken.parse("a".repeat(32))), worker)
            val request = jarLiveRequest(xml.encodeToByteArray(), byteArrayOf(1), "dev.logisim.fixture.DriveLibrary",
                driverTrace = true)
            val plan = client.plan(request)
            val initial = (observed.array("inputs")[0] as CompanionJsonObject).fields.mapValues { (_, value) ->
                LogicVector.of(1, (value as CompanionJsonString).value.toULong())
            }
            val closed = CompletableDeferred<Unit>()
            val failures = mutableListOf<String>()
            assertFailsWith<IllegalArgumentException> {
                JarEditorController.open(this, client, request, plan, plan.confirm(), "$xml\n",
                    ComponentCatalog.registry, initial, { true }, {}, {})
            }
            val differentJar = jarLiveRequest(xml.encodeToByteArray(), byteArrayOf(2), "dev.logisim.fixture.DriveLibrary",
                driverTrace = true)
            val mismatch = assertFailsWith<CompanionProtocolException> {
                JarEditorController.open(this, client, differentJar, plan, plan.confirm(), xml,
                    ComponentCatalog.registry, initial, { true }, {}, {})
            }
            assertEquals("approval-mismatch", mismatch.code)
            assertFalse(worker.submitted)
            val controller = JarEditorController.open(this, client, request, plan, plan.confirm(), xml,
                ComponentCatalog.registry, initial, { true }, { failures += it.resolve() }, { closed.complete(Unit) })
            val editor = EditorState(null, jarController = controller)
            val observer = launch { editor.observeJarSimulation() }
            try {
                val ea = editor.document.components.single { it.kind == ComponentKind.INPUT && it.label == "EA" }
                val output = editor.document.components.single { it.kind == ComponentKind.OUTPUT }
                fun signal() = editor.simulation.portVectors.getValue(output.position).display
                assertFalse(editor.documentWritable)
                assertEquals(xml, editor.exportXml())
                assertEquals(0L, worker.inputs.receive())
                assertEquals("E", signal())

                editor.tap(ea.position, EditorMode.INTERACT)
                assertEquals(1L, worker.inputs.receive())
                while (editor.simulation.inputVectors.getValue(ea.id).display != "0") yield()
                assertEquals("E", signal())
                worker.releaseFirstReply = true
                while (signal() != "1") yield()

                assertTrue(editor.activateComponent(ea.id))
                assertEquals(2L, worker.inputs.receive())
                assertEquals("1", signal())
                editor.resetSimulation()
                closed.await()
                while (signal() != "?") yield()
                assertTrue(worker.cancelled)
                assertFalse(editor.activateComponent(ea.id))
                assertTrue(failures.isEmpty(), failures.toString())
                assertEquals(xml, editor.exportXml())
            } finally {
                controller.stop()
                observer.cancelAndJoin()
            }
        }
    }

    private class RecordedWorker(private val observed: CompanionJsonObject) : CompanionTransport {
        val inputs = Channel<Long>(Channel.UNLIMITED)
        var releaseFirstReply = false
        var submitted = false
            private set
        var cancelled = false
            private set
        private var sequence = -1L
        private val jobId = "a".repeat(32)

        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            val path = request.url.substringAfter("/v1/")
            val body = request.body?.let { CompanionJsonCodec.parseObject(it.decodeToString()) }
            val result = when (request.method to path) {
                "POST" to "plans" -> {
                    val envelope = requireNotNull(body)
                    json("operation" to envelope.fields.getValue("operation"),
                        "parameters" to envelope.obj("parameters"),
                        "inputs" to CompanionJsonArray(envelope.array("inputs").map {
                            val input = it as CompanionJsonObject
                            val size = kotlin.io.encoding.Base64.decode(input.string("base64")).size
                            json("name" to input.fields.getValue("name"), "sha256" to input.fields.getValue("sha256"),
                                "size" to CompanionJsonNumber(size.toLong()))
                        }),
                        "planToken" to CompanionJsonString("approved-plan"),
                        "requiresConfirmation" to CompanionJsonBoolean(true),
                        "expiresInSeconds" to CompanionJsonNumber(60L),
                        "osSandbox" to CompanionJsonString("test-fixture"))
                }
                "POST" to "jobs" -> {
                    assertEquals("approved-plan", requireNotNull(body).string("planToken"))
                    submitted = true
                    job()
                }
                "GET" to "jobs/$jobId" -> {
                    val recorded = when {
                        sequence < 0 -> null
                        sequence >= 1 && releaseFirstReply -> 1
                        else -> 0
                    }
                    val live = if (recorded == null) json("ports" to observed.fields.getValue("ports")) else {
                        val trace = observed.obj("instanceTrace")
                        json("ports" to observed.fields.getValue("ports"), "sequence" to CompanionJsonNumber(recorded.toLong()),
                            "revision" to CompanionJsonNumber(recorded.toLong()), "outputs" to observed.array("frames")[recorded],
                            "instanceTrace" to json("instances" to trace.fields.getValue("instances"),
                                "frames" to CompanionJsonArray(listOf(trace.array("frames")[recorded])),
                                "drives" to CompanionJsonArray(listOf(trace.array("drives")[recorded]))))
                    }
                    job(live = live)
                }
                "POST" to "jobs/$jobId/input" -> {
                    val input = requireNotNull(body)
                    assertEquals(sequence + 1, input.long("sequence"))
                    sequence++
                    assertEquals(sequence, input.long("revision"))
                    assertEquals(observed.array("inputs")[(sequence % 2).toInt()], input.obj("inputs"))
                    inputs.send(sequence)
                    json("accepted" to CompanionJsonBoolean(true), "sequence" to CompanionJsonNumber(sequence))
                }
                "DELETE" to "jobs/$jobId" -> {
                    cancelled = true
                    job("cancelled")
                }
                else -> error("Unexpected worker request ${request.method} $path")
            }
            return CompanionHttpResponse(200, CompanionJsonCodec.write(result).encodeToByteArray(), "application/json")
        }

        private fun job(state: String = "running", live: CompanionJsonObject? = null) =
            CompanionJsonObject(buildMap {
                put("jobId", CompanionJsonString(jobId))
                put("operation", CompanionJsonString("jar-live"))
                put("state", CompanionJsonString(state))
                put("logs", CompanionJsonArray(emptyList()))
                if (live != null) put("live", live)
            })

        private fun json(vararg fields: Pair<String, CompanionJson>) = CompanionJsonObject(mapOf(*fields))
    }

    private fun repoFile(path: String): File = listOf(File(path), File("../$path")).first(File::isFile)
}
