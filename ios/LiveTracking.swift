import Foundation
import CoreLocation

/**
 * Main native module implementation for iOS.
 * Extends RCTEventEmitter to support sending events to JavaScript.
 * Supports both TurboModules (new architecture) and Bridge (legacy architecture).
 *
 * Wires together all engines:
 * - LocationEngine: CLLocationManager wrapper for GPS updates
 * - QueueEngine: CoreData offline queue for history
 * - FirebaseSyncEngine: Firebase writes (RTDB/Firestore)
 * - NetworkListener: NWPathMonitor connectivity detection
 * - ActivityRecognitionHandler: CMMotionActivityManager activity detection
 * - MotionSleepManager: Sleep mode for battery optimization
 * - PermissionHandler: Permission checks
 * - BackgroundModeHelper: Significant location monitoring
 * - TrackingCleanup: Stop/cleanup utility
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 11.2
 */
@objc(LiveTracking)
class LiveTracking: RCTEventEmitter {

    // MARK: - Event Names

    private static let EVENT_LOCATION_UPDATE = "onLocationUpdate"
    private static let EVENT_TRACKING_ERROR = "onTrackingError"

    // MARK: - State

    private enum TrackingState: String {
        case idle
        case configured
        case tracking
        case motionSleep = "motion_sleep"
    }

    private var state: TrackingState = .idle
    private var hasListeners: Bool = false

    // MARK: - Engines

    private var locationEngine: LocationEngine?
    private var queueEngine: QueueEngine?
    private var syncEngine: FirebaseSyncEngine?
    private var networkListener: NetworkListener?
    private var activityRecognitionHandler: ActivityRecognitionHandler?
    private var motionSleepManager: MotionSleepManager?
    private var permissionHandler: PermissionHandler?
    private var trackingCleanup: TrackingCleanup?

    // MARK: - Configuration

    private var intervalMs: Int = 10000
    private var distanceFilterMeters: Double = 10.0
    private var stopWhenStill: Bool = true
    private var historyBatchSize: Int = 15
    private var currentLocationPath: String?
    private var historyPath: String?
    private var firebaseService: String = "RTDB"

    // MARK: - Tracking State

    private var lastLocation: CLLocation?
    private var lastUpdateTime: Date?

    // MARK: - RCTEventEmitter Overrides

    @objc
    override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    override func supportedEvents() -> [String]! {
        return [
            LiveTracking.EVENT_LOCATION_UPDATE,
            LiveTracking.EVENT_TRACKING_ERROR
        ]
    }

    override func startObserving() {
        hasListeners = true
    }

    override func stopObserving() {
        hasListeners = false
    }

    // MARK: - Public Methods (Exposed to JS)

    /**
     * Configure the tracking module with a JSON config string.
     * Parses the config and initializes all engines.
     *
     * Expected JSON structure:
     * {
     *   "optimization": { "intervalMs": 10000, "distanceFilterMeters": 10, "stopWhenStill": true },
     *   "firebase": { "service": "RTDB"|"Firestore", "currentLocationPath": "...", "historyPath": "...", "historyBatchSize": 15 }
     * }
     */
    @objc
    func configure(_ config: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let data = config.data(using: .utf8) else {
            reject("INVALID_CONFIG", "Configuration string is not valid UTF-8", nil)
            return
        }

        guard let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            reject("INVALID_CONFIG", "Configuration string is not valid JSON", nil)
            return
        }

        // Parse firebase config (required)
        guard let firebaseConfig = json["firebase"] as? [String: Any] else {
            reject("INVALID_CONFIG", "Missing required field: firebase", nil)
            return
        }

        guard let service = firebaseConfig["service"] as? String,
              (service == "RTDB" || service == "Firestore") else {
            reject("INVALID_CONFIG", "Invalid or missing firebase.service. Must be 'RTDB' or 'Firestore'", nil)
            return
        }

        let currentPath = firebaseConfig["currentLocationPath"] as? String
        let histPath = firebaseConfig["historyPath"] as? String

        if currentPath == nil && histPath == nil {
            reject("INVALID_CONFIG", "At least one of firebase.currentLocationPath or firebase.historyPath must be configured", nil)
            return
        }

        // Parse optimization config (optional with defaults)
        let optimizationConfig = json["optimization"] as? [String: Any]

