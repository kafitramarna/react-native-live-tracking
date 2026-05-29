package com.livetracking.queue

import android.content.Context
import com.livetracking.sync.OfflineQueueProvider
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * QueueEngine manages the offline location queue using SQLite.
 * All database operations run on a dedicated background thread.
 *
 * Implements [OfflineQueueProvider] to provide per-target offline queue
 * operations used by [TargetHandler] for target-scoped persistence.
 */
class QueueEngine(context: Context) : OfflineQueueProvider {

    private val db: TrackingDatabase = TrackingDatabase.getInstance(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // --- Legacy methods (backward-compatible, operate without target_path filter) ---

    fun enqueue(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        accuracy: Float,
        speed: Float?,
        altitude: Double?,
        bearing: Float?
    ): Future<Unit> {
        return executor.submit(Callable {
            val location = QueuedLocation(
                id = UUID.randomUUID().toString(),
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp,
                accuracy = accuracy,
                speed = speed,
                altitude = altitude,
                bearing = bearing,
                createdAt = System.currentTimeMillis()
            )
            db.insert(location)
        })
    }

    fun dequeueBatch(size: Int): Future<List<QueuedLocation>> {
        return executor.submit(Callable {
            db.getOldestBatch(size)
        })
    }

    fun removeBatch(ids: List<String>): Future<Unit> {
        return executor.submit(Callable {
            db.deleteByIds(ids)
        })
    }

    fun count(): Future<Int> {
        return executor.submit(Callable {
            db.getCount()
        })
    }

    // --- Per-target methods (OfflineQueueProvider implementation) ---

    /**
     * Enqueue a location data point for a specific target path.
     * Delegates to [TrackingDatabase.insertForTarget] which enforces the
     * 10,000 data point cap per target with oldest-eviction.
     */
    override fun enqueueForTarget(
        targetPath: String,
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        accuracy: Float,
        speed: Float?,
        altitude: Double?,
        bearing: Float?
    ): Future<Unit> {
        return executor.submit(Callable {
            val location = QueuedLocation(
                id = UUID.randomUUID().toString(),
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp,
                accuracy = accuracy,
                speed = speed,
                altitude = altitude,
                bearing = bearing,
                createdAt = System.currentTimeMillis()
            )
            db.insertForTarget(location, targetPath)
        })
    }

    /**
     * Dequeue the oldest batch of queued locations for a specific target path.
     * Results are ordered chronologically (oldest first).
     *
     * @param targetPath The target path to dequeue from
     * @param size Maximum number of locations to dequeue
     * @return Future resolving to the list of queued locations
     */
    override fun dequeueBatchForTarget(targetPath: String, size: Int): Future<List<QueuedLocation>> {
        return executor.submit(Callable {
            db.getOldestBatchForTarget(targetPath, size)
        })
    }

    /**
     * Get the count of queued locations for a specific target path.
     *
     * @param targetPath The target path to count
     * @return Future resolving to the count of queued locations
     */
    override fun countForTarget(targetPath: String): Future<Int> {
        return executor.submit(Callable {
            db.getCountForTarget(targetPath)
        })
    }

    /**
     * Get the count of queued locations grouped by target path.
     *
     * @return Future resolving to a map of target_path → count
     */
    fun countsByTarget(): Future<Map<String, Int>> {
        return executor.submit(Callable {
            db.getCountsByTarget()
        })
    }

    /**
     * Evict the oldest queued location for a specific target path.
     * Used to enforce the 10,000 data point cap per target.
     *
     * @param targetPath The target path to evict from
     * @return Future resolving when eviction is complete
     */
    override fun evictOldestForTarget(targetPath: String): Future<Unit> {
        return executor.submit(Callable {
            db.evictOldestForTarget(targetPath)
        })
    }

    /**
     * Remove a batch of queued locations by their IDs.
     * Used after successful write confirmation during offline queue flush.
     *
     * @param ids List of location IDs to remove
     * @return Future resolving when removal is complete
     */
    override fun removeBatchForTarget(ids: List<String>): Future<Unit> {
        return executor.submit(Callable {
            db.deleteByIds(ids)
        })
    }

    fun shutdown() {
        executor.shutdown()
    }
}
