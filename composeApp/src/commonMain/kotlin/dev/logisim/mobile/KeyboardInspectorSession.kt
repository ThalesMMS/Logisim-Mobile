package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKinds

/** Attribute changes, including buflen, keep the same keyboard input session. */
internal data class KbdDraftContext(val circuit: CircuitAccessibilityContext, val componentId: String, val debugCopyIdentity: Any?)

internal fun EditorState.keyboardDraftContext(): KbdDraftContext? = selected?.takeIf {
    it.kind == IoKinds.Keyboard && (activeInstancePath.isNotEmpty() || activeCircuitName == project.mainCircuitName)
}?.let { KbdDraftContext(accessibilityContext(), it.id, debugCopyIdentity) }

internal class KeyboardInspectorSession(private val owner: EditorState, val context: KbdDraftContext) {
    val draft = KeyboardInputDraft()
    val focus = InspectorFocusState()
    private var active = true
    private val hosts = mutableSetOf<Host>()

    internal class Host internal constructor(val presentation: InspectorPresentation)

    fun attach(host: Host) { if (active) hosts += host }

    fun detach(host: Host) {
        if (hosts.remove(host)) focus.focusChanged(host.presentation, false)
    }

    fun isCurrent(host: Host, current: KeyboardInspectorSession?): Boolean =
        active && this === current && host in hosts && context == owner.keyboardDraftContext() &&
            owner.documentWritable && owner.interactionsEnabled

    fun focusChanged(host: Host, current: KeyboardInspectorSession?, focused: Boolean, otherFocus: InspectorFocusState? = null) {
        if (!isCurrent(host, current)) return
        if (focused) otherFocus?.clear()
        focus.focusChanged(host.presentation, focused)
    }

    fun edit(host: Host, current: KeyboardInspectorSession?, value: TextFieldValue): IoInputEvent.ReplaceKeyboardText? {
        if (!isCurrent(host, current)) return null
        val capacity = owner.selected!!.attributeInt("buflen", 32).coerceIn(1, 256)
        val event = draft.edit(value, capacity)
        return event.takeIf { send(host, current, it) }
    }

    fun send(host: Host, current: KeyboardInspectorSession?, event: IoInputEvent): Boolean {
        if (!isCurrent(host, current)) return false
        return owner.sendComponentEvent(context.componentId, event, context.circuit.instancePath,
            context.circuit.circuitId, context.circuit.documentIdentity)
    }

    fun close() {
        active = false
        hosts.clear()
        focus.close()
    }
}

@Composable
internal fun rememberKeyboardInspectorSession(editor: EditorState): KeyboardInspectorSession? {
    val context = editor.keyboardDraftContext()
    val session = remember(editor, editor.componentRegistry, context) { context?.let { KeyboardInspectorSession(editor, it) } }
    DisposableEffect(session) { onDispose { session?.close() } }
    return session
}

@Composable
internal fun rememberKeyboardHost(session: KeyboardInspectorSession, presentation: InspectorPresentation): KeyboardInspectorSession.Host {
    val host = remember(session, presentation) { KeyboardInspectorSession.Host(presentation) }
    DisposableEffect(session, host) {
        session.attach(host)
        onDispose { session.detach(host) }
    }
    return host
}