        let parsedIntervalMs = optimizationConfig?["intervalMs"] as? Int ?? 10000
        let parsedDistanceFilter = optimizationConfig?["distanceFilterMeters"] as? Double
            ?? (optimizationConfig?["distanceFilterMeters"] as? Int).map { Double($0) }
            ?? 10.0
        let parsedStopWhenStill = optimizationConfig?["stopWhenStill"] as? Bool ?? true
        let parsedBatchSize = firebaseConfig["historyBatchSize"] as? Int ?? 15

        // Validate values
        if parsedIntervalMs < 0 {
            reject("INVALID_CONFIG", "optimization.intervalMs must be non-negative", nil)
            return
        }
        if parsedDistanceFilter < 0 {
            reject("INVALID_CONFIG", "optimization.distanceFilterMeters must be non-negative", nil)
            return
        }

        // Store configuration
        self.firebaseService = service
        self.currentLocationPath = currentPath
        self.historyPath = histPath
        self.intervalMs = parsedIntervalMs
        self.distanceFilterMeters = parsedDistanceFilter
        self.stopWhenStill = parsedStopWhenStill
        self.historyBatchSize = parsedBatchSize

        // Initialize engines
        self.locationEngine = LocationEngine()
        self.locationEngine?.delegate = self

        self.queueEngine = QueueEngine()

        self.syncEngine = FirebaseSyncEngine(
            service: service,
            currentLocationPath: currentPath,
            historyPath: histPath
        )

        self.networkListener = NetworkListener()
        self.networkListener?.delegate = self

        self.activityRecognitionHandler = ActivityRecognitionHandler()
        self.activityRecognitionHandler?.stationaryThresholdMs = MotionSleepManager.STILL_THRESHOLD_MS
        self.activityRecognitionHandler?.delegate = self

        self.motionSleepManager = MotionSleepManager(
            locationEngine: self.locationEngine!,
            stopWhenStill: parsedStopWhenStill,
            intervalMs: parsedIntervalMs,
            distanceFilter: parsedDistanceFilter
        )
        self.motionSleepManager?.delegate = self

        self.permissionHandler = PermissionHandler()
        self.trackingCleanup = TrackingCleanup()

        // Update state
        self.state = .configured

