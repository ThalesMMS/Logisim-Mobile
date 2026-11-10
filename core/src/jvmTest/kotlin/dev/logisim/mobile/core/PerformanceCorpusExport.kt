package dev.logisim.mobile.core

import java.io.File
import java.security.MessageDigest

/** Export the same bank topology for native-host drawing and import measurements. */
object PerformanceCorpusExport {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: new-output-directory sizes" }
        val directory = File(args[0])
        require(!directory.exists()) { "Use a new corpus directory." }
        val sizes = args[1].split(',').map(String::toInt)
        require(sizes.isNotEmpty() && sizes.all { it in 1..16_384 })
        directory.mkdirs()
        val entries = mutableListOf<CompanionJson>()
        for ((name, factory) in listOf("independent" to PerformanceCircuits::independentGates,
            "hierarchical" to PerformanceCircuits::hierarchicalGates)) {
            for (size in sizes) {
                val fixture = factory(size)
                val labelled = fixture.project.copy(circuits = fixture.project.circuits.map { circuit ->
                    circuit.copy(components = circuit.components.map { component ->
                        if (component.kind == ComponentKind.INPUT || component.kind == ComponentKind.OUTPUT)
                            component.copy(label = component.id) else component
                    })
                })
                val bytes = CircCodec.writeProject(labelled).encodeToByteArray()
                val imported = CircCodec.readProject(bytes.decodeToString())
                val status: String
                val verifiedOutputs: Int
                var rejectionNotices = emptyList<String>()
                when (imported) {
                    is CircProjectImportResult.Success -> {
                        val restored = fixture.rebound(imported.project)
                        var state = SimulationState()
                        for (high in listOf(false, true, false)) {
                            state = CircuitSimulator().settle(restored.project, state, vectorInputs = restored.inputs(high))
                            check(!state.oscillating)
                            restored.observedOutputs.forEach { id -> check(state.vectorValues[id] == fixture.expected(high)) }
                        }
                        status = "accepted"
                        verifiedOutputs = restored.observedOutputs.size
                    }
                    is CircProjectImportResult.Failure -> {
                        status = "rejected"
                        verifiedOutputs = 0
                        rejectionNotices = imported.notices.map { it.key.ifBlank { "literal" } }
                    }
                }
                val filename = "$name-$size.circ"
                File(directory, filename).writeBytes(bytes)
                entries += CompanionJsonObject(mapOf(
                    "file" to CompanionJsonString(filename), "case" to CompanionJsonString(name),
                    "banks" to CompanionJsonNumber(size.toLong()),
                    "main_components" to CompanionJsonNumber(labelled.mainCircuit.components.size.toLong()),
                    "main_wires" to CompanionJsonNumber(labelled.mainCircuit.wires.size.toLong()),
                    "bytes" to CompanionJsonNumber(bytes.size.toLong()),
                    "sha256" to CompanionJsonString(MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }),
                    "import_status" to CompanionJsonString(status),
                    "rejection_notice_keys" to CompanionJsonArray(rejectionNotices.map(::CompanionJsonString)),
                    "verified_outputs" to CompanionJsonNumber(verifiedOutputs.toLong()),
                    "driven_input_labels" to CompanionJsonArray(fixture.drivenInputs.map(::CompanionJsonString)),
                    "low_output_hex" to CompanionJsonString("00"), "high_output_hex" to CompanionJsonString("5a"),
                ))
            }
        }
        File(directory, "manifest.json").writeText(CompanionJsonCodec.write(CompanionJsonObject(mapOf(
            "schema" to CompanionJsonNumber(1L), "corpus_version" to CompanionJsonNumber(2L),
            "description" to CompanionJsonString("8-bit independent and hierarchical banks with labelled inputs and outputs"),
            "verification" to CompanionJsonString("Accepted imports verify every output for low, high, low transitions; rejected imports retain their notice keys and exact XML"),
            "cases" to CompanionJsonArray(entries),
        ))) + "\n")
        println(File(directory, "manifest.json"))
    }
}
