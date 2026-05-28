package com.livetracking.queue

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queued_locations")
data class QueuedLocation(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val speed: Float?,
    val altitude: Double?,
    val bearing: Float?,
    val createdAt: Long
)
