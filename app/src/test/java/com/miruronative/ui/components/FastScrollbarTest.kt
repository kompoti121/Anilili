package com.miruronative.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollbarTest {
    @Test
    fun calculateScrollTargetIndexCalculatesCorrectIndexForDragFractions() {
        val totalItems = 100

        // Fraction 0.0 -> First item (0)
        assertEquals(0, calculateScrollTargetIndex(0.0f, totalItems))

        // Fraction 1.0 -> Last item (99)
        assertEquals(99, calculateScrollTargetIndex(1.0f, totalItems))

        // Fraction 0.5 -> Middle item (50)
        assertEquals(50, calculateScrollTargetIndex(0.5f, totalItems))

        // Fraction 0.25 -> Quarter item (25)
        assertEquals(25, calculateScrollTargetIndex(0.25f, totalItems))

        // Out of bounds fractions are clamped
        assertEquals(0, calculateScrollTargetIndex(-0.5f, totalItems))
        assertEquals(99, calculateScrollTargetIndex(1.5f, totalItems))
    }

    @Test
    fun calculateScrollTargetIndexHandlesEmptyList() {
        assertEquals(0, calculateScrollTargetIndex(0.5f, 0))
    }
}
