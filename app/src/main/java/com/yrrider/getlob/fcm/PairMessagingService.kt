package com.yrrider.getlob.fcm

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yrrider.getlob.data.Crypto
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.db.AppDatabase
import com.yrrider.getlob.data.db.MessageEntity
import com.yrrider.getlob.data.db.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PairMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        LocalStore.setPendingFcmToken(applicationContext, token)
        LocalStore.setTokenSynced(applicationContext, false)
        TokenSyncWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val raw = Message.fromFcm(message.data)

        // Ignore self-echo
        val myUid = LocalStore.myUid(applicationContext).ifBlank {
            FirebaseAuth.getInstance().currentUser?.uid ?: ""
        }
        if (raw.from.isNotEmpty() && raw.from == myUid) return

        Notifier.createChannels(this)

        // Fast in-memory decrypt (< 5 ms)
        val msg = raw.copy(text = Crypto.decryptText(this, raw.text))
        val pairId = message.data["pairId"] ?: LocalStore.pairId(applicationContext)

        // Upsert into local Room database & advance delivered cursor
        if (msg.id.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val plainReply = if (msg.replyText.isNotEmpty()) Crypto.decryptText(applicationContext, msg.replyText) else ""
                    val entity = MessageEntity(
                        id = msg.id,
                        pairId = pairId,
                        type = msg.type,
                        plainText = msg.text,
                        mediaUrl = msg.mediaUrl,
                        localMediaPath = "",
                        thumbB64 = msg.thumbB64,
                        fromUid = msg.from,
                        clientCreatedAt = msg.clientCreatedAt,
                        serverCreatedAt = msg.createdAt,
                        syncStatus = SyncStatus.DELIVERED,
                        replyToId = msg.replyToId,
                        replyText = plainReply,
                        replySender = msg.replySender
                    )
                    AppDatabase.getInstance(applicationContext).messageDao().upsertFromPushOrRemote(entity)
                    if (pairId.isNotEmpty() && msg.clientCreatedAt > 0L) {
                        Repo.updateDeliveredCursor(applicationContext, pairId, msg.clientCreatedAt)
                    }
                }
            }
        }

        // Dispatch visible alert immediately
        when (msg.type) {
            MsgType.SOS -> {
                SosAlertService.start(applicationContext, msg)
            }
            MsgType.LIVE -> {
                com.yrrider.getlob.ui.LiveTokenCache.prewarm(applicationContext)
                AudioAlerts.vibrateLiveInvite(applicationContext)
                Notifier.show(applicationContext, msg)
            }
            MsgType.VIBE -> {
                Notifier.show(applicationContext, msg)
            }
            else -> {
                if (!AppForegroundTracker.isChatVisible.value) {
                    Notifier.show(applicationContext, msg)
                }
            }
        }

        // Offload widget update to Expedited WorkManager
        val widgetTypes = listOf(
            MsgType.TEXT, MsgType.EMOJI, MsgType.IMAGE,
            MsgType.GIF, MsgType.VIDEO, MsgType.SOS,
            MsgType.VIBE, MsgType.GAME
        )
        if (msg.type in widgetTypes) {
            val fetchFull = message.data["fetchFull"] == "1"
            WidgetSyncWorker.enqueueExpedited(applicationContext, msg, fetchFull)
        }
    }
}
