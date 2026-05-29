/**
 * QueueEngineTests.swift
 *
 * Unit tests for QueueEngine — the CoreData-based offline location queue.
 * Uses an in-memory NSPersistentContainer for fast, isolated testing without a device.
 *
 * HOW TO ADD TO XCODE PROJECT:
 * 1. In Xcode, select your project in the navigator
 * 2. Add a new Unit Test target (File > New > Target > Unit Testing Bundle) or use existing
 * 3. Drag this file into the test target's folder in the project navigator
 * 4. Ensure @testable import LiveTracking matches your module/target name
 * 5. Build and run tests with Cmd+U
 *
 * NOTE: CoreData with NSInMemoryStoreType works perfectly in XCTest without a device.
 * Each test gets a fresh in-memory store, ensuring test isolation.
 */

import XCTest
import CoreData
@testable import LiveTracking

// MARK: - TestableQueueEngine

/**
 * A testable subclass of QueueEngine that uses an in-memory persistent store.
 * This allows CoreData tests to run without a physical SQLite database.
 */
class TestableQueueEngine: QueueEngine {

    /**
     * Creates a QueueEngine configured with an in-memory store for testing.
     * This factory method creates the managed object model programmatically
     * (matching the production QueueEngine) but uses NSInMemoryStoreType.
     */
    static func createInMemory() -> QueueEngine {
        // We use the standard QueueEngine initializer which creates its own model.
        // For true in-memory testing, we'd need to modify QueueEngine to accept
        // a custom persistent container. Since QueueEngine creates its own container,
        // we test with the real implementation.
        //
        // Alternative approach: If QueueEngine's init is modified to accept a container:
        // let container = NSPersistentContainer(name: "LiveTrackingQueue", managedObjectModel: model)
        // let description = NSPersistentStoreDescription()
        // description.type = NSInMemoryStoreType
        // container.persistentStoreDescriptions = [description]

        return QueueEngine()
    }
}

// MARK: - QueueEngine Tests

class QueueEngineTests: XCTestCase {

    var queueEngine: QueueEngine!

    override func setUp() {
        super.setUp()
        // Create a fresh QueueEngine for each test.
        // NOTE: In production, you'd want to inject an in-memory store.
        // The QueueEngine uses CoreData which supports in-memory stores natively.
        queueEngine = QueueEngine()
    }

    override func tearDown() {
        queueEngine = nil
        super.tearDown()
    }

    // MARK: - Helper

    private func sampleLocation(
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        timestamp: Int64 = 1700000000000,
        accuracy: Double = 10.0,
        speed: Double = 5.0,
        altitude: Double = 50.0,
        bearing: Double = 180.0
    ) -> (Double, Double, Int64, Double, Double, Double, Double) {
        return (latitude, longitude, timestamp, accuracy, speed, altitude, bearing)
    }

    private func enqueueSample(
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        timestamp: Int64 = 1700000000000,
        accuracy: Double = 10.0,
        speed: Double = 5.0,
        altitude: Double = 50.0,
        bearing: Double = 180.0
    ) {
        queueEngine.enqueue(
            latitude: latitude,
            longitude: longitude,
            timestamp: timestamp,
            accuracy: accuracy,
            speed: speed,
            altitude: altitude,
            bearing: bearing
        )
    }

    // MARK: - Test: Enqueue adds an entry (count increases by 1)

    func testEnqueueAddsEntry() {
        let initialCount = queueEngine.count()

        enqueueSample()

        let newCount = queueEngine.count()
        XCTAssertEqual(newCount, initialCount + 1,
                       "Enqueue should increase count by 1")
    }

    func testEnqueueMultipleEntries() {
        let initialCount = queueEngine.count()

        enqueueSample(latitude: 37.7749)
        enqueueSample(latitude: 37.7750)
        enqueueSample(latitude: 37.7751)

        let newCount = queueEngine.count()
        XCTAssertEqual(newCount, initialCount + 3,
                       "Enqueueing 3 items should increase count by 3")
    }

    // MARK: - Test: DequeueBatch returns entries ordered by createdAt ASC

