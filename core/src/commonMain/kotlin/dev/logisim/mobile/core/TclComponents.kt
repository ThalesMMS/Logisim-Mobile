package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

data class TclSignalPort(val name: String, val direction: PortDirection, val width: Int) {
    init {
        require(Regex("[A-Za-z][A-Za-z0-9_]{0,127}").matches(name)) { "Tcl ports require plain signal names." }
        require(width in 1..64) { "Tcl port width must be from 1 to 64." }
    }
}

data class TclInterface(val ports: List<TclSignalPort>) {
    init {
        require(ports.size in 1..512 && ports.map { it.name.lowercase() }.distinct().size == ports.size) { "Tcl ports must be present and unique." }
    }
    val inputs get() = ports.filter { it.direction != PortDirection.OUTPUT }
    val outputs get() = ports.filter { it.direction != PortDirection.INPUT }
    val hasDesktopClock get() = ports.any { it.name == "sysclk_i" && it.direction != PortDirection.OUTPUT && it.width == 1 }

    // Mirror companion tcl_runtime._frame_limit, retaining legacy capacity for wide interfaces.
    val replayFrameLimit: Int
        get() {
            val frameBytes = ports.sumOf { port ->
                (port.name.length + port.width + 16) * if (port.direction == PortDirection.INOUT) 2 else 1
            }
            return (512 * 1024 / frameBytes).coerceIn(256, 1024)
        }

    fun layout(component: CircuitComponent): ComponentLayout {
        val terminals = inputs.mapIndexed { index, port -> ComponentTerminal(port.name,
            GridPoint(component.position.x, component.position.y + 40 + index * 10), port.direction, port.width, port.name) } +
            outputs.filter { it.direction == PortDirection.OUTPUT }.mapIndexed { index, port -> ComponentTerminal(port.name,
                GridPoint(component.position.x + 140, component.position.y + 40 + index * 10), port.direction, port.width, port.name) }
        return ComponentLayout(terminals, ComponentBounds(component.position.x, component.position.y,
            component.position.x + 140, component.position.y + maxOf(inputs.size, outputs.size) * 10 + 40))
    }
}

object TclInterfaces {
    val reds = TclInterface(buildList {
        add(TclSignalPort("Hex0_obs", PortDirection.INPUT, 4)); add(TclSignalPort("Hex1_obs", PortDirection.INPUT, 4))
        repeat(16) { add(TclSignalPort("L${it}_obs", PortDirection.INPUT, 1)) }
        add(TclSignalPort("Result_A_obs", PortDirection.INPUT, 16)); add(TclSignalPort("Result_B_obs", PortDirection.INPUT, 16))
        add(TclSignalPort("seg7_obs", PortDirection.INPUT, 8)); add(TclSignalPort("sysclk_i", PortDirection.INPUT, 1)); add(TclSignalPort("rst_in", PortDirection.INPUT, 1))
        repeat(16) { add(TclSignalPort("S${it}_sti", PortDirection.OUTPUT, 1)) }
        add(TclSignalPort("Val_A_sti", PortDirection.OUTPUT, 16)); add(TclSignalPort("Val_B_sti", PortDirection.OUTPUT, 16)); add(TclSignalPort("rst_o", PortDirection.OUTPUT, 1))
    })

    const val DEFAULT_GENERIC = "entity TCL_Generic is port(horloge_i : in std_logic; val_i : in std_logic_vector(3 downto 0); max_o : out std_logic; cpt_o : out std_logic_vector(3 downto 0)); end TCL_Generic;"

