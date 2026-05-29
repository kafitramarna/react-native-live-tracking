import Foundation
import FirebaseDatabase
import FirebaseFirestore

/**
 * Protocol for receiving target handler events (errors, warnings).
 */
protocol TargetHandlerDelegate: AnyObject {
    /**
     * Called when a write operation fails after all retry attempts are exhausted.
     *
     * - Parameter targetPath: The Firebase path of the target that failed
     * - Parameter method: The write method that was used
     * - Parameter errorCode: Machine-readable error code
     * - Parameter message: Human-readable error description
     */
    func onWriteError(targetPath: String, method: String, errorCode: String, message: String)

    /**
     * Called when the offline queue overflows for a target.
     *
     * - Parameter targetPath: The Firebase path of the target whose queue overflowed
     */
    func onQueueOverflow(targetPath: String)
}

/**
 * Protocol for checking network connectivity status.
 * Allows dependency injection for testing.
 */
protocol NetworkStatusProvider: AnyObject {
    func isOnline() -> Bool
}

/**
 * Protocol for offline queue operations.
 * Allows dependency injection for testing and per-target queue management.
 */
protocol OfflineQueueProvider: AnyObject {
    func enqueueForTarget(targetPath: String, location: LocationDataPoint)
    func countForTarget(targetPath: String) -> Int
    func evictOldestForTarget(targetPath: String)
}

/**
 * TargetHandler manages a single sync target's write operations.
 *
 * Responsibilities:
 * - Batch accumulation: Accumulates location data points in memory until batchSize is reached
 * - Batch timeout: Flushes partial batches after 30 seconds of inactivity
 * - Write dispatching: Executes Firebase writes using the configured method (set/push/update)
 * - Retry with exponential backoff: Retries transient failures with configurable max attempts
 * - Retry cancellation: For set/update targets, cancels in-progress retries when newer data arrives
 * - Offline queue delegation: Persists data via CoreData when offline and offlineQueue is enabled
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 4.5, 4.7, 5.3, 7.7
 */
class TargetHandler {

    // MARK: - Constants

    private static let baseDelayMs: Int = 1000
    private static let multiplier: Double = 2.0
    private static let jitterMs: Int = 200
    private static let maxRetriesSetUpdate: Int = 3
    private static let maxRetriesPush: Int = 5
    private static let batchTimeoutSeconds: TimeInterval = 30.0
    private static let maxQueueSize: Int = 10000

    // MARK: - Properties

    let config: SyncTargetConfig
    let service: String

    weak var delegate: TargetHandlerDelegate?
    weak var networkStatusProvider: NetworkStatusProvider?
    weak var offlineQueueProvider: OfflineQueueProvider?

    /// In-memory batch buffer
    private var buffer: [LocationDataPoint] = []

    /// Serial queue for thread-safe access to buffer and retry state
    private let handlerQueue: DispatchQueue

    /// Timer for 30-second batch timeout flush
    private var batchTimeoutTimer: DispatchSourceTimer?

    /// Flag indicating whether a retry is currently in progress
    private var retryInProgress: Bool = false

    /// Work item for the current retry delay (used for cancellation)
    private var currentRetryWorkItem: DispatchWorkItem?

    /// Generation counter for retry cancellation on set/update targets
    private var writeGeneration: UInt64 = 0

    // MARK: - Initialization

    /**
     * Initialize a TargetHandler for a specific sync target.
     *
     * - Parameter config: The sync target configuration
     * - Parameter service: Firebase service type ("RTDB" or "Firestore")
     */
    init(config: SyncTargetConfig, service: String) {
        self.config = config
        self.service = service
        self.handlerQueue = DispatchQueue(
            label: "com.livetracking.target.\(config.path)",
            qos: .utility
        )
    }

    // MARK: - Public Methods

    /**
     * Dispatch a new location data point to this target.
     *
     * If the target has batchSize > 1, the point is accumulated in the buffer.
     * If the buffer reaches batchSize, a write is triggered.
     * If batchSize is 1 (immediate), the point is written immediately.
     *
     * For set/update targets with an active retry, the retry is cancelled
     * and only the newest data is written.
     *
     * If the device is offline and offlineQueue is enabled, data is persisted locally.
     *
     * - Parameter location: The location data point to dispatch
     */
    func dispatchLocation(_ location: LocationDataPoint) {
        handlerQueue.async { [weak self] in
            guard let self = self else { return }
            self.handleDispatch(location)
        }
    }

