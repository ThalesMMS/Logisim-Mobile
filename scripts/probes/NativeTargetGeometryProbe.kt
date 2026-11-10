package dev.logisim.mobile.input

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.GridPoint
import java.io.File

/** Compile with the real NativeTargetGeometry.kt and core jar; pass before and after XML paths. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: NativeTargetGeometryProbe <before.circ> <after.circ>" }
    val before = readProject(args[0])
    val after = readProject(args[1])
    val moved = GridPoint(10, 0)
    check(NativeTargetGeometry.verifiedDelta(before, after, moved) == GridPoint(10, 0))
    println("PASS native-target-displacement delta=10,0")

    rejects("unexpected-delta") {
        NativeTargetGeometry.verifiedDelta(before, after, GridPoint(20, 0))
    }
    val movedAnchor = changeComponent(after, "mi-input") {
        it.copy(position = GridPoint(it.position.x + 1, it.position.y))
    }
    rejects("anchor-position") { NativeTargetGeometry.verifiedDelta(before, movedAnchor, moved) }
    val changedAnchor = changeComponent(after, "mi-input") {
        it.copy(attributes = it.attributes + ("width" to "2"))
    }
    rejects("anchor-property") { NativeTargetGeometry.verifiedDelta(before, changedAnchor, moved) }
    val changedTarget = changeComponent(after, "mi-not") {
        it.copy(attributes = it.attributes + ("size" to "40"))
    }
    rejects("target-geometry") { NativeTargetGeometry.verifiedDelta(before, changedTarget, moved) }

    val extraComponent = after.mainCircuit.components.single { it.id == "mi-not" }.copy(id = "probe-extra")
    val extraId = after.withCircuit(after.mainCircuit.copy(components = after.mainCircuit.components + extraComponent))
    rejects("extra-id") { NativeTargetGeometry.verifiedDelta(before, extraId, moved) }
    val missingId = after.withCircuit(after.mainCircuit.copy(
        components = after.mainCircuit.components.filterNot { it.id == "mi-output" },
    ))
    rejects("missing-id") { NativeTargetGeometry.verifiedDelta(before, missingId, moved) }

    val unexpectedLabel = changeComponent(after, "mi-not") {
        it.copy(label = "unexpected", attributes = it.attributes + ("label" to "unexpected"))
    }
    rejects("unexpected-label") { NativeTargetGeometry.verifiedDelta(before, unexpectedLabel, moved) }
    rejects("expected-label-mismatch") {
        NativeTargetGeometry.verifiedDelta(before, after, moved, expectedLabel = "unexpected")
    }

    val clearedLabel = changeComponent(before, "mi-not") {
        it.copy(label = "", attributes = it.attributes + ("label" to ""))
    }
    check(NativeTargetGeometry.verifiedDelta(before, clearedLabel, GridPoint(0, 0), expectedLabel = "") == GridPoint(0, 0))
    val positionsChanged = before.mainCircuit.components.associate { it.id to it.position } !=
        clearedLabel.mainCircuit.components.associate { it.id to it.position }
    check(!positionsChanged)
    println("PASS focus-cleared-label delta=0,0 positionsChanged=false")

    rejects("focus-original-label") {
        NativeTargetGeometry.verifiedDelta(before, before, GridPoint(0, 0), expectedLabel = "")
    }
    val clearedLabelMovedTarget = changeComponent(clearedLabel, "mi-not") {
        it.copy(position = GridPoint(it.position.x + 10, it.position.y))
    }
    rejects("focus-cleared-label-target-position") {
        NativeTargetGeometry.verifiedDelta(before, clearedLabelMovedTarget, GridPoint(0, 0), expectedLabel = "")
    }
    val clearedLabelChangedSize = changeComponent(clearedLabel, "mi-not") {
        it.copy(attributes = it.attributes + ("size" to "40"))
    }
    rejects("focus-cleared-label-target-size") {
        NativeTargetGeometry.verifiedDelta(before, clearedLabelChangedSize, GridPoint(0, 0), expectedLabel = "")
    }
    val clearedLabelChangedFacing = changeComponent(clearedLabel, "mi-not") {
        it.copy(attributes = it.attributes + ("facing" to "north"))
    }
    rejects("focus-cleared-label-target-facing") {
        NativeTargetGeometry.verifiedDelta(before, clearedLabelChangedFacing, GridPoint(0, 0), expectedLabel = "")
    }
    val clearedLabelMovedAnchor = changeComponent(clearedLabel, "mi-input") {
        it.copy(position = GridPoint(it.position.x + 1, it.position.y))
    }
    rejects("focus-cleared-label-anchor-position") {
        NativeTargetGeometry.verifiedDelta(before, clearedLabelMovedAnchor, GridPoint(0, 0), expectedLabel = "")
    }
    println("PASS NativeTargetGeometryProbe cases=15")
}

private fun readProject(path: String): CircuitProject = when (val result = CircCodec.readProject(File(path).readText())) {
    is CircProjectImportResult.Success -> result.project
    is CircProjectImportResult.Failure -> error("Cannot import $path: ${result.diagnostics}")
}

private fun changeComponent(
    project: CircuitProject,
    id: String,
    update: (CircuitComponent) -> CircuitComponent,
): CircuitProject {
    val original = project.mainCircuit.components.single { it.id == id }
    val replacement = update(original)
    return project.withCircuit(project.mainCircuit.copy(
        components = project.mainCircuit.components.map { if (it.id == id) replacement else it },
    ))
}

private fun rejects(name: String, block: () -> Unit) {
    val rejected = try {
        block()
        false
    } catch (_: IllegalStateException) {
        true
    }
    check(rejected) { "Expected NativeTargetGeometry to reject $name" }
    println("PASS rejected-$name")
}
