package com.yrrider.getlob.fcm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yrrider.getlob.data.Crypto
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.widget.MediaProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull



class PairMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { Repo.saveToken(token, applicationContext) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val m = Message.fromFcm(message.data)
        Notifier.createChannels(this)

        runBlocking {
            withTimeoutOrNull(25_000) { handle(applicationContext, m) }
        }
    }

    companion object {
        suspend fun handle(ctx: Context, raw: Message) {
            val m = raw.copy(text = Crypto.decryptText(ctx, raw.text))

            if (m.type == MsgType.VIBE) {
                AudioAlerts.vibrate(ctx, isSos = false)
            } else if (m.type == MsgType.SOS) {
                AudioAlerts.vibrate(ctx, isSos = true)
                AudioAlerts.playSosSound(ctx)
            } else if (m.type == MsgType.LIVE) {
                AudioAlerts.vibrateLiveInvite(ctx)
            }


            Notifier.show(ctx, m)


            runCatching {
                MediaProcessor.apply(ctx, m)
            }.onFailure { e ->
                android.util.Log.e("PairMessagingService", "MediaProcessor.apply error: ${e.message}", e)
            }
        }
    }
}
