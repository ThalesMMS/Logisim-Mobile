package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class AlignmentAxis { HORIZONTAL, VERTICAL }
enum class AlignmentEdge { START, CENTER, END }
enum class LayerMove { FRONT, BACK, FORWARD, BACKWARD }

/** Each result is one document edit, so callers can apply it as one undo operation. */
object AnnotationEdits {
    fun insertText(document: CircuitDocument, id: String, position: GridPoint, text: String,
        font: AnnotationFont = AnnotationFont(), color: String = "#000000"): CircuitDocument {
        val component = CircuitComponent(id, AnnotationLibrary.Text, position, attributes = mapOf(
            "text" to text, "font" to font.serialized, "color" to color, "halign" to "left", "valign" to "top"))
        AnnotationLibrary.definitions().first().schema.parse(component.attributes)
        return document.copy(components = document.components + component)
    }

    fun insertImage(document: CircuitDocument, id: String, position: GridPoint, image: EmbeddedImage,
        width: Int = image.width, height: Int = image.height, scale: String = "fit", attribution: String = ""): CircuitDocument {
        val component = CircuitComponent(id, AnnotationLibrary.Image, position, attributes = mapOf(
            "image" to image.dataUri, "width" to width.toString(), "height" to height.toString(), "scale" to scale,
            "license" to "Unspecified", "attribution" to attribution))
        AnnotationLibrary.definitions().last().schema.parse(component.attributes)
        return document.copy(components = document.components + component)
    }

    fun edit(document: CircuitDocument, id: String, updates: Map<String, String>): CircuitDocument {
        val component = document.components.single { it.id == id }
        requireLocalized(AnnotationLibrary.isAnnotation(component)) { message("annotation_not_annotation") }
        val updated = component.copy(attributes = component.attributes + updates)
        AnnotationLibrary.definitions().single { it.kind == component.kind }.schema.parse(updated.attributes)
        return document.copy(components = document.components.map { if (it.id == id) updated else it })
    }

    fun group(document: CircuitDocument, ids: Set<String>, groupId: String?): CircuitDocument {
        requireLocalized(groupId == null || groupId.isNotBlank()) { message("annotation_group_id") }
        return document.copy(components = document.components.map { component ->
            if (component.id !in ids) component else component.copy(attributes =
                if (groupId == null) component.attributes - "mobile-group" else component.attributes + ("mobile-group" to groupId))
        })
    }

    fun groupMembers(document: CircuitDocument, id: String): Set<String> {
        val component = document.components.single { it.id == id }
        val group = component.attributes["mobile-group"] ?: return setOf(id)
        return document.components.filter { it.attributes["mobile-group"] == group }.map { it.id }.toSet()
    }

    fun reorder(document: CircuitDocument, ids: Set<String>, move: LayerMove): CircuitDocument {
        val result = document.components.toMutableList()
        when (move) {
            LayerMove.FRONT -> { result.removeAll { it.id in ids }; result.addAll(document.components.filter { it.id in ids }) }
            LayerMove.BACK -> { result.removeAll { it.id in ids }; result.addAll(0, document.components.filter { it.id in ids }) }
            LayerMove.FORWARD -> for (index in result.lastIndex - 1 downTo 0) {
                if (result[index].id in ids && result[index + 1].id !in ids) {
                    val selected = result[index]; result[index] = result[index + 1]; result[index + 1] = selected
                }
            }
            LayerMove.BACKWARD -> for (index in 1..result.lastIndex) {
                if (result[index].id in ids && result[index - 1].id !in ids) {
                    val selected = result[index]; result[index] = result[index - 1]; result[index - 1] = selected
                }
            }
        }
        return document.copy(components = result)
    }

    fun align(document: CircuitDocument, ids: Set<String>, axis: AlignmentAxis, edge: AlignmentEdge,
        registry: ComponentRegistry = ComponentRegistry(ComponentCatalog.definitions)): CircuitDocument {
        val selected = document.components.filter { it.id in ids }
        requireLocalized(selected.size >= 2) { message("annotation_select_two") }
        val bounds = selected.associateWith { bounds(it, registry) }
        val start = bounds.values.minOf { if (axis == AlignmentAxis.HORIZONTAL) it.left else it.top }
        val end = bounds.values.maxOf { if (axis == AlignmentAxis.HORIZONTAL) it.right else it.bottom }
        val target = when (edge) { AlignmentEdge.START -> start; AlignmentEdge.END -> end; AlignmentEdge.CENTER -> (start + end) / 2 }
        return move(document, selected.associate { component ->
            val box = bounds.getValue(component)
            val current = when (edge) {
                AlignmentEdge.START -> if (axis == AlignmentAxis.HORIZONTAL) box.left else box.top
                AlignmentEdge.END -> if (axis == AlignmentAxis.HORIZONTAL) box.right else box.bottom
                AlignmentEdge.CENTER -> if (axis == AlignmentAxis.HORIZONTAL) (box.left + box.right) / 2 else (box.top + box.bottom) / 2
            }
            component.id to if (axis == AlignmentAxis.HORIZONTAL) GridPoint(component.position.x + target - current, component.position.y)
                else GridPoint(component.position.x, component.position.y + target - current)
        })
    }

    fun distribute(document: CircuitDocument, ids: Set<String>, axis: AlignmentAxis,
        registry: ComponentRegistry = ComponentRegistry(ComponentCatalog.definitions)): CircuitDocument {
        val selected = document.components.filter { it.id in ids }.sortedBy {
            val box = bounds(it, registry)
            if (axis == AlignmentAxis.HORIZONTAL) box.left + box.right else box.top + box.bottom
        }
        requireLocalized(selected.size >= 3) { message("annotation_select_three") }
        fun center(component: CircuitComponent): Int = bounds(component, registry).let {
            if (axis == AlignmentAxis.HORIZONTAL) (it.left + it.right) / 2 else (it.top + it.bottom) / 2
        }
        val start = center(selected.first())
        val span = center(selected.last()) - start
        return move(document, selected.mapIndexed { index, component ->
            val delta = start + (span.toLong() * index / (selected.size - 1)).toInt() - center(component)
            component.id to if (axis == AlignmentAxis.HORIZONTAL) GridPoint(component.position.x + delta, component.position.y)
                else GridPoint(component.position.x, component.position.y + delta)
        }.toMap())
    }

    private fun bounds(component: CircuitComponent, registry: ComponentRegistry) =
        if (AnnotationLibrary.isAnnotation(component)) AnnotationLibrary.bounds(component) else registry.layout(component).bounds

    private fun move(document: CircuitDocument, positions: Map<String, GridPoint>): CircuitDocument {
        val editor = CircuitEditor(document)
        for ((id, position) in positions) {
            val component = editor.document.components.single { it.id == id }
            if (AnnotationLibrary.isAnnotation(component)) editor.replace(editor.document.copy(components = editor.document.components.map {
                if (it.id == id) it.copy(position = position) else it
            })) else editor.move(id, position)
            requireLocalized(editor.document.components.single { it.id == id }.position == position) { message("annotation_connection_loss") }
        }
        return editor.document
    }
}
