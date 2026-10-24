package com.yrrider.getlob.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.SecureMedia
import com.yrrider.getlob.data.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Turns an incoming Message into something the widget can show:
 *  - image  -> poster still
 *  - gif    -> poster + up to 10 frames (played as a ViewFlipper slideshow)
 *  - video  -> poster + up to 10 frames from the first ~3 s
 *  - others -> text only
 * Media is downloaded and decrypted (if end-to-end encrypted) before processing.
 * Message text must already be decrypted by the caller. Then every widget instance refreshes.
 */
object MediaProcessor {
    private const val MAX_FRAMES = 10
    private const val FRAME_PX = 360          // frames are small on purpose (widget memory limit)
    private const val POSTER_PX = 720

    suspend fun apply(ctx: Context, m: Message, localFile: File? = null) = withContext(Dispatchers.IO) {
        Log.d("MediaProcessor", "apply message type=${m.type}, text=${m.text}, localFile=${localFile?.name}")
        val old = LocalStore.read(ctx)
        val next: WidgetState? = when (m.type) {
            MsgType.TEXT, MsgType.EMOJI -> {
                clear(ctx)
                WidgetState(kind = m.type, text = m.text, posterPath = "", frameCount = 0)
            }

            MsgType.IMAGE -> {
                val f = localFile?.takeIf { it.exists() && it.length() > 0 }
                    ?: runCatching { SecureMedia.fetch(ctx, m.mediaUrl) }.getOrNull()
                if (f != null && f.exists() && f.length() > 0) {
                    clear(ctx)
                    writePoster(ctx, f.absolutePath)
                    WidgetState(
                        kind = MsgType.IMAGE,
                        text = m.text,
                        posterPath = LocalStore.posterFile(ctx).absolutePath,
                        mediaUrl = m.mediaUrl
                    )
                } else {
                    Log.e("MediaProcessor", "Failed to get image file for mediaUrl: ${m.mediaUrl}")
                    null
                }
            }

            MsgType.GIF -> {
                val f = localFile?.takeIf { it.exists() && it.length() > 0 }
                    ?: runCatching { SecureMedia.fetch(ctx, m.mediaUrl) }.getOrNull()
                if (f != null && f.exists() && f.length() > 0) {
                    clear(ctx)
                    val n = extractGifFrames(ctx, f)
                    WidgetState(
                        kind = MsgType.GIF,
                        text = m.text,
                        posterPath = LocalStore.frameFile(ctx, 0).absolutePath,
                        frameCount = n,
                        mediaUrl = m.mediaUrl
                    )
                } else null
            }

            MsgType.VIDEO -> {
                val f = localFile?.takeIf { it.exists() && it.length() > 0 }
                    ?: runCatching { SecureMedia.fetch(ctx, m.mediaUrl) }.getOrNull()
                if (f != null && f.exists() && f.length() > 0) {
                    clear(ctx)
                    val n = extractVideoFrames(ctx, f)
                    WidgetState(
                        kind = MsgType.VIDEO,
                        text = m.text,
                        posterPath = LocalStore.posterFile(ctx).absolutePath,
                        frameCount = n,
                        mediaUrl = m.mediaUrl
                    )
                } else null
            }

            MsgType.SOS -> {
                clear(ctx)
                WidgetState(kind = MsgType.SOS, text = "🚨 SOS ALERT")
            }
            MsgType.GAME -> old.copy(kind = MsgType.GAME, text = m.text.ifEmpty { "🎮 Your turn!" })
            else -> null    // vibe etc. do not change the widget
        }
        if (next != null) {
            LocalStore.write(ctx, next.copy(updatedAt = System.currentTimeMillis()))
            refresh(ctx)
        }
    }

    suspend fun refresh(ctx: Context) {
        runCatching {
            val manager = GlanceAppWidgetManager(ctx)
            val glanceIds = manager.getGlanceIds(PairWidget::class.java)
            glanceIds.forEach { id ->
                PairWidget().update(ctx, id)
            }
        }
        runCatching { PairWidget().updateAll(ctx) }
        runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PairWidgetReceiver::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = ComponentName(ctx, PairWidgetReceiver::class.java)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                ctx.sendBroadcast(intent)
            }
        }
    }

    /** Clears the locally rendered canvas after the paired history is removed. */
    suspend fun clearCanvas(ctx: Context) = withContext(Dispatchers.IO) {
        clear(ctx)
        LocalStore.write(ctx, WidgetState(updatedAt = System.currentTimeMillis()))
        refresh(ctx)
    }

    private fun clear(ctx: Context) {
        LocalStore.framesDir(ctx).listFiles()?.forEach { it.delete() }
        LocalStore.posterFile(ctx).delete()
    }

    private fun writePoster(ctx: Context, srcPath: String) {
        // Large enough for any widget; per-size scaling happens at render time.
        val bmp = ImageFit.decode(srcPath, POSTER_PX, POSTER_PX, crop = false) ?: return
        save(bmp, LocalStore.posterFile(ctx), 85)
    }

    private fun save(bmp: Bitmap, f: File, q: Int) {
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
    }

    @Suppress("DEPRECATION")
    private fun extractGifFrames(ctx: Context, gif: File): Int {
        val movie = Movie.decodeFile(gif.absolutePath) ?: return 0
        val w = movie.width().coerceAtLeast(1)
        val h = movie.height().coerceAtLeast(1)
        val k = minOf(1f, FRAME_PX.toFloat() / maxOf(w, h))
        val dur = movie.duration().coerceAtLeast(1)
        val n = MAX_FRAMES
        for (i in 0 until n) {
            movie.setTime(i * dur / n)
            val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF1E1E2E.toInt())
            c.scale(k, k)
            movie.draw(c, 0f, 0f)
            save(bmp, LocalStore.frameFile(ctx, i), 80)
            bmp.recycle()
        }
        return n
    }

    private fun extractVideoFrames(ctx: Context, video: File): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val poster = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_PX, POSTER_PX)
            if (poster != null) save(poster, LocalStore.posterFile(ctx), 85)
            var count = 0
            for (i in 0 until MAX_FRAMES) {
                val t = i * 300_000L   // every 0.3 s => ~3 s preview
                val f = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_PX, FRAME_PX) ?: break
                save(f, LocalStore.frameFile(ctx, count), 80)
                count++
            }
            return count
        } finally {
            r.release()
        }
    }
}
