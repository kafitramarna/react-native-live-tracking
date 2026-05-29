package com.livetracking

import com.livetracking.sync.SyncTargetConfig
import com.livetracking.sync.TargetHandler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.pow

/**
 * Unit tests for the exponential backoff delay calculation in TargetHandler.
 *
 * **Validates: Requirements 7.1**
 *
 * Property 10: Exponential backoff delay formula
 * For any retry attempt number `a` (1-indexed), the calculated backoff delay SHALL be
 * within the range [baseDelay × 2^(a-1) - 200, baseDelay × 2^(a-1) + 200] milliseconds
 * (where baseDelay = 1000ms), and SHALL never be negative.
 */
class BackoffCalculationTest {

    private lateinit var handler: TargetHandler

    companion object {
        private const val BASE_DELAY_MS = 1000L
        private const val MULTIPLIER = 2.0
        private const val JITTER_MS = 200L
    }

    @Before
    fun setup() {
        // Create a minimal TargetHandler to access calculateBackoffDelay
        val config = SyncTargetConfig(
            path = "test/path",
            method = "set",
            batchSize = 1,
            offlineQueue = false
        )
        handler = TargetHandler(
            config = config,
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    // --- Property 10: Backoff delay formula within expected range ---

    @Test
    fun `attempt 1 delay is within 1000ms plus or minus 200ms`() {
        // baseDelay × 2^(1-1) = 1000 × 1 = 1000ms
        // Expected range: [800, 1200]
        repeat(100) {
            val delay = handler.calculateBackoffDelay(1)
            assertTrue(
                "Attempt 1 delay $delay should be >= 800ms",
                delay >= 800L
            )
            assertTrue(
                "Attempt 1 delay $delay should be <= 1200ms",
                delay <= 1200L
            )
        }
    }

    @Test
    fun `attempt 2 delay is within 2000ms plus or minus 200ms`() {
        // baseDelay × 2^(2-1) = 1000 × 2 = 2000ms
        // Expected range: [1800, 2200]
        repeat(100) {
            val delay = handler.calculateBackoffDelay(2)
            assertTrue(
                "Attempt 2 delay $delay should be >= 1800ms",
                delay >= 1800L
            )
            assertTrue(
                "Attempt 2 delay $delay should be <= 2200ms",
                delay <= 2200L
            )
        }
    }

    @Test
    fun `attempt 3 delay is within 4000ms plus or minus 200ms`() {
        // baseDelay × 2^(3-1) = 1000 × 4 = 4000ms
        // Expected range: [3800, 4200]
        repeat(100) {
            val delay = handler.calculateBackoffDelay(3)
            assertTrue(
                "Attempt 3 delay $delay should be >= 3800ms",
                delay >= 3800L
            )
            assertTrue(
                "Attempt 3 delay $delay should be <= 4200ms",
                delay <= 4200L
            )
        }
    }

    @Test
    fun `attempt 4 delay is within 8000ms plus or minus 200ms`() {
        // baseDelay × 2^(4-1) = 1000 × 8 = 8000ms
        // Expected range: [7800, 8200]
        repeat(100) {
            val delay = handler.calculateBackoffDelay(4)
            assertTrue(
                "Attempt 4 delay $delay should be >= 7800ms",
                delay >= 7800L
            )
            assertTrue(
                "Attempt 4 delay $delay should be <= 8200ms",
                delay <= 8200L
            )
        }
    }

    @Test
    fun `attempt 5 delay is within 16000ms plus or minus 200ms`() {
        // baseDelay × 2^(5-1) = 1000 × 16 = 16000ms
        // Expected range: [15800, 16200]
        repeat(100) {
            val delay = handler.calculateBackoffDelay(5)
            assertTrue(
                "Attempt 5 delay $delay should be >= 15800ms",
                delay >= 15800L
            )
            assertTrue(
                "Attempt 5 delay $delay should be <= 16200ms",
                delay <= 16200L
            )
        }
    }

    // --- Property 10: Delay is never negative ---

    @Test
    fun `delay is never negative for any attempt number`() {
        // Run many iterations across multiple attempt numbers to verify non-negativity
        for (attempt in 1..10) {
            repeat(100) {
                val delay = handler.calculateBackoffDelay(attempt)
                assertTrue(
                    "Delay for attempt $attempt should never be negative, got $delay",
                    delay >= 0L
                )
            }
        }
    }

    @Test
    fun `delay for attempt 1 is never negative even with worst-case jitter`() {
        // Attempt 1: base = 1000ms, jitter = -200ms → minimum = 800ms
        // This should always be positive
        repeat(1000) {
            val delay = handler.calculateBackoffDelay(1)
            assertTrue(
                "Attempt 1 delay should never be negative, got $delay",
                delay >= 0L
            )
        }
    }

    // --- Property-based: randomized attempts within valid range ---

    @Test
    fun `randomized attempts all produce delays within expected formula range`() {
        /**
         * **Validates: Requirements 7.1**
         *
         * Property 10: For any retry attempt number a (1-indexed), the calculated
         * backoff delay SHALL be within [baseDelay × 2^(a-1) - 200, baseDelay × 2^(a-1) + 200]
         */
        val random = java.util.Random(42) // Fixed seed for reproducibility

        repeat(500) {
            val attempt = random.nextInt(5) + 1 // 1 to 5
            val delay = handler.calculateBackoffDelay(attempt)

            val expectedBase = (BASE_DELAY_MS * MULTIPLIER.pow((attempt - 1).toDouble())).toLong()
            val lowerBound = expectedBase - JITTER_MS
            val upperBound = expectedBase + JITTER_MS

            assertTrue(
                "Attempt $attempt: delay $delay should be >= $lowerBound",
                delay >= lowerBound
            )
            assertTrue(
                "Attempt $attempt: delay $delay should be <= $upperBound",
                delay <= upperBound
            )
            assertTrue(
                "Attempt $attempt: delay $delay should never be negative",
                delay >= 0L
            )
        }
    }

    // --- Exponential growth verification ---

    @Test
    fun `delays grow exponentially across attempts`() {
        // Verify that the median delay roughly doubles with each attempt
        val medianDelays = (1..4).map { attempt ->
            val delays = (1..100).map { handler.calculateBackoffDelay(attempt) }
            delays.sorted()[50] // Approximate median
        }

        // Each subsequent delay should be roughly 2x the previous (within jitter tolerance)
        for (i in 1 until medianDelays.size) {
            val ratio = medianDelays[i].toDouble() / medianDelays[i - 1].toDouble()
            assertTrue(
                "Ratio between attempt ${i + 1} and $i should be ~2.0, got $ratio",
                ratio in 1.5..2.5
            )
        }
    }
}
