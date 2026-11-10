package dev.logisim.mobile.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun createCompanionTransport(): CompanionTransport = JvmCompanionTransport()
internal actual fun companionSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private class JvmCompanionTransport : CompanionTransport {
    override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse =
        withTimeoutOrNull(request.timeoutMillis) { executeRequest(request) } ?: throw CompanionTransportException("timeout")

    private suspend fun executeRequest(request: CompanionHttpRequest): CompanionHttpResponse = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = executor.submit {
            try {
                val connection = URI(request.url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
                active.set(connection)
                if (!continuation.isActive) { connection.disconnect(); return@submit }
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = request.timeoutMillis.toInt()
                    connection.readTimeout = request.timeoutMillis.toInt()
                    connection.requestMethod = request.method
                    request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    request.body?.let { bytes ->
                        if (!continuation.isActive || Thread.currentThread().isInterrupted) return@submit
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    if (!continuation.isActive || Thread.currentThread().isInterrupted) return@submit
                    val status = connection.responseCode
                    if (connection.contentLengthLong > request.maxResponseBytes) throw CompanionTransportException("response-limit")
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val buffer = ByteArrayOutputStream()
                    stream?.use { input ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            if (!continuation.isActive || Thread.currentThread().isInterrupted) return@submit
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (buffer.size().toLong() + count > request.maxResponseBytes) throw CompanionTransportException("response-limit")
                            buffer.write(chunk, 0, count)
                        }
                    }
                    if (continuation.isActive) continuation.resume(CompanionHttpResponse(status, buffer.toByteArray(), connection.contentType))
                } finally { active.compareAndSet(connection, null); connection.disconnect() }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(when (error) {
                    is CompanionTransportException -> error
                    is SocketTimeoutException -> CompanionTransportException("timeout")
                    else -> CompanionTransportException("network-failure")
                })
            }
        }
        continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
    companion object {
        private val executor = Executors.newFixedThreadPool(4) { command -> Thread(command, "logisim-companion-http").apply { isDaemon = true } }
    }
}
