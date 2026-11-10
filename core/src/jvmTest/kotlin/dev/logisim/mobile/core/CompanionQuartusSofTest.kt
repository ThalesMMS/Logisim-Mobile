package dev.logisim.mobile.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompanionQuartusSofTest {
    private val endpoint = "http://127.0.0.1:8080"
    private val token = "t".repeat(32)
    private val connection = CompanionConnection(CompanionEndpoint.parse(endpoint), CompanionToken.parse(token))
    private val jobId = "1".repeat(32)
    private val part = "5CSEBA6U23I7"
    private val profile = CompanionProgramTargetProfile(
        "DE10-NANO", "USB-Blaster [1-1]", "quartus", 0, "a".repeat(64), null, sofPart = part,
    )
    private val fileBytes by lazy { ByteArray(7_449_702) { (it * 31).toByte() } }
    private val imported by lazy { CompanionArtifact.fromQuartusSof("imported-bitstream.sof", fileBytes) }

    private class Transport(private val respond: (CompanionHttpRequest) -> CompanionHttpResponse) : CompanionTransport {
        val requests = mutableListOf<CompanionHttpRequest>()
        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            requests += request
            return respond(request)
        }
    }

    @Test
    fun largeSofUsesOnlyTheOptInCapabilityAndKeepsLegacyLimits() = runBlocking<Unit> {
        fun capabilities(limit: Long? = null, sofPart: String? = null, profileTransport: String = "quartus"): CompanionHttpResponse {
            val partField = sofPart?.let { ",\"sofPart\":\"$it\"" }.orEmpty()
            val limitField = limit?.let { ",\"quartusSofBytes\":$it" }.orEmpty()
            return json("""
                {"protocolVersion":1,"operations":[{"name":"fpga-program","allowed":true,"available":true,
                "requiresConfirmation":true,"description":"program","missingTools":[],"toolAlternatives":[],
                "programTargets":["nano"],"programTargetProfiles":{"nano":{"board":"DE10-NANO","cable":"USB-Blaster",
                "transport":"$profileTransport","chainPosition":0,"fingerprint":"${profile.fingerprint}"$partField}}}],
                "limits":{"requestBytes":6291456,"inputBytes":4194304,"outputBytes":2097152,"timeoutSeconds":60,
                "processMemoryBytes":536870912,"memoryEnforcement":"rlimit","osSandbox":false$limitField}}
            """.trimIndent())
        }
        val legacy = CompanionClient(connection, Transport { capabilities() }).capabilities()
        assertNull(legacy.limits.quartusSofBytes)
        assertNull(legacy.operations.single().programTargetProfiles.getValue("nano").sofPart)
        val supported = CompanionClient(connection, Transport {
            capabilities(limit = 8_388_608L, sofPart = part)
        }).capabilities()
        assertEquals(8_388_608L, supported.limits.quartusSofBytes)
        assertEquals(part, supported.operations.single().programTargetProfiles.getValue("nano").sofPart)
        assertEquals(4_194_304L, supported.limits.inputBytes)
        assertEquals(6_291_456L, supported.limits.requestBytes)
        for (bad in listOf(
            capabilities(limit = 8_388_607L),
            capabilities(limit = 8_388_609L),
            capabilities(sofPart = part, profileTransport = "quartus-cpld"),
        )) {
            assertEquals("invalid-response", assertFailsWith<CompanionProtocolException> {
                CompanionClient(connection, Transport { bad }).capabilities()
            }.code)
        }
        assertNull(imported.quartusSofPart)
        assertFailsWith<IllegalArgumentException> { CompanionInput.fromBytes(imported.name, fileBytes) }
        assertFailsWith<IllegalArgumentException> { CompanionArtifact.fromBytes(imported.name, fileBytes) }
        assertFailsWith<IllegalArgumentException> { CompanionArtifact.fromQuartusSof("design.pof", fileBytes) }
        assertFailsWith<IllegalArgumentException> {
            CompanionArtifact.fromQuartusSof("design.sof", ByteArray(QUARTUS_SOF_MAX_BYTES + 1))
        }
    }

    @Test
    fun approvedRawUploadKeepsTheReviewedFileProfileAndRetryId() = runBlocking<Unit> {
        val transport = Transport { request ->
            when (request.url) {
                "$endpoint/v1/fpga-sof/plans" -> {
                    assertEquals("POST", request.method)
                    assertEquals("Bearer $token", request.headers["Authorization"])
                    val sent = CompanionJsonCodec.parseObject(checkNotNull(request.body).decodeToString())
                    assertEquals("fpga-program", sent.string("operation"))
                    assertEquals(setOf("operation", "inputs", "parameters"), sent.fields.keys)
                    val descriptor = sent.array("inputs").single() as CompanionJsonObject
                    assertEquals(setOf("name", "size", "sha256"), descriptor.fields.keys)
                    assertEquals(imported.name, descriptor.string("name"))
                    assertEquals(7_449_702L, descriptor.long("size"))
                    assertEquals(imported.sha256, descriptor.string("sha256"))
                    assertEquals(CompanionJsonCodec.parseObject("""
                        {"target":"nano","bitstream":"imported-bitstream.sof","profile":"${profile.fingerprint}","flash":false}
                    """.trimIndent()), sent.obj("parameters"))
                    review(sent)
                }
                "$endpoint/v1/fpga-sof/jobs" -> {
                    assertEquals("POST", request.method)
                    assertEquals("application/octet-stream", request.headers["Content-Type"])
                    assertEquals("application/json", request.headers["Accept"])
                    assertEquals("Bearer $token", request.headers["Authorization"])
                    assertEquals(imported.name, request.headers["X-Logisim-Sof-Name"])
                    assertEquals(imported.sha256, request.headers["X-Logisim-Sof-Sha256"])
                    assertEquals("nano", request.headers["X-Logisim-Fpga-Target"])
                    assertEquals(profile.fingerprint, request.headers["X-Logisim-Fpga-Profile"])
                    assertEquals("reviewed-plan", request.headers["X-Logisim-Plan-Token"])
                    assertEquals("retry-1", request.headers["X-Logisim-Request-Id"])
                    assertContentEquals(fileBytes, checkNotNull(request.body))
                    json("""
                        {"jobId":"$jobId","operation":"fpga-program","state":"queued","result":null,"error":null,"logs":[]}
                    """.trimIndent(), status = 202)
                }
                else -> error("Unexpected companion request")
            }
        }
        val client = CompanionClient(connection, transport)
        val approval = client.planLargeQuartusSof(imported, "nano", profile).confirm()
        for ((artifact, target) in listOf(
            CompanionArtifact.fromQuartusSof("renamed.sof", fileBytes) to "nano",
            imported to "other",
        )) {
            assertEquals("approval-mismatch", assertFailsWith<CompanionProtocolException> {
                client.submitLargeQuartusSof(artifact, target, profile, approval, "retry-1")
            }.code)
        }
        assertEquals(1, transport.requests.size)
        repeat(2) {
            assertEquals(jobId, client.submitLargeQuartusSof(imported, "nano", profile, approval, "retry-1").jobId)
        }
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun rejectsAPlanThatChangesTheReviewOrCannotCarryItsTokenInAHeader() = runBlocking<Unit> {
        val badReviews = listOf<(CompanionJsonObject) -> CompanionHttpResponse>(
            { review(it, token = "invalid\nheader") },
            { review(it, token = "x".repeat(257)) },
            { review(it, confirmed = false) },
            { review(CompanionJsonObject(it.fields + ("inputs" to CompanionJsonArray(emptyList())))) },
        )
        for (badReview in badReviews) {
            val transport = Transport { request ->
                assertEquals("$endpoint/v1/fpga-sof/plans", request.url)
                badReview(CompanionJsonCodec.parseObject(checkNotNull(request.body).decodeToString()))
            }
            val client = CompanionClient(connection, transport)
            assertEquals("invalid-plan", assertFailsWith<CompanionProtocolException> {
                client.planLargeQuartusSof(imported, "nano", profile)
            }.code)
            assertFailsWith<IllegalArgumentException> { client.planLargeQuartusSof(imported, "nano", profile.copy(sofPart = null)) }
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun downloadReturnsOnlyTheVerifiedBinaryForTheReviewedPart() = runBlocking<Unit> {
        val descriptor = downloadDescriptor()
        val transport = Transport { request ->
            assertEquals("$endpoint/v1/jobs/$jobId/artifacts/design.sof", request.url)
            assertEquals("GET", request.method)
            assertEquals("Bearer $token", request.headers["Authorization"])
            assertEquals("application/octet-stream", request.headers["Accept"])
            assertEquals(fileBytes.size, request.maxResponseBytes)
            assertNull(request.body)
            CompanionHttpResponse(200, fileBytes, "application/octet-stream")
        }
        val received = CompanionClient(connection, transport).downloadQuartusSof(build(descriptor), descriptor, part.lowercase())
        assertEquals("design.sof", received.name)
        assertEquals(part, received.quartusSofPart)
        assertEquals(imported.sha256, received.sha256)
        assertContentEquals(fileBytes, received.bytes)
        fileBytes[0] = 127
        assertEquals(0.toByte(), received.bytes[0])
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun downloadRejectsWrongDescriptorStateAndBinaryResponses() = runBlocking<Unit> {
        val descriptor = downloadDescriptor()
        val unexpected = Transport { error("Invalid descriptor should be rejected before download") }
        val client = CompanionClient(connection, unexpected)
        for (invalid in listOf(
            build(descriptor, state = CompanionJobState.CANCELLED),
            build(descriptor, resultPart = "OTHER"),
            build(descriptor, artifacts = listOf(descriptor, descriptor)),
        )) {
            assertEquals("invalid-artifact", assertFailsWith<CompanionProtocolException> {
                client.downloadQuartusSof(invalid, descriptor, part)
            }.code)
        }
        val corrupt = fileBytes.copyOf().also { it[0] = 1 }
        for ((response, code) in listOf(
            CompanionHttpResponse(302, ByteArray(0), null) to "redirect-denied",
            CompanionHttpResponse(206, fileBytes, "application/octet-stream") to "response-type",
            CompanionHttpResponse(200, fileBytes, "application/json") to "response-type",
            CompanionHttpResponse(200, ByteArray(2), "application/octet-stream") to "invalid-artifact",
            CompanionHttpResponse(200, ByteArray(fileBytes.size + 1), "application/octet-stream") to "response-limit",
            CompanionHttpResponse(200, corrupt, "application/octet-stream") to "invalid-artifact",
        )) {
            assertEquals(code, assertFailsWith<CompanionProtocolException> {
                CompanionClient(connection, Transport { response }).downloadQuartusSof(build(descriptor), descriptor, part)
            }.code)
        }
    }

    private fun downloadDescriptor() = CompanionJsonObject(mapOf(
        "name" to CompanionJsonString("design.sof"),
        "size" to CompanionJsonNumber(fileBytes.size.toLong()),
        "sha256" to CompanionJsonString(imported.sha256),
        "transport" to CompanionJsonString("binary"),
    ))

    private fun build(
        descriptor: CompanionJsonObject, resultPart: String = part, state: CompanionJobState = CompanionJobState.SUCCEEDED,
        artifacts: List<CompanionJsonObject> = listOf(descriptor),
    ) = CompanionJob(jobId, "fpga-quartus-build", state, CompanionJsonObject(mapOf(
        "part" to CompanionJsonString(resultPart), "artifacts" to CompanionJsonArray(artifacts),
    )), null, emptyList())

    private fun review(envelope: CompanionJsonObject, token: String = "reviewed-plan", confirmed: Boolean = true) = json(
        CompanionJsonCodec.write(CompanionJsonObject(envelope.fields + mapOf(
            "expiresInSeconds" to CompanionJsonNumber(60L),
            "planToken" to CompanionJsonString(token),
            "requiresConfirmation" to CompanionJsonBoolean(confirmed),
        ))),
    )

    private fun json(body: String, status: Int = 200) =
        CompanionHttpResponse(status, body.encodeToByteArray(), "application/json")
}
