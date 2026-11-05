import Foundation
import UIKit

/**
 * Public entry point for resuming a tracking session after process death.
 *
 * Call from `application(_:didFinishLaunchingWithOptions:)` AFTER
 * FirebaseApp.configure() so the RTDB status watcher can attach:
 *
 * ```swift
 * LiveTrackingResume.resumeIfNeeded(launchOptions: launchOptions)
 * ```
 *
 * Resume conditions (mirrors the Android self-heal):
 * - a tracking session was persisted as active (configure + start succeeded),
 * - the persisted config can be re-parsed,
 * - control.resumeOnRestart was not disabled,
 * - required permissions are still granted.
 *
 * On failure the persisted flag is cleared and a stop reason is recorded so the
 * JS layer can report it via getStatus() on next foreground check.
 */
@objc(LiveTrackingResume)
public final class LiveTrackingResume: NSObject {

    @objc
    public static func resumeIfNeeded(launchOptions: [UIApplication.LaunchOptionsKey: Any]?) {
        guard TrackingStateStore.isTrackingActive() else { return }

        guard let savedConfig = TrackingStateStore.loadConfig() else {
            TrackingStateStore.saveTrackingActive(false)
            TrackingStateStore.saveLastStopReason("resume_failed")
            return
        }

        // Honor resumeOnRestart from the persisted config (peek before configure)
        if let data = savedConfig.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let control = json["control"] as? [String: Any],
           control["resumeOnRestart"] as? Bool == false {
            TrackingStateStore.saveTrackingActive(false)
            TrackingStateStore.saveLastStopReason("resume_disabled")
            return
        }

        let relaunchReason = (launchOptions?[.location] != nil) ? "location_relaunch" : "cold_start"
        TrackingStateStore.saveLastRestartReason(relaunchReason)

        // Reuse the live module instance when RN already created one; otherwise
        // instantiate a headless module that runs engines without the JS layer.
        let module = LiveTracking.shared ?? LiveTracking()
        _ = module.resumeFromSavedConfig(savedConfig)
    }
}
