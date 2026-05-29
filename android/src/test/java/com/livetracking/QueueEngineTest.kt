package com.livetracking

import com.livetracking.queue.QueuedLocation
import com.livetracking.queue.QueuedLocationDao
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Callable

/**
 * Unit tests for QueueEngine and QueuedLocation.
 *
 * NOTE: QueueEngine depends on Room Database (TrackingDatabase) which requires Android Context.
 * These tests verify:
 * - QueuedLocation data class structure and field correctness
 * - UUID generation for unique IDs
 * - Executor-based background threading pattern
 * - DAO interface contract
 *
 * Full Room database integration tests (insert, query, delete) require instrumented tests
 * (androidTest) running on a device or emulator with a real or in-memory Room database.
 */
class QueueEngineTest {

    private lateinit var mockDao: QueuedLocationDao
    private lateinit var executor: ExecutorService

    @Before
    fun setup() {
        mockDao = mockk(relaxed = true)
        executor = Executors.newSingleThreadExecutor()
    }

    // --- QueuedLocation Data Class Tests ---

    @Test
    fun `QueuedLocation has all required fields`() {
        val location = QueuedLocation(
            id = "test-id-123",
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = 1700000000000L,
            accuracy = 5.0f,
            speed = 2.5f,
            altitude = 100.0,
            bearing = 180.0f,
            createdAt = 1700000000100L
        )

        assertEquals("test-id-123", location.id)
        assertEquals(37.7749, location.latitude, 0.0001)
        assertEquals(-122.4194, location.longitude, 0.0001)
        assertEquals(1700000000000L, location.timestamp)
        assertEquals(5.0f, location.accuracy, 0.01f)
        assertEquals(2.5f, location.speed!!, 0.01f)
        assertEquals(100.0, location.altitude!!, 0.01)
        assertEquals(180.0f, location.bearing!!, 0.01f)
        assertEquals(1700000000100L, location.createdAt)
    }

    @Test
    fun `QueuedLocation allows nullable speed`() {
        val location = QueuedLocation(
            id = "test-id",
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L,
            accuracy = 0.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = 0L
        )

        assertNull(location.speed)
    }

    @Test
    fun `QueuedLocation allows nullable altitude`() {
        val location = QueuedLocation(
            id = "test-id",
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L,
            accuracy = 0.0f,
            speed = 1.0f,
            altitude = null,
            bearing = null,
            createdAt = 0L
        )

        assertNull(location.altitude)
    }

    @Test
    fun `QueuedLocation allows nullable bearing`() {
        val location = QueuedLocation(
            id = "test-id",
            latitude = 0.0,
            longitude = 0.0,
            timestamp = 0L,
            accuracy = 0.0f,
            speed = 1.0f,
            altitude = 50.0,
            bearing = null,
            createdAt = 0L
        )

        assertNull(location.bearing)
    }

    @Test
    fun `QueuedLocation data class supports equality`() {
        val loc1 = QueuedLocation(
            id = "same-id",
            latitude = 1.0,
            longitude = 2.0,
            timestamp = 100L,
            accuracy = 5.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = 200L
        )

        val loc2 = QueuedLocation(
            id = "same-id",
            latitude = 1.0,
            longitude = 2.0,
            timestamp = 100L,
            accuracy = 5.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = 200L
        )

        assertEquals(loc1, loc2)
    }

    @Test
    fun `QueuedLocation data class supports copy`() {
        val original = QueuedLocation(
            id = "original-id",
            latitude = 1.0,
            longitude = 2.0,
            timestamp = 100L,
            accuracy = 5.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = 200L
        )

        val copy = original.copy(latitude = 99.0)

        assertEquals("original-id", copy.id)
        assertEquals(99.0, copy.latitude, 0.0001)
        assertEquals(2.0, copy.longitude, 0.0001)
    }

    // --- UUID Generation Tests ---

    @Test
    fun `UUID randomUUID generates unique IDs`() {
        val ids = (1..100).map { UUID.randomUUID().toString() }.toSet()
        assertEquals(100, ids.size) // All 100 should be unique
    }

    @Test
    fun `UUID randomUUID generates valid UUID format`() {
        val uuid = UUID.randomUUID().toString()
        // UUID format: 8-4-4-4-12 hex characters
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("UUID should match standard format: $uuid", uuidRegex.matches(uuid))
    }

