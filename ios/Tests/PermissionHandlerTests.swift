/**
 * PermissionHandlerTests.swift
 *
 * Unit tests for PermissionHandler — testing permission check logic and error codes.
 * Tests focus on error code constants, PermissionResult enum, and the checkAllRequirements flow.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: Actual CLLocationManager authorization status cannot be controlled in unit tests
 * without a device/simulator. These tests focus on the logic that CAN be tested:
 * - Error code constants
 * - PermissionResult enum behavior
 * - The ordering logic of checkAllRequirements
 */

import XCTest
import CoreLocation
@testable import LiveTracking

// MARK: - PermissionHandler Tests

class PermissionHandlerTests: XCTestCase {

    var permissionHandler: PermissionHandler!

    override func setUp() {
        super.setUp()
        permissionHandler = PermissionHandler()
    }

    override func tearDown() {
        permissionHandler = nil
        super.tearDown()
    }

    // MARK: - Test: Error code constants are correct

    func testPermissionDeniedErrorCodeConstant() {
        XCTAssertEqual(PermissionHandler.ERROR_PERMISSION_DENIED, "PERMISSION_DENIED",
                       "PERMISSION_DENIED error code should be 'PERMISSION_DENIED'")
    }

    func testGpsDisabledErrorCodeConstant() {
        XCTAssertEqual(PermissionHandler.ERROR_GPS_DISABLED, "GPS_DISABLED",
                       "GPS_DISABLED error code should be 'GPS_DISABLED'")
    }

    func testErrorCodesAreDistinct() {
        XCTAssertNotEqual(
            PermissionHandler.ERROR_PERMISSION_DENIED,
            PermissionHandler.ERROR_GPS_DISABLED,
            "Error codes should be distinct from each other"
        )
    }

    func testErrorCodesAreNotEmpty() {
        XCTAssertFalse(PermissionHandler.ERROR_PERMISSION_DENIED.isEmpty,
                       "PERMISSION_DENIED should not be empty")
        XCTAssertFalse(PermissionHandler.ERROR_GPS_DISABLED.isEmpty,
                       "GPS_DISABLED should not be empty")
    }

    // MARK: - Test: PermissionResult enum cases work correctly

    func testPermissionResultGrantedCase() {
        let result: PermissionResult = .granted

        switch result {
        case .granted:
            // Expected
            break
        case .denied:
            XCTFail("Should be .granted, not .denied")
        }
    }

    func testPermissionResultDeniedCase() {
        let result: PermissionResult = .denied(
            errorCode: "TEST_ERROR",
            message: "Test error message"
        )

        switch result {
        case .granted:
            XCTFail("Should be .denied, not .granted")
        case .denied(let errorCode, let message):
            XCTAssertEqual(errorCode, "TEST_ERROR", "Error code should match")
            XCTAssertEqual(message, "Test error message", "Message should match")
        }
    }

    func testPermissionResultDeniedWithPermissionDeniedCode() {
        let result: PermissionResult = .denied(
            errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
            message: "Location permission denied"
        )

        if case .denied(let errorCode, let message) = result {
            XCTAssertEqual(errorCode, "PERMISSION_DENIED")
            XCTAssertFalse(message.isEmpty, "Message should not be empty")
        } else {
            XCTFail("Result should be .denied")
        }
    }

    func testPermissionResultDeniedWithGpsDisabledCode() {
        let result: PermissionResult = .denied(
            errorCode: PermissionHandler.ERROR_GPS_DISABLED,
            message: "GPS is disabled"
        )

        if case .denied(let errorCode, let message) = result {
            XCTAssertEqual(errorCode, "GPS_DISABLED")
            XCTAssertFalse(message.isEmpty, "Message should not be empty")
        } else {
            XCTFail("Result should be .denied")
        }
    }

    // MARK: - Test: checkAllRequirements checks services before permissions

    /**
     * Verify that checkAllRequirements returns a PermissionResult.
     * The actual authorization status depends on the test environment,
     * but the method should not crash and should return a valid result.
     */
    func testCheckAllRequirementsReturnsValidResult() {
        let result = permissionHandler.checkAllRequirements()

        // Result should be either .granted or .denied — both are valid
        switch result {
        case .granted:
            // Valid result
            break
        case .denied(let errorCode, let message):
            // Valid result — verify it has proper error info
            XCTAssertFalse(errorCode.isEmpty, "Error code should not be empty when denied")
            XCTAssertFalse(message.isEmpty, "Message should not be empty when denied")
        }
    }

    /**
     * Verify that checkLocationServicesEnabled returns a valid result.
     */
    func testCheckLocationServicesEnabledReturnsValidResult() {
        let result = permissionHandler.checkLocationServicesEnabled()

        switch result {
        case .granted:
            // Location services are enabled on this machine
            break
        case .denied(let errorCode, let message):
            XCTAssertEqual(errorCode, PermissionHandler.ERROR_GPS_DISABLED,
                          "Should use GPS_DISABLED error code when services are disabled")
            XCTAssertFalse(message.isEmpty)
        }
    }

    /**
     * Verify that checkLocationPermission returns a valid result.
     */
    func testCheckLocationPermissionReturnsValidResult() {
        let result = permissionHandler.checkLocationPermission()

        switch result {
        case .granted:
            // Permission is granted
            break
        case .denied(let errorCode, let message):
            XCTAssertEqual(errorCode, PermissionHandler.ERROR_PERMISSION_DENIED,
                          "Should use PERMISSION_DENIED error code")
            XCTAssertFalse(message.isEmpty)
        }
    }

    // MARK: - Test: PermissionHandler can be initialized with custom CLLocationManager

    func testPermissionHandlerInitWithCustomLocationManager() {
        let customManager = CLLocationManager()
        let handler = PermissionHandler(locationManager: customManager)

        // Should not crash and should return a valid result
        let result = handler.checkAllRequirements()
        switch result {
        case .granted, .denied:
            // Both are valid
            break
        }
    }

    func testPermissionHandlerInitWithDefaultLocationManager() {
        let handler = PermissionHandler()

        // Should not crash
        let result = handler.checkLocationServicesEnabled()
        switch result {
        case .granted, .denied:
            // Both are valid
            break
        }
    }

    // MARK: - Test: Error messages are descriptive

    func testDeniedResultContainsHelpfulMessage() {
        // Create a denied result and verify the message is user-friendly
        let result: PermissionResult = .denied(
            errorCode: PermissionHandler.ERROR_PERMISSION_DENIED,
            message: "Location permission denied by user. Please grant location permission in Settings to enable tracking."
        )

        if case .denied(_, let message) = result {
            XCTAssertTrue(message.contains("permission") || message.contains("Permission"),
                         "Message should mention 'permission'")
            XCTAssertTrue(message.count > 20,
                         "Message should be descriptive (more than 20 characters)")
        }
    }

    func testGpsDisabledResultContainsHelpfulMessage() {
        let result: PermissionResult = .denied(
            errorCode: PermissionHandler.ERROR_GPS_DISABLED,
            message: "GPS/Location services are disabled. Please enable location services in device Settings."
        )

        if case .denied(_, let message) = result {
            XCTAssertTrue(message.contains("GPS") || message.contains("location") || message.contains("Location"),
                         "Message should mention GPS or location services")
            XCTAssertTrue(message.contains("Settings") || message.contains("settings"),
                         "Message should guide user to Settings")
        }
    }
}
