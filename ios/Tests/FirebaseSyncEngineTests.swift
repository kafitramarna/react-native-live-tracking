/**
 * FirebaseSyncEngineTests.swift
 *
 * Unit tests for FirebaseSyncEngine — focusing on retry/backoff logic and path validation.
 * The calculateBackoffDelay method is `internal` and accessible within the same module via @testable import.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: Firebase SDK calls are NOT mocked here — these tests focus on the backoff calculation
 * and path validation logic which don't require Firebase connectivity.
 */

import XCTest
@testable import LiveTracking

// MARK: - Mock SyncCallback

class MockSyncCallback: SyncCallback {
    var successCount = 0
    var errorCount = 0
    var lastErrorCode: String?
    var lastErrorMessage: String?

    var onSuccessExpectation: XCTestExpectation?
    var onErrorExpectation: XCTestExpectation?

    func onSuccess() {
        successCount += 1
        onSuccessExpectation?.fulfill()
    }

    func onError(errorCode: String, message: String) {
        errorCount += 1
        lastErrorCode = errorCode
        lastErrorMessage = message
        onErrorExpectation?.fulfill()
    }
}

// MARK: - FirebaseSyncEngine Tests

class FirebaseSyncEngineTests: XCTestCase {

    var syncEngine: FirebaseSyncEngine!
    var mockCallback: MockSyncCallback!

    override func setUp() {
        super.setUp()
        mockCallback = MockSyncCallback()
    }

    override func tearDown() {
        syncEngine = nil
        mockCallback = nil
        super.tearDown()
    }

    // MARK: - Backoff Delay Tests

    /**
     * Test: calculateBackoffDelay(attempt: 1) ≈ 1000ms ±200ms
     *
     * Formula: baseDelay(1000) × 2^(1-1) ± jitter(200)
     * = 1000 × 1 ± 200
     * = 800 to 1200
     */
    func testCalculateBackoffDelayAttempt1() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        let delay = syncEngine.calculateBackoffDelay(attempt: 1)

