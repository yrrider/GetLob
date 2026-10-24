package com.yrrider.getlob.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.yrrider.getlob.R
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.WidgetSettings
import com.yrrider.getlob.data.WidgetState
import com.yrrider.getlob.ui.OverlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Glance home-screen widget. Displays:
 *  - Frosted Glass UI effect with customizable transparency & border color
 *  - Clean and beautiful canvas with no bottom satellite icons
 *  - High-res Still photo (crop/fit)
 *  - Looping animated Video / GIF slideshow
 *  - Text notes & Mood emojis with Cursive / Calligraphy font styles
 *  - Tapping opens the smooth Google-style floating action overlay
 */
class PairWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PairWidget()
}

class PairWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = LocalStore.read(context)
        val settings = LocalStore.readSettings(context)
        val theme = WidgetThemes.theme(context)
        provideContent { WidgetContent(state, settings, theme) }
    }
}

@Composable
private fun WidgetContent(s: WidgetState, settings: WidgetSettings, theme: WidgetTheme) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val density = ctx.resources.displayMetrics.density

    // Defend against NaN or zero dimensions during initial widget placement
    val wDp = size.width.value.takeIf { !it.isNaN() && it > 10f } ?: 160f
    val hDp = size.height.value.takeIf { !it.isNaN() && it > 10f } ?: 160f
    val wPx = (wDp * density).toInt().coerceIn(80, 400)
    val hPx = (hDp * density).toInt().coerceIn(80, 400)

    val open = actionStartActivity<OverlayActivity>()
    val animated = (s.kind == MsgType.GIF || s.kind == MsgType.VIDEO) && s.frameCount > 1

    // Decode still image
    val still: Bitmap? = remember(s.updatedAt, wPx, hPx, s.fitCrop) {
        if (!animated && s.posterPath.isNotEmpty()) ImageFit.decode(s.posterPath, wPx, hPx, s.fitCrop) else null
    }
    // Animated flipper
    val flipper: RemoteViews? = remember(s.updatedAt, wPx, hPx) {
        if (animated) buildFlipper(ctx, s, wPx, hPx) else null
    }

    // Glass frosted background bitmap (compact size, capped to 400px to avoid IPC parcel limits)
    val glassBg: Bitmap = remember(wPx, hPx, settings, theme) {
        WidgetRenderer.renderGlassBackground(ctx, wPx, hPx, settings, theme)
    }

    // Typography Bitmap for text notes and moods
    val textBmp: Bitmap? = remember(s.updatedAt, s.text, s.kind, wPx, hPx, settings, theme) {
        if (flipper == null && still == null) {
            WidgetRenderer.renderTextOrMoodBitmap(ctx, wPx, hPx, s, settings, theme)
        } else null
    }

    val bgAlpha = settings.bgTransparency.coerceIn(0.05f, 1f)
    val baseColor = Color(theme.bg).copy(alpha = bgAlpha)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(baseColor))
            .cornerRadius(settings.cornerDp.dp)
            .clickable(open),
        contentAlignment = Alignment.Center,
    ) {
        // Layer 1: Frosted Glass UI Background (specular sheen & border)
        Image(
            provider = ImageProvider(glassBg),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize()
        )

        // Layer 2: Clean content (Animated media, photo still, or text note)
        when {
            flipper != null -> {
                AndroidRemoteViews(remoteViews = flipper, modifier = GlanceModifier.fillMaxSize().clickable(open))
            }
            still != null -> {
                Image(
                    provider = ImageProvider(still),
                    contentDescription = null,
                    contentScale = if (s.fitCrop) ContentScale.Crop else ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxSize().padding(if (settings.borderWidthDp > 0) 2.dp else 0.dp),
                )
            }
            textBmp != null -> {
                Image(
                    provider = ImageProvider(textBmp),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxSize()
                )
            }
        }

        // Layer 3: Minimal indicators only (No bottom icons - clean and beautiful widget)
        if (animated || s.kind == MsgType.GIF || s.kind == MsgType.VIDEO) {
            Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .cornerRadius(8.dp)
                            .background(ColorProvider(Color(0x99000000)))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (s.kind == MsgType.VIDEO) "▶ VIDEO" else "GIF",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

private fun buildFlipper(ctx: Context, s: WidgetState, wPx: Int, hPx: Int): RemoteViews? {
    val k = minOf(1f, 320f / maxOf(wPx, hPx))
    val fw = (wPx * k).toInt().coerceAtLeast(1)
    val fh = (hPx * k).toInt().coerceAtLeast(1)
    val root = RemoteViews(ctx.packageName, R.layout.widget_flipper)
    var added = 0
    for (i in 0 until s.frameCount) {
        val f = LocalStore.frameFile(ctx, i)
        if (!f.exists()) continue
        val scaled = ImageFit.decode(f.absolutePath, fw, fh, crop = true) ?: continue
        val rv = RemoteViews(ctx.packageName, R.layout.widget_frame)
        rv.setImageViewBitmap(R.id.frame, scaled)
        root.addView(R.id.flipper, rv)
        added++
    }
    return if (added > 1) root else null
}

class VibeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.IO) {
            runCatching { Repo.send(context, Message(type = MsgType.VIBE)) }
        }
    }
}
