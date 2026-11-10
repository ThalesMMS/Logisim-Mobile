@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.logisim.mobile.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSCachedURLResponse
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.dataWithBytes
import platform.Foundation.setAllHTTPHeaderFields
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun createCompanionTransport(): CompanionTransport = AppleCompanionTransport()

internal actual fun companionSha256(bytes: ByteArray): String {
    val digest = ByteArray(32)
    digest.usePinned { target ->
        if (bytes.isEmpty()) CC_SHA256(null, 0u, target.addressOf(0).reinterpret<UByteVar>())
        else bytes.usePinned { source -> CC_SHA256(source.addressOf(0), bytes.size.toUInt(), target.addressOf(0).reinterpret<UByteVar>()) }
    }
    return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

private class AppleCompanionTransport : CompanionTransport {
    override suspend fun execute(request: CompanionHttpRequest): CompanionHttpResponse =
        withTimeoutOrNull(request.timeoutMillis) { executeRequest(request) } ?: throw CompanionTransportException("timeout")

    private suspend fun executeRequest(request: CompanionHttpRequest): CompanionHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val delegate = CompanionSessionDelegate(request, continuation)
            continuation.invokeOnCancellation { delegate.cancel() }
            delegate.start()
        }
}

private class CompanionSessionDelegate(
    private val request: CompanionHttpRequest,
    private val continuation: CancellableContinuation<CompanionHttpResponse>,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val lock = NSLock()
    private var finished = false
    private var received = 0
    private var buffer = ByteArray(0)
    private var status: Int? = null
    private var contentType: String? = null
    private var activeSession: NSURLSession? = null
    private var activeTask: NSURLSessionDataTask? = null

    fun start() {
        if (locked { finished }) return
        try {
            val url = NSURL.URLWithString(request.url)
                ?: throw CompanionTransportException("invalid-url")
            val nativeRequest = NSMutableURLRequest.requestWithURL(url,
                NSURLRequestReloadIgnoringLocalCacheData, request.timeoutMillis / 1000.0)
            nativeRequest.setHTTPMethod(request.method)
            nativeRequest.setAllHTTPHeaderFields(buildMap<Any?, String> { putAll(request.headers) })
            request.body?.let { bytes ->
                nativeRequest.setHTTPBody(if (bytes.isEmpty()) NSData() else bytes.usePinned {
                    NSData.dataWithBytes(bytes = it.addressOf(0), length = bytes.size.toULong())
                })
            }
            val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
                setURLCache(null)
                setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                setHTTPShouldSetCookies(false)
                setHTTPCookieStorage(null)
                setURLCredentialStorage(null)
                setConnectionProxyDictionary(emptyMap<Any?, Any?>())
                setTimeoutIntervalForRequest(request.timeoutMillis / 1000.0)
                setTimeoutIntervalForResource(request.timeoutMillis / 1000.0)
                setHTTPMaximumConnectionsPerHost(1)
            }
            val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
            val session = NSURLSession.sessionWithConfiguration(configuration, this, queue)
            val task = session.dataTaskWithRequest(nativeRequest)
            val cancelled = locked {
                if (finished) true else {
                    activeSession = session
                    activeTask = task
                    false
                }
            }
            if (cancelled) session.invalidateAndCancel() else task.resume()
        } catch (_: Throwable) {
            complete(Result.failure(CompanionTransportException("transport-configuration")))
        }
    }

    fun cancel() {
        val resources = locked {
            if (finished) return
            finished = true
            buffer = ByteArray(0)
            val task = activeTask
            activeTask = null
            (task to activeSession).also { activeSession = null }
        }
        resources.first?.cancel()
        resources.second?.invalidateAndCancel()
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        completionHandler(null)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val response = didReceiveResponse as? NSHTTPURLResponse
        if (response == null || response.expectedContentLength > request.maxResponseBytes.toLong()) {
            completionHandler(NSURLSessionResponseCancel)
            complete(Result.failure(CompanionTransportException(
                if (response == null) "invalid-http-response" else "response-limit")))
            return
        }
        val stopped = locked {
            if (finished) true else {
                status = response.statusCode.toInt()
                contentType = response.MIMEType
                false
            }
        }
        completionHandler(if (stopped) NSURLSessionResponseCancel else NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        val tooLarge = locked {
            if (finished) return
            if (didReceiveData.length > (request.maxResponseBytes - received).toULong()) true else {
                val length = didReceiveData.length.toInt()
                val needed = received + length
                if (needed > buffer.size) {
                    val doubled = minOf(request.maxResponseBytes.toLong(), maxOf(8192L, buffer.size.toLong() * 2)).toInt()
                    buffer = buffer.copyOf(maxOf(needed, doubled))
                }
                if (length > 0) buffer.usePinned { target ->
                    memcpy(target.addressOf(received), didReceiveData.bytes, didReceiveData.length)
                }
                received = needed
                false
            }
        }
        if (tooLarge) complete(Result.failure(CompanionTransportException("response-limit")))
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        willCacheResponse: NSCachedURLResponse,
        completionHandler: (NSCachedURLResponse?) -> Unit,
    ) {
        completionHandler(null)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null) {
            complete(Result.failure(CompanionTransportException(
                if (didCompleteWithError.code == NSURLErrorTimedOut) "timeout" else "network")))
            return
        }
        val result = locked {
            if (finished) return
            val responseStatus = status
            if (responseStatus == null) Result.failure(CompanionTransportException("invalid-http-response")) else {
                Result.success(CompanionHttpResponse(responseStatus, buffer.copyOf(received), contentType))
            }
        }
        complete(result)
    }

    private fun complete(result: Result<CompanionHttpResponse>) {
        val session = locked {
            if (finished) return
            finished = true
            buffer = ByteArray(0)
            activeTask = null
            activeSession.also { activeSession = null }
        }
        session?.invalidateAndCancel()
        continuation.resumeWith(result)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}
