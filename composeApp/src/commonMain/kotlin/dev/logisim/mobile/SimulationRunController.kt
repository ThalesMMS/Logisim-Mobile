package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedFailure
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class SimulationStep { TICK, EVENT, RESET, SETTLE, TICK_CYCLE }
enum class SimulationRequestPurpose { COMMAND, DOCUMENT_INITIALIZATION }
enum class SimulationRunPhase { PAUSED, RUNNING, STEPPING, INACTIVE, CLOSED }

data class SimulationRunStatus(
    val phase: SimulationRunPhase = SimulationRunPhase.PAUSED,
    val frequencyHz: Double = 4.0,
    val completedSteps: Long = 0,
    val lastStep: SimulationStep? = null,
    /** Why the run stopped, in the catalog's languages: the failure's own message, or a generic one. */
    val failure: LocalizedText? = null,
    val pendingSteps: Int = 0,
    val latestManualRequest: Long = 0,
    val automaticTicks: Boolean = true,
    val measuredTicksPerSecond: Double? = null,
)

/** Capture on the editor dispatcher before computation starts. Options belong to the engine. */
data class SimulationRunSnapshot(
    val project: CircuitProject,
    val previous: SimulationState,
    val inputs: Map<String, LogicValue> = emptyMap(),
    val vectorInputs: Map<String, LogicVector> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    val elapsedTimeMillis: Long = 0,
    val revision: Long = 0,
    val events: Map<String, List<ComponentEvent>> = emptyMap(),
    val startupResetIds: Set<String> = emptySet(),
    val resetVectorInputs: Map<String, LogicVector> = emptyMap(),
    val purpose: SimulationRequestPurpose = SimulationRequestPurpose.COMMAND,
)

/**
 * Call controls from the supplied scope's dispatcher. Only computation leaves that dispatcher;
 * stale, cancelled, or superseded work cannot publish a result into the editor.
 */
