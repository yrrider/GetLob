package com.yrrider.getlob.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yrrider.getlob.R
import com.yrrider.getlob.data.WidgetSettings
import com.yrrider.getlob.widget.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    widgetSettings: WidgetSettings,
    partnerNameInput: String,
    onPartnerNameChange: (String) -> Unit,
    onSavePartnerName: () -> Unit,
    inviteCode: String,
    fingerprint: String,
    localTransparency: Float,
    onTransparencyChange: (Float) -> Unit,
    onTransparencyFinished: () -> Unit,
    localCorner: Float,
    onCornerChange: (Float) -> Unit,
    onCornerFinished: () -> Unit,
    hasUnsavedWidgetChanges: Boolean,
    onUpdateSettings: (WidgetSettings) -> Unit,
    onSaveSettings: () -> Unit,
    onBack: () -> Unit,
    onClearCanvas: () -> Unit,
    onUnpair: () -> Unit,
    onBatteryClick: () -> Unit
) {
    val ctx = LocalContext.current
    val currentTheme = WidgetThemes.all[widgetSettings.themeIndex.coerceIn(WidgetThemes.all.indices)]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back to Chat",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F2C34))
            )
        },
        containerColor = Color(0xFF0B141B)
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202C33))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Partner & Profile",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Text("Partner's Display Name (Stored Locally)", fontSize = 12.sp, color = Color(0xFF8696A0))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = partnerNameInput,
                            onValueChange = { onPartnerNameChange(it.take(30)) },
                            placeholder = { Text("e.g. My Love, Sophia") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onSavePartnerName,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00A884),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF202C33))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Paired via Code", fontSize = 12.sp, color = Color(0xFF8696A0))
                            Text(inviteCode, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF25D366))
                        }
                        Surface(
                            color = Color(0xFF14532D),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "Connected",
                                color = Color(0xFF86EFAC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202C33))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "✨ Widget Appearance & Style",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            if (hasUnsavedWidgetChanges) "Unsaved" else "Saved",
                            fontSize = 11.sp,
                            color = if (hasUnsavedWidgetChanges) Color(0xFFFBBF24) else Color(0xFF25D366)
                        )
                    }

                    val effectiveBgColor = if (widgetSettings.bgColor != 0L) widgetSettings.bgColor else currentTheme.bg
                    val previewBg = if (widgetSettings.glassEffect) {
                        Brush.verticalGradient(
                            listOf(
                                Color(effectiveBgColor).copy(alpha = (localTransparency * 1.3f).coerceIn(0f, 1f)),
                                Color(effectiveBgColor).copy(alpha = (localTransparency * 0.7f).coerceIn(0f, 1f))
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                Color(effectiveBgColor).copy(alpha = localTransparency),
                                Color(effectiveBgColor).copy(alpha = localTransparency)
                            )
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(localCorner.dp))
                            .background(previewBg)
                            .border(
                                width = (if (widgetSettings.borderWidthDp > 0) widgetSettings.borderWidthDp else 0f).dp,
                                color = Color(widgetSettings.borderColor).let { it.copy(alpha = it.alpha * widgetSettings.borderTransparency) },
                                shape = RoundedCornerShape(localCorner.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Good morning ❤️",
                                color = Color(widgetSettings.fontColor),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Widget Preview • ${widgetSettings.fontStyle}",
                                color = Color(widgetSettings.fontColor).copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Column {
                        Text("Widget Background Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BG_COLORS.forEach { (colorLong, _) ->
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (colorLong == 0L) Color(0xFF27272A) else Color(colorLong))
                                        .border(
                                            2.dp,
                                            if (widgetSettings.bgColor == colorLong) Color(0xFF25D366) else Color(0x33FFFFFF),
                                            CircleShape
                                        )
                                        .clickable { onUpdateSettings(widgetSettings.copy(bgColor = colorLong)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (colorLong == 0L) {
                                        Text("T", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Glass UI Effect", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Frosted gradient, specular sheen & translucent glow", fontSize = 11.sp, color = Color(0xFF8696A0))
                        }
                        Switch(
                            checked = widgetSettings.glassEffect,
                            onCheckedChange = { onUpdateSettings(widgetSettings.copy(glassEffect = it)) }
                        )
                    }

                    Column {
                        Text("Transparency: ${(localTransparency * 100).toInt()}%", fontSize = 13.sp, color = Color.White)
                        Slider(
                            value = localTransparency,
                            onValueChange = onTransparencyChange,
                            onValueChangeFinished = onTransparencyFinished,
                            valueRange = 0.05f..1.0f
                        )
                    }

                    Column {
                        Text("Font Style", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FONT_STYLES.forEach { style ->
                                FilterChip(
                                    selected = widgetSettings.fontStyle == style,
                                    onClick = { onUpdateSettings(widgetSettings.copy(fontStyle = style)) },
                                    label = { Text(style, fontSize = 12.sp) }
                                )
                            }
                        }
                    }

                    Column {
                        Text("Font Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FONT_COLORS.forEach { (colorLong, _) ->
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (colorLong == 0L) Color(0xFF27272A) else Color(colorLong))
                                        .border(
                                            2.dp,
                                            if (widgetSettings.fontColor == colorLong) Color(0xFF25D366) else Color(0x33FFFFFF),
                                            CircleShape
                                        )
                                        .clickable { onUpdateSettings(widgetSettings.copy(fontColor = colorLong)) }
                                )
                            }
                        }
                    }

                    Column {
                        Text("Border Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BORDER_COLORS.forEach { (colorLong, _) ->
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (colorLong == 0L) Color(0xFF262626) else Color(colorLong))
                                        .border(
                                            2.dp,
                                            if (widgetSettings.borderColor == colorLong) Color(0xFF25D366) else Color(0x33FFFFFF),
                                            CircleShape
                                        )
                                        .clickable { onUpdateSettings(widgetSettings.copy(borderColor = colorLong)) }
                                )
                            }
                        }
                    }

                    Column {
                        Text("Corner Roundness: ${localCorner.toInt()} dp", fontSize = 13.sp, color = Color.White)
                        Slider(
                            value = localCorner,
                            onValueChange = onCornerChange,
                            onValueChangeFinished = onCornerFinished,
                            valueRange = 8f..36f
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (widgetSettings.fitCrop) "Crop to Fill Canvas" else "Fit Whole Media (Letterbox)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                "Controls how photos scale on the home widget",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8696A0)
                            )
                        }
                        Switch(
                            checked = widgetSettings.fitCrop,
                            onCheckedChange = { onUpdateSettings(widgetSettings.copy(fitCrop = it)) }
                        )
                    }

                    Button(
                        onClick = onSaveSettings,
                        enabled = hasUnsavedWidgetChanges,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A884),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Widget Changes", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202C33))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🔒 End-to-End Encryption",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF25D366)
                        )
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = Color(0xFF14532D),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "Fingerprint: $fingerprint",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF86EFAC),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        "All messages, photos, videos, and live satellite calls are encrypted with your secret shared passphrase configured during first-time pairing setup. Compare your safety fingerprint with your partner to confirm your connection is verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8696A0),
                        lineHeight = 16.sp
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202C33))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Actions & Performance",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        val canFullScreen = nm?.canUseFullScreenIntent() ?: true
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.parse("package:${ctx.packageName}")
                                    }
                                    ctx.startActivity(intent)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (canFullScreen) "🚨 Full-Screen SOS Alarm: Allowed (Tap to manage)" else "⚠️ Allow Full-Screen SOS Alarm",
                                fontSize = 13.sp,
                                color = if (canFullScreen) Color(0xFF86EFAC) else Color(0xFFFFD279)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onBatteryClick,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Allow Unrestricted Battery (Instant Push)", fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = onClearCanvas,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧹 Clear Chat & Widget Canvas", color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    }

                    Button(
                        onClick = onUnpair,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3B1215),
                            contentColor = Color(0xFFF87171)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF991B1B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💔 Disconnect / Unpair", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Made With ❤️ For ❤️ by Yrrider",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFF43F5E),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "GetLob • Always Connected",
                    fontSize = 11.sp,
                    color = Color(0xFF8696A0),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
