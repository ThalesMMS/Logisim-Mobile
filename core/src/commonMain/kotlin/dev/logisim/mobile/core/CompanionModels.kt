package dev.logisim.mobile.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class CompanionEndpoint private constructor(val url: String) {
    override fun toString(): String = url
    companion object {
        fun parse(value: String): CompanionEndpoint {
            val match = Regex("(https?)://(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+)(:[0-9]+)?/?").matchEntire(value.trim())
                ?: throw IllegalArgumentException("Use an HTTP(S) host without credentials, query, fragment or path.")
            val scheme = match.groupValues[1]
            val host = match.groupValues[2].lowercase()
            val port = match.groupValues[3].removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull()
            require(match.groupValues[3].isEmpty() || port in 1..65535) { "Invalid companion port." }
            require(scheme == "https" || host in setOf("127.0.0.1", "[::1]")) {
                "HTTP is permitted only for literal 127.0.0.1 or [::1]. Use HTTPS for every other host."
            }
            return CompanionEndpoint("$scheme://$host${match.groupValues[3]}")
        }
    }
}

class CompanionToken private constructor(internal val value: String) {
    override fun toString(): String = "CompanionToken([redacted])"
    companion object {
        fun parse(value: String): CompanionToken {
            require(value.length in 32..4096 && value.all { it.code in 33..126 }) { "Use a companion token of at least 32 printable ASCII characters." }
            return CompanionToken(value)
        }
    }
}

class CompanionConnection(val endpoint: CompanionEndpoint, internal val token: CompanionToken) {
    override fun toString(): String = "CompanionConnection(endpoint=$endpoint, token=[redacted])"
}

@OptIn(ExperimentalEncodingApi::class)
class CompanionInput private constructor(val name: String, private val content: ByteArray) {
    val size: Int get() = content.size
    val sha256: String = companionSha256(content)
    internal fun envelope() = CompanionJsonObject(mapOf("name" to CompanionJsonString(name),
        "sha256" to CompanionJsonString(sha256), "base64" to CompanionJsonString(Base64.Default.encode(content))))
    override fun toString(): String = "CompanionInput(name=$name, bytes=$size)"
    companion object {
        fun fromBytes(name: String, bytes: ByteArray): CompanionInput {
            require(companionFileName.matches(name)) { "Use a plain input file name." }
            require(bytes.size <= 4 * 1024 * 1024) { "Input exceeds 4 MiB." }
            return CompanionInput(name, bytes.copyOf())
        }
    }
}

data class CompanionJobRequest(
    val operation: String,
    val inputs: List<CompanionInput> = emptyList(),
    val parameters: Map<String, CompanionJson> = emptyMap(),
) {
    init {
        require(Regex("[a-z][a-z0-9.-]{0,63}").matches(operation)) { "Invalid companion operation." }
        require(inputs.size <= 64 && inputs.map { it.name }.distinct().size == inputs.size) { "Input names must be unique, with at most 64 files." }
        require(inputs.sumOf { it.size.toLong() } <= 4 * 1024 * 1024) { "Inputs exceed 4 MiB." }
    }
    internal fun envelope(): CompanionJsonObject = CompanionJsonObject(mapOf(
        "operation" to CompanionJsonString(operation), "inputs" to CompanionJsonArray(inputs.map { it.envelope() }),
        "parameters" to CompanionJsonObject(parameters)))
    internal fun fingerprint(): String = companionSha256(CompanionJsonCodec.write(envelope()).encodeToByteArray())
    override fun toString(): String = "CompanionJobRequest(operation=$operation, files=${inputs.size})"
}

data class CompanionOperationCapability(
    val name: String, val allowed: Boolean, val available: Boolean,
    val missingTools: List<String>, val toolAlternatives: List<CompanionToolAlternative>,
    val requiresConfirmation: Boolean, val description: String,
    val programTargets: List<String> = emptyList(),
    val programTargetProfiles: Map<String, CompanionProgramTargetProfile> = emptyMap(),
    val flashProgramTargets: List<String> = emptyList(),
)
data class CompanionProgramTargetProfile(
    val board: String,
    val cable: String,
    val transport: String,
    val chainPosition: Int?,
    val fingerprint: String,
    val unavailableReason: String?,
    val flashDevice: String? = null,
    val flashChainPosition: Int? = null,
    val part: String? = null,
    val sofPart: String? = null,
)
data class CompanionToolAlternative(val tools: List<String>, val available: Boolean)
data class CompanionLimits(
    val requestBytes: Long, val inputBytes: Long, val outputBytes: Long, val timeoutSeconds: Double,
    val processMemoryBytes: Long, val memoryEnforcement: String, val osSandbox: Boolean,
    val retainedJobs: Long? = null, val requestReceipts: Long? = null, val requestSeconds: Double? = null,
    val quartusSofBytes: Long? = null,
)
data class CompanionCapabilities(val protocolVersion: Int, val operations: List<CompanionOperationCapability>, val limits: CompanionLimits)
data class CompanionPlannedInput(val name: String, val sha256: String, val size: Long)