    /**
     * Flush any partially-filled batch immediately.
     * Called when tracking is stopped to ensure no data is lost.
     *
     * - Parameter completion: Called when flush is complete
     */
    func flush(completion: (() -> Void)? = nil) {
        handlerQueue.async { [weak self] in
            guard let self = self else {
                completion?()
                return
            }
            self.cancelBatchTimeout()
            if !self.buffer.isEmpty {
                let batch = self.buffer
                self.buffer = []
                self.executeBatchWrite(batch: batch)
            }
            completion?()
        }
    }

    /**
     * Write a batch of locations from the offline queue with a completion callback.
     * Used during offline queue flush to confirm write success before removing entries.
     *
     * - Parameter batch: Array of location data points to write
     * - Parameter completion: Called with `true` on success, `false` on failure (after all retries exhausted)
     *
     * Requirements: 5.2, 5.5, 5.6
     */
    func writeOfflineQueueBatch(_ batch: [LocationDataPoint], completion: @escaping (Bool) -> Void) {
        handlerQueue.async { [weak self] in
            guard let self = self else {
                completion(false)
                return
            }

            let maxRetries = (self.config.method == "push")
                ? TargetHandler.maxRetriesPush
                : TargetHandler.maxRetriesSetUpdate

            self.executeOfflineQueueWriteWithRetry(
                batch: batch,
                attempt: 1,
                maxRetries: maxRetries,
                completion: completion
            )
        }
    }

    /**
     * Shut down the handler, cancelling timers and pending retries.
     */
    func shutdown() {
        handlerQueue.async { [weak self] in
            guard let self = self else { return }
            self.cancelBatchTimeout()
            self.cancelCurrentRetry()
            self.buffer = []
        }
    }

    // MARK: - Private Methods — Dispatch Logic

    private func handleDispatch(_ location: LocationDataPoint) {
        // Check if device is offline
        let isOnline = networkStatusProvider?.isOnline() ?? true

        if !isOnline {
            if config.offlineQueue {
                enqueueOffline(location: location)
            }
            // If offlineQueue is disabled, discard the data (Requirement 5.3)
            return
        }

        // For set/update targets: cancel in-progress retry when newer data arrives (Requirement 7.7)
        if (config.method == "set" || config.method == "update") && retryInProgress {
            cancelCurrentRetry()
        }

        // Batch accumulation logic
        if config.batchSize > 1 {
            buffer.append(location)
            resetBatchTimeout()

            if buffer.count >= config.batchSize {
                // Batch is full — flush it
                cancelBatchTimeout()
                let batch = buffer
                buffer = []
                executeBatchWrite(batch: batch)
            }
        } else {
            // Immediate write (batchSize == 1)
            executeBatchWrite(batch: [location])
        }
    }

    // MARK: - Private Methods — Batch Timeout

