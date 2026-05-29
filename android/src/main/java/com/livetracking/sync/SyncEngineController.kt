package com.livetracking.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SyncEngineController replaces the monolithic FirebaseSyncEngine.
 *
 * It manages a list of TargetHandler instances — one per configured sync target.
 * When a location arrives, it fans out to all handlers in parallel.
 * Each handler independently manages batching, retry, and offline queue logic.
 *
 * Responsibilities:
 * - Parse targets JSON and instantiate one TargetHandler per target
 * - Dispatch location data to all handlers concurrently
 * - Flush all partial batches on stop
 * - Report per-target queued location counts
 * - Clean up all handlers on shutdown
 *
 * @param targetsJson JSON array string of sync target configurations
 * @param firebaseService The Firebase service type ("RTDB" or "Firestore")
 * @param networkChecker Function that returns current online status
 * @param offlineQueueProvider Optional provider for offline persistence
 * @param eventListener Listener for error/warning events
 * @throws IllegalArgumentException if targetsJson fails to parse or contains invalid targets
 */
class SyncEngineController(
    targetsJson: String,
    private val firebaseService: String,
    private val networkChecker: () -> Boolean,
    private val offlineQueueProvider: OfflineQueueProvider? = null,
    private val eventListener: TargetEventListener? = null
) {

    private val handlers: List<TargetHandler>
    private val dispatchExecutor: ExecutorService = Executors.newCachedThreadPool()

    init {
        handlers = parseAndCreateHandlers(targetsJson)
    }

    /**
     * Dispatch a location data point to all configured sync targets in parallel.
     *
     * Each target handler receives the location independently and decides
     * whether to accumulate (batch), write immediately, or queue offline.
     * A failure in one target does not block others.
     *
     * @param location The location data point to dispatch
     */
    fun dispatchLocation(location: LocationDataPoint) {
        for (handler in handlers) {
            dispatchExecutor.execute {
                handler.dispatch(location)
            }
        }
    }

    /**
     * Flush all partially-filled batches for all targets.
     *
     * Called during stop() to ensure no accumulated data is lost.
     * Blocks until all handlers have completed their flush.
     */
    fun flushAll() {
        for (handler in handlers) {
            handler.flush()
        }
    }

    /**
     * Get the number of queued locations for each configured target.
     *
     * Returns a map of target path → queued count.
     * Targets with offlineQueue disabled report 0.
     *
     * @return Map of target path to queued location count
     */
    fun getQueuedCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (handler in handlers) {
            counts[handler.targetPath] = handler.getQueuedCount()
        }
        return counts
    }

    /**
     * Shutdown all handlers and release resources.
     *
     * Cancels any in-progress retries and batch timers.
     * After shutdown, no further dispatches will be processed.
     */
    fun shutdown() {
        for (handler in handlers) {
            handler.shutdown()
        }
        dispatchExecutor.shutdown()
    }

    /**
     * Flush offline queues for all targets that have offlineQueue enabled.
     * Called when network connectivity is restored.
     */
    fun flushOfflineQueues() {
        for (handler in handlers) {
            handler.flushOfflineQueue()
        }
    }

    /**
     * Get the list of configured target paths.
     *
     * @return List of target path strings
     */
    fun getTargetPaths(): List<String> {
        return handlers.map { it.targetPath }
    }

    // --- Private implementation ---

    /**
     * Parse the targets JSON array and create one TargetHandler per target.
     *
     * @param targetsJson JSON array string of sync target configurations
     * @return List of TargetHandler instances
     * @throws IllegalArgumentException if JSON is invalid or targets are missing required fields
     */
    private fun parseAndCreateHandlers(targetsJson: String): List<TargetHandler> {
        val configs = parseTargetsJson(targetsJson)
        return configs.map { config ->
            val queueProvider = if (config.offlineQueue) offlineQueueProvider else null
            TargetHandler(
                config = config,
                firebaseService = firebaseService,
                networkChecker = networkChecker,
                offlineQueueProvider = queueProvider,
                eventListener = eventListener
            )
        }
    }

    /**
     * Parse a JSON array string into a list of SyncTargetConfig objects.
     *
     * Each target must have:
     * - "path" (string, required)
     * - "method" (string, required: "set", "push", or "update")
     * - "batchSize" (int, optional, defaults to 1)
     * - "offlineQueue" (boolean, optional, defaults to false)
     *
     * @param json JSON array string
     * @return List of SyncTargetConfig
     * @throws IllegalArgumentException if parsing fails or required fields are missing
     */
    private fun parseTargetsJson(json: String): List<SyncTargetConfig> {
        val jsonArray: JSONArray
        try {
            jsonArray = JSONArray(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException(
                "Failed to parse targets JSON: ${e.message}"
            )
        }

        if (jsonArray.length() == 0) {
            throw IllegalArgumentException(
                "Targets array is empty — at least one sync target is required"
            )
        }

        val configs = mutableListOf<SyncTargetConfig>()

        for (i in 0 until jsonArray.length()) {
            val obj: JSONObject
            try {
                obj = jsonArray.getJSONObject(i)
            } catch (e: JSONException) {
                throw IllegalArgumentException(
                    "Target at index $i is not a valid JSON object: ${e.message}"
                )
            }

            val path = obj.optString("path", "")
            if (path.isEmpty()) {
                throw IllegalArgumentException(
                    "Target at index $i is missing required field 'path'"
                )
            }

            val method = obj.optString("method", "")
            if (method.isEmpty()) {
                throw IllegalArgumentException(
                    "Target at index $i is missing required field 'method'"
                )
            }
            if (method !in listOf("set", "push", "update")) {
                throw IllegalArgumentException(
                    "Target at index $i has invalid method '$method'. Must be 'set', 'push', or 'update'"
                )
            }

            val batchSize = obj.optInt("batchSize", 1)
            val offlineQueue = obj.optBoolean("offlineQueue", false)

            configs.add(
                SyncTargetConfig(
                    path = path,
                    method = method,
                    batchSize = batchSize,
                    offlineQueue = offlineQueue
                )
            )
        }

        return configs
    }
}