class CompanionPlan internal constructor(
    val operation: String,
    val parameters: CompanionJsonObject,
    val inputs: List<CompanionPlannedInput>,
    val requiresConfirmation: Boolean,
    val expiresInSeconds: Double,
    private val planToken: String,
    private val requestFingerprint: String,
    val osSandbox: String? = null,
) {
    /** Call only from the user's explicit confirmation action. */
    fun confirm(): CompanionApproval = CompanionApproval(planToken, requestFingerprint)
    override fun toString(): String = "CompanionPlan(operation=$operation, files=${inputs.size}, token=[redacted])"
}
class CompanionApproval internal constructor(internal val token: String, internal val requestFingerprint: String) {
    override fun toString(): String = "CompanionApproval([redacted])"
}

enum class CompanionJobState(val wireName: String) {
    QUEUED("queued"), RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled");
    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}
data class CompanionJobError(val code: String, val message: String, val details: CompanionJson) {
    override fun toString(): String = "CompanionJobError(code=$code)"
}
data class CompanionJobLog(val stream: String, val text: String) {
    override fun toString(): String = "CompanionJobLog(stream=$stream, characters=${text.length})"
}
class CompanionJob(
    val jobId: String, val operation: String, val state: CompanionJobState,
    val result: CompanionJson, val error: CompanionJobError?, val logs: List<CompanionJobLog>,
    val live: CompanionJsonObject? = null,
) {
    fun artifact(value: CompanionJson, maxBytes: Int = 2 * 1024 * 1024): CompanionArtifact = CompanionArtifact.read(value, maxBytes)
    override fun toString(): String = "CompanionJob(id=$jobId, operation=$operation, state=$state)"
}

const val QUARTUS_SOF_MAX_BYTES: Int = 8 * 1024 * 1024

@OptIn(ExperimentalEncodingApi::class)
class CompanionArtifact private constructor(
    val name: String,
    val sha256: String,
    private val content: ByteArray,
    val quartusSofPart: String? = null,
) {
    val size: Int get() = content.size
    val bytes: ByteArray get() = content.copyOf()
    override fun toString(): String = "CompanionArtifact(name=$name, bytes=$size)"
    companion object {
        fun fromBytes(name: String, bytes: ByteArray): CompanionArtifact {
            require(companionFileName.matches(name) && bytes.size <= 4 * 1024 * 1024)
            val content = bytes.copyOf()
            return CompanionArtifact(name, companionSha256(content), content)
        }

        fun fromQuartusSof(name: String, bytes: ByteArray): CompanionArtifact {
            require(companionFileName.matches(name) && name.endsWith(".sof", ignoreCase = true))
            require(bytes.size in 1..QUARTUS_SOF_MAX_BYTES)
            val content = bytes.copyOf()
            return CompanionArtifact(name, companionSha256(content), content)
        }

        internal fun verifiedQuartusSof(bytes: ByteArray, sha256: String, part: String): CompanionArtifact {
            val artifact = fromQuartusSof("design.sof", bytes)
            if (artifact.sha256 != sha256) {
                throw CompanionProtocolException("invalid-artifact", "The Quartus SOF SHA-256 does not match.")
            }
            return CompanionArtifact(artifact.name, artifact.sha256, artifact.content, part)
        }

        fun read(value: CompanionJson, maxBytes: Int = 2 * 1024 * 1024): CompanionArtifact {
            require(maxBytes in 0..8 * 1024 * 1024)
            val objectValue = value as? CompanionJsonObject ?: throw CompanionProtocolException("invalid-artifact", "Expected an artifact object.")
            val name = objectValue.string("name")
            val hash = objectValue.string("sha256")
            val size = objectValue.long("size")
            val encoded = objectValue.string("base64")
            if (!companionFileName.matches(name) || !Regex("[a-f0-9]{64}").matches(hash) || size !in 0..maxBytes.toLong() ||
                encoded.length.toLong() > ((maxBytes.toLong() + 2) / 3) * 4) {
                throw CompanionProtocolException("invalid-artifact", "Artifact metadata or size is invalid.")
            }
            val bytes = try { Base64.Default.decode(encoded) } catch (_: IllegalArgumentException) {
                throw CompanionProtocolException("invalid-artifact", "Artifact base64 is invalid.")
            }
            if (bytes.size.toLong() != size || Base64.Default.encode(bytes) != encoded || companionSha256(bytes) != hash) {
                throw CompanionProtocolException("invalid-artifact", "Artifact length or SHA-256 does not match.")
            }
            return CompanionArtifact(name, hash, bytes)
        }
    }
}

internal val companionFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
