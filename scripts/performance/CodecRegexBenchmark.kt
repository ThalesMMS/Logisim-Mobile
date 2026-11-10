package dev.logisim.mobile.core

import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest

private fun imported(xml: String): CircuitProject = when (val result = CircCodec.readProject(xml)) {
    is CircProjectImportResult.Success -> result.project
    is CircProjectImportResult.Failure -> error(result.diagnostics.joinToString())
}

private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Compare this unchanged harness with two separately compiled versions of CircCodec. */
fun main(args: Array<String>) {
    val iterations = args[1].toInt()
    val warmups = args[2].toInt()
    val large = buildString {
        append("<project><lib name=\"g\" desc=\"#Gates\"/><circuit name=\"large\">")
        repeat(2048) { index ->
            val x = index % 64 * 100
            val y = index / 64 * 100
            append("<comp lib=\"g\" name=\"NOT Gate\" loc=\"($x,$y)\"/>")
            append("<wire from=\"($x,$y)\" to=\"(${x + 40},$y)\"/>")
        }
        append("</circuit></project>")
    }
    val validPoints = mapOf("(0,0)" to GridPoint(0, 0), "( -1000000 ,1000000 )" to GridPoint(-1_000_000, 1_000_000),
        "(1,&#9;2)" to GridPoint(1, 2), "(-0,-0)" to GridPoint(0, 0), "(&#10;-12,&#13;34 )" to GridPoint(-12, 34))
    fun pointXml(point: String) = "<project><lib name=\"w\" desc=\"#Wiring\"/><circuit name=\"main\"><comp lib=\"w\" name=\"Pin\" loc=\"$point\"/></circuit></project>"
    for ((text, expected) in validPoints) check(imported(pointXml(text)).mainCircuit.components.single().position == expected)
    for (text in listOf("(+1,2)", "(1,2) ", "(1000001,0)", "(2147483648,0)", "(1 2)", "1,2", "(1.0,2)")) {
        check(CircCodec.readProject(pointXml(text)) is CircProjectImportResult.Failure) { "Accepted invalid point $text" }
    }
    val empty = imported("<project><circuit name=\"main\"/></project>")
    for (name in listOf("a", "_", "a0", "a-b", "a.b", "a:b")) {
        check(name in CircCodec.writeProject(empty.copy(metadata = mapOf(name to "kept"))))
    }
    for (name in listOf("", "0a", "é", "white space", "a/b")) {
        check(runCatching { CircCodec.writeProject(empty.copy(metadata = mapOf(name to "invalid"))) }.isFailure)
    }
    val thread = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    thread.isThreadCpuTimeEnabled = true
    thread.isThreadAllocatedMemoryEnabled = true
    fun median(values: List<Long>) = values.sorted()[values.size / 2]
    for ((name, xml) in listOf("sap1" to File(args[0]).readText(), "large2048" to large)) {
        val project = imported(xml)
        val exported = CircCodec.writeProject(project)
        val reopened = imported(exported)
        check(reopened.circuits.sumOf { it.components.size } == project.circuits.sumOf { it.components.size })
        check(reopened.circuits.sumOf { it.wires.size } == project.circuits.sumOf { it.wires.size })
        println("artifact\t$name\t${project.circuits.size}\t${project.circuits.sumOf { it.components.size }}\t${project.circuits.sumOf { it.wires.size }}\t${digest(exported)}")
        for (phase in listOf("import", "export")) {
            fun operation() {
                if (phase == "import") check(imported(xml).circuits.size == project.circuits.size)
                else check(CircCodec.writeProject(project) == exported)
            }
            repeat(warmups) { operation() }
            val wall = mutableListOf<Long>()
            val cpu = mutableListOf<Long>()
            val allocation = mutableListOf<Long>()
            repeat(iterations) {
                val allocated = thread.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val cpuStart = thread.currentThreadCpuTime
                val start = System.nanoTime()
                operation()
                wall += System.nanoTime() - start
                cpu += thread.currentThreadCpuTime - cpuStart
                allocation += thread.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated
            }
            println("measurement\t$name\t$phase\t${median(wall)}\t${median(cpu)}\t${median(allocation)}")
        }
    }
    println("grammar\t23\tpassed")
}
