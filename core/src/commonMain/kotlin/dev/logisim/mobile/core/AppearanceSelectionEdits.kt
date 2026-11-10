package dev.logisim.mobile.core

/** Selection operations affect supported shapes in drawing order. */
object AppearanceSelectionEdits {
    fun shapes(appearance: SymbolAppearance, ids: Set<String>): List<SymbolObject.Shape> =
        appearance.objects.filterIsInstance<SymbolObject.Shape>().filter { it.id in ids }

    fun remove(appearance: SymbolAppearance, ids: Set<String>): SymbolAppearance =
        appearance.copy(objects = appearance.objects.filterNot { it is SymbolObject.Shape && it.id in ids })

    fun layer(appearance: SymbolAppearance, ids: Set<String>, front: Boolean): SymbolAppearance {
        val (selected, rest) = appearance.objects.partition { it is SymbolObject.Shape && it.id in ids }
        return appearance.copy(objects = if (front) rest + selected else selected + rest)
    }

    fun insert(
        appearance: SymbolAppearance,
        source: List<SymbolObject.Shape>,
        duplicate: Boolean,
    ): Pair<SymbolAppearance, Set<String>> {
        if (source.isEmpty()) return appearance to emptySet()
        val existing = appearance.objects.filterIsInstance<SymbolObject.Shape>()
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        val shapes = if (duplicate) {
            source.map { AppearanceEdits.translate(it, 10, 10) }
        } else {
            // Paste at the source location unless the entire group would overlap a copy.
            val existingGeometry = existing.mapTo(mutableSetOf(), ::geometry)
            var offset = 0
            var candidates = source
            while (candidates.all { geometry(it) in existingGeometry }) {
                offset += 10
                candidates = source.map { AppearanceEdits.translate(it, offset, offset) }
            }
            candidates
        }
        var next = 1
        val added = shapes.map { shape ->
            while ("shape-$next" in usedIds) next++
            val id = "shape-$next"
            usedIds += id
            shape.copy(id = id, attributes = shape.attributes - "id")
        }
        return appearance.copy(objects = appearance.objects + added) to added.mapTo(linkedSetOf()) { it.id }
    }

    private fun geometry(shape: SymbolObject.Shape): SymbolObject.Shape {
        val normalized = AppearanceEdits.translate(shape, 0, 0)
        return normalized.copy(id = "shape", attributes = normalized.attributes - "id")
    }
}
