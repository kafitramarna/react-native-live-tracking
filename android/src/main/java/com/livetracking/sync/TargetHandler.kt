package com.livetracking.sync

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.livetracking.queue.QueuedLocation
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.random.Random

/**
 * Interface for per-target offline queue operations.
 * Implemented by QueueEngine (task 5.6) to provide target-scoped persistence.
 */
interface OfflineQueueProvider {
    fun enqueueForTarget(
        targetPath: String,
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        accuracy: Float,
        speed: Float?,
        altitude: Double?,
        bearing: Float?
    ): Future<Unit>

    fun dequeueBatchForTarget(targetPath: String, size: Int): Future<List<QueuedLocation>>
    fun countForTarget(targetPath: String): Future<Int>
    fun evictOldestForTarget(targetPath: String): Future<Unit>
    fun removeBatchForTarget(ids: List<String>): Future<Unit>
}

/**
 * Callback interface for target write operations.
 */
interface TargetWriteCallback {
    fun onSuccess()
    fun onError(errorCode: String, message: String)
}

/**
 * Listener interface for error/warning events emitted by a TargetHandler.
 */
interface TargetEventListener {
    /**
     * Called when a write operation fails after all retries are exhausted.
     */
    fun onWriteError(targetPath: String, method: String, errorCode: String, message: String)

    /**
     * Called when the offline queue overflows (10,000 cap reached).
     */
    fun onQueueOverflow(targetPath: String)
}

/**
 * TargetHandler manages a single sync target's write lifecycle.
 *
 * Responsibilities:
 * - Batch accumulation: buffers location data points until batchSize is reached
 * - 30-second batch timeout: flushes partial batches after 30s of inactivity
 * - Write execution: dispatches writes to Firebase using the configured method (set/push/update)
 * - Retry cancellation: for set/update targets, cancels in-progress retries when newer data arrives
 * - Offline queue delegation: enqueues data when offline and offlineQueue is enabled, discards otherwise
 *
 * Each TargetHandler operates independently and does not block other targets.
 *
 * @param config The sync target configuration
 * @param firebaseService The Firebase service type ("RTDB" or "Firestore")
 * @param networkChecker Function that returns current online status
 * @param offlineQueueProvider Optional provider for offline persistence (null if offlineQueue is disabled)
 * @param eventListener Listener for error/warning events
 */
