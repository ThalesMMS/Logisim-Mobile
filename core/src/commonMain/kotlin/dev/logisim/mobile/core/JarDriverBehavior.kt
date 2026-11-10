package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message

internal data class JarInputRevisionEvent(
    val sessionIdentity: Any,
    override val eventAddress: String,
    val revision: Long,
) : AddressedComponentEvent

internal data class JarDriveReplyEvent(
    val sessionIdentity: Any,
    override val eventAddress: String,
    val sequence: Long,
    val revision: Long,
    val drives: Map<String, JarTerminalDrive>,
) : AddressedComponentEvent

private data class JarDriverState(
    val sessionIdentity: Any,
    val revision: Long = -1,
    val sequence: Long = -1,
    val outputRevision: Long = -1,
    val drives: Map<String, JarTerminalDrive> = emptyMap(),
    val retired: Boolean = false,
) : ComponentOutputReleases {
    override val releasedOutputs: Set<String>
        get() = drives.filterValues { it == JarTerminalDrive.Released }.keys
}

/** Applies only drives delivered by the approved companion, using the normal output event queue. */
internal class JarDriverBehavior(
    private val sessionIdentity: Any,
    private val isCurrent: () -> Boolean,
) : ComponentBehavior {
    override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
        JarDriverState(sessionIdentity)

    override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
        JarDriverState(sessionIdentity, retired = true)

    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
        val ports = context.component.explicitPorts.filter { it.direction != PortDirection.INPUT }
        val prior = context.state as? JarDriverState
        check(prior == null || prior.sessionIdentity === sessionIdentity) {
            "A new JAR session requires a fresh native simulation."
        }
        var state = prior ?: JarDriverState(sessionIdentity)
        if (state.retired || !isCurrent()) {
            val releases = ports.associate { it.name to JarTerminalDrive.Released }
            return ComponentEvaluation(emptyMap(), state.copy(retired = true, drives = releases),
                delay = 0, notices = listOf(message("jar_live_retired")), outputGeneration = "jar:retired")
        }
        for (event in context.events) when (event) {
            is JarInputRevisionEvent -> if (event.sessionIdentity === sessionIdentity && event.revision > state.revision) {
                // While waiting for the new reply, the previous drive remains applied.
                state = state.copy(revision = event.revision)
            }
            is JarDriveReplyEvent -> if (event.sessionIdentity === sessionIdentity &&
                event.revision == state.revision && event.sequence > state.sequence) {
                require(event.drives.keys == ports.map { it.name }.toSet() && ports.all { port ->
                    val value = event.drives.getValue(port.name)
                    value !is JarTerminalDrive.Driven || value.value.width == port.width
                }) { "The JAR reply does not match its approved terminal widths." }
                state = state.copy(sequence = event.sequence, outputRevision = event.revision, drives = event.drives)
            }
            else -> Unit
        }
        val pending = state.outputRevision < 0 || state.outputRevision != state.revision
        val outputs = state.drives.mapNotNull { (port, drive) ->
            (drive as? JarTerminalDrive.Driven)?.let { port to it.value }
        }.toMap()
        return ComponentEvaluation(outputs, state, delay = 0,
            notices = if (pending) listOf(message("jar_live_waiting")) else emptyList(),
            outputGeneration = "jar:${state.sequence}")
    }
}
