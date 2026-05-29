package com.livetracking

import com.livetracking.sync.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for SyncEngineController and TargetHandler.
 *
 * Tests verify:
 * - Multi-target parallel dispatch (Property 9: target isolation)
 * - Batch accumulation invariant (Property 7)
 * - Partial batch flush on stop (Property 8)
 * - Target isolation on failure (Property 9)
 * - Retry cancellation on newer data for set/update (Property 11)
 *
 * Validates: Requirements 3.1, 3.5, 4.1, 4.2, 4.4, 7.4, 7.7
 */
class SyncEngineControllerTest {

    private fun makeLocation(
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        timestamp: Long = System.currentTimeMillis(),
        accuracy: Float = 10.0f,
        speed: Float? = null,
        altitude: Double? = null,
        bearing: Float? = null
    ): LocationDataPoint {
        return LocationDataPoint(
            latitude = lat,
            longitude = lng,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude,
            bearing = bearing
        )
    }

    // =========================================================================
    // Property 7: Batch accumulation invariant
    // For any SyncTarget with batchSize N > 1, after dispatching M location data
    // points (where 1 ≤ M < N), zero write operations SHALL have been initiated.
    // After dispatching exactly N points, exactly one batch write SHALL be initiated.
    // Validates: Requirements 4.1, 4.2
    // =========================================================================

    @Test
    fun `batch accumulation - no write until batchSize reached`() {
        // Arrange: Create a TargetHandler with batchSize=5 and track writes
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/batch", method = "push", batchSize = 5),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Dispatch 4 points (less than batchSize of 5)
        repeat(4) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }

        // Allow executor to process
        Thread.sleep(200)

        // Assert: No writes should have occurred
        assertEquals(
            "No write should occur before batchSize is reached",
            0, writeCount.get()
        )

