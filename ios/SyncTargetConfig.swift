import Foundation

/**
 * Configuration for a single sync target.
 *
 * Each sync target defines a Firebase path, write method, optional batch size,
 * and optional offline queue preference. The native SyncEngineController
 * instantiates one TargetHandler per SyncTargetConfig.
 *
 * - path: Firebase path to write location data to
 * - method: Write method — "set" (overwrite), "push" (append), or "update" (merge)
 * - batchSize: Number of location points to accumulate before writing. Default: 1 (immediate write)
 * - offlineQueue: Whether to persist data locally when offline. Default: false
 *
 * Requirements: 9.3
 */
struct SyncTargetConfig {
    /// Firebase path to write location data to
    let path: String

    /// Write method: "set" (overwrite), "push" (append), or "update" (merge)
    let method: String

    /// Number of location points to accumulate before writing. Default: 1 (immediate write)
    let batchSize: Int

    /// Whether to persist data locally when offline. Default: false
    let offlineQueue: Bool

    init(path: String, method: String, batchSize: Int = 1, offlineQueue: Bool = false) {
        self.path = path
        self.method = method
        self.batchSize = batchSize
        self.offlineQueue = offlineQueue
    }
}
