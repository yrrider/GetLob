package com.yrrider.getlob.fcm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType

class SosAlertService : Service() {

    companion object {
        private const val TAG = "SosAlertService"
        const val ACTION_START_SOS = "com.yrrider.getlob.action.START_SOS"
        const val ACTION_STOP_SOS = "com.yrrider.getlob.action.STOP_SOS"

        private const val EXTRA_MSG_ID = "extra_msg_id"
        private const val EXTRA_SENDER = "extra_sender"

        fun start(ctx: Context, msg: Message) {
            val intent = Intent(ctx, SosAlertService::class.java).apply {
                action = ACTION_START_SOS
                putExtra(EXTRA_MSG_ID, msg.id)
                putExtra(EXTRA_SENDER, msg.from)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, SosAlertService::class.java).apply {
                action = ACTION_STOP_SOS
            }
            ctx.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stopSos() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SOS -> {
                stopSos()
            }
            else -> {
                startSos(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSos(intent: Intent?) {
        val msgId = intent?.getStringExtra(EXTRA_MSG_ID) ?: ""
        val sender = intent?.getStringExtra(EXTRA_SENDER) ?: ""
        val dummyMsg = Message(id = msgId, type = MsgType.SOS, from = sender)

        Notifier.createChannels(this)
        val notification = Notifier.buildSosNotification(this, dummyMsg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.NOTIF_ID_SOS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifier.NOTIF_ID_SOS, notification)
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "getlob:SosAlertWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(50_000L)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }

        AudioAlerts.playSosSound(this)
        AudioAlerts.startSosVibrationLoop(this)

        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, 45_000L)
    }

    private fun stopSos() {
        handler.removeCallbacks(autoStopRunnable)
        AudioAlerts.stopSosSound()
        AudioAlerts.stopSosVibration(this)

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {}
        wakeLock = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopSos()
        super.onDestroy()
    }
}
