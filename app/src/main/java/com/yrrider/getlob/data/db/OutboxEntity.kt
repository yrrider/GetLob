package com.yrrider.getlob.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val msgId: String,
    val pairId: String,
    val type: String,
    val plainText: String,
    val localMediaPath: String,
    val mediaExt: String,
    val thumbB64: String,
    val replyToId: String,
    val replyText: String,
    val replySender: String,
    val clientCreatedAt: Long,
    val attempts: Int,
    val createdAt: Long,
)
