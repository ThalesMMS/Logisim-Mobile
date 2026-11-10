package dev.logisim.mobile.core

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionServerIntegrationTest {
    @Test
    fun malformedUtf8ReportsAProtocolErrorInsteadOfLeakingAnIoException() = runBlocking {
        val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8765"), CompanionToken.parse("test-token-".repeat(4)))
        val transport = object : CompanionTransport {
            override suspend fun execute(request: CompanionHttpRequest) = CompanionHttpResponse(200, byteArrayOf(0xC3.toByte()), "application/json")
        }
        assertEquals("invalid-json", assertFailsWith<CompanionProtocolException> { CompanionClient(connection, transport).capabilities() }.code)
    }

    @Test
    fun programmingTargetsAreOptionalForLegacyHostsAndValidatedWhenAdvertised() = runBlocking {
        val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8765"), CompanionToken.parse("test-token-".repeat(4)))
        suspend fun readTargets(rawTargets: String?): List<String> {
            val field = rawTargets?.let { ",\"programTargets\":$it" }.orEmpty()
            val json = """{"protocolVersion":1,"operations":[{"name":"fpga-program","allowed":true,"available":true,
                "missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"program"$field}],
                "limits":{"requestBytes":100,"inputBytes":100,"outputBytes":100,"timeoutSeconds":1,
                "processMemoryBytes":1024,"memoryEnforcement":"test","osSandbox":false}}"""
            val transport = object : CompanionTransport {
                override suspend fun execute(request: CompanionHttpRequest) =
                    CompanionHttpResponse(200, json.encodeToByteArray(), "application/json")
            }
            return CompanionClient(connection, transport).capabilities().operations.single().programTargets
        }
        assertEquals(emptyList(), readTargets(null))
        assertEquals(listOf("bench", "rack.A_1-2"), readTargets("""["bench","rack.A_1-2"]"""))
        val tooMany = (1..33).joinToString(",", "[", "]") { "\"bench$it\"" }
        for (invalid in listOf("""["../bench"]""", """["a","a"]""", """[""]""", "[1]", "null", tooMany)) {
            val error = assertFailsWith<CompanionProtocolException> { readTargets(invalid) }
            assertEquals("invalid-response", error.code)
        }
    }

    @Test
    fun quartusFlashCapabilitiesRequireAnExactGeckoProfile() = runBlocking {
        val connection = CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8765"), CompanionToken.parse("test-token-".repeat(4)))
        suspend fun readFlashTargets(board: String, position: Int): List<String> {
            val json = """{"protocolVersion":1,"operations":[{"name":"fpga-program","allowed":true,"available":true,
                "missingTools":[],"toolAlternatives":[],"requiresConfirmation":true,"description":"program",
                "programTargets":["bench"],"flashProgramTargets":["bench"],"programTargetProfiles":{"bench":{
                "board":"$board","transport":"quartus","cable":"USB-Blaster [1-2]","chainPosition":$position,
                "flashDevice":"EPCQ16","fingerprint":"${"a".repeat(64)}"}}}],
                "limits":{"requestBytes":100,"inputBytes":100,"outputBytes":100,"timeoutSeconds":1,
                "processMemoryBytes":1024,"memoryEnforcement":"test","osSandbox":false}}"""
            val transport = object : CompanionTransport {
                override suspend fun execute(request: CompanionHttpRequest) =
                    CompanionHttpResponse(200, json.encodeToByteArray(), "application/json")
            }
            return CompanionClient(connection, transport).capabilities().operations.single().flashProgramTargets
        }
        val epfl = "GECKO4_EDUCATION_EPFL_EDITION"
        assertEquals(listOf("bench"), readFlashTargets("GECKO4_EDUCATION", 0))
        assertEquals(listOf("bench"), readFlashTargets(epfl, 0))
        assertEquals("invalid-response", assertFailsWith<CompanionProtocolException> { readFlashTargets(epfl, 1) }.code)
        assertEquals("invalid-response", assertFailsWith<CompanionProtocolException> { readFlashTargets("DE0_CV", 0) }.code)
    }

    @Test
    fun actualServerAuthenticatesUploadsReportsStatusAndReturnsVerifiedArtifacts() = runBlocking {
        Fixture().use { fixture ->
            val client = fixture.client()
            val capabilities = client.capabilities()
            assertEquals(1, capabilities.protocolVersion)
            assertTrue(capabilities.operations.single { it.name == "verify-inputs" }.allowed)
            assertFalse(capabilities.limits.osSandbox)
            val payload = "Olá circuitos 💡".encodeToByteArray()
            val request = CompanionJobRequest("test-artifact", listOf(CompanionInput.fromBytes("input.txt", payload)))
            val submitted = client.submit(request, requestId = "artifact-retry-1")
            val finished = finish(client, submitted)
            assertEquals(CompanionJobState.SUCCEEDED, finished.state)
            val artifact = finished.artifact((finished.result as CompanionJsonObject).fields.getValue("artifact"))
            assertTrue(payload.contentEquals(artifact.bytes))
            assertEquals("result.bin", artifact.name)
            assertEquals(listOf("artifact ready"), finished.logs.map { it.text })
            assertEquals(submitted.jobId, client.submit(request, requestId = "artifact-retry-1").jobId)
            val conflict = assertFailsWith<CompanionProtocolException> {
                client.submit(request.copy(parameters = mapOf("different" to CompanionJsonBoolean(true))), requestId = "artifact-retry-1")
            }
            assertEquals("request-conflict", conflict.code)
        }
    }

    @Test
    fun actualServerRequiresTheReviewedPlanAndRejectsChangedApproval() = runBlocking {
        Fixture().use { fixture ->
            val client = fixture.client()
            val request = CompanionJobRequest("test-confirmed", listOf(CompanionInput.fromBytes("input.txt", byteArrayOf(1, 2, 3))),
                parameters = mapOf("frequency" to CompanionJsonNumber("1e0"), "largeInteger" to CompanionJsonNumber("9007199254740993")))
            assertEquals("confirmation-required", assertFailsWith<CompanionProtocolException> { client.submit(request) }.code)
            val plan = client.plan(request)
            assertTrue(plan.requiresConfirmation)
            assertEquals(request.inputs.single().sha256, plan.inputs.single().sha256)
            val approval = plan.confirm()
            assertEquals("approval-mismatch", assertFailsWith<CompanionProtocolException> {
                client.submit(request.copy(parameters = mapOf("changed" to CompanionJsonBoolean(true))), approval)
            }.code)
            val done = finish(client, client.submit(request, approval))
            assertEquals(CompanionJobState.SUCCEEDED, done.state)
            assertTrue((done.result as CompanionJsonObject).boolean("confirmed"))
            assertEquals("confirmation-required", assertFailsWith<CompanionProtocolException> { client.submit(request, approval) }.code)
        }
    }

    @Test
    fun actualServerReportsAuthorizationAndOperationErrorsAndCancelsJobs() = runBlocking {
        Fixture().use { fixture ->
            val unauthorized = fixture.client("wrong-test-token-".repeat(3))
            val error = assertFailsWith<CompanionProtocolException> { unauthorized.capabilities() }
            assertEquals(401, error.httpStatus)
            assertEquals("unauthorized", error.code)
            val client = fixture.client()
            assertEquals("unknown-operation", assertFailsWith<CompanionProtocolException> {
                client.submit(CompanionJobRequest("not-an-operation"))
            }.code)
            val submitted = client.submit(CompanionJobRequest("verify-inputs", parameters = mapOf("delaySeconds" to CompanionJsonNumber(2L))))
            val cancelled = finish(client, client.cancel(submitted.jobId))
            assertEquals(CompanionJobState.CANCELLED, cancelled.state)
        }
    }

    @Test
    fun actualHttpAdapterDoesNotFollowRedirectsAndBoundsResponsesAndCancellation() = runBlocking {
        Fixture().use { fixture ->
            val transport = createCompanionTransport()
            val headers = mapOf("Authorization" to "Bearer ${fixture.token}")
            fun request(path: String, limit: Int = 4096) = CompanionHttpRequest(fixture.endpoint.url + path, "GET", headers, null, 5_000, limit)
            assertEquals(302, transport.execute(request("/v1/test-redirect")).status)
            assertEquals("response-limit", assertFailsWith<CompanionTransportException> {
                transport.execute(request("/v1/test-large", 64))
            }.code)
            val start = System.nanoTime()
            assertFailsWith<TimeoutCancellationException> { withTimeout(200) { transport.execute(request("/v1/test-slow")) } }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_800, "HTTP cancellation did not release the caller promptly")
        }
    }

    @Test
    fun actualHttpAdapterAppliesATotalDeadlineAndRejectsUntrustedTls() = runBlocking {
        Fixture().use { fixture ->
            val start = System.nanoTime()
            val request = CompanionHttpRequest(fixture.endpoint.url + "/v1/test-trickle", "GET", emptyMap(), null, 300, 4096)
            assertEquals("timeout", assertFailsWith<CompanionTransportException> { createCompanionTransport().execute(request) }.code)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500, "A trickling response exceeded the total deadline")
        }
        Fixture(tls = true).use { fixture ->
            assertEquals("network-failure", assertFailsWith<CompanionTransportException> { fixture.client().capabilities() }.code)
        }
    }

    @Test
    fun actualServerSynthesizesUploadedFpgaSourceAndVerifiesTheNetlist() = runBlocking {
        Fixture().use { fixture ->
            val client = fixture.client()
            val operation = client.capabilities().operations.firstOrNull { it.name == "fpga-synthesize" }
            org.junit.Assume.assumeTrue("Requires the FPGA companion handler and Yosys", operation?.available == true)
            val source = "module top(input a, input b, output y); assign y = a & b; endmodule"
            val request = CompanionJobRequest("fpga-synthesize", listOf(CompanionInput.fromBytes("source.v", source.encodeToByteArray())),
                mapOf("flow" to CompanionJsonString("ice40"), "part" to CompanionJsonString("hx1k"),
                    "package" to CompanionJsonString("tq144"), "top" to CompanionJsonString("top"),
                    "language" to CompanionJsonString("verilog"), "goal" to CompanionJsonString("synthesis"),
                    "sources" to CompanionJsonArray(listOf(CompanionJsonString("source.v")))))
            val plan = client.plan(request)
            val done = finish(client, client.submit(request, plan.confirm()))
            assertEquals(CompanionJobState.SUCCEEDED, done.state, done.error?.message)
            val result = done.result as CompanionJsonObject
            assertTrue(result.boolean("synthesized"))
            assertFalse(result.boolean("programmed"))
            assertFalse(result.boolean("hardwareVerified"))
            val netlist = done.artifact(result.array("artifacts").single())
            assertEquals("synthesized.json", netlist.name)
            assertTrue(CompanionJsonCodec.parseObject(netlist.bytes.decodeToString()).obj("modules").fields.containsKey("top"))
        }
    }

    private suspend fun finish(client: CompanionClient, initial: CompanionJob): CompanionJob = withTimeout(10_000) {
        var job = initial
        while (!job.state.terminal) { delay(20); job = client.status(job.jobId) }
        job
    }

    private class Fixture(tls: Boolean = false) : AutoCloseable {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private val root = sequenceOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "scripts/companion-client-fixture.py").isFile }
        private val source = System.getenv("LOGISIM_COMPANION_SOURCE")?.let(::File) ?: root
        private val process: Process
        val endpoint: CompanionEndpoint
        init {
            check(File(source, "companion/server.py").isFile) { "Companion server is missing; set LOGISIM_COMPANION_SOURCE to its checkout." }
            val log = File(root, "outputs/companion-client/fixture-errors.log").apply { parentFile.mkdirs() }
            val builder = ProcessBuilder("python3", "-u", File(root, "scripts/companion-client-fixture.py").path, source.canonicalPath, if (tls) "tls" else "plain")
                .redirectError(log)
            builder.environment()["LOGISIM_COMPANION_TEST_TOKEN"] = token
            process = builder.start()
            val port = process.inputStream.bufferedReader().readLine()?.toIntOrNull()
            check(port != null) { "Companion test server did not start; see outputs/companion-client/fixture-errors.log." }
            endpoint = CompanionEndpoint.parse("${if (tls) "https" else "http"}://127.0.0.1:$port")
        }
        fun client(secret: String = token) = CompanionClient(CompanionConnection(endpoint, CompanionToken.parse(secret)))
        override fun close() { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
    }
}
