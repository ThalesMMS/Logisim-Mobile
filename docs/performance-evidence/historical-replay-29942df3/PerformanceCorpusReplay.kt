package dev.logisim.mobile.core

import java.io.File
import java.security.MessageDigest

/** Replay existing historical bytes. This entry point records no timings and exports no XML. */
object PerformanceCorpusReplay {
    private const val MANIFEST_SHA256 = "295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58"
    private const val SAP_SHA256 = "2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: new-receipt.json copied-manifest.json copied-sap1.circ" }
        val receipt = File(args[0])
        require(!receipt.exists()) { "Use a new replay receipt." }
        val manifest = File(args[1])
        val manifestBytes = manifest.readBytes()
        check(sha256(manifestBytes) == MANIFEST_SHA256) { "Historical manifest bytes differ" }
        val entries = CompanionJsonCodec.parseObject(manifestBytes.decodeToString()).array("cases")
            .map { it as? CompanionJsonObject ?: error("Corpus case must be an object") }
        val expectedCases = listOf("independent", "hierarchical").flatMap { kind ->
            listOf(16, 64, 256, 384, 1024).map { kind to it }
        }
        check(entries.map { it.string("case") to it.long("banks").toInt() } == expectedCases)
        check(manifest.parentFile.listFiles()?.map { it.name }?.toSet() ==
            expectedCases.map { (kind, banks) -> "$kind-$banks.circ" }.toSet() + "manifest.json")
        val results = entries.map { entry -> replayBank(manifest.parentFile, entry) }
        val sapBytes = File(args[2]).readBytes()
        check(sapBytes.size == 47_528 && sha256(sapBytes) == SAP_SHA256) { "Historical SAP-1 bytes differ" }
        replaySap(sapBytes.decodeToString())
        receipt.absoluteFile.parentFile.mkdirs()
        receipt.writeText(CompanionJsonCodec.write(CompanionJsonObject(mapOf(
            "schema" to CompanionJsonNumber(1L),
            "scope" to CompanionJsonString("functional_replay"),
            "benchmark_executed" to CompanionJsonBoolean(false),
            "cases" to CompanionJsonArray(results),
            "accepted" to CompanionJsonNumber(8L), "rejected" to CompanionJsonNumber(2L),
            "sap_ticks" to CompanionJsonNumber(160L),
            "sap_display_hex" to CompanionJsonString("08"),
            "metadata_reference_limit" to CompanionJsonString(
                "Independent literals check labels, widths, counts, topology, hierarchy and SAP behavior. " +
                    "The canonical roundtrip and PerformanceOracle derive their full metadata reference from the first import; " +
                    "they do not independently prove preservation of every original XML metadata field."),
        ))) + "\n")
        println(receipt)
    }

    private fun replayBank(directory: File, entry: CompanionJsonObject): CompanionJsonObject {
        val kind = entry.string("case")
        val banks = entry.long("banks").toInt()
        val name = "$kind-$banks.circ"
        check(entry.string("file") == name)
        val bytes = File(directory, name).readBytes()
        check(bytes.size.toLong() == entry.long("bytes") && sha256(bytes) == entry.string("sha256"))
        check(entry.long("main_components") == 4L * banks && entry.long("main_wires") == banks.toLong())
        check(entry.string("low_output_hex") == "00" && entry.string("high_output_hex") == "5a")
        check(entry.array("driven_input_labels") == (0 until banks).map { CompanionJsonString("a-$it") })
        val imported = CircCodec.readProject(bytes.decodeToString())
        if (banks == 1024) {
            check(entry.string("import_status") == "rejected" && entry.long("verified_outputs") == 0L)
            check(imported is CircProjectImportResult.Failure) { "$name must retain its historical rejection" }
            check(imported.notices.map { it.key } == listOf("error_xml_structure"))
            check(entry.array("rejection_notice_keys") == listOf(CompanionJsonString("error_xml_structure")))
        } else {
            check(entry.string("import_status") == "accepted" && entry.long("verified_outputs") == banks.toLong())
            check(entry.array("rejection_notice_keys").isEmpty())
            check(imported is CircProjectImportResult.Success) { "$name must import" }
            val fixture = verifyBankShape(imported.project, kind, banks)
            val oracle = PerformanceOracle(fixture)
            val canonical = CircCodec.writeProject(imported.project)
            val reopened = CircCodec.readProject(canonical)
            oracle.verifyImported(reopened)
            check(reopened is CircProjectImportResult.Success)
            check(CircCodec.writeProject(reopened.project) == canonical)
            verifyBankShape(reopened.project, kind, banks)
            val simulator = CircuitSimulator()
            var state = SimulationState()
            for (high in listOf(false, true, false)) {
                state = simulator.settle(fixture.project, state, vectorInputs = fixture.inputs(high))
                oracle.verifyState(fixture, state, high)
                check(state.diagnostics.isEmpty()) { "$name main diagnostics: ${state.diagnostics}" }
                check(state.circuitStates.values.all { it.diagnostics.isEmpty() }) { "$name child diagnostics" }
            }
        }
        return CompanionJsonObject(mapOf(
            "file" to CompanionJsonString(name), "sha256" to CompanionJsonString(sha256(bytes)),
            "import_status" to CompanionJsonString(if (banks == 1024) "rejected" else "accepted"),
            "verified_outputs" to CompanionJsonNumber(if (banks == 1024) 0L else banks.toLong()),
        ))
    }

    private fun verifyBankShape(project: CircuitProject, kind: String, banks: Int): PerformanceCircuits.Case {
        val hierarchical = kind == "hierarchical"
        val mainName = if (hierarchical) "hierarchy-$banks" else "gates-$banks"
        check(project.sourceVersion == "3.8.0" && project.mainCircuitName == mainName)
        check(project.circuits.map { it.name } == if (hierarchical) listOf(mainName, "and8") else listOf(mainName))
        val main = project.mainCircuit
        check(main.id == mainName && main.components.size == 4 * banks && main.wires.size == banks)
        val pins = main.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
        val labels = (0 until banks).flatMap { listOf("a-$it", "b-$it", "out-$it") }
        check(pins.size == 3 * banks && pins.map { it.label }.toSet() == labels.toSet())
        check(pins.map { it.label }.distinct().size == pins.size && pins.all { it.width == 8 })
        check(main.components.map { it.kind to it.position }.distinct().size == main.components.size)
        val byLabel = pins.associateBy { it.label }
        val expectedWires = mutableListOf<WireSegment>()
        repeat(banks) { index ->
            val x = 100 + index % 32 * 200
            val y = 100 + index / 32 * 100
            val a = byLabel.getValue("a-$index")
            val b = byLabel.getValue("b-$index")
            val out = byLabel.getValue("out-$index")
            check(a.kind == ComponentKind.INPUT && b.kind == ComponentKind.INPUT && out.kind == ComponentKind.OUTPUT)
            check(a.position == GridPoint(x - if (hierarchical) 40 else 50, y - if (hierarchical) 10 else 20))
            check(b.position == GridPoint(x - if (hierarchical) 40 else 50, y + if (hierarchical) 10 else 20))
            check(out.position == GridPoint(x + if (hierarchical) 70 else 40, y))
            val gateKind = if (hierarchical) ComponentKind.SUBCIRCUIT else ComponentKind.AND
            val gate = main.components.single { it.kind == gateKind && it.position == GridPoint(x, y) }
            if (hierarchical) {
                check(gate.circuitName == "and8")
                check(gate.explicitPorts.map { Triple(it.position, it.direction, it.width) }.toSet() == setOf(
                    Triple(GridPoint(-40, -10), PortDirection.INPUT, 8),
                    Triple(GridPoint(-40, 10), PortDirection.INPUT, 8),
                    Triple(GridPoint(40, 0), PortDirection.OUTPUT, 8)))
                check(gate.explicitPorts.size == 3)
                check(project.subcircuitPortBindings(gate).map { (point, pin) -> point to pin.label }.toSet() == setOf(
                    GridPoint(x - 40, y - 10) to "a", GridPoint(x - 40, y + 10) to "b", GridPoint(x + 40, y) to "out"))
            } else {
                check(gate.width == 8 && gate.attributeInt("inputs", 2) == 2 && gate.facing == "east")
                check(gate.inputPorts() == listOf(a.position, b.position))
            }
            expectedWires += WireSegment(GridPoint(x + if (hierarchical) 40 else 0, y), out.position)
        }
        check(main.wires == expectedWires)
        if (hierarchical) verifyChild(project.circuits[1])
        val driven = (0 until banks).map { byLabel.getValue("a-$it").id }
        val outputs = (0 until banks).map { byLabel.getValue("out-$it").id }
        val highInputs = buildMap {
            repeat(banks) { index ->
                put(byLabel.getValue("a-$index").id, LogicVector.high(8))
                put(byLabel.getValue("b-$index").id, LogicVector.of(8, 0x5auL))
            }
        }
        return PerformanceCircuits.Case(project, driven, outputs, highInputs)
    }

    private fun verifyChild(child: CircuitDocument) {
        check(child.id == "and8" && child.components.size == 4 && child.attributes["appearance"] == "custom")
        val a = child.components.single { it.label == "a" }
        val b = child.components.single { it.label == "b" }
        val out = child.components.single { it.label == "out" }
        val gate = child.components.single { it.kind == ComponentKind.AND }
        check(a.kind == ComponentKind.INPUT && a.position == GridPoint(0, 0) && a.width == 8)
        check(b.kind == ComponentKind.INPUT && b.position == GridPoint(0, 40) && b.width == 8)
        check(out.kind == ComponentKind.OUTPUT && out.position == GridPoint(140, 20) && out.width == 8)
        check(gate.position == GridPoint(100, 20) && gate.width == 8)
        check(gate.inputPorts() == listOf(GridPoint(50, 0), GridPoint(50, 40)))
        check(child.wires == listOf(WireSegment(GridPoint(0, 0), GridPoint(50, 0)),
            WireSegment(GridPoint(0, 40), GridPoint(50, 40)), WireSegment(GridPoint(100, 20), GridPoint(140, 20))))
    }

    private fun replaySap(xml: String) {
        val imported = CircCodec.readProject(xml)
        check(imported is CircProjectImportResult.Success) { "Historical SAP-1 must import" }
        val project = imported.project
        check(project.sourceVersion == "3.8.0" && project.mainCircuitName == "main")
        check(project.circuits.map { it.name } == listOf("main", "CP", "Acumulador", "Somador_Subtrator",
            "Registrador_B_6a147718", "REM", "REGISTRADOR_DE_INSTRUCOES_2d63c7b0"))
        check(project.circuits.sumOf { it.components.size } == 156 && project.circuits.sumOf { it.wires.size } == 318)
        val roms = project.mainCircuit.components.filter { it.kind == ComponentKind.ROM }
        check(roms.size == 3)
        val canonical = CircCodec.writeProject(project)
        val reopened = CircCodec.readProject(canonical)
        check(reopened is CircProjectImportResult.Success)
        check(reopened.project.circuits.map { it.name } == project.circuits.map { it.name })
        check(reopened.project.circuits.map { it.wires } == project.circuits.map { it.wires })
        check(reopened.project.mainCircuit.components.filter { it.kind == ComponentKind.ROM }.map { it.attributes["contents"] } ==
            roms.map { it.attributes["contents"] })
        check(CircCodec.writeProject(reopened.project) == canonical)
        val reset = project.mainCircuit.components.single { it.label == "Reset" }
        val display = project.mainCircuit.components.single { it.kind == ComponentKind.REGISTER && it.position == GridPoint(600, 340) }
        val simulator = CircuitSimulator()
        var state = simulator.settle(project)
        state = simulator.settle(project, state, mapOf(reset.id to LogicValue.HIGH))
        state = simulator.settle(project, state, mapOf(reset.id to LogicValue.LOW))
        check(state.vectorValues.getValue(display.id).toULongOrNull() == 0uL)
        repeat(160) { index ->
            state = simulator.settle(project, state, tick = true)
            check(!state.oscillating) { "SAP-1 oscillated at tick ${index + 1}" }
            check(state.vectorValues.getValue(display.id).toULongOrNull() == if (index + 1 < 56) 0uL else 8uL) {
                "SAP-1 display differs at tick ${index + 1}"
            }
        }
        check(state.ticks == 160L && state.circuitStates.size == 6)
        check(state.circuitName == "main" && state.instancePath.isEmpty())
        check(state.diagnostics.isEmpty() && state.circuitStates.values.all { it.diagnostics.isEmpty() })
        val children = project.mainCircuit.components.filter { it.kind == ComponentKind.SUBCIRCUIT }
        check(children.map { it.circuitName }.toSet() == project.circuits.drop(1).map { it.name }.toSet())
        check(state.circuitStates.keys == children.map { it.id }.toSet())
        for (child in children) {
            val childState = state.circuitStates.getValue(child.id)
            check(childState.circuitName == child.circuitName && childState.instancePath == child.id)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
