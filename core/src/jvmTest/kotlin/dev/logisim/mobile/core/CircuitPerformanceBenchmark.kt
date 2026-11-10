package dev.logisim.mobile.core

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil

private data class Measurement(
    val wallNanos: Long,
    val cpuNanos: Long?,
    val allocatedBytes: Long?,
    val heapBytes: Long,
)

private data class Measured<T>(val value: T, val measurement: Measurement)

private class Meter {
    private val thread = ManagementFactory.getThreadMXBean()
    private val allocation = thread as? com.sun.management.ThreadMXBean
    private val memory = ManagementFactory.getMemoryMXBean()

    init {
        if (thread.isThreadCpuTimeSupported) thread.isThreadCpuTimeEnabled = true
        if (allocation?.isThreadAllocatedMemorySupported == true) allocation.isThreadAllocatedMemoryEnabled = true
    }

    fun <T> measure(block: () -> T): Measured<T> {
        val id = Thread.currentThread().threadId()
        val cpuBefore = if (thread.isThreadCpuTimeEnabled) thread.currentThreadCpuTime else null
        val allocatedBefore = allocation?.takeIf { it.isThreadAllocatedMemoryEnabled }?.getThreadAllocatedBytes(id)
        val before = System.nanoTime()
        val value = block()
        val wall = System.nanoTime() - before
        val measurement = Measurement(wall,
            cpuBefore?.let { thread.currentThreadCpuTime - it },
            allocatedBefore?.let { allocation.getThreadAllocatedBytes(id) - it },
            memory.heapMemoryUsage.used)
        return Measured(value, measurement)
    }
}

private fun List<Long>.percentile(fraction: Double): Long = sorted()[
    (ceil(size * fraction).toInt() - 1).coerceIn(indices)]

private fun jsonText(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 32) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
    }
    append('"')
}

/** JVM-only measurement entrypoint. The benchmark makes no device or energy claims. */
fun main(args: Array<String>) {
    require(args.size in 4..5) { "Usage: output.json sizes iterations warmups [phases]" }
    val output = File(args[0])
    val sizes = args[1].split(',').map(String::toInt)
    val iterations = args[2].toInt().also { require(it in 3..1000) }
    val warmups = args[3].toInt().also { require(it in 0..100) }
    val phases = args.getOrNull(4)?.split(',')?.toSet()
    val meter = Meter()
    val results = mutableListOf<String>()
    val csv = StringBuilder("case,size,components,wires,phase,iteration,wall_ns,cpu_ns,allocated_bytes,heap_bytes\n")
    val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    val gcCountBefore = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
    val gcMillisBefore = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
    for ((name, factory) in listOf("independent" to PerformanceCircuits::independentGates,
        "hierarchical" to PerformanceCircuits::hierarchicalGates)) {
        for (size in sizes) {
            val fixture = factory(size)
            val high = fixture.inputs(true)
            val low = fixture.inputs(false)
            val oracle = PerformanceOracle(fixture)
            val xml = oracle.serializedXml
            val restored = oracle.referenceFixture
            oracle.verifyState(restored, CircuitSimulator().settle(restored.project, vectorInputs = restored.inputs(true)), true)
            val components = fixture.project.mainCircuit.components.size
            val wires = fixture.project.mainCircuit.wires.size
            fun <T> record(phase: String, operation: (Int) -> T, verify: (Int, T) -> Unit) {
                if (phases != null && phase !in phases) return
                repeat(warmups) { verify(it, operation(it)) }
                val samples = List(iterations) { iteration ->
                    val measured = meter.measure { operation(iteration) }
                    verify(iteration, measured.value)
                    measured.measurement.also { sample ->
                        csv.append("$name,$size,$components,$wires,$phase,$iteration,${sample.wallNanos},")
                            .append(sample.cpuNanos ?: "").append(',')
                            .append(sample.allocatedBytes ?: "").append(',').append(sample.heapBytes).append('\n')
                    }
                }
                val wall = samples.map { it.wallNanos }
                val cpu = samples.mapNotNull { it.cpuNanos }
                val allocation = samples.mapNotNull { it.allocatedBytes }
                results += """{"case":"$name","size":$size,"components":$components,"wires":$wires,"phase":"$phase","iterations":$iterations,"wall_median_ns":${wall.percentile(0.5)},"wall_p95_ns":${wall.percentile(0.95)},"wall_max_ns":${wall.max()},"cpu_median_ns":${cpu.takeIf { it.isNotEmpty() }?.percentile(0.5)},"allocated_median_bytes":${allocation.takeIf { it.isNotEmpty() }?.percentile(0.5)},"heap_max_bytes":${samples.maxOf { it.heapBytes }}}"""
                System.err.println("$name/$size $phase median=${wall.percentile(0.5) / 1_000_000.0}ms")
            }
            record("serialize", { CircCodec.writeProject(fixture.project) }) { _, actual ->
                oracle.verifySerialized(actual)
            }
            record("import", { CircCodec.readProject(xml) }) { _, imported ->
                oracle.verifyImported(imported)
            }
            record("cold_settle", { iteration ->
                val highInput = iteration % 2 == 0
                CircuitSimulator().settle(fixture.project, vectorInputs = if (highInput) high else low)
            }) { iteration, actual ->
                oracle.verifyState(fixture, actual, iteration % 2 == 0)
            }
            val simulator = CircuitSimulator()
            var state = simulator.settle(fixture.project, vectorInputs = low)
            var lastInputHigh = false
            oracle.verifyState(fixture, state, lastInputHigh)
            record("input_change", {
                lastInputHigh = !lastInputHigh
                state = simulator.settle(fixture.project, state, vectorInputs = if (lastInputHigh) high else low)
                state
            }) { _, actual ->
                oracle.verifyState(fixture, actual, lastInputHigh)
            }
            record("unchanged_settle", {
                state = simulator.settle(fixture.project, state)
                state
            }) { _, actual ->
                oracle.verifyState(fixture, actual, lastInputHigh)
            }
        }
    }
    output.parentFile?.mkdirs()
    output.writeText(buildString {
        append("{\n  \"schema\": 2,\n  \"runtime\": \"JVM\",\n")
        append("  \"verification_scope\": \"outside_timed_regions\",\n")
        append("  \"cold_settle_scope\": \"new_simulator_in_warm_jvm\",\n")
        append("  \"gc_scope\": \"benchmark_loop_including_preparation_warmups_and_verification\",\n")
        append("  \"java_version\": ").append(jsonText(System.getProperty("java.version"))).append(",\n")
        append("  \"os\": ").append(jsonText(System.getProperty("os.name"))).append(",\n")
        append("  \"architecture\": ").append(jsonText(System.getProperty("os.arch"))).append(",\n")
        append("  \"processors\": ${Runtime.getRuntime().availableProcessors()},\n")
        append("  \"max_heap_bytes\": ${Runtime.getRuntime().maxMemory()},\n")
        append("  \"warmups\": $warmups,\n")
        append("  \"gc_count\": ${collectors.sumOf { it.collectionCount.coerceAtLeast(0) } - gcCountBefore},\n")
        append("  \"gc_millis\": ${collectors.sumOf { it.collectionTime.coerceAtLeast(0) } - gcMillisBefore},\n")
        append("  \"unmeasured\": [\"draw_frame\",\"pan_zoom\",\"device_energy\",\"background_execution\",\"cancellation_latency\"],\n")
        append("  \"results\": [\n    ").append(results.joinToString(",\n    ")).append("\n  ]\n}\n")
    })
    File(output.parentFile, output.nameWithoutExtension + ".csv").writeText(csv.toString())
}
