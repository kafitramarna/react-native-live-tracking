package com.livetracking.sync

/**
 * Configuration for a single sync target.
 *
 * Each sync target defines a Firebase path, write method, optional batch size,
 * and optional offline queue preference. The native SyncEngineController
 * instantiates one TargetHandler per SyncTargetConfig.
 *
 * @param path Firebase path to write location data to
 * @param method Write method: "set" (overwrite), "push" (append), or "update" (merge)
 * @param batchSize Number of location points to accumulate before writing. Default: 1 (immediate write)
 * @param offlineQueue Whether to persist data locally when offline. Default: false
 */
data class SyncTargetConfig(
    val path: String,
    val method: String,
    val batchSize: Int = 1,
    val offlineQueue: Boolean = false
)
