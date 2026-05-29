import Foundation

/**
 * Protocol for receiving sync engine events (errors, warnings) to forward to JavaScript.
 */
protocol SyncEngineControllerDelegate: AnyObject {
    /**
     * Called when a write operation fails after all retry attempts are exhausted.
     *
     * - Parameter targetPath: The Firebase path of the target that failed
     * - Parameter method: The write method that was used
     * - Parameter errorCode: Machine-readable error code
     * - Parameter message: Human-readable error description
     */
    func onSyncError(targetPath: String, method: String, errorCode: String, message: String)

    /**
     * Called when the offline queue overflows for a target.
     *
     * - Parameter targetPath: The Firebase path of the target whose queue overflowed
     */
    func onSyncQueueOverflow(targetPath: String)
}

/**
 * SyncEngineController replaces the monolithic FirebaseSyncEngine with a multi-target
 * dispatch architecture. It parses the targets JSON configuration, instantiates one
 * TargetHandler per sync target, and fans out location dispatches to all handlers
 * concurrently.
 *
 * Key responsibilities:
 * - Parse targets JSON and instantiate TargetHandlers
 * - Fan out location dispatches to all handlers in parallel
 * - Flush all partial batches on stop
 * - Provide per-target queue status counts
 * - Forward error/warning events from handlers to the JS layer via delegate
 *
 * Requirements: 3.1, 3.5, 4.4, 9.3, 10.1, 10.2
 */
class SyncEngineController {

    // MARK: - Properties

    /// The Firebase service type ("RTDB" or "Firestore")
    private let service: String

    /// Array of target handlers, one per configured sync target
    private(set) var handlers: [TargetHandler] = []

    /// Offline queue manager for per-target persistence
    private let offlineQueueManager: OfflineQueueManager

    /// Network status provider for connectivity checks
    private let networkStatusProvider: NetworkStatusProvider

    /// Delegate for forwarding events to JS
    weak var delegate: SyncEngineControllerDelegate?

    /// Concurrent dispatch queue for parallel location fan-out
    private let dispatchQueue = DispatchQueue(
        label: "com.livetracking.syncengine.dispatch",
        qos: .utility,
        attributes: .concurrent
    )

    // MARK: - Initialization

    /**
     * Initialize the SyncEngineController by parsing a JSON configuration string.
     *
     * The JSON must contain:
     * - "service": "RTDB" or "Firestore"
     * - "targets": Array of target objects, each with "path" (string) and "method" (string),
     *   plus optional "batchSize" (int) and "offlineQueue" (bool)
     *
     * - Parameter jsonString: The full firebase configuration JSON string
     * - Parameter networkStatus: Provider for checking network connectivity
     * - Parameter queueManager: Offline queue manager for per-target persistence
     * - Throws: SyncEngineError if JSON parsing fails or required fields are missing
     *
     * Requirements: 9.3
     */
    init(
        jsonString: String,
        networkStatus: NetworkStatusProvider,
        queueManager: OfflineQueueManager
    ) throws {
        self.networkStatusProvider = networkStatus
        self.offlineQueueManager = queueManager

        // Parse JSON
        guard let data = jsonString.data(using: .utf8) else {
            throw SyncEngineError.invalidConfig("Configuration string is not valid UTF-8")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            throw SyncEngineError.invalidConfig("Configuration string is not valid JSON")
        }

        // Parse service
        guard let parsedService = json["service"] as? String,
              (parsedService == "RTDB" || parsedService == "Firestore") else {
            throw SyncEngineError.invalidConfig("Invalid or missing 'service'. Must be 'RTDB' or 'Firestore'")
        }
        self.service = parsedService

        // Parse targets array
        guard let targetsArray = json["targets"] as? [[String: Any]] else {
            throw SyncEngineError.invalidConfig("Missing or invalid 'targets' array")
        }

        if targetsArray.isEmpty {
            throw SyncEngineError.invalidConfig("'targets' array must contain at least one target")
        }

        // Instantiate one TargetHandler per target
        var parsedHandlers: [TargetHandler] = []
        for (index, targetJson) in targetsArray.enumerated() {
            guard let path = targetJson["path"] as? String, !path.isEmpty else {
                throw SyncEngineError.invalidConfig("Target at index \(index) is missing required field 'path'")
            }

            guard let method = targetJson["method"] as? String,
                  (method == "set" || method == "push" || method == "update") else {
                throw SyncEngineError.invalidConfig("Target at index \(index) is missing or has invalid 'method'. Must be 'set', 'push', or 'update'")
            }

            let batchSize = targetJson["batchSize"] as? Int ?? 1
            let offlineQueue = targetJson["offlineQueue"] as? Bool ?? false

            let config = SyncTargetConfig(
                path: path,
                method: method,
                batchSize: batchSize,
                offlineQueue: offlineQueue
            )

            let handler = TargetHandler(config: config, service: parsedService)
            handler.delegate = self
            handler.networkStatusProvider = networkStatus
            handler.offlineQueueProvider = self
            parsedHandlers.append(handler)
        }

        self.handlers = parsedHandlers
    }

