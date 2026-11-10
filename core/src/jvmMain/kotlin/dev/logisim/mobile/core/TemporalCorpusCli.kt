package dev.logisim.mobile.core

import java.io.File

/** JVM entry point for comparing propagation timestamps with the desktop oracle. */
object TemporalCorpusCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size in 2..3) { "Usage: TemporalCorpusCli fixture.circ operations.tsv [step]" }
        val imported = CircCodec.readProject(File(arguments[0]).readText())
        val project = (imported as? CircProjectImportResult.Success)?.project ?: error("Cannot import ${arguments[0]}: $imported")
        for (event in CircuitTemporalTrace.run(project, File(arguments[1]).readText(), arguments.getOrNull(2) == "step")) {
            println("E\t${event.action}\t${event.time}\t${event.probe}\t${event.bits}")
        }
    }
}
