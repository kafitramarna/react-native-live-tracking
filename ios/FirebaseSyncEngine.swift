import Foundation
import FirebaseDatabase
import FirebaseFirestore

/**
 * Protocol for Firebase sync operation callbacks.
 */
protocol SyncCallback: AnyObject {
    func onSuccess()
    func onError(errorCode: String, message: String)
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
 * Requirements: 4.1, 4.3, 5.2, 10.4
 */
class FirebaseSyncEngine {

    // MARK: - Constants

    private static let baseDelayMs: Int = 1000
    private static let multiplier: Double = 2.0
    private static let jitterMs: Int = 200
    private static let maxRetriesCurrent: Int = 3
    private static let maxRetriesHistory: Int = 5

    // MARK: - Properties

    private let service: String
    private let currentLocationPath: String?
    private let historyPath: String?
    private let syncQueue = DispatchQueue(label: "com.livetracking.firebase.sync", qos: .utility)

    // MARK: - Initialization

    /**
     * Initialize the FirebaseSyncEngine.
     *
     * - Parameter service: Either "RTDB" or "Firestore"
     * - Parameter currentLocationPath: Firebase path for current location (overwrite)
     * - Parameter historyPath: Firebase path for history locations (append)
     */
    init(service: String, currentLocationPath: String?, historyPath: String?) {
        self.service = service
        self.currentLocationPath = currentLocationPath
        self.historyPath = historyPath
    }

    // MARK: - Public Methods

    /**
     * Update the current location at the configured currentLocationPath.
     * Uses set/update (overwrite) semantics.
     * Retries up to 3 times with exponential backoff on failure.
     *
     * - Parameter latitude: Location latitude
     * - Parameter longitude: Location longitude
     * - Parameter timestamp: Unix timestamp in milliseconds
     * - Parameter accuracy: Location accuracy in meters
     * - Parameter speed: Speed in m/s, nullable
     * - Parameter callback: SyncCallback for success/error notification
     */
    func updateCurrentLocation(
        latitude: Double,
        longitude: Double,
        timestamp: Int64,
        accuracy: Double,
        speed: Double?,
        callback: SyncCallback
    ) {
        guard let path = currentLocationPath else {
            callback.onError(errorCode: "NO_PATH", message: "currentLocationPath is not configured")
            return
        }

        var data: [String: Any] = [
            "latitude": latitude,
            "longitude": longitude,
            "timestamp": timestamp,
            "accuracy": accuracy,
            "updatedAt": timestamp
        ]
        if let speed = speed {
            data["speed"] = speed
        }

        syncQueue.async { [weak self] in
            guard let self = self else { return }
            self.executeWithRetry(
                maxRetries: FirebaseSyncEngine.maxRetriesCurrent,
                operation: { completion in
                    self.performCurrentLocationWrite(path: path, data: data, completion: completion)
                },
                callback: callback
            )
        }
    }

    /**
     * Push a batch of locations to the configured historyPath.
     * Uses push/append semantics (each location gets a unique key).
     * Retries up to 5 times with exponential backoff on failure.
     *
     * - Parameter locations: Array of location dictionaries to send
     * - Parameter callback: SyncCallback for success/error notification
     */
    func pushHistoryBatch(locations: [[String: Any]], callback: SyncCallback) {
        guard let path = historyPath else {
            callback.onError(errorCode: "NO_PATH", message: "historyPath is not configured")
            return
        }

        if locations.isEmpty {
            callback.onSuccess()
            return
        }

        syncQueue.async { [weak self] in
            guard let self = self else { return }
            self.executeWithRetry(
                maxRetries: FirebaseSyncEngine.maxRetriesHistory,
                operation: { completion in
                    self.performHistoryBatchWrite(path: path, locations: locations, completion: completion)
                },
                callback: callback
            )
        }
    }

    // MARK: - Private Write Methods

    private func performCurrentLocationWrite(
        path: String,
        data: [String: Any],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        switch service {
        case "RTDB":
            let reference = Database.database().reference(withPath: path)
            reference.setValue(data) { error, _ in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        case "Firestore":
            let document = Firestore.firestore().document(path)
            document.setData(data) { error in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }
        default:
            completion(false, "INVALID_SERVICE", "Unknown service: \(service). Use 'RTDB' or 'Firestore'.")
        }
    }

    private func performHistoryBatchWrite(
        path: String,
        locations: [[String: Any]],
        completion: @escaping (Bool, String?, String?) -> Void
    ) {
        switch service {
        case "RTDB":
            let reference = Database.database().reference(withPath: path)
            var updates: [String: Any] = [:]

            for location in locations {
                let key = reference.childByAutoId().key ?? UUID().uuidString
                updates[key] = location
            }

            if updates.isEmpty {
                completion(true, nil, nil)
                return
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
            let collection = firestore.collection(path)
            let batch = firestore.batch()

            for location in locations {
                let docRef = collection.document()
                batch.setData(location, forDocument: docRef)
            }

            batch.commit { error in
                if let error = error {
                    completion(false, "FIREBASE_WRITE_FAILED", error.localizedDescription)
                } else {
                    completion(true, nil, nil)
                }
            }

        default:
            completion(false, "INVALID_SERVICE", "Unknown service: \(service). Use 'RTDB' or 'Firestore'.")
        }
    }

    // MARK: - Retry Logic

    /**
     * Execute an operation with exponential backoff retry.
     *
     * Retry delay formula: baseDelay × 2^(attempt-1) ± jitter
     * - Base delay: 1000ms
     * - Multiplier: 2x
     * - Jitter: ±200ms (random)
     *
     * - Parameter maxRetries: Maximum number of retry attempts
     * - Parameter operation: The Firebase write operation to execute, calls completion(success, errorCode, message)
     * - Parameter callback: Final callback after all retries exhausted or success
     */
    private func executeWithRetry(
        maxRetries: Int,
        operation: @escaping (@escaping (Bool, String?, String?) -> Void) -> Void,
        callback: SyncCallback
    ) {
        var attempt = 0

        func tryOperation() {
            attempt += 1
            operation { success, errorCode, message in
                if success {
                    callback.onSuccess()
                } else {
                    if attempt >= maxRetries {
                        callback.onError(
                            errorCode: errorCode ?? "FIREBASE_WRITE_FAILED",
                            message: "Failed after \(attempt) attempts: \(message ?? "Unknown error")"
                        )
                    } else {
                        let delay = self.calculateBackoffDelay(attempt: attempt)
                        Thread.sleep(forTimeInterval: Double(delay) / 1000.0)
                        tryOperation()
                    }
                }
            }
        }

        tryOperation()
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * Formula: baseDelay × 2^(attempt-1) ± random jitter
     *
     * - Parameter attempt: Current attempt number (1-indexed)
     * - Returns: Delay in milliseconds
     */
    func calculateBackoffDelay(attempt: Int) -> Int {
        let exponentialDelay = Double(FirebaseSyncEngine.baseDelayMs) * pow(FirebaseSyncEngine.multiplier, Double(attempt - 1))
        let jitter = Int.random(in: -FirebaseSyncEngine.jitterMs...FirebaseSyncEngine.jitterMs)
        return max(0, Int(exponentialDelay) + jitter)
    }
}
