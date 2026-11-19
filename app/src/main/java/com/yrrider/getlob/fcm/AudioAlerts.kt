package com.yrrider.getlob.fcm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object AudioAlerts {
    private const val TAG = "AudioAlerts"
    private var sosRingtone: Ringtone? = null
    private var sosAudioTrack: AudioTrack? = null
    private var sosTimeoutRunnable: Runnable? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getVibrator(ctx: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun vibrate(ctx: Context, isSos: Boolean) {
        try {
            val vibrator = getVibrator(ctx)
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "No vibrator found on device")
                return
            }

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

    fun vibrateLiveInvite(ctx: Context) {
        try {
            val vibrator = getVibrator(ctx) ?: return
            val pattern = longArrayOf(0, 300, 150, 300, 150, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                        .build()
                    vibrator.vibrate(effect, attrs)
                } else {
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Live talk vibration failed", e)
        }
    }

    @Synchronized
    fun startSosVibrationLoop(ctx: Context) {
        stopSosVibration(ctx)
        try {
            val vibrator = getVibrator(ctx) ?: return
            val pattern = longArrayOf(0, 600, 200, 600, 200, 600, 200, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = if (vibrator.hasAmplitudeControl()) {
                    intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
                } else null
                val effect = if (amplitudes != null) {
                    VibrationEffect.createWaveform(pattern, amplitudes, 0)
                } else {
                    VibrationEffect.createWaveform(pattern, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val vibAttrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build()
                    vibrator.vibrate(effect, vibAttrs)
                } else {
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "SOS vibration loop failed", e)
        }
    }

    @Synchronized
    fun stopSosVibration(ctx: Context) {
        try {
            val vibrator = getVibrator(ctx)
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Cancel vibration failed", e)
        }
    }

    @Synchronized
    fun playSosSound(ctx: Context) {
        stopSosSound()
        try {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            lastAudioManager = audioManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                audioFocusRequest = focusRequest
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }

            val sampleRate = 44100
            val durationPerTone = 0.32
            val numSamplesPerTone = (sampleRate * durationPerTone).toInt()
            val totalSamples = numSamplesPerTone * 2
            val pcmData = ShortArray(totalSamples)

            val freq1 = 920.0
            val freq2 = 640.0
            for (i in 0 until numSamplesPerTone) {
                val angle = 2.0 * Math.PI * i / (sampleRate / freq1)
                pcmData[i] = (Math.sin(angle) * Short.MAX_VALUE * 0.95).toInt().toShort()
            }
            for (i in 0 until numSamplesPerTone) {
                val angle = 2.0 * Math.PI * i / (sampleRate / freq2)
                pcmData[numSamplesPerTone + i] = (Math.sin(angle) * Short.MAX_VALUE * 0.95).toInt().toShort()
            }

            val bufferSize = totalSamples * 2
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttrs)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmData, 0, totalSamples)
            track.setLoopPoints(0, totalSamples, -1)
            track.play()
            sosAudioTrack = track

            sosTimeoutRunnable = Runnable { stopSosSound() }.also {
                mainHandler.postDelayed(it, 45_000)
            }
            Log.i(TAG, "Playing dedicated SOS siren on USAGE_ALARM")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to play synthesized SOS siren, falling back to Ringtone", e)
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                sosRingtone = RingtoneManager.getRingtone(ctx.applicationContext, alarmUri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            } catch (err: Throwable) {
                Log.e(TAG, "Ringtone fallback failed", err)
            }
        }
    }

    @Synchronized
    fun stopSosSound() {
        sosTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sosTimeoutRunnable = null
        try {
            sosAudioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop SOS audio track", e)
        } finally {
            sosAudioTrack = null
        }
        try {
            sosRingtone?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop SOS ringtone", e)
        } finally {
            sosRingtone = null
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { lastAudioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                lastAudioManager?.abandonAudioFocus(null)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to abandon audio focus", e)
        } finally {
            audioFocusRequest = null
            lastAudioManager = null
        }
    }
}
