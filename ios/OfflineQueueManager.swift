import Foundation
import CoreData

/**
 * CDQueuedLocationV2 represents a queued location entry stored in CoreData
 * with per-target isolation via the `targetPath` attribute.
 *
 * This replaces the original CDQueuedLocation entity by adding targetPath
 * to support independent offline queues per sync target.
 *
 * Requirements: 5.1, 5.4, 5.7, 10.2
 */
@objc(CDQueuedLocationV2)
class CDQueuedLocationV2: NSManagedObject {
    @NSManaged var id: String
    @NSManaged var latitude: Double
    @NSManaged var longitude: Double
    @NSManaged var timestamp: Int64
    @NSManaged var accuracy: Double
    @NSManaged var speed: Double
    @NSManaged var altitude: Double
    @NSManaged var bearing: Double
    @NSManaged var createdAt: Int64
    @NSManaged var targetPath: String

    /// Whether speed value is available (non-placeholder)
    @NSManaged var hasSpeed: Bool
    /// Whether altitude value is available (non-placeholder)
    @NSManaged var hasAltitude: Bool
    /// Whether bearing value is available (non-placeholder)
    @NSManaged var hasBearing: Bool
}

/**
 * OfflineQueueManager manages the CoreData-based offline queue with per-target isolation.
 *
 * Each sync target that has `offlineQueue: true` gets its own isolated queue.
 * The manager enforces a 10,000 data point cap per target with oldest-eviction
 * when the cap is reached.
 *
 * Key responsibilities:
 * - Enqueue location data points for a specific target path
 * - Dequeue oldest batch for a specific target (chronological order)
 * - Remove successfully synced entries
 * - Enforce 10,000 cap per target with oldest-eviction
 * - Provide per-target and total count queries
 *
 * Requirements: 5.1, 5.4, 5.7, 10.2
 */
class OfflineQueueManager {

    // MARK: - Constants

    /// Maximum number of queued data points per target
    static let maxQueueSizePerTarget: Int = 10_000

    /// CoreData entity name
    private static let entityName = "CDQueuedLocationV2"

    // MARK: - Properties

    private let persistentContainer: NSPersistentContainer
    private let queue = DispatchQueue(label: "com.livetracking.offlinequeue", qos: .utility)

    /// Callback for queue overflow warnings
    var onQueueOverflow: ((_ targetPath: String) -> Void)?

    // MARK: - Initialization

    init() {
        let model = OfflineQueueManager.createManagedObjectModel()
        persistentContainer = NSPersistentContainer(
            name: "LiveTrackingOfflineQueue",
            managedObjectModel: model
        )

        persistentContainer.loadPersistentStores { _, error in
            if let error = error {
                print("[OfflineQueueManager] Failed to load persistent store: \(error.localizedDescription)")
            }
        }
    }

    /// Initializer for testing with a custom persistent container
    init(persistentContainer: NSPersistentContainer) {
        self.persistentContainer = persistentContainer
    }

    // MARK: - Public Methods

    /**
     * Enqueue a location data point for a specific target path.
     *
     * If the queue for the target has reached the 10,000 cap, the oldest
     * entry is evicted to make room for the new data point, and a warning
     * is emitted via the `onQueueOverflow` callback.
     *
     * - Parameter location: The location data point to enqueue
     * - Parameter targetPath: The sync target path this location belongs to
     *
     * Requirements: 5.1, 5.7
     */
    func enqueue(location: LocationDataPoint, targetPath: String) {
        let context = persistentContainer.newBackgroundContext()
        context.performAndWait {
            // Check current count for this target
            let currentCount = self.fetchCount(for: targetPath, in: context)

            // Enforce 10,000 cap with oldest-eviction
            if currentCount >= OfflineQueueManager.maxQueueSizePerTarget {
                self.evictOldest(for: targetPath, in: context)
                self.onQueueOverflow?(targetPath)
            }

            // Insert new entry
            guard let entity = NSEntityDescription.entity(
                forEntityName: OfflineQueueManager.entityName,
                in: context
            ) else {
                print("[OfflineQueueManager] Failed to get entity description")
                return
            }

            let entry = CDQueuedLocationV2(entity: entity, insertInto: context)
            entry.id = UUID().uuidString
            entry.latitude = location.latitude
            entry.longitude = location.longitude
            entry.timestamp = location.timestamp
            entry.accuracy = location.accuracy
            entry.speed = location.speed ?? 0
            entry.altitude = location.altitude ?? 0
            entry.bearing = location.bearing ?? 0
            entry.hasSpeed = location.speed != nil
            entry.hasAltitude = location.altitude != nil
            entry.hasBearing = location.bearing != nil
            entry.createdAt = Int64(Date().timeIntervalSince1970 * 1000)
            entry.targetPath = targetPath

            do {
                try context.save()
            } catch {
                print("[OfflineQueueManager] Failed to enqueue location: \(error.localizedDescription)")
            }
        }
    }

