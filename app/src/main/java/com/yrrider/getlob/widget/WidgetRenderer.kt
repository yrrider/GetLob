package com.yrrider.getlob.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.ColorUtils
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.WidgetSettings
import com.yrrider.getlob.data.WidgetState

object WidgetRenderer {
    private const val TAG = "WidgetRenderer"
    private const val MAX_DIM = 280
    private var cachedCursiveTypeface: Typeface? = null
    private var cachedCalligraphyTypeface: Typeface? = null

    fun getTypeface(ctx: Context, fontStyle: String): Typeface {
        return try {
            when (fontStyle) {
                "Calligraphy" -> {
                    cachedCalligraphyTypeface ?: try {
                        Typeface.createFromAsset(ctx.assets, "fonts/calligraphy.ttf")
                    } catch (e: Exception) {
                        Typeface.create("cursive", Typeface.NORMAL)
                    }.also { cachedCalligraphyTypeface = it }
                }
                "Cursive" -> {
                    cachedCursiveTypeface ?: try {
                        Typeface.createFromAsset(ctx.assets, "fonts/cursive.ttf")
                    } catch (e: Exception) {
                        Typeface.create("cursive", Typeface.BOLD)
                    }.also { cachedCursiveTypeface = it }
                }
                "Serif" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                "Modern" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load typeface $fontStyle", e)
            Typeface.DEFAULT
        }
    }

    fun renderCompositeWidget(
        ctx: Context,
        rawW: Int,
        rawH: Int,
        s: WidgetState,
        settings: WidgetSettings,
        theme: WidgetTheme
    ): Bitmap {
        val maxSide = maxOf(rawW.coerceAtLeast(80), rawH.coerceAtLeast(80))
        val scale = if (maxSide > MAX_DIM) MAX_DIM.toFloat() / maxSide else 1f
        val wPx = (rawW * scale).toInt().coerceAtLeast(80)
        val hPx = (rawH * scale).toInt().coerceAtLeast(80)

        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = ctx.resources.displayMetrics.density * (wPx.toFloat() / rawW.coerceAtLeast(1))
        val cornerPx = (settings.cornerDp * density).coerceIn(4f, minOf(wPx, hPx) / 2f)
        val strokeWidthPx = (settings.borderWidthDp * density).coerceAtLeast(1f)
        val inset = strokeWidthPx / 2f
        val rect = RectF(inset, inset, wPx - inset, hPx - inset)

        val alphaInt = (settings.bgTransparency.coerceIn(0.05f, 1f) * 255).toInt()
        val baseColor = if (settings.bgColor != 0L) settings.bgColor.toInt() else theme.bg.toInt()

        if (settings.glassEffect) {
            val topAlpha = (alphaInt * 1.35f).toInt().coerceIn(0, 255)
            val bottomAlpha = (alphaInt * 0.70f).toInt().coerceIn(0, 255)
            val topColor = ColorUtils.setAlphaComponent(baseColor, topAlpha)
            val bottomColor = ColorUtils.setAlphaComponent(baseColor, bottomAlpha)

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(0f, 0f, 0f, hPx.toFloat(), topColor, bottomColor, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
        } else {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ColorUtils.setAlphaComponent(baseColor, alphaInt)
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
        }

        val hasStill = s.posterPath.isNotEmpty() && s.kind != MsgType.SOS
        var drewPhoto = false
        if (hasStill) {
            try {
                val photo = ImageFit.decode(s.posterPath, wPx, hPx, s.fitCrop)
                if (photo != null) {
                    canvas.save()
                    val clipPath = Path().apply {
                        addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
                    }
                    canvas.clipPath(clipPath)
                    val photoRect = RectF(0f, 0f, wPx.toFloat(), hPx.toFloat())
                    canvas.drawBitmap(photo, null, photoRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                    drewPhoto = true
                    if (photo !== bmp) photo.recycle()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to decode still photo for widget", e)
            }
        }

        if (!drewPhoto) {
            drawContentText(canvas, ctx, wPx, hPx, s, settings, theme, density)
        }

        if (settings.glassEffect) {
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, hPx * 0.55f,
                    ColorUtils.setAlphaComponent(0xFFFFFF, (50 * settings.bgTransparency).toInt().coerceIn(5, 60)),
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, sheenPaint)
        }

        if (strokeWidthPx > 0f && settings.borderColor != 0L) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                color = ColorUtils.setAlphaComponent(
                    settings.borderColor.toInt(),
                    (Color.alpha(settings.borderColor.toInt()) * settings.borderTransparency.coerceIn(0f, 1f)).toInt()
                )
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)

            if (settings.glassEffect) {
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeWidthPx * 0.8f
                    shader = LinearGradient(
                        0f, 0f, wPx.toFloat(), 0f,
                        intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, highlightPaint)
            }
        }

        return bmp
    }

    private fun drawContentText(
        canvas: Canvas,
        ctx: Context,
        wPx: Int,
        hPx: Int,
        s: WidgetState,
        settings: WidgetSettings,
        theme: WidgetTheme,
        density: Float
    ) {
        val typeface = getTypeface(ctx, settings.fontStyle)
        val fontColor = settings.fontColor.toInt()
        val isEmoji = s.kind == MsgType.EMOJI

        val padX = (16 * density).toInt()
        val availW = (wPx - padX * 2).coerceAtLeast(10)

        if (s.kind == MsgType.SOS) {
            val minDim = minOf(wPx, hPx)
            val sirenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = minDim * 0.35f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("🚨", wPx / 2f, hPx * 0.42f, sirenPaint)
            val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFEF4444.toInt()
                this.typeface = Typeface.DEFAULT_BOLD
                textSize = (18 * density).coerceIn(14f * density, minDim * 0.16f)
                textAlign = Paint.Align.CENTER
                setShadowLayer(4 * density, 0f, 1f * density, 0xAA000000.toInt())
            }
            val sosText = s.text.ifBlank { "EMERGENCY SOS" }
            canvas.drawText(sosText, wPx / 2f, hPx * 0.72f, alertPaint)
        } else if (isEmoji) {
            val fullText = s.text.trim()
            val parts = fullText.split(" ", limit = 2)
            val emoji = parts.firstOrNull() ?: fullText
            val moodLabel = if (parts.size > 1) parts[1] else ""

            val minDim = minOf(wPx, hPx)
            val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = minDim * 0.40f
                textAlign = Paint.Align.CENTER
            }

            val emojiY = if (moodLabel.isNotEmpty()) hPx * 0.46f else hPx * 0.58f
            canvas.drawText(emoji, wPx / 2f, emojiY, emojiPaint)

            if (moodLabel.isNotEmpty()) {
                val moodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fontColor
                    this.typeface = typeface
                    textSize = (18 * density).coerceIn(13f * density, minDim * 0.16f)
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(3 * density, 0f, 1f * density, 0x77000000)
                }
                canvas.drawText(moodLabel, wPx / 2f, hPx * 0.76f, moodPaint)
            }
        } else if (s.text.isNotEmpty()) {
            val minDim = minOf(wPx, hPx)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fontColor
                this.typeface = typeface
                textSize = (20 * density).coerceIn(14f * density, minDim * 0.17f)
                setShadowLayer(4 * density, 0f, 1.5f * density, 0x88000000)
            }

            @Suppress("DEPRECATION")
            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(s.text, 0, s.text.length, textPaint, availW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.15f)
                    .build()
            } else {
                StaticLayout(
                    s.text, textPaint, availW,
                    Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false
                )
            }

            val totalH = layout.height
            val startY = ((hPx - totalH) / 2f).coerceAtLeast(8 * density)

            canvas.save()
            canvas.translate(padX.toFloat(), startY)
            layout.draw(canvas)
            canvas.restore()
        } else {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.accent.toInt()
                this.typeface = typeface
                textSize = 22 * density
                textAlign = Paint.Align.CENTER
                setShadowLayer(3 * density, 0f, 1f * density, 0x66000000)
            }
            canvas.drawText("GetLob ❤️", wPx / 2f, hPx * 0.46f, titlePaint)

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(fontColor, 190)
                this.typeface = Typeface.DEFAULT
                textSize = 11 * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Tap to connect", wPx / 2f, hPx * 0.62f, subPaint)
        }
    }

    fun renderGlassBackground(ctx: Context, rawW: Int, rawH: Int, settings: WidgetSettings, theme: WidgetTheme): Bitmap =
        renderCompositeWidget(ctx, rawW, rawH, WidgetState(), settings, theme)

    fun renderTextOrMoodBitmap(ctx: Context, rawW: Int, rawH: Int, s: WidgetState, settings: WidgetSettings, theme: WidgetTheme): Bitmap =
        renderCompositeWidget(ctx, rawW, rawH, s, settings, theme)

    fun overlayTextOnBitmap(src: Bitmap, caption: String, fontStyle: String, fontColor: Int): Bitmap {
        if (caption.isBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fontColor
            textSize = (w * 0.048f).coerceIn(24f, 72f)
            typeface = getTypefaceForCaption(fontStyle)
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        }

        val paddingY = textPaint.textSize * 0.6f
        val textHeight = textPaint.descent() - textPaint.ascent()
        val bannerHeight = textHeight + paddingY * 2
        val bannerTop = (h * 0.72f).coerceAtMost(h - bannerHeight - 20f)
        val bannerBottom = bannerTop + bannerHeight

        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, bannerTop, w.toFloat(), bannerBottom, bannerPaint)

        val textY = bannerTop + paddingY - textPaint.ascent()
        canvas.drawText(caption, w / 2f, textY, textPaint)

        return out
    }

    private fun getTypefaceForCaption(fontStyle: String): Typeface = when (fontStyle) {
        "Calligraphy", "Cursive" -> Typeface.create("cursive", Typeface.BOLD)
        "Serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}