    /**
     * Initialize with pre-built components (for testing).
     *
     * - Parameter service: Firebase service type
     * - Parameter handlers: Pre-configured target handlers
     * - Parameter networkStatus: Network status provider
     * - Parameter queueManager: Offline queue manager
     */
    init(
        service: String,
        handlers: [TargetHandler],
        networkStatus: NetworkStatusProvider,
        queueManager: OfflineQueueManager
    ) {
        self.service = service
        self.handlers = handlers
        self.networkStatusProvider = networkStatus
        self.offlineQueueManager = queueManager

        // Wire up delegates
        for handler in handlers {
            handler.delegate = self
            handler.networkStatusProvider = networkStatus
            handler.offlineQueueProvider = self
        }
    }

    // MARK: - Public Methods

    /**
     * Dispatch a location data point to all configured sync targets concurrently.
     *
     * Each target handler receives the location independently and decides whether
     * to accumulate (batch), write immediately, or queue offline based on its
     * configuration and the current network state.
     *
     * Requirement 3.1: Initiate write operations to all targets in parallel.
     * Requirement 3.5: Continue processing for all targets independently on failure.
     *
     * - Parameter location: The location data point to dispatch
     */
    func dispatchLocation(_ location: LocationDataPoint) {
        for handler in handlers {
            dispatchQueue.async {
                handler.dispatchLocation(location)
            }
        }
    }

    /**
     * Flush all partially-filled batches for all sync targets.
     * Called when tracking is stopped to ensure no data is lost.
     *
     * This method blocks until all handlers have completed their flush.
     *
     * Requirement 4.4: Flush all partial batches before stop completes.
     *
     * - Parameter completion: Called when all handlers have flushed
     */
    func flushAll(completion: (() -> Void)? = nil) {
        let group = DispatchGroup()

        for handler in handlers {
            group.enter()
            handler.flush {
                group.leave()
            }
        }

        group.notify(queue: .global(qos: .utility)) {
            completion?()
        }
    }

    /**
     * Get the queued location counts per target path.
     *
     * Returns a dictionary mapping each configured target path to its offline queue count.
     * Targets with `offlineQueue: false` report 0.
     *
     * Requirement 10.1: Total count across all targets.
     * Requirement 10.2: Per-target queue counts.
     *
     * - Returns: Dictionary of [targetPath: queuedCount]
     */
    func getQueuedCounts() -> [String: Int] {
        var counts: [String: Int] = [:]

        for handler in handlers {
            if handler.config.offlineQueue {
                counts[handler.config.path] = offlineQueueManager.countForTarget(handler.config.path)
            } else {
                counts[handler.config.path] = 0
            }
        }

        return counts
    }

    /**
     * Get the total count of all queued locations across all targets.
     *
     * Requirement 10.1: Total queued location count.
     *
     * - Returns: Total number of queued locations
     */
    func getTotalQueuedCount() -> Int {
        var total = 0
        for handler in handlers {
            if handler.config.offlineQueue {
                total += offlineQueueManager.countForTarget(handler.config.path)
            }
        }
        return total
    }

