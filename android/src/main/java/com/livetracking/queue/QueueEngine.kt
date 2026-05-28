package com.livetracking.queue

import android.content.Context
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * QueueEngine manages the offline location queue using Room Database.
 * All database operations run on a dedicated background thread to avoid blocking the main thread.
 *
 * Responsibilities:
 * - Enqueue new locations with unique IDs
 * - Dequeue oldest batch for sending to Firebase
 * - Remove successfully sent batches
 * - Report current queue count
 */
class QueueEngine(context: Context) {

    private val dao: QueuedLocationDao
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        val database = TrackingDatabase.getInstance(context)
        dao = database.queuedLocationDao()
    }

    /**
     * Enqueue a new location into the local queue.
     * Creates a QueuedLocation with a UUID and current system time as createdAt.
     *
     * @param latitude Location latitude (-90 to 90)
     * @param longitude Location longitude (-180 to 180)
     * @param timestamp Unix timestamp in milliseconds when location was captured
     * @param accuracy Location accuracy in meters
     * @param speed Speed in m/s, nullable
     * @param altitude Altitude in meters, nullable
     * @param bearing Bearing in degrees (0-360), nullable
     */
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
            dao.insert(location)
        })
    }

    /**
     * Dequeue the oldest batch of locations from the queue.
     * Returns locations ordered by createdAt ascending (FIFO).
     *
     * @param size Maximum number of locations to retrieve
     * @return Future containing a list of the oldest queued locations
     */
    fun dequeueBatch(size: Int): Future<List<QueuedLocation>> {
        return executor.submit(Callable {
            dao.getOldestBatch(size)
        })
    }

    /**
     * Remove a batch of locations from the queue by their IDs.
     * Typically called after a successful Firebase sync.
     *
     * @param ids List of location IDs to remove
     */
    fun removeBatch(ids: List<String>): Future<Unit> {
        return executor.submit(Callable {
            dao.deleteByIds(ids)
        })
    }

    /**
     * Get the current number of locations waiting in the queue.
     *
     * @return Future containing the count of queued locations
     */
    fun count(): Future<Int> {
        return executor.submit(Callable {
            dao.getCount()
        })
    }

    /**
     * Shutdown the executor service. Call this when the engine is no longer needed.
     */
    fun shutdown() {
        executor.shutdown()
    }
}
