import dev.logisim.mobile.core.*

private fun loaded(root: String, resolver: CircuitLibraryResolver) =
    (CircLibraryCodec.read(root, "root", resolver) as CircLibraryImportResult.Success).resolved

fun main() {
    val child = """<project source="5.1.0"><main name="Child"/><circuit name="Child" id="child-stable"/></project>"""
    val root = """<project source="5.1.0"><lib name="child" desc="file#child.circ"/><main name="Top"/><circuit name="Top" id="top-stable"><comp lib="child" name="Child" loc="(50,50)" logisim-mobile-id="first"/><comp lib="child" name="Child" loc="(100,100)" logisim-mobile-id="second"/></circuit></project>"""
    val resolver = CircuitLibraryResolver { _, reference -> if (reference == "child.circ") CircuitLibrarySource("child", child, "v1") else null }
    val a = loaded(root, resolver)
    val b = loaded(root, resolver)
    check(a.project.circuits.size == 2)
    check(a.project.circuits.map { it.id } == b.project.circuits.map { it.id })
    check(a.project.mainCircuit.components.map { it.id }.toSet() == setOf("first", "second"))
    check(a.project.mainCircuit.components.map { it.circuitName }.distinct().size == 1)
    check(a.changes(resolver).isEmpty())
    check(CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, _ -> null }) is CircLibraryImportResult.Failure)
    val cyclicChild = child.replace("<main", "<lib name=\"back\" desc=\"file#root.circ\"/><main")
    val cycle = CircLibraryCodec.read(root, "root", CircuitLibraryResolver { _, path ->
        when(path) { "child.circ" -> CircuitLibrarySource("child", cyclicChild); "root.circ" -> CircuitLibrarySource("root", root); else -> null }
    })
    check(cycle is CircLibraryImportResult.Failure)
    check(a.changes(CircuitLibraryResolver { _, _ -> null }).single().sourceId == "child")
    check(a.changes(CircuitLibraryResolver { _, _ -> CircuitLibrarySource("replacement", child, "v1") }).single().sourceId == "child")
    check(a.changes(CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", child, "v2") }).single().sourceId == "child")
    check(a.changes(CircuitLibraryResolver { _, _ -> CircuitLibrarySource("child", child.replace("Child", "Changed"), "v1") }).single().sourceId == "child")
    println("PASS: stable definition/instance identity, shared definition, missing reference, cycle, disappeared source, replacement identity, revision change and XML change")
}
