/**
 * ActivityRecognitionHandlerTests.swift
 *
 * Unit tests for ActivityRecognitionHandler — testing activity detection state and defaults.
 * Tests focus on enum cases, default values, and initial state.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: CMMotionActivityManager requires device hardware (M-series coprocessor).
 * These tests focus on logic that can be tested without hardware:
 * - ActivityType enum cases
 * - Default threshold values
 * - Initial state of the handler
 * - ActivityStateDelegate protocol conformance
 */

import XCTest
@testable import LiveTracking

// MARK: - Mock ActivityStateDelegate

class MockActivityStateDelegate: ActivityStateDelegate {
    var activityChangedCount = 0
    var lastActivity: ActivityType?
    var stationaryDurationExceededCount = 0
    var lastDurationMs: Int64?

    func onActivityChanged(activity: ActivityType) {
        activityChangedCount += 1
        lastActivity = activity
    }

    func onStationaryDurationExceeded(durationMs: Int64) {
        stationaryDurationExceededCount += 1
        lastDurationMs = durationMs
    }
}

// MARK: - ActivityRecognitionHandler Tests

class ActivityRecognitionHandlerTests: XCTestCase {

    var handler: ActivityRecognitionHandler!
    var mockDelegate: MockActivityStateDelegate!

    override func setUp() {
        super.setUp()
        handler = ActivityRecognitionHandler()
        mockDelegate = MockActivityStateDelegate()
        handler.delegate = mockDelegate
    }

    override func tearDown() {
        handler = nil
        mockDelegate = nil
        super.tearDown()
    }

    // MARK: - Test: ActivityType enum has correct cases

    func testActivityTypeHasStationaryCase() {
        let activity: ActivityType = .stationary
        switch activity {
        case .stationary:
            // Expected
            break
        default:
            XCTFail("Should match .stationary case")
        }
    }

    func testActivityTypeHasWalkingCase() {
        let activity: ActivityType = .walking
        switch activity {
        case .walking:
            // Expected
            break
        default:
            XCTFail("Should match .walking case")
        }
    }

    func testActivityTypeHasAutomotiveCase() {
        let activity: ActivityType = .automotive
        switch activity {
        case .automotive:
            // Expected
            break
        default:
            XCTFail("Should match .automotive case")
        }
    }

    func testActivityTypeHasUnknownCase() {
        let activity: ActivityType = .unknown
        switch activity {
        case .unknown:
            // Expected
            break
        default:
            XCTFail("Should match .unknown case")
        }
    }

    func testActivityTypeAllCasesAreDistinct() {
        // Verify all cases can be distinguished via switch
        let activities: [ActivityType] = [.stationary, .walking, .automotive, .unknown]

        var stationaryCount = 0
        var walkingCount = 0
        var automotiveCount = 0
        var unknownCount = 0

        for activity in activities {
            switch activity {
            case .stationary: stationaryCount += 1
            case .walking: walkingCount += 1
            case .automotive: automotiveCount += 1
            case .unknown: unknownCount += 1
            }
        }

        XCTAssertEqual(stationaryCount, 1, "Should have exactly one .stationary")
        XCTAssertEqual(walkingCount, 1, "Should have exactly one .walking")
        XCTAssertEqual(automotiveCount, 1, "Should have exactly one .automotive")
        XCTAssertEqual(unknownCount, 1, "Should have exactly one .unknown")
    }

    // MARK: - Test: stationaryThresholdMs default is 180_000

    func testStationaryThresholdMsDefaultIs180000() {
        XCTAssertEqual(handler.stationaryThresholdMs, 180_000,
                       "Default stationaryThresholdMs should be 180,000 ms (3 minutes)")
    }

    func testStationaryThresholdMsCanBeModified() {
        handler.stationaryThresholdMs = 60_000 // 1 minute

        XCTAssertEqual(handler.stationaryThresholdMs, 60_000,
                       "stationaryThresholdMs should be modifiable")
    }

    // MARK: - Test: isDeviceStationary returns false initially

    func testIsDeviceStationaryReturnsFalseInitially() {
        XCTAssertFalse(handler.isDeviceStationary(),
                       "isDeviceStationary should return false before any activity updates")
    }

    // MARK: - Test: getCurrentActivity returns unknown initially

    func testGetCurrentActivityReturnsUnknownInitially() {
        let activity = handler.getCurrentActivity()

        switch activity {
        case .unknown:
            // Expected
            break
        default:
            XCTFail("getCurrentActivity should return .unknown initially, got \(activity)")
        }
    }

    // MARK: - Test: getStationaryDurationMs returns 0 initially

    func testGetStationaryDurationMsReturnsZeroInitially() {
        let duration = handler.getStationaryDurationMs()
        XCTAssertEqual(duration, 0,
                       "getStationaryDurationMs should return 0 when not stationary")
    }

    // MARK: - Test: ActivityStateDelegate protocol has correct methods

    func testActivityStateDelegateOnActivityChangedMethod() {
        // Verify the delegate protocol method exists and can be called
        mockDelegate.onActivityChanged(activity: .walking)

        XCTAssertEqual(mockDelegate.activityChangedCount, 1)
        XCTAssertNotNil(mockDelegate.lastActivity)

        if let lastActivity = mockDelegate.lastActivity {
            switch lastActivity {
            case .walking:
                // Expected
                break
            default:
                XCTFail("Delegate should receive .walking activity")
            }
        }
    }

    func testActivityStateDelegateOnStationaryDurationExceededMethod() {
        // Verify the delegate protocol method exists and can be called
        mockDelegate.onStationaryDurationExceeded(durationMs: 200_000)

        XCTAssertEqual(mockDelegate.stationaryDurationExceededCount, 1)
        XCTAssertEqual(mockDelegate.lastDurationMs, 200_000)
    }

    // MARK: - Test: Handler can be instantiated

    func testHandlerCanBeInstantiated() {
        let newHandler = ActivityRecognitionHandler()
        XCTAssertNotNil(newHandler, "ActivityRecognitionHandler should be instantiable")
    }

    // MARK: - Test: stopActivityRecognition resets state

    func testStopActivityRecognitionResetsState() {
        // Stop should reset internal state without crashing
        handler.stopActivityRecognition()

        XCTAssertFalse(handler.isDeviceStationary(),
                       "isDeviceStationary should be false after stop")
        XCTAssertEqual(handler.getStationaryDurationMs(), 0,
                       "Stationary duration should be 0 after stop")

        let activity = handler.getCurrentActivity()
        switch activity {
        case .unknown:
            // Expected after reset
            break
        default:
            XCTFail("getCurrentActivity should return .unknown after stop")
        }
    }

    // MARK: - Test: Multiple stop calls don't crash

    func testMultipleStopCallsDontCrash() {
        handler.stopActivityRecognition()
        handler.stopActivityRecognition()
        handler.stopActivityRecognition()

        // Should not crash
        XCTAssertFalse(handler.isDeviceStationary())
    }

    // MARK: - Test: Delegate is weak reference

    func testDelegateIsWeakReference() {
        var delegate: MockActivityStateDelegate? = MockActivityStateDelegate()
        handler.delegate = delegate

        // Verify delegate is set
        XCTAssertNotNil(handler.delegate)

        // Release the delegate
        delegate = nil

        // Delegate should be nil (weak reference)
        XCTAssertNil(handler.delegate, "Delegate should be a weak reference")
    }
}
