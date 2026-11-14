package dev.logisim.mobile.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Evolution's tick frequencies (d58a61e0): MenuSimulate.SUPPORTED_TICK_FREQUENCIES, the Simulate menu's radio items,
 * and UnifiedHeaderBar.showFrequencyMenu's presets. Mobile offers the part its run loop sustains.
 */
object TickFrequencies {
    /** MenuSimulate.SUPPORTED_TICK_FREQUENCIES, slowest first. */
    val supported: List<Double> = listOf(
        2048000.0, 1024000.0, 512000.0, 256000.0, 128000.0, 64000.0, 32000.0, 16000.0, 8000.0, 4000.0,
        2000.0, 1000.0, 512.0, 256.0, 128.0, 64.0, 32.0, 16.0, 8.0, 4.0, 2.0, 1.0, 0.5, 0.25,
    ).reversed()

    /** The presets of the header's frequency button (UnifiedHeaderBar.showFrequencyMenu), 0.25 Hz to 4 kHz. */
    val headerPresets: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0,
        1000.0, 2000.0, 4000.0)

    /**
     * The fastest rate offered. SimulationRunController wakes at most once a millisecond and runs one tick per wake,
     * so faster choices would only be labels; a stored faster value is kept and shown with its measured rate.
     */
    const val MAX_SUSTAINED_HZ: Double = 1000.0

    /** The values Mobile offers in the toolbar, the Simulate menu, Simulation options and Preferences. */
    val choices: List<Double> = supported.filter { it <= MAX_SUSTAINED_HZ }

    /** Whether two frequencies name the same rate; imported text such as "0.250" or "4" need not be bit-identical. */
    fun same(a: Double, b: Double): Boolean = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))

    /** The offered value that [hz] names, or null for a value outside [choices]. */
    fun choiceFor(hz: Double): Double? = choices.firstOrNull { same(it, hz) }

    /** [choices] plus [current] in its sorted place when no choice names it, so a stored value stays selectable. */
    fun choicesWith(current: Double): List<TickFrequencyChoice> {
        val listed = choices.map { TickFrequencyChoice(it, custom = false) }
        if (choiceFor(current) != null || !current.isFinite() || current <= 0) return listed
        return (listed + TickFrequencyChoice(current, custom = true)).sortedBy { it.hz }
    }

    fun isSustained(hz: Double): Boolean = hz <= MAX_SUSTAINED_HZ || same(hz, MAX_SUSTAINED_HZ)
}

/** One frequency of a picker; [custom] marks a stored value that is not one of Evolution's. */
data class TickFrequencyChoice(val hz: Double, val custom: Boolean)

/**
 * MenuSimulate.getTickFrequencyStrings and TickFrequencyChoice.localeChanged: whole hertz below 1000 Hz without a
 * decimal part, other values below 1000 Hz as written, and kilohertz rounded to one decimal from 1000 Hz up.
 * The units are SI symbols, as in Evolution's simulateTickFreqItem and simulateTickKFreqItem.
 */
fun formatTickFrequency(hz: Double): String {
    // Java's Math.round, as Evolution rounds: ties go up.
    fun javaRound(value: Double) = FloatingPointNumbers.roundToLong(value)
    if (hz < 1000) {
        val whole = abs(hz - javaRound(hz)) < 0.0001
        return (if (whole) javaRound(hz).toString() else hz.toString()) + " Hz"
    }
    val kilohertz = javaRound(hz / 100) / 10.0
    val text = if (kilohertz == javaRound(kilohertz).toDouble()) javaRound(kilohertz).toString() else kilohertz.toString()
    return "$text kHz"
}