        resolve(nil)
    }

    /**
     * Start location tracking.
     * Checks permissions, starts location engine, activity recognition,
     * network listener, and significant location monitoring.
     */
    @objc
    func start(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard state == .configured || state == .tracking else {
            reject("NOT_CONFIGURED", "Library must be configured before starting. Call configure() first.", nil)
            return
        }

        guard let permissionHandler = self.permissionHandler else {
            reject("NOT_CONFIGURED", "Permission handler not initialized. Call configure() first.", nil)
            return
        }

        // Check permissions
        let permissionResult = permissionHandler.checkAllRequirements()
        switch permissionResult {
        case .granted:
            break
        case .denied(let errorCode, let message):
            reject(errorCode, message, nil)
            return
        }

        guard let locationEngine = self.locationEngine else {
            reject("NOT_CONFIGURED", "Location engine not initialized. Call configure() first.", nil)
            return
        }

        // Start location engine
        locationEngine.startLocationUpdates(intervalMs: intervalMs, distanceFilter: distanceFilterMeters)

        // Start activity recognition for motion sleep mode
        activityRecognitionHandler?.startActivityRecognition()

        // Start network listener for queue flush
        networkListener?.startListening()

        // Start significant location monitoring as fallback
        let clManager = CLLocationManager()
        BackgroundModeHelper.shared.startSignificantLocationMonitoring(locationManager: clManager)

        // Mark tracking active
        trackingCleanup?.markTrackingActive(locationManager: clManager)

        // Reset last update tracking
        lastUpdateTime = nil
        lastLocation = nil

        // Update state
        state = .tracking

        // Flush any pending queue items
        flushQueueIfNeeded()

        resolve(nil)
    }

    /**
     * Stop location tracking.
     * Stops all engines via TrackingCleanup, resets state.
     */
    @objc
    func stop(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard state == .tracking || state == .motionSleep else {
            // Already stopped, resolve silently
            resolve(nil)
            return
        }

        // Stop location engine and significant location monitoring
        if let locationEngine = self.locationEngine {
            let clManager = CLLocationManager()
            trackingCleanup?.stopAllTracking(
                locationEngine: locationEngine,
                backgroundHelper: BackgroundModeHelper.shared,
                locationManager: clManager
            )
        }

        // Stop activity recognition
        activityRecognitionHandler?.stopActivityRecognition()

        // Stop network listener
        networkListener?.stopListening()

        // Cleanup
        trackingCleanup?.cleanup()

        // Reset state
        lastLocation = nil
        lastUpdateTime = nil
        state = .configured

        resolve(nil)
    }

    /**
     * Get the current tracking status as a JSON string.
     * Returns state, isOnline, queuedLocations, lastLocation, batteryOptimization.
     */
    @objc
    func getStatus(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        var status: [String: Any] = [
            "state": state.rawValue,
            "isOnline": networkListener?.isOnline() ?? false,
            "queuedLocations": queueEngine?.count() ?? 0
        ]

        // Battery optimization mode
        let batteryOptimization: String
        if !stopWhenStill {
            batteryOptimization = "disabled"
        } else if motionSleepManager?.isInSleepMode() == true {
            batteryOptimization = "low_power"
        } else {
            batteryOptimization = "full_accuracy"
        }
        status["batteryOptimization"] = batteryOptimization

        // Last location
        if let location = lastLocation {
            status["lastLocation"] = [
                "latitude": location.coordinate.latitude,
                "longitude": location.coordinate.longitude,
                "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000),
                "accuracy": location.horizontalAccuracy,
                "speed": location.speed >= 0 ? location.speed : NSNull(),
                "altitude": location.altitude,
                "bearing": location.course >= 0 ? location.course : NSNull()
            ] as [String: Any]
        } else {
            status["lastLocation"] = NSNull()
        }

        // Serialize to JSON string
        if let jsonData = try? JSONSerialization.data(withJSONObject: status, options: []),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            resolve(jsonString)
        } else {
            resolve("{}")
        }
    }

    /**
     * Get the number of queued locations waiting to be synced.
     */
    @objc
    func getQueuedLocations(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let count = queueEngine?.count() ?? 0
        resolve(count)
    }

    // MARK: - Private Methods

    /**
     * Apply distance/time filter to determine if a location update should be processed.
     * Both conditions must be met: time elapsed >= intervalMs AND distance >= distanceFilterMeters.
     */
    private func shouldProcessLocation(_ location: CLLocation) -> Bool {
        guard let lastLoc = lastLocation, let lastTime = lastUpdateTime else {
            // First location always passes
            return true
        }

        // Check time filter
        let timeDiffMs = Int(location.timestamp.timeIntervalSince(lastTime) * 1000)
        if timeDiffMs < intervalMs {
            return false
        }

        // Check distance filter
        let distance = location.distance(from: lastLoc)
        if distance < distanceFilterMeters {
            return false
        }

        return true
    }

    /**
     * Process a valid location update:
     * 1. Emit event to JS
     * 2. Enqueue for history (if historyPath configured)
     * 3. Sync current location to Firebase (if currentLocationPath configured)
     * 4. Check if batch should be flushed
     */
    private func processLocationUpdate(_ location: CLLocation) {
        // Update last known location and time
        lastLocation = location
        lastUpdateTime = location.timestamp

        let locationData: [String: Any] = [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000),
            "accuracy": location.horizontalAccuracy,
            "speed": location.speed >= 0 ? location.speed : NSNull(),
            "altitude": location.altitude,
            "bearing": location.course >= 0 ? location.course : NSNull()
        ]

        // 1. Emit event to JavaScript
        emitLocationEvent(locationData)

        // 2. Enqueue for history if historyPath is configured
        if historyPath != nil {
            queueEngine?.enqueue(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                timestamp: Int64(location.timestamp.timeIntervalSince1970 * 1000),
                accuracy: location.horizontalAccuracy,
                speed: location.speed >= 0 ? location.speed : 0,
                altitude: location.altitude,
                bearing: location.course >= 0 ? location.course : 0
            )

            // Check if batch should be flushed
            flushQueueIfNeeded()
        }

        // 3. Sync current location to Firebase if currentLocationPath is configured
        if currentLocationPath != nil {
            syncEngine?.updateCurrentLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                timestamp: Int64(location.timestamp.timeIntervalSince1970 * 1000),
                accuracy: location.horizontalAccuracy,
                speed: location.speed >= 0 ? location.speed : nil,
                callback: self
            )
        }
    }

    /**
     * Flush the queue if batch size is reached and device is online.
     */
    private func flushQueueIfNeeded() {
        guard let queueEngine = self.queueEngine,
              let syncEngine = self.syncEngine,
              historyPath != nil else { return }

        let isOnline = networkListener?.isOnline() ?? false
        guard isOnline else { return }

        let queueCount = queueEngine.count()
        if queueCount >= historyBatchSize {
            let batch = queueEngine.dequeueBatch(size: historyBatchSize)
            if !batch.isEmpty {
                let ids = batch.compactMap { $0["id"] as? String }
                let locations = batch.map { item -> [String: Any] in
                    var loc: [String: Any] = [
                        "latitude": item["latitude"] ?? 0,
                        "longitude": item["longitude"] ?? 0,
                        "timestamp": item["timestamp"] ?? 0,
                        "accuracy": item["accuracy"] ?? 0,
                        "speed": item["speed"] ?? 0,
                        "altitude": item["altitude"] ?? 0,
                        "bearing": item["bearing"] ?? 0
                    ]
                    return loc
                }

                syncEngine.pushHistoryBatch(locations: locations, callback: QueueSyncCallback(
                    queueEngine: queueEngine,
                    ids: ids,
                    onError: { [weak self] errorCode, message in
                        self?.emitErrorEvent(errorCode: errorCode, message: message)
                    }
                ))
            }
        }
    }

    /**
     * Emit a location update event to JavaScript.
     */
    private func emitLocationEvent(_ locationData: [String: Any]) {
        guard hasListeners else { return }
        sendEvent(withName: LiveTracking.EVENT_LOCATION_UPDATE, body: locationData)
    }

    /**
     * Emit a tracking error event to JavaScript.
     */
    private func emitErrorEvent(errorCode: String, message: String) {
        guard hasListeners else { return }
        sendEvent(withName: LiveTracking.EVENT_TRACKING_ERROR, body: [
            "code": errorCode,
            "message": message
        ])
    }
}

