package com.yrrider.getlob.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yrrider.getlob.R
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.ui.LiveTalkActivity
import com.yrrider.getlob.ui.OverlayActivity

object Notifier {
    const val CH_GENERAL = "general"
    const val CH_PTT = "ptt"
    const val CH_SOS = "sos_loud_channel_v2"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_GENERAL, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PTT, "Push to talk", NotificationManager.IMPORTANCE_HIGH)
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "Urgent SOS Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600, 200, 800)
                setSound(null, null)
            }
        )
    }

    private fun liveTalkIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, LiveTalkActivity::class.java).apply {
            action = LiveTalkActivity.ACTION_OPEN_LIVE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            ctx,
            9999,
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
            m.type.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun show(ctx: Context, m: Message) {
        if (m.type == MsgType.VIBE) return

        val partnerName = LocalStore.readSettings(ctx).partnerName.ifBlank { "Partner" }

        val (channel, title, body) = when (m.type) {
            MsgType.LIVE -> Triple(CH_PTT, partnerName, "📡 Wants to talk! Tap to join Tap-to-Talk")
            MsgType.PTT -> Triple(CH_PTT, partnerName, "\uD83C\uDF99 Tap to talk: voice message")
            MsgType.VOICE -> Triple(CH_PTT, partnerName, "\uD83C\uDF99 Voice note (Tap to listen)")
            MsgType.SOS -> Triple(CH_SOS, "🚨 SOS from $partnerName", "$partnerName triggered an urgent emergency alert!")
            MsgType.TEXT, MsgType.EMOJI -> Triple(CH_GENERAL, partnerName, m.text)
            MsgType.IMAGE -> Triple(CH_GENERAL, partnerName, "Sent you a photo 📸")
            MsgType.GIF -> Triple(CH_GENERAL, partnerName, "Sent you a GIF 🎞️")
            MsgType.VIDEO -> Triple(CH_GENERAL, partnerName, "Sent you a video 📹")
            MsgType.GAME -> Triple(CH_GENERAL, partnerName, "🎮 Your turn in Tic-Tac-Toe!")
            else -> return
        }

        val sos = m.type == MsgType.SOS
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_getlob)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(if (sos) NotificationCompat.PRIORITY_MAX else if (channel == CH_GENERAL) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(overlayIntent(ctx, m, sos))

        if (m.type == MsgType.PTT || m.type == MsgType.VOICE) {
            b.addAction(android.R.drawable.ic_media_play, "Listen", overlayIntent(ctx, m))
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }

        if (m.type == MsgType.LIVE) {
            b.setContentIntent(liveTalkIntent(ctx))
            b.addAction(android.R.drawable.ic_btn_speak_now, "Join Talk 📡", liveTalkIntent(ctx))
            b.setFullScreenIntent(liveTalkIntent(ctx), true)
            b.setPriority(NotificationCompat.PRIORITY_MAX)
            b.setCategory(NotificationCompat.CATEGORY_CALL)
            b.setVibrate(longArrayOf(0, 300, 150, 300, 150, 500))
        }

        if (sos) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setFullScreenIntent(overlayIntent(ctx, m, true), true)
            b.setVibrate(longArrayOf(0, 600, 200, 600, 200, 600, 200, 800))
        }

        try {
            NotificationManagerCompat.from(ctx).notify(m.type.hashCode(), b.build())
        } catch (_: SecurityException) {

        }
    }
}
