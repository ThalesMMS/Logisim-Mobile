package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class FpgaProgramSessionTest {
    private val id = "a".repeat(32)
    private val flashFingerprint = "3".repeat(64)
    private val flashProfile = """{"bench":{"board":"ecp5_evn","cable":"ft2232","transport":"openfpgaloader","chainPosition":0,"fingerprint":"$flashFingerprint"}}"""
    private fun result(target: String = "bench", reported: Boolean = true, verified: Boolean = false, flash: Boolean = false, mode: String? = null) =
        CompanionJob(id, "fpga-program", CompanionJobState.SUCCEEDED, CompanionJsonCodec.parseObject(
            """{"target":"$target","programmerReportedSuccess":$reported,"hardwareVerified":$verified,"flash":$flash${
                mode?.let { ",\"programmingMode\":\"$it\"" }.orEmpty()}}"""), null, emptyList())

    @Test fun resultKeepsProgrammerReportSeparateFromHardwareVerification() {
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        session.receive(result())
        assertTrue(session.outcome!!.programmerReportedSuccess)
        assertFalse(session.outcome!!.hardwareVerified)
        assertEquals(CompanionJobState.SUCCEEDED, session.job!!.state)
    }

    @Test fun rejectsWrongTargetOrPhysicalVerificationClaim() {
        for (job in listOf(result(target = "other"), result(verified = true), result(reported = false), result(flash = true))) {
            val session = FpgaProgramSession()
            session.selectTarget("bench")
            assertFailsWith<CompanionProtocolException> { session.receive(job) }
            assertNull(session.outcome)
            assertNull(session.job)
        }
    }

    @Test fun completedJobCannotBeReplacedByLateRunningResponse() {
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        session.receive(result())
        session.receive(CompanionJob(id, "fpga-program", CompanionJobState.RUNNING, CompanionJsonNull, null, emptyList()), id)
        assertEquals(CompanionJobState.SUCCEEDED, session.job!!.state)
        assertFailsWith<CompanionProtocolException> { session.receive(result(), "b".repeat(32)) }
    }
    @Test fun unavailableAndEmptyTargetsCannotBePrepared() = runBlocking {
        for ((allowed, targets) in listOf(false to listOf("bench"), true to emptyList())) {
            val host = FakeHost(allowed, targets)
            val session = FpgaProgramSession()
            session.selectTarget("bench")
            assertFailsWith<IllegalArgumentException> { session.prepare(host.client, artifact()) }
            assertNull(session.review)
            assertEquals(0, host.plans)
        }
    }

    @Test fun planBindsTheSelectedTargetAndVerifiedBitstream() = runBlocking {
        val host = FakeHost(true, listOf("bench"))
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        session.prepare(host.client, artifact())
        val review = requireNotNull(session.review)
        assertTrue(review.requiresConfirmation)
        assertEquals("bench", review.parameters.string("target"))
        assertEquals("design.bin", review.parameters.string("bitstream"))
        assertEquals(3L, review.inputs.single().size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", review.inputs.single().sha256)
        assertEquals(artifact().sha256, session.reviewedHash)
        session.selectTarget("another")
        assertNull(session.review)
    }

    @Test fun newSelectionAndReviewClearThePreviousResult() = runBlocking {
        val host = FakeHost(true, listOf("bench", "other"))
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        session.receive(result())
        session.selectTarget("other")
        assertNull(session.job)
        assertNull(session.outcome)
        session.prepare(host.client, artifact())
        assertNull(session.job)
        assertNull(session.outcome)
        assertEquals("other", session.review!!.parameters.string("target"))
    }

    @Test fun importedFileKeepsNativeNameAndRequiresTheSelectedCableFingerprint() = runBlocking {
        val fingerprint = "2".repeat(64)
        val profile = """{"board":"BASYS3","cable":"Digilent","transport":"vivado","chainPosition":0,"fingerprint":"$fingerprint","unavailableReason":null}"""
        val disabled = profile.replace("null", "\"missing-tool:vivado\"")
        val host = FakeHost(true, listOf("bench", "disabled"), """{"bench":$profile,"disabled":$disabled}""")
        val session = FpgaProgramSession()
        session.refresh(host.client)
        assertTrue(session.selectable("bench"))
        assertFalse(session.selectable("disabled"))
        assertFalse(isFpgaBitstream(DocumentAsset("example.bit", "application/octet-stream", byteArrayOf())))
        val imported = FpgaImportedBitstream.read(DocumentAsset("Previous build.BIT", "application/octet-stream", "abc".encodeToByteArray()))
        assertEquals("Previous build.BIT", imported.name)
        session.selectTarget("bench")
        session.prepare(host.client, imported.artifact)
        val review = requireNotNull(session.review)
        assertEquals(fingerprint, review.parameters.string("profile"))
        assertEquals("imported-bitstream.bit", review.inputs.single().name)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", review.inputs.single().sha256)
    }

    @Test fun onlyApprovedTargetCanReviewFlashAndSwitchingModesInvalidatesApproval() = runBlocking {
        val host = FakeHost(true, listOf("bench", "other"), profiles = flashProfile, flashTargets = listOf("bench"))
        val session = FpgaProgramSession()
        session.refresh(host.client)
        session.selectTarget("other")
        session.selectFlash(true)
        assertFalse(session.flash)
        session.selectTarget("bench")
        session.selectFlash(true)
        session.prepare(host.client, artifact())
        assertEquals("bench", session.review!!.parameters.string("target"))
        assertTrue(session.review!!.parameters.boolean("flash"))
        assertEquals(flashFingerprint, session.review!!.parameters.string("profile"))
        assertFailsWith<CompanionProtocolException> { session.receive(result()) }
        session.receive(result(flash = true))
        assertTrue(session.outcome!!.flash)
        assertFalse(session.outcome!!.hardwareVerified)
        session.selectFlash(false)
        assertNull(session.review)
        assertNull(session.requestId)
        session.prepare(host.client, artifact())
        assertFalse(session.review!!.parameters.boolean("flash"))
    }

    @Test fun flashAliasWithoutAnExplicitJtagProfileIsRejected() = runBlocking {
        val host = FakeHost(true, listOf("bench"), flashTargets = listOf("bench"))
        val failure = assertFailsWith<CompanionProtocolException> { host.client.capabilities() }
        assertEquals("invalid-response", failure.code)
    }

    @Test fun vendorFlashReviewRequiresTheApprovedDeviceAndChain() = runBlocking {
        val fingerprint = "4".repeat(64)
        val ise = """{"bench":{"board":"BASYS2","cable":"usb21","transport":"ise","chainPosition":0,"fingerprint":"$fingerprint","flashDevice":"XCF02S","flashChainPosition":1}}"""
        val quartus = """{"bench":{"board":"GECKO4_EDUCATION","cable":"USB-Blaster [1-2]","transport":"quartus","chainPosition":0,"fingerprint":"$fingerprint","flashDevice":"EPCQ16"}}"""
        for ((profiles, device, position) in listOf(Triple(ise, "XCF02S", 1), Triple(quartus, "EPCQ16", null))) {
            val host = FakeHost(true, listOf("bench"), profiles, flashTargets = listOf("bench"))
            val session = FpgaProgramSession()
            session.refresh(host.client)
            assertEquals(device, session.profiles.getValue("bench").flashDevice)
            assertEquals(position, session.profiles.getValue("bench").flashChainPosition)
            session.selectTarget("bench")
            session.selectFlash(true)
            val extension = if (device == "XCF02S") "bit" else "sof"
            session.prepare(host.client, CompanionArtifact.fromBytes("design.$extension", "abc".encodeToByteArray()))
            assertEquals(fingerprint, session.review!!.parameters.string("profile"))
            assertTrue(session.review!!.parameters.boolean("flash"))
        }
        for (profiles in listOf(ise.replace("XCF02S", "XCF04S"), ise.replace("flashChainPosition\":1", "flashChainPosition\":0"))) {
            val host = FakeHost(true, listOf("bench"), profiles, flashTargets = listOf("bench"))
            assertEquals("invalid-response", assertFailsWith<CompanionProtocolException> { host.client.capabilities() }.code)
        }
    }

    @Test fun revokedFlashApprovalRejectsReviewWithoutFallingBackToRam() = runBlocking {
        val host = FakeHost(true, listOf("bench"), profiles = flashProfile, flashTargets = listOf("bench"))
        val session = FpgaProgramSession()
        session.refresh(host.client)
        session.selectTarget("bench")
        session.selectFlash(true)
        host.flashTargets = emptyList()
        val failure = assertFailsWith<CompanionProtocolException> { session.prepare(host.client, artifact()) }
        assertEquals("fpga-flash-not-allowed", failure.code)
        assertEquals(0, host.plans)
        assertNull(session.review)
    }

    @Test fun importedJedecRequiresTheMicrolabtleProfileAndANonvolatileResult() = runBlocking {
        val fingerprint = "3".repeat(64)
        val profiles = """{"bench":{"board":"MICROLABTLE","cable":"usb21","transport":"ise-cpld","chainPosition":0,"fingerprint":"$fingerprint"},
            "fpga":{"board":"BASYS2","cable":"usb22","transport":"ise","chainPosition":0,"fingerprint":"${"4".repeat(64)}"}}"""
        val host = FakeHost(true, listOf("bench", "fpga", "legacy"), profiles)
        val session = FpgaProgramSession()
        val imported = FpgaImportedBitstream.read(DocumentAsset("logic.JED", "application/octet-stream", "abc".encodeToByteArray()))
        for (target in listOf("fpga", "legacy")) {
            session.selectTarget(target)
            assertFailsWith<IllegalArgumentException> { session.prepare(host.client, imported.artifact) }
        }
        session.selectTarget("bench")
        assertFailsWith<IllegalArgumentException> { session.prepare(host.client, artifact()) }
        assertEquals(0, host.plans)
        session.prepare(host.client, imported.artifact)
        assertEquals("imported-bitstream.jed", session.review!!.inputs.single().name)
        assertEquals(fingerprint, session.review!!.parameters.string("profile"))
        assertFailsWith<CompanionProtocolException> { session.receive(result()) }
        val response = CompanionJob(id, "fpga-program", CompanionJobState.SUCCEEDED, CompanionJsonCodec.parseObject(
            """{"target":"bench","programmerReportedSuccess":true,"hardwareVerified":false,"flash":false,"programmingMode":"cpld-nonvolatile"}"""),
            null, emptyList())
        session.receive(response)
        assertTrue(session.outcome!!.programmerReportedSuccess)
        assertFalse(session.outcome!!.hardwareVerified)
    }

    @Test fun importedPofRequiresTheCpldProfileAndNonvolatileResult() = runBlocking {
        val fingerprint = "3".repeat(64)
        val cpld = """{"board":"MAX_V_CONSOLE","cable":"USB-Blaster [1-2]","transport":"quartus-cpld","chainPosition":0,"fingerprint":"$fingerprint"}"""
        val fpga = cpld.replace("MAX_V_CONSOLE", "DE0_CV").replace("quartus-cpld", "quartus").replace(fingerprint, "4".repeat(64))
        val host = FakeHost(true, listOf("bench", "fpga", "legacy"), """{"bench":$cpld,"fpga":$fpga}""")
        val imported = FpgaImportedBitstream.read(DocumentAsset("MAX V.POF", "application/octet-stream", "abc".encodeToByteArray()))
        val session = FpgaProgramSession()
        session.refresh(host.client)
        assertTrue(session.selectable("bench", imported.artifact.name))
        assertFalse(session.selectable("fpga", imported.artifact.name))
        assertFalse(session.selectable("legacy", imported.artifact.name))
        assertFalse(session.selectable("bench", "legacy.sof"))
        assertTrue(session.selectable("fpga", "legacy.sof"))
        session.selectTarget("fpga")
        assertFailsWith<IllegalArgumentException> { session.prepare(host.client, imported.artifact) }
        assertEquals(0, host.plans)
        session.selectTarget("bench")
        session.prepare(host.client, imported.artifact)
        val review = requireNotNull(session.review)
        assertTrue(review.requiresConfirmation)
        assertFalse(review.parameters.boolean("flash"))
        assertEquals(fingerprint, review.parameters.string("profile"))
        assertEquals("imported-bitstream.pof", review.inputs.single().name)
        assertEquals(imported.artifact.sha256, review.inputs.single().sha256)
        assertFailsWith<CompanionProtocolException> { session.receive(result()) }
        session.receive(result(mode = "cpld-nonvolatile"))
        assertTrue(session.outcome!!.programmerReportedSuccess)
        assertFalse(session.outcome!!.hardwareVerified)
    }

    @Test fun retentionIncludesActiveAndUncertainProgramming() = runBlocking {
        val host = FakeHost(true, listOf("bench"))
        val session = FpgaCompanionSession()
        assertFalse(session.retainsClient)
        session.program.busy = true
        assertTrue(session.retainsClient)
        session.program.busy = false
        session.program.selectTarget("bench")
        session.program.prepare(host.client, artifact())
        host.failFirstSubmit = true
        assertFailsWith<CompanionTransportException> { session.program.submit(host.client) }
        assertTrue(session.retainsClient)
        session.program.submit(host.client)
        assertTrue(session.retainsClient)
        session.program.cancel(host.client, id)
        assertFalse(session.retainsClient)
    }

    @Test fun retryUsesSameRequestAndCancellationUpdatesTheJob() = runBlocking {
        val host = FakeHost(true, listOf("bench"))
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        session.prepare(host.client, artifact())
        host.failFirstSubmit = true
        assertFailsWith<CompanionTransportException> { session.submit(host.client) }
        val originalId = session.requestId
        assertTrue(session.locked)
        session.selectTarget("other")
        assertEquals("bench", session.target)
        session.submit(host.client)
        assertEquals(2, host.submissions.size)
        assertEquals(host.submissions.first(), host.submissions.last())
        assertEquals(originalId, host.submissions.first().string("requestId"))
        assertEquals("fixture", host.submissions.first().string("planToken"))
        assertEquals(CompanionJobState.QUEUED, session.job!!.state)
        session.cancel(host.client, id)
        assertEquals(CompanionJobState.CANCELLED, session.job!!.state)
        assertNull(session.outcome)
    }

    @Test fun largeSofUsesOnlyAnAdvertisedVolatileQuartusTargetAndRetriesTheSameBytes() = runBlocking {
        val fingerprint = "5".repeat(64)
        val profile = """{"bench":{"board":"DE10_Nano","cable":"USB-Blaster [1-2]","transport":"quartus",
            "chainPosition":0,"fingerprint":"$fingerprint","sofPart":"5CSEBA6U23I7"}}"""
        val host = FakeHost(true, listOf("bench", "legacy"), profiles = profile)
        val bytes = ByteArray(6_690_335) { it.toByte() }
        val imported = FpgaImportedBitstream.read(DocumentAsset("factory.SOF", "application/octet-stream", bytes))
        assertEquals("imported-bitstream.sof", imported.artifact.name)
        assertFalse(isFpgaBitstream(DocumentAsset("factory.pof", "application/octet-stream", bytes)))
        val session = FpgaProgramSession()
        session.refresh(host.client)
        assertFalse(session.selectable("bench", imported.artifact))
        host.quartusSofBytes = QUARTUS_SOF_MAX_BYTES
        session.refresh(host.client)
        assertTrue(session.selectable("bench", imported.artifact))
        assertFalse(session.selectable("legacy", imported.artifact))
        session.selectTarget("bench")
        session.prepare(host.client, imported.artifact)
        val review = requireNotNull(session.review)
        assertFalse(review.parameters.boolean("flash"))
        assertEquals(fingerprint, review.parameters.string("profile"))
        assertEquals(bytes.size.toLong(), review.inputs.single().size)
        assertEquals(imported.artifact.sha256, review.inputs.single().sha256)
        host.failFirstSubmit = true
        assertFailsWith<CompanionTransportException> { session.submit(host.client) }
        assertTrue(session.locked)
        session.submit(host.client)
        assertEquals(2, host.binarySubmissions.size)
        assertEquals(host.binarySubmissions.first().headers, host.binarySubmissions.last().headers)
        for (submission in host.binarySubmissions) assertContentEquals(bytes, requireNotNull(submission.body))
        assertTrue(host.submissions.isEmpty())
        assertEquals(CompanionJobState.QUEUED, session.job?.state)
        session.cancel(host.client, id)
        assertEquals(CompanionJobState.CANCELLED, session.job?.state)
    }

    @Test fun rejectedLargeSofLetsTheUserPrepareANewReview() = runBlocking {
        val profile = """{"bench":{"board":"DE10_Nano","cable":"USB-Blaster [1-2]","transport":"quartus",
            "chainPosition":0,"fingerprint":"${"5".repeat(64)}","sofPart":"5CSEBA6U23I7"}}"""
        val artifact = FpgaImportedBitstream.read(DocumentAsset("factory.sof", "application/octet-stream",
            ByteArray(6_690_335))).artifact
        for (code in listOf("confirmation-required", "fpga-part-mismatch")) {
            val host = FakeHost(true, listOf("bench"), profiles = profile).apply { quartusSofBytes = QUARTUS_SOF_MAX_BYTES }
            val session = FpgaProgramSession()
            session.selectTarget("bench")
            session.prepare(host.client, artifact)
            val rejectedId = session.requestId
            host.binarySubmitRejection = code

            val failure = assertFailsWith<CompanionProtocolException> { session.submit(host.client) }
            assertEquals(code, failure.code)
            assertFalse(session.locked)
            assertFalse(session.submitAttempted)
            assertNull(session.review)
            assertNull(session.requestId)
            assertNull(session.job)
            assertEquals("bench", session.target)

            session.prepare(host.client, artifact)
            assertEquals(2, host.plans)
            assertTrue(session.requestId != rejectedId)
            session.submit(host.client)
            assertEquals(CompanionJobState.QUEUED, session.job?.state)
        }
    }

    @Test fun aTargetThatDisappearsAfterRefreshGivesALocalizedNotice() = runBlocking {
        val host = FakeHost(true, emptyList())
        val session = FpgaProgramSession()
        session.selectTarget("bench")
        val failure = assertFailsWith<dev.logisim.mobile.core.localization.LocalizedArgumentException> { session.prepare(host.client, artifact()) }
        assertEquals("fpga_program_target_unavailable_now", failure.text.key)
        assertFalse("Failed requirement" in failure.text.resolve(dev.logisim.mobile.core.localization.AppLanguage.PORTUGUESE))
        session.fail(failure)
        assertEquals("fpga_program_target_unavailable_now", session.notice?.key)
        // A withdrawn flash approval reads as guidance, not the host's English sentence.
        val flash = FakeHost(true, listOf("bench"), profiles = flashProfile, flashTargets = listOf("bench"))
        val flashing = FpgaProgramSession()
        flashing.refresh(flash.client)
        flashing.selectTarget("bench")
        flashing.selectFlash(true)
        flash.flashTargets = emptyList()
        val withdrawn = assertFailsWith<CompanionProtocolException> { flashing.prepare(flash.client, artifact()) }
        flashing.fail(withdrawn)
        assertEquals("fpga_failure_flash_not_allowed", flashing.notice?.key)
        assertEquals("fpga-flash-not-allowed: ${withdrawn.message}", flashing.noticeDetail)
        assertFalse("host" in flashing.notice!!.resolve(dev.logisim.mobile.core.localization.AppLanguage.PORTUGUESE))
    }

    private fun artifact() = CompanionArtifact.read(CompanionJsonCodec.parseObject(
        """{"name":"design.bin","size":3,"sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","base64":"YWJj"}"""))

    private class FakeHost(val allowed: Boolean, val targets: List<String>, val profiles: String = "{}", var flashTargets: List<String> = emptyList()) : CompanionTransport {
        var plans = 0
        var failFirstSubmit = false
        var binarySubmitRejection: String? = null
        var quartusSofBytes: Int? = null
        val submissions = mutableListOf<CompanionJsonObject>()
        val binarySubmissions = mutableListOf<CompanionHttpRequest>()
        val client = CompanionClient(CompanionConnection(CompanionEndpoint.parse("http://127.0.0.1:8042"), CompanionToken.parse("x".repeat(32))), this)
        override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse {
            val body = if (request.url.endsWith("/capabilities")) {
                val values = targets.joinToString(",") { "\"$it\"" }
                val flashValues = flashTargets.joinToString(",") { "\"$it\"" }
                val flashCapability = if (flashTargets.isEmpty()) "" else ""","flashProgramTargets":[$flashValues]"""
                val sofCapability = quartusSofBytes?.let { ""","quartusSofBytes":$it""" }.orEmpty()
                """{"protocolVersion":1,"operations":[{"name":"fpga-program","allowed":$allowed,"available":true,"requiresConfirmation":true,"description":"","missingTools":[],"toolAlternatives":[],"programTargets":[$values],"programTargetProfiles":$profiles$flashCapability}],"limits":{"requestBytes":6000000,"inputBytes":4000000,"outputBytes":4000000,"timeoutSeconds":30,"processMemoryBytes":1000000,"memoryEnforcement":"none","osSandbox":false$sofCapability}}"""
            } else if (request.url.endsWith("/fpga-sof/jobs") && request.method == "POST") {
                binarySubmissions += request
                binarySubmitRejection?.let { code ->
                    binarySubmitRejection = null
                    return CompanionHttpResponse(400, """{"error":{"code":"$code","message":"Review rejected."}}""".encodeToByteArray(), "application/json")
                }
                if (failFirstSubmit && binarySubmissions.size == 1) throw CompanionTransportException("fixture-lost-response")
                """{"jobId":"${"a".repeat(32)}","operation":"fpga-program","state":"queued","logs":[]}"""
            } else if (request.url.endsWith("/jobs") && request.method == "POST") {
                submissions += CompanionJsonCodec.parseObject(requireNotNull(request.body).decodeToString())
                if (failFirstSubmit && submissions.size == 1) throw CompanionTransportException("fixture-lost-response")
                """{"jobId":"${"a".repeat(32)}","operation":"fpga-program","state":"queued","logs":[]}"""
            } else if (request.method == "DELETE") {
                """{"jobId":"${"a".repeat(32)}","operation":"fpga-program","state":"cancelled","logs":[]}"""
            } else {
                plans++
                val envelope = CompanionJsonCodec.parseObject(requireNotNull(request.body).decodeToString())
                val input = envelope.array("inputs").single() as CompanionJsonObject
                val planned = CompanionJsonObject(mapOf("name" to input.fields.getValue("name"), "sha256" to input.fields.getValue("sha256"),
                    "size" to (input.fields["size"] ?: CompanionJsonNumber(3L))))
                CompanionJsonCodec.write(CompanionJsonObject(mapOf("operation" to CompanionJsonString("fpga-program"),
                    "parameters" to envelope.obj("parameters"), "inputs" to CompanionJsonArray(listOf(planned)),
                    "requiresConfirmation" to CompanionJsonBoolean(true), "expiresInSeconds" to CompanionJsonNumber(30L), "planToken" to CompanionJsonString("fixture"))))
            }
            return CompanionHttpResponse(200, body.encodeToByteArray(), "application/json")
        }
    }

}
