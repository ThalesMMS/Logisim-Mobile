package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal enum class InspectorPresentation { INLINE, SHEET }

internal data class InspectorFocusRequest(val id: Long, val presentation: InspectorPresentation)

internal data class InspectorPresentationChange(
    val previous: InspectorPresentation?,
    val current: InspectorPresentation,
    val focusedPresentation: InspectorPresentation?,
)

internal class InspectorFocusState(private val enabled: () -> Boolean = { true }) {
    var focusedPresentation by mutableStateOf<InspectorPresentation?>(null)
        private set
    var focusRequest by mutableStateOf<InspectorFocusRequest?>(null)
        private set
    private var presentation: InspectorPresentation? = null
    private var nextFocusRequest = 0L
    private var active = true

    fun focusChanged(host: InspectorPresentation, focused: Boolean) {
        if (!active) return
        if (focused) {
            focusedPresentation = host
        } else if (focusedPresentation == host) {
            focusedPresentation = null
            if (focusRequest?.presentation == host) focusRequest = null
        }
    }

    fun completeFocus(request: InspectorFocusRequest) {
        if (focusRequest == request && focusedPresentation == request.presentation) focusRequest = null
    }

    fun dismissPresentation(host: InspectorPresentation) {
        if (focusedPresentation == host) focusedPresentation = null
        if (focusRequest?.presentation == host) focusRequest = null
    }

    fun presentationChange(next: InspectorPresentation) = InspectorPresentationChange(
        presentation, next, (focusedPresentation ?: focusRequest?.presentation).takeIf { enabled() },
    )

    fun commitPresentation(change: InspectorPresentationChange): Boolean {
        if (!active || presentation == change.current) return false
        presentation = change.current
        if (change.previous == null || change.previous == change.current || change.focusedPresentation == null || !enabled()) return false
        if (focusedPresentation == change.current && focusRequest == null) return false
        focusRequest = InspectorFocusRequest(++nextFocusRequest, change.current)
        return true
    }

    fun clear() {
        focusedPresentation = null
        focusRequest = null
    }

    fun close() {
        active = false
        clear()
    }
}

@Composable
internal fun TrackInspectorPresentation(
    focus: InspectorFocusState,
    presentation: InspectorPresentation,
    onFocusedFieldMoved: (InspectorPresentation) -> Unit,
) {
    // Capture before removing the old host, whose focus-loss callback runs during disposal.
    val change = remember(focus, presentation) { focus.presentationChange(presentation) }
    SideEffect {
        if (focus.commitPresentation(change)) onFocusedFieldMoved(presentation)
    }
}