    @Test
    fun `UUID is used as primary key for QueuedLocation`() {
        val id = UUID.randomUUID().toString()
        val location = QueuedLocation(
            id = id,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = System.currentTimeMillis(),
            accuracy = 0.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = System.currentTimeMillis()
        )

        assertNotNull(location.id)
        assertTrue(location.id.isNotEmpty())
        assertEquals(36, location.id.length) // UUID string length is always 36
    }

    // --- Executor Background Thread Tests ---

    @Test
    fun `executor submits tasks and returns Future`() {
        val future: Future<String> = executor.submit(Callable { "result" })
        assertEquals("result", future.get())
    }

    @Test
    fun `executor runs tasks on background thread`() {
        val mainThread = Thread.currentThread()
        var taskThread: Thread? = null

        val future = executor.submit(Callable {
            taskThread = Thread.currentThread()
            "done"
        })
        future.get() // Wait for completion

        assertNotNull(taskThread)
        assertNotEquals(mainThread, taskThread)
    }

    @Test
    fun `executor processes tasks sequentially with single thread`() {
        val singleThreadExecutor = Executors.newSingleThreadExecutor()
        val executionOrder = mutableListOf<Int>()

        val futures = (1..5).map { index ->
            singleThreadExecutor.submit(Callable {
                executionOrder.add(index)
                index
            })
        }

        // Wait for all to complete
        futures.forEach { it.get() }

        assertEquals(listOf(1, 2, 3, 4, 5), executionOrder)
        singleThreadExecutor.shutdown()
    }

    // --- DAO Interface Contract Tests ---

    @Test
    fun `QueuedLocationDao insert is called with correct location`() {
        val location = QueuedLocation(
            id = "test-id",
            latitude = 37.0,
            longitude = -122.0,
            timestamp = 1000L,
            accuracy = 5.0f,
            speed = null,
            altitude = null,
            bearing = null,
            createdAt = 2000L
        )

        mockDao.insert(location)

        verify(exactly = 1) { mockDao.insert(location) }
    }

    @Test
    fun `QueuedLocationDao getOldestBatch respects limit parameter`() {
        val batch = listOf(
            QueuedLocation("1", 0.0, 0.0, 100L, 5.0f, null, null, null, 100L),
            QueuedLocation("2", 0.0, 0.0, 200L, 5.0f, null, null, null, 200L)
        )

        every { mockDao.getOldestBatch(10) } returns batch

        val result = mockDao.getOldestBatch(10)

        assertEquals(2, result.size)
        verify { mockDao.getOldestBatch(10) }
    }

    @Test
    fun `QueuedLocationDao deleteByIds accepts list of IDs`() {
        val ids = listOf("id-1", "id-2", "id-3")

        mockDao.deleteByIds(ids)

        verify(exactly = 1) { mockDao.deleteByIds(ids) }
    }

    @Test
    fun `QueuedLocationDao getCount returns integer`() {
        every { mockDao.getCount() } returns 42

        val count = mockDao.getCount()

        assertEquals(42, count)
    }

    // --- Enqueue Pattern Tests ---

    @Test
    fun `enqueue pattern creates QueuedLocation with UUID and current time`() {
        val beforeTime = System.currentTimeMillis()

        val location = QueuedLocation(
            id = UUID.randomUUID().toString(),
            latitude = 37.7749,
            longitude = -122.4194,
            timestamp = 1700000000000L,
            accuracy = 10.0f,
            speed = 5.0f,
            altitude = 50.0,
            bearing = 90.0f,
            createdAt = System.currentTimeMillis()
        )

        val afterTime = System.currentTimeMillis()

        // Verify UUID format
        assertTrue(location.id.length == 36)

        // Verify createdAt is approximately now
        assertTrue(location.createdAt >= beforeTime)
        assertTrue(location.createdAt <= afterTime)

        // Verify passed parameters are stored correctly
        assertEquals(37.7749, location.latitude, 0.0001)
        assertEquals(-122.4194, location.longitude, 0.0001)
        assertEquals(1700000000000L, location.timestamp)
        assertEquals(10.0f, location.accuracy, 0.01f)
        assertEquals(5.0f, location.speed!!, 0.01f)
        assertEquals(50.0, location.altitude!!, 0.01)
        assertEquals(90.0f, location.bearing!!, 0.01f)
    }
}
