package com.livetracking.queue

/**
 * Data class representing a queued location entry.
 */
data class QueuedLocation(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val speed: Float?,
    val altitude: Double?,
    val bearing: Float?,
    val createdAt: Long
)