// MARK: - LocationUpdateDelegate

extension LiveTracking: LocationUpdateDelegate {
    func onLocationReceived(location: CLLocation) {
        // Apply distance/time filter
        if shouldProcessLocation(location) {
            processLocationUpdate(location)
        }
    }
}

// MARK: - NetworkStateDelegate

extension LiveTracking: NetworkStateDelegate {
    func onNetworkAvailable() {
        // Flush queue when network is restored
        flushQueueIfNeeded()
    }

    func onNetworkLost() {
        // No action needed - locations continue to be queued locally
    }
}

// MARK: - ActivityStateDelegate

extension LiveTracking: ActivityStateDelegate {
    func onActivityChanged(activity: ActivityType) {
        // Forward to MotionSleepManager
        motionSleepManager?.onActivityDetected(activity: activity)
    }

    func onStationaryDurationExceeded(durationMs: Int64) {
        // MotionSleepManager handles this via onActivityDetected
        // This is an additional notification that can be used for logging
    }
}

// MARK: - MotionSleepDelegate

extension LiveTracking: MotionSleepDelegate {
    func onSleepModeActivated() {
        state = .motionSleep
    }

    func onSleepModeDeactivated() {
        state = .tracking
    }
}

// MARK: - SyncCallback (for current location sync)

extension LiveTracking: SyncCallback {
    func onSuccess() {
        // Current location synced successfully - no action needed
    }

    func onError(errorCode: String, message: String) {
        emitErrorEvent(errorCode: errorCode, message: message)
    }
}

// MARK: - QueueSyncCallback (for history batch sync)

/**
 * Callback handler for queue batch sync operations.
 * On success, removes the synced items from the queue.
 * On error, emits an error event (items remain in queue for retry).
 */
private class QueueSyncCallback: SyncCallback {
    private let queueEngine: QueueEngine
    private let ids: [String]
    private let onError: (String, String) -> Void

    init(queueEngine: QueueEngine, ids: [String], onError: @escaping (String, String) -> Void) {
        self.queueEngine = queueEngine
        self.ids = ids
        self.onError = onError
    }

    func onSuccess() {
        // Remove successfully synced items from queue
        queueEngine.removeBatch(ids: ids)
    }

    func onError(errorCode: String, message: String) {
        // Items remain in queue for retry on next opportunity
        onError(errorCode, message)
    }
}
