package org.astrasec.launcher.model

/** Pure ordering rules shared by touch and directional navigation. */
object FavoriteOrder {
    enum class Direction { LEFT, RIGHT, UP, DOWN }

    fun target(index: Int, size: Int, columns: Int, direction: Direction): Int {
        if (index !in 0 until size || columns < 1) return index
        return when (direction) {
            Direction.LEFT -> if (index % columns > 0) index - 1 else index
            Direction.RIGHT -> if (index % columns < columns - 1 && index + 1 < size) index + 1 else index
            Direction.UP -> if (index >= columns) index - columns else index
            Direction.DOWN -> if (index / columns < (size - 1) / columns) {
                (index + columns).coerceAtMost(size - 1)
            } else index
        }
    }

    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items.toList()
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Preserve unavailable favorites and concurrent additions/removals, without inventing entries. */
    fun merge(current: List<String>, arranged: List<String>): List<String> {
        val existing = current.distinct()
        val ordered = arranged.distinct().filter { it in existing }
        val reordered = ordered.iterator()
        val movable = ordered.toSet()
        return existing.map { if (it in movable) reordered.next() else it }
    }
}
