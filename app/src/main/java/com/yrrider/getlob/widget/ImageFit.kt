package com.yrrider.getlob.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageFit {

    private const val MAX_PIXELS = 160_000

    fun compressForUpload(
        ctx: Context,
        srcFile: File,
        maxDim: Int = 1080,
        quality: Int = 82
    ): File {
        if (!srcFile.exists() || srcFile.length() == 0L) return srcFile

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return srcFile

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(srcFile.absolutePath, opts) ?: return srcFile

        val rotated = fixOrientation(srcFile.absolutePath, decoded)

        val maxSide = max(rotated.width, rotated.height)
        val finalBmp = if (maxSide > maxDim) {
            val scale = maxDim.toFloat() / maxSide
            val tw = (rotated.width * scale).roundToInt().coerceAtLeast(1)
            val th = (rotated.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, tw, th, true).also {
                if (it !== rotated && rotated !== decoded) rotated.recycle()
            }
        } else {
            rotated
        }

        val outFile = File(ctx.cacheDir, "fast_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (finalBmp !== decoded) finalBmp.recycle()
        decoded.recycle()
        return outFile
    }

    private fun fixOrientation(path: String, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        } catch (e: Throwable) {
            bmp
        }
    }

    fun decode(path: String, wPx: Int, hPx: Int, crop: Boolean): Bitmap? {
        if (path.isEmpty() || wPx <= 0 || hPx <= 0) return null

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

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeFile(path, opts) ?: return null
        val result = scale(src, tw, th, crop)
        if (result !== src) src.recycle()
        return result
    }

    fun scale(src: Bitmap, tw: Int, th: Int, crop: Boolean): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        return if (crop) {

            val k = max(tw / sw, th / sh)
            val scaled = Bitmap.createScaledBitmap(src, (sw * k).roundToInt().coerceAtLeast(tw), (sh * k).roundToInt().coerceAtLeast(th), true)
            val x = (scaled.width - tw) / 2
            val y = (scaled.height - th) / 2
            Bitmap.createBitmap(scaled, x, y, tw, th).also { if (scaled !== src) scaled.recycle() }
        } else {

            val k = min(tw / sw, th / sh)
            Bitmap.createScaledBitmap(src, (sw * k).roundToInt().coerceAtLeast(1), (sh * k).roundToInt().coerceAtLeast(1), true)
        }
    }
}
