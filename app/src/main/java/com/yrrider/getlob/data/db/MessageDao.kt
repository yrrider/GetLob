package com.yrrider.getlob.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE pairId = :pairId ORDER BY clientCreatedAt DESC")
    fun observeMessages(pairId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: MessageEntity)

    @Transaction
    suspend fun upsertFromPushOrRemote(entity: MessageEntity) {
        val existing = getMessageById(entity.id)
        if (existing == null) {
            insertOrReplace(entity)
        } else {
            val bestLocalPath = if (existing.localMediaPath.isNotEmpty()) existing.localMediaPath else entity.localMediaPath
            val bestSyncStatus = when {
                existing.syncStatus == SyncStatus.READ -> SyncStatus.READ
                existing.syncStatus == SyncStatus.DELIVERED && entity.syncStatus != SyncStatus.READ -> SyncStatus.DELIVERED
                else -> entity.syncStatus
            }
            val bestPlainText = if (entity.plainText.isNotEmpty()) entity.plainText else existing.plainText
            val bestMediaUrl = if (entity.mediaUrl.isNotEmpty()) entity.mediaUrl else existing.mediaUrl
            val bestThumb = if (entity.thumbB64.isNotEmpty()) entity.thumbB64 else existing.thumbB64

            insertOrReplace(
                entity.copy(
                    plainText = bestPlainText,
                    mediaUrl = bestMediaUrl,
                    localMediaPath = bestLocalPath,
                    thumbB64 = bestThumb,
                    syncStatus = bestSyncStatus,
                    serverCreatedAt = if (entity.serverCreatedAt > 0L) entity.serverCreatedAt else existing.serverCreatedAt
                )
            )
        }
    }

    @Query("UPDATE messages SET syncStatus = :newStatus WHERE pairId = :pairId AND fromUid = :myUid AND clientCreatedAt <= :untilMs AND syncStatus != 'READ'")
    suspend fun markDeliveredUpTo(pairId: String, myUid: String, untilMs: Long, newStatus: String = SyncStatus.DELIVERED)

    @Query("UPDATE messages SET syncStatus = :newStatus WHERE pairId = :pairId AND fromUid = :myUid AND clientCreatedAt <= :untilMs")
    suspend fun markReadUpTo(pairId: String, myUid: String, untilMs: Long, newStatus: String = SyncStatus.READ)

    @Query("UPDATE messages SET syncStatus = :status, mediaUrl = :mediaUrl WHERE id = :msgId")
    suspend fun updateSentStatus(msgId: String, status: String, mediaUrl: String)

    @Query("DELETE FROM messages WHERE pairId = :pairId")
    suspend fun clearPair(pairId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
