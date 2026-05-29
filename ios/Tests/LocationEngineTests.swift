/**
 * LocationEngineTests.swift
 *
 * Unit tests for LocationEngine — testing instantiation, protocol, and safe method calls.
 * Tests focus on the LocationUpdateDelegate protocol and safe lifecycle management.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: CLLocationManager requires a device/simulator for actual location updates.
 * These tests focus on logic that can be tested without GPS hardware:
 * - LocationUpdateDelegate protocol conformance
 * - LocationEngine instantiation
 * - Safe stop before start
 * - Delegate weak reference behavior
 */

import XCTest
import CoreLocation
@testable import LiveTracking

// MARK: - Mock LocationUpdateDelegate

class MockLocationUpdateDelegate: LocationUpdateDelegate {
    var locationsReceived: [CLLocation] = []

    func onLocationReceived(location: CLLocation) {
        locationsReceived.append(location)
    }
}

// MARK: - LocationEngine Tests

class LocationEngineTests: XCTestCase {

    var locationEngine: LocationEngine!
    var mockDelegate: MockLocationUpdateDelegate!

    override func setUp() {
        super.setUp()
        locationEngine = LocationEngine()
        mockDelegate = MockLocationUpdateDelegate()
        locationEngine.delegate = mockDelegate
    }

    override func tearDown() {
        locationEngine.stopLocationUpdates()
        locationEngine = nil
        mockDelegate = nil
        super.tearDown()
    }

    // MARK: - Test: LocationUpdateDelegate protocol has correct method

    func testLocationUpdateDelegateOnLocationReceivedMethod() {
        // Verify the protocol method exists and can be called
        let testLocation = CLLocation(latitude: 37.7749, longitude: -122.4194)
        mockDelegate.onLocationReceived(location: testLocation)

        XCTAssertEqual(mockDelegate.locationsReceived.count, 1,
                       "onLocationReceived should be callable")
        XCTAssertEqual(mockDelegate.locationsReceived.first?.coordinate.latitude, 37.7749,
                       accuracy: 0.0001, "Location latitude should match")
        XCTAssertEqual(mockDelegate.locationsReceived.first?.coordinate.longitude, -122.4194,
                       accuracy: 0.0001, "Location longitude should match")
    }

    func testLocationUpdateDelegateReceivesMultipleLocations() {
        let locations = [
            CLLocation(latitude: 37.7749, longitude: -122.4194),
            CLLocation(latitude: 40.7128, longitude: -74.0060),
            CLLocation(latitude: 51.5074, longitude: -0.1278)
        ]

        for location in locations {
            mockDelegate.onLocationReceived(location: location)
        }

        XCTAssertEqual(mockDelegate.locationsReceived.count, 3,
                       "Delegate should receive all locations")
    }

    // MARK: - Test: LocationEngine can be instantiated

    func testLocationEngineCanBeInstantiated() {
        let engine = LocationEngine()
        XCTAssertNotNil(engine, "LocationEngine should be instantiable")
    }

    func testLocationEngineIsNSObject() {
        // LocationEngine inherits from NSObject for CLLocationManagerDelegate
        XCTAssertTrue(locationEngine is NSObject,
                      "LocationEngine should be an NSObject subclass")
    }

    func testLocationEngineConformsToCLLocationManagerDelegate() {
        // Verify LocationEngine conforms to CLLocationManagerDelegate
        XCTAssertTrue(locationEngine is CLLocationManagerDelegate,
                      "LocationEngine should conform to CLLocationManagerDelegate")
    }

    // MARK: - Test: stopLocationUpdates doesn't crash when called before start

    func testStopLocationUpdatesBeforeStartDoesNotCrash() {
        // Calling stop before start should be safe (no-op on CLLocationManager)
        locationEngine.stopLocationUpdates()

        // Should not crash — this verifies defensive coding
    }

    func testMultipleStopCallsDontCrash() {
        locationEngine.stopLocationUpdates()
        locationEngine.stopLocationUpdates()
        locationEngine.stopLocationUpdates()

        // Should not crash
    }

    // MARK: - Test: Delegate is weak reference

