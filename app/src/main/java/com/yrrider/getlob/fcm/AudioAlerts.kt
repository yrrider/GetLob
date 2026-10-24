package com.yrrider.getlob.fcm

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object AudioAlerts {
    private const val TAG = "AudioAlerts"
    private var sosRingtone: Ringtone? = null

    /**
     * Triggers vibration for either a gentle Vibe pulse or urgent SOS alert.
     * Uses VibratorManager on Android 12+ (API 31+) and sets appropriate Audio/Vibration attributes
     * so vibration triggers reliably even in background or with touch haptics turned off.
     */
    fun vibrate(ctx: Context, isSos: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "No vibrator found on device")
                return
            }

            // Patterns: {delay, vibrate, pause, vibrate, pause, vibrate...}
            val pattern = if (isSos) {
                longArrayOf(0, 600, 200, 600, 200, 600, 200, 800)
            } else {
                longArrayOf(0, 150, 100, 150, 100, 300)
            }

            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(if (isSos) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    val amplitudes = if (isSos) {
                        intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
                    } else {
                        intArrayOf(0, 200, 0, 220, 0, 255)
                    }
                    VibrationEffect.createWaveform(pattern, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(pattern, -1)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val vibAttrs = VibrationAttributes.Builder()
                        .setUsage(if (isSos) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION)
                        .build()
                    vibrator.vibrate(effect, vibAttrs)
                } else {
                    vibrator.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    /**
     * Plays the device's alarm sound loudly on the ALARM stream for SOS alerts.
     */
    @Synchronized
    fun playSosSound(ctx: Context) {
        stopSosSound()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            sosRingtone = RingtoneManager.getRingtone(ctx.applicationContext, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
            Log.i(TAG, "Playing SOS alarm sound")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to play SOS sound", e)
        }
    }

    /**
     * Stops the SOS alarm sound.
     */
    @Synchronized
    fun stopSosSound() {
        try {
            sosRingtone?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop SOS sound", e)
        } finally {
            sosRingtone = null
        }
    }
}
