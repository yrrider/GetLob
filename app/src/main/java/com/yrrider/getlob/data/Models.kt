package com.yrrider.getlob.data

import com.google.firebase.firestore.DocumentSnapshot

/** Message types that travel between the two devices. */
object MsgType {
    const val TEXT = "text"
    const val EMOJI = "emoji"
    const val IMAGE = "image"
    const val GIF = "gif"
    const val VIDEO = "video"
    const val VOICE = "voice"
    const val PTT = "ptt"      // push-to-talk clip: triggers a "Tap to listen" notification
    const val VIBE = "vibe"
    const val SOS = "sos"
    const val GAME = "game"    // "your turn" ping
}

data class Message(
    val id: String = "",
    val type: String = MsgType.TEXT,
    val text: String = "",
    val mediaUrl: String = "",
    val from: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "type" to type,
        "text" to text,
        "mediaUrl" to mediaUrl,
        "from" to from,
        "createdAt" to createdAt,
    )

    companion object {
        fun fromDoc(d: DocumentSnapshot) = Message(
            id = d.id,
            type = d.getString("type") ?: MsgType.TEXT,
            text = d.getString("text") ?: "",
            mediaUrl = d.getString("mediaUrl") ?: "",
            from = d.getString("from") ?: "",
            createdAt = d.getTimestamp("createdAt")?.toDate()?.time ?: d.getLong("createdAt") ?: 0L,
        )

        /** Builds a Message from an FCM data payload (all values are strings). */
        fun fromFcm(m: Map<String, String>) = Message(
            id = m["msgId"] ?: "",
            type = m["type"] ?: MsgType.TEXT,
            text = m["text"] ?: "",
            mediaUrl = m["mediaUrl"] ?: "",
            from = m["senderUid"] ?: m["from"] ?: "",
            createdAt = m["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }
}

/** Tic-tac-toe state stored in pairs/{pairId}/games/current */
data class GameState(
    val board: String = "---------",
    val turn: String = "",
    val x: String = "",
    val o: String = "",
    val winner: String = "",   // "", "X", "O", "draw"
)
