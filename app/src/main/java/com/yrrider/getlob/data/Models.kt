package com.yrrider.getlob.data

import com.google.firebase.firestore.DocumentSnapshot

object MsgType {
    const val TEXT = "text"
    const val EMOJI = "emoji"
    const val IMAGE = "image"
    const val GIF = "gif"
    const val VIDEO = "video"
    const val VOICE = "voice"
    const val PTT = "ptt"
    const val LIVE = "live"
    const val VIBE = "vibe"
    const val SOS = "sos"
    const val GAME = "game"
}

data class Message(
    val id: String = "",
    val type: String = MsgType.TEXT,
    val text: String = "",
    val mediaUrl: String = "",
    val from: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val replyToId: String = "",
    val replyText: String = "",
    val replySender: String = "",
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("type", type)
        put("text", text)
        put("mediaUrl", mediaUrl)
        put("from", from)
        put("createdAt", createdAt)
        if (replyToId.isNotEmpty()) put("replyToId", replyToId)
        if (replyText.isNotEmpty()) put("replyText", replyText)
        if (replySender.isNotEmpty()) put("replySender", replySender)
    }

    companion object {
        fun fromDoc(d: DocumentSnapshot) = Message(
            id = d.id,
            type = d.getString("type") ?: MsgType.TEXT,
            text = d.getString("text") ?: "",
            mediaUrl = d.getString("mediaUrl") ?: "",
            from = d.getString("from") ?: "",
            createdAt = d.getTimestamp("createdAt")?.toDate()?.time ?: d.getLong("createdAt") ?: 0L,
            replyToId = d.getString("replyToId") ?: "",
            replyText = d.getString("replyText") ?: "",
            replySender = d.getString("replySender") ?: "",
        )

        fun fromFcm(m: Map<String, String>) = Message(
            id = m["msgId"] ?: "",
            type = m["type"] ?: MsgType.TEXT,
            text = m["text"] ?: "",
            mediaUrl = m["mediaUrl"] ?: "",
            from = m["senderUid"] ?: m["from"] ?: "",
            createdAt = m["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
            replyToId = m["replyToId"] ?: "",
            replyText = m["replyText"] ?: "",
            replySender = m["replySender"] ?: "",
        )
    }
}

data class GameState(
    val board: String = "---------",
    val turn: String = "",
    val x: String = "",
    val o: String = "",
    val winner: String = "",
)
