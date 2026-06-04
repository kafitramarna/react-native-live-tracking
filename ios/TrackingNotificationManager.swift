import Foundation
import UserNotifications

/**
 * Manages persistent local notifications for iOS live tracking.
 *
 * iOS does not have a "foreground service" concept like Android. Instead,
 * this manager posts a local notification when tracking starts and removes it
 * when tracking stops. If the user dismisses the notification, it is re-posted
 * to maintain visibility (similar to Android's foreground service notification).
 *
 * Requirements:
 * - Show persistent notification while tracking is active
 * - Re-post notification if dismissed by user
 * - Remove notification when tracking stops
 * - Gracefully degrade if notification permission is denied
 */
@objc
class TrackingNotificationManager: NSObject, UNUserNotificationCenterDelegate {

    // MARK: - Constants

    private static let notificationIdentifier = "com.livetracking.persistent"
    private static let categoryIdentifier = "LIVE_TRACKING_CATEGORY"
    private static let recheckInterval: TimeInterval = 5.0

    // MARK: - Properties

    private var title: String = "Live Tracking"
    private var body: String = "Tracking your location"
    private var isTrackingActive: Bool = false
    private var recheckTimer: Timer?
    private var hasNotificationPermission: Bool = false

    // MARK: - Singleton

    @objc static let shared = TrackingNotificationManager()

    private override init() {
        super.init()
        setupNotificationCategory()
    }

    // MARK: - Configuration

    /**
     * Configure the notification title and body text.
     *
     * - Parameter title: The notification title
     * - Parameter body: The notification body text
     */
    @objc
    func configure(title: String, body: String) {
        self.title = title
        self.body = body
    }

    // MARK: - Public Methods

    /**
     * Show the persistent tracking notification.
     * Requests notification permission if not already granted.
     * Starts a re-check timer to re-post if the user dismisses it.
     */
    @objc
    func showTrackingNotification() {
        isTrackingActive = true

        let center = UNUserNotificationCenter.current()
        center.delegate = self

        center.requestAuthorization(options: [.alert, .sound]) { [weak self] granted, _ in
            guard let self = self else { return }
            self.hasNotificationPermission = granted
            if granted {
                DispatchQueue.main.async {
                    self.postNotification()
                    self.startRecheckTimer()
                }
            }
        }
    }

    /**
     * Remove the tracking notification and stop the re-check timer.
     */
    @objc
    func removeTrackingNotification() {
        isTrackingActive = false
        stopRecheckTimer()

        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [TrackingNotificationManager.notificationIdentifier])
        center.removePendingNotificationRequests(withIdentifiers: [TrackingNotificationManager.notificationIdentifier])
    }

    // MARK: - UNUserNotificationCenterDelegate

    /**
     * Handle notification presentation while app is in foreground.
     * Show the notification banner even when app is active.
     */
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if notification.request.identifier == TrackingNotificationManager.notificationIdentifier {
            completionHandler([.banner])
        } else {
            completionHandler([.banner, .sound])
        }
    }

    // MARK: - Private Methods

    private func setupNotificationCategory() {
        let category = UNNotificationCategory(
            identifier: TrackingNotificationManager.categoryIdentifier,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    private func postNotification() {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.categoryIdentifier = TrackingNotificationManager.categoryIdentifier
        // No sound for persistent notification to avoid annoyance
        content.sound = nil

        let request = UNNotificationRequest(
            identifier: TrackingNotificationManager.notificationIdentifier,
            content: content,
            trigger: nil // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("[TrackingNotificationManager] Failed to post notification: \(error.localizedDescription)")
            }
        }
    }

    /**
     * Start a timer that periodically checks if the notification is still delivered.
     * If not (user dismissed it), re-post it while tracking is active.
     */
    private func startRecheckTimer() {
        stopRecheckTimer()
        recheckTimer = Timer.scheduledTimer(
            withTimeInterval: TrackingNotificationManager.recheckInterval,
            repeats: true
        ) { [weak self] _ in
            self?.recheckNotification()
        }
    }

    private func stopRecheckTimer() {
        recheckTimer?.invalidate()
        recheckTimer = nil
    }

    private func recheckNotification() {
        guard isTrackingActive, hasNotificationPermission else {
            stopRecheckTimer()
            return
        }

        UNUserNotificationCenter.current().getDeliveredNotifications { [weak self] notifications in
            guard let self = self, self.isTrackingActive else { return }

            let hasTrackingNotification = notifications.contains {
                $0.request.identifier == TrackingNotificationManager.notificationIdentifier
            }

            if !hasTrackingNotification {
                DispatchQueue.main.async {
                    self.postNotification()
                }
            }
        }
    }
}
