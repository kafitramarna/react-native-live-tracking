package com.livetracking.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QueuedLocationDao {

    @Insert
    fun insert(location: QueuedLocation)

    @Query("SELECT * FROM queued_locations ORDER BY createdAt ASC LIMIT :limit")
    fun getOldestBatch(limit: Int): List<QueuedLocation>

    @Query("DELETE FROM queued_locations WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM queued_locations")
    fun getCount(): Int
}
