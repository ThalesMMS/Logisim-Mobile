package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedFailure
import dev.logisim.mobile.core.localization.LocalizedText

class CompanionHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMillis: Long,
    val maxResponseBytes: Int,
) {
    init {
        require(method in setOf("GET", "POST", "DELETE"))
        val split = url.indexOf("/v1/")
        require(split > 0 && Regex("/v1/[A-Za-z0-9/.-]+").matches(url.substring(split)))
        CompanionEndpoint.parse(url.substring(0, split))
        require(timeoutMillis in 1..120_000 && maxResponseBytes in 1..8 * 1024 * 1024)
        require(headers.all { (key, value) -> Regex("[A-Za-z0-9-]+").matches(key) &&
            value.length <= 8192 && value.none { it == '\r' || it == '\n' } })
    }
    override fun toString(): String = "CompanionHttpRequest(method=$method, bodyBytes=${body?.size ?: 0})"
}

class CompanionHttpResponse(val status: Int, val body: ByteArray, val contentType: String?) {
    override fun toString(): String = "CompanionHttpResponse(status=$status, bodyBytes=${body.size})"
}

interface CompanionTransport { suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse }
expect fun createCompanionTransport(): CompanionTransport
internal expect fun companionSha256(bytes: ByteArray): String

class CompanionTransportException(val code: String) : Exception("Companion transport failed: $code")

class CompanionProtocolException private constructor(
    val code: String,
    override val message: String,
    val httpStatus: Int?,
    val details: CompanionJson,
    override val text: LocalizedText,
) : Exception(message), LocalizedFailure {
    constructor(code: String, message: String, httpStatus: Int? = null, details: CompanionJson = CompanionJsonNull) :
        this(code, message, httpStatus, details, LocalizedText.literal(message))

    constructor(code: String, text: LocalizedText, httpStatus: Int? = null, details: CompanionJson = CompanionJsonNull) :
        this(code, text.resolve(), httpStatus, details, text)

    override fun toString(): String = "CompanionProtocolException(code=$code, status=$httpStatus)"
}
