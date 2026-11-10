package dev.logisim.mobile.core

private const val JSON_RESPONSE_MAX_BYTES = 8 * 1024 * 1024
private const val INLINE_INPUT_MAX_BYTES = 4 * 1024 * 1024
private const val BINARY_CONTENT_TYPE = "application/octet-stream"
private val quartusSofPartPattern = Regex("[A-Z0-9][A-Z0-9-]{0,63}")
private val companionHashPattern = Regex("[a-f0-9]{64}")
private val companionRequestIdPattern = Regex("[A-Za-z0-9._-]{1,64}")

class CompanionClient(
    private val connection: CompanionConnection,
    private val transport: CompanionTransport = createCompanionTransport(),
    private val timeoutMillis: Long = 30_000,
) {
    init { require(timeoutMillis in 1..120_000) }

    suspend fun capabilities(): CompanionCapabilities {
        val response = exchange("GET", "/v1/capabilities")
        val version = response.long("protocolVersion")
        if (version != 1L) throw CompanionProtocolException("protocol-version", "The companion protocol version is unsupported.")
        val operations = response.array("operations").map { value ->
            val operation = value.asObject()
            val targets = operation.programTargets("programTargets")
            val flashTargets = operation.programTargets("flashProgramTargets")
            val profiles = operation.programTargetProfiles(targets)
            if (flashTargets.any { profiles[it]?.supportsFlash() != true }) {
                throw CompanionProtocolException("invalid-response", "Flash targets require a supported explicit programming profile.")
            }
            CompanionOperationCapability(operation.string("name"), operation.boolean("allowed"), operation.boolean("available"),
                operation.array("missingTools").map { it.asString() }, operation.array("toolAlternatives").map {
                    val alternative = it.asObject()
                    CompanionToolAlternative(alternative.array("tools").map { tool -> tool.asString() }, alternative.boolean("available"))
                }, operation.boolean("requiresConfirmation"), operation.string("description"), targets,
                profiles, flashTargets)
        }
        val limits = response.obj("limits")
        val parsedLimits = CompanionLimits(limits.long("requestBytes"), limits.long("inputBytes"), limits.long("outputBytes"),
            limits.number("timeoutSeconds"), limits.long("processMemoryBytes"), limits.string("memoryEnforcement"), limits.boolean("osSandbox"),
            limits.fields["retainedJobs"]?.let { limits.long("retainedJobs") },
            limits.fields["requestReceipts"]?.let { limits.long("requestReceipts") },
            limits.fields["requestSeconds"]?.let { limits.number("requestSeconds") },
            limits.fields["quartusSofBytes"]?.let { limits.long("quartusSofBytes") })
        if (parsedLimits.requestBytes <= 0 || parsedLimits.inputBytes < 0 || parsedLimits.outputBytes <= 0 || parsedLimits.timeoutSeconds <= 0 ||
            parsedLimits.processMemoryBytes <= 0 || parsedLimits.retainedJobs?.let { it <= 0 } == true ||
            parsedLimits.requestReceipts?.let { it <= 0 } == true || parsedLimits.requestSeconds?.let { it <= 0 } == true ||
            parsedLimits.quartusSofBytes?.let { it != QUARTUS_SOF_MAX_BYTES.toLong() } == true) {
            throw CompanionProtocolException("invalid-response", "The companion returned invalid resource limits.")
        }
        return CompanionCapabilities(1, operations, parsedLimits)
    }

    suspend fun plan(request: CompanionJobRequest): CompanionPlan {
        val response = exchange("POST", "/v1/plans", request.envelope())
        val expected = request.inputs.map { CompanionPlannedInput(it.name, it.sha256, it.size.toLong()) }
        return readPlan(response, request.operation, expected, CompanionJsonObject(request.parameters), request::fingerprint)
    }

    suspend fun planLargeQuartusSof(
        artifact: CompanionArtifact, target: String, profile: CompanionProgramTargetProfile,
    ): CompanionPlan {
        val envelope = largeSofPlanEnvelope(artifact, target, profile)
        val response = exchange("POST", "/v1/fpga-sof/plans", envelope)
        val expected = listOf(CompanionPlannedInput(artifact.name, artifact.sha256, artifact.size.toLong()))
        val plan = readPlan(response, "fpga-program", expected, envelope.obj("parameters")) { largeSofFingerprint(envelope) }
        if (!plan.requiresConfirmation || !plan.expiresInSeconds.isFinite() || !isHeaderPlanToken(response.string("planToken"))) {
            throw CompanionProtocolException("invalid-plan", "The companion did not provide a usable Quartus SOF confirmation.")
        }
        return plan
    }

    private fun readPlan(
        response: CompanionJsonObject,
        operation: String,
        expectedInputs: List<CompanionPlannedInput>,
        expectedParameters: CompanionJsonObject,
        fingerprint: () -> String,
    ): CompanionPlan {
        if (response.string("operation") != operation) throw CompanionProtocolException("invalid-plan", "The operation in the plan does not match.")
        val inputs = response.array("inputs").map {
            val input = it.asObject()
            CompanionPlannedInput(input.string("name"), input.string("sha256"), input.long("size"))
        }
        val parameters = response.obj("parameters")
        if (inputs.sortedBy { it.name } != expectedInputs.sortedBy { it.name } || !parameters.sameValue(expectedParameters)) {
            throw CompanionProtocolException("invalid-plan", "The plan does not match the submitted files and parameters.")
        }
        val expires = response.number("expiresInSeconds")
        val token = response.string("planToken")
        if (expires <= 0 || token.isBlank() || token.length > 4096) throw CompanionProtocolException("invalid-plan", "The plan has no valid confirmation token.")
        return CompanionPlan(operation, parameters, inputs, response.boolean("requiresConfirmation"),
            expires, token, fingerprint(),
            response.fields["osSandbox"]?.let { response.string("osSandbox") })
    }

    suspend fun submit(request: CompanionJobRequest, approval: CompanionApproval? = null, requestId: String? = null): CompanionJob {
        if (approval != null && approval.requestFingerprint != request.fingerprint()) {
            throw CompanionProtocolException("approval-mismatch", "The files or parameters changed after confirmation.")
        }
        require(requestId == null || companionRequestIdPattern.matches(requestId)) { "Invalid retry identifier." }
        val fields = request.envelope().fields.toMutableMap()
        approval?.let { fields["planToken"] = CompanionJsonString(it.token) }
        requestId?.let { fields["requestId"] = CompanionJsonString(it) }
        return job(exchange("POST", "/v1/jobs", CompanionJsonObject(fields)))
    }

    suspend fun submitLargeQuartusSof(
        artifact: CompanionArtifact,
        target: String,
        profile: CompanionProgramTargetProfile,
        approval: CompanionApproval,
        requestId: String,
    ): CompanionJob {
        val envelope = largeSofPlanEnvelope(artifact, target, profile)
        if (approval.requestFingerprint != largeSofFingerprint(envelope) || !isHeaderPlanToken(approval.token)) {
            throw CompanionProtocolException("approval-mismatch", "The Quartus SOF or target changed after confirmation.")
        }
        require(companionRequestIdPattern.matches(requestId)) { "Invalid retry identifier." }
        val response = execute("POST", "/v1/fpga-sof/jobs", artifact.bytes, BINARY_CONTENT_TYPE, "application/json",
            JSON_RESPONSE_MAX_BYTES, mapOf(
                "X-Logisim-Sof-Name" to artifact.name,
                "X-Logisim-Sof-Sha256" to artifact.sha256,
                "X-Logisim-Fpga-Target" to target,
                "X-Logisim-Fpga-Profile" to profile.fingerprint,
                "X-Logisim-Plan-Token" to approval.token,
                "X-Logisim-Request-Id" to requestId,
            ))
        val submitted = job(readJson(response))
        if (submitted.operation != "fpga-program") {
            throw CompanionProtocolException("invalid-response", "The companion returned a different programming operation.")
        }
        return submitted
    }

    suspend fun downloadQuartusSof(job: CompanionJob, value: CompanionJson, expectedPart: String): CompanionArtifact {
        validateJobId(job.jobId)
        val reviewedPart = expectedPart.uppercase()
        if (job.operation != "fpga-quartus-build" || job.state != CompanionJobState.SUCCEEDED ||
            !quartusSofPartPattern.matches(reviewedPart)) {
            throw CompanionProtocolException("invalid-artifact", "A Quartus SOF requires a completed build for the reviewed part.")
        }
        val result = job.result.asObject()
        val descriptor = value.asObject()
        val size = descriptor.long("size")
        val sha256 = descriptor.string("sha256")
        if (result.string("part") != reviewedPart || result.array("artifacts").singleOrNull() != descriptor ||
            descriptor.fields.keys != setOf("name", "size", "sha256", "transport") ||
            descriptor.string("name") != "design.sof" || descriptor.string("transport") != "binary" ||
            size !in (FPGA_VENDOR_ARTIFACT_MAX_BYTES + 1L)..QUARTUS_SOF_MAX_BYTES.toLong() ||
            !companionHashPattern.matches(sha256)) {
            throw CompanionProtocolException("invalid-artifact", "The Quartus SOF descriptor does not match the reviewed build.")
        }
        val response = execute("GET", "/v1/jobs/${job.jobId}/artifacts/design.sof", null,
            BINARY_CONTENT_TYPE, BINARY_CONTENT_TYPE, size.toInt())
        if (response.status !in 200..299) readJson(response)
        if (response.status != 200 || response.contentType.mediaType() != BINARY_CONTENT_TYPE) {
            throw CompanionProtocolException("response-type", "The companion did not return a complete binary SOF.", response.status)
        }
        if (response.body.size.toLong() != size) {
            throw CompanionProtocolException("invalid-artifact", "The Quartus SOF length does not match.")
        }
        return CompanionArtifact.verifiedQuartusSof(response.body, sha256, reviewedPart)
    }

    suspend fun status(jobId: String): CompanionJob { validateJobId(jobId); return job(exchange("GET", "/v1/jobs/$jobId")) }
    suspend fun cancel(jobId: String): CompanionJob { validateJobId(jobId); return job(exchange("DELETE", "/v1/jobs/$jobId")) }

    internal suspend fun sendJobInput(jobId: String, input: CompanionJsonObject, sequence: Long) {
        validateJobId(jobId)
        val receipt = exchange("POST", "/v1/jobs/$jobId/input", input)
        if (!receipt.boolean("accepted") || receipt.long("sequence") != sequence) {
            throw CompanionProtocolException("invalid-live-receipt", "The live input receipt did not match.")
        }
    }

    internal suspend fun sendTkInput(jobId: String, sequence: Int, input: TclTkInput) {
        validateJobId(jobId)
        require(sequence in 1..8)
        val event = when (input) {
            is TclTkInput.Tap -> mapOf("type" to CompanionJsonString("tap"), "x" to CompanionJsonNumber(input.x.toLong()),
                "y" to CompanionJsonNumber(input.y.toLong()))
            is TclTkInput.Key -> mapOf("type" to CompanionJsonString("key"),
                "key" to CompanionJsonString(if (input.character == ' ') "space" else input.character.toString()))
            is TclTkInput.Control -> mapOf("type" to CompanionJsonString("key"), "key" to CompanionJsonString(input.name))
        }
        val response = exchange("POST", "/v1/jobs/$jobId/tk-input", CompanionJsonObject(event + ("sequence" to CompanionJsonNumber(sequence.toLong()))))
        if (response.long("sequence") != sequence.toLong()) {
            throw CompanionProtocolException("tk-input-order", "The companion acknowledged a different Tk input.")
        }
    }

    private suspend fun exchange(method: String, path: String, envelope: CompanionJsonObject? = null): CompanionJsonObject {
        val body = envelope?.let { CompanionJsonCodec.write(it).encodeToByteArray() }
        if ((body?.size ?: 0) > 6 * 1024 * 1024) throw CompanionProtocolException("request-limit", "The request exceeds 6 MiB.")
        return readJson(execute(method, path, body, "application/json; charset=utf-8", "application/json", JSON_RESPONSE_MAX_BYTES))
    }

    private suspend fun execute(
        method: String, path: String, body: ByteArray?, contentType: String, accept: String,
        maxResponseBytes: Int, extraHeaders: Map<String, String> = emptyMap(),
    ): CompanionHttpResponse {
        val headers = mapOf("Authorization" to "Bearer ${connection.token.value}", "Content-Type" to contentType,
            "Accept" to accept, "Cache-Control" to "no-store") + extraHeaders
        val response = transport.execute(CompanionHttpRequest(connection.endpoint.url + path, method,
            headers, body, timeoutMillis, maxResponseBytes))
        if (response.body.size > maxResponseBytes) throw CompanionProtocolException("response-limit", "The response exceeds the client limit.")
        if (response.status in 300..399) throw CompanionProtocolException("redirect-denied", "Companion redirects are not followed.", response.status)
        return response
    }

    private fun readJson(response: CompanionHttpResponse): CompanionJsonObject {
        if (response.contentType.mediaType() != "application/json") {
            throw CompanionProtocolException("response-type", "The companion did not return JSON.", response.status)
        }
        val decoded = try { response.body.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) {
            throw CompanionProtocolException("invalid-json", "The companion returned invalid UTF-8.", response.status)
        }
        val result = CompanionJsonCodec.parseObject(decoded)
        if (response.status !in 200..299) {
            val error = result.obj("error")
            throw CompanionProtocolException(error.string("code"), redact(error.string("message")), response.status,
                error.fields["details"] ?: CompanionJsonNull)
        }
        return result
    }

    private fun job(response: CompanionJsonObject): CompanionJob {
        val id = response.string("jobId"); validateJobId(id)
        val state = CompanionJobState.entries.firstOrNull { it.wireName == response.string("state") }
            ?: throw CompanionProtocolException("invalid-response", "Unknown companion job state.")
        val rawError = response.fields["error"]
        val error = if (rawError == null || rawError == CompanionJsonNull) null else rawError.asObject().let {
            CompanionJobError(it.string("code"), redact(it.string("message")), it.fields["details"] ?: CompanionJsonNull)
        }
        return CompanionJob(id, response.string("operation"), state, response.fields["result"] ?: CompanionJsonNull, error,
            response.array("logs").map { val log = it.asObject(); CompanionJobLog(log.string("stream"), redact(log.string("text"))) },
            response.fields["live"]?.let { response.obj("live") })
    }
    private fun redact(text: String): String = text.replace(connection.token.value, "[redacted]")
    private fun validateJobId(id: String) {
        if (!Regex("[a-f0-9]{32}").matches(id)) throw CompanionProtocolException("invalid-job-id", "Invalid companion job identifier.")
    }
}

