package com.yrrider.getlob.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.yrrider.getlob.R
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.ui.LiveTalkActivity
import com.yrrider.getlob.ui.MainActivity
import com.yrrider.getlob.ui.OverlayActivity
import java.util.concurrent.CopyOnWriteArrayList

object Notifier {
    const val CH_GENERAL = "general_v2"
    const val CH_PTT = "ptt_v2"
    const val CH_VIBE = "vibe_v1"
    const val CH_SOS = "sos_loud_channel_v3"

    const val NOTIF_ID_CHAT = 1001
    const val NOTIF_ID_SOS = 9110
    const val NOTIF_ID_LIVE = 9999
    const val NOTIF_ID_VIBE = 8888

    const val KEY_TEXT_REPLY = "key_reply_text"

    private data class StoredChatMessage(val text: String, val timestamp: Long, val sender: String)
    private val unreadChatMessages = CopyOnWriteArrayList<StoredChatMessage>()

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // Delete legacy channels so Android doesn't use cached low-importance settings
        runCatching {
            nm.deleteNotificationChannel("general")
            nm.deleteNotificationChannel("ptt")
            nm.deleteNotificationChannel("sos_loud_channel_v2")
        }

        nm.createNotificationChannel(
            NotificationChannel(CH_GENERAL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setShowBadge(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_PTT, "Push to talk", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_VIBE, "Vibes & Nudges", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150, 100, 300)
                setSound(null, null)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "Urgent SOS Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600, 200, 800)
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmUri, audioAttrs)
            }
        )
    }

    fun canUseFullScreenIntent(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    private fun liveTalkIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, LiveTalkActivity::class.java).apply {
            action = LiveTalkActivity.ACTION_OPEN_LIVE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            NOTIF_ID_LIVE,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun overlayIntent(ctx: Context, m: Message, sos: Boolean = false): PendingIntent {
        val i = Intent(ctx, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(OverlayActivity.EXTRA_PLAY_URL, m.mediaUrl)
            putExtra(OverlayActivity.EXTRA_PLAY_KIND, m.type)
            putExtra(OverlayActivity.EXTRA_SOS, sos)
        }
        return PendingIntent.getActivity(
            ctx,
            if (sos) NOTIF_ID_SOS else m.type.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun mainChatIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            NOTIF_ID_CHAT,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun directReplyAction(ctx: Context): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply…")
            .build()

        val replyIntent = Intent(ctx, DirectReplyReceiver::class.java)
        val replyPendingIntent = PendingIntent.getBroadcast(
            ctx,
            1002,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    fun clearChatNotifications(ctx: Context) {
        unreadChatMessages.clear()
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_CHAT)
    }

    fun buildSosNotification(ctx: Context, m: Message): Notification {
        val partnerName = LocalStore.readSettings(ctx).partnerName.ifBlank { "Partner" }
        val overlayPi = overlayIntent(ctx, m, sos = true)

        val dismissIntent = Intent(ctx, SosAlertService::class.java).apply {
            action = SosAlertService.ACTION_STOP_SOS
        }
        val dismissPendingIntent = PendingIntent.getService(
            ctx,
            9111,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(ctx, CH_SOS)
            .setSmallIcon(R.drawable.ic_stat_getlob)
            .setContentTitle("🚨 SOS ALERT from $partnerName")
            .setContentText("$partnerName triggered an urgent emergency alert!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(overlayPi, true)
            .setContentIntent(overlayPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss SOS", dismissPendingIntent)
            .build()
    }

    fun show(ctx: Context, m: Message) {
        val partnerName = LocalStore.readSettings(ctx).partnerName.ifBlank { "Partner" }

        when (m.type) {
            MsgType.SOS -> {
                val notif = buildSosNotification(ctx, m)
                try {
                    NotificationManagerCompat.from(ctx).notify(NOTIF_ID_SOS, notif)
                } catch (_: SecurityException) {}
            }

            MsgType.VIBE -> {
                val b = NotificationCompat.Builder(ctx, CH_VIBE)
                    .setSmallIcon(R.drawable.ic_stat_getlob)
                    .setContentTitle(partnerName)
                    .setContentText("Sent you a vibe 💓")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setTimeoutAfter(4_000)
                    .setContentIntent(mainChatIntent(ctx))
                try {
                    NotificationManagerCompat.from(ctx).notify(NOTIF_ID_VIBE, b.build())
                } catch (_: SecurityException) {}
            }

            MsgType.LIVE -> {
                val b = NotificationCompat.Builder(ctx, CH_PTT)
                    .setSmallIcon(R.drawable.ic_stat_getlob)
                    .setContentTitle(partnerName)
                    .setContentText("📡 Wants to talk! Tap to join Tap-to-Talk")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setContentIntent(liveTalkIntent(ctx))
                    .setFullScreenIntent(liveTalkIntent(ctx), true)
                    .addAction(android.R.drawable.ic_btn_speak_now, "Join Talk 📡", liveTalkIntent(ctx))
                    .setAutoCancel(true)
                try {
                    NotificationManagerCompat.from(ctx).notify(NOTIF_ID_LIVE, b.build())
                } catch (_: SecurityException) {}
            }

            else -> {
                // Regular chat messages (TEXT, EMOJI, IMAGE, GIF, VIDEO, VOICE, PTT, GAME)
                val body = when (m.type) {
                    MsgType.IMAGE -> "Sent you a photo 📸"
                    MsgType.GIF -> "Sent you a GIF 🎞️"
                    MsgType.VIDEO -> "Sent you a video 📹"
                    MsgType.VOICE, MsgType.PTT -> "🎙️ Voice note"
                    MsgType.GAME -> "🎮 Your turn in Tic-Tac-Toe!"
                    else -> m.text.ifBlank { "New message" }
                }

                unreadChatMessages.add(StoredChatMessage(body, m.createdAt, partnerName))
                while (unreadChatMessages.size > 6) {
                    unreadChatMessages.removeAt(0)
                }

                val partnerPerson = Person.Builder().setName(partnerName).build()
                val style = NotificationCompat.MessagingStyle(partnerPerson)
                for (item in unreadChatMessages) {
                    style.addMessage(item.text, item.timestamp, partnerPerson)
                }

                val b = NotificationCompat.Builder(ctx, CH_GENERAL)
                    .setSmallIcon(R.drawable.ic_stat_getlob)
                    .setStyle(style)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(mainChatIntent(ctx))
                    .addAction(directReplyAction(ctx))
                    .setAutoCancel(true)

                try {
                    NotificationManagerCompat.from(ctx).notify(NOTIF_ID_CHAT, b.build())
                } catch (_: SecurityException) {}
            }
        }
    }
}
