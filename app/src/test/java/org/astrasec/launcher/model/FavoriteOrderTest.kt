package org.astrasec.launcher.model

import org.astrasec.launcher.model.FavoriteOrder.Direction.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteOrderTest {
    @Test fun movesByInsertionWithoutChangingSource() {
        val source = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), FavoriteOrder.move(source, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), FavoriteOrder.move(source, 3, 0))
        assertEquals(listOf("a", "b", "c", "d"), source)
        assertEquals(source, FavoriteOrder.move(source, -1, 2))
        assertEquals(source, FavoriteOrder.move(source, 0, 4))
    }

    @Test fun directionsRespectEdgesAndPartialLastRow() {
        assertEquals(0, FavoriteOrder.target(0, 8, 3, LEFT))
        assertEquals(2, FavoriteOrder.target(2, 8, 3, RIGHT))
        assertEquals(0, FavoriteOrder.target(0, 8, 3, UP))
        assertEquals(4, FavoriteOrder.target(1, 8, 3, DOWN))
        assertEquals(7, FavoriteOrder.target(5, 8, 3, DOWN))
        assertEquals(7, FavoriteOrder.target(7, 8, 3, DOWN))
        assertEquals(7, FavoriteOrder.target(7, 8, 3, RIGHT))
        assertEquals(4, FavoriteOrder.target(7, 8, 3, UP))
        assertEquals(0, FavoriteOrder.target(0, 1, 1, DOWN))
        assertEquals(0, FavoriteOrder.target(0, 0, 1, RIGHT))
    }

    @Test fun mergeKeepsUnavailableAndNewFavoritesAndDoesNotResurrectRemovedApps() {
        assertEquals(listOf("b", "hidden", "a", "new"),
            FavoriteOrder.merge(listOf("a", "hidden", "b", "new"), listOf("b", "removed", "a", "b")))
        assertEquals(listOf("a", "b"), FavoriteOrder.merge(listOf("a", "b"), emptyList()))
        assertEquals(emptyList<String>(), FavoriteOrder.merge(emptyList(), listOf("a")))
        assertEquals(listOf("b", "a"), FavoriteOrder.merge(listOf("a", "a", "b"), listOf("b", "a")))
    }
}
