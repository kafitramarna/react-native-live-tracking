package com.livetracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.livetracking.queue.QueuedLocation
import com.livetracking.queue.TrackingDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for the offline queue (TrackingDatabase per-target methods).
 * Uses Robolectric to provide an in-memory SQLite database.
 *
 * **Validates: Requirements 5.1, 5.2, 5.4, 5.7, 10.1, 10.2, 10.3**
 * **Properties 12, 13, 14, 15**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OfflineQueueTest {

    private lateinit var db: TrackingDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Create a fresh database instance for each test
        db = TrackingDatabase(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    // --- Helper ---

    private fun createLocation(
        id: String = UUID.randomUUID().toString(),
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        timestamp: Long = System.currentTimeMillis(),
        accuracy: Float = 5.0f,
        speed: Float? = null,
        altitude: Double? = null,
        bearing: Float? = null,
        createdAt: Long = System.currentTimeMillis()
    ): QueuedLocation {
        return QueuedLocation(
            id = id,
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude,
            bearing = bearing,
            createdAt = createdAt
        )
    }

    // =========================================================================
    // Property 12: Offline queue size invariant
    // For any SyncTarget with offlineQueue: true, the number of queued data
    // points for that target SHALL never exceed 10,000. When the queue is at
    // capacity and a new point arrives, the oldest point SHALL be evicted.
    // Validates: Requirements 5.1, 5.7
    // =========================================================================

    @Test
    fun `insertForTarget enforces 10000 cap by evicting oldest`() {
        val targetPath = "drivers/abc/location"

        // Insert exactly MAX_QUEUE_SIZE_PER_TARGET items
        for (i in 1..TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET) {
            val location = createLocation(
                id = "loc-$i",
                createdAt = i.toLong()
            )
            db.insertForTarget(location, targetPath)
        }

        assertEquals(TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET, db.getCountForTarget(targetPath))

        // Insert one more — should evict the oldest (createdAt = 1)
        val newLocation = createLocation(
            id = "loc-new",
            createdAt = (TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET + 1).toLong()
        )
        db.insertForTarget(newLocation, targetPath)

        // Count should still be at the cap
        assertEquals(TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET, db.getCountForTarget(targetPath))

        // The oldest item (createdAt = 1) should have been evicted
        val oldest = db.getOldestBatchForTarget(targetPath, 1)
        assertEquals(1, oldest.size)
        // The oldest remaining should be createdAt = 2 (the first was evicted)
        assertEquals(2L, oldest[0].createdAt)
    }

    @Test
    fun `insertForTarget evicts oldest when at capacity - new item is preserved`() {
        val targetPath = "trips/xyz/history"

        // Fill to capacity
        for (i in 1..TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET) {
            db.insertForTarget(createLocation(id = "item-$i", createdAt = i.toLong()), targetPath)
        }

        // Insert a new item
        val newId = "newest-item"
        db.insertForTarget(
            createLocation(id = newId, createdAt = 99999L),
            targetPath
        )

        // The new item should be in the queue
        val newest = db.getOldestBatchForTarget(targetPath, TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET)
        assertTrue(newest.any { it.id == newId })
    }

    @Test
    fun `queue never exceeds 10000 after multiple inserts beyond cap`() {
        val targetPath = "test/overflow"

        // Insert 10,050 items (50 over the cap)
        for (i in 1..10_050) {
            db.insertForTarget(createLocation(id = "item-$i", createdAt = i.toLong()), targetPath)
        }

        // Count should be exactly at the cap
        assertEquals(TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET, db.getCountForTarget(targetPath))

        // The oldest remaining should be item 51 (items 1-50 were evicted)
        val oldest = db.getOldestBatchForTarget(targetPath, 1)
        assertEquals(51L, oldest[0].createdAt)
    }

    // =========================================================================
    // Property 13: Chronological flush ordering
    // When connectivity is restored, queued data points SHALL be flushed in
    // chronological order (oldest first).
    // Validates: Requirements 5.2
    // =========================================================================

    @Test
    fun `getOldestBatchForTarget returns items in chronological order`() {
        val targetPath = "drivers/abc/location"

        // Insert items in non-sequential order
        db.insertForTarget(createLocation(id = "third", createdAt = 300L), targetPath)
        db.insertForTarget(createLocation(id = "first", createdAt = 100L), targetPath)
        db.insertForTarget(createLocation(id = "second", createdAt = 200L), targetPath)

        val batch = db.getOldestBatchForTarget(targetPath, 3)

        assertEquals(3, batch.size)
        assertEquals("first", batch[0].id)
        assertEquals("second", batch[1].id)
        assertEquals("third", batch[2].id)
    }

    @Test
    fun `getOldestBatchForTarget respects limit parameter`() {
        val targetPath = "test/batch"

        for (i in 1..10) {
            db.insertForTarget(createLocation(id = "item-$i", createdAt = i.toLong()), targetPath)
        }

        val batch = db.getOldestBatchForTarget(targetPath, 5)

        assertEquals(5, batch.size)
        // Should be the 5 oldest
        assertEquals(1L, batch[0].createdAt)
        assertEquals(5L, batch[4].createdAt)
    }

    @Test
    fun `deleteByIds removes items and subsequent dequeue skips them`() {
        val targetPath = "test/flush"

        // Insert 5 items
        for (i in 1..5) {
            db.insertForTarget(createLocation(id = "item-$i", createdAt = i.toLong()), targetPath)
        }

        // Simulate flush: dequeue first 2, then delete them
        val firstBatch = db.getOldestBatchForTarget(targetPath, 2)
        assertEquals(2, firstBatch.size)
        db.deleteByIds(firstBatch.map { it.id })

        // Next dequeue should start from item-3
        val secondBatch = db.getOldestBatchForTarget(targetPath, 2)
        assertEquals(2, secondBatch.size)
        assertEquals("item-3", secondBatch[0].id)
        assertEquals("item-4", secondBatch[1].id)
    }

    @Test
    fun `chronological ordering is maintained after eviction`() {
        val targetPath = "test/order-after-eviction"

        // Fill to capacity with items createdAt = 1..10000
        for (i in 1..TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET) {
            db.insertForTarget(createLocation(id = "item-$i", createdAt = i.toLong()), targetPath)
        }

        // Insert 5 more (evicts items 1-5)
        for (i in 1..5) {
            db.insertForTarget(
                createLocation(id = "new-$i", createdAt = (10000 + i).toLong()),
                targetPath
            )
        }

        // Dequeue should still be in chronological order
        val batch = db.getOldestBatchForTarget(targetPath, 5)
        assertEquals(5, batch.size)
        // Oldest remaining should be item-6 (createdAt = 6)
        assertEquals(6L, batch[0].createdAt)
        assertEquals(7L, batch[1].createdAt)
        assertEquals(8L, batch[2].createdAt)
        assertEquals(9L, batch[3].createdAt)
        assertEquals(10L, batch[4].createdAt)
    }

    // =========================================================================
    // Property 14: Queue count aggregation
    // getQueuedLocations() SHALL return c1 + c2 + ... + cn, and
    // getQueuedLocationsByTarget() SHALL return a record mapping each target
    // path to its respective count.
    // Validates: Requirements 10.1, 10.2
    // =========================================================================

    @Test
    fun `getCountsByTarget returns correct per-target counts`() {
        val target1 = "drivers/abc/location"
        val target2 = "trips/xyz/history"
        val target3 = "fleet/status"

        // Insert different counts per target
        for (i in 1..3) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target1)
        }
        for (i in 1..7) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target2)
        }
        for (i in 1..2) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target3)
        }

        val counts = db.getCountsByTarget()

        assertEquals(3, counts[target1])
        assertEquals(7, counts[target2])
        assertEquals(2, counts[target3])
    }

    @Test
    fun `getCountsByTarget aggregation sums to total count`() {
        val target1 = "path/a"
        val target2 = "path/b"

        for (i in 1..5) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target1)
        }
        for (i in 1..8) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target2)
        }

        val counts = db.getCountsByTarget()
        val totalFromAggregation = counts.values.sum()
        val totalFromCount = db.getCount()

        assertEquals(totalFromCount, totalFromAggregation)
        assertEquals(13, totalFromAggregation)
    }

    @Test
    fun `getCountForTarget returns correct count for specific target`() {
        val target1 = "target/one"
        val target2 = "target/two"

        for (i in 1..4) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target1)
        }
        for (i in 1..6) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), target2)
        }

        assertEquals(4, db.getCountForTarget(target1))
        assertEquals(6, db.getCountForTarget(target2))
    }

    @Test
    fun `getCountsByTarget returns empty map when no items queued`() {
        val counts = db.getCountsByTarget()
        assertTrue(counts.isEmpty())
    }

    @Test
    fun `getCountsByTarget updates after deletions`() {
        val target = "test/delete"

        for (i in 1..5) {
            db.insertForTarget(createLocation(id = "del-$i", createdAt = i.toLong()), target)
        }

        // Delete 2 items
        db.deleteByIds(listOf("del-1", "del-2"))

        val counts = db.getCountsByTarget()
        assertEquals(3, counts[target])
    }

    // =========================================================================
    // Property 15: Non-queuing targets report zero
    // For any SyncTarget with offlineQueue set to false or undefined,
    // getQueuedLocationsByTarget() SHALL report 0 queued locations for that
    // target's path.
    // Validates: Requirements 10.3
    // =========================================================================

    @Test
    fun `getCountForTarget returns zero for target with no queued items`() {
        // A target that has never had items queued (simulates offlineQueue: false)
        val nonQueuingTarget = "realtime/current"

        assertEquals(0, db.getCountForTarget(nonQueuingTarget))
    }

    @Test
    fun `getCountsByTarget does not include targets with zero items`() {
        val queuingTarget = "history/path"
        val nonQueuingTarget = "realtime/path"

        // Only insert for the queuing target
        db.insertForTarget(createLocation(createdAt = 1L), queuingTarget)

        val counts = db.getCountsByTarget()

        // The queuing target should be present
        assertEquals(1, counts[queuingTarget])
        // The non-queuing target should not appear (or be absent from the map)
        assertNull(counts[nonQueuingTarget])
    }

    @Test
    fun `non-queuing target reports zero regardless of other targets having items`() {
        val queuingTarget1 = "target/queued1"
        val queuingTarget2 = "target/queued2"
        val nonQueuingTarget = "target/no-queue"

        // Insert items for queuing targets
        for (i in 1..10) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), queuingTarget1)
        }
        for (i in 1..5) {
            db.insertForTarget(createLocation(createdAt = i.toLong()), queuingTarget2)
        }

        // Non-queuing target should report zero
        assertEquals(0, db.getCountForTarget(nonQueuingTarget))

        // Verify queuing targets have their counts
        assertEquals(10, db.getCountForTarget(queuingTarget1))
        assertEquals(5, db.getCountForTarget(queuingTarget2))
    }

    // =========================================================================
    // Per-target queue isolation (Property 12 extension)
    // Validates: Requirements 5.4
    // =========================================================================

    @Test
    fun `per-target queues are isolated - insert to one does not affect another`() {
        val target1 = "drivers/abc/location"
        val target2 = "trips/xyz/history"

        for (i in 1..5) {
            db.insertForTarget(createLocation(id = "t1-$i", createdAt = i.toLong()), target1)
        }
        for (i in 1..3) {
            db.insertForTarget(createLocation(id = "t2-$i", createdAt = i.toLong()), target2)
        }

        assertEquals(5, db.getCountForTarget(target1))
        assertEquals(3, db.getCountForTarget(target2))
    }

    @Test
    fun `per-target queues are isolated - dequeue from one does not affect another`() {
        val target1 = "path/a"
        val target2 = "path/b"

        for (i in 1..5) {
            db.insertForTarget(createLocation(id = "a-$i", createdAt = i.toLong()), target1)
        }
        for (i in 1..5) {
            db.insertForTarget(createLocation(id = "b-$i", createdAt = i.toLong()), target2)
        }

        // Dequeue from target1
        val batch = db.getOldestBatchForTarget(target1, 5)
        assertEquals(5, batch.size)
        // All items should belong to target1
        batch.forEach { assertTrue(it.id.startsWith("a-")) }

        // target2 should be unaffected
        assertEquals(5, db.getCountForTarget(target2))
    }

    @Test
    fun `per-target cap enforcement is isolated - overflow in one does not evict from another`() {
        val target1 = "target/capped"
        val target2 = "target/safe"

        // Fill target2 with 100 items
        for (i in 1..100) {
            db.insertForTarget(createLocation(id = "safe-$i", createdAt = i.toLong()), target2)
        }

        // Fill target1 to capacity and overflow
        for (i in 1..(TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET + 10)) {
            db.insertForTarget(createLocation(id = "capped-$i", createdAt = i.toLong()), target1)
        }

        // target1 should be at cap
        assertEquals(TrackingDatabase.MAX_QUEUE_SIZE_PER_TARGET, db.getCountForTarget(target1))
        // target2 should be completely unaffected
        assertEquals(100, db.getCountForTarget(target2))
    }

    @Test
    fun `evictOldestForTarget only evicts from specified target`() {
        val target1 = "target/evict"
        val target2 = "target/keep"

        db.insertForTarget(createLocation(id = "evict-1", createdAt = 1L), target1)
        db.insertForTarget(createLocation(id = "evict-2", createdAt = 2L), target1)
        db.insertForTarget(createLocation(id = "keep-1", createdAt = 1L), target2)
        db.insertForTarget(createLocation(id = "keep-2", createdAt = 2L), target2)

        // Evict oldest from target1
        db.evictOldestForTarget(target1)

        // target1 should have lost its oldest
        assertEquals(1, db.getCountForTarget(target1))
        val remaining = db.getOldestBatchForTarget(target1, 1)
        assertEquals("evict-2", remaining[0].id)

        // target2 should be unaffected
        assertEquals(2, db.getCountForTarget(target2))
    }
}
