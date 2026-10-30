package com.livetracking

import com.livetracking.runtime.StatusWatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for StatusWatcher's stop-value comparison logic.
 *
 * The watcher itself requires Firebase RTDB (covered by instrumented tests);
 * isStopValue is pure and verifies the numeric comparison semantics —
 * RTDB numeric values may surface as Long or Double, and string payloads
 * are parsed defensively.
 */
class StatusWatcherTest {

    @Test
    fun `isStopValue matches Long equal to stopValue`() {
        assertTrue(StatusWatcher.isStopValue(2L, 2))
    }

    @Test
    fun `isStopValue matches Int equal to stopValue`() {
        assertTrue(StatusWatcher.isStopValue(2, 2))
    }

    @Test
    fun `isStopValue matches Double equal to stopValue`() {
        assertTrue(StatusWatcher.isStopValue(2.0, 2))
    }

    @Test
    fun `isStopValue matches numeric String equal to stopValue`() {
        assertTrue(StatusWatcher.isStopValue("2", 2))
        assertTrue(StatusWatcher.isStopValue(" 2 ", 2))
    }

    @Test
    fun `isStopValue rejects different numeric value`() {
        assertFalse(StatusWatcher.isStopValue(1L, 2))
        assertFalse(StatusWatcher.isStopValue(0, 2))
    }

    @Test
    fun `isStopValue rejects null`() {
        assertFalse(StatusWatcher.isStopValue(null, 2))
    }

    @Test
    fun `isStopValue rejects non-numeric values`() {
        assertFalse(StatusWatcher.isStopValue("completed", 2))
        assertFalse(StatusWatcher.isStopValue(true, 2))
        assertFalse(StatusWatcher.isStopValue(emptyMap<String, Any>(), 2))
    }
}
