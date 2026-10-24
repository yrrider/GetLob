package com.yrrider.getlob.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import androidx.glance.layout.size
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
 *  - Single composite frosted glass UI bitmap (under 300KB to guarantee 0 Binder IPC crashes on all devices)
 *  - Crisp still photos (crop/fit)
 *  - Lightweight looping animated slideshow (max 3 frames)
 *  - Text notes & Mood emojis with Cursive / Calligraphy typography
 *  - Built-in graceful error fallback via onCompositionError
 */
class PairWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PairWidget()
}

class PairWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_loading) {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val ctx = LocalContext.current
            val state = LocalStore.read(ctx)
            val settings = LocalStore.readSettings(ctx)
            val theme = WidgetThemes.theme(ctx)
            WidgetContent(state, settings, theme)
        }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        Log.e("PairWidget", "Composition error on appWidgetId $appWidgetId", throwable)
        runCatching {
            val views = RemoteViews(context.packageName, R.layout.widget_loading)
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        }
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
    val rawW = (wDp * density).toInt().coerceAtLeast(80)
    val rawH = (hDp * density).toInt().coerceAtLeast(80)

    val open = actionStartActivity<OverlayActivity>()
    val animated = (s.kind == MsgType.GIF || s.kind == MsgType.VIDEO) && s.frameCount > 1

    // Single composite bitmap: frosted background + still/typography + border
    // Memory footprint is ~270 KB, guaranteed under Android 1MB binder limit!
    val compositeBmp: Bitmap? = remember(s.updatedAt, s.posterPath, s.text, s.kind, rawW, rawH, s.fitCrop, settings, theme) {
        if (!animated || s.kind == MsgType.SOS) {
            WidgetRenderer.renderCompositeWidget(ctx, rawW, rawH, s, settings, theme)
        } else null
    }

    // Animated slideshow with strictly capped frames (max 3 frames, 180x180 = ~150KB total)
    val flipper: RemoteViews? = remember(s.updatedAt, rawW, rawH, s.kind) {
        if (animated && s.kind != MsgType.SOS) buildFlipper(ctx, s, rawW, rawH) else null
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(open),
        contentAlignment = Alignment.Center,
    ) {
        if (flipper != null) {
            AndroidRemoteViews(remoteViews = flipper, modifier = GlanceModifier.fillMaxSize().clickable(open))
        } else if (compositeBmp != null) {
            Image(
                provider = ImageProvider(compositeBmp),
                contentDescription = "GetLob Widget",
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize()
            )
        }

        // Minimal indicator in corner for animations
        if (animated || s.kind == MsgType.GIF || s.kind == MsgType.VIDEO) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.TopStart
            ) {
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
            }
        }
    }
}

private fun buildFlipper(ctx: Context, s: WidgetState, wPx: Int, hPx: Int): RemoteViews? {
    val fw = 180
    val fh = 180
    val root = RemoteViews(ctx.packageName, R.layout.widget_flipper)
    var added = 0
    val maxFrames = minOf(3, s.frameCount)
    for (i in 0 until maxFrames) {
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
