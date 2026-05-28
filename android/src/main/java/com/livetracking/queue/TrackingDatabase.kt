package com.livetracking.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [QueuedLocation::class], version = 1, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {

    abstract fun queuedLocationDao(): QueuedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: TrackingDatabase? = null

        fun getInstance(context: Context): TrackingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackingDatabase::class.java,
                    "live_tracking_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
