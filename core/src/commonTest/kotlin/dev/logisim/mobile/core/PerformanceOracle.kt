package dev.logisim.mobile.core

/** Correctness checks run outside the benchmark's measured operations. */
internal class PerformanceOracle(fixture: PerformanceCircuits.Case) {
    private val original = detachedCase(fixture)
    val serializedXml: String = CircCodec.writeProject(original.project)
    private val referenceProject: CircuitProject
    private val referenceMigrations: List<CircMigration>
    private val canonicalXml: String
    private val instanceCircuits: Map<String, String>
    val referenceFixture: PerformanceCircuits.Case

    init {
        val outputs = original.project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }
        check(outputs.isNotEmpty()) { "fixture.observedOutputs must not be empty" }
        same("fixture.observedOutputs", outputs.map { it.id }, original.observedOutputs)
        check(outputs.all { it.width == 8 }) { "fixture outputs must have width 8" }
        val inputs = original.project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.map { it.id }.toSet()
        same("fixture.highInputs.keys", inputs, original.highInputs.keys)
        check(original.drivenInputs.isNotEmpty() && original.drivenInputs.distinct() == original.drivenInputs &&
            original.drivenInputs.all { it in inputs }) { "fixture.drivenInputs must identify distinct input components" }
        val imported = CircCodec.readProject(serializedXml)
        check(imported is CircProjectImportResult.Success) { "reference import failed" }
        referenceProject = detachedProject(imported.project)
        referenceMigrations = imported.migrations.toList()
        canonicalXml = CircCodec.writeProject(referenceProject)
        referenceFixture = rebound(detachedProject(referenceProject))
        instanceCircuits = collectInstanceCircuits(referenceProject)
    }

    fun verifySerialized(actual: String) {
        sameXml("serializedXml", serializedXml, actual)
    }

    fun verifyImported(result: CircProjectImportResult): PerformanceCircuits.Case {
        check(result is CircProjectImportResult.Success) { "import failed" }
        verifyProject(referenceProject, result.project)
        same("import.migrations", referenceMigrations, result.migrations)
        sameXml("import.canonicalXml", canonicalXml, CircCodec.writeProject(result.project))
        return rebound(result.project)
    }

    fun verifyState(case: PerformanceCircuits.Case, state: SimulationState, high: Boolean) {
        check(case.project == original.project || case.project == referenceProject) {
            "state.fixture.project differs from original and imported reference"
        }
        same("state.fixture.drivenInputs", original.drivenInputs, case.drivenInputs)
        same("state.fixture.observedOutputs", original.observedOutputs, case.observedOutputs)
        same("state.fixture.highInputs", original.highInputs, case.highInputs)
        same("state.circuitName", referenceProject.mainCircuitName, state.circuitName)
        same("state.instancePath", "", state.instancePath)
        same("state.circuitStates.keys", instanceCircuits.keys, state.circuitStates.keys)
        check(!state.oscillating) { "state.oscillating" }
        for ((path, name) in instanceCircuits) {
            val child = state.circuitStates.getValue(path)
            same("state.circuitStates[$path].circuitName", name, child.circuitName)
            same("state.circuitStates[$path].instancePath", path, child.instancePath)
            check(!child.oscillating) { "state.circuitStates[$path].oscillating" }
        }
        val expected = LogicVector.of(8, if (high) 0x5auL else 0uL)
        for ((index, id) in original.observedOutputs.withIndex()) {
            check(state.vectorValues[id] == expected) {
                "state.output[$index] '$id' must equal width-8 ${if (high) "5a" else "00"}"
            }
        }
        val error = CircuitDiagnosticAnalyzer.analyze(case.project, state).diagnostics
            .firstOrNull { it.severity == DiagnosticSeverity.ERROR }
        check(error == null) { "state.diagnostic error ${error?.code}" }
    }

    private fun rebound(project: CircuitProject): PerformanceCircuits.Case {
        same("rebound.mainCircuitName", original.project.mainCircuitName, project.mainCircuitName)
        same("rebound.circuits.size", original.project.circuits.size, project.circuits.size)
        for (index in original.project.circuits.indices) {
            val expected = original.project.circuits[index]
            val actual = project.circuits[index]
            same("rebound.circuit[$index].id", expected.id, actual.id)
            same("rebound.circuit[$index].name", expected.name, actual.name)
            same("rebound.circuit[$index].wires", expected.wires, actual.wires)
            same("rebound.circuit[$index].components.size", expected.components.size, actual.components.size)
            for (componentIndex in expected.components.indices) {
                val before = expected.components[componentIndex]
                val after = actual.components[componentIndex]
                val field = "rebound.circuit[$index].component[$componentIndex]"
                same("$field.id", before.id, after.id)
                same("$field.kind", before.kind, after.kind)
                same("$field.position", before.position, after.position)
                same("$field.circuitName", before.circuitName, after.circuitName)
                for ((name, value) in before.attributes) same("$field.attribute[$name]", value, after.attributes[name])
                same("$field.ports", before.layout().ports, after.layout().ports)
            }
        }
        return PerformanceCircuits.Case(project, original.drivenInputs.toList(), original.observedOutputs.toList(),
            original.highInputs.toMap())
    }
}

