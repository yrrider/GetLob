package com.yrrider.getlob.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import coil.compose.AsyncImage
import com.google.firebase.messaging.FirebaseMessaging
import com.yrrider.getlob.data.*
import com.yrrider.getlob.fcm.Notifier
import com.yrrider.getlob.widget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Notifier.createChannels(this)
        } catch (e: Throwable) {
            Log.e("GetLob", "Failed to create notification channels", e)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFFF43F5E),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    surfaceVariant = Color(0xFF334155),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC),
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private val EMOJI_MOODS = listOf(
    "❤️" to "Love",
    "🥰" to "Adore",
    "✨" to "Vibe",
    "🔥" to "Energy",
    "☕" to "Coffee",
    "😴" to "Sleepy",
    "🥺" to "Miss you",
    "🎉" to "Happy",
    "🎧" to "Music",
    "💪" to "Grind"
)

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf("") }
    var pairId by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf(with(LocalStore) { ctx.pairCode }) }
    var joinCode by remember { mutableStateOf("") }
    var fitCrop by remember { mutableStateOf(LocalStore.read(ctx).fitCrop) }
    var error by remember { mutableStateOf("") }
    var busySending by remember { mutableStateOf(false) }

    // Text & Mood Composer
    var messageText by remember { mutableStateOf("") }
    var selectedMood by remember { mutableStateOf("") }

    // End-to-end encryption state
    var keyTick by remember { mutableIntStateOf(0) }
    var passphrase by remember { mutableStateOf("") }
    val encrypted = remember(keyTick) { Crypto.hasKey(ctx) }
    val fingerprint = remember(keyTick) { Crypto.fingerprint(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    suspend fun sendAndShow(m: Message) {
        Repo.send(ctx, m)
        if (m.type in listOf(MsgType.TEXT, MsgType.EMOJI, MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO)) {
            runCatching { MediaProcessor.apply(ctx, m) }
        }
    }

    fun executeSend(label: String, block: suspend () -> Unit) {
        scope.launch {
            busySending = true
            try {
                block()
                toast("$label sent to partner!")
            } catch (e: Exception) {
                toast("Send failed: ${e.message}")
            } finally {
                busySending = false
            }
        }
    }

    // Media Pickers
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            executeSend("Photo") {
                val small = withContext(Dispatchers.IO) { downscaleToCache(ctx, uri) }
                val url = Repo.upload(ctx, small, "jpg")
                sendAndShow(Message(type = MsgType.IMAGE, mediaUrl = url))
            }
        }
    }

    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            executeSend("GIF") {
                val url = Repo.upload(ctx, uri, "gif")
                sendAndShow(Message(type = MsgType.GIF, mediaUrl = url))
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            executeSend("Video") {
                val url = Repo.upload(ctx, uri, "mp4")
                sendAndShow(Message(type = MsgType.VIDEO, mediaUrl = url))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        try {
            me = Repo.uid()
            runCatching {
                val t = FirebaseMessaging.getInstance().token.await()
                Repo.saveToken(t)
            }
            pairId = Repo.pairId(ctx)
            if (inviteCode.isEmpty()) {
                inviteCode = with(LocalStore) { ctx.pairCode }
            }
        } catch (e: Throwable) {
            Log.e("GetLob", "Init error", e)
            error = e.message ?: "Setup failed"
        } finally {
            ready = true
        }
    }

    // Real-time Pair State (Listens for member count)
    val pairInfo by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.pairFlow(pairId) else flowOf(null)
    }.collectAsState(initial = null)

    // Update invite code from firestore pair document if available
    LaunchedEffect(pairInfo) {
        pairInfo?.let { info ->
            if (info.code.isNotEmpty()) inviteCode = info.code
        }
    }

    val isConnectedWithPartner = (pairInfo?.members?.size ?: 0) >= 2
    val history by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.messagesFlow(pairId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Shared synced widget settings (transparency, border color, font style/color, partner name)
    var widgetSettings by remember { mutableStateOf(LocalStore.readSettings(ctx)) }
    var partnerNameInput by remember { mutableStateOf(widgetSettings.partnerName) }

    val syncedSettings by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.settingsFlow(pairId) else flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(syncedSettings) {
        syncedSettings?.let { newS ->
            if (newS != widgetSettings) {
                widgetSettings = newS
                partnerNameInput = newS.partnerName
                LocalStore.writeSettings(ctx, newS)
                PairWidget().updateAll(ctx)
            }
        }
    }

    fun updateSettings(newS: WidgetSettings) {
        widgetSettings = newS
        LocalStore.writeSettings(ctx, newS)
        scope.launch {
            Repo.saveSettings(ctx, newS)
            PairWidget().updateAll(ctx)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // App Header
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFFF43F5E)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("❤️", fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "GetLob",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        when {
                            isConnectedWithPartner -> "Connected canvas"
                            pairId.isNotEmpty() -> "Waiting for partner…"
                            else -> "Pair setup"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConnectedWithPartner) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                    )
                }
            }

            if (isConnectedWithPartner) {
                FilledTonalButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(ctx, LiveTalkActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        )
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📡 Tap to Talk", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (error.isNotEmpty()) {
            Surface(
                color = Color(0x33EF4444),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    error,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
            return@Column
        }

        // Show Setup screen if not paired OR waiting for partner (only 1 member)
        if (pairId.isEmpty() || !isConnectedWithPartner) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (pairId.isNotEmpty()) "Share Your Code With Partner" else "Pair with Your Partner",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        if (pairId.isNotEmpty())
                            "Give this 6-character code to your partner to enter on their phone:"
                        else
                            "One person taps 'Create a Pair Code', and the other person enters that code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    if (inviteCode.isNotEmpty() || (pairInfo?.code?.isNotEmpty() == true)) {
                        val activeCode = (pairInfo?.code?.ifEmpty { null }) ?: inviteCode
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("YOUR PAIR CODE", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    activeCode,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    letterSpacing = 4.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFFFBBF24))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Waiting for partner to enter code…", fontSize = 12.sp, color = Color(0xFFFBBF24))
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    Repo.unpair(ctx)
                                    pairId = ""
                                    inviteCode = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel / Reset Code")
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        inviteCode = Repo.createPair(ctx)
                                        pairId = Repo.pairId(ctx)
                                    } catch (e: Exception) {
                                        error = e.message ?: "Failed to generate code"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("Create a Pair Code")
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFF334155))
                        Spacer(Modifier.height(16.dp))

                        Text("Or enter your partner's code:", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = { joinCode = it.uppercase().take(6) },
                            singleLine = true,
                            placeholder = { Text("6-CHAR CODE") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        Repo.joinPair(ctx, joinCode)
                                        pairId = Repo.pairId(ctx)
                                    } catch (e: Exception) {
                                        error = e.message ?: "Invalid code"
                                    }
                                }
                            },
                            enabled = joinCode.length == 6,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Join Partner")
                        }
                    }
                }
            }
        } else {
            // Main Connected Hub (Both Phones Successfully Paired)
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Widget Actions Card (Add Photo, GIF, Video, Text, Mood)
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Update Partner's Widget",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                if (busySending) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            // Quick Media Action Buttons (Image, GIF, Video, Vibe)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ActionTile(
                                    modifier = Modifier.weight(1f),
                                    icon = "📸",
                                    label = "Image",
                                    color = Color(0xFF0284C7),
                                    onClick = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                )
                                ActionTile(
                                    modifier = Modifier.weight(1f),
                                    icon = "🎞️",
                                    label = "GIF",
                                    color = Color(0xFF8B5CF6),
                                    onClick = {
                                        gifPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"))
                                        )
                                    }
                                )
                                ActionTile(
                                    modifier = Modifier.weight(1f),
                                    icon = "📹",
                                    label = "Video",
                                    color = Color(0xFFEC4899),
                                    onClick = {
                                        videoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    }
                                )
                                ActionTile(
                                    modifier = Modifier.weight(1f),
                                    icon = "📳",
                                    label = "Vibe",
                                    color = Color(0xFF10B981),
                                    onClick = {
                                        executeSend("Vibe") {
                                            Repo.send(ctx, Message(type = MsgType.VIBE))
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            // Mood or Emoji selector
                            Text("Share Mood / Reaction", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(EMOJI_MOODS) { (emoji, label) ->
                                    FilterChip(
                                        selected = selectedMood == emoji,
                                        onClick = {
                                            selectedMood = emoji
                                            executeSend("Mood") {
                                                sendAndShow(Message(type = MsgType.EMOJI, text = emoji))
                                            }
                                        },
                                        label = { Text("$emoji $label", fontSize = 12.sp) }
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Text composer for widget
                            Text("Send Widget Text Note", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = messageText,
                                    onValueChange = { messageText = it.take(280) },
                                    placeholder = { Text("Note for their home screen…", fontSize = 13.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val text = messageText.trim()
                                        if (text.isNotEmpty()) {
                                            executeSend("Text Note") {
                                                sendAndShow(Message(type = MsgType.TEXT, text = text))
                                                messageText = ""
                                            }
                                        }
                                    },
                                    enabled = messageText.isNotBlank() && !busySending,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                                ) {
                                    Text("Post")
                                }
                            }
                        }
                    }
                }

                // Widget Glass UI & Shared Partner Settings Card (Auto-synced to both devices)
                item {
                    val currentTheme = WidgetThemes.all[widgetSettings.themeIndex.coerceIn(WidgetThemes.all.indices)]
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "✨ Glass UI & Widget Styles",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text("Auto-syncs 🔄", fontSize = 11.sp, color = Color(0xFF4ADE80))
                            }

                            // Partner Name (shown in notification headers on both devices)
                            Column {
                                Text("Partner's Display Name (In Notifications)", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = partnerNameInput,
                                        onValueChange = { partnerNameInput = it.take(30) },
                                        placeholder = { Text("e.g. My Love, Sophia, Sweetheart") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val trimmed = partnerNameInput.trim().ifEmpty { "Partner" }
                                            updateSettings(widgetSettings.copy(partnerName = trimmed))
                                            toast("Partner name saved & synced!")
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                                    ) {
                                        Text("Save")
                                    }
                                }
                            }

                            // Live Frosted Glass Preview
                            val previewBg = if (widgetSettings.glassEffect) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(currentTheme.bg).copy(alpha = (widgetSettings.bgTransparency * 1.3f).coerceIn(0f, 1f)),
                                        Color(currentTheme.bg).copy(alpha = (widgetSettings.bgTransparency * 0.7f).coerceIn(0f, 1f))
                                    )
                                )
                            } else {
                                Brush.linearGradient(listOf(Color(currentTheme.bg).copy(alpha = widgetSettings.bgTransparency), Color(currentTheme.bg).copy(alpha = widgetSettings.bgTransparency)))
                            }

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(widgetSettings.cornerDp.dp))
                                    .background(previewBg)
                                    .border(
                                        width = (if (widgetSettings.borderWidthDp > 0) widgetSettings.borderWidthDp else 0f).dp,
                                        color = Color(widgetSettings.borderColor),
                                        shape = RoundedCornerShape(widgetSettings.cornerDp.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Goodmorning beta", color = Color(widgetSettings.fontColor), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(2.dp))
                                    Text("❤️ Love • ${widgetSettings.fontStyle} Style", color = Color(widgetSettings.fontColor).copy(alpha = 0.8f), fontSize = 12.sp)
                                }
                            }

                            // Glass UI Effect Toggle
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Glass UI Effect", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Frosted gradient, specular sheen & translucent glow", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                                Switch(
                                    checked = widgetSettings.glassEffect,
                                    onCheckedChange = { updateSettings(widgetSettings.copy(glassEffect = it)) }
                                )
                            }

                            // Transparency Slider
                            Column {
                                Text("Transparency: ${(widgetSettings.bgTransparency * 100).toInt()}%", fontSize = 13.sp, color = Color.White)
                                Slider(
                                    value = widgetSettings.bgTransparency,
                                    onValueChange = { updateSettings(widgetSettings.copy(bgTransparency = it)) },
                                    valueRange = 0.05f..1.0f
                                )
                            }

                            // Font Style Selector (Calligraphy, Cursive, Serif, Modern)
                            Column {
                                Text("Font Style (Synced to Both Phones)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FONT_STYLES.forEach { style ->
                                        FilterChip(
                                            selected = widgetSettings.fontStyle == style,
                                            onClick = { updateSettings(widgetSettings.copy(fontStyle = style)) },
                                            label = { Text(style, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }

                            // Font Colour Selector
                            Column {
                                Text("Font Colour (Both Devices)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FONT_COLORS.forEach { (colorLong, _) ->
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(colorLong))
                                                .border(
                                                    2.dp,
                                                    if (widgetSettings.fontColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                                                    CircleShape
                                                )
                                                .clickable { updateSettings(widgetSettings.copy(fontColor = colorLong)) }
                                        )
                                    }
                                }
                            }

                            // Border Colour Selector
                            Column {
                                Text("Border Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    BORDER_COLORS.forEach { (colorLong, _) ->
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(if (colorLong == 0L) Color(0xFF334155) else Color(colorLong))
                                                .border(
                                                    2.dp,
                                                    if (widgetSettings.borderColor == colorLong) Color(0xFF38BDF8) else Color(0x33FFFFFF),
                                                    CircleShape
                                                )
                                                .clickable { updateSettings(widgetSettings.copy(borderColor = colorLong)) }
                                        )
                                    }
                                }
                            }

                            // Image Fit / Crop switch
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
                                        "Controls how photos, video stills and GIFs scale",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Switch(
                                    checked = widgetSettings.fitCrop,
                                    onCheckedChange = {
                                        fitCrop = it
                                        updateSettings(widgetSettings.copy(fitCrop = it))
                                        scope.launch { MediaProcessor.refresh(ctx) }
                                    }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("⚡ Battery Optimization", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            Repo.unpair(ctx)
                                            pairId = ""
                                            inviteCode = ""
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5))
                                ) {
                                    Text("Unpair", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Security / Passphrase Encryption Card
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (encrypted) "🔒 End-to-End Encryption ON" else "🔓 Encryption Not Set",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (encrypted) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                                )
                                if (encrypted) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        color = Color(0xFF14532D),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Code: $fingerprint",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF86EFAC),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Enter the same passphrase on both devices. Audio, images, videos & texts are encrypted before leaving your phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { passphrase = it },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text("Shared Secret Passphrase (8+ chars)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        Crypto.setPassphrase(ctx, pairId, passphrase.trim())
                                        passphrase = ""
                                        keyTick++
                                        toast("Passphrase set! Verify code matches partner's.")
                                    },
                                    enabled = passphrase.trim().length >= 8,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Set Passphrase")
                                }
                                if (encrypted) {
                                    OutlinedButton(
                                        onClick = {
                                            Crypto.clear(ctx)
                                            keyTick++
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Turn Off")
                                    }
                                }
                            }
                        }
                    }
                }

                // History Feed
                item {
                    Text(
                        "Recent Canvas History",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (history.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Send an image, GIF, video, or note above!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                } else {
                    items(history, key = { it.id }) { m ->
                        HistoryRow(m = m, mine = m.from == me, keyTick = keyTick)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun HistoryRow(m: Message, mine: Boolean, keyTick: Int) {
    val ctx = LocalContext.current
    val loader = remember { mediaLoader(ctx) }
    val text = remember(m.text, keyTick) { Crypto.decryptText(ctx, m.text) }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = m.type in listOf(MsgType.VOICE, MsgType.PTT, MsgType.GIF, MsgType.VIDEO)) {
                ctx.startActivity(
                    Intent(ctx, OverlayActivity::class.java)
                        .putExtra(OverlayActivity.EXTRA_PLAY_URL, m.mediaUrl)
                        .putExtra(OverlayActivity.EXTRA_PLAY_KIND, m.type)
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (mine) Color(0xFF1E293B) else Color(0xFF1E1B4B)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${if (mine) "You" else "Partner"} • ${m.type.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) Color(0xFF38BDF8) else Color(0xFFF43F5E),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }

            if (text.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text, color = Color.White, fontSize = 15.sp)
            }

            if (m.type in listOf(MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO) && m.mediaUrl.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val model = rememberMedia(m.mediaUrl)
                if (model != null) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp))) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            imageLoader = loader,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        )
                        if (m.type == MsgType.VIDEO || m.type == MsgType.GIF) {
                            Surface(
                                color = Color(0x99000000),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            ) {
                                Text(
                                    if (m.type == MsgType.VIDEO) "▶ Tap to play video" else "GIF",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text("🔒 Decrypting media…", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                }
            }

            if (m.type == MsgType.VOICE || m.type == MsgType.PTT) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎙️", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("Tap to listen to audio clip", color = Color(0xFF38BDF8), fontSize = 13.sp)
                }
            }
        }
    }
}

private fun downscaleToCache(ctx: Context, uri: Uri): File {
    val src = File(ctx.cacheDir, "pick_src")
    ctx.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(src).use { i.copyTo(it) } }
    val bmp = ImageFit.decode(src.absolutePath, 1280, 1280, crop = false) ?: return src
    val out = File(ctx.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return out
}
