package com.yrrider.getlob.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes an image at exactly the size the widget needs, so RemoteViews never gets an oversized
 * bitmap (the launcher drops widgets whose bitmaps exceed its memory budget).
 */
object ImageFit {
    /** Hard cap on pixels per bitmap: ~0.64 MP => ~2.5 MB ARGB. Keeps several frames well under budget. */
    private const val MAX_PIXELS = 640_000

    fun decode(path: String, wPx: Int, hPx: Int, crop: Boolean): Bitmap? {
        if (path.isEmpty() || wPx <= 0 || hPx <= 0) return null
        // Respect the pixel cap while keeping the widget's aspect ratio.
        var tw = wPx
        var th = hPx
        val px = tw.toLong() * th
        if (px > MAX_PIXELS) {
            val k = Math.sqrt(MAX_PIXELS.toDouble() / px)
            tw = (tw * k).roundToInt().coerceAtLeast(1)
            th = (th * k).roundToInt().coerceAtLeast(1)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        // Cheap power-of-two downsample first, then exact scale.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeFile(path, opts) ?: return null
        return scale(src, tw, th, crop)
    }

    fun scale(src: Bitmap, tw: Int, th: Int, crop: Boolean): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        return if (crop) {
            // Fill the box, centre-crop the overflow.
            val k = max(tw / sw, th / sh)
            val scaled = Bitmap.createScaledBitmap(src, (sw * k).roundToInt().coerceAtLeast(tw), (sh * k).roundToInt().coerceAtLeast(th), true)
            val x = (scaled.width - tw) / 2
            val y = (scaled.height - th) / 2
            Bitmap.createBitmap(scaled, x, y, tw, th).also { if (scaled !== src) scaled.recycle() }
        } else {
            // Fit inside the box, keep aspect ratio (letterbox).
            val k = min(tw / sw, th / sh)
            Bitmap.createScaledBitmap(src, (sw * k).roundToInt().coerceAtLeast(1), (sh * k).roundToInt().coerceAtLeast(1), true)
        }
    }
}
