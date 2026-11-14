package dev.logisim.mobile

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/**
 * Whether the editor shows a circuit the running simulation does not include: another circuit opened on its own,
 * rather than the main circuit or one of its instances. The live project simulation holds every instance's state, so
 * an instance view keeps updating while it runs, as Evolution's Project.setCircuitState only rebinds the view.
 */
internal fun EditorState.viewsOutsideRunningCircuit(): Boolean =
    activeInstancePath.isEmpty() && activeCircuitName != project.mainCircuitName

/**
 * The navigation effect (C58): entering an instance or returning to the main circuit keeps a run going; showing a
 * circuit outside it pauses continuous execution, keeping queued input releases, and says why in the status line.
 */
internal fun pauseOutsideRunningCircuit(state: EditorState, run: SimulationRunController) {
    if (!state.viewsOutsideRunningCircuit()) return
    val running = run.status.value.phase == SimulationRunPhase.RUNNING
    run.pauseContinuousExecution()
    if (running) state.reportStatus(message(Messages.SimulationPausedOutsideMain, "circuit" to state.activeCircuitName))
}