        XCTAssertGreaterThanOrEqual(delay, 800,
            "Attempt 1 delay should be >= 800ms (1000 - 200 jitter)")
        XCTAssertLessThanOrEqual(delay, 1200,
            "Attempt 1 delay should be <= 1200ms (1000 + 200 jitter)")
    }

    /**
     * Test: calculateBackoffDelay(attempt: 2) ≈ 2000ms ±200ms
     *
     * Formula: baseDelay(1000) × 2^(2-1) ± jitter(200)
     * = 1000 × 2 ± 200
     * = 1800 to 2200
     */
    func testCalculateBackoffDelayAttempt2() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        let delay = syncEngine.calculateBackoffDelay(attempt: 2)

        XCTAssertGreaterThanOrEqual(delay, 1800,
            "Attempt 2 delay should be >= 1800ms (2000 - 200 jitter)")
        XCTAssertLessThanOrEqual(delay, 2200,
            "Attempt 2 delay should be <= 2200ms (2000 + 200 jitter)")
    }

    /**
     * Test: calculateBackoffDelay(attempt: 3) ≈ 4000ms ±200ms
     *
     * Formula: baseDelay(1000) × 2^(3-1) ± jitter(200)
     * = 1000 × 4 ± 200
     * = 3800 to 4200
     */
    func testCalculateBackoffDelayAttempt3() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        let delay = syncEngine.calculateBackoffDelay(attempt: 3)

        XCTAssertGreaterThanOrEqual(delay, 3800,
            "Attempt 3 delay should be >= 3800ms (4000 - 200 jitter)")
        XCTAssertLessThanOrEqual(delay, 4200,
            "Attempt 3 delay should be <= 4200ms (4000 + 200 jitter)")
    }

    /**
     * Test: delay is never negative
     *
     * The implementation uses max(0, ...) to ensure non-negative delays.
     * Even with negative jitter on attempt 1, the result should be >= 0.
     */
    func testDelayIsNeverNegative() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        // Run multiple times to account for random jitter
        for attempt in 1...10 {
            for _ in 0..<100 {
                let delay = syncEngine.calculateBackoffDelay(attempt: attempt)
                XCTAssertGreaterThanOrEqual(delay, 0,
                    "Delay should never be negative for attempt \(attempt)")
            }
        }
    }

    /**
     * Test: updateCurrentLocation with nil path calls onError
     *
     * When currentLocationPath is nil, the engine should immediately call onError
     * with "NO_PATH" error code.
     */
    func testUpdateCurrentLocationWithNilPathCallsOnError() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: nil, historyPath: "/history")

        let expectation = self.expectation(description: "onError should be called")
        mockCallback.onErrorExpectation = expectation

        syncEngine.updateCurrentLocation(
            latitude: 37.7749,
            longitude: -122.4194,
            timestamp: 1700000000000,
            accuracy: 10.0,
            speed: 5.0,
            callback: mockCallback
        )

        waitForExpectations(timeout: 1.0) { error in
            XCTAssertNil(error)
        }

        XCTAssertEqual(mockCallback.errorCount, 1, "onError should be called exactly once")
        XCTAssertEqual(mockCallback.lastErrorCode, "NO_PATH", "Error code should be NO_PATH")
        XCTAssertEqual(mockCallback.successCount, 0, "onSuccess should not be called")
    }

    /**
     * Test: pushHistoryBatch with empty array calls onSuccess
     *
     * When locations array is empty, the engine should immediately call onSuccess
     * without attempting a Firebase write.
     */
    func testPushHistoryBatchWithEmptyArrayCallsOnSuccess() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/current", historyPath: "/history")

        let expectation = self.expectation(description: "onSuccess should be called")
        mockCallback.onSuccessExpectation = expectation

        syncEngine.pushHistoryBatch(locations: [], callback: mockCallback)

        waitForExpectations(timeout: 1.0) { error in
            XCTAssertNil(error)
        }

        XCTAssertEqual(mockCallback.successCount, 1, "onSuccess should be called for empty batch")
        XCTAssertEqual(mockCallback.errorCount, 0, "onError should not be called for empty batch")
    }

    /**
     * Test: pushHistoryBatch with nil path calls onError
     *
     * When historyPath is nil, the engine should immediately call onError
     * with "NO_PATH" error code.
     */
    func testPushHistoryBatchWithNilPathCallsOnError() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/current", historyPath: nil)

        let expectation = self.expectation(description: "onError should be called")
        mockCallback.onErrorExpectation = expectation

        let locations: [[String: Any]] = [
            ["latitude": 37.7749, "longitude": -122.4194, "timestamp": 1700000000000]
        ]

        syncEngine.pushHistoryBatch(locations: locations, callback: mockCallback)

        waitForExpectations(timeout: 1.0) { error in
            XCTAssertNil(error)
        }

        XCTAssertEqual(mockCallback.errorCount, 1, "onError should be called exactly once")
        XCTAssertEqual(mockCallback.lastErrorCode, "NO_PATH", "Error code should be NO_PATH")
        XCTAssertEqual(mockCallback.successCount, 0, "onSuccess should not be called")
    }

    /**
     * Test: jitter produces variation across multiple calls
     *
     * Running calculateBackoffDelay multiple times for the same attempt should produce
     * different values due to random jitter (±200ms range).
     */
    func testJitterProducesVariation() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        var delays: Set<Int> = []

        // Run 50 times — with ±200ms jitter range (401 possible values), we should see variation
        for _ in 0..<50 {
            let delay = syncEngine.calculateBackoffDelay(attempt: 1)
            delays.insert(delay)
        }

        // With 401 possible values and 50 samples, we should get at least 2 distinct values
        XCTAssertGreaterThan(delays.count, 1,
            "Jitter should produce variation across multiple calls. Got \(delays.count) unique values from 50 samples")
    }

    /**
     * Test: backoff grows exponentially
     *
     * Verify that higher attempts produce larger delays (on average).
     */
    func testBackoffGrowsExponentially() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        // Calculate average delay for each attempt over multiple samples
        let samples = 100
        var avgDelay1: Double = 0
        var avgDelay2: Double = 0
        var avgDelay3: Double = 0

        for _ in 0..<samples {
            avgDelay1 += Double(syncEngine.calculateBackoffDelay(attempt: 1))
            avgDelay2 += Double(syncEngine.calculateBackoffDelay(attempt: 2))
            avgDelay3 += Double(syncEngine.calculateBackoffDelay(attempt: 3))
        }

        avgDelay1 /= Double(samples)
        avgDelay2 /= Double(samples)
        avgDelay3 /= Double(samples)

        XCTAssertLessThan(avgDelay1, avgDelay2, "Attempt 2 should have higher average delay than attempt 1")
        XCTAssertLessThan(avgDelay2, avgDelay3, "Attempt 3 should have higher average delay than attempt 2")

        // Verify approximate 2x growth
        let ratio2to1 = avgDelay2 / avgDelay1
        let ratio3to2 = avgDelay3 / avgDelay2

        XCTAssertGreaterThan(ratio2to1, 1.5, "Delay should roughly double between attempts 1 and 2")
        XCTAssertLessThan(ratio2to1, 2.5, "Delay should roughly double between attempts 1 and 2")
        XCTAssertGreaterThan(ratio3to2, 1.5, "Delay should roughly double between attempts 2 and 3")
        XCTAssertLessThan(ratio3to2, 2.5, "Delay should roughly double between attempts 2 and 3")
    }

    /**
     * Test: attempt 4 delay ≈ 8000ms ±200ms
     *
     * Formula: 1000 × 2^(4-1) = 8000 ± 200
     */
    func testCalculateBackoffDelayAttempt4() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        let delay = syncEngine.calculateBackoffDelay(attempt: 4)

        XCTAssertGreaterThanOrEqual(delay, 7800, "Attempt 4 delay should be >= 7800ms")
        XCTAssertLessThanOrEqual(delay, 8200, "Attempt 4 delay should be <= 8200ms")
    }

    /**
     * Test: attempt 5 delay ≈ 16000ms ±200ms
     *
     * Formula: 1000 × 2^(5-1) = 16000 ± 200
     */
    func testCalculateBackoffDelayAttempt5() {
        syncEngine = FirebaseSyncEngine(service: "RTDB", currentLocationPath: "/test", historyPath: "/history")

        let delay = syncEngine.calculateBackoffDelay(attempt: 5)

        XCTAssertGreaterThanOrEqual(delay, 15800, "Attempt 5 delay should be >= 15800ms")
        XCTAssertLessThanOrEqual(delay, 16200, "Attempt 5 delay should be <= 16200ms")
    }
}