class SimulationRunController(
    private val scope: CoroutineScope,
    private val capture: () -> SimulationRunSnapshot,
    private val advance: suspend (SimulationRunSnapshot, SimulationStep) -> SimulationState,
    private val isCurrent: (SimulationRunSnapshot) -> Boolean,
    private val publish: (SimulationRunSnapshot, SimulationState) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val waitForNext: suspend (Long) -> Unit = { delay(it) },
    private val advanceWithObservations: (suspend (SimulationRunSnapshot, SimulationStep) -> SimulationAdvanceResult)? = null,
    private val publishObservations: (SimulationRunSnapshot, List<SimulationObservation>) -> Unit = { _, _ -> },
    private val tickRateTimeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutableStatus = MutableStateFlow(SimulationRunStatus())
    val status: StateFlow<SimulationRunStatus> = mutableStatus.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var manualTail: Job? = null
    private var manualTailId = 0L
    private data class Initialization(val snapshot: SimulationRunSnapshot, val step: SimulationStep)
    private var pendingInitialization: Initialization? = null
    private data class ManualCommand(
        val step: SimulationStep,
        val transform: (SimulationRunSnapshot) -> SimulationRunSnapshot,
        val releasesInput: Boolean,
        val events: List<ComponentEvent>,
        val initialization: Initialization? = null,
        var cancelled: Boolean = false,
    )
    private val manualJobs = linkedMapOf<Job, ManualCommand>()
    private val advanceLock = Mutex()
    private var active = true
    private var closed = false
    private var resumeAfterStimulus = false
    private var tickRateWindowStart: TimeMark? = null
    private var ticksInRateWindow = 0L

    fun start(): Boolean {
        if (!scope.isActive) { close(); return false }
        if (closed || !active) return false
        if (mutableStatus.value.phase == SimulationRunPhase.RUNNING) return true
        if (mutableStatus.value.pendingSteps > 0) {
            resumeAfterStimulus = true
            return true
        }
        stopWork()
        val ticket = generation
        val automaticTicks = mutableStatus.value.automaticTicks
        mutableStatus.value = mutableStatus.value.copy(phase = SimulationRunPhase.RUNNING, failure = null)
        work = scope.launch {
            try {
                var remainingTick = periodMillis(mutableStatus.value.frequencyHz)
                while (ticket == generation && active && !closed) {
                    val snapshot = capture()
                    val untilWakeup = snapshot.previous.nextWakeupMillis?.let {
                        (it - snapshot.elapsedTimeMillis).coerceAtLeast(1L)
                    }
                    val wait = if (automaticTicks) minOf(remainingTick, untilWakeup ?: Long.MAX_VALUE)
                        else untilWakeup ?: awaitCancellation()
                    waitForNext(wait)
                    coroutineContext.ensureActive()
                    val step = if (automaticTicks) {
                        remainingTick -= wait
                        if (remainingTick <= 0L) SimulationStep.TICK else SimulationStep.SETTLE
                    } else SimulationStep.SETTLE
                    if (!perform(ticket, step)) break
                    if (step == SimulationStep.TICK) remainingTick = periodMillis(mutableStatus.value.frequencyHz)
                }
            } catch (_: CancellationException) {
                // A newer control action owns the next visible state.
            } catch (error: Exception) {
                fail(ticket, error)
            }
        }
        return true
    }

    fun pause() {
        if (closed) return
        resumeAfterStimulus = false
        pendingInitialization = null
        stopWork()
        mutableStatus.value = mutableStatus.value.copy(
            phase = if (active) SimulationRunPhase.PAUSED else SimulationRunPhase.INACTIVE,
        )
    }

    fun pauseContinuousExecution() {
        if (closed) return
        resumeAfterStimulus = false
        if (mutableStatus.value.phase == SimulationRunPhase.RUNNING) pause()
    }

    /** Manual clicks are FIFO. Reset cancels older commands before entering the queue. */
    fun step(step: SimulationStep): Job? {
        resumeAfterStimulus = false
        val job = enqueue(step)
        if (job != null && step == SimulationStep.RESET) pendingInitialization = null
        return job
    }

    /** Runtime absence is represented by an unattached editor handler, never by rejection. */
    fun request(snapshot: SimulationRunSnapshot, step: SimulationStep): SimulationRequestResult {
        if (snapshot.purpose == SimulationRequestPurpose.DOCUMENT_INITIALIZATION) {
            if (!scope.isActive) { close(); return SimulationRequestResult.REJECTED }
            if (closed || step != SimulationStep.RESET && step != SimulationStep.SETTLE ||
                snapshot.events.isNotEmpty() || !isCurrent(snapshot)) return SimulationRequestResult.REJECTED
            val initialization = Initialization(snapshot, step)
            pendingInitialization = initialization
            resumeAfterStimulus = false
            if (active && enqueueInitialization(initialization) == null) return SimulationRequestResult.REJECTED
            return SimulationRequestResult.ACCEPTED
        }
        val job = if (step == SimulationStep.SETTLE) submitStimulus(snapshot.inputs, snapshot.vectorInputs, snapshot.events)
            else step(step)
        return if (job == null) SimulationRequestResult.REJECTED else SimulationRequestResult.ACCEPTED
    }

    /** UI drafts belong to an accepted event instance, including its in-flight computation. */
    fun isEventPending(event: ComponentEvent): Boolean =
        manualJobs.values.any { command -> !command.cancelled && command.events.any { it === event } }

    /** Discard a single queued event and any unpublished computation it started. */
    fun cancelEvent(event: ComponentEvent) {
        manualJobs.values.firstOrNull { it.events.singleOrNull() === event }?.cancelled = true
    }

    /** Distinct input transitions remain FIFO; running execution resumes after the final one. */
    fun submitStimulus(
        inputs: Map<String, LogicValue>,
        vectorInputs: Map<String, LogicVector>,
        events: Map<String, List<ComponentEvent>> = emptyMap(),
    ): Job? {
        if (!scope.isActive) { close(); return null }
        if (closed) return null
        val release = releasesInputs(capture().project, events)
        val resume = active && (resumeAfterStimulus || mutableStatus.value.phase == SimulationRunPhase.RUNNING)
        resumeAfterStimulus = resume
        val command = enqueue(SimulationStep.SETTLE, releasesInput = release, events = events.values.flatten()) { it.copy(inputs = inputs, vectorInputs = vectorInputs, events = events) }
        if (command == null) resumeAfterStimulus = false
        return command
    }

    private fun enqueueInitialization(initialization: Initialization) =
        enqueue(initialization.step, initialization = initialization)

    private fun enqueue(
        step: SimulationStep,
        releasesInput: Boolean = false,
        initialization: Initialization? = null,
        events: List<ComponentEvent> = emptyList(),
        transform: (SimulationRunSnapshot) -> SimulationRunSnapshot = { it },
    ): Job? {
        if (!scope.isActive) { close(); return null }
        if (closed || !active && !releasesInput) return null
        if (step == SimulationStep.RESET || mutableStatus.value.pendingSteps == 0) stopWork()
        val ticket = generation
        val previous = manualTail
        val commandId = ++manualTailId
        val command = ManualCommand(step, transform, releasesInput, events.toList(), initialization)
        mutableStatus.value = mutableStatus.value.copy(
            phase = if (active) SimulationRunPhase.STEPPING else SimulationRunPhase.INACTIVE, failure = null,
            pendingSteps = mutableStatus.value.pendingSteps + 1,
            latestManualRequest = commandId,
        )
        val job = scope.launch {
            try {
                previous?.join()
                if (ticket == generation) perform(ticket, step, releasesInput, initialization, transform, command)
            } catch (_: CancellationException) {
                // A newer control action owns the next visible state.
            } catch (error: Exception) {
                fail(ticket, error)
            } finally {
                if (ticket == generation && !closed) {
                    mutableStatus.value = mutableStatus.value.copy(
                        pendingSteps = (mutableStatus.value.pendingSteps - 1).coerceAtLeast(0),
                        phase = if (!active) SimulationRunPhase.INACTIVE
                            else if (commandId == manualTailId) SimulationRunPhase.PAUSED else mutableStatus.value.phase,
                    )
                    if (commandId == manualTailId && resumeAfterStimulus) {
                        resumeAfterStimulus = false
                        start()
                    }
                }
            }
        }
        manualTail = job
        manualJobs[job] = command
        job.invokeOnCompletion { manualJobs.remove(job) }
        return job
    }

    fun setAutomaticTicks(enabled: Boolean) {
        if (closed || enabled == mutableStatus.value.automaticTicks) return
        val restart = mutableStatus.value.phase == SimulationRunPhase.RUNNING
        if (restart) pause()
        mutableStatus.value = mutableStatus.value.copy(automaticTicks = enabled)
        if (restart) start()
    }

    /**
     * Sets the automatic tick rate; a running loop with automatic ticks restarts at the new rate. Pass [restartRunning]
     * when the caller is about to replace the project for this change (EditorState.setTickFrequency): any running loop
     * then restarts first, so a step still computing exits on its ticket instead of pausing on the stale revision.
     * Queued or computing commands, such as an input change made while running, start again in the new generation:
     * they capture the replaced project, and a run waiting for them resumes after the last one.
     */
    fun configureFrequency(frequencyHz: Double, restartRunning: Boolean = false): Boolean {
        require(frequencyHz.isFinite() && frequencyHz > 0) { "A frequência precisa ser positiva e finita." }
        if (closed) return false
        if (frequencyHz == mutableStatus.value.frequencyHz) return true
        val restart = mutableStatus.value.phase == SimulationRunPhase.RUNNING &&
            (mutableStatus.value.automaticTicks || restartRunning)
        val requeued = if (restartRunning && active && mutableStatus.value.pendingSteps > 0)
            manualJobs.values.filter { !it.cancelled } else emptyList()
        if (restart) pause()
        // stopWork keeps resumeAfterStimulus, so the last requeued command resumes a run it interrupted.
        else if (requeued.isNotEmpty()) stopWork()
        mutableStatus.value = mutableStatus.value.copy(frequencyHz = frequencyHz)
        if (restart) start()
        requeued.forEach { enqueue(it.step, it.releasesInput, it.initialization, it.events, it.transform) }
        return true
    }

    /** Returning to the foreground leaves execution paused until the user resumes it. */
    fun setActive(active: Boolean) {
        if (closed || this.active == active) return
        val releases = manualJobs.values.filter { it.releasesInput && !it.cancelled }
        val initialization = pendingInitialization?.takeIf { isCurrent(it.snapshot) }
        this.active = active
        pause()
        // A document still needs its first simulation after a lifecycle cancellation.
        // The saved snapshot guards ownership; execution captures the current startup drives.
        pendingInitialization = initialization
        if (active && initialization != null) enqueueInitialization(initialization)
        // A lifecycle transition stops automatic/manual work, but accepted input cleanup
        // still has to clear held inputs. Each release runs once in the new generation.
        releases.forEach { enqueue(it.step, releasesInput = true, events = it.events, transform = it.transform) }
    }

    /** Invalidate before replacing a project, editing it, or changing external input. */
    fun invalidate() = pause()

    fun close() {
        if (closed) return
        stopWork()
        pendingInitialization = null
        active = false
        closed = true
        mutableStatus.value = mutableStatus.value.copy(phase = SimulationRunPhase.CLOSED)
    }

    private suspend fun perform(
        ticket: Long,
        step: SimulationStep,
        releasesInput: Boolean = false,
        initialization: Initialization? = null,
        transform: (SimulationRunSnapshot) -> SimulationRunSnapshot = { it },
        command: ManualCommand? = null,
    ): Boolean = advanceLock.withLock {
        if (ticket != generation || !active && !releasesInput || closed || command?.cancelled == true) return@withLock false
        if (initialization != null && (pendingInitialization !== initialization || !isCurrent(initialization.snapshot))) {
            if (pendingInitialization === initialization) pendingInitialization = null
            return@withLock false
        }
        val snapshot = if (initialization == null) transform(capture()) else
            capture().copy(purpose = SimulationRequestPurpose.DOCUMENT_INITIALIZATION)
        val advanced = withContext(workerDispatcher) {
            advanceWithObservations?.invoke(snapshot, step)
                ?: SimulationAdvanceResult(advance(snapshot, step), emptyList())
        }
        val result = advanced.state
        coroutineContext.ensureActive()
        if (ticket != generation || !active && !releasesInput || closed || command?.cancelled == true) return@withLock false
        if (initialization != null && pendingInitialization !== initialization) return@withLock false
        if (!isCurrent(snapshot)) {
            pause()
            return@withLock false
        }
        publishObservations(snapshot, advanced.observations)
        publish(snapshot, result)
        if (initialization != null && pendingInitialization === initialization) pendingInitialization = null
        val measuredRate = if (step == SimulationStep.TICK && ticket == generation &&
            mutableStatus.value.phase == SimulationRunPhase.RUNNING && result.ticks > snapshot.previous.ticks
        ) measureCompletedTick() else mutableStatus.value.measuredTicksPerSecond
        mutableStatus.value = mutableStatus.value.copy(
            completedSteps = if (step == SimulationStep.RESET) 0 else mutableStatus.value.completedSteps + 1,
            lastStep = step,
            measuredTicksPerSecond = measuredRate,
        )
        if (result.oscillating) {
            pause()
            return@withLock false
        }
        true
    }

    private fun measureCompletedTick(): Double? {
        val start = tickRateWindowStart
        if (start == null) {
            tickRateWindowStart = tickRateTimeSource.markNow()
            return null
        }
        ticksInRateWindow++
        val elapsed = start.elapsedNow()
        if (elapsed.inWholeNanoseconds <= 0L) return mutableStatus.value.measuredTicksPerSecond
        val rate = ticksInRateWindow / (elapsed / 1.seconds)
        if (elapsed >= 1.seconds) {
            tickRateWindowStart = tickRateTimeSource.markNow()
            ticksInRateWindow = 0
        }
        return rate
    }

    private fun stopWork() {
        generation++
        work?.cancel()
        work = null
        manualJobs.keys.toList().forEach { it.cancel() }
        manualJobs.clear()
        manualTail = null
        tickRateWindowStart = null
        ticksInRateWindow = 0
        mutableStatus.value = mutableStatus.value.copy(pendingSteps = 0, measuredTicksPerSecond = null)
    }

    private fun fail(ticket: Long, error: Throwable) {
        if (ticket != generation || closed) return
        pendingInitialization = null
        stopWork()
        mutableStatus.value = mutableStatus.value.copy(
            phase = if (active) SimulationRunPhase.PAUSED else SimulationRunPhase.INACTIVE,
            failure = simulationFailureText(error),
        )
        onFailure(error)
    }

    companion object {
        fun periodMillis(frequencyHz: Double): Long {
            require(frequencyHz.isFinite() && frequencyHz > 0) { "A frequência precisa ser positiva e finita." }
            return (1000.0 / frequencyHz).roundToLong().coerceAtLeast(1)
        }
    }
}

/**
 * What a failed run reports: a localized failure's own text, otherwise the catalog's "Simulation failed." in the app
 * language. An exception's raw message is never shown; it is neither translated nor written for users (C161).
 */
internal fun simulationFailureText(error: Throwable): LocalizedText =
    (error as? LocalizedFailure)?.text ?: message(Messages.CoreSimulationFailed)
