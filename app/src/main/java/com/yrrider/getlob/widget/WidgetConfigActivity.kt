package com.yrrider.getlob.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.WidgetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val BORDER_COLORS = listOf(
    0x80FFFFFFL to "Frosted White",
    0x9938BDF8L to "Neon Cyan",
    0x99F43F5EL to "Rose Pink",
    0x99FBBF24L to "Golden Glow",
    0x99A855F7L to "Lavender",
    0x00000000L to "None",
)

val BG_COLORS = listOf(
    0L to "Theme Default",
    0xFF000000L to "Pitch Black",
    0xFF18181BL to "Charcoal",
    0xFF0F172AL to "Midnight",
    0xFF1E293BL to "Slate",
    0xFF2E1065L to "Violet",
    0xFF4C0519L to "Wine",
    0xFF022C22L to "Forest",
    0xFFFFFFFFL to "Pure White",
)

val FONT_COLORS = listOf(
    0xFFFFFFFFL to "Pure White",
    0xFFFDE047L to "Sun Gold",
    0xFFFDA4AFL to "Soft Rose",
    0xFF38BDF8L to "Cyan",
    0xFF6EE7B7L to "Emerald",
    0xFFC084FCL to "Lavender",
)

val FONT_STYLES = listOf("Calligraphy", "Cursive", "Serif", "Modern")

class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID


        setResult(Activity.RESULT_CANCELED)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF000000),
                    surface = Color(0xFF0D0D0D),
                    onBackground = Color(0xFFFFFFFF),
                    onSurface = Color(0xFFFFFFFF)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF000000)) {
                    ConfigScreen { finalSettings ->

                        LocalStore.writeSettings(this, finalSettings)


                        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()


                        CoroutineScope(Dispatchers.IO).launch {

                            runCatching {
                                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                    val id = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(widgetId)
                                    PairWidget().update(applicationContext, id)
                                }
                            }
                            runCatching { PairWidget().updateAll(applicationContext) }
                            runCatching { Repo.saveSettings(applicationContext, finalSettings) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(onDone: (WidgetSettings) -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(LocalStore.readSettings(ctx)) }
    var transparency by remember(s.bgTransparency) { mutableFloatStateOf(s.bgTransparency) }
    var corner by remember(s.cornerDp) { mutableFloatStateOf(s.cornerDp.toFloat()) }
    val theme = WidgetThemes.all[s.themeIndex.coerceIn(WidgetThemes.all.indices)]

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Customize Widget",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )


        val effectiveBg = if (s.bgColor != 0L) s.bgColor else theme.bg
        val previewBg = if (s.glassEffect) {
            Brush.verticalGradient(
                listOf(
                    Color(effectiveBg).copy(alpha = (transparency * 1.3f).coerceIn(0f, 1f)),
                    Color(effectiveBg).copy(alpha = (transparency * 0.7f).coerceIn(0f, 1f))
                )
            )
        } else {
            Brush.linearGradient(
                listOf(
                    Color(effectiveBg).copy(alpha = transparency),
                    Color(effectiveBg).copy(alpha = transparency)
                )
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(corner.dp))
                .background(previewBg)
                .border(
                    width = (if (s.borderWidthDp > 0) s.borderWidthDp else 0f).dp,
                    color = Color(s.borderColor).let { it.copy(alpha = it.alpha * s.borderTransparency) },
                    shape = RoundedCornerShape(corner.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Goodmorning",
                    color = Color(s.fontColor),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "❤️ Love • ${s.fontStyle} Style",
                    color = Color(s.fontColor).copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Widget Background Colour", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BG_COLORS.forEach { (colorLong, _) ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (colorLong == 0L) Color(0xFF27272A) else Color(colorLong))
                            .border(
                                2.dp,
                                if (s.bgColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                                CircleShape
                            )
                            .clickable { s = s.copy(bgColor = colorLong) }
                    )
                }
            }
        }


        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Glass UI Effect", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Frosted specular reflection & glassy sheen", fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
            Switch(
                checked = s.glassEffect,
                onCheckedChange = { s = s.copy(glassEffect = it) }
            )
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text(
                "Background Transparency: ${(transparency * 100).toInt()}%",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = transparency,
                onValueChange = { transparency = it },
                onValueChangeFinished = { s = s.copy(bgTransparency = transparency) },
                valueRange = 0.05f..1.0f
            )
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Border Colour", fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BORDER_COLORS.forEach { (colorLong, _) ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (colorLong == 0L) Color(0xFF262626) else Color(colorLong))
                            .border(
                                2.dp,
                                if (s.borderColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                                CircleShape
                            )
                            .clickable { s = s.copy(borderColor = colorLong) }
                    )
                }
            }
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Border Transparency: ${(s.borderTransparency * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold)
            Slider(
                value = s.borderTransparency,
                onValueChange = { s = s.copy(borderTransparency = it) },
                valueRange = 0f..1f
            )
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Font Style", fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FONT_STYLES.forEach { style ->
                    FilterChip(
                        selected = s.fontStyle == style,
                        onClick = { s = s.copy(fontStyle = style) },
                        label = { Text(style, fontSize = 12.sp) }
                    )
                }
            }
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Font Colour", fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FONT_COLORS.forEach { (colorLong, _) ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (colorLong == 0L) Color(0xFF27272A) else Color(colorLong))
                            .border(
                                2.dp,
                                if (s.fontColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                                CircleShape
                            )
                            .clickable { s = s.copy(fontColor = colorLong) }
                    )
                }
            }
        }


        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F0F0F))
                .padding(14.dp)
        ) {
            Text("Corner Roundness: ${corner.toInt()} dp", color = Color.White, fontWeight = FontWeight.SemiBold)
            Slider(
                value = corner,
                onValueChange = { corner = it },
                onValueChangeFinished = { s = s.copy(cornerDp = corner.toInt()) },
                valueRange = 8f..36f
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val finalSettings = s.copy(
                    bgTransparency = transparency,
                    cornerDp = corner.toInt()
                )
                onDone(finalSettings)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFFFFF), contentColor = Color(0xFF000000))
        ) {
            Text("Add Widget to Home Screen", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
