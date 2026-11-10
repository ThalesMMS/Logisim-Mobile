package dev.logisim.mobile.core

data class SpatialRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) { "Spatial bounds must be finite." }
        require(left <= right && top <= bottom) { "Spatial bounds must not be inverted." }
    }

    fun intersects(other: SpatialRect): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    internal fun containsBounds(other: SpatialRect): Boolean =
        left <= other.left && right >= other.right && top <= other.top && bottom >= other.bottom
    internal fun union(other: SpatialRect) = SpatialRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

data class SpatialItem<T>(val value: T, val bounds: SpatialRect)

/** Immutable index. Query results retain insertion order, including overlapping items. */
class SpatialIndex<T>(items: List<SpatialItem<T>>) {
    private val items = items.toList()
    private val root = if (items.isEmpty()) null else build(items.indices.toList())
    val size: Int get() = items.size

    fun query(bounds: SpatialRect): List<T> {
        val root = root ?: return emptyList()
        if (!root.bounds.intersects(bounds)) return emptyList()
        if (bounds.containsBounds(root.bounds)) return items.map { it.value }
        val found = ArrayList<Int>()
        fun visit(node: Node) {
            if (!node.bounds.intersects(bounds)) return
            if (node.entries != null) {
                for (index in node.entries) if (items[index].bounds.intersects(bounds)) found += index
            } else {
                node.first?.let(::visit)
                node.second?.let(::visit)
            }
        }
        visit(root)
        found.sort()
        return found.map { items[it].value }
    }

    private fun build(indices: List<Int>): Node {
        val bounds = indices.drop(1).fold(items[indices.first()].bounds) { total, index ->
            total.union(items[index].bounds)
        }
        if (indices.size <= 8) return Node(bounds, entries = indices)
        val horizontal = bounds.right - bounds.left >= bounds.bottom - bounds.top
        val sorted = indices.sortedBy { index ->
            val item = items[index].bounds
            if (horizontal) item.left / 2 + item.right / 2 else item.top / 2 + item.bottom / 2
        }
        val middle = sorted.size / 2
        return Node(bounds, build(sorted.subList(0, middle)), build(sorted.subList(middle, sorted.size)))
    }

    private data class Node(
        val bounds: SpatialRect,
        val first: Node? = null,
        val second: Node? = null,
        val entries: List<Int>? = null,
    )
}
