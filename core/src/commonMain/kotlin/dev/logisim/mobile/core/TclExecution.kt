package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class TclSourceFile(val name: String, val text: String) {
    init {
        requireLocalized(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(name)) { message(Messages.TclClientErrorSourceName, "name" to name) }
        requireLocalized(text.encodeToByteArray().size <= MAX_BYTES) { message(Messages.TclClientErrorSourceSize) }
    }
    val sha256 get() = ExtensionDigest.sha256(text.encodeToByteArray())

    companion object {
        const val MAX_BYTES = 256 * 1024
    }
}

data class TclConsoleRequest(val requestId: String, val source: TclSourceFile, val includes: List<TclSourceFile> = emptyList()) {
    init { validateTclSources(requestId, source, includes) }
}
data class TclConsoleReply(val result: String, val stdout: String, val stderr: String, val sourceSha256: String,
    val windowPreview: EmbeddedImage? = null)

sealed interface TclTkInput {
    data class Tap(val x: Int, val y: Int) : TclTkInput {
        init { require(x in 0 until 640 && y in 0 until 480) }
    }
    data class Key(val character: Char) : TclTkInput {
        init { require(character == ' ' || character in 'a'..'z' || character in '0'..'9') }
    }
    enum class Control : TclTkInput { Return, BackSpace, Escape, Tab, Left, Right, Up, Down }
}

class TclTkFrame internal constructor(val sequence: Int, val image: EmbeddedImage,
    private val deliver: suspend (TclTkInput) -> Unit) {
    val acceptsInput: Boolean get() = sequence < 8
    suspend fun send(input: TclTkInput) {
        check(acceptsInput)
        deliver(input)
    }
}
data class TclStepObservation(val forced: Boolean, val requestedTick: Boolean, val outputs: Map<String, LogicVector>)
data class TclStepReply(val steps: List<TclStepObservation>, val outputs: Map<String, LogicVector>, val stdout: String,
    val stderr: String, val sourceSha256: String, val delay: Long = 1) {
    val tickRequestFrames: Set<Int> get() = steps.mapIndexedNotNull { index, step -> index.takeIf { step.requestedTick } }.toSet()
}
data class TclStepRequest(val requestId: String, val source: TclSourceFile, val face: TclInterface,
    val frames: List<TclInputFrame>, val includes: List<TclSourceFile> = emptyList()) {
    init {
        validateTclSources(requestId, source, includes)
        requireLocalized(face.hasDesktopClock) { message(Messages.TclClientErrorClockRequired) }
        requireLocalized(frames.size in 1..face.replayFrameLimit) {
            message(Messages.TclClientErrorFrameCount, "limit" to face.replayFrameLimit, "count" to frames.size)
        }
        val expected = face.inputs.associate { it.name to it.width }
        requireLocalized(frames.all { frame -> frame.inputs.keys == expected.keys && frame.inputs.all { (name, value) -> value.width == expected[name] } }) {
            message(Messages.TclClientErrorFrameInterface)
        }
    }

    fun wireParameters(): Map<String, Any> = mapOf("script" to source.name,
        "ports" to face.ports.map { mapOf("name" to it.name, "direction" to when (it.direction) {
            PortDirection.INPUT -> "input"; PortDirection.OUTPUT -> "output"; PortDirection.INOUT -> "inout"
        }, "width" to it.width) },
        "frames" to frames.map { frame -> frame.inputs.mapValues { it.value.display.replace('?', 'U') } })
}

private fun validateTclSources(requestId: String, source: TclSourceFile, includes: List<TclSourceFile>) {
    requireLocalized(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(requestId)) { message(Messages.TclClientErrorRequestIdentity, "request" to requestId) }
    validateTclSourceFiles(source, includes)
}

const val MAX_TCL_INCLUDES = 15

fun validateTclSourceFiles(source: TclSourceFile, includes: List<TclSourceFile>) {
    val sources = listOf(source) + includes
    requireLocalized(includes.size <= MAX_TCL_INCLUDES && sources.map { it.name }.distinct().size == sources.size) { message(Messages.TclClientErrorSourceNames) }
    requireLocalized(sources.sumOf { it.text.encodeToByteArray().size.toLong() } <= 1024 * 1024) { message(Messages.TclClientErrorTotalSize) }
}

/** The host transport enforces selection, authentication and limits, and cancels remote work when its calling coroutine is cancelled. */
interface TclExecutionClient {
    val hostLabel: String
    val available: Boolean
    suspend fun runConsole(request: TclConsoleRequest): TclConsoleReply
    suspend fun step(request: TclStepRequest): TclStepReply
    suspend fun cancel(requestId: String)
}

/** A generation guard prevents a cancelled or replaced job from publishing late results. */
class TclSubmissionState {
    var generation: Long = 0
        private set
    var activeRequestId: String? = null
        private set

    fun begin(requestId: String): Long {
        requireLocalized(requestId.isNotBlank()) { message(Messages.TclClientErrorRequestIdentity, "request" to requestId) }
        activeRequestId = requestId
        return ++generation
    }

    fun accepts(requestId: String, submittedGeneration: Long): Boolean =
        activeRequestId == requestId && generation == submittedGeneration

    fun complete(requestId: String, submittedGeneration: Long): Boolean {
        if (!accepts(requestId, submittedGeneration)) return false
        activeRequestId = null
        return true
    }

    fun cancel(): String? {
        val old = activeRequestId
        activeRequestId = null
        generation++
        return old
    }
}