private val programTargetAlias = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private fun largeSofPlanEnvelope(
    artifact: CompanionArtifact, target: String, profile: CompanionProgramTargetProfile,
): CompanionJsonObject {
    require(artifact.name.endsWith(".sof", ignoreCase = true) &&
        artifact.size in (INLINE_INPUT_MAX_BYTES + 1)..QUARTUS_SOF_MAX_BYTES) { "Provide a large Quartus SOF." }
    require(programTargetAlias.matches(target) && profile.transport == "quartus" && profile.unavailableReason == null &&
        profile.chainPosition?.let { it in 0..63 } == true && companionHashPattern.matches(profile.fingerprint) &&
        profile.sofPart?.let(quartusSofPartPattern::matches) == true) { "Select an approved Quartus SOF target." }
    require(artifact.quartusSofPart == null || artifact.quartusSofPart == profile.sofPart) {
        "The Quartus SOF does not match the selected part."
    }
    return CompanionJsonObject(mapOf(
        "operation" to CompanionJsonString("fpga-program"),
        "inputs" to CompanionJsonArray(listOf(CompanionJsonObject(mapOf(
            "name" to CompanionJsonString(artifact.name),
            "size" to CompanionJsonNumber(artifact.size.toLong()),
            "sha256" to CompanionJsonString(artifact.sha256),
        )))),
        "parameters" to CompanionJsonObject(mapOf(
            "target" to CompanionJsonString(target),
            "bitstream" to CompanionJsonString(artifact.name),
            "profile" to CompanionJsonString(profile.fingerprint),
            "flash" to CompanionJsonBoolean(false),
        )),
    ))
}