    fun parseGeneric(source: String): TclInterface {
        require(source.length <= 128_000) { "Tcl interface exceeds 128 KB." }
        val text = source.lineSequence().joinToString("\n") { it.substringBefore("--") }
        require(Regex("\\bentity\\s+[A-Za-z][A-Za-z0-9_]*\\s+is\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "A Tcl Generic interface requires a VHDL entity." }
        require(!Regex("\\bgeneric\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "Tcl Generic parameters are not supported; use literal signal widths." }
        val opening = Regex("\\bport\\s*\\(", RegexOption.IGNORE_CASE).find(text)
            ?: throw IllegalArgumentException("The Tcl Generic entity has no port list.")
        val start = opening.range.last + 1
        var depth = 1
        var end = start
        while (end < text.length && depth > 0) {
            when (text[end]) { '(' -> depth++; ')' -> depth-- }
            end++
        }
        require(depth == 0) { "The Tcl Generic port list is not closed." }
        val ports = mutableListOf<TclSignalPort>()
        for (declaration in text.substring(start, end - 1).split(';').filter { it.isNotBlank() }) {
            val match = Regex("\\s*([A-Za-z][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z][A-Za-z0-9_]*)*)\\s*:\\s*(inout|in|out)\\s+(std_logic(?:_vector\\s*\\(\\s*([0-9]+)\\s+(downto|to)\\s+([0-9]+)\\s*\\))?)\\s*",
                RegexOption.IGNORE_CASE).matchEntire(declaration)
                ?: throw IllegalArgumentException("Unsupported Tcl port declaration: ${declaration.trim()}.")
            val direction = when (match.groupValues[2].lowercase()) {
                "in" -> PortDirection.INPUT; "out" -> PortDirection.OUTPUT; else -> PortDirection.INOUT
            }
            val width = if (match.groupValues[4].isEmpty()) 1 else {
                val first = match.groupValues[4].toIntOrNull() ?: throw IllegalArgumentException("Invalid Tcl vector index.")
                val last = match.groupValues[6].toIntOrNull() ?: throw IllegalArgumentException("Invalid Tcl vector index.")
                val ascending = match.groupValues[5].equals("to", true)
                require(if (ascending) first <= last else first >= last) { "The Tcl vector direction and bounds disagree." }
                (kotlin.math.abs(first.toLong() - last) + 1).also { require(it <= 64) { "Tcl vectors support at most 64 bits." } }.toInt()
            }
            match.groupValues[1].split(',').forEach { ports += TclSignalPort(it.trim(), direction, width) }
        }
        return TclInterface(ports)
    }
}

enum class TclRuntimePhase { STOPPED, WAITING, READY, FAILED, HISTORY_LIMIT }
data class TclInputFrame(val inputs: Map<String, LogicVector>)
data class TclAutomaticTicks(val authorization: String, val remaining: Int = LIMIT) {
    companion object {
        const val LIMIT = 16
    }
}

data class TclRuntimeState(
    val phase: TclRuntimePhase = TclRuntimePhase.STOPPED,
    val sourceDigest: String? = null,
    val generation: Long = 0,
    val frames: List<TclInputFrame> = emptyList(),
    val outputs: Map<String, LogicVector> = emptyMap(),
    val pendingTickRequests: Int = 0,
    val consumedTickRequests: Int = 0,
    val completedFrameCount: Int = 0,
    val message: String? = null,
    val notice: LocalizedText? = null,
    val automaticTicks: TclAutomaticTicks? = null,
    val approvalId: String? = null,
) : ComponentState {
    val enabled get() = phase == TclRuntimePhase.WAITING || phase == TclRuntimePhase.READY
    val requestedTick get() = pendingTickRequests > 0

    fun tickRequest(authorization: String? = null): TclComponentEvent.ApplyTick =
        TclComponentEvent.ApplyTick(generation, pendingTickRequests, authorization, consumedTickRequests)
}

sealed interface TclComponentEvent : ComponentEvent {
    data class Enable(
        val sourceDigest: String,
        val automaticTickAuthorization: String? = null,
        val approvalId: String? = null,
    ) : TclComponentEvent
    data object Disable : TclComponentEvent
    data class Response(
        val generation: Long,
        val sourceDigest: String,
        val outputs: Map<String, LogicVector>,
        val tickRequestFrames: Set<Int> = emptySet(),
        val approvalId: String? = null,
        val frameCount: Int? = null,
    ) : TclComponentEvent
    data class Failure(val generation: Long, val message: String, val notice: LocalizedText = LocalizedText.literal(message),
                       val approvalId: String? = null) : TclComponentEvent
    data object TickRequestAcknowledged : TclComponentEvent
    data class ApplyTick(val generation: Long, val pendingRequests: Int, val authorization: String? = null,
        val consumedRequests: Int = 0) : TclComponentEvent {
        fun matches(state: TclRuntimeState): Boolean =
            state.enabled && generation == state.generation && pendingRequests > 0 && pendingRequests == state.pendingTickRequests &&
                consumedRequests == state.consumedTickRequests &&
                (authorization == null || state.automaticTicks?.let { it.authorization == authorization && it.remaining > 0 } == true)
    }
    data class RevokeAutomaticTicks(val authorization: String) : TclComponentEvent
}

object TclComponents {
    val CONSOLE_REDS = ComponentKind("TCL_CONSOLE_REDS")
    val GENERIC = ComponentKind("TCL_GENERIC")
    private val content = AttributeKey.text("content", TclInterfaces.DEFAULT_GENERIC) { it.length <= 128_000 }
    private val path = AttributeKey.text("filePath") { it.length <= 4096 }
    private val label = AttributeKey.text("label") { it.length <= 4096 }
    private val font = AttributeKey.text("labelfont", "SansSerif bold 16") { it.length <= 256 }

    fun definitions(): List<ComponentDefinition> = listOf(
        definition(CONSOLE_REDS, "TclConsoleReds", false), definition(GENERIC, "TclGeneric", true))

    fun interfaceFor(component: CircuitComponent): TclInterface = if (component.kind == CONSOLE_REDS) TclInterfaces.reds
        else TclInterfaces.parseGeneric(component.attributes["content"] ?: TclInterfaces.DEFAULT_GENERIC)

    private fun definition(kind: ComponentKind, name: String, generic: Boolean) = ComponentDefinition(
        kind, name, "#TCL", schema = AttributeSchema(listOf(path, label, font) + if (generic) listOf(content) else emptyList()),
        layout = { component, _ -> runCatching { interfaceFor(component).layout(component) }.getOrElse {
            ComponentLayout(emptyList(), ComponentBounds(component.position.x, component.position.y,
                component.position.x + 140, component.position.y + 80))
        } }, behavior = object : ComponentBehavior {
            override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
                runCatching { TclRuntimeState(outputs = unknownOutputs(interfaceFor(component))) }.getOrElse {
                    TclRuntimeState(phase = TclRuntimePhase.FAILED, message = it.message,
                        notice = message(Messages.TclRuntimeInterface, "details" to it.message.orEmpty()))
                }

            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val face = try { interfaceFor(context.component) } catch (failure: IllegalArgumentException) {
                    val details = failure.message.orEmpty()
                    val notice = message(Messages.TclRuntimeInterface, "details" to details)
                    val state = TclRuntimeState(phase = TclRuntimePhase.FAILED, message = details,
                        notice = notice)
                    return ComponentEvaluation(emptyMap(), state, delay = 1,
                        diagnostics = listOf(notice.resolve()), notices = listOf(notice))
                }
                val initial = TclRuntimeState(outputs = unknownOutputs(face))
                var state = context.state as? TclRuntimeState ?: initial
                for (event in context.events) when (event) {
                    is TclComponentEvent.Enable -> {
                        require(Regex("[a-f0-9]{64}").matches(event.sourceDigest)) { "Tcl source approval requires a SHA-256 digest." }
                        state = initial.copy(phase = TclRuntimePhase.READY, sourceDigest = event.sourceDigest, generation = state.generation + 1,
                            automaticTicks = event.automaticTickAuthorization?.let { TclAutomaticTicks(it) }, approvalId = event.approvalId)
                    }
                    TclComponentEvent.Disable -> state = initial.copy(generation = state.generation + 1)
                    is TclComponentEvent.Response -> {
                        val count = event.frameCount ?: state.frames.size
                        val matchingGeneration = if (event.approvalId == null) {
                            event.generation == state.generation && event.frameCount == null
                        } else {
                            event.frameCount != null && count == state.completedFrameCount + 1 &&
                                event.generation == state.generation - (state.frames.size - count)
                        }
                        if (event.approvalId != state.approvalId || event.sourceDigest != state.sourceDigest ||
                            state.phase != TclRuntimePhase.WAITING || !matchingGeneration ||
                            count !in (state.completedFrameCount + 1)..state.frames.size) continue
                        if (event.outputs.keys != initial.outputs.keys || face.outputs.any { event.outputs[it.name]?.width != it.width } ||
                            event.tickRequestFrames.any { it !in 0 until count }) {
                            state = state.copy(phase = TclRuntimePhase.FAILED, outputs = initial.outputs, pendingTickRequests = 0,
                                message = null, notice = message(Messages.TclRuntimeResponse))
                        } else state = state.copy(phase = if (count == state.frames.size) TclRuntimePhase.READY else TclRuntimePhase.WAITING,
                            outputs = event.outputs.toMap(),
                            pendingTickRequests = state.pendingTickRequests + event.tickRequestFrames.count { it >= state.completedFrameCount },
                            completedFrameCount = count, message = null, notice = null)
                    }
                    is TclComponentEvent.Failure -> if (event.approvalId == state.approvalId && state.phase == TclRuntimePhase.WAITING &&
                        (event.approvalId != null || event.generation == state.generation))
                        state = state.copy(phase = TclRuntimePhase.FAILED, outputs = initial.outputs, pendingTickRequests = 0, message = event.message, notice = event.notice)
                    TclComponentEvent.TickRequestAcknowledged -> if (state.requestedTick) {
                        state = state.copy(pendingTickRequests = state.pendingTickRequests - 1, consumedTickRequests = state.consumedTickRequests + 1)
                    }
                    is TclComponentEvent.ApplyTick -> if (event.matches(state)) {
                        val automaticTicks = state.automaticTicks
                        state = state.copy(pendingTickRequests = state.pendingTickRequests - 1, consumedTickRequests = state.consumedTickRequests + 1,
                            automaticTicks = if (event.authorization != null && automaticTicks != null)
                                automaticTicks.copy(remaining = automaticTicks.remaining - 1) else automaticTicks)
                    }
                    is TclComponentEvent.RevokeAutomaticTicks -> if (event.authorization == state.automaticTicks?.authorization) {
                        state = state.copy(automaticTicks = null)
                    }
                }
                if (state.enabled) {
                    if (!face.hasDesktopClock) state = state.copy(phase = TclRuntimePhase.FAILED, outputs = initial.outputs, pendingTickRequests = 0,
                        message = null, notice = message(Messages.TclRuntimeClock))
                    else {
                        val inputs = face.inputs.associate { port -> port.name to (context.inputs[port.name] ?: LogicVector.unknown(port.width)) }
                        if (state.frames.lastOrNull()?.inputs != inputs) {
                            state = if (state.frames.size >= face.replayFrameLimit) state.copy(phase = TclRuntimePhase.HISTORY_LIMIT, outputs = initial.outputs, pendingTickRequests = 0,
                                message = null, notice = message(Messages.TclRuntimeHistory, "limit" to face.replayFrameLimit))
                            else state.copy(phase = TclRuntimePhase.WAITING, generation = state.generation + 1,
                                frames = state.frames + TclInputFrame(inputs.toMap()), message = null, notice = null)
                        }
                    }
                }
                val status = state.notice ?: if (state.phase == TclRuntimePhase.STOPPED) message(Messages.TclRuntimeStopped) else null
                if (status != state.notice) state = state.copy(notice = status)
                val diagnostics = listOfNotNull(status?.resolve() ?: state.message)
                return ComponentEvaluation(state.outputs, state, delay = 1, diagnostics = diagnostics, notices = listOfNotNull(status))
            }
        }, supportsRotation = false,
    )

    private fun unknownOutputs(face: TclInterface) = face.outputs.associate { it.name to LogicVector.unknown(it.width) }
}
