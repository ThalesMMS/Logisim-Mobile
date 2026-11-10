package dev.logisim.mobile.core

import java.io.File
import kotlin.math.ceil

/** Measures intersection queries only; this is not a rendered-frame benchmark. */
object SpatialIndexBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Usage: output.json sizes iterations warmups" }
        val output = File(args[0])
        val sizes = args[1].split(',').map(String::toInt)
        val iterations = args[2].toInt()
        val warmups = args[3].toInt()
        val results = mutableListOf<String>()
        var checksum = 0L
        for (count in sizes) {
            val items = List(count) { index ->
                val x = (index % 1_000) * 40.0
                val y = (index / 1_000) * 40.0
                SpatialItem(index, SpatialRect(x, y, x + 20, y + 20))
            }
            val before = System.nanoTime()
            val index = SpatialIndex(items)
            val construction = System.nanoTime() - before
            val queries = List(64) { frame ->
                val x = (frame * 53 % 980) * 40.0
                val y = (frame * 7 % maxOf(1, count / 1_000)) * 40.0
                SpatialRect(x, y, x + 400, y + 400)
            }
            for (query in queries) {
                check(items.filter { it.bounds.intersects(query) }.map { it.value } == index.query(query))
            }
            fun sample(indexed: Boolean): Long {
                val start = System.nanoTime()
                for (query in queries) {
                    val visible = if (indexed) index.query(query) else
                        items.filter { it.bounds.intersects(query) }.map { it.value }
                    checksum += visible.sumOf { it.toLong() }
                }
                return System.nanoTime() - start
            }
            repeat(warmups) { sample(false); sample(true) }
            val linear = List(iterations) { sample(false) }.sorted()
            val indexed = List(iterations) { sample(true) }.sorted()
            fun List<Long>.p95() = this[(ceil(size * 0.95).toInt() - 1).coerceIn(indices)]
            results += """{"items":$count,"queries_per_iteration":${queries.size},"construction_ns":$construction,"linear_median_ns":${linear[iterations / 2]},"index_median_ns":${indexed[iterations / 2]},"linear_p95_ns":${linear.p95()},"index_p95_ns":${indexed.p95()}}"""
            System.err.println("spatial/$count linear=${linear[iterations / 2] / 1e6}ms index=${indexed[iterations / 2] / 1e6}ms")
        }
        output.parentFile?.mkdirs()
        output.writeText("""{"schema":1,"runtime":"JVM","workload":"spatial-query","checksum":$checksum,"unmeasured":["draw_frame","pan_zoom_frame","device_energy"],"results":[${results.joinToString(",")}]}""" + "\n")
    }
}
