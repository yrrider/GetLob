package com.yrrider.getlob.ui

import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.glance.appwidget.updateAll
import coil.compose.AsyncImage
import com.google.firebase.messaging.FirebaseMessaging
import com.yrrider.getlob.R
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen {
    CHAT,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.rgb(0x0B, 0x14, 0x1B)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        try {
            Notifier.createChannels(this)
        } catch (e: Throwable) {
            Log.e("GetLob", "Failed to create notification channels", e)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00A884),
                    secondary = Color(0xFF25D366),
                    background = Color(0xFF0B141B),
                    surface = Color(0xFF111B21),
                    surfaceVariant = Color(0xFF202C33),
                    onBackground = Color(0xFFFFFFFF),
                    onSurface = Color(0xFFFFFFFF),
                )
            ) {
                Surface(color = Color(0xFF0B141B)) {
                    App()
                }
            }
        }
    }
}

val CHAT_EMOJIS = listOf(
    "❤️", "🥰", "😘", "🥺", "💕", "✨", "🔥", "☕",
    "😴", "🍕", "🎉", "💪", "🤗", "🌹", "💌", "💋",
    "😍", "💖", "🌙", "⭐", "🧸", "🍫", "🥂", "🎶"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(AppScreen.CHAT) }

    var ready by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf("") }
    var pairId by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf(with(LocalStore) { ctx.pairCode }) }
    var joinCode by remember { mutableStateOf("") }
    var fitCrop by remember { mutableStateOf(LocalStore.read(ctx).fitCrop) }
    var error by remember { mutableStateOf("") }
    var busySending by remember { mutableStateOf(false) }


    var messageText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<Message?>(null) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }


    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    var photoCaption by remember { mutableStateOf("") }
    var cameraTempPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isCreatingCode by remember { mutableStateOf(false) }
    var isJoiningCode by remember { mutableStateOf(false) }


    var moods by remember { mutableStateOf(LocalStore.getMoods(ctx)) }
    var showCustomizeMoodsDialog by remember { mutableStateOf(false) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }


    var keyTick by remember { mutableIntStateOf(0) }
    var passphrase by remember { mutableStateOf("") }
    val hasCustomPassphrase = remember(keyTick) { Crypto.hasCustomPassphrase(ctx) }
    val fingerprint = remember(keyTick, pairId) { Crypto.fingerprint(ctx) }
    var headerLogo by remember {
        mutableStateOf(ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("headerLogo", "") ?: "")
    }

    val headerLogoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            headerLogo = uri.toString()
            ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().putString("headerLogo", headerLogo).apply()
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    suspend fun sendAndShow(m: Message, localFile: File? = null) {
        val withReply = if (replyingToMessage != null) {
            val rep = replyingToMessage!!
            val rawRepText = rep.text.ifEmpty {
                when (rep.type) {
                    MsgType.IMAGE -> "Photo 📷"
                    MsgType.GIF -> "GIF 🎞️"
                    MsgType.VIDEO -> "Video 📹"
                    MsgType.VOICE, MsgType.PTT -> "Voice Note 🎙️"
                    MsgType.EMOJI -> "Reaction"
                    MsgType.SOS -> "🚨 SOS Alert"
                    else -> "Message"
                }
            }
            val plainRep = Crypto.decryptText(ctx, rawRepText)
            m.copy(
                replyToId = rep.id,
                replyText = plainRep,
                replySender = if (rep.from == me) "You" else "Partner"
            )
        } else m

        replyingToMessage = null
        messageText = ""

        if (withReply.type in listOf(MsgType.TEXT, MsgType.EMOJI, MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO)) {
            runCatching { MediaProcessor.apply(ctx, withReply, localFile) }
        }
        Repo.send(ctx, withReply)
    }

    fun executeSend(label: String, block: suspend () -> Unit) {
        scope.launch {
            busySending = true
            try {
                block()
            } catch (e: Exception) {
                toast("Send failed: ${e.message}")
            } finally {
                busySending = false
            }
        }
    }


    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraTempPath
        if (ok && path != null) {
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                pendingPhotoFile = f
            }
        }
    }

    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val f = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
            f.parentFile?.mkdirs()
            if (!f.exists()) runCatching { f.createNewFile() }
            cameraTempPath = f.absolutePath
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
            cameraLauncher.launch(uri)
        } else {
            toast("Camera permission needed to snap photos")
        }
    }

    fun launchCameraSnap() {
        cameraPerm.launch(Manifest.permission.CAMERA)
    }


    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val small = withContext(Dispatchers.IO) { downscaleToCache(ctx, uri) }
                pendingPhotoFile = small
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

        pairId = with(LocalStore) { ctx.pairId }
        if (inviteCode.isEmpty()) {
            inviteCode = with(LocalStore) { ctx.pairCode }
        }


        try {
            me = Repo.uid()
            val remotePairId = Repo.pairId(ctx)
            if (remotePairId.isNotEmpty()) {
                pairId = remotePairId
            }
        } catch (e: Throwable) {
            Log.w("GetLob", "Init check: ${e.message}")
        } finally {
            ready = true
        }


        launch(Dispatchers.IO) {
            runCatching {
                val t = FirebaseMessaging.getInstance().token.await()
                Repo.saveToken(t, ctx)
            }
        }
    }


    val pairInfo by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.pairFlow(pairId) else flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(pairInfo) {
        pairInfo?.let { info ->
            if (info.code.isNotEmpty()) inviteCode = info.code
            if (info.createdAt > 0L) {
                LocalStore.setPairedAt(ctx, info.createdAt)
            }
        }
    }

    val isConnectedWithPartner = (pairInfo?.members?.size ?: 0) >= 2
    LaunchedEffect(isConnectedWithPartner) {
        if (isConnectedWithPartner) error = ""
    }

    val history by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.messagesFlow(pairId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var lastAppliedMessageId by remember { mutableStateOf("") }

    LaunchedEffect(pairInfo?.canvasClearedAt) {
        val remoteCleared = pairInfo?.canvasClearedAt ?: 0L
        if (remoteCleared > 0L && remoteCleared > LocalStore.getLastClearedAt(ctx)) {
            LocalStore.setLastClearedAt(ctx, remoteCleared)
            MediaProcessor.clearCanvas(ctx)
            lastAppliedMessageId = ""
        }
    }


    LaunchedEffect(history, keyTick) {
        history.firstOrNull()?.takeIf { it.id != lastAppliedMessageId }?.let { latest ->
            val visible = latest.copy(text = Crypto.decryptText(ctx, latest.text))
            runCatching { MediaProcessor.apply(ctx, visible) }
            lastAppliedMessageId = latest.id
        }
    }


    var widgetSettings by remember { mutableStateOf(LocalStore.readSettings(ctx)) }
    var chatSettings by remember { mutableStateOf(LocalStore.readChatSettings(ctx)) }
    var partnerNameInput by remember { mutableStateOf(widgetSettings.partnerName) }
    var hasUnsavedWidgetChanges by remember { mutableStateOf(false) }


    var localTransparency by remember(widgetSettings.bgTransparency) { mutableFloatStateOf(widgetSettings.bgTransparency) }
    var localCorner by remember(widgetSettings.cornerDp) { mutableFloatStateOf(widgetSettings.cornerDp.toFloat()) }
    var localBorderTransparency by remember(widgetSettings.borderTransparency) { mutableFloatStateOf(widgetSettings.borderTransparency) }

    val syncedSettings by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.settingsFlow(pairId) else flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(syncedSettings) {
        syncedSettings?.let { newS ->
            if (!hasUnsavedWidgetChanges && newS != widgetSettings) {
                widgetSettings = newS
                partnerNameInput = newS.partnerName
                localTransparency = newS.bgTransparency
                localCorner = newS.cornerDp.toFloat()
                localBorderTransparency = newS.borderTransparency
                LocalStore.writeSettings(ctx, newS)
                MediaProcessor.refresh(ctx)
            }
        }
    }

    fun updateSettings(newS: WidgetSettings) {
        widgetSettings = newS
        hasUnsavedWidgetChanges = true
    }

    fun saveWidgetSettings() {
        scope.launch {
            try {
                Repo.saveSettings(ctx, widgetSettings)
                MediaProcessor.refresh(ctx)
                hasUnsavedWidgetChanges = false
                toast("Widget settings saved")
            } catch (e: Exception) {
                toast("Could not save settings: ${e.message}")
            }
        }
    }


    BackHandler(enabled = currentScreen == AppScreen.SETTINGS) {
        currentScreen = AppScreen.CHAT
    }


    if (pendingPhotoFile != null) {
        AlertDialog(
            onDismissRequest = { pendingPhotoFile = null; photoCaption = "" },
            containerColor = Color(0xFF0F0F0F),
            title = { Text("Snap Preview & Text", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = pendingPhotoFile,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (photoCaption.isNotEmpty()) {
                            Surface(
                                color = Color(0x99000000),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                Text(
                                    text = photoCaption,
                                    color = Color(widgetSettings.fontColor),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = photoCaption,
                        onValueChange = { photoCaption = it.take(100) },
                        placeholder = { Text("Add text over image (Snapchat style)…", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileToSend = pendingPhotoFile!!
                        val captionToSend = photoCaption.trim()
                        pendingPhotoFile = null
                        photoCaption = ""

                        executeSend("Snap") {
                            val finalFile = withContext(Dispatchers.IO) {
                                val optimized = ImageFit.compressForUpload(ctx, fileToSend, maxDim = 1080, quality = 82)
                                if (captionToSend.isNotEmpty()) {
                                    val originalBmp = BitmapFactory.decodeFile(optimized.absolutePath)
                                    if (originalBmp != null) {
                                        val stamped = WidgetRenderer.overlayTextOnBitmap(
                                            originalBmp,
                                            captionToSend,
                                            widgetSettings.fontStyle,
                                            widgetSettings.fontColor.toInt()
                                        )
                                        val out = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
                                        FileOutputStream(out).use {
                                            stamped.compress(Bitmap.CompressFormat.JPEG, 82, it)
                                        }
                                        if (optimized != fileToSend) optimized.delete()
                                        out
                                    } else optimized
                                } else optimized
                            }

                            val url = Repo.upload(ctx, finalFile, "jpg")
                            val cached = SecureMedia.cacheFile(ctx, url)
                            runCatching { finalFile.copyTo(cached, overwrite = true) }
                            sendAndShow(Message(type = MsgType.IMAGE, mediaUrl = url, text = captionToSend), localFile = cached)
                            runCatching {
                                if (finalFile != fileToSend && finalFile.exists()) finalFile.delete()
                                if (fileToSend.exists() && fileToSend.name.startsWith("snap_")) fileToSend.delete()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Send Snap 🚀", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (pendingPhotoFile?.name?.startsWith("snap_") == true) pendingPhotoFile?.delete()
                    pendingPhotoFile = null
                    photoCaption = ""
                }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    if (!ready) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NeonHeartLoader(size = 84.dp)
                Spacer(Modifier.height(20.dp))
                Text(
                    "Connecting to GetLob…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFCBD5E1)
                )
            }
        }
        return
    }


    if (pairId.isEmpty() || !isConnectedWithPartner) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("❤️", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Text("GetLob", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
            }

            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val activeCode = (pairInfo?.code?.ifEmpty { null }) ?: inviteCode
                    val isInPairingMode = activeCode.isNotEmpty()

                    if (isInPairingMode) {
                        Surface(
                            color = Color(0x2610B981),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                                Spacer(Modifier.width(6.dp))
                                Text("PAIRING MODE ACTIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        if (isInPairingMode) "Share Code With Partner" else "Pair with Your Partner",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        if (isInPairingMode)
                            "Device is in pairing mode. Give this 6-character code to your partner to enter on their phone:"
                        else
                            "To connect, tap 'Enter Pairing Mode' or enter your partner's code below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    if (isInPairingMode) {
                        Surface(
                            color = Color(0xFF0F0F0F),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626)),
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
                                    color = Color(0xFF25D366),
                                    letterSpacing = 4.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF25D366))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Waiting for partner to enter code…", fontSize = 12.sp, color = Color(0xFF25D366))
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { Repo.exitPairingMode(ctx) }
                                    pairId = ""
                                    inviteCode = ""
                                    error = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Exit Pairing Mode / Cancel")
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    isCreatingCode = true
                                    error = ""
                                    try {
                                        inviteCode = Repo.createPair(ctx)
                                        pairId = Repo.pairId(ctx)
                                        error = ""
                                    } catch (e: Exception) {
                                        error = e.message ?: "Failed to generate code"
                                    } finally {
                                        isCreatingCode = false
                                    }
                                }
                            },
                            enabled = !isCreatingCode,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
                        ) {
                            if (isCreatingCode) {
                                NeonHeartLoader(size = 24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Entering Pairing Mode…", fontWeight = FontWeight.Bold)
                            } else {
                                Text("Enter Pairing Mode & Get Code", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFF262626))
                        Spacer(Modifier.height(16.dp))

                        Text("Or enter your partner's code:", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = {
                                joinCode = it.uppercase().take(6)
                                error = ""
                            },
                            singleLine = true,
                            placeholder = { Text("6-CHAR CODE") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isJoiningCode = true
                                    error = ""
                                    try {
                                        Repo.joinPair(ctx, joinCode)
                                        pairId = Repo.pairId(ctx)
                                        error = ""
                                    } catch (e: Exception) {
                                        error = e.message ?: "Invalid code or partner not in pairing mode"
                                    } finally {
                                        isJoiningCode = false
                                    }
                                }
                            },
                            enabled = joinCode.length == 6 && !isJoiningCode,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
                        ) {
                            if (isJoiningCode) {
                                NeonHeartLoader(size = 24.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Joining Partner…", fontWeight = FontWeight.Bold)
                            } else {
                                Text("Join Partner", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = Color(0x33EF4444),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66EF4444)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = error,
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Device UUID: ${LocalStore.appUuid(ctx)}",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }


    if (!hasCustomPassphrase) {
        SetupPassphraseScreen(
            partnerName = widgetSettings.partnerName.ifBlank { "Partner" },
            pairId = pairId,
            onSetPassphrase = { pass ->
                Crypto.setPassphrase(ctx, pairId, pass.trim())
                keyTick++
                toast("Encryption passphrase configured! 🔒")
            },
            onUnpair = { showUnpairDialog = true }
        )
        return
    }


    when (currentScreen) {
        AppScreen.CHAT -> {
            ChatScreen(
                partnerName = widgetSettings.partnerName.ifBlank { "Partner" },
                headerLogo = headerLogo,
                onHeaderLogoClick = {
                    headerLogoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                history = history,
                me = me,
                keyTick = keyTick,
                messageText = messageText,
                onMessageChange = { messageText = it },
                replyingToMessage = replyingToMessage,
                onDismissReply = { replyingToMessage = null },
                onReplyClick = { replyingToMessage = it },
                onSend = {
                    val text = messageText.trim()
                    if (text.isNotEmpty()) {
                        executeSend("Message") {
                            sendAndShow(Message(type = MsgType.TEXT, text = text))
                        }
                    }
                },
                onQuickSnap = { launchCameraSnap() },
                onVibe = {
                    executeSend("Vibe") {
                        Repo.send(ctx, Message(type = MsgType.VIBE))
                        toast("💓 Sent Vibe to ${widgetSettings.partnerName}!")
                    }
                },
                onLiveTalk = {
                    try {
                        ctx.startActivity(
                            Intent(ctx, LiveTalkActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        )
                    } catch (e: Exception) {
                        toast("Could not start Live Talk: ${e.message}")
                    }
                },
                onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                onOpenAttach = { showAttachSheet = true },
                onOpenEmojis = { showEmojiSheet = true },
                onClearChat = { showClearHistoryDialog = true },
                onUnpair = { showUnpairDialog = true },
                busySending = busySending,
                moods = moods,
                pairedAtMs = pairInfo?.createdAt?.takeIf { it > 0L } ?: LocalStore.pairedAt(ctx),
                chatSettings = chatSettings,
                onUpdateChatSettings = { newS ->
                    chatSettings = newS
                    LocalStore.writeChatSettings(ctx, newS)
                }
            )
        }

        AppScreen.SETTINGS -> {
            SettingsScreen(
                widgetSettings = widgetSettings,
                partnerNameInput = partnerNameInput,
                onPartnerNameChange = { partnerNameInput = it },
                onSavePartnerName = {
                    val trimmed = partnerNameInput.trim().ifEmpty { "Partner" }
                    updateSettings(widgetSettings.copy(partnerName = trimmed))
                    saveWidgetSettings()
                },
                inviteCode = inviteCode.ifEmpty { LocalStore.pairCode(ctx) },
                hasCustomPassphrase = hasCustomPassphrase,
                fingerprint = fingerprint,
                passphrase = passphrase,
                onPassphraseChange = { passphrase = it },
                onSetPassphrase = {
                    Crypto.setPassphrase(ctx, pairId, passphrase.trim())
                    passphrase = ""
                    keyTick++
                    toast("Custom passphrase active! Verify code matches partner's.")
                },
                onResetPassphrase = {
                    Crypto.clear(ctx)
                    keyTick++
                    toast("Reverted to automatic pair encryption")
                },
                localTransparency = localTransparency,
                onTransparencyChange = { localTransparency = it },
                onTransparencyFinished = { updateSettings(widgetSettings.copy(bgTransparency = localTransparency)) },
                localCorner = localCorner,
                onCornerChange = { localCorner = it },
                onCornerFinished = { updateSettings(widgetSettings.copy(cornerDp = localCorner.toInt())) },
                localBorderTransparency = localBorderTransparency,
                onBorderTransparencyChange = { localBorderTransparency = it },
                onBorderTransparencyFinished = { updateSettings(widgetSettings.copy(borderTransparency = localBorderTransparency)) },
                hasUnsavedWidgetChanges = hasUnsavedWidgetChanges,
                onUpdateSettings = ::updateSettings,
                onSaveSettings = ::saveWidgetSettings,
                onBack = { currentScreen = AppScreen.CHAT },
                onClearCanvas = { showClearHistoryDialog = true },
                onUnpair = { showUnpairDialog = true },
                onBatteryClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            )
        }
    }


    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = Color(0xFF1F2C34),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    "Share with ${widgetSettings.partnerName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_snap_camera,
                        label = "Camera",
                        color = Color(0xFFFBBF24),
                        onClick = {
                            showAttachSheet = false
                            launchCameraSnap()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_photo_gallery,
                        label = "Gallery",
                        color = Color(0xFF38BDF8),
                        onClick = {
                            showAttachSheet = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_gif_motion,
                        label = "GIF",
                        color = Color(0xFFA855F7),
                        onClick = {
                            showAttachSheet = false
                            gifPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_video_record,
                        label = "Video",
                        color = Color(0xFFEC4899),
                        onClick = {
                            showAttachSheet = false
                            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_vibe_pulse,
                        label = "Vibe Pulse",
                        color = Color(0xFF10B981),
                        onClick = {
                            showAttachSheet = false
                            executeSend("Vibe") {
                                Repo.send(ctx, Message(type = MsgType.VIBE))
                                toast("💓 Sent Vibe to ${widgetSettings.partnerName}!")
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_stat_getlob,
                        label = "SOS Alert",
                        color = Color(0xFFEF4444),
                        onClick = {
                            showAttachSheet = false
                            executeSend("SOS") {
                                sendAndShow(Message(type = MsgType.SOS, text = "🚨 SOS Emergency Alert!"))
                            }
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }


    if (showEmojiSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiSheet = false },
            containerColor = Color(0xFF1F2C34),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reactions & Moods", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    TextButton(onClick = {
                        showEmojiSheet = false
                        showCustomizeMoodsDialog = true
                    }) {
                        Text("⚙️ Customize", color = Color(0xFF25D366), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))


                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(moods) { (emoji, label) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                showEmojiSheet = false
                                executeSend("Reaction") {
                                    sendAndShow(Message(type = MsgType.EMOJI, text = if (label.isNotBlank()) "$emoji $label" else emoji))
                                }
                            },
                            label = { Text(if (label.isNotBlank()) "$emoji $label" else emoji, fontSize = 13.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Love & Couple Emojis", fontSize = 12.sp, color = Color(0xFF8696A0), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))


                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CHAT_EMOJIS.chunked(8).forEach { rowEmojis ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            rowEmojis.forEach { em ->
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            messageText += em
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(em, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }


    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair from Partner?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to disconnect from ${widgetSettings.partnerName}? All shared messages, uploaded photos/videos, and pair data will be permanently and immediately deleted from cloud storage.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairDialog = false
                        scope.launch {
                            runCatching { Repo.unpair(ctx) }
                            pairId = ""
                            inviteCode = ""
                            keyTick++
                            currentScreen = AppScreen.CHAT
                            toast("Unpaired from partner. All data permanently deleted.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Disconnect", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUnpairDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF141414),
            shape = RoundedCornerShape(20.dp)
        )
    }


    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear Chat & Canvas?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Text(
                    "This will delete all shared messages, photos, and clear the widget canvas for both of you.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryDialog = false
                        scope.launch {
                            try {
                                Repo.clearHistory(ctx)
                                MediaProcessor.clearCanvas(ctx)
                                lastAppliedMessageId = ""
                                toast("Chat & Canvas cleared")
                            } catch (e: Exception) {
                                toast("Could not clear: ${e.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearHistoryDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF141414),
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showCustomizeMoodsDialog) {
        CustomizeMoodsDialog(
            moods = moods,
            onDismiss = { showCustomizeMoodsDialog = false },
            onAdd = { emoji, label ->
                LocalStore.addCustomMood(ctx, emoji, label)
                moods = LocalStore.getMoods(ctx)
            },
            onRemove = { emoji ->
                LocalStore.removeCustomMood(ctx, emoji)
                moods = LocalStore.getMoods(ctx)
            },
            onReset = {
                LocalStore.resetMoods(ctx)
                moods = LocalStore.getMoods(ctx)
            }
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(
    partnerName: String,
    headerLogo: String,
    onHeaderLogoClick: () -> Unit,
    history: List<Message>,
    me: String,
    keyTick: Int,
    messageText: String,
    onMessageChange: (String) -> Unit,
    replyingToMessage: Message?,
    onDismissReply: () -> Unit,
    onReplyClick: (Message) -> Unit,
    onSend: () -> Unit,
    onQuickSnap: () -> Unit,
    onVibe: () -> Unit,
    onLiveTalk: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAttach: () -> Unit,
    onOpenEmojis: () -> Unit,
    onClearChat: () -> Unit,
    onUnpair: () -> Unit,
    busySending: Boolean,
    moods: List<Pair<String, String>>,
    pairedAtMs: Long,
    chatSettings: ChatSettings,
    onUpdateChatSettings: (ChatSettings) -> Unit
) {
    val ctx = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showPartnerProfile by remember { mutableStateOf(false) }
    var showChatCustomization by remember { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(history.firstOrNull()?.id) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()

            .imePadding()
            .background(Color(chatSettings.wallpaperColor))
    ) {

        Surface(
            color = Color.Black,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showPartnerProfile = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFFF43F5E))))
                            .clickable { showPartnerProfile = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (headerLogo.isBlank()) {
                            Text("❤️", fontSize = 22.sp)
                        } else {
                            AsyncImage(
                                model = Uri.parse(headerLogo),
                                contentDescription = "Profile picture",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            partnerName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF25D366))
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Connected • Widget Synced",
                                fontSize = 11.sp,
                                color = Color(0xFF25D366)
                            )
                        }
                    }
                }


                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onLiveTalk) {
                        Icon(
                            painter = painterResource(R.drawable.ic_satellite_talk),
                            contentDescription = "Live Talk",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = onVibe) {
                        Icon(
                            painter = painterResource(R.drawable.ic_vibe_pulse),
                            contentDescription = "Send Vibe",
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = "More options",
                                tint = Color(0xFFAEBAC1),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(Color(0xFF233138))
                        ) {
                            DropdownMenuItem(
                                text = { Text("🎨 Customise Chat", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showChatCustomization = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("⚙️ Settings", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🧹 Clear Chat", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onClearChat()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("💔 Unpair", color = Color(0xFFF87171)) },
                                onClick = {
                                    showMenu = false
                                    onUnpair()
                                }
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF262626), thickness = 1.dp)


        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(chatSettings.wallpaperColor))
        ) {
            if (history.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            color = Color(0xFF182229),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔒 End-to-End Encrypted", fontSize = 12.sp, color = Color(0xFFFFD279), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Messages and widgets between you and $partnerName are end-to-end encrypted. No one else can see them.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF8696A0),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Say hi to $partnerName! 👋",
                            color = Color(0xFF8696A0),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(history, key = { it.id }) { msg ->
                        val isMine = msg.from == me
                        ChatMessageBubble(
                            m = msg,
                            mine = isMine,
                            partnerName = partnerName,
                            keyTick = keyTick,
                            chatSettings = chatSettings,
                            onReply = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReplyClick(msg)
                            },
                            onImageClick = { url ->
                                previewImageUrl = url
                            },
                            onMediaPlay = {
                                ctx.startActivity(
                                    Intent(ctx, OverlayActivity::class.java)
                                        .putExtra(OverlayActivity.EXTRA_PLAY_URL, msg.mediaUrl)
                                        .putExtra(OverlayActivity.EXTRA_PLAY_KIND, msg.type)
                                )
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }


        AnimatedVisibility(
            visible = replyingToMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            replyingToMessage?.let { rep ->
                val repText = Crypto.decryptText(ctx, rep.text.ifEmpty {
                    when (rep.type) {
                        MsgType.IMAGE -> "Photo 📷"
                        MsgType.GIF -> "GIF 🎞️"
                        MsgType.VIDEO -> "Video 📹"
                        MsgType.VOICE, MsgType.PTT -> "Voice Note 🎙️"
                        MsgType.EMOJI -> "Reaction"
                        MsgType.SOS -> "🚨 SOS Alert"
                        else -> "Message"
                    }
                })
                Surface(
                    color = Color(0xFF1F2C34),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(Color(0xFF25D366), RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (rep.from == me) "You" else partnerName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF25D366)
                            )
                            Text(
                                repText,
                                fontSize = 12.sp,
                                color = Color(0xFF8696A0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismissReply, modifier = Modifier.size(24.dp)) {
                            Text("✕", color = Color(0xFF8696A0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }


        if (moods.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131D24))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(moods) { (emoji, label) ->
                    Surface(
                        color = Color(0xFF202C33),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            onMessageChange(if (messageText.isEmpty()) "$emoji $label" else "$messageText $emoji")
                        }
                    ) {
                        Text(
                            text = if (label.isNotBlank()) "$emoji $label" else emoji,
                            color = Color(0xFFE9EDEF),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }


        Surface(
            color = Color(0xFF1F2C34),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Surface(
                    color = Color(0xFF2A3942),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        IconButton(onClick = onOpenEmojis, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_emoji),
                                contentDescription = "Emoji",
                                tint = Color(0xFF8696A0),
                                modifier = Modifier.size(20.dp)
                            )
                        }


                        BasicTextField(
                            value = messageText,
                            onValueChange = onMessageChange,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            textStyle = TextStyle(color = Color.White, fontSize = chatSettings.messageFontSize.sp),
                            cursorBrush = SolidColor(Color(0xFF25D366)),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                if (messageText.isEmpty()) {
                                    Text(
                                        "Message $partnerName…",
                                        color = Color(0xFF8696A0),
                                        fontSize = chatSettings.messageFontSize.sp
                                    )
                                }
                                innerTextField()
                            }
                        )


                        IconButton(onClick = onOpenAttach, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_attach),
                                contentDescription = "Attach",
                                tint = Color(0xFF8696A0),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))


                val isTextEntered = messageText.isNotBlank()
                Surface(
                    color = Color(0xFF00A884),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(enabled = !busySending) {
                            if (isTextEntered) {
                                onSend()
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            } else {
                                onQuickSnap()
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (busySending) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else if (isTextEntered) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_send),
                                contentDescription = "Send message",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_snap_camera),
                                contentDescription = "Camera",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }


    if (showPartnerProfile) {
        PartnerProfileSheet(
            partnerName = partnerName,
            headerLogo = headerLogo,
            pairedAtMs = pairedAtMs,
            onChangePhoto = onHeaderLogoClick,
            onLiveTalk = onLiveTalk,
            onVibe = onVibe,
            onCustomiseChat = { showChatCustomization = true },
            onOpenSettings = onOpenSettings,
            onClearChat = onClearChat,
            onUnpair = onUnpair,
            onDismiss = { showPartnerProfile = false }
        )
    }


    if (showChatCustomization) {
        ChatCustomizationSheet(
            initialSettings = chatSettings,
            onSave = { newSettings ->
                onUpdateChatSettings(newSettings)
                showChatCustomization = false
            },
            onDismiss = { showChatCustomization = false }
        )
    }


    previewImageUrl?.let { url ->
        val targetMsg = history.find { it.mediaUrl == url }
        ImagePreviewDialog(
            mediaUrl = url,
            onReply = {
                if (targetMsg != null) onReplyClick(targetMsg)
            },
            onDismiss = { previewImageUrl = null }
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageBubble(
    m: Message,
    mine: Boolean,
    partnerName: String,
    keyTick: Int,
    chatSettings: ChatSettings,
    onReply: () -> Unit,
    onImageClick: (String) -> Unit,
    onMediaPlay: () -> Unit
) {
    val ctx = LocalContext.current
    val loader = remember { mediaLoader(ctx) }
    val text = remember(m.text, keyTick) { Crypto.decryptText(ctx, m.text) }
    val replyText = remember(m.replyText, keyTick) { Crypto.decryptText(ctx, m.replyText) }

    val bubbleBg = if (mine) Color(chatSettings.myBubbleColor) else Color(chatSettings.partnerBubbleColor)
    val cornerRadius = chatSettings.bubbleCornerDp.dp
    val bubbleShape = if (mine) {
        RoundedCornerShape(cornerRadius, cornerRadius, 2.dp, cornerRadius)
    } else {
        RoundedCornerShape(cornerRadius, cornerRadius, cornerRadius, 2.dp)
    }
    val fontFamily = fontStyleToFamily(chatSettings.fontStyle)
    val fontSize = chatSettings.messageFontSize.sp

    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current

    val swipeModifier = if (chatSettings.swipeToReplyEnabled) {
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                val newOffset = (offsetX.value + delta).coerceIn(0f, 90f * density)
                scope.launch { offsetX.snapTo(newOffset) }
            },
            onDragStopped = {
                if (offsetX.value > 45f * density) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReply()
                }
                scope.launch {
                    offsetX.animateTo(
                        0f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        )
                    )
                }
            }
        )
    } else Modifier

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .then(swipeModifier),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
        ) {
            if (offsetX.value > 12f * density) {
                Icon(
                    painter = painterResource(R.drawable.ic_chat_reply),
                    contentDescription = "Swipe to reply",
                    tint = Color(0xFF25D366),
                    modifier = Modifier.padding(end = 6.dp).size(20.dp)
                )
            }

            Surface(
                shape = bubbleShape,
                color = bubbleBg,
                modifier = Modifier
                    .widthIn(min = 70.dp, max = 300.dp)
                    .combinedClickable(
                        onClick = {
                            if (m.type == MsgType.IMAGE) {
                                onImageClick(m.mediaUrl)
                            } else if (m.type in listOf(MsgType.GIF, MsgType.VIDEO, MsgType.VOICE, MsgType.PTT)) {
                                onMediaPlay()
                            }
                        },
                        onLongClick = onReply
                    )
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {

                    if (m.replyToId.isNotEmpty() || replyText.isNotEmpty()) {
                        Surface(
                            color = Color(0x33000000),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .background(if (mine) Color(0xFF25D366) else Color(0xFF38BDF8), RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        m.replySender.ifEmpty { if (mine) "You" else partnerName },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (mine) Color(0xFF25D366) else Color(0xFF38BDF8)
                                    )
                                    Text(
                                        replyText.ifEmpty { "Quoted message" },
                                        fontSize = 11.sp,
                                        color = Color(0xFFE9EDEF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }


                    if (m.type in listOf(MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO) && m.mediaUrl.isNotEmpty()) {
                        val model = rememberMedia(m.mediaUrl)
                        if (model != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (m.type == MsgType.IMAGE) onImageClick(m.mediaUrl)
                                        else onMediaPlay()
                                    }
                            ) {
                                AsyncImage(
                                    model = model,
                                    contentDescription = null,
                                    imageLoader = loader,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                if (m.type == MsgType.VIDEO || m.type == MsgType.GIF) {
                                    Surface(
                                        color = Color(0x99000000),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            if (m.type == MsgType.VIDEO) "▶ Play Video" else "GIF",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Text("🔒 Decrypting media…", fontSize = 12.sp, color = Color(0xFF8696A0))
                        }
                    }


                    if (m.type == MsgType.VOICE || m.type == MsgType.PTT) {
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎙️", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Voice Message (Tap to listen)", fontSize = 13.sp, color = Color(0xFF38BDF8))
                        }
                    }


                    if (m.type == MsgType.SOS) {
                        Surface(
                            color = Color(0x33EF4444),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("🚨", fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("EMERGENCY SOS ALERT", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }


                    if (m.type == MsgType.VIBE) {
                        Row(
                            Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💓", fontSize = 20.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (mine) "You sent a vibration" else "Sent you a vibration",
                                color = Color.White,
                                fontSize = fontSize,
                                fontFamily = fontFamily
                            )
                        }
                    }


                    val showInlineTimestamp = text.isNotEmpty() && m.type != MsgType.SOS && m.type != MsgType.VIBE

                    if (showInlineTimestamp) {
                        WhatsAppTextMessage(
                            text = text,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = if (m.type == MsgType.EMOJI) (chatSettings.messageFontSize + 8).sp else fontSize,
                                fontFamily = fontFamily,
                                lineHeight = (chatSettings.messageFontSize + 5).sp
                            ),
                            timeContent = {
                                MessageTimestamp(m.createdAt, mine)
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MessageTimestamp(m.createdAt, mine)
                        }
                    }
                }
            }
        }
    }
}



@Composable
private fun MessageTimestamp(
    createdAt: Long,
    mine: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp)
    ) {
        val timeStr = remember(createdAt) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(createdAt))
        }
        Text(
            text = timeStr,
            fontSize = 10.sp,
            color = Color(0xFF8696A0)
        )
        if (mine) {
            Spacer(Modifier.width(3.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chat_double_check),
                contentDescription = "Delivered",
                tint = Color(0xFF53BDEB),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun WhatsAppTextMessage(
    text: String,
    style: TextStyle,
    timeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Layout(
        content = {
            Text(
                text = text,
                style = style
            )
            timeContent()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val timePlaceable = measurables[1].measure(Constraints())
        val spacing = 4.dp.roundToPx()

        val textLayoutResult = textMeasurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = constraints.maxWidth)
        )

        val messagePlaceable = measurables[0].measure(
            Constraints(
                minWidth = 0,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
        )

        val lineCount = textLayoutResult.lineCount.coerceAtLeast(1)
        val lastLineEnd = textLayoutResult.getLineRight(lineCount - 1)
        val lastLineStart = textLayoutResult.getLineLeft(lineCount - 1)
        val lastLineWidth = (lastLineEnd - lastLineStart).roundToInt()
        val textWidth = textLayoutResult.size.width
        val textHeight = textLayoutResult.size.height

        val fitsOnLastLine = (lastLineWidth + spacing + timePlaceable.width) <= constraints.maxWidth

        val totalWidth: Int
        val totalHeight: Int
        val messageY: Int
        val timeY: Int

        if (lineCount == 1) {
            if (textWidth + spacing + timePlaceable.width <= constraints.maxWidth) {
                totalWidth = textWidth + spacing + timePlaceable.width
                totalHeight = maxOf(textHeight, timePlaceable.height)
                messageY = (totalHeight - textHeight) / 2
                timeY = totalHeight - timePlaceable.height
            } else {
                totalWidth = maxOf(textWidth, timePlaceable.width)
                totalHeight = textHeight + timePlaceable.height
                messageY = 0
                timeY = textHeight
            }
        } else if (fitsOnLastLine) {
            totalWidth = maxOf(textWidth, lastLineWidth + spacing + timePlaceable.width)
            totalHeight = textHeight
            messageY = 0
            timeY = totalHeight - timePlaceable.height
        } else {
            totalWidth = maxOf(textWidth, timePlaceable.width)
            totalHeight = textHeight + timePlaceable.height
            messageY = 0
            timeY = textHeight
        }

        val clampedWidth = totalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val clampedHeight = totalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(clampedWidth, clampedHeight) {
            messagePlaceable.placeRelative(0, messageY)
            timePlaceable.placeRelative(
                clampedWidth - timePlaceable.width,
                timeY.coerceAtMost(clampedHeight - timePlaceable.height)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerProfileSheet(
    partnerName: String,
    headerLogo: String,
    pairedAtMs: Long,
    onChangePhoto: () -> Unit,
    onLiveTalk: () -> Unit,
    onVibe: () -> Unit,
    onCustomiseChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B21),
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFFF43F5E))))
                    .clickable { onChangePhoto() },
                contentAlignment = Alignment.Center
            ) {
                if (headerLogo.isBlank()) {
                    Text("❤️", fontSize = 46.sp)
                } else {
                    AsyncImage(
                        model = Uri.parse(headerLogo),
                        contentDescription = "Partner profile photo",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366))
                        .border(2.dp, Color(0xFF111B21), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_snap_camera),
                        contentDescription = "Change photo",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))


            Text(
                text = partnerName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(4.dp))


            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Connected • Live Widget Synced",
                    fontSize = 12.sp,
                    color = Color(0xFF25D366),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(18.dp))


            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1F2C34),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2BFFFFFF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⏳", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Together Counter",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8696A0)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatConnectedDuration(pairedAtMs),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFD279),
                        textAlign = TextAlign.Center
                    )
                    if (pairedAtMs > 0L) {
                        Spacer(Modifier.height(4.dp))
                        val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(pairedAtMs))
                        Text(
                            text = "Paired on $formattedDate",
                            fontSize = 11.sp,
                            color = Color(0xFF8696A0)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))


            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PartnerActionCircle(
                    iconRes = R.drawable.ic_satellite_talk,
                    label = "Live Talk",
                    color = Color(0xFF38BDF8),
                    onClick = {
                        onDismiss()
                        onLiveTalk()
                    }
                )
                PartnerActionCircle(
                    iconRes = R.drawable.ic_vibe_pulse,
                    label = "Send Vibe",
                    color = Color(0xFF25D366),
                    onClick = {
                        onDismiss()
                        onVibe()
                    }
                )
                PartnerActionCircle(
                    iconRes = R.drawable.ic_text_note,
                    label = "Customise",
                    color = Color(0xFFA855F7),
                    onClick = {
                        onDismiss()
                        onCustomiseChat()
                    }
                )
            }

            Spacer(Modifier.height(20.dp))


            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1F2C34),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ProfileMenuRow(
                        title = "Chat Customisation",
                        subtitle = "Wallpaper, font size, bubble colors & style",
                        icon = "🎨",
                        onClick = {
                            onDismiss()
                            onCustomiseChat()
                        }
                    )
                    HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 0.5.dp)
                    ProfileMenuRow(
                        title = "Widget & App Settings",
                        subtitle = "Themes, borders, glass effect, notification controls",
                        icon = "⚙️",
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        }
                    )
                    HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 0.5.dp)
                    ProfileMenuRow(
                        title = "Clear Chat History",
                        subtitle = "Clear messages locally from your conversation",
                        icon = "🧹",
                        onClick = {
                            onDismiss()
                            onClearChat()
                        }
                    )
                    HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 0.5.dp)
                    ProfileMenuRow(
                        title = "Unpair Partner",
                        subtitle = "Disconnect from this partner device",
                        icon = "💔",
                        titleColor = Color(0xFFF87171),
                        onClick = {
                            onDismiss()
                            onUnpair()
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatCustomizationSheet(
    initialSettings: ChatSettings,
    onSave: (ChatSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var settings by remember { mutableStateOf(initialSettings) }

    val wallpaperOptions = listOf(
        "WhatsApp Dark" to 0xFF0B141BL,
        "Midnight" to 0xFF0D1B2AL,
        "Pitch Black" to 0xFF000000L,
        "Deep Plum" to 0xFF1D1128L,
        "Forest Green" to 0xFF0B2518L,
        "Slate" to 0xFF192229L,
        "Espresso" to 0xFF2A1B14L
    )

    val sentBubbleOptions = listOf(
        "WhatsApp Green" to 0xFF005C4BL,
        "Sky Blue" to 0xFF0284C7L,
        "Royal Violet" to 0xFF7C3AEDL,
        "Rose Pink" to 0xFFBE185DL,
        "Teal" to 0xFF0D9488L,
        "Graphite" to 0xFF262D31L
    )

    val partnerBubbleOptions = listOf(
        "WhatsApp Grey" to 0xFF202C33L,
        "Slate Grey" to 0xFF1E293BL,
        "Charcoal" to 0xFF27272AL,
        "Midnight Black" to 0xFF18181BL,
        "Dark Violet" to 0xFF2D1F3DL
    )

    val fontSizeOptions = listOf(
        "Small" to 13,
        "Normal" to 15,
        "Medium" to 17,
        "Large" to 19
    )

    val cornerOptions = listOf(
        "Sharp (8dp)" to 8,
        "Normal (14dp)" to 14,
        "Round (18dp)" to 18,
        "Pill (24dp)" to 24
    )

    val fontStyles = listOf("Modern", "Serif", "Cursive", "Calligraphy", "Monospace")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B21),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎨 Chat Customisation", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = onDismiss) {
                    Text("✕", fontSize = 16.sp, color = Color(0xFF8696A0))
                }
            }

            Spacer(Modifier.height(10.dp))


            Surface(
                color = Color(settings.wallpaperColor),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier.fillMaxWidth().height(130.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceAround
                ) {

                    Surface(
                        color = Color(settings.partnerBubbleColor),
                        shape = RoundedCornerShape(
                            settings.bubbleCornerDp.dp,
                            settings.bubbleCornerDp.dp,
                            settings.bubbleCornerDp.dp,
                            2.dp
                        ),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = "How does this look? 😊",
                            color = Color.White,
                            fontSize = settings.messageFontSize.sp,
                            fontFamily = fontStyleToFamily(settings.fontStyle),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }


                    Surface(
                        color = Color(settings.myBubbleColor),
                        shape = RoundedCornerShape(
                            settings.bubbleCornerDp.dp,
                            settings.bubbleCornerDp.dp,
                            2.dp,
                            settings.bubbleCornerDp.dp
                        ),
                        modifier = Modifier.wrapContentWidth().align(Alignment.End)
                    ) {
                        Text(
                            text = "Looks amazing! ❤️",
                            color = Color.White,
                            fontSize = settings.messageFontSize.sp,
                            fontFamily = fontStyleToFamily(settings.fontStyle),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Chat Wallpaper Theme", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                wallpaperOptions.forEach { (name, colorLong) ->
                    ColorCirclePicker(
                        color = Color(colorLong),
                        isSelected = settings.wallpaperColor == colorLong,
                        label = name,
                        onClick = { settings = settings.copy(wallpaperColor = colorLong) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Your Bubble Color (Sent)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sentBubbleOptions.forEach { (name, colorLong) ->
                    ColorCirclePicker(
                        color = Color(colorLong),
                        isSelected = settings.myBubbleColor == colorLong,
                        label = name,
                        onClick = { settings = settings.copy(myBubbleColor = colorLong) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Partner Bubble Color (Received)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                partnerBubbleOptions.forEach { (name, colorLong) ->
                    ColorCirclePicker(
                        color = Color(colorLong),
                        isSelected = settings.partnerBubbleColor == colorLong,
                        label = name,
                        onClick = { settings = settings.copy(partnerBubbleColor = colorLong) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Message Font Size", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fontSizeOptions.forEach { (label, size) ->
                    FilterChip(
                        selected = settings.messageFontSize == size,
                        onClick = { settings = settings.copy(messageFontSize = size) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Bubble Corner Roundness", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                cornerOptions.forEach { (label, dp) ->
                    FilterChip(
                        selected = settings.bubbleCornerDp == dp,
                        onClick = { settings = settings.copy(bubbleCornerDp = dp) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Text("Font Style", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8696A0))
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fontStyles.forEach { style ->
                    FilterChip(
                        selected = settings.fontStyle == style,
                        onClick = { settings = settings.copy(fontStyle = style) },
                        label = { Text(style, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))


            Surface(
                color = Color(0xFF1F2C34),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Swipe to Reply", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("Swipe message bubble right to quote reply", color = Color(0xFF8696A0), fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.swipeToReplyEnabled,
                        onCheckedChange = { settings = settings.copy(swipeToReplyEnabled = it) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))


            Button(
                onClick = { onSave(settings) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Chat Appearance", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}


@Composable
private fun ImagePreviewDialog(
    mediaUrl: String,
    onReply: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val model = rememberMedia(mediaUrl)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {

            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Full preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp, top = 60.dp)
                )
            } else {
                CircularProgressIndicator(
                    color = Color(0xFF25D366),
                    modifier = Modifier.align(Alignment.Center)
                )
            }


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text("Photo Preview", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.size(48.dp))
            }


            Surface(
                color = Color(0xCC111B21),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (saving) return@Button
                            scope.launch {
                                saving = true
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        SecureMedia.fetch(ctx, mediaUrl)
                                    }
                                    val ok = exportImageToGallery(ctx, file)
                                    if (ok) Toast.makeText(ctx, "✅ Photo saved to Gallery (Pictures/GetLob)", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(ctx, "Failed to save photo", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (saving) "Saving…" else "💾 Save to Gallery", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onReply()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66FFFFFF))
                    ) {
                        Text("↩️ Reply", color = Color.White)
                    }
                }
            }
        }
    }
}


@Composable
private fun ColorCirclePicker(
    color: Color,
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color(0xFF25D366) else Color(0x44FFFFFF),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (isSelected) Color(0xFF25D366) else Color(0xFF8696A0),
            maxLines = 1
        )
    }
}

@Composable
private fun PartnerActionCircle(
    iconRes: Int,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileMenuRow(
    title: String,
    subtitle: String,
    icon: String,
    titleColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF8696A0), fontSize = 11.sp)
        }
        Text("›", fontSize = 20.sp, color = Color(0xFF8696A0))
    }
}


fun formatConnectedDuration(pairedAtMs: Long): String {
    if (pairedAtMs <= 0L) return "Connected recently ❤️"
    val diff = (System.currentTimeMillis() - pairedAtMs).coerceAtLeast(0L)
    val hoursTotal = diff / (1000 * 60 * 60)
    val days = hoursTotal / 24
    val hours = hoursTotal % 24
    return when {
        days > 0 -> "Connected for $days ${if (days == 1L) "day" else "days"} and $hours ${if (hours == 1L) "hour" else "hours"} ❤️"
        hours > 0 -> "Connected for $hours ${if (hours == 1L) "hour" else "hours"} ❤️"
        else -> {
            val mins = (diff / (1000 * 60)).coerceAtLeast(1)
            "Connected for $mins ${if (mins == 1L) "minute" else "minutes"} ❤️"
        }
    }
}


fun fontStyleToFamily(style: String): FontFamily = when (style.lowercase()) {
    "serif" -> FontFamily.Serif
    "cursive", "calligraphy" -> FontFamily.Cursive
    "monospace" -> FontFamily.Monospace
    else -> FontFamily.Default
}


fun exportImageToGallery(context: Context, sourceFile: File): Boolean {
    return try {
        val resolver = context.contentResolver
        val filename = "GetLob_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GetLob")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GetLob")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, filename)
            sourceFile.copyTo(destFile, overwrite = true)
            @Suppress("DEPRECATION")
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
            )
        }
        true
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to save image to gallery", e)
        false
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupPassphraseScreen(
    partnerName: String,
    pairId: String,
    onSetPassphrase: (String) -> Unit,
    onUnpair: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val mismatch = confirmPass.isNotEmpty() && pass != confirmPass
    val valid = pass.trim().length >= 6 && pass == confirmPass


    val liveFp = remember(pass) {
        if (pass.trim().length >= 6) {
            try {
                val spec = javax.crypto.spec.PBEKeySpec(pass.trim().toCharArray(), "pairwidget:$pairId".toByteArray(), 120_000, 256)
                val k = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                val h = java.security.MessageDigest.getInstance("SHA-256").digest("fp".toByteArray() + k)
                h.take(3).joinToString("-") { "%02X".format(it) }
            } catch (_: Exception) { "" }
        } else ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B141B))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF00A884), Color(0xFF10B981)))),
            contentAlignment = Alignment.Center
        ) {
            Text("🔐", fontSize = 34.sp)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Connected to $partnerName! ❤️",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF25D366)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Set Shared Encryption Key",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "To keep all your messages, photos, videos, and live satellite calls private, enter a secret passphrase agreed on with $partnerName.",
            fontSize = 13.sp,
            color = Color(0xFF8696A0),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(24.dp))

        Surface(
            color = Color(0xFF182229),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222E35)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "💡 Important",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD279)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Both you and $partnerName must type the EXACT SAME passphrase. Once set, your safety fingerprints will match and unlock the chat.",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Shared Passphrase (min 6 chars)") },
            placeholder = { Text("e.g. OurSecretWord123") },
            singleLine = true,
            visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Text(if (showPass) "👁️" else "🙈", fontSize = 16.sp)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = confirmPass,
            onValueChange = { confirmPass = it },
            label = { Text("Confirm Passphrase") },
            singleLine = true,
            visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            isError = mismatch,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        if (mismatch) {
            Spacer(Modifier.height(6.dp))
            Text("Passphrases do not match", color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        if (liveFp.isNotEmpty() && !mismatch) {
            Spacer(Modifier.height(14.dp))
            Surface(
                color = Color(0xFF14532D),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live Safety Fingerprint:", fontSize = 12.sp, color = Color(0xFF86EFAC))
                    Text(
                        liveFp,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD279),
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { onSetPassphrase(pass.trim()) },
            enabled = valid,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
        ) {
            Text("🔒 Set Passphrase & Continue to Chat", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(14.dp))

        TextButton(onClick = onUnpair) {
            Text("💔 Disconnect / Unpair", color = Color(0xFFF87171), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    widgetSettings: WidgetSettings,
    partnerNameInput: String,
    onPartnerNameChange: (String) -> Unit,
    onSavePartnerName: () -> Unit,
    inviteCode: String,
    hasCustomPassphrase: Boolean,
    fingerprint: String,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    onSetPassphrase: () -> Unit,
    onResetPassphrase: () -> Unit,
    localTransparency: Float,
    onTransparencyChange: (Float) -> Unit,
    onTransparencyFinished: () -> Unit,
    localCorner: Float,
    onCornerChange: (Float) -> Unit,
    onCornerFinished: () -> Unit,
    localBorderTransparency: Float,
    onBorderTransparencyChange: (Float) -> Unit,
    onBorderTransparencyFinished: () -> Unit,
    hasUnsavedWidgetChanges: Boolean,
    onUpdateSettings: (WidgetSettings) -> Unit,
    onSaveSettings: () -> Unit,
    onBack: () -> Unit,
    onClearCanvas: () -> Unit,
    onUnpair: () -> Unit,
    onBatteryClick: () -> Unit
) {
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


                    Text("Partner's Display Name", fontSize = 12.sp, color = Color(0xFF8696A0))
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
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
                        Brush.linearGradient(listOf(Color(effectiveBgColor).copy(alpha = localTransparency), Color(effectiveBgColor).copy(alpha = localTransparency)))
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
                            Text("Good morning ❤️", color = Color(widgetSettings.fontColor), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text("Widget Preview • ${widgetSettings.fontStyle}", color = Color(widgetSettings.fontColor).copy(alpha = 0.8f), fontSize = 11.sp)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White),
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


@Composable
private fun CustomizeMoodsDialog(
    moods: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    var newEmoji by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Customize Moods & Reactions", fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Add or remove quick reactions that you and your partner can tap.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newEmoji,
                        onValueChange = { newEmoji = it.take(4) },
                        placeholder = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.width(70.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it.take(15) },
                        placeholder = { Text("Label (e.g. Love)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            if (newEmoji.isNotBlank()) {
                                onAdd(newEmoji.trim(), newLabel.trim())
                                newEmoji = ""
                                newLabel = ""
                            }
                        },
                        enabled = newEmoji.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
                    ) {
                        Text("+")
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF262626))
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(moods) { (e, l) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$e $l", fontSize = 14.sp, color = Color.White)
                            IconButton(onClick = { onRemove(e) }, modifier = Modifier.size(24.dp)) {
                                Text("✕", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text("Reset Defaults", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF141414),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF2A3942),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

private fun downscaleToCache(ctx: Context, uri: Uri): File {
    val src = requireNotNull(ctx.contentResolver.openInputStream(uri)) { "Unable to open selected image" }
        .use { input -> requireNotNull(BitmapFactory.decodeStream(input)) { "Selected file is not a supported image" } }
    val out = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
    val scaled = ImageFit.scale(src, 1080, 1080, crop = false)
    FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    if (scaled !== src) scaled.recycle()
    src.recycle()
    return out
}