private fun largeSofFingerprint(envelope: CompanionJsonObject): String =
    companionSha256(("fpga-sof\n" + CompanionJsonCodec.write(envelope)).encodeToByteArray())

private fun isHeaderPlanToken(token: String): Boolean = token.length in 1..256 && token.all { it.code in 33..126 }

private fun String?.mediaType(): String? = this?.substringBefore(';')?.trim()?.lowercase()

private fun CompanionJsonObject.programTargets(field: String): List<String> {
    if (string("name") != "fpga-program" || field !in fields) return emptyList()
    val targets = array(field).map { it.asString() }
    if (targets.size > 32 || targets.distinct().size != targets.size || targets.any { !programTargetAlias.matches(it) }) {
        throw CompanionProtocolException("invalid-response", "Invalid FPGA program target aliases.")
    }
    return targets
}

private fun CompanionJsonObject.programTargetProfiles(targets: List<String>): Map<String, CompanionProgramTargetProfile> {
    if ("programTargetProfiles" !in fields) return emptyMap()
    val profiles = obj("programTargetProfiles").fields
    if (profiles.keys.any { it !in targets })
        throw CompanionProtocolException("invalid-response", "Unknown FPGA program target profile.")
    return profiles.mapValues { (_, raw) ->
        val value = raw.asObject()
        val position = value.fields["chainPosition"]?.let { value.long("chainPosition") }
        val flashPosition = value.fields["flashChainPosition"]?.let { value.long("flashChainPosition") }
        val flashDevice = value.fields["flashDevice"]?.asString()
        val part = value.fields["part"]?.asString()
        val sofPart = value.fields["sofPart"]?.asString()
        val reason = value.fields["unavailableReason"]?.let { if (it == CompanionJsonNull) null else it.asString() }
        val fingerprint = value.string("fingerprint")
        val transport = value.string("transport")
        if (position != null && position !in 0L..63L || flashPosition != null && flashPosition !in 0L..63L ||
            part != null && !Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(part) ||
            !companionHashPattern.matches(fingerprint) ||
            sofPart != null && (transport != "quartus" || position == null || !quartusSofPartPattern.matches(sofPart)))
            throw CompanionProtocolException("invalid-response", "Invalid FPGA program target profile.")
        CompanionProgramTargetProfile(value.string("board"), value.string("cable"), transport,
            position?.toInt(), fingerprint, reason, flashDevice, flashPosition?.toInt(), part = part, sofPart = sofPart)
    }
}

private fun CompanionProgramTargetProfile.supportsFlash(): Boolean =
    chainPosition != null && when (transport) {
        "openfpgaloader" -> flashDevice == null && flashChainPosition == null
        "ise" -> board == "BASYS2" && flashDevice == "XCF02S" &&
            flashChainPosition != null && flashChainPosition != chainPosition
        "quartus" -> (board == "GECKO4_EDUCATION" || board == "GECKO4_EDUCATION_EPFL_EDITION") &&
            chainPosition == 0 && flashDevice == "EPCQ16" && flashChainPosition == null
        else -> false
    }

private fun CompanionJson.asObject(): CompanionJsonObject = this as? CompanionJsonObject
    ?: throw CompanionProtocolException("invalid-response", "Expected a JSON object.")
private fun CompanionJson.asString(): String = (this as? CompanionJsonString)?.value
    ?: throw CompanionProtocolException("invalid-response", "Expected a JSON string.")
private fun CompanionJsonObject.number(name: String): Double = (fields[name] as? CompanionJsonNumber)?.value?.toDoubleOrNull()
    ?: throw CompanionProtocolException("invalid-response", "Expected a JSON number: $name")
