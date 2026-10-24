package com.yrrider.getlob.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
    private const val MAX_DIM = 400
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

    /**
     * Creates the glassmorphic frosted background with specular highlights, rounded corners,
     * transparency value, and customizable border.
     * Capped to MAX_DIM (400px) to guarantee it never exceeds Android RemoteViews IPC limits.
     */
    fun renderGlassBackground(
        ctx: Context,
        rawW: Int,
        rawH: Int,
        settings: WidgetSettings,
        theme: WidgetTheme
    ): Bitmap {
        val wPx = rawW.coerceIn(80, MAX_DIM)
        val hPx = rawH.coerceIn(80, MAX_DIM)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = ctx.resources.displayMetrics.density
        val cornerPx = (settings.cornerDp * density * (wPx.toFloat() / rawW.coerceAtLeast(1))).coerceIn(4f, wPx / 2f)
        val strokeWidthPx = (settings.borderWidthDp * density).coerceAtLeast(1f)
        val inset = strokeWidthPx / 2f
        val rect = RectF(inset, inset, wPx - inset, hPx - inset)

        val alphaInt = (settings.bgTransparency.coerceIn(0.05f, 1f) * 255).toInt()
        val baseColor = theme.bg.toInt()

        if (settings.glassEffect) {
            // Glassmorphism effect: Frosted specular gradient fill
            val topAlpha = (alphaInt * 1.35f).toInt().coerceIn(0, 255)
            val bottomAlpha = (alphaInt * 0.70f).toInt().coerceIn(0, 255)
            val topColor = ColorUtils.setAlphaComponent(baseColor, topAlpha)
            val bottomColor = ColorUtils.setAlphaComponent(baseColor, bottomAlpha)

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, hPx.toFloat(),
                    topColor, bottomColor,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)

            // Frosted glass white sheen reflection on upper half
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, hPx * 0.65f,
                    ColorUtils.setAlphaComponent(0xFFFFFF, (70 * settings.bgTransparency).toInt().coerceIn(10, 80)),
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, sheenPaint)

            // Crisp glass border stroke
            if (strokeWidthPx > 0f && settings.borderColor != 0L) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeWidthPx
                    color = settings.borderColor.toInt()
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)

                // Top rim bevel specular highlight
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeWidthPx * 0.8f
                    shader = LinearGradient(
                        0f, 0f, wPx.toFloat(), 0f,
                        intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, highlightPaint)
            }
        } else {
            // Solid/translucent clean flat style
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ColorUtils.setAlphaComponent(baseColor, alphaInt)
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)

            if (strokeWidthPx > 0f && settings.borderColor != 0L) {
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokeWidthPx
                    color = settings.borderColor.toInt()
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)
            }
        }

        return bmp
    }

    /**
     * Renders text note or emoji/mood into a compact Bitmap using the selected font style
     * (Calligraphy, Cursive, Serif, Modern), custom font color, and multiline wrapping.
     * Note: "Partner's Mood" label is completely removed; only emoji and mood are shown!
     */
    fun renderTextOrMoodBitmap(
        ctx: Context,
        rawW: Int,
        rawH: Int,
        s: WidgetState,
        settings: WidgetSettings,
        theme: WidgetTheme
    ): Bitmap {
        val wPx = rawW.coerceIn(80, MAX_DIM)
        val hPx = rawH.coerceIn(80, MAX_DIM)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val density = ctx.resources.displayMetrics.density * (wPx.toFloat() / rawW.coerceAtLeast(1))
        val typeface = getTypeface(ctx, settings.fontStyle)
        val fontColor = settings.fontColor.toInt()
        val isEmoji = s.kind == MsgType.EMOJI

        val padX = (16 * density).toInt()
        val availW = (wPx - padX * 2).coerceAtLeast(10)

        if (isEmoji) {
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

        return bmp
    }

    /**
     * Burns caption text directly onto an image bitmap (Snapchat style).
     * Renders a translucent banner with centered bold/stylish text.
     */
    fun overlayTextOnBitmap(src: Bitmap, caption: String, fontStyle: String, fontColor: Int): Bitmap {
        if (caption.isBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fontColor
            textSize = (w * 0.048f).coerceIn(24f, 72f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        }

        val paddingY = textPaint.textSize * 0.6f
        val textHeight = textPaint.descent() - textPaint.ascent()
        val bannerHeight = textHeight + paddingY * 2
        val bannerTop = (h * 0.72f).coerceAtMost(h - bannerHeight - 20f)
        val bannerBottom = bannerTop + bannerHeight

        // Translucent dark banner like Snapchat
        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, bannerTop, w.toFloat(), bannerBottom, bannerPaint)

        // Text centered in banner
        val textY = bannerTop + paddingY - textPaint.ascent()
        canvas.drawText(caption, w / 2f, textY, textPaint)

        return out
    }
}
