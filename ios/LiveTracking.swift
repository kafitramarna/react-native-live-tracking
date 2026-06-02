import Foundation
import CoreLocation

/**
 * Main native module implementation for iOS.
 * Extends RCTEventEmitter to support sending events to JavaScript.
 * Supports both TurboModules (new architecture) and Bridge (legacy architecture).
 *
 * Wires together all engines:
 * - LocationEngine: CLLocationManager wrapper for GPS updates
 * - SyncEngineController: Multi-target Firebase sync with per-target batching, retry, and offline queue
 * - OfflineQueueManager: CoreData offline queue for per-target persistence
 * - NetworkListener: NWPathMonitor connectivity detection
 * - ActivityRecognitionHandler: CMMotionActivityManager activity detection
 * - MotionSleepManager: Sleep mode for battery optimization
 * - PermissionHandler: Permission checks
 * - BackgroundModeHelper: Significant location monitoring
 * - TrackingCleanup: Stop/cleanup utility
 *
 * Requirements: 3.1, 4.4, 9.3, 9.4, 10.1, 10.2, 10.3, 10.4
 */
@objc(LiveTracking)
class LiveTracking: RCTEventEmitter, RCTTurboModule {

    // MARK: - Event Names

    private static let EVENT_LOCATION_UPDATE = "onLocationUpdate"
    private static let EVENT_TRACKING_ERROR = "onTrackingError"
    private static let EVENT_QUEUE_OVERFLOW = "onQueueOverflow"

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
    private var syncEngineController: SyncEngineController?
    private var offlineQueueManager: OfflineQueueManager?
    private var networkListener: NetworkListener?
    private var activityRecognitionHandler: ActivityRecognitionHandler?
    private var motionSleepManager: MotionSleepManager?
    private var permissionHandler: PermissionHandler?
    private var trackingCleanup: TrackingCleanup?

    // MARK: - Configuration

    private var intervalMs: Int = 10000
    private var distanceFilterMeters: Double = 10.0
    private var stopWhenStill: Bool = true

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
            LiveTracking.EVENT_TRACKING_ERROR,
            LiveTracking.EVENT_QUEUE_OVERFLOW
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
     * Parses the config and initializes all engines including SyncEngineController.
     *
     * Expected JSON structure:
     * {
     *   "optimization": { "intervalMs": 10000, "distanceFilterMeters": 10, "stopWhenStill": true },
     *   "firebase": { "service": "RTDB"|"Firestore", "targets": [...] }
     * }
     *
     * Requirements: 9.3, 9.4
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

        // Validate targets array exists
        guard firebaseConfig["targets"] is [[String: Any]] else {
            reject("INVALID_CONFIG", "Missing or invalid 'targets' array in firebase configuration", nil)
            return
        }

        // Parse optimization config (optional with defaults)
        let optimizationConfig = json["optimization"] as? [String: Any]

        let parsedIntervalMs = optimizationConfig?["intervalMs"] as? Int ?? 10000
        let parsedDistanceFilter = optimizationConfig?["distanceFilterMeters"] as? Double
            ?? (optimizationConfig?["distanceFilterMeters"] as? Int).map { Double($0) }
            ?? 10.0
        let parsedStopWhenStill = optimizationConfig?["stopWhenStill"] as? Bool ?? true

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
        self.intervalMs = parsedIntervalMs
        self.distanceFilterMeters = parsedDistanceFilter
        self.stopWhenStill = parsedStopWhenStill

        // Initialize network listener first (needed by SyncEngineController)
        self.networkListener = NetworkListener()
        self.networkListener?.delegate = self

        // Initialize offline queue manager
        self.offlineQueueManager = OfflineQueueManager()

        // Serialize the firebase config back to JSON for SyncEngineController
        guard let firebaseJsonData = try? JSONSerialization.data(withJSONObject: firebaseConfig, options: []),
              let firebaseJsonString = String(data: firebaseJsonData, encoding: .utf8) else {
            reject("INVALID_CONFIG", "Failed to serialize firebase configuration", nil)
            return
        }

        // Initialize SyncEngineController with targets JSON
        // Requirement 9.3: Deserialize JSON targets array and instantiate one write handler per SyncTarget
        // Requirement 9.4: Reject with INVALID_CONFIG if targets JSON fails to parse
        do {
            self.syncEngineController = try SyncEngineController(
                jsonString: firebaseJsonString,
                networkStatus: self.networkListener!,
                queueManager: self.offlineQueueManager!
            )
            self.syncEngineController?.delegate = self
        } catch let error as SyncEngineError {
            reject(error.errorCode, error.errorDescription ?? "Failed to initialize sync engine", nil)
            return
        } catch {
            reject("INVALID_CONFIG", "Failed to initialize sync engine: \(error.localizedDescription)", nil)
            return
        }

        // Initialize remaining engines
        self.locationEngine = LocationEngine()
        self.locationEngine?.delegate = self

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

        // Flush any pending offline queues
        syncEngineController?.flushOfflineQueues()

