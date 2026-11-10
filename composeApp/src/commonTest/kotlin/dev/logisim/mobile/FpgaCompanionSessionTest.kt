package dev.logisim.mobile

import dev.logisim.mobile.core.CompanionJob
import dev.logisim.mobile.core.CompanionArtifact
import dev.logisim.mobile.core.CompanionClient
import dev.logisim.mobile.core.CompanionConnection
import dev.logisim.mobile.core.CompanionEndpoint
import dev.logisim.mobile.core.CompanionHttpRequest
import dev.logisim.mobile.core.CompanionHttpResponse
import dev.logisim.mobile.core.CompanionJobRequest
import dev.logisim.mobile.core.CompanionJsonBoolean
import dev.logisim.mobile.core.CompanionJsonString
import dev.logisim.mobile.core.CompanionJobState
import dev.logisim.mobile.core.CompanionJsonCodec
import dev.logisim.mobile.core.CompanionProtocolException
import dev.logisim.mobile.core.CompanionToken
import dev.logisim.mobile.core.CompanionTransport
import dev.logisim.mobile.core.CompanionTransportException
import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBuildPlan
import dev.logisim.mobile.core.FpgaBuildStage
import dev.logisim.mobile.core.FpgaDevice
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaVendor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FpgaCompanionSessionTest {
    private val jobId = "a".repeat(32)

    private fun success(
        placedAndRouted: Boolean = false,
        bitstreamGenerated: Boolean = false,
        programmed: Boolean = false,
        hardwareVerified: Boolean = false,
        encoded: String = "aGVsbG8=",
    ) = CompanionJob(jobId, "fpga-synthesize", CompanionJobState.SUCCEEDED, CompanionJsonCodec.parseObject("""
        {"synthesized":true,"placedAndRouted":$placedAndRouted,"bitstreamGenerated":$bitstreamGenerated,
         "programmed":$programmed,"hardwareVerified":$hardwareVerified,"artifacts":[
         {"name":"synthesized.json","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","base64":"$encoded"}]}
    """.trimIndent()), null, emptyList())

    @Test
    fun successfulSynthesisExposesVerifiedBytesAndSeparateStageFlags() = runBlocking {
        val session = FpgaCompanionSession()
        session.receive(success())
        assertEquals(CompanionJobState.SUCCEEDED, session.job?.state)
        assertTrue(session.outcome!!.synthesized)
        assertFalse(session.outcome!!.placedAndRouted)
        assertFalse(session.outcome!!.bitstreamGenerated)
        assertFalse(session.outcome!!.programmed)
        assertFalse(session.outcome!!.hardwareVerified)
        assertContentEquals("hello".encodeToByteArray(), session.artifacts.single().bytes)
        assertEquals("synthesized.json", session.artifacts.single().name)
    }

    @Test
    fun successfulBitstreamKeepsPlacementAndPackingSeparate() = runBlocking {
        val session = FpgaCompanionSession()
        session.receive(success(placedAndRouted = true, bitstreamGenerated = true))
        assertTrue(session.outcome!!.placedAndRouted)
        assertTrue(session.outcome!!.bitstreamGenerated)
        assertFalse(session.outcome!!.programmed)
    }

    @Test
    fun vendorBuildsRejectAnotherBoardsProgrammingFile() = runBlocking {
        for ((operation, expected, target) in listOf(
            Triple("fpga-ise-cpld-build", "design.jed", null),
            Triple("fpga-quartus-build", "design.pof", "max-v-5m570z"),
            Triple("fpga-quartus-build", "design.pof", "max-7000-epm7128s"),
            Triple("fpga-quartus-build", "design.pof", "max-7000-epm7256s"),
        )) {
            fun session() = FpgaCompanionSession().also {
                it.request = CompanionJobRequest(operation, emptyList(),
                    target?.let { value -> mapOf("target" to CompanionJsonString(value)) } ?: emptyMap())
            }
            fun snapshot(name: String) = CompanionJob(jobId, operation, CompanionJobState.SUCCEEDED,
                CompanionJsonCodec.parseObject("""
                    {"synthesized":true,"placedAndRouted":true,"bitstreamGenerated":true,
                     "programmed":false,"hardwareVerified":false,"artifacts":[
                     {"name":"$name","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","base64":"aGVsbG8="}]}
                """.trimIndent()), null, emptyList())
            val accepted = session()
            accepted.receive(snapshot(expected))
            assertEquals(expected, accepted.artifacts.single().name)
            for (wrongName in listOf("design.jed", "design.pof", "design.sof").filter { it != expected }) {
                val rejected = session()
                assertEquals("invalid-artifact", assertFailsWith<CompanionProtocolException> {
                    rejected.receive(snapshot(wrongName))
                }.code)
                assertTrue(rejected.artifacts.isEmpty())
            }
        }
    }

    @Test
    fun tamperedArtifactNeverBecomesExportable() = runBlocking {
        val session = FpgaCompanionSession()
        val failure = assertFailsWith<CompanionProtocolException> { session.receive(success(encoded = "aGVsbGE=")) }
        assertEquals("invalid-artifact", failure.code)
        assertTrue(session.artifacts.isEmpty())
        assertEquals(null, session.outcome)
    }

    @Test
    fun physicalClaimsAndInconsistentStageFlagsAreRejected() = runBlocking {
        for (snapshot in listOf(success(programmed = true), success(hardwareVerified = true), success(bitstreamGenerated = true))) {
            val session = FpgaCompanionSession()
            val failure = assertFailsWith<CompanionProtocolException> { session.receive(snapshot) }
            assertEquals("invalid-fpga-result", failure.code)
            assertTrue(session.artifacts.isEmpty())
            assertEquals(null, session.outcome)
        }
    }

    @Test
    fun unrelatedJobAndLateRunningSnapshotCannotReplaceCompletedJob() = runBlocking {
        val session = FpgaCompanionSession()
        session.receive(success(), jobId)
        val running = CompanionJob(jobId, "fpga-synthesize", CompanionJobState.RUNNING, CompanionJsonCodec.parse("null"), null, emptyList())
        session.receive(running, jobId)
        assertEquals(CompanionJobState.SUCCEEDED, session.job?.state)
        assertContentEquals("hello".encodeToByteArray(), session.artifacts.single().bytes)
        assertFailsWith<CompanionProtocolException> { session.receive(success(), "b".repeat(32)) }
        assertEquals(jobId, session.job?.jobId)
    }

    @Test
    fun binarySofDownloadFailureKeepsStatusRetryableAndNeverPublishesUnverifiedBytes() = runBlocking {
        val part = "5CSEBA6U23I7"
        val bytes = ByteArray(7_449_702) { it.toByte() }
        val digest = CompanionArtifact.fromQuartusSof("design.sof", bytes).sha256
        val transport = object : CompanionTransport {
            var downloads = 0
            override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
                assertTrue(request.url.endsWith("/v1/jobs/$jobId/artifacts/design.sof"))
                if (++downloads == 1) throw CompanionTransportException("fixture-lost-download")
                return CompanionHttpResponse(200, bytes, "application/octet-stream")
            }
        }
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"),
            CompanionToken.parse("x".repeat(32))), transport)
        val board = FpgaBoardDefinition("DE10-Nano", FpgaDevice(FpgaVendor.ALTERA, "Cyclone V", part, "UBGA 484", "I7"), null, emptyList())
        val session = FpgaCompanionSession().apply {
            request = CompanionJobRequest("fpga-quartus-build", emptyList(), mapOf("binarySof" to CompanionJsonBoolean(true)))
            reviewedBuild = FpgaBuildPlan(board, FpgaFlow.QUARTUS, "logisim_fpga_top", emptyList(),
                listOf(FpgaBuildStage("vendor-build", "quartus_sh", emptyList(), listOf("output_files/build.sof"))),
                emptyMap(), emptyList())
        }
        session.receive(CompanionJob(jobId, "fpga-quartus-build", CompanionJobState.RUNNING, CompanionJsonCodec.parse("null"), null, emptyList()))
        val finished = CompanionJob(jobId, "fpga-quartus-build", CompanionJobState.SUCCEEDED, CompanionJsonCodec.parseObject("""
            {"flow":"quartus","top":"logisim_fpga_top","part":"$part","synthesized":true,"placedAndRouted":true,
             "bitstreamGenerated":true,"programmed":false,"hardwareVerified":false,"artifacts":[
             {"name":"design.sof","size":${bytes.size},"sha256":"$digest","transport":"binary"}]}
        """.trimIndent()), null, emptyList())
        assertFailsWith<CompanionTransportException> { session.receive(finished, jobId, client) }
        assertTrue(session.active)
        assertTrue(session.artifacts.isEmpty())
        session.receive(finished, jobId, client)
        assertEquals(2, transport.downloads)
        assertEquals(CompanionJobState.SUCCEEDED, session.job?.state)
        assertEquals(part, session.artifacts.single().quartusSofPart)
        assertEquals(digest, session.artifacts.single().sha256)
    }
}