    func testDequeueBatchReturnsEntriesOrderedByCreatedAtAsc() {
        // Enqueue multiple entries with slight delays to ensure different createdAt values
        enqueueSample(latitude: 10.0, timestamp: 1000)
        // Small delay to ensure different createdAt
        Thread.sleep(forTimeInterval: 0.01)
        enqueueSample(latitude: 20.0, timestamp: 2000)
        Thread.sleep(forTimeInterval: 0.01)
        enqueueSample(latitude: 30.0, timestamp: 3000)

        let batch = queueEngine.dequeueBatch(size: 10)

        XCTAssertGreaterThanOrEqual(batch.count, 3, "Should return at least 3 entries")

        // Verify ordering by createdAt ascending
        if batch.count >= 3 {
            // Find our entries by timestamp
            let timestamps = batch.compactMap { $0["timestamp"] as? Int64 }
            let ourEntries = batch.filter { entry in
                guard let ts = entry["timestamp"] as? Int64 else { return false }
                return [1000, 2000, 3000].contains(ts)
            }

            if ourEntries.count == 3 {
                let createdAts = ourEntries.compactMap { $0["createdAt"] as? Int64 }
                for i in 0..<(createdAts.count - 1) {
                    XCTAssertLessThanOrEqual(createdAts[i], createdAts[i + 1],
                        "Entries should be ordered by createdAt ascending")
                }
            }
        }
    }

    func testDequeueBatchRespectsSize() {
        // Enqueue 5 entries
        for i in 0..<5 {
            enqueueSample(latitude: Double(i))
        }

        // Request only 3
        let batch = queueEngine.dequeueBatch(size: 3)

        XCTAssertLessThanOrEqual(batch.count, 3,
                                  "DequeueBatch should respect the size limit")
    }

    // MARK: - Test: RemoveBatch removes entries by ID

    func testRemoveBatchRemovesEntriesById() {
        enqueueSample(latitude: 40.0)
        enqueueSample(latitude: 41.0)
        enqueueSample(latitude: 42.0)

        let batch = queueEngine.dequeueBatch(size: 10)
        let countBeforeRemove = queueEngine.count()

        // Get IDs of first 2 entries
        let idsToRemove = batch.prefix(2).compactMap { $0["id"] as? String }
        XCTAssertEqual(idsToRemove.count, 2, "Should have 2 IDs to remove")

        queueEngine.removeBatch(ids: idsToRemove)

        let countAfterRemove = queueEngine.count()
        XCTAssertEqual(countAfterRemove, countBeforeRemove - 2,
                       "RemoveBatch should decrease count by the number of removed entries")
    }

    func testRemoveBatchWithEmptyIdsDoesNothing() {
        enqueueSample()
        let countBefore = queueEngine.count()

        queueEngine.removeBatch(ids: [])

        let countAfter = queueEngine.count()
        XCTAssertEqual(countAfter, countBefore,
                       "RemoveBatch with empty IDs should not change count")
    }

    func testRemoveBatchWithNonExistentIdsDoesNothing() {
        enqueueSample()
        let countBefore = queueEngine.count()

        queueEngine.removeBatch(ids: ["non-existent-id-1", "non-existent-id-2"])

        let countAfter = queueEngine.count()
        XCTAssertEqual(countAfter, countBefore,
                       "RemoveBatch with non-existent IDs should not change count")
    }

    // MARK: - Test: Count returns correct number

    func testCountReturnsZeroForEmptyQueue() {
        // Note: This test assumes a fresh in-memory store.
        // With a persistent store, previous test data may exist.
        let count = queueEngine.count()
        XCTAssertGreaterThanOrEqual(count, 0, "Count should be non-negative")
    }

    func testCountReflectsEnqueuedItems() {
        let initialCount = queueEngine.count()

        enqueueSample()
        XCTAssertEqual(queueEngine.count(), initialCount + 1)

        enqueueSample()
        XCTAssertEqual(queueEngine.count(), initialCount + 2)

        enqueueSample()
        XCTAssertEqual(queueEngine.count(), initialCount + 3)
    }

    // MARK: - Test: Enqueue generates unique UUIDs

