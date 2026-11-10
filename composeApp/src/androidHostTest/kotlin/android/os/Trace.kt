package android.os

/** The empty-composition JVM probes do not publish Android profiling events. */
@Suppress("UNUSED_PARAMETER")
object Trace {
    @JvmStatic fun beginSection(name: String) = Unit
    @JvmStatic fun endSection() = Unit
}