    /**
     * Reset the 30-second batch timeout timer.
     * If a timer is already running, it is cancelled and restarted.
     * When the timer fires, the partial batch is flushed.
     *
     * Requirement 4.7: Flush partial batch after 30 seconds of inactivity.
     */
    private func resetBatchTimeout() {
        cancelBatchTimeout()

        let timer = DispatchSource.makeTimerSource(queue: handlerQueue)
        timer.schedule(deadline: .now() + TargetHandler.batchTimeoutSeconds)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            if !self.buffer.isEmpty {
                let batch = self.buffer
                self.buffer = []
                self.executeBatchWrite(batch: batch)
            }
        }
        timer.resume()
        batchTimeoutTimer = timer
    }

    private func cancelBatchTimeout() {
        batchTimeoutTimer?.cancel()
        batchTimeoutTimer = nil
    }

    // MARK: - Private Methods — Write Execution

    /**
     * Execute a batch write to Firebase with retry logic.
     *
     * - Parameter batch: Array of location data points to write
     */
    private func executeBatchWrite(batch: [LocationDataPoint]) {
        let maxRetries = (config.method == "push")
            ? TargetHandler.maxRetriesPush
            : TargetHandler.maxRetriesSetUpdate

        // Increment generation for retry cancellation
        writeGeneration += 1
        let currentGeneration = writeGeneration

        retryInProgress = true
        executeWithRetry(
            batch: batch,
            attempt: 1,
            maxRetries: maxRetries,
            generation: currentGeneration
        )
    }

    /**
     * Execute a write operation with exponential backoff retry.
     *
     * Attempt 1 is the initial write. Attempts 2 through maxRetries+1 are retries.
     * So for maxRetries=3 (set/update), there are up to 4 total attempts (1 initial + 3 retries).
     * For maxRetries=5 (push), there are up to 6 total attempts (1 initial + 5 retries).
     *
     * Requirements: 7.1, 7.2, 7.3
     *
     * - Parameter batch: The batch of locations to write
     * - Parameter attempt: Current attempt number (1-indexed, where 1 = initial attempt)
     * - Parameter maxRetries: Maximum number of retry attempts after the initial attempt
     * - Parameter generation: Write generation for cancellation detection
     */
    private func executeWithRetry(
        batch: [LocationDataPoint],
        attempt: Int,
        maxRetries: Int,
        generation: UInt64
    ) {
        // Check if this retry has been superseded by newer data
        guard generation == writeGeneration else {
            retryInProgress = false
            return
        }

        performFirebaseWrite(batch: batch) { [weak self] success, errorCode, message in
            guard let self = self else { return }

            self.handlerQueue.async {
                // Check generation again after async callback
                guard generation == self.writeGeneration else {
                    self.retryInProgress = false
                    return
                }

                if success {
                    self.retryInProgress = false
                } else {
                    // Check for non-transient errors — do not retry (Requirement 7.6)
                    if self.isNonTransientError(errorCode: errorCode) {
                        self.retryInProgress = false
                        self.delegate?.onWriteError(
                            targetPath: self.config.path,
                            method: self.config.method,
                            errorCode: errorCode ?? "FIREBASE_WRITE_FAILED",
                            message: message ?? "Non-transient error"
                        )
                        // Queue if offlineQueue enabled
                        if self.config.offlineQueue {
                            for location in batch {
                                self.enqueueOffline(location: location)
                            }
                        }
                        return
                    }

                    // attempt counts total attempts (initial + retries).
                    // maxRetries represents the number of retries allowed after the initial attempt.
                    // So we exhaust when attempt > maxRetries (attempt 1 = initial, 2..maxRetries+1 = retries).
                    if attempt > maxRetries {
                        // All retries exhausted (Requirement 7.5)
                        self.retryInProgress = false
                        self.delegate?.onWriteError(
                            targetPath: self.config.path,
                            method: self.config.method,
                            errorCode: errorCode ?? "FIREBASE_WRITE_FAILED",
                            message: "Failed after \(maxRetries) retries: \(message ?? "Unknown error")"
                        )
                        // Queue if offlineQueue enabled (Requirement 4.6, 7.5)
                        if self.config.offlineQueue {
                            for location in batch {
                                self.enqueueOffline(location: location)
                            }
                        }
                    } else {
                        // Schedule retry with exponential backoff (Requirement 7.1)
                        let delay = self.calculateBackoffDelay(attempt: attempt)
                        let workItem = DispatchWorkItem { [weak self] in
                            guard let self = self else { return }
                            self.executeWithRetry(
                                batch: batch,
                                attempt: attempt + 1,
                                maxRetries: maxRetries,
                                generation: generation
                            )
                        }
                        self.currentRetryWorkItem = workItem
                        self.handlerQueue.asyncAfter(
                            deadline: .now() + .milliseconds(delay),
                            execute: workItem
                        )
                    }
                }
            }
        }
    }

    /**
     * Cancel the current in-progress retry.
     */
    private func cancelCurrentRetry() {
        currentRetryWorkItem?.cancel()
        currentRetryWorkItem = nil
        retryInProgress = false
        writeGeneration += 1
    }

    // MARK: - Private Methods — Offline Queue Write with Retry

    /**
     * Execute a write operation for offline queue data with exponential backoff retry.
     * Unlike the regular executeWithRetry, this calls a completion handler with the result
     * so the caller can decide whether to remove entries from the queue.
     *
     * - Parameter batch: The batch of locations to write
     * - Parameter attempt: Current attempt number (1-indexed)
     * - Parameter maxRetries: Maximum number of retry attempts after the initial attempt
     * - Parameter completion: Called with `true` on success, `false` on failure
     *
     * Requirements: 5.2, 5.6, 7.1
     */
    private func executeOfflineQueueWriteWithRetry(
        batch: [LocationDataPoint],
        attempt: Int,
        maxRetries: Int,
        completion: @escaping (Bool) -> Void
    ) {
        performFirebaseWrite(batch: batch) { [weak self] success, errorCode, message in
            guard let self = self else {
                completion(false)
                return
            }

            self.handlerQueue.async {
                if success {
                    completion(true)
                } else {
                    // Check for non-transient errors — do not retry
                    if self.isNonTransientError(errorCode: errorCode) {
                        self.delegate?.onWriteError(
                            targetPath: self.config.path,
                            method: self.config.method,
                            errorCode: errorCode ?? "FIREBASE_WRITE_FAILED",
                            message: message ?? "Non-transient error"
                        )
                        completion(false)
                        return
                    }

                    if attempt > maxRetries {
                        // All retries exhausted
                        self.delegate?.onWriteError(
                            targetPath: self.config.path,
                            method: self.config.method,
                            errorCode: errorCode ?? "FIREBASE_WRITE_FAILED",
                            message: "Offline queue flush failed after \(maxRetries) retries: \(message ?? "Unknown error")"
                        )
                        completion(false)
                    } else {
                        // Schedule retry with exponential backoff
                        let delay = self.calculateBackoffDelay(attempt: attempt)
                        self.handlerQueue.asyncAfter(deadline: .now() + .milliseconds(delay)) { [weak self] in
                            guard let self = self else {
                                completion(false)
                                return
                            }
                            self.executeOfflineQueueWriteWithRetry(
                                batch: batch,
                                attempt: attempt + 1,
                                maxRetries: maxRetries,
                                completion: completion
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: - Private Methods — Firebase Write Operations

    /**
     * Perform the actual Firebase write operation based on the configured method.
     *
     * - Parameter batch: Array of location data points to write
     * - Parameter completion: Callback with (success, errorCode, message)
     */
    private func performFirebaseWrite(
        batch: [LocationDataPoint],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        switch config.method {
        case "set":
            performSetWrite(batch: batch, completion: completion)
        case "push":
            performPushWrite(batch: batch, completion: completion)
        case "update":
            performUpdateWrite(batch: batch, completion: completion)
        default:
            completion(false, "INVALID_METHOD", "Unknown method: \(config.method)")
        }
    }

    /**
     * Perform a set (overwrite) write operation.
     * For set, only the latest data point is written (overwrites previous).
     *
     * Requirement 3.2: Overwrite data at the target path.
     */
    private func performSetWrite(
        batch: [LocationDataPoint],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        // For set method, use the latest point (overwrite semantics)
        guard let latestPoint = batch.last else {
            completion(true, nil, nil)
            return
        }

        let data = serializeLocationDataPoint(latestPoint)

        switch service {
        case "RTDB":
            let reference = Database.database().reference(withPath: config.path)
            reference.setValue(data) { error, _ in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        case "Firestore":
            let document = Firestore.firestore().document(config.path)
            document.setData(data) { error in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        default:
            completion(false, "INVALID_SERVICE", "Unknown service: \(service)")
        }
    }

    /**
     * Perform a push (append) write operation.
     * Each data point in the batch gets a unique auto-generated key.
     *
     * Requirement 3.3: Append location data as new child nodes/documents.
     */
    private func performPushWrite(
        batch: [LocationDataPoint],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        if batch.isEmpty {
            completion(true, nil, nil)
            return
        }

        switch service {
        case "RTDB":
            let reference = Database.database().reference(withPath: config.path)
            var updates: [String: Any] = [:]

            for point in batch {
                let key = reference.childByAutoId().key ?? UUID().uuidString
                updates[key] = serializeLocationDataPoint(point)
            }

            reference.updateChildValues(updates) { error, _ in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }

        case "Firestore":
            let firestore = Firestore.firestore()
            let collection = firestore.collection(config.path)
            let writeBatch = firestore.batch()

            for point in batch {
                let docRef = collection.document()
                writeBatch.setData(serializeLocationDataPoint(point), forDocument: docRef)
            }

            writeBatch.commit { error in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }

        default:
            completion(false, "INVALID_SERVICE", "Unknown service: \(service)")
        }
    }

    /**
     * Perform an update (merge) write operation.
     * Merges location data fields into existing data without removing existing fields.
     *
     * Requirement 3.4: Merge location data fields into existing data.
     */
    private func performUpdateWrite(
        batch: [LocationDataPoint],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        // For update method, use the latest point (merge semantics)
        guard let latestPoint = batch.last else {
            completion(true, nil, nil)
            return
        }

        let data = serializeLocationDataPoint(latestPoint)

        switch service {
        case "RTDB":
            let reference = Database.database().reference(withPath: config.path)
            reference.updateChildValues(data) { error, _ in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        case "Firestore":
            let document = Firestore.firestore().document(config.path)
            document.updateData(data) { error in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        default:
            completion(false, "INVALID_SERVICE", "Unknown service: \(service)")
        }
    }

    // MARK: - Private Methods — Offline Queue

    /**
     * Enqueue a location data point to the offline queue.
     * Enforces the 10,000 cap per target with oldest-eviction.
     *
     * Requirement 5.1: Persist up to 10,000 data points per target.
     * Requirement 5.7: Evict oldest when at capacity.
     */
    private func enqueueOffline(location: LocationDataPoint) {
        guard let queueProvider = offlineQueueProvider else { return }

        let currentCount = queueProvider.countForTarget(targetPath: config.path)
        if currentCount >= TargetHandler.maxQueueSize {
            queueProvider.evictOldestForTarget(targetPath: config.path)
            delegate?.onQueueOverflow(targetPath: config.path)
        }

        queueProvider.enqueueForTarget(targetPath: config.path, location: location)
    }

    // MARK: - Private Methods — Backoff Calculation

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * Formula: baseDelay × 2^(attempt-1) ± random jitter
     * Result is never negative.
     *
     * - Parameter attempt: Current attempt number (1-indexed)
     * - Returns: Delay in milliseconds
     */
    func calculateBackoffDelay(attempt: Int) -> Int {
        let exponentialDelay = Double(TargetHandler.baseDelayMs) * pow(TargetHandler.multiplier, Double(attempt - 1))
        let jitter = Int.random(in: -TargetHandler.jitterMs...TargetHandler.jitterMs)
        return max(0, Int(exponentialDelay) + jitter)
    }

    // MARK: - Private Methods — Helpers

    /**
     * Check if an error code represents a non-transient error that should not be retried.
     *
     * Requirement 7.6: Do not retry auth/permission errors.
     */
    private func isNonTransientError(errorCode: String?) -> Bool {
        guard let code = errorCode else { return false }
        return code == "PERMISSION_DENIED" || code == "UNAUTHENTICATED"
    }

    /**
     * Serialize a LocationDataPoint to a dictionary for Firebase write.
     * Omits optional fields that are nil (never writes placeholder values).
     *
     * Requirement 3.7: Omit or null unavailable sensor fields.
     */
    private func serializeLocationDataPoint(_ point: LocationDataPoint) -> [String: Any] {
        var data: [String: Any] = [
            "latitude": point.latitude,
            "longitude": point.longitude,
            "timestamp": point.timestamp,
            "accuracy": point.accuracy
        ]

        if let speed = point.speed {
            data["speed"] = speed
        }

        if let altitude = point.altitude {
            data["altitude"] = altitude
        }

        if let bearing = point.bearing {
            data["bearing"] = bearing
        }

        return data
    }
}