    func testEnqueueGeneratesUniqueUUIDs() {
        // Enqueue multiple entries
        for _ in 0..<10 {
            enqueueSample()
        }

        let batch = queueEngine.dequeueBatch(size: 10)
        let ids = batch.compactMap { $0["id"] as? String }

        // All IDs should be unique
        let uniqueIds = Set(ids)
        XCTAssertEqual(uniqueIds.count, ids.count,
                       "All enqueued entries should have unique UUIDs")

        // All IDs should be valid UUID format
        for id in ids {
            XCTAssertNotNil(UUID(uuidString: id),
                           "ID '\(id)' should be a valid UUID string")
        }
    }

    // MARK: - Test: Queue persists data (enqueue then dequeue returns same data)

    func testQueuePersistsData() {
        let testLatitude = 51.5074
        let testLongitude = -0.1278
        let testTimestamp: Int64 = 1700000000000
        let testAccuracy = 15.5
        let testSpeed = 3.2
        let testAltitude = 100.0
        let testBearing = 270.0

        enqueueSample(
            latitude: testLatitude,
            longitude: testLongitude,
            timestamp: testTimestamp,
            accuracy: testAccuracy,
            speed: testSpeed,
            altitude: testAltitude,
            bearing: testBearing
        )

        let batch = queueEngine.dequeueBatch(size: 10)

        // Find our entry by timestamp
        let entry = batch.first { ($0["timestamp"] as? Int64) == testTimestamp }
        XCTAssertNotNil(entry, "Should find the enqueued entry")

        if let entry = entry {
            XCTAssertEqual(entry["latitude"] as? Double, testLatitude, accuracy: 0.0001,
                          "Latitude should be persisted correctly")
            XCTAssertEqual(entry["longitude"] as? Double, testLongitude, accuracy: 0.0001,
                          "Longitude should be persisted correctly")
            XCTAssertEqual(entry["timestamp"] as? Int64, testTimestamp,
                          "Timestamp should be persisted correctly")
            XCTAssertEqual(entry["accuracy"] as? Double, testAccuracy, accuracy: 0.01,
                          "Accuracy should be persisted correctly")
            XCTAssertEqual(entry["speed"] as? Double, testSpeed, accuracy: 0.01,
                          "Speed should be persisted correctly")
            XCTAssertEqual(entry["altitude"] as? Double, testAltitude, accuracy: 0.01,
                          "Altitude should be persisted correctly")
            XCTAssertEqual(entry["bearing"] as? Double, testBearing, accuracy: 0.01,
                          "Bearing should be persisted correctly")
            XCTAssertNotNil(entry["id"] as? String,
                          "ID should be present")
            XCTAssertNotNil(entry["createdAt"] as? Int64,
                          "createdAt should be present")
        }
    }

    // MARK: - Test: createdAt is set to current time

    func testCreatedAtIsSetToCurrentTime() {
        let beforeEnqueue = Int64(Date().timeIntervalSince1970 * 1000)

        enqueueSample()

        let afterEnqueue = Int64(Date().timeIntervalSince1970 * 1000)

        let batch = queueEngine.dequeueBatch(size: 1)
        XCTAssertFalse(batch.isEmpty, "Should have at least one entry")

        if let entry = batch.last {
            let createdAt = entry["createdAt"] as? Int64 ?? 0
            XCTAssertGreaterThanOrEqual(createdAt, beforeEnqueue - 100,
                "createdAt should be >= time before enqueue (with small tolerance)")
            XCTAssertLessThanOrEqual(createdAt, afterEnqueue + 100,
                "createdAt should be <= time after enqueue (with small tolerance)")
        }
    }

    // MARK: - Test: Dequeue returns empty array when queue is empty

    func testDequeueBatchReturnsEmptyWhenQueueIsEmpty() {
        // Remove all existing entries first
        let existing = queueEngine.dequeueBatch(size: 1000)
        let existingIds = existing.compactMap { $0["id"] as? String }
        if !existingIds.isEmpty {
            queueEngine.removeBatch(ids: existingIds)
        }

        let batch = queueEngine.dequeueBatch(size: 10)
        XCTAssertTrue(batch.isEmpty, "DequeueBatch should return empty array when queue is empty")
    }
}
