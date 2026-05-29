package com.livetracking

import com.livetracking.queue.QueuedLocation
import com.livetracking.sync.FirebaseSyncEngine
import com.livetracking.sync.SyncCallback
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FirebaseSyncEngine.
 *
 * Focuses on the pure logic that can be tested without Firebase SDK initialization:
 * - Exponential backoff delay calculation
 * - Null path error handling
 * - Empty batch handling
 *
 * Full Firebase write tests require either:
 * - Instrumented tests with Firebase emulator
 * - Integration tests with a test Firebase project
 */
class FirebaseSyncEngineTest {

    private lateinit var syncEngine: FirebaseSyncEngine
    private lateinit var syncEngineNoCurrentPath: FirebaseSyncEngine
    private lateinit var syncEngineNoHistoryPath: FirebaseSyncEngine

    @Before
    fun setup() {
        syncEngine = FirebaseSyncEngine(
            service = "RTDB",
            currentLocationPath = "users/test-user/currentLocation",
            historyPath = "users/test-user/history"
        )

        syncEngineNoCurrentPath = FirebaseSyncEngine(
            service = "RTDB",
            currentLocationPath = null,
            historyPath = "users/test-user/history"
        )

        syncEngineNoHistoryPath = FirebaseSyncEngine(
            service = "RTDB",
            currentLocationPath = "users/test-user/currentLocation",
            historyPath = null
        )
    }

    // --- Exponential Backoff Delay Tests ---

    @Test
    fun `calculateBackoffDelay attempt 1 returns approximately 1000ms`() {
        // Formula: 1000 * 2^(1-1) ± 200 = 1000 ± 200
        // Expected range: [800, 1200]
        val delays = (1..100).map { syncEngine.calculateBackoffDelay(1) }

        delays.forEach { delay ->
            assertTrue(
                "Attempt 1 delay should be between 800 and 1200ms, got $delay",
                delay in 800..1200
            )
        }
    }

    @Test
    fun `calculateBackoffDelay attempt 2 returns approximately 2000ms`() {
        // Formula: 1000 * 2^(2-1) ± 200 = 2000 ± 200
        // Expected range: [1800, 2200]
        val delays = (1..100).map { syncEngine.calculateBackoffDelay(2) }

        delays.forEach { delay ->
            assertTrue(
                "Attempt 2 delay should be between 1800 and 2200ms, got $delay",
                delay in 1800..2200
            )
        }
    }

    @Test
    fun `calculateBackoffDelay attempt 3 returns approximately 4000ms`() {
        // Formula: 1000 * 2^(3-1) ± 200 = 4000 ± 200
        // Expected range: [3800, 4200]
        val delays = (1..100).map { syncEngine.calculateBackoffDelay(3) }

        delays.forEach { delay ->
            assertTrue(
                "Attempt 3 delay should be between 3800 and 4200ms, got $delay",
                delay in 3800..4200
            )
        }
    }

    @Test
    fun `calculateBackoffDelay attempt 4 returns approximately 8000ms`() {
        // Formula: 1000 * 2^(4-1) ± 200 = 8000 ± 200
        // Expected range: [7800, 8200]
        val delays = (1..100).map { syncEngine.calculateBackoffDelay(4) }

        delays.forEach { delay ->
            assertTrue(
                "Attempt 4 delay should be between 7800 and 8200ms, got $delay",
                delay in 7800..8200
            )
        }
    }

    @Test
    fun `calculateBackoffDelay attempt 5 returns approximately 16000ms`() {
        // Formula: 1000 * 2^(5-1) ± 200 = 16000 ± 200
        // Expected range: [15800, 16200]
        val delays = (1..100).map { syncEngine.calculateBackoffDelay(5) }

        delays.forEach { delay ->
            assertTrue(
                "Attempt 5 delay should be between 15800 and 16200ms, got $delay",
                delay in 15800..16200
            )
        }
    }

    @Test
    fun `calculateBackoffDelay is never negative`() {
        // Test across many attempts to ensure maxOf(0L, ...) guard works
        val delays = (1..10).flatMap { attempt ->
            (1..100).map { syncEngine.calculateBackoffDelay(attempt) }
        }

        delays.forEach { delay ->
            assertTrue("Delay should never be negative, got $delay", delay >= 0)
        }
    }

    @Test
    fun `calculateBackoffDelay includes jitter variation`() {
        // Run multiple times for the same attempt and verify we get different values (jitter)
        val delays = (1..50).map { syncEngine.calculateBackoffDelay(1) }.toSet()

        // With ±200ms jitter, we should get multiple distinct values over 50 runs
        assertTrue(
            "Expected jitter to produce multiple distinct delay values, got ${delays.size}",
            delays.size > 1
        )
    }