    /**
     * Dequeue the oldest batch of locations for a specific target.
     * Returns locations ordered by createdAt ascending (FIFO / chronological).
     *
     * - Parameter targetPath: The sync target path to dequeue from
     * - Parameter size: Maximum number of locations to retrieve
     * - Returns: Array of LocationDataPoint entries in chronological order
     *
     * Requirements: 5.2, 5.4
     */
    func dequeueBatch(targetPath: String, size: Int) -> [(id: String, location: LocationDataPoint)] {
        let context = persistentContainer.viewContext
        var results: [(id: String, location: LocationDataPoint)] = []

        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
            fetchRequest.predicate = NSPredicate(format: "targetPath == %@", targetPath)
            fetchRequest.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
            fetchRequest.fetchLimit = size

            do {
                let entries = try context.fetch(fetchRequest)
                results = entries.map { entry in
                    let location = LocationDataPoint(
                        latitude: entry.latitude,
                        longitude: entry.longitude,
                        timestamp: entry.timestamp,
                        accuracy: entry.accuracy,
                        speed: entry.hasSpeed ? entry.speed : nil,
                        altitude: entry.hasAltitude ? entry.altitude : nil,
                        bearing: entry.hasBearing ? entry.bearing : nil
                    )
                    return (id: entry.id, location: location)
                }
            } catch {
                print("[OfflineQueueManager] Failed to dequeue batch: \(error.localizedDescription)")
            }
        }

