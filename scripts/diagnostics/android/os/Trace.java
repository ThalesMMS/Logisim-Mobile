package android.os;

/** JVM-only adapter for Android tracing, used solely by the headless diagnostic. */
public final class Trace {
    private Trace() {}
    public static void beginSection(String sectionName) {}
    public static void endSection() {}
    public static boolean isEnabled() { return false; }
}