    func testDelegateIsWeakReference() {
        var delegate: MockLocationUpdateDelegate? = MockLocationUpdateDelegate()
        locationEngine.delegate = delegate

        XCTAssertNotNil(locationEngine.delegate)

        // Release the delegate
        delegate = nil

        // Delegate should be nil (weak reference)
        XCTAssertNil(locationEngine.delegate, "Delegate should be a weak reference")
    }

    // MARK: - Test: startLocationUpdates with default accuracy

    func testStartLocationUpdatesWithDefaultAccuracy() {
        // This test verifies the method signature exists and can be called.
        // On a simulator/device, this would actually start location updates.
        // In unit tests, we just verify it doesn't crash.
        locationEngine.startLocationUpdates(intervalMs: 5000, distanceFilter: 10.0)

        // Clean up
        locationEngine.stopLocationUpdates()
    }

    // MARK: - Test: startLocationUpdates with custom accuracy

    func testStartLocationUpdatesWithCustomAccuracy() {
        // Verify the overloaded method with accuracy parameter exists and can be called
        locationEngine.startLocationUpdates(
            intervalMs: 5000,
            distanceFilter: 10.0,
            accuracy: kCLLocationAccuracyKilometer
        )

        // Clean up
        locationEngine.stopLocationUpdates()
    }

    func testStartLocationUpdatesWithBestAccuracy() {
        locationEngine.startLocationUpdates(
            intervalMs: 1000,
            distanceFilter: 5.0,
            accuracy: kCLLocationAccuracyBest
        )

        // Clean up
        locationEngine.stopLocationUpdates()
    }

    // MARK: - Test: Start then stop lifecycle

    func testStartThenStopLifecycle() {
        locationEngine.startLocationUpdates(intervalMs: 5000, distanceFilter: 10.0)
        locationEngine.stopLocationUpdates()

        // Should complete without crash
    }

    func testMultipleStartStopCycles() {
        for _ in 0..<5 {
            locationEngine.startLocationUpdates(intervalMs: 5000, distanceFilter: 10.0)
            locationEngine.stopLocationUpdates()
        }

        // Should complete without crash
    }

    // MARK: - Test: CLLocationManagerDelegate method handling

    func testDidUpdateLocationsCallsDelegate() {
        // Simulate CLLocationManager calling the delegate method
        let testLocation = CLLocation(latitude: 48.8566, longitude: 2.3522)
        let locationManager = CLLocationManager()

        // Call the delegate method directly
        locationEngine.locationManager(locationManager, didUpdateLocations: [testLocation])

        XCTAssertEqual(mockDelegate.locationsReceived.count, 1,
                       "Delegate should receive the location")
        XCTAssertEqual(mockDelegate.locationsReceived.first?.coordinate.latitude, 48.8566,
                       accuracy: 0.0001)
        XCTAssertEqual(mockDelegate.locationsReceived.first?.coordinate.longitude, 2.3522,
                       accuracy: 0.0001)
    }

    func testDidUpdateLocationsUsesLastLocation() {
        // When multiple locations are delivered, only the last one should be forwarded
        let locations = [
            CLLocation(latitude: 37.0, longitude: -122.0),
            CLLocation(latitude: 38.0, longitude: -121.0),
            CLLocation(latitude: 39.0, longitude: -120.0)
        ]
        let locationManager = CLLocationManager()

        locationEngine.locationManager(locationManager, didUpdateLocations: locations)

        XCTAssertEqual(mockDelegate.locationsReceived.count, 1,
                       "Should only forward the last location")
        XCTAssertEqual(mockDelegate.locationsReceived.first?.coordinate.latitude, 39.0,
                       accuracy: 0.0001, "Should use the last location in the array")
    }

    func testDidUpdateLocationsWithEmptyArrayDoesNotCallDelegate() {
        let locationManager = CLLocationManager()

        locationEngine.locationManager(locationManager, didUpdateLocations: [])

        XCTAssertEqual(mockDelegate.locationsReceived.count, 0,
                       "Should not call delegate with empty locations array")
    }

    func testDidFailWithErrorDoesNotCrash() {
        let locationManager = CLLocationManager()
        let error = NSError(domain: kCLErrorDomain, code: CLError.denied.rawValue, userInfo: nil)

        // Should not crash — just logs the error
        locationEngine.locationManager(locationManager, didFailWithError: error)
    }
}
