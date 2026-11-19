package com.yrrider.getlob.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PreparedImage(val file: File, val thumbB64: String)

object ImageFit {

    private const val MAX_PIXELS = 190_000

    fun prepareImageForUpload(
        ctx: Context,
        uri: Uri,
        maxDim: Int = 1080,
        quality: Int = 82
    ): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val f = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return PreparedImage(f, "")
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Could not decode image")

        val orientation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        val rotated = if (!matrix.isIdentity) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val maxSide = max(rotated.width, rotated.height)
        val finalBmp = if (maxSide > maxDim) {
            val scale = maxDim.toFloat() / maxSide
            val tw = (rotated.width * scale).roundToInt().coerceAtLeast(1)
            val th = (rotated.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, tw, th, true).also {
                if (it !== rotated) rotated.recycle()
            }
        } else {
            rotated
        }

        val thumbB64 = runCatching {
            val thumbBmp = Bitmap.createScaledBitmap(finalBmp, 48, 48, true)
            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.JPEG
            }
            thumbBmp.compress(format, 50, baos)
            thumbBmp.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")

        val outFile = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { out ->
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        finalBmp.recycle()

        return PreparedImage(outFile, thumbB64)
    }

    fun prepareFileForUpload(
        ctx: Context,
        srcFile: File,
        caption: String = "",
        fontStyle: String = "",
        fontColor: Int = 0xFFFFFFFF.toInt(),
        maxDim: Int = 1080,
        quality: Int = 82
    ): PreparedImage {
        val prep = prepareImageForUpload(ctx, Uri.fromFile(srcFile), maxDim, quality)
        if (caption.isBlank()) return prep

        val bmp = BitmapFactory.decodeFile(prep.file.absolutePath) ?: return prep
        val stamped = WidgetRenderer.overlayTextOnBitmap(bmp, caption, fontStyle, fontColor)
        val stampedFile = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
        FileOutputStream(stampedFile).use { stamped.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        prep.file.delete()
        if (stamped !== bmp) stamped.recycle()
        bmp.recycle()
        return PreparedImage(stampedFile, prep.thumbB64)
    }

    fun prepareImageForUpload(
        ctx: Context,
        srcFile: File,
        caption: String = "",
        fontStyle: String = "",
        fontColor: Int = 0xFFFFFFFF.toInt(),
        maxDim: Int = 1080,
        quality: Int = 82
    ): PreparedImage {
        return prepareFileForUpload(ctx, srcFile, caption, fontStyle, fontColor, maxDim, quality)
    }

    fun compressForUpload(
        ctx: Context,
        srcFile: File,
        maxDim: Int = 1080,
        quality: Int = 82
    ): File {
        return prepareFileForUpload(ctx, srcFile, maxDim = maxDim, quality = quality).file
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
