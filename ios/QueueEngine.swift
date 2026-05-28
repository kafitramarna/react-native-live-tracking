import Foundation
import CoreData

/**
 * CDQueuedLocation represents a queued location entry stored in CoreData.
 * Used by the Offline Queue Engine to persist locations locally before syncing to Firebase.
 *
 * Requirements: 6.1
 */
@objc(CDQueuedLocation)
class CDQueuedLocation: NSManagedObject {
    @NSManaged var id: String
    @NSManaged var latitude: Double
    @NSManaged var longitude: Double
    @NSManaged var timestamp: Int64
    @NSManaged var accuracy: Double
    @NSManaged var speed: Double
    @NSManaged var altitude: Double
    @NSManaged var bearing: Double
    @NSManaged var createdAt: Int64
}

/**
 * QueueEngine manages the offline location queue using CoreData.
 * All operations are synchronous and intended to be called from a background thread.
 *
 * Responsibilities:
 * - Enqueue new locations with unique IDs
 * - Dequeue oldest batch for sending to Firebase
 * - Remove successfully sent batches
 * - Report current queue count
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 6.1, 6.3
 */
class QueueEngine {

    private let persistentContainer: NSPersistentContainer

    // MARK: - Initialization

    init() {
        let model = QueueEngine.createManagedObjectModel()
        persistentContainer = NSPersistentContainer(name: "LiveTrackingQueue", managedObjectModel: model)

        persistentContainer.loadPersistentStores { _, error in
            if let error = error {
                print("[QueueEngine] Failed to load persistent store: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Public Methods

    /**
     * Enqueue a new location into the local queue.
     * Creates a CDQueuedLocation with a UUID and current system time as createdAt.
     *
     * - Parameter latitude: Location latitude (-90 to 90)
     * - Parameter longitude: Location longitude (-180 to 180)
     * - Parameter timestamp: Unix timestamp in milliseconds when location was captured
     * - Parameter accuracy: Location accuracy in meters
     * - Parameter speed: Speed in m/s
     * - Parameter altitude: Altitude in meters
     * - Parameter bearing: Bearing in degrees (0-360)
     */
    func enqueue(
        latitude: Double,
        longitude: Double,
        timestamp: Int64,
        accuracy: Double,
        speed: Double,
        altitude: Double,
        bearing: Double
    ) {
        let context = persistentContainer.viewContext

        let entity = NSEntityDescription.entity(forEntityName: "CDQueuedLocation", in: context)!
        let location = CDQueuedLocation(entity: entity, insertInto: context)

        location.id = UUID().uuidString
        location.latitude = latitude
        location.longitude = longitude
        location.timestamp = timestamp
        location.accuracy = accuracy
        location.speed = speed
        location.altitude = altitude
        location.bearing = bearing
        location.createdAt = Int64(Date().timeIntervalSince1970 * 1000)

        do {
            try context.save()
        } catch {
            print("[QueueEngine] Failed to enqueue location: \(error.localizedDescription)")
        }
    }

    /**
     * Dequeue the oldest batch of locations from the queue.
     * Returns locations ordered by createdAt ascending (FIFO).
     *
     * - Parameter size: Maximum number of locations to retrieve
     * - Returns: Array of dictionaries containing location data
     */
    func dequeueBatch(size: Int) -> [[String: Any]] {
        let context = persistentContainer.viewContext

        let fetchRequest = NSFetchRequest<CDQueuedLocation>(entityName: "CDQueuedLocation")
        fetchRequest.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
        fetchRequest.fetchLimit = size

        do {
            let results = try context.fetch(fetchRequest)
            return results.map { location in
                return [
                    "id": location.id,
                    "latitude": location.latitude,
                    "longitude": location.longitude,
                    "timestamp": location.timestamp,
                    "accuracy": location.accuracy,
                    "speed": location.speed,
                    "altitude": location.altitude,
                    "bearing": location.bearing,
                    "createdAt": location.createdAt
                ] as [String: Any]
            }
        } catch {
            print("[QueueEngine] Failed to dequeue batch: \(error.localizedDescription)")
            return []
        }
    }

    /**
     * Remove a batch of locations from the queue by their IDs.
     * Typically called after a successful Firebase sync.
     *
     * - Parameter ids: Array of location IDs to remove
     */
    func removeBatch(ids: [String]) {
        let context = persistentContainer.viewContext

        let fetchRequest = NSFetchRequest<CDQueuedLocation>(entityName: "CDQueuedLocation")
        fetchRequest.predicate = NSPredicate(format: "id IN %@", ids)

        do {
            let results = try context.fetch(fetchRequest)
            for object in results {
                context.delete(object)
            }
            try context.save()
        } catch {
            print("[QueueEngine] Failed to remove batch: \(error.localizedDescription)")
        }
    }

    /**
     * Get the current number of locations waiting in the queue.
     *
     * - Returns: Count of queued locations
     */
    func count() -> Int {
        let context = persistentContainer.viewContext

        let fetchRequest = NSFetchRequest<CDQueuedLocation>(entityName: "CDQueuedLocation")

        do {
            return try context.count(for: fetchRequest)
        } catch {
            print("[QueueEngine] Failed to get count: \(error.localizedDescription)")
            return 0
        }
    }

    // MARK: - Private Methods

    /**
     * Creates the NSManagedObjectModel programmatically.
     * This avoids the need for a .xcdatamodeld file and allows the model to be defined in code.
     */
    private static func createManagedObjectModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        // Define CDQueuedLocation entity
        let entity = NSEntityDescription()
        entity.name = "CDQueuedLocation"
        entity.managedObjectClassName = NSStringFromClass(CDQueuedLocation.self)

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

        let altitudeAttribute = NSAttributeDescription()
        altitudeAttribute.name = "altitude"
        altitudeAttribute.attributeType = .doubleAttributeType
        altitudeAttribute.isOptional = false

        let bearingAttribute = NSAttributeDescription()
        bearingAttribute.name = "bearing"
        bearingAttribute.attributeType = .doubleAttributeType
        bearingAttribute.isOptional = false

        let createdAtAttribute = NSAttributeDescription()
        createdAtAttribute.name = "createdAt"
        createdAtAttribute.attributeType = .integer64AttributeType
        createdAtAttribute.isOptional = false

        entity.properties = [
            idAttribute,
            latitudeAttribute,
            longitudeAttribute,
            timestampAttribute,
            accuracyAttribute,
            speedAttribute,
            altitudeAttribute,
            bearingAttribute,
            createdAtAttribute
        ]

        model.entities = [entity]

        return model
    }
}
