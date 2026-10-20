package com.test.app.democode.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchReducerTest {
    @Test
    fun increment_updatesState() {
        val result = ArchReducer.reduce(ArchState(), ArchIntent.Increment)

        assertEquals(1, result.state.count)
        assertEquals("收到 Increment Intent", result.state.lastAction)
        assertNull(result.effect)
    }

    @Test
    fun incrementAtMaximum_keepsStateAndEmitsEffect() {
        val state = ArchState(count = ArchReducer.MAX_COUNT)

        val result = ArchReducer.reduce(state, ArchIntent.Increment)

        assertEquals(state, result.state)
        assertTrue(result.effect is ArchEffect.ShowMessage)
    }

    @Test
    fun decrementAtMinimum_keepsStateAndEmitsEffect() {
        val state = ArchState(count = ArchReducer.MIN_COUNT)

        val result = ArchReducer.reduce(state, ArchIntent.Decrement)

        assertEquals(state, result.state)
        assertTrue(result.effect is ArchEffect.ShowMessage)
    }

    @Test
    fun reset_setsCountToZeroAndEmitsEffect() {
        val result = ArchReducer.reduce(ArchState(count = 3), ArchIntent.Reset)

        assertEquals(0, result.state.count)
        assertEquals("收到 Reset Intent", result.state.lastAction)
        assertTrue(result.effect is ArchEffect.ShowMessage)
    }
}