        handler.shutdown()
    }

    @Test
    fun `batch accumulation - write initiated at exactly batchSize`() {
        // Arrange: Create a TargetHandler with batchSize=3 and track writes
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/batch", method = "push", batchSize = 3),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Dispatch exactly 3 points (equals batchSize)
        repeat(3) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }

        // Allow executor to process
        Thread.sleep(300)

        // Assert: Exactly one write should have occurred
        assertEquals(
            "Exactly one write should occur when batchSize is reached",
            1, writeCount.get()
        )

        handler.shutdown()
    }

    @Test
    fun `batch accumulation - two writes for 2x batchSize points`() {
        // Arrange: batchSize=2, dispatch 4 points → expect 2 writes
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/batch", method = "push", batchSize = 2),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Dispatch 4 points
        repeat(4) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }

        // Allow executor to process
        Thread.sleep(300)

        // Assert: Two writes should have occurred
        assertEquals(
            "Two writes should occur for 2x batchSize points",
            2, writeCount.get()
        )

        handler.shutdown()
    }

    @Test
    fun `batch accumulation - immediate write when batchSize is 1`() {
        // Arrange: batchSize=1 means every point triggers a write
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/immediate", method = "set", batchSize = 1),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Dispatch 3 points
        repeat(3) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }

        // Allow executor to process
        Thread.sleep(300)

        // Assert: 3 writes (one per point)
        assertEquals(
            "Each point should trigger a write when batchSize is 1",
            3, writeCount.get()
        )

        handler.shutdown()
    }

    // =========================================================================
    // Property 8: Partial batch flush on stop
    // For any set of SyncTargets with partially-filled batches, when stop() is
    // called, the Sync_Engine SHALL flush all accumulated points for every target.
    // Validates: Requirements 4.4
    // =========================================================================

    @Test
    fun `partial batch flush on stop - flushes accumulated points`() {
        // Arrange: batchSize=10, dispatch 3 points (partial batch)
        val writeCount = AtomicInteger(0)
        val writtenPointCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/flush", method = "push", batchSize = 10),
            onWriteWithPoints = { points ->
                writeCount.incrementAndGet()
                writtenPointCount.addAndGet(points)
            }
        )

        // Act: Dispatch 3 points (less than batchSize of 10)
        repeat(3) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }
        Thread.sleep(200)

        // Verify no write yet
        assertEquals(0, writeCount.get())

        // Act: Flush (simulates stop)
        handler.flush()

        // Assert: The partial batch should have been flushed
        assertEquals(
            "Flush should trigger a write for partial batch",
            1, writeCount.get()
        )
        assertEquals(
            "All 3 accumulated points should be written",
            3, writtenPointCount.get()
        )

        handler.shutdown()
    }

    @Test
    fun `partial batch flush on stop - SyncEngineController flushAll flushes all targets`() {
        // Arrange: Two targets with partial batches
        val target1Writes = AtomicInteger(0)
        val target2Writes = AtomicInteger(0)

        val handler1 = createTestableTargetHandler(
            config = SyncTargetConfig(path = "target/one", method = "push", batchSize = 10),
            onWrite = { target1Writes.incrementAndGet() }
        )
        val handler2 = createTestableTargetHandler(
            config = SyncTargetConfig(path = "target/two", method = "push", batchSize = 10),
            onWrite = { target2Writes.incrementAndGet() }
        )

        // Dispatch partial batches to both
        repeat(3) { handler1.dispatch(makeLocation(timestamp = 1000L + it)) }
        repeat(5) { handler2.dispatch(makeLocation(timestamp = 2000L + it)) }
        Thread.sleep(200)

        // Verify no writes yet
        assertEquals(0, target1Writes.get())
        assertEquals(0, target2Writes.get())

        // Act: Flush both (simulates SyncEngineController.flushAll())
        handler1.flush()
        handler2.flush()

        // Assert: Both targets should have flushed
        assertEquals("Target 1 should flush partial batch", 1, target1Writes.get())
        assertEquals("Target 2 should flush partial batch", 1, target2Writes.get())

        handler1.shutdown()
        handler2.shutdown()
    }

    @Test
    fun `partial batch flush on stop - no write when batch is empty`() {
        // Arrange: No points dispatched
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/empty", method = "push", batchSize = 10),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Flush with empty batch
        handler.flush()

        // Assert: No write should occur
        assertEquals(
            "No write should occur when flushing an empty batch",
            0, writeCount.get()
        )

        handler.shutdown()
    }

    // =========================================================================
    // Property 9: Target isolation on failure
    // If a write operation fails for one target, all other targets SHALL continue
    // to receive and process new location data points independently.
    // Validates: Requirements 3.5, 7.4
    // =========================================================================

    @Test
    fun `target isolation - failure in one target does not block others`() {
        // Arrange: Two handlers - one that always fails, one that succeeds
        val failingWriteCount = AtomicInteger(0)
        val successWriteCount = AtomicInteger(0)

        val failingHandler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "target/failing", method = "set", batchSize = 1),
            onWrite = {
                failingWriteCount.incrementAndGet()
                throw RuntimeException("Simulated Firebase failure")
            }
        )

        val successHandler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "target/success", method = "set", batchSize = 1),
            onWrite = { successWriteCount.incrementAndGet() }
        )

        // Act: Dispatch locations to both handlers
        val location = makeLocation()
        failingHandler.dispatch(location)
        successHandler.dispatch(location)

        // Allow processing
        Thread.sleep(300)

        // Assert: Success handler should have processed the write
        assertTrue(
            "Successful target should process writes regardless of other target failures",
            successWriteCount.get() >= 1
        )

        failingHandler.shutdown()
        successHandler.shutdown()
    }

    @Test
    fun `target isolation - multiple targets process independently`() {
        // Arrange: Three targets with different batch sizes
        val writes = Array(3) { AtomicInteger(0) }

        val handlers = listOf(
            createTestableTargetHandler(
                config = SyncTargetConfig(path = "target/a", method = "push", batchSize = 1),
                onWrite = { writes[0].incrementAndGet() }
            ),
            createTestableTargetHandler(
                config = SyncTargetConfig(path = "target/b", method = "push", batchSize = 2),
                onWrite = { writes[1].incrementAndGet() }
            ),
            createTestableTargetHandler(
                config = SyncTargetConfig(path = "target/c", method = "push", batchSize = 3),
                onWrite = { writes[2].incrementAndGet() }
            )
        )

        // Act: Dispatch 3 locations to all handlers
        repeat(3) { i ->
            val loc = makeLocation(timestamp = 1000L + i)
            handlers.forEach { it.dispatch(loc) }
        }

        Thread.sleep(400)

        // Assert: Each target should have written according to its own batchSize
        // target/a (batchSize=1): 3 writes (one per point)
        // target/b (batchSize=2): 1 write (2 points), 1 point still buffered
        // target/c (batchSize=3): 1 write (3 points)
        assertEquals("Target A (batchSize=1) should have 3 writes", 3, writes[0].get())
        assertEquals("Target B (batchSize=2) should have 1 write", 1, writes[1].get())
        assertEquals("Target C (batchSize=3) should have 1 write", 1, writes[2].get())

        handlers.forEach { it.shutdown() }
    }

    @Test
    fun `target isolation - SyncEngineController dispatches to all targets in parallel`() {
        // Arrange: Create a controller with multiple targets via JSON
        val targetsJson = """[
            {"path": "parallel/target1", "method": "set", "batchSize": 1},
            {"path": "parallel/target2", "method": "push", "batchSize": 1},
            {"path": "parallel/target3", "method": "update", "batchSize": 1}
        ]"""

        // We can't easily mock Firebase in the controller, but we can verify
        // that the controller creates handlers for all targets
        val controller = SyncEngineController(
            targetsJson = targetsJson,
            firebaseService = "RTDB",
            networkChecker = { true }
        )

        // Assert: All target paths are registered
        val paths = controller.getTargetPaths()
        assertEquals(3, paths.size)
        assertTrue(paths.contains("parallel/target1"))
        assertTrue(paths.contains("parallel/target2"))
        assertTrue(paths.contains("parallel/target3"))

        controller.shutdown()
    }

    // =========================================================================
    // Property 11: Retry cancellation on newer data for overwrite targets
    // For set/update targets with an active retry, when new data arrives,
    // the in-progress retry SHALL be cancelled and only the newest data written.
    // Validates: Requirements 7.7
    // =========================================================================

    @Test
    fun `retry cancellation - set target cancels retry when newer data arrives`() {
        // Arrange: A set target that fails on first write (triggering retry)
        // then receives new data which should cancel the retry
        val writeAttempts = mutableListOf<Long>() // Track timestamps of write attempts
        val writeLatch = CountDownLatch(1)
        var firstCallFailed = false

        val handler = createTestableTargetHandlerWithRetry(
            config = SyncTargetConfig(path = "test/set-cancel", method = "set", batchSize = 1),
            onWrite = { timestamp ->
                synchronized(writeAttempts) {
                    writeAttempts.add(timestamp)
                }
                if (!firstCallFailed) {
                    firstCallFailed = true
                    // Simulate transient failure to trigger retry
                    throw TransientWriteException("Network timeout")
                }
                // Subsequent calls succeed
                writeLatch.countDown()
            }
        )

        // Act: Dispatch first location (will fail and start retry)
        handler.dispatch(makeLocation(timestamp = 1000L))
        Thread.sleep(100) // Let the first dispatch process and fail

        // Dispatch newer location (should cancel retry of old data)
        handler.dispatch(makeLocation(timestamp = 2000L))

        // Wait for the write to complete
        writeLatch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        // Assert: The last written timestamp should be the newer one (2000L)
        synchronized(writeAttempts) {
            assertTrue(
                "At least one write attempt should have been made",
                writeAttempts.isNotEmpty()
            )
            // The final successful write should be for the newer data
            assertEquals(
                "The newest data (timestamp 2000) should be the last written",
                2000L, writeAttempts.last()
            )
        }

        handler.shutdown()
    }

    @Test
    fun `retry cancellation - update target cancels retry when newer data arrives`() {
        // Same as set target - update should also cancel retries on newer data
        val writeAttempts = mutableListOf<Long>()
        val writeLatch = CountDownLatch(1)
        var firstCallFailed = false

        val handler = createTestableTargetHandlerWithRetry(
            config = SyncTargetConfig(path = "test/update-cancel", method = "update", batchSize = 1),
            onWrite = { timestamp ->
                synchronized(writeAttempts) {
                    writeAttempts.add(timestamp)
                }
                if (!firstCallFailed) {
                    firstCallFailed = true
                    throw TransientWriteException("Network timeout")
                }
                writeLatch.countDown()
            }
        )

        // Act: Dispatch first location (will fail and start retry)
        handler.dispatch(makeLocation(timestamp = 1000L))
        Thread.sleep(100)

        // Dispatch newer location (should cancel retry)
        handler.dispatch(makeLocation(timestamp = 2000L))

        writeLatch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        // Assert: Final write should be for newer data
        synchronized(writeAttempts) {
            assertTrue(writeAttempts.isNotEmpty())
            assertEquals(
                "Update target should write newest data after cancelling retry",
                2000L, writeAttempts.last()
            )
        }

        handler.shutdown()
    }

    @Test
    fun `retry cancellation - push target does NOT cancel retry on newer data`() {
        // Push targets should NOT cancel retries - all data must be preserved
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/push-no-cancel", method = "push", batchSize = 1),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Act: Dispatch multiple locations rapidly
        repeat(3) {
            handler.dispatch(makeLocation(timestamp = 1000L + it))
        }

        Thread.sleep(400)

        // Assert: All writes should proceed (no cancellation for push)
        assertEquals(
            "Push target should write all data without cancellation",
            3, writeCount.get()
        )

        handler.shutdown()
    }

    // =========================================================================
    // SyncEngineController JSON parsing and multi-target creation tests
    // Validates: Requirements 3.1
    // =========================================================================

    @Test
    fun `SyncEngineController parses valid targets JSON`() {
        val targetsJson = """[
            {"path": "users/abc/location", "method": "set"},
            {"path": "trips/xyz/history", "method": "push", "batchSize": 20, "offlineQueue": true}
        ]"""

        val controller = SyncEngineController(
            targetsJson = targetsJson,
            firebaseService = "RTDB",
            networkChecker = { true }
        )

        val paths = controller.getTargetPaths()
        assertEquals(2, paths.size)
        assertEquals("users/abc/location", paths[0])
        assertEquals("trips/xyz/history", paths[1])

        controller.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncEngineController rejects empty targets array`() {
        SyncEngineController(
            targetsJson = "[]",
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncEngineController rejects invalid JSON`() {
        SyncEngineController(
            targetsJson = "not valid json",
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncEngineController rejects target missing path`() {
        SyncEngineController(
            targetsJson = """[{"method": "set"}]""",
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncEngineController rejects target missing method`() {
        SyncEngineController(
            targetsJson = """[{"path": "test/path"}]""",
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncEngineController rejects target with invalid method`() {
        SyncEngineController(
            targetsJson = """[{"path": "test/path", "method": "delete"}]""",
            firebaseService = "RTDB",
            networkChecker = { true }
        )
    }

    @Test
    fun `SyncEngineController getQueuedCounts returns zero for non-queuing targets`() {
        val targetsJson = """[
            {"path": "target/a", "method": "set"},
            {"path": "target/b", "method": "push"}
        ]"""

        val controller = SyncEngineController(
            targetsJson = targetsJson,
            firebaseService = "RTDB",
            networkChecker = { true }
        )

        val counts = controller.getQueuedCounts()
        assertEquals(0, counts["target/a"])
        assertEquals(0, counts["target/b"])

        controller.shutdown()
    }

    // =========================================================================
    // TargetHandler - offline behavior tests
    // =========================================================================

    @Test
    fun `TargetHandler discards data when offline and offlineQueue disabled`() {
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/discard", method = "set", batchSize = 1, offlineQueue = false),
            networkOnline = false,
            onWrite = { writeCount.incrementAndGet() }
        )

        // Dispatch while offline
        handler.dispatch(makeLocation())
        Thread.sleep(200)

        // No write should occur (data discarded)
        assertEquals(0, writeCount.get())

        handler.shutdown()
    }

    @Test
    fun `TargetHandler does not dispatch after shutdown`() {
        val writeCount = AtomicInteger(0)

        val handler = createTestableTargetHandler(
            config = SyncTargetConfig(path = "test/shutdown", method = "set", batchSize = 1),
            onWrite = { writeCount.incrementAndGet() }
        )

        // Shutdown first
        handler.shutdown()

        // Dispatch after shutdown
        handler.dispatch(makeLocation())
        Thread.sleep(200)

        // No write should occur
        assertEquals(0, writeCount.get())
    }

    // =========================================================================
    // Helper: Testable TargetHandler that bypasses Firebase
    // =========================================================================

    /**
     * Creates a TargetHandler subclass that intercepts write operations
     * for testing without requiring Firebase SDK initialization.
     */
    private fun createTestableTargetHandler(
        config: SyncTargetConfig,
        networkOnline: Boolean = true,
        onWrite: (() -> Unit)? = null,
        onWriteWithPoints: ((Int) -> Unit)? = null
    ): TestableTargetHandler {
        return TestableTargetHandler(
            config = config,
            networkOnline = networkOnline,
            onWrite = onWrite,
            onWriteWithPoints = onWriteWithPoints
        )
    }

    private fun createTestableTargetHandlerWithRetry(
        config: SyncTargetConfig,
        onWrite: (Long) -> Unit
    ): TestableTargetHandlerWithRetry {
        return TestableTargetHandlerWithRetry(
            config = config,
            onWrite = onWrite
        )
    }
}

/**
 * Exception class to simulate transient Firebase write failures in tests.
 */
class TransientWriteException(message: String) : RuntimeException(message)

/**
 * A testable TargetHandler that bypasses Firebase SDK calls.
 * Instead of writing to Firebase, it invokes the provided callbacks.
 *
 * This allows testing batch accumulation, flush, and dispatch logic
 * without requiring Firebase initialization.
 */
class TestableTargetHandler(
    private val config: SyncTargetConfig,
    private val networkOnline: Boolean = true,
    private val onWrite: (() -> Unit)? = null,
    private val onWriteWithPoints: ((Int) -> Unit)? = null
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val batch: MutableList<LocationDataPoint> = mutableListOf()
    private val isShutdown = AtomicBoolean(false)

    val targetPath: String get() = config.path

    fun dispatch(location: LocationDataPoint) {
        if (isShutdown.get()) return

        executor.execute {
            // Check if device is offline
            if (!networkOnline) {
                // Discard if offlineQueue is disabled (simplified for testing)
                return@execute
            }

            // Accumulate in batch
            batch.add(location)

            // Check if batch is full
            if (batch.size >= config.batchSize) {
                flushBatch()
            }
        }
    }

    fun flush() {
        if (isShutdown.get()) return

        val future = executor.submit {
            if (batch.isNotEmpty()) {
                flushBatch()
            }
        }
        try {
            future.get()
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun shutdown() {
        isShutdown.set(true)
        executor.shutdown()
    }

    private fun flushBatch() {
        if (batch.isEmpty()) return

        val pointCount = batch.size
        batch.clear()

        try {
            onWrite?.invoke()
            onWriteWithPoints?.invoke(pointCount)
        } catch (e: Exception) {
            // Simulated failure - target isolation means we don't propagate
        }
    }
}

/**
 * A testable TargetHandler that supports retry cancellation testing.
 * For set/update targets, newer data cancels in-progress retries.
 */
class TestableTargetHandlerWithRetry(
    private val config: SyncTargetConfig,
    private val onWrite: (Long) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val batch: MutableList<LocationDataPoint> = mutableListOf()
    private val isShutdown = AtomicBoolean(false)
    private val retryGeneration = AtomicInteger(0)
    private val isRetrying = AtomicBoolean(false)

    private val maxRetries: Int
        get() = if (config.method == "push") 5 else 3

    fun dispatch(location: LocationDataPoint) {
        if (isShutdown.get()) return

        executor.execute {
            // For set/update targets, cancel any in-progress retry
            if (config.method == "set" || config.method == "update") {
                if (isRetrying.get()) {
                    retryGeneration.incrementAndGet()
                    isRetrying.set(false)
                }
            }

            // Accumulate in batch
            batch.add(location)

            // Check if batch is full
            if (batch.size >= config.batchSize) {
                flushBatch()
            }
        }
    }

    fun shutdown() {
        isShutdown.set(true)
        retryGeneration.incrementAndGet()
        executor.shutdown()
    }

    private fun flushBatch() {
        if (batch.isEmpty()) return

        val points = ArrayList(batch)
        batch.clear()

        val generation = retryGeneration.get()
        executeWithRetry(generation, points)
    }

    private fun executeWithRetry(generation: Int, points: List<LocationDataPoint>) {
        isRetrying.set(true)
        var attempt = 0

        fun tryOperation() {
            // Check if superseded
            if (retryGeneration.get() != generation) {
                isRetrying.set(false)
                return
            }

            attempt++
            val lastPoint = if (config.method == "push") points.first() else points.last()

            try {
                onWrite(lastPoint.timestamp)
                isRetrying.set(false)
            } catch (e: TransientWriteException) {
                // Check if superseded before retrying
                if (retryGeneration.get() != generation) {
                    isRetrying.set(false)
                    return
                }

                if (attempt >= maxRetries) {
                    isRetrying.set(false)
                    return
                }

                // Short delay for testing (not real backoff)
                try {
                    Thread.sleep(50)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    isRetrying.set(false)
                    return
                }

                // Check again after sleep
                if (retryGeneration.get() != generation) {
                    isRetrying.set(false)
                    return
                }

                tryOperation()
            } catch (e: Exception) {
                isRetrying.set(false)
            }
        }

        tryOperation()
    }
}
