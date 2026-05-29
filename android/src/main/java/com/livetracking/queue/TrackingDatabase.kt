package com.livetracking.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database helper for the location queue.
 * Supports per-target offline queues with a target_path column.
 * Replaces Room to avoid kapt/ksp annotation processor dependency issues.
 */
class TrackingDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "live_tracking_db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_NAME = "queued_locations"
        private const val INDEX_NAME = "idx_queued_target_path"

        /**
         * Maximum number of queued data points per target.
         * When this limit is reached, the oldest data point is evicted.
         */
        const val MAX_QUEUE_SIZE_PER_TARGET = 10_000

        @Volatile
        private var INSTANCE: TrackingDatabase? = null

        fun getInstance(context: Context): TrackingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackingDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                id TEXT PRIMARY KEY,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                accuracy REAL NOT NULL,
                speed REAL,
                altitude REAL,
                bearing REAL,
                createdAt INTEGER NOT NULL,
                target_path TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL(
            "CREATE INDEX $INDEX_NAME ON $TABLE_NAME(target_path, createdAt ASC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migration v1 → v2: add target_path column with default empty string
            db.execSQL(
                "ALTER TABLE $TABLE_NAME ADD COLUMN target_path TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "CREATE INDEX $INDEX_NAME ON $TABLE_NAME(target_path, createdAt ASC)"
            )
        }
    }

    // --- Legacy methods (backward-compatible, operate without target_path filter) ---

    fun insert(location: QueuedLocation) {
        val values = ContentValues().apply {
            put("id", location.id)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("timestamp", location.timestamp)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("altitude", location.altitude)
            put("bearing", location.bearing)
            put("createdAt", location.createdAt)
        }
        writableDatabase.insert(TABLE_NAME, null, values)
    }

    fun getOldestBatch(limit: Int): List<QueuedLocation> {
        val locations = mutableListOf<QueuedLocation>()
        val cursor = readableDatabase.query(
            TABLE_NAME, null, null, null, null, null,
            "createdAt ASC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                locations.add(cursorToQueuedLocation(it))
            }
        }
        return locations
    }

    fun deleteByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.delete(TABLE_NAME, "id IN ($placeholders)", ids.toTypedArray())
    }

    fun getCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    // --- Per-target methods ---

    /**
     * Insert a location for a specific target path.
     * Enforces the 10,000 data point cap per target by evicting the oldest
     * entry when the limit is reached.
     */
    fun insertForTarget(location: QueuedLocation, targetPath: String) {
        val db = writableDatabase
        // Enforce cap: evict oldest if at limit
        val currentCount = getCountForTarget(targetPath)
        if (currentCount >= MAX_QUEUE_SIZE_PER_TARGET) {
            evictOldestForTarget(targetPath)
        }

        val values = ContentValues().apply {
            put("id", location.id)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("timestamp", location.timestamp)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("altitude", location.altitude)
            put("bearing", location.bearing)
            put("createdAt", location.createdAt)
            put("target_path", targetPath)
        }
        db.insert(TABLE_NAME, null, values)
    }

    /**
     * Get the oldest batch of queued locations for a specific target path.
     * Results are ordered by createdAt ASC (oldest first).
     */
    fun getOldestBatchForTarget(targetPath: String, limit: Int): List<QueuedLocation> {
        val locations = mutableListOf<QueuedLocation>()
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            "target_path = ?",
            arrayOf(targetPath),
            null,
            null,
            "createdAt ASC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                locations.add(cursorToQueuedLocation(it))
            }
        }
        return locations
    }

    /**
     * Get the count of queued locations for a specific target path.
     */
    fun getCountForTarget(targetPath: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME WHERE target_path = ?",
            arrayOf(targetPath)
        )
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    /**
     * Get the count of queued locations grouped by target path.
     * Returns a map of target_path → count.
     */
    fun getCountsByTarget(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val cursor = readableDatabase.rawQuery(
            "SELECT target_path, COUNT(*) FROM $TABLE_NAME GROUP BY target_path",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                val count = it.getInt(1)
                counts[path] = count
            }
        }
        return counts
    }

    /**
     * Evict the oldest queued location for a specific target path.
     * Used to enforce the 10,000 data point cap per target.
     */
    fun evictOldestForTarget(targetPath: String) {
        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE_NAME WHERE id = (
                SELECT id FROM $TABLE_NAME
                WHERE target_path = ?
                ORDER BY createdAt ASC
                LIMIT 1
            )
            """.trimIndent(),
            arrayOf(targetPath)
        )
    }

    // --- Private helpers ---

    private fun cursorToQueuedLocation(cursor: android.database.Cursor): QueuedLocation {
        return QueuedLocation(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow("accuracy")),
            speed = if (cursor.isNull(cursor.getColumnIndexOrThrow("speed"))) null
                else cursor.getFloat(cursor.getColumnIndexOrThrow("speed")),
            altitude = if (cursor.isNull(cursor.getColumnIndexOrThrow("altitude"))) null
                else cursor.getDouble(cursor.getColumnIndexOrThrow("altitude")),
            bearing = if (cursor.isNull(cursor.getColumnIndexOrThrow("bearing"))) null
                else cursor.getFloat(cursor.getColumnIndexOrThrow("bearing")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
        )
    }
}
