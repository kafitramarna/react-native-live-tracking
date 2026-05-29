/**
 * NetworkListenerTests.swift
 *
 * Unit tests for NetworkListener — testing network monitoring state and protocol.
 * Tests focus on initial state, protocol conformance, and safe method calls.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: NWPathMonitor requires the actual network stack to function.
 * These tests focus on logic that can be tested without network hardware:
 * - Initial state (isOnline returns false before startListening)
 * - NetworkStateDelegate protocol methods
 * - Safe start/stop lifecycle
 */

import XCTest
@testable import LiveTracking

// MARK: - Mock NetworkStateDelegate

class MockNetworkStateDelegate: NetworkStateDelegate {
    var networkAvailableCount = 0
    var networkLostCount = 0

    var onNetworkAvailableExpectation: XCTestExpectation?
    var onNetworkLostExpectation: XCTestExpectation?

    func onNetworkAvailable() {
        networkAvailableCount += 1
        onNetworkAvailableExpectation?.fulfill()
    }

    func onNetworkLost() {
        networkLostCount += 1
        onNetworkLostExpectation?.fulfill()
    }
}

// MARK: - NetworkListener Tests

class NetworkListenerTests: XCTestCase {

    var networkListener: NetworkListener!
    var mockDelegate: MockNetworkStateDelegate!

    override func setUp() {
        super.setUp()
        networkListener = NetworkListener()
        mockDelegate = MockNetworkStateDelegate()
        networkListener.delegate = mockDelegate
    }

    override func tearDown() {
        networkListener.stopListening()
        networkListener = nil
        mockDelegate = nil
        super.tearDown()
    }

    // MARK: - Test: isOnline returns false initially (before startListening)

    func testIsOnlineReturnsFalseInitially() {
        XCTAssertFalse(networkListener.isOnline(),
                       "isOnline should return false before startListening is called")
    }

    func testIsOnlineReturnsFalseWithoutStartListening() {
        // Create a fresh listener and check without ever calling startListening
        let freshListener = NetworkListener()
        XCTAssertFalse(freshListener.isOnline(),
                       "A newly created NetworkListener should report isOnline as false")
    }

    // MARK: - Test: NetworkStateDelegate protocol has correct methods

    func testNetworkStateDelegateOnNetworkAvailableMethod() {
        // Verify the protocol method exists and can be called
        mockDelegate.onNetworkAvailable()

        XCTAssertEqual(mockDelegate.networkAvailableCount, 1,
                       "onNetworkAvailable should be callable")
    }

    func testNetworkStateDelegateOnNetworkLostMethod() {
        // Verify the protocol method exists and can be called
        mockDelegate.onNetworkLost()

        XCTAssertEqual(mockDelegate.networkLostCount, 1,
                       "onNetworkLost should be callable")
    }

    func testNetworkStateDelegateMultipleCalls() {
        mockDelegate.onNetworkAvailable()
        mockDelegate.onNetworkAvailable()
        mockDelegate.onNetworkLost()

        XCTAssertEqual(mockDelegate.networkAvailableCount, 2)
        XCTAssertEqual(mockDelegate.networkLostCount, 1)
    }

    // MARK: - Test: NetworkListener can be instantiated

    func testNetworkListenerCanBeInstantiated() {
        let listener = NetworkListener()
        XCTAssertNotNil(listener, "NetworkListener should be instantiable")
    }

    // MARK: - Test: stopListening doesn't crash when called before start

    func testStopListeningBeforeStartDoesNotCrash() {
        // Should be a no-op, not a crash
        networkListener.stopListening()

        XCTAssertFalse(networkListener.isOnline(),
                       "isOnline should still be false after stopListening without start")
    }

    // MARK: - Test: Multiple stopListening calls don't crash

    func testMultipleStopListeningCallsDontCrash() {
        networkListener.stopListening()
        networkListener.stopListening()
        networkListener.stopListening()

        // Should not crash
        XCTAssertFalse(networkListener.isOnline())
    }

    // MARK: - Test: startListening is idempotent

    func testStartListeningIsIdempotent() {
        // Multiple start calls should not crash or create multiple monitors
        networkListener.startListening()
        networkListener.startListening()
        networkListener.startListening()

        // Should not crash — the guard clause prevents multiple starts
        // Clean up
        networkListener.stopListening()
    }

    // MARK: - Test: Delegate is weak reference

    func testDelegateIsWeakReference() {
        var delegate: MockNetworkStateDelegate? = MockNetworkStateDelegate()
        networkListener.delegate = delegate

        XCTAssertNotNil(networkListener.delegate)

        // Release the delegate
        delegate = nil

        // Delegate should be nil (weak reference)
        XCTAssertNil(networkListener.delegate, "Delegate should be a weak reference")
    }

    // MARK: - Test: Start then stop lifecycle

    func testStartThenStopLifecycle() {
        networkListener.startListening()

        // Give the monitor a moment to initialize
        Thread.sleep(forTimeInterval: 0.1)

        networkListener.stopListening()

        // After stopping, the listener should still report its last known state
        // (which may have been updated during the brief listening period)
        // The key thing is it doesn't crash
    }

    // MARK: - Test: Delegate not called before startListening

    func testDelegateNotCalledBeforeStartListening() {
        // Without calling startListening, delegate should never be called
        Thread.sleep(forTimeInterval: 0.1)

        XCTAssertEqual(mockDelegate.networkAvailableCount, 0,
                       "Delegate should not be called before startListening")
        XCTAssertEqual(mockDelegate.networkLostCount, 0,
                       "Delegate should not be called before startListening")
    }
}
