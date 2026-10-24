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
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.size
import androidx.glance.layout.width
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
import com.yrrider.getlob.ui.LiveTalkActivity
import com.yrrider.getlob.ui.OverlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Glance home-screen widget. Displays:
 *  - Frosted Glass UI effect with customizable transparency & border color
 *  - High-res Still photo (crop/fit)
 *  - Looping animated Video / GIF slideshow
 *  - Text notes & Mood emojis with Cursive / Calligraphy font styles
 *  - Quick buttons for Live Talk & Vibe haptic pulse
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
    val wPx = (size.width.value * density).toInt().coerceAtLeast(1)
    val hPx = (size.height.value * density).toInt().coerceAtLeast(1)
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

    // Glass frosted background bitmap
    val glassBg: Bitmap = remember(wPx, hPx, settings, theme) {
        WidgetRenderer.renderGlassBackground(ctx, wPx, hPx, settings, theme)
    }

    // High quality Typography Bitmap for text notes, moods (Calligraphy / Cursive) or empty state
    val textBmp: Bitmap? = remember(s.updatedAt, s.text, s.kind, wPx, hPx, settings, theme) {
        if (flipper == null && still == null) {
            WidgetRenderer.renderTextOrMoodBitmap(ctx, wPx, hPx, s, settings, theme)
        } else null
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(settings.cornerDp.dp)
            .clickable(open),
        contentAlignment = Alignment.Center,
    ) {
        // Layer 1: Frosted Glass UI Background (transparency, border, specular glass gradient)
        Image(
            provider = ImageProvider(glassBg),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize()
        )

        // Layer 2: Content (Animated media, photo still, or calligraphy/cursive text note)
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

        // Layer 3: Top indicators & bottom quick actions
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            // Top badges
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (animated || s.kind == MsgType.GIF || s.kind == MsgType.VIDEO) {
                    Box(
                        modifier = GlanceModifier
                            .cornerRadius(10.dp)
                            .background(ColorProvider(Color(0x99000000)))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (s.kind == MsgType.VIDEO) "▶ VIDEO" else "GIF",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                // Subtle glowing accent indicator dot
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(ColorProvider(Color(settings.fontColor)))
                ) {}
            }

            Spacer(GlanceModifier.defaultWeight())

            // Bottom Quick Action Buttons: Live Talk & Vibe Haptic
            Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                QuickButton(
                    icon = "📡",
                    theme = theme,
                    action = actionStartActivity<LiveTalkActivity>()
                )
                Spacer(GlanceModifier.width(8.dp))
                QuickButton(
                    icon = "📳",
                    theme = theme,
                    action = actionRunCallback<VibeAction>()
                )
            }
        }
    }
}

@Composable
private fun QuickButton(icon: String, theme: WidgetTheme, action: androidx.glance.action.Action) {
    Box(
        modifier = GlanceModifier
            .size(34.dp)
            .cornerRadius(17.dp)
            .background(ColorProvider(Color(theme.accent).copy(alpha = 0.85f)))
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, style = TextStyle(fontSize = 15.sp))
    }
}

private fun buildFlipper(ctx: Context, s: WidgetState, wPx: Int, hPx: Int): RemoteViews? {
    val k = minOf(1f, 360f / maxOf(wPx, hPx))
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
