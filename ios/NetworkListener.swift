import Foundation
import Network

/**
 * Protocol for receiving network state change notifications.
 * Implementations receive callbacks when connectivity is gained or lost.
 */
protocol NetworkStateDelegate: AnyObject {
    /**
     * Called when network connectivity is restored.
     * Use this to trigger queue flush for pending location data.
     */
    func onNetworkAvailable()

    /**
     * Called when network connectivity is lost.
     */
    func onNetworkLost()
}

/**
 * NetworkListener monitors device network connectivity using NWPathMonitor.
 * When connectivity is restored after being offline, it notifies the registered delegate
 * so that pending queued locations can be flushed to Firebase.
 *
 * Requirements: 6.2, 6.4
 *
 * Usage:
 * ```
 * let listener = NetworkListener()
 * listener.delegate = self
 * listener.startListening()
 * // ...
 * listener.stopListening()
 * ```
 */
class NetworkListener {

    // MARK: - Properties

    private let monitor: NWPathMonitor
    private let monitorQueue: DispatchQueue
    private var isListening: Bool = false
    private var currentlyOnline: Bool = false

    weak var delegate: NetworkStateDelegate?

    // MARK: - Initialization

    init() {
        monitor = NWPathMonitor()
        monitorQueue = DispatchQueue(label: "com.livetracking.network.monitor", qos: .utility)
    }

    // MARK: - Public Methods

    /**
     * Start listening for network connectivity changes.
     * Starts NWPathMonitor on a background queue.
     * If already listening, this is a no-op.
     */
    func startListening() {
        guard !isListening else { return }

        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }

            let wasOnline = self.currentlyOnline
            let isNowOnline = path.status == .satisfied

            self.currentlyOnline = isNowOnline

            if isNowOnline && !wasOnline {
                self.delegate?.onNetworkAvailable()
            } else if !isNowOnline && wasOnline {
                self.delegate?.onNetworkLost()
            }
        }

        monitor.start(queue: monitorQueue)
        isListening = true
    }

    /**
     * Stop listening for network connectivity changes.
     * Cancels the NWPathMonitor.
     * If not currently listening, this is a no-op.
     */
    func stopListening() {
        guard isListening else { return }
        monitor.cancel()
        isListening = false
    }

    /**
     * Returns the current network connectivity state.
     *
     * - Returns: true if the device currently has network connectivity, false otherwise
     */
    func isOnline() -> Bool {
        return currentlyOnline
    }
}