    @Test
    fun `calculateBackoffDelay grows exponentially`() {
        // Average of many samples should show exponential growth
        val avgDelay1 = (1..100).map { syncEngine.calculateBackoffDelay(1) }.average()
        val avgDelay2 = (1..100).map { syncEngine.calculateBackoffDelay(2) }.average()
        val avgDelay3 = (1..100).map { syncEngine.calculateBackoffDelay(3) }.average()

        // Each level should be approximately 2x the previous
        assertTrue("Delay 2 should be ~2x delay 1", avgDelay2 > avgDelay1 * 1.5)
        assertTrue("Delay 3 should be ~2x delay 2", avgDelay3 > avgDelay2 * 1.5)
    }

    // --- Null Path Error Handling Tests ---

    @Test
    fun `updateCurrentLocation with null path calls onError`() {
        var errorCode: String? = null
        var errorMessage: String? = null

        val callback = object : SyncCallback {
            override fun onSuccess() {
                fail("Should not call onSuccess when path is null")
            }

            override fun onError(code: String, message: String) {
                errorCode = code
                errorMessage = message
            }
        }

        syncEngineNoCurrentPath.updateCurrentLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = System.currentTimeMillis(),
            accuracy = 10.0f,
            speed = null,
            callback = callback
        )

        assertEquals("NO_PATH", errorCode)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("currentLocationPath"))
    }

    @Test
    fun `pushHistoryBatch with null path calls onError`() {
        var errorCode: String? = null
        var errorMessage: String? = null

        val callback = object : SyncCallback {
            override fun onSuccess() {
                fail("Should not call onSuccess when path is null")
            }

            override fun onError(code: String, message: String) {
                errorCode = code
                errorMessage = message
            }
        }

        val locations = listOf(
            QueuedLocation("1", 37.0, -122.0, 1000L, 5.0f, null, null, null, 1000L)
        )

        syncEngineNoHistoryPath.pushHistoryBatch(locations, callback)

        assertEquals("NO_PATH", errorCode)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("historyPath"))
    }

    // --- Empty Batch Handling Tests ---

    @Test
    fun `pushHistoryBatch with empty list calls onSuccess immediately`() {
        var successCalled = false

        val callback = object : SyncCallback {
            override fun onSuccess() {
                successCalled = true
            }

            override fun onError(code: String, message: String) {
                fail("Should not call onError for empty list")
            }
        }

        syncEngine.pushHistoryBatch(emptyList(), callback)

        assertTrue("onSuccess should be called for empty batch", successCalled)
    }

    @Test
    fun `pushHistoryBatch with empty list and null path calls onError`() {
        // null path check happens before empty list check
        var errorCode: String? = null

        val callback = object : SyncCallback {
            override fun onSuccess() {
                fail("Should not call onSuccess when path is null")
            }

            override fun onError(code: String, message: String) {
                errorCode = code
            }
        }

        syncEngineNoHistoryPath.pushHistoryBatch(emptyList(), callback)

        assertEquals("NO_PATH", errorCode)
    }

    // --- SyncCallback Interface Tests ---

    @Test
    fun `SyncCallback onSuccess can be called`() {
        var called = false
        val callback = object : SyncCallback {
            override fun onSuccess() { called = true }
            override fun onError(code: String, message: String) {}
        }

        callback.onSuccess()
        assertTrue(called)
    }

    @Test
    fun `SyncCallback onError provides error code and message`() {
        var receivedCode: String? = null
        var receivedMessage: String? = null

        val callback = object : SyncCallback {
            override fun onSuccess() {}
            override fun onError(code: String, message: String) {
                receivedCode = code
                receivedMessage = message
            }
        }

        callback.onError("TEST_ERROR", "Something went wrong")

        assertEquals("TEST_ERROR", receivedCode)
        assertEquals("Something went wrong", receivedMessage)
    }

    // --- Constructor / Configuration Tests ---

    @Test
    fun `FirebaseSyncEngine accepts RTDB service type`() {
        val engine = FirebaseSyncEngine("RTDB", "path/current", "path/history")
        assertNotNull(engine)
    }

    @Test
    fun `FirebaseSyncEngine accepts Firestore service type`() {
        val engine = FirebaseSyncEngine("Firestore", "collection/doc", "collection")
        assertNotNull(engine)
    }

    @Test
    fun `FirebaseSyncEngine accepts null paths`() {
        val engine = FirebaseSyncEngine("RTDB", null, null)
        assertNotNull(engine)
    }

    // --- Backoff Constants Verification ---

    @Test
    fun `backoff base delay is 1000ms for attempt 1`() {
        // The base delay without jitter should center around 1000ms
        val delays = (1..1000).map { syncEngine.calculateBackoffDelay(1) }
        val average = delays.average()

        // Average should be very close to 1000 (jitter averages to ~0)
        assertTrue(
            "Average delay for attempt 1 should be near 1000ms, got $average",
            average in 900.0..1100.0
        )
    }

    @Test
    fun `backoff jitter range is within +-200ms`() {
        // For attempt 1, base is 1000ms, so all values should be in [800, 1200]
        val delays = (1..1000).map { syncEngine.calculateBackoffDelay(1) }

        val min = delays.min()
        val max = delays.max()

        assertTrue("Min delay should be >= 800, got $min", min >= 800)
        assertTrue("Max delay should be <= 1200, got $max", max <= 1200)
    }
}
