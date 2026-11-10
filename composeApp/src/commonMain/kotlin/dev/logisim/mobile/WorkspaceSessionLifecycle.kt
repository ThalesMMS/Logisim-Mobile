package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceSession
import dev.logisim.mobile.core.WorkspaceSessionState

data class WorkspaceSessionBinding(val session: WorkspaceSession, val state: WorkspaceSessionState)

/** The app supplies its run controller's pause operation; returning to the foreground never starts it. */
@Composable
fun rememberWorkspaceSession(repository: WorkspaceCatalogRepository, pauseSimulation: () -> Unit): WorkspaceSessionBinding {
    val pause by rememberUpdatedState(pauseSimulation)
    var state by remember(repository) { mutableStateOf(WorkspaceSessionState()) }
    val session = remember(repository) { WorkspaceSession(repository, { pause() }, { state = it }) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        session.foregroundChanged(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session.foregroundChanged(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.pauseAndFlush()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.pauseAndFlush()
        }
    }
    return WorkspaceSessionBinding(session, state)
}
