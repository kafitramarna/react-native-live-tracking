import Foundation
import FirebaseDatabase

/**
 * Watches an RTDB path for a remote stop signal while tracking is active.
 * Lets a natively-resumed session honor remote stops without the RN/JS layer —
 * iOS counterpart of the Android StatusWatcher.
 *
 * The listener is attached to the shared FirebaseDatabase instance; the app is
 * expected to have called FirebaseApp.configure() already (AppDelegate does).
 */
final class StatusWatcher {

    private let stopValue: Int
    private let onStopRequested: () -> Void

    private var reference: DatabaseReference?
    private var handle: DatabaseHandle?

    private(set) var armedPath: String?

    var isArmed: Bool {
        handle != nil
    }

    init(stopValue: Int, onStopRequested: @escaping () -> Void) {
        self.stopValue = stopValue
        self.onStopRequested = onStopRequested
    }

    func arm(path: String) {
        if armedPath == path && handle != nil { return }
        disarm()

        let ref = Database.database().reference(withPath: path)
        let handle = ref.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }
            if StatusWatcher.isStopValue(snapshot.value, stopValue: self.stopValue) {
                self.onStopRequested()
            }
        }

        reference = ref
        self.handle = handle
        armedPath = path
    }

    func disarm() {
        if let handle = handle, let reference = reference {
            reference.removeObserver(withHandle: handle)
        }
        handle = nil
        reference = nil
        armedPath = nil
    }

    static func isStopValue(_ value: Any?, stopValue: Int) -> Bool {
        if let number = value as? NSNumber {
            return number.intValue == stopValue
        }
        if let string = value as? String {
            return Int(string.trimmingCharacters(in: .whitespaces)) == stopValue
        }
        return false
    }
}
