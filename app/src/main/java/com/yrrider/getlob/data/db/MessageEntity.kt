package com.yrrider.getlob.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yrrider.getlob.data.Message

@Entity(
    tableName = "messages",
    indices = [Index(value = ["pairId", "clientCreatedAt"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val pairId: String,
    val type: String,
    val plainText: String,
    val mediaUrl: String,
    val localMediaPath: String,
    val thumbB64: String,
    val fromUid: String,
    val clientCreatedAt: Long,
    val serverCreatedAt: Long,
    val syncStatus: String,
    val replyToId: String,
    val replyText: String,
    val replySender: String,
) {
    fun toMessage(): Message = Message(
        id = id,
        type = type,
        text = plainText,
        mediaUrl = mediaUrl,
        from = fromUid,
        createdAt = clientCreatedAt,
        replyToId = replyToId,
        replyText = replyText,
        replySender = replySender,
        clientCreatedAt = clientCreatedAt,
        thumbB64 = thumbB64,
        syncStatus = syncStatus,
        localMediaPath = localMediaPath
    )
}