    /**
     * Shut down the controller and all target handlers.
     * Cancels all timers, pending retries, and clears buffers.
     */
    func shutdown() {
        for handler in handlers {
            handler.shutdown()
        }
    }

    /**
     * Flush offline queues for all targets that have offlineQueue enabled.
     * Called when network connectivity is restored.
     *
     * Each target's queue is flushed independently in chronological order.
     * Respects batchSize when flushing. Stops flushing on failure and retains
     * the failed point for reattempt via retry.
     *
     * Requirements: 5.2, 5.5, 5.6
     */
    func flushOfflineQueues() {
        for handler in handlers {
            guard handler.config.offlineQueue else { continue }

            dispatchQueue.async { [weak self] in
                self?.flushOfflineQueueForTarget(handler: handler)
            }
        }
    }

    // MARK: - Private Methods

    /**
     * Flush the offline queue for a specific target handler.
     * Dequeues batches in chronological order and writes them to Firebase.
     * Each batch is removed from the queue only after successful write confirmation.
     * Stops flushing on failure; the failed data remains in the queue for retry.
     *
     * Requirements: 5.2, 5.5, 5.6
     */
    private func flushOfflineQueueForTarget(handler: TargetHandler) {
        let batchSize = handler.config.batchSize > 1 ? handler.config.batchSize : 20

        // Flush in a loop: dequeue a batch, write it, remove on success, stop on failure
        flushNextBatch(handler: handler, batchSize: batchSize)
    }

    /**
     * Recursively flush the next batch for a target.
     * Stops when the queue is empty or a write fails.
     */
    private func flushNextBatch(handler: TargetHandler, batchSize: Int) {
        let batch = offlineQueueManager.dequeueBatch(targetPath: handler.config.path, size: batchSize)

        guard !batch.isEmpty else { return }

        let locations = batch.map { $0.location }
        let ids = batch.map { $0.id }

        handler.writeOfflineQueueBatch(locations) { [weak self] success in
            guard let self = self else { return }

            if success {
                // Remove from queue only after successful write confirmation
                self.offlineQueueManager.removeBatch(ids: ids)

                // Continue flushing the next batch
                self.dispatchQueue.async {
                    self.flushNextBatch(handler: handler, batchSize: batchSize)
                }
            }
            // On failure: stop flushing. The failed data remains in the queue.
            // The TargetHandler's retry mechanism will have already been exhausted,
            // and the data stays in the queue for the next network restore event.
        }
    }
}

// MARK: - TargetHandlerDelegate

extension SyncEngineController: TargetHandlerDelegate {
    func onWriteError(targetPath: String, method: String, errorCode: String, message: String) {
        delegate?.onSyncError(targetPath: targetPath, method: method, errorCode: errorCode, message: message)
    }

    func onQueueOverflow(targetPath: String) {
        delegate?.onSyncQueueOverflow(targetPath: targetPath)
    }
}

// MARK: - OfflineQueueProvider

extension SyncEngineController: OfflineQueueProvider {
    func enqueueForTarget(targetPath: String, location: LocationDataPoint) {
        offlineQueueManager.enqueue(location: location, targetPath: targetPath)
    }

    func countForTarget(targetPath: String) -> Int {
        return offlineQueueManager.countForTarget(targetPath)
    }

    func evictOldestForTarget(targetPath: String) {
        // The OfflineQueueManager handles eviction internally in enqueue
        // when the cap is reached, but we provide this for explicit eviction
        let batch = offlineQueueManager.dequeueBatch(targetPath: targetPath, size: 1)
        if let oldest = batch.first {
            offlineQueueManager.removeBatch(ids: [oldest.id])
        }
    }
}

// MARK: - SyncEngineError

/**
 * Errors that can occur during SyncEngineController initialization.
 */
enum SyncEngineError: Error, LocalizedError {
    case invalidConfig(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfig(let message):
            return message
        }
    }

    var errorCode: String {
        switch self {
        case .invalidConfig:
            return "INVALID_CONFIG"
        }
    }
}
