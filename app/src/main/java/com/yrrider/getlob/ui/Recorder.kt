package com.yrrider.getlob.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Minimal AAC/m4a recorder used by hold-to-record (voice note and push-to-talk). */
class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = try {
        val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(22050)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f; startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        release(); false
    }

    /** Returns the recorded file, or null if it was too short / failed. */
    fun stop(): File? {
        val r = rec ?: return null
        val f = file
        val tooShort = System.currentTimeMillis() - startedAt < 500
        try { r.stop() } catch (_: Exception) { f?.delete(); release(); return null }
        release()
        if (tooShort) { f?.delete(); return null }
        return f
    }

    fun release() {
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