class TargetHandler(
    private val config: SyncTargetConfig,
    private val firebaseService: String,
    private val networkChecker: () -> Boolean,
    private val offlineQueueProvider: OfflineQueueProvider? = null,
    private val eventListener: TargetEventListener? = null
) {

    /** The Firebase path this handler writes to. */
    val targetPath: String get() = config.path

    companion object {
        private const val BATCH_TIMEOUT_MS = 30_000L
        private const val BASE_DELAY_MS = 1000L
        private const val MULTIPLIER = 2.0
        private const val JITTER_MS = 200L
        private const val MAX_QUEUE_SIZE = 10_000
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val batch: MutableList<LocationDataPoint> = mutableListOf()
    private var batchTimer: Timer? = null
    private val retryGeneration = AtomicInteger(0)
    private val isRetrying = AtomicBoolean(false)
    private val isShutdown = AtomicBoolean(false)

    private val maxRetries: Int
        get() = if (config.method == "push") 5 else 3

    /**
     * Dispatch a new location data point to this target.
     *
     * If the device is offline and offlineQueue is enabled, the point is queued.
     * If the device is offline and offlineQueue is disabled, the point is discarded.
     * If online, the point is accumulated in the batch buffer.
     * When the batch reaches batchSize, it is flushed (written to Firebase).
     * For set/update targets, any in-progress retry is cancelled when new data arrives.
     *
     * @param location The location data point to dispatch
     */
    fun dispatch(location: LocationDataPoint) {
        if (isShutdown.get()) return

        executor.execute {
            // For set/update targets, cancel any in-progress retry since newer data supersedes
            if (config.method == "set" || config.method == "update") {
                if (isRetrying.get()) {
                    retryGeneration.incrementAndGet()
                    isRetrying.set(false)
                }
            }

            // Check if device is offline
            if (!networkChecker()) {
                if (config.offlineQueue && offlineQueueProvider != null) {
                    enqueueOffline(location)
                }
                // If offlineQueue is disabled, discard the data point
                return@execute
            }

            // Accumulate in batch
            batch.add(location)
            resetBatchTimer()

            // Check if batch is full
            if (batch.size >= config.batchSize) {
                flushBatch()
            }
        }
    }

    /**
     * Flush any partially-filled batch immediately.
     * Called during stop() to ensure no data is lost.
     * This method blocks until the flush is complete.
     */
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
            // Ignore interruption during shutdown
        }
    }

    /**
     * Shutdown this handler, cancelling timers and releasing resources.
     */
    fun shutdown() {
        isShutdown.set(true)
        cancelBatchTimer()
        retryGeneration.incrementAndGet() // Cancel any in-progress retries
        executor.shutdown()
    }

    /**
     * Get the number of queued locations for this target's offline queue.
     *
     * @return The count of queued locations, or 0 if offlineQueue is disabled
     */
    fun getQueuedCount(): Int {
        if (!config.offlineQueue || offlineQueueProvider == null) return 0
        return try {
            offlineQueueProvider.countForTarget(config.path).get()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Flush the offline queue for this target.
     * Called when network connectivity is restored.
     * Flushes in chronological order, respecting batchSize for batched writes.
     */
    fun flushOfflineQueue() {
        if (isShutdown.get()) return
        if (!config.offlineQueue || offlineQueueProvider == null) return

        executor.execute {
            doFlushOfflineQueue()
        }
    }

    // --- Private implementation ---

    private fun flushBatch() {
        cancelBatchTimer()
        if (batch.isEmpty()) return

        val pointsToWrite = ArrayList(batch)
        batch.clear()

        executeWrite(pointsToWrite)
    }

    private fun executeWrite(points: List<LocationDataPoint>) {
        val generation = retryGeneration.get()

        when (config.method) {
            "set" -> executeSet(points.last(), generation, points)
            "push" -> executePush(points, generation)
            "update" -> executeUpdate(points.last(), generation, points)
        }
    }

    private fun executeSet(point: LocationDataPoint, generation: Int, allPoints: List<LocationDataPoint>) {
        val data = locationToMap(point)
        executeWithRetry(generation, allPoints) { callback ->
            writeSet(data, callback)
        }
    }

    private fun executePush(points: List<LocationDataPoint>, generation: Int) {
        executeWithRetry(generation, points) { callback ->
            writePush(points, callback)
        }
    }

    private fun executeUpdate(point: LocationDataPoint, generation: Int, allPoints: List<LocationDataPoint>) {
        val data = locationToMap(point)
        executeWithRetry(generation, allPoints) { callback ->
            writeUpdate(data, callback)
        }
    }

    private fun writeSet(data: Map<String, Any?>, callback: TargetWriteCallback) {
        when (firebaseService) {
            "RTDB" -> {
                val ref = FirebaseDatabase.getInstance().getReference(config.path)
                ref.setValue(data)
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "RTDB set failed")
                    }
            }
            "Firestore" -> {
                val doc = FirebaseFirestore.getInstance().document(config.path)
                doc.set(data)
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "Firestore set failed")
                    }
            }
        }
    }

    private fun writePush(points: List<LocationDataPoint>, callback: TargetWriteCallback) {
        when (firebaseService) {
            "RTDB" -> {
                val ref = FirebaseDatabase.getInstance().getReference(config.path)
                val updates = hashMapOf<String, Any>()
                for (point in points) {
                    val key = ref.push().key ?: continue
                    val map = locationToMap(point)
                    @Suppress("UNCHECKED_CAST")
                    updates[key] = map as Any
                }
                if (updates.isEmpty()) {
                    callback.onSuccess()
                    return
                }
                ref.updateChildren(updates)
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "RTDB push failed")
                    }
            }
            "Firestore" -> {
                val firestore = FirebaseFirestore.getInstance()
                val collection = firestore.collection(config.path)
                val batch = firestore.batch()
                for (point in points) {
                    val docRef = collection.document()
                    batch.set(docRef, locationToMap(point))
                }
                batch.commit()
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "Firestore push failed")
                    }
            }
        }
    }

    private fun writeUpdate(data: Map<String, Any?>, callback: TargetWriteCallback) {
        when (firebaseService) {
            "RTDB" -> {
                val ref = FirebaseDatabase.getInstance().getReference(config.path)
                @Suppress("UNCHECKED_CAST")
                ref.updateChildren(data as Map<String, Any>)
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "RTDB update failed")
                    }
            }
            "Firestore" -> {
                val doc = FirebaseFirestore.getInstance().document(config.path)
                @Suppress("UNCHECKED_CAST")
                doc.update(data as Map<String, Any>)
                    .addOnSuccessListener { callback.onSuccess() }
                    .addOnFailureListener { e ->
                        callback.onError(classifyError(e), e.message ?: "Firestore update failed")
                    }
            }
        }
    }

    /**
     * Execute a write operation with exponential backoff retry.
     *
     * For set/update targets, retries are cancelled if a newer generation arrives
     * (i.e., new data supersedes the in-progress write).
     *
     * After all retries are exhausted, if offlineQueue is enabled, the data points
     * are queued for later flush upon connectivity restoration (Requirement 7.5).
     *
     * @param generation The retry generation at the time of dispatch
     * @param points The data points being written (used for offline queuing on failure)
     * @param operation The write operation to execute
     */
    private fun executeWithRetry(generation: Int, points: List<LocationDataPoint>, operation: (TargetWriteCallback) -> Unit) {
        isRetrying.set(true)
        var attempt = 0

        fun tryOperation() {
            // Check if this retry has been superseded by newer data
            if (retryGeneration.get() != generation) {
                isRetrying.set(false)
                return
            }

            attempt++
            operation(object : TargetWriteCallback {
                override fun onSuccess() {
                    isRetrying.set(false)
                }

                override fun onError(errorCode: String, message: String) {
                    // Don't retry non-transient errors
                    if (isNonTransientError(errorCode)) {
                        isRetrying.set(false)
                        eventListener?.onWriteError(config.path, config.method, errorCode, message)
                        return
                    }

                    // Check if superseded before retrying
                    if (retryGeneration.get() != generation) {
                        isRetrying.set(false)
                        return
                    }

                    if (attempt >= maxRetries) {
                        isRetrying.set(false)
                        eventListener?.onWriteError(
                            config.path,
                            config.method,
                            errorCode,
                            "Failed after $attempt attempts: $message"
                        )
                        // If offlineQueue is enabled, queue the data for later flush
                        if (config.offlineQueue && offlineQueueProvider != null) {
                            for (point in points) {
                                enqueueOffline(point)
                            }
                        }
                        return
                    }

                    // Exponential backoff with jitter
                    val delay = calculateBackoffDelay(attempt)
                    try {
                        Thread.sleep(delay)
                    } catch (e: InterruptedException) {
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
                }
            })
        }

        tryOperation()
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * Formula: baseDelay × 2^(attempt-1) ± random jitter (±200ms)
     * Result is never negative.
     *
     * @param attempt Current attempt number (1-indexed)
     * @return Delay in milliseconds
     */
    internal fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = (BASE_DELAY_MS * MULTIPLIER.pow((attempt - 1).toDouble())).toLong()
        val jitter = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return maxOf(0L, exponentialDelay + jitter)
    }

    private fun enqueueOffline(location: LocationDataPoint) {
        if (offlineQueueProvider == null) return

        // Check queue size cap
        try {
            val currentCount = offlineQueueProvider.countForTarget(config.path).get()
            if (currentCount >= MAX_QUEUE_SIZE) {
                // Evict oldest to make room
                offlineQueueProvider.evictOldestForTarget(config.path).get()
                eventListener?.onQueueOverflow(config.path)
            }
        } catch (e: Exception) {
            // If we can't check count, try to enqueue anyway
        }

        offlineQueueProvider.enqueueForTarget(
            targetPath = config.path,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.timestamp,
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude,
            bearing = location.bearing
        )
    }

    private fun doFlushOfflineQueue() {
        if (offlineQueueProvider == null) return

        while (!isShutdown.get()) {
            val batchSize = config.batchSize
            val queuedBatch = try {
                offlineQueueProvider.dequeueBatchForTarget(config.path, batchSize).get()
            } catch (e: Exception) {
                break
            }

            if (queuedBatch.isEmpty()) break

            // Convert QueuedLocations to LocationDataPoints
            val points = queuedBatch.map { queued ->
                LocationDataPoint(
                    latitude = queued.latitude,
                    longitude = queued.longitude,
                    timestamp = queued.timestamp,
                    accuracy = queued.accuracy,
                    speed = queued.speed,
                    altitude = queued.altitude,
                    bearing = queued.bearing
                )
            }

            // Write synchronously during flush
            var writeSuccess = false
            val generation = retryGeneration.get()

            val latch = java.util.concurrent.CountDownLatch(1)
            var writeError: String? = null

            val callback = object : TargetWriteCallback {
                override fun onSuccess() {
                    writeSuccess = true
                    latch.countDown()
                }

                override fun onError(errorCode: String, message: String) {
                    writeError = message
                    latch.countDown()
                }
            }

            // Execute the write based on method
            when (config.method) {
                "set" -> writeSet(locationToMap(points.last()), callback)
                "push" -> writePush(points, callback)
                "update" -> writeUpdate(locationToMap(points.last()), callback)
            }

            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }

            if (writeSuccess) {
                // Remove successfully written points from queue
                val ids = queuedBatch.map { it.id }
                try {
                    offlineQueueProvider.removeBatchForTarget(ids).get()
                } catch (e: Exception) {
                    // Log but continue
                }
            } else {
                // Stop flushing on failure - retain data for retry later
                break
            }
        }
    }

    private fun resetBatchTimer() {
        cancelBatchTimer()
        if (config.batchSize <= 1) return // No timer needed for immediate writes

        batchTimer = Timer("BatchTimer-${config.path}", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (!isShutdown.get()) {
                        executor.execute {
                            if (batch.isNotEmpty()) {
                                flushBatch()
                            }
                        }
                    }
                }
            }, BATCH_TIMEOUT_MS)
        }
    }

    private fun cancelBatchTimer() {
        batchTimer?.cancel()
        batchTimer = null
    }

    /**
     * Convert a LocationDataPoint to a Firebase-compatible map.
     * Null optional fields are included as null (not placeholder values).
     */
    private fun locationToMap(point: LocationDataPoint): Map<String, Any?> {
        val map = hashMapOf<String, Any?>(
            "latitude" to point.latitude,
            "longitude" to point.longitude,
            "timestamp" to point.timestamp,
            "accuracy" to point.accuracy.toDouble()
        )
        // Include optional fields as null when unavailable (never use placeholders)
        if (point.speed != null) {
            map["speed"] = point.speed.toDouble()
        }
        if (point.altitude != null) {
            map["altitude"] = point.altitude
        }
        if (point.bearing != null) {
            map["bearing"] = point.bearing.toDouble()
        }
        return map
    }

    /**
     * Classify a Firebase exception as transient or non-transient.
     * Non-transient errors (permission denied, auth failures) should not be retried.
     */
    private fun classifyError(exception: Exception): String {
        val message = exception.message?.lowercase() ?: ""
        return when {
            message.contains("permission") || message.contains("denied") -> "PERMISSION_DENIED"
            message.contains("unauthenticated") || message.contains("auth") -> "PERMISSION_DENIED"
            else -> "FIREBASE_WRITE_FAILED"
        }
    }

    /**
     * Check if an error code represents a non-transient error that should not be retried.
     */
    private fun isNonTransientError(errorCode: String): Boolean {
        return errorCode == "PERMISSION_DENIED"
    }
}
