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
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.WidgetSettings
import kotlinx.coroutines.launch

val BORDER_COLORS = listOf(
    0x80FFFFFFL to "Frosted White",
    0x9938BDF8L to "Neon Cyan",
    0x99F43F5EL to "Rose Pink",
    0x99FBBF24L to "Golden Glow",
    0x99A855F7L to "Lavender",
    0x00000000L to "None",
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ConfigScreen {
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(LocalStore.readSettings(ctx)) }
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
        Text("Customize Widget", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))

        // Live Frosted Glass Preview
        val previewBg = if (s.glassEffect) {
            Brush.verticalGradient(
                listOf(
                    Color(theme.bg).copy(alpha = (s.bgTransparency * 1.3f).coerceIn(0f, 1f)),
                    Color(theme.bg).copy(alpha = (s.bgTransparency * 0.7f).coerceIn(0f, 1f))
                )
            )
        } else {
            Brush.linearGradient(listOf(Color(theme.bg).copy(alpha = s.bgTransparency), Color(theme.bg).copy(alpha = s.bgTransparency)))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(s.cornerDp.dp))
                .background(previewBg)
                .border(
                    width = (if (s.borderWidthDp > 0) s.borderWidthDp else 0f).dp,
                    color = Color(s.borderColor),
                    shape = RoundedCornerShape(s.cornerDp.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Goodmorning beta", color = Color(s.fontColor), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("✨ ${s.fontStyle} Style", color = Color(s.fontColor).copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }

        // Glass UI Effect Switch
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Glass UI Effect", fontWeight = FontWeight.SemiBold)
                Text("Frosted specular reflection & glassy sheen", fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
            Switch(
                checked = s.glassEffect,
                onCheckedChange = { s = s.copy(glassEffect = it) }
            )
        }

        // Background Transparency Slider
        Text("Background Transparency: ${(s.bgTransparency * 100).toInt()}%")
        Slider(
            value = s.bgTransparency,
            onValueChange = { s = s.copy(bgTransparency = it) },
            valueRange = 0.05f..1.0f
        )

        // Border Colour
        Text("Border Colour", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BORDER_COLORS.forEach { (colorLong, _) ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (colorLong == 0L) Color(0xFF334155) else Color(colorLong))
                        .border(
                            2.dp,
                            if (s.borderColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                            CircleShape
                        )
                        .clickable { s = s.copy(borderColor = colorLong) }
                )
            }
        }

        // Font Style (Calligraphy, Cursive, Serif, Modern)
        Text("Font Style", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FONT_STYLES.forEach { style ->
                FilterChip(
                    selected = s.fontStyle == style,
                    onClick = { s = s.copy(fontStyle = style) },
                    label = { Text(style, fontSize = 12.sp) }
                )
            }
        }

        // Font Colour
        Text("Font Colour", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FONT_COLORS.forEach { (colorLong, _) ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(colorLong))
                        .border(
                            2.dp,
                            if (s.fontColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                            CircleShape
                        )
                        .clickable { s = s.copy(fontColor = colorLong) }
                )
            }
        }

        // Corner Roundness
        Text("Corner Roundness: ${s.cornerDp} dp")
        Slider(
            value = s.cornerDp.toFloat(),
            onValueChange = { s = s.copy(cornerDp = it.toInt()) },
            valueRange = 8f..36f
        )

        // Base Theme
        Text("Base Color Theme", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetThemes.all.forEachIndexed { i, th ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(th.bg))
                        .border(
                            2.dp,
                            if (i == s.themeIndex) Color(th.accent) else Color(0x33FFFFFF),
                            CircleShape
                        )
                        .clickable { s = s.copy(themeIndex = i) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                LocalStore.writeSettings(ctx, s)
                scope.launch {
                    Repo.saveSettings(ctx, s)
                    PairWidget().updateAll(ctx)
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF0F172A))
        ) {
            Text("Done (Sync to Both Devices)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
