package com.yrrider.getlob.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE msgId = :msgId")
    suspend fun delete(msgId: String)

    @Query("UPDATE outbox SET attempts = :attempts WHERE msgId = :msgId")
    suspend fun updateAttempts(msgId: String, attempts: Int)

    @Query("DELETE FROM outbox WHERE pairId = :pairId")
    suspend fun clearPair(pairId: String)

    @Query("DELETE FROM outbox")
    suspend fun clearAll()
}
