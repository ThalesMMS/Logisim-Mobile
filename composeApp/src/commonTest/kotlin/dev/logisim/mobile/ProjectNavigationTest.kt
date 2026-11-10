package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectNavigationTest {
    private fun child(id: String, name: String, label: String = name) =
        CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(0, 0), label, circuitName = name)

    @Test
    fun repeatedInstanceLabelsRetainIndependentPathsAndStableIds() {
        val leaf = CircuitDocument("Somador", id = "leaf-id")
        val mid = CircuitDocument("Unidade", listOf(child("nested", leaf.name, "Cálculo")), id = "mid-id")
        val main = CircuitDocument("Principal", listOf(child("left", mid.name, "Unidade esquerda"),
            child("right", mid.name, "Unidade direita")), id = "main-id")
        val rows = ProjectNavigation.rows(CircuitProject(listOf(main, mid, leaf), main.name))
        assertEquals(listOf("Principal", "Unidade esquerda", "Cálculo", "Unidade direita", "Cálculo", "Unidade", "Somador"), rows.map { it.label })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        val nested = rows.filter { it.label == "Cálculo" }
        assertEquals(listOf(listOf("left", "nested"), listOf("right", "nested")), nested.map { it.target.instancePath })
        assertTrue(nested.all { it.target.circuitId == "leaf-id" })
    }

    @Test
    fun sameNamedSiblingsAndTheirDescendantsHaveDistinctSpokenPaths() {
        val leaf = CircuitDocument("Leaf", id = "leaf-id")
        val module = CircuitDocument("Module", listOf(child("nested", leaf.name)), id = "module-id")
        val main = CircuitDocument("Main", listOf(
            child("first", module.name, ""),
            child("reserved", module.name, "Module [1]"),
            child("second", module.name, "Module"),
        ), id = "main-id")
        val rows = ProjectNavigation.rows(CircuitProject(listOf(main, module, leaf), main.name))
        val instances = rows.filter { it.kind == ProjectNavigationKind.INSTANCE }

        assertEquals(listOf("Module [2]", "Module [1]", "Module [3]"), instances.filter { it.depth == 1 }.map { it.label })
        assertEquals(listOf(
            listOf("Main", "Module [2]", "Leaf"),
            listOf("Main", "Module [1]", "Leaf"),
            listOf("Main", "Module [3]", "Leaf"),
        ), instances.filter { it.depth == 2 }.map { it.breadcrumb })
        assertEquals(listOf(listOf("first", "nested"), listOf("reserved", "nested"), listOf("second", "nested")),
            instances.filter { it.depth == 2 }.map { it.target.instancePath })
    }

    @Test
    fun terminatesRecursiveReferencesAndMarksTheOffendingRow() {
        val a = CircuitDocument("A", listOf(child("to-b", "B")), id = "a")
        val b = CircuitDocument("B", listOf(child("to-a", "A")), id = "b")
        val rows = ProjectNavigation.rows(CircuitProject(listOf(a, b), "A"))
        assertEquals(4, rows.size)
        assertEquals(ProjectNavigationKind.RECURSIVE_REFERENCE, rows[2].kind)
        assertEquals(listOf("to-b", "to-a"), rows[2].target.instancePath)
    }

    @Test
    fun aLongHierarchyKeepsAllDefinitionsAndInstances() {
        val circuits = (0 until 200).map { index ->
            CircuitDocument("c$index", if (index < 199) listOf(child("next", "c${index + 1}")) else emptyList(), id = "id$index")
        }
        val rows = ProjectNavigation.rows(CircuitProject(circuits, "c0"))
        assertEquals(399, rows.size)
        assertEquals(200, rows.count { it.kind == ProjectNavigationKind.CIRCUIT })
        assertEquals(199, rows.filter { it.kind == ProjectNavigationKind.INSTANCE }.maxOf { it.depth })
    }
}
