package com.livetracking.sync

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.livetracking.queue.QueuedLocation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.random.Random

/**
 * Callback interface for Firebase sync operations.
 */
interface SyncCallback {
    fun onSuccess()
    fun onError(errorCode: String, message: String)
}

/**
 * FirebaseSyncEngine handles writing location data to Firebase.
 *
 * Supports both Firebase Realtime Database (RTDB) and Cloud Firestore.
 * Implements retry with exponential backoff for resilient writes.
 *
 * - updateCurrentLocation: overwrites the current location at currentLocationPath
 * - pushHistoryBatch: appends a batch of locations to historyPath
 *
 * @param service Either "RTDB" or "Firestore"
 * @param currentLocationPath Firebase path for current location (overwrite)
 * @param historyPath Firebase path for history locations (append)
 */
class FirebaseSyncEngine(
    private val service: String,
    private val currentLocationPath: String?,
    private val historyPath: String?
) {

    companion object {
        private const val BASE_DELAY_MS = 1000L
        private const val MULTIPLIER = 2.0
        private const val JITTER_MS = 200L
        private const val MAX_RETRIES_CURRENT = 3
        private const val MAX_RETRIES_HISTORY = 5
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Update the current location at the configured currentLocationPath.
     * Uses set/update (overwrite) semantics.
     * Retries up to 3 times with exponential backoff on failure.
     *
     * @param latitude Location latitude
     * @param longitude Location longitude
     * @param timestamp Unix timestamp in milliseconds
     * @param accuracy Location accuracy in meters
     * @param speed Speed in m/s, nullable
     * @param callback SyncCallback for success/error notification
     */
    fun updateCurrentLocation(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        accuracy: Float,
        speed: Float?,
        callback: SyncCallback
    ) {
        if (currentLocationPath == null) {
            callback.onError("NO_PATH", "currentLocationPath is not configured")
            return
        }

        val data = hashMapOf<String, Any>(
            "latitude" to latitude,
            "longitude" to longitude,
            "timestamp" to timestamp,
            "accuracy" to accuracy.toDouble(),
            "updatedAt" to timestamp
        )
        speed?.let { data["speed"] = it.toDouble() }

        executor.execute {
            executeWithRetry(
                maxRetries = MAX_RETRIES_CURRENT,
                operation = { attemptCallback ->
                    writeCurrentLocation(data, attemptCallback)
                },
                callback = callback
            )
        }
    }

    /**
     * Push a batch of locations to the configured historyPath.
     * Uses push/append semantics (each location gets a unique key).
     * Retries up to 5 times with exponential backoff on failure.
     *
     * @param locations List of QueuedLocation to send
     * @param callback SyncCallback for success/error notification
     */
    fun pushHistoryBatch(locations: List<QueuedLocation>, callback: SyncCallback) {
        if (historyPath == null) {
            callback.onError("NO_PATH", "historyPath is not configured")
            return
        }

        if (locations.isEmpty()) {
            callback.onSuccess()
            return
        }

        executor.execute {
            executeWithRetry(
                maxRetries = MAX_RETRIES_HISTORY,
                operation = { attemptCallback ->
                    writeHistoryBatch(locations, attemptCallback)
                },
                callback = callback
            )
        }
    }

    /**
     * Shutdown the executor service. Call when the engine is no longer needed.
     */
    fun shutdown() {
        executor.shutdown()
    }

    // --- Private implementation ---

    private fun writeCurrentLocation(data: Map<String, Any>, callback: SyncCallback) {
        when (service) {
            "RTDB" -> writeCurrentLocationRTDB(data, callback)
            "Firestore" -> writeCurrentLocationFirestore(data, callback)
            else -> callback.onError("INVALID_SERVICE", "Unknown service: $service. Use 'RTDB' or 'Firestore'.")
        }
    }

    private fun writeCurrentLocationRTDB(data: Map<String, Any>, callback: SyncCallback) {
        val reference = FirebaseDatabase.getInstance().getReference(currentLocationPath!!)
        reference.setValue(data)
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e ->
                callback.onError("FIREBASE_WRITE_FAILED", e.message ?: "RTDB write failed")
            }
    }

    private fun writeCurrentLocationFirestore(data: Map<String, Any>, callback: SyncCallback) {
        val document = FirebaseFirestore.getInstance().document(currentLocationPath!!)
        document.set(data)
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e ->
                callback.onError("FIREBASE_WRITE_FAILED", e.message ?: "Firestore write failed")
            }
    }

    private fun writeHistoryBatch(locations: List<QueuedLocation>, callback: SyncCallback) {
        when (service) {
            "RTDB" -> writeHistoryBatchRTDB(locations, callback)
            "Firestore" -> writeHistoryBatchFirestore(locations, callback)
            else -> callback.onError("INVALID_SERVICE", "Unknown service: $service. Use 'RTDB' or 'Firestore'.")
        }
    }

    private fun writeHistoryBatchRTDB(locations: List<QueuedLocation>, callback: SyncCallback) {
        val reference = FirebaseDatabase.getInstance().getReference(historyPath!!)
        val updates = hashMapOf<String, Any>()

        for (location in locations) {
            val key = reference.push().key ?: continue
            updates[key] = locationToMap(location)
        }

        if (updates.isEmpty()) {
            callback.onSuccess()
            return
        }

        reference.updateChildren(updates)
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e ->
                callback.onError("FIREBASE_WRITE_FAILED", e.message ?: "RTDB batch write failed")
            }
    }

    private fun writeHistoryBatchFirestore(locations: List<QueuedLocation>, callback: SyncCallback) {
        val firestore = FirebaseFirestore.getInstance()
        val collection = firestore.collection(historyPath!!)
        val batch = firestore.batch()

        for (location in locations) {
            val docRef = collection.document()
            batch.set(docRef, locationToMap(location))
        }

        batch.commit()
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e ->
                callback.onError("FIREBASE_WRITE_FAILED", e.message ?: "Firestore batch write failed")
            }
    }

    private fun locationToMap(location: QueuedLocation): Map<String, Any?> {
        val map = hashMapOf<String, Any?>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "timestamp" to location.timestamp,
            "accuracy" to location.accuracy.toDouble(),
            "speed" to location.speed?.toDouble()
        )
        location.altitude?.let { map["altitude"] = it }
        location.bearing?.let { map["bearing"] = it.toDouble() }
        return map
    }

    /**
     * Execute an operation with exponential backoff retry.
     *
     * Retry delay formula: baseDelay * 2^(attempt-1) ± jitter
     * - Base delay: 1000ms
     * - Multiplier: 2x
     * - Jitter: ±200ms (random)
     *
     * @param maxRetries Maximum number of retry attempts
     * @param operation The Firebase write operation to execute
     * @param callback Final callback after all retries exhausted or success
     */
    private fun executeWithRetry(
        maxRetries: Int,
        operation: (SyncCallback) -> Unit,
        callback: SyncCallback
    ) {
        var attempt = 0

        fun tryOperation() {
            attempt++
            operation(object : SyncCallback {
                override fun onSuccess() {
                    callback.onSuccess()
                }

                override fun onError(errorCode: String, message: String) {
                    if (attempt >= maxRetries) {
                        callback.onError(errorCode, "Failed after $attempt attempts: $message")
                    } else {
                        val delay = calculateBackoffDelay(attempt)
                        try {
                            Thread.sleep(delay)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            callback.onError("INTERRUPTED", "Retry interrupted")
                            return
                        }
                        tryOperation()
                    }
                }
            })
        }

        tryOperation()
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * Formula: baseDelay * 2^(attempt-1) ± random jitter
     *
     * @param attempt Current attempt number (1-indexed)
     * @return Delay in milliseconds
     */
    internal fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = (BASE_DELAY_MS * MULTIPLIER.pow((attempt - 1).toDouble())).toLong()
        val jitter = Random.nextLong(-JITTER_MS, JITTER_MS + 1)
        return maxOf(0L, exponentialDelay + jitter)
    }
}