        return results
    }

    /**
     * Remove entries from the queue by their IDs.
     * Called after successful Firebase write confirmation.
     *
     * - Parameter ids: Array of entry IDs to remove
     *
     * Requirements: 5.2
     */
    func removeBatch(ids: [String]) {
        let context = persistentContainer.newBackgroundContext()
        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
            fetchRequest.predicate = NSPredicate(format: "id IN %@", ids)

            do {
                let entries = try context.fetch(fetchRequest)
                for entry in entries {
                    context.delete(entry)
                }
                try context.save()
            } catch {
                print("[OfflineQueueManager] Failed to remove batch: \(error.localizedDescription)")
            }
        }
    }

    /**
     * Get the count of queued locations for a specific target.
     *
     * - Parameter targetPath: The sync target path to count
     * - Returns: Number of queued locations for the target
     *
     * Requirements: 10.2
     */
    func countForTarget(_ targetPath: String) -> Int {
        let context = persistentContainer.viewContext
        return fetchCount(for: targetPath, in: context)
    }

    /**
     * Get the total count of all queued locations across all targets.
     *
     * - Returns: Total number of queued locations
     *
     * Requirements: 10.1
     */
    func totalCount() -> Int {
        let context = persistentContainer.viewContext
        var count = 0

        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
            do {
                count = try context.count(for: fetchRequest)
            } catch {
                print("[OfflineQueueManager] Failed to get total count: \(error.localizedDescription)")
            }
        }

        return count
    }

    /**
     * Get queued location counts grouped by target path.
     * Returns a dictionary mapping each target path to its queue count.
     *
     * - Returns: Dictionary of [targetPath: count]
     *
     * Requirements: 10.2
     */
    func countsByTarget() -> [String: Int] {
        let context = persistentContainer.viewContext
        var counts: [String: Int] = [:]

        context.performAndWait {
            let fetchRequest = NSFetchRequest<NSDictionary>(entityName: OfflineQueueManager.entityName)
            fetchRequest.resultType = .dictionaryResultType

            let countExpression = NSExpression(forFunction: "count:", arguments: [NSExpression(forKeyPath: "id")])
            let countDescription = NSExpressionDescription()
            countDescription.name = "count"
            countDescription.expression = countExpression
            countDescription.expressionResultType = .integer64AttributeType

            fetchRequest.propertiesToFetch = ["targetPath", countDescription]
            fetchRequest.propertiesToGroupBy = ["targetPath"]

            do {
                let results = try context.fetch(fetchRequest)
                for result in results {
                    if let path = result["targetPath"] as? String,
                       let count = result["count"] as? Int {
                        counts[path] = count
                    }
                }
            } catch {
                print("[OfflineQueueManager] Failed to get counts by target: \(error.localizedDescription)")
            }
        }

        return counts
    }

    /**
     * Remove all queued entries for a specific target.
     * Useful when a target is removed from configuration.
     *
     * - Parameter targetPath: The sync target path to clear
     */
    func clearTarget(_ targetPath: String) {
        let context = persistentContainer.newBackgroundContext()
        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
            fetchRequest.predicate = NSPredicate(format: "targetPath == %@", targetPath)

            do {
                let entries = try context.fetch(fetchRequest)
                for entry in entries {
                    context.delete(entry)
                }
                try context.save()
            } catch {
                print("[OfflineQueueManager] Failed to clear target: \(error.localizedDescription)")
            }
        }
    }

    /**
     * Remove all queued entries across all targets.
     */
    func clearAll() {
        let context = persistentContainer.newBackgroundContext()
        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)

            do {
                let entries = try context.fetch(fetchRequest)
                for entry in entries {
                    context.delete(entry)
                }
                try context.save()
            } catch {
                print("[OfflineQueueManager] Failed to clear all: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Private Methods

    /**
     * Fetch the count of entries for a specific target path.
     */
    private func fetchCount(for targetPath: String, in context: NSManagedObjectContext) -> Int {
        var count = 0
        context.performAndWait {
            let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
            fetchRequest.predicate = NSPredicate(format: "targetPath == %@", targetPath)

            do {
                count = try context.count(for: fetchRequest)
            } catch {
                print("[OfflineQueueManager] Failed to fetch count for target: \(error.localizedDescription)")
            }
        }
        return count
    }

    /**
     * Evict the oldest entry for a specific target path.
     * Called when the queue reaches the 10,000 cap.
     *
     * Requirements: 5.7
     */
    private func evictOldest(for targetPath: String, in context: NSManagedObjectContext) {
        let fetchRequest = NSFetchRequest<CDQueuedLocationV2>(entityName: OfflineQueueManager.entityName)
        fetchRequest.predicate = NSPredicate(format: "targetPath == %@", targetPath)
        fetchRequest.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
        fetchRequest.fetchLimit = 1

        do {
            let results = try context.fetch(fetchRequest)
            if let oldest = results.first {
                context.delete(oldest)
                try context.save()
            }
        } catch {
            print("[OfflineQueueManager] Failed to evict oldest entry: \(error.localizedDescription)")
        }
    }

    // MARK: - CoreData Model Definition

    /**
     * Creates the NSManagedObjectModel programmatically with the targetPath attribute.
     * This defines the CDQueuedLocationV2 entity with all required attributes
     * including the new `targetPath` field for per-target queue isolation.
     */
    static func createManagedObjectModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        // Define CDQueuedLocationV2 entity
        let entity = NSEntityDescription()
        entity.name = "CDQueuedLocationV2"
        entity.managedObjectClassName = NSStringFromClass(CDQueuedLocationV2.self)

        // Define attributes
        let idAttribute = NSAttributeDescription()
        idAttribute.name = "id"
        idAttribute.attributeType = .stringAttributeType
        idAttribute.isOptional = false

        let latitudeAttribute = NSAttributeDescription()
        latitudeAttribute.name = "latitude"
        latitudeAttribute.attributeType = .doubleAttributeType
        latitudeAttribute.isOptional = false

        let longitudeAttribute = NSAttributeDescription()
        longitudeAttribute.name = "longitude"
        longitudeAttribute.attributeType = .doubleAttributeType
        longitudeAttribute.isOptional = false

        let timestampAttribute = NSAttributeDescription()
        timestampAttribute.name = "timestamp"
        timestampAttribute.attributeType = .integer64AttributeType
        timestampAttribute.isOptional = false

        let accuracyAttribute = NSAttributeDescription()
        accuracyAttribute.name = "accuracy"
        accuracyAttribute.attributeType = .doubleAttributeType
        accuracyAttribute.isOptional = false

        let speedAttribute = NSAttributeDescription()
        speedAttribute.name = "speed"
        speedAttribute.attributeType = .doubleAttributeType
        speedAttribute.isOptional = false
        speedAttribute.defaultValue = 0.0

        let altitudeAttribute = NSAttributeDescription()
        altitudeAttribute.name = "altitude"
        altitudeAttribute.attributeType = .doubleAttributeType
        altitudeAttribute.isOptional = false
        altitudeAttribute.defaultValue = 0.0

        let bearingAttribute = NSAttributeDescription()
        bearingAttribute.name = "bearing"
        bearingAttribute.attributeType = .doubleAttributeType
        bearingAttribute.isOptional = false
        bearingAttribute.defaultValue = 0.0

        let createdAtAttribute = NSAttributeDescription()
        createdAtAttribute.name = "createdAt"
        createdAtAttribute.attributeType = .integer64AttributeType
        createdAtAttribute.isOptional = false

        let targetPathAttribute = NSAttributeDescription()
        targetPathAttribute.name = "targetPath"
        targetPathAttribute.attributeType = .stringAttributeType
        targetPathAttribute.isOptional = false
        targetPathAttribute.defaultValue = ""

        let hasSpeedAttribute = NSAttributeDescription()
        hasSpeedAttribute.name = "hasSpeed"
        hasSpeedAttribute.attributeType = .booleanAttributeType
        hasSpeedAttribute.isOptional = false
        hasSpeedAttribute.defaultValue = false

        let hasAltitudeAttribute = NSAttributeDescription()
        hasAltitudeAttribute.name = "hasAltitude"
        hasAltitudeAttribute.attributeType = .booleanAttributeType
        hasAltitudeAttribute.isOptional = false
        hasAltitudeAttribute.defaultValue = false

        let hasBearingAttribute = NSAttributeDescription()
        hasBearingAttribute.name = "hasBearing"
        hasBearingAttribute.attributeType = .booleanAttributeType
        hasBearingAttribute.isOptional = false
        hasBearingAttribute.defaultValue = false

        entity.properties = [
            idAttribute,
            latitudeAttribute,
            longitudeAttribute,
            timestampAttribute,
            accuracyAttribute,
            speedAttribute,
            altitudeAttribute,
            bearingAttribute,
            createdAtAttribute,
            targetPathAttribute,
            hasSpeedAttribute,
            hasAltitudeAttribute,
            hasBearingAttribute
        ]

        // Add index on targetPath + createdAt for efficient per-target queries
        let targetPathIndex = NSFetchIndexDescription(
            name: "idx_targetPath_createdAt",
            elements: [
                NSFetchIndexElementDescription(
                    property: targetPathAttribute,
                    collationType: .binary
                ),
                NSFetchIndexElementDescription(
                    property: createdAtAttribute,
                    collationType: .binary
                )
            ]
        )
        entity.indexes = [targetPathIndex]

        model.entities = [entity]

        return model
    }
}