private fun collectInstanceCircuits(project: CircuitProject): Map<String, String> = buildMap {
    fun visit(document: CircuitDocument, path: String) {
        for (component in document.components.filter { it.kind == ComponentKind.SUBCIRCUIT }) {
            val child = project.circuit(component.circuitName ?: error("reference subcircuit name missing"))
                ?: error("reference subcircuit missing")
            val childPath = if (path.isEmpty()) component.id else "$path/${component.id}"
            put(childPath, child.name)
            visit(child, childPath)
        }
    }
    visit(project.mainCircuit, "")
}

private fun same(field: String, expected: Any?, actual: Any?) {
    check(expected == actual) { "$field differs" }
}

private fun sameXml(field: String, expected: String, actual: String) {
    if (expected == actual) return
    val index = (0 until minOf(expected.length, actual.length)).firstOrNull { expected[it] != actual[it] }
        ?: minOf(expected.length, actual.length)
    error("$field differs at character $index (expected length ${expected.length}, actual length ${actual.length})")
}

private fun verifyProject(expected: CircuitProject, actual: CircuitProject) {
    same("import.mainCircuitName", expected.mainCircuitName, actual.mainCircuitName)
    same("import.sourceVersion", expected.sourceVersion, actual.sourceVersion)
    same("import.metadata", expected.metadata, actual.metadata)
    same("import.libraries", expected.libraries, actual.libraries)
    same("import.extraXml", expected.extraXml, actual.extraXml)
    same("import.mainXml", expected.mainXml, actual.mainXml)
    same("import.circuits.size", expected.circuits.size, actual.circuits.size)
    for (index in expected.circuits.indices) {
        val before = expected.circuits[index]
        val after = actual.circuits[index]
        val field = "import.circuit[$index]"
        same("$field.id", before.id, after.id)
        same("$field.name", before.name, after.name)
        same("$field.attributes", before.attributes, after.attributes)
        same("$field.xmlAttributes", before.xmlAttributes, after.xmlAttributes)
        same("$field.extraXml", before.extraXml, after.extraXml)
        same("$field.fileMetadata", before.fileMetadata, after.fileMetadata)
        same("$field.wires.size", before.wires.size, after.wires.size)
        for (wire in before.wires.indices) {
            same("$field.wire[$wire].from", before.wires[wire].from, after.wires[wire].from)
            same("$field.wire[$wire].to", before.wires[wire].to, after.wires[wire].to)
        }
        same("$field.components.size", before.components.size, after.components.size)
        for (component in before.components.indices) {
            verifyComponent("$field.component[$component]", before.components[component], after.components[component])
        }
    }
}

private fun verifyComponent(field: String, expected: CircuitComponent, actual: CircuitComponent) {
    same("$field.id", expected.id, actual.id)
    same("$field.kind", expected.kind, actual.kind)
    same("$field.position", expected.position, actual.position)
    same("$field.label", expected.label, actual.label)
    same("$field.circuitName", expected.circuitName, actual.circuitName)
    same("$field.attributes", expected.attributes, actual.attributes)
    same("$field.implicitAttributes", expected.implicitAttributes, actual.implicitAttributes)
    same("$field.effectiveAttributes", effectiveAttributes(expected), effectiveAttributes(actual))
    same("$field.xmlAttributes", expected.xmlAttributes, actual.xmlAttributes)
    same("$field.extraXml", expected.extraXml, actual.extraXml)
    same("$field.explicitPorts", expected.explicitPorts, actual.explicitPorts)
    same("$field.explicitBounds", expected.explicitBounds, actual.explicitBounds)
    same("$field.appearanceTexts", expected.appearanceTexts, actual.appearanceTexts)
    same("$field.sharedLabel", expected.sharedLabel, actual.sharedLabel)
    same("$field.layout", expected.layout(), actual.layout())
}

private fun effectiveAttributes(component: CircuitComponent): Map<String, String> {
    val definition = ComponentCatalog.registry.definition(component.kind)
    return definition?.schema?.defaults.orEmpty() + definition?.defaultAttributes.orEmpty() +
        component.implicitAttributes + component.attributes
}

private fun detachedCase(case: PerformanceCircuits.Case) = PerformanceCircuits.Case(
    detachedProject(case.project), case.drivenInputs.toList(), case.observedOutputs.toList(), case.highInputs.toMap())

private fun detachedProject(project: CircuitProject): CircuitProject = project.copy(
    circuits = project.circuits.map { circuit -> circuit.copy(
        components = circuit.components.map { component -> component.copy(
            attributes = component.attributes.toMap(), implicitAttributes = component.implicitAttributes.toMap(),
            xmlAttributes = component.xmlAttributes.toMap(), extraXml = component.extraXml.toList(),
            explicitPorts = component.explicitPorts.toList(), appearanceTexts = component.appearanceTexts.toList(),
        ) },
        wires = circuit.wires.toList(), attributes = circuit.attributes.toMap(), extraXml = circuit.extraXml.toList(),
        xmlAttributes = circuit.xmlAttributes.toMap(), fileMetadata = circuit.fileMetadata?.let { metadata -> metadata.copy(
            metadata = metadata.metadata.toMap(), libraries = metadata.libraries.toMap(), extraXml = metadata.extraXml.toList(),
        ) },
    ) },
    metadata = project.metadata.toMap(), extraXml = project.extraXml.toList(), libraries = project.libraries.toMap(),
)