        resolve(nil)
    }

    /**
     * Stop location tracking.
     * Flushes all partial batches via SyncEngineController, then stops all engines.
     *
     * Requirement 4.4: Flush all partially-filled batches before stop completes.
     */
    @objc
    func stop(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard state == .tracking || state == .motionSleep else {
            // Already stopped, resolve silently
            resolve(nil)
            return
        }

        // Flush all partial batches before stopping (Requirement 4.4)
        syncEngineController?.flushAll { [weak self] in
            guard let self = self else {
                resolve(nil)
                return
            }

            // Stop location engine and significant location monitoring
            if let locationEngine = self.locationEngine {
                let clManager = CLLocationManager()
                self.trackingCleanup?.stopAllTracking(
                    locationEngine: locationEngine,
                    backgroundHelper: BackgroundModeHelper.shared,
                    locationManager: clManager
                )
            }

            // Stop activity recognition
            self.activityRecognitionHandler?.stopActivityRecognition()

            // Stop network listener
            self.networkListener?.stopListening()

            // Cleanup
            self.trackingCleanup?.cleanup()

            // Reset state
            self.lastLocation = nil
            self.lastUpdateTime = nil
            self.state = .configured

            resolve(nil)
        }
    }

    /**
     * Get the current tracking status as a JSON string.
     * Returns state, isOnline, queuedLocations, lastLocation, batteryOptimization.
     */
    @objc
    func getStatus(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let totalQueued = syncEngineController?.getTotalQueuedCount() ?? 0

        var status: [String: Any] = [
            "state": state.rawValue,
            "isOnline": networkListener?.isOnline() ?? false,
            "queuedLocations": totalQueued
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
     * Get the total number of queued locations waiting to be synced across all targets.
     *
     * Requirement 10.1: Total count across all targets.
     * Requirement 10.4: Reject with NOT_CONFIGURED if called before configure().
     */
    @objc
    func getQueuedLocations(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard state != .idle, syncEngineController != nil else {
            reject("NOT_CONFIGURED", "Library must be configured before querying queue status. Call configure() first.", nil)
            return
        }

        let count = syncEngineController?.getTotalQueuedCount() ?? 0
        resolve(count)
    }

    /**
     * Get the queued location counts per target path as a JSON string.
     * Returns a record mapping each configured target path to its queued location count.
     * Targets with offlineQueue disabled report 0.
     *
     * Requirement 10.2: Per-target queue counts.
     * Requirement 10.3: Non-queuing targets report 0.
     * Requirement 10.4: Reject with NOT_CONFIGURED if called before configure().
     */
    @objc
    func getQueuedLocationsByTarget(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard state != .idle, let controller = syncEngineController else {
            reject("NOT_CONFIGURED", "Library must be configured before querying queue status. Call configure() first.", nil)
            return
        }

        let counts = controller.getQueuedCounts()

        // Serialize to JSON string for bridge transport
        if let jsonData = try? JSONSerialization.data(withJSONObject: counts, options: []),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            resolve(jsonString)
        } else {
            resolve("{}")
        }
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
     * 2. Dispatch to all sync targets via SyncEngineController
     *
     * Requirement 3.1: Dispatch to all configured targets in parallel.
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

        // 2. Dispatch to all sync targets via SyncEngineController
        let dataPoint = LocationDataPoint(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            accuracy: location.horizontalAccuracy,
            speed: location.speed >= 0 ? location.speed : nil,
            altitude: location.altitude,
            bearing: location.course >= 0 ? location.course : nil
        )

        syncEngineController?.dispatchLocation(dataPoint)
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
    private func emitErrorEvent(errorCode: String, message: String, targetPath: String? = nil, method: String? = nil) {
        guard hasListeners else { return }
        var body: [String: Any] = [
            "code": errorCode,
            "message": message
        ]
        if let targetPath = targetPath {
            body["targetPath"] = targetPath
        }
        if let method = method {
            body["method"] = method
        }
        sendEvent(withName: LiveTracking.EVENT_TRACKING_ERROR, body: body)
    }

    /**
     * Emit a queue overflow warning event to JavaScript.
     */
    private func emitQueueOverflowEvent(targetPath: String) {
        guard hasListeners else { return }
        sendEvent(withName: LiveTracking.EVENT_QUEUE_OVERFLOW, body: [
            "code": "QUEUE_OVERFLOW",
            "message": "Offline queue overflow for target: \(targetPath)",
            "targetPath": targetPath
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
        // Flush offline queues when network is restored
        syncEngineController?.flushOfflineQueues()
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

// MARK: - SyncEngineControllerDelegate

extension LiveTracking: SyncEngineControllerDelegate {
    func onSyncError(targetPath: String, method: String, errorCode: String, message: String) {
        emitErrorEvent(errorCode: errorCode, message: message, targetPath: targetPath, method: method)
    }

    func onSyncQueueOverflow(targetPath: String) {
        emitQueueOverflowEvent(targetPath: targetPath)
    }
}
