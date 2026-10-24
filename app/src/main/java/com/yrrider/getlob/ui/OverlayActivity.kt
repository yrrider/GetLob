package com.yrrider.getlob.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.MsgType
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.SecureMedia
import com.yrrider.getlob.data.mediaUri
import com.yrrider.getlob.data.rememberMedia
import com.yrrider.getlob.fcm.AudioAlerts
import com.yrrider.getlob.widget.ImageFit
import com.yrrider.getlob.widget.MediaProcessor
import com.yrrider.getlob.data.WidgetSettings
import com.yrrider.getlob.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Coil loader that can animate GIFs and show video frames. Shared with MainActivity. */
fun mediaLoader(ctx: Context): ImageLoader = ImageLoader.Builder(ctx).components {
    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    add(VideoFrameDecoder.Factory())
}.build()

/**
 * Translucent activity shown when the widget is tapped: a floating Google-style widget overlay
 * anchored right near the home screen widget.
 * Features:
 *  - Opens near the widget itself with a smooth spring animation
 *  - Transparent backdrop (does not dim the screen)
 *  - Single row of colourful minimalistic round icons:
 *      1. Media (image/video/gif)
 *      2. Text / Mood
 *      3. Tap to talk (Live Talk)
 *      4. Vibe (haptic pulse)
 *      5. SOS (emergency alert)
 *  - No text under icons, clean minimalistic pill
 */
class OverlayActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PLAY_URL = "play_url"
        const val EXTRA_PLAY_KIND = "play_kind"
        const val EXTRA_SOS = "sos"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sos = intent.getBooleanExtra(EXTRA_SOS, false)
        if (sos) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val url = intent.getStringExtra(EXTRA_PLAY_URL).orEmpty()
        val kind = intent.getStringExtra(EXTRA_PLAY_KIND).orEmpty()
        val widgetBounds = intent.sourceBounds

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF0D0D0D),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                OverlayRoot(url, kind, sos, widgetBounds) {
                    AudioAlerts.stopSosSound()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioAlerts.stopSosSound()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

private enum class Mode { MENU, MEDIA_CHOOSER, CAPTION, TEXT, GAME, PLAY, ALERT, CONFIRM_SOS }

private val EMOJIS = listOf(
    "❤️" to "Love",
    "🥰" to "Adore",
    "✨" to "Vibe",
    "🔥" to "Energy",
    "☕" to "Coffee",
    "😴" to "Sleepy",
    "🥺" to "Miss you",
    "🎉" to "Happy"
)

@Composable
private fun OverlayRoot(
    playUrl: String,
    playKind: String,
    sos: Boolean,
    widgetBounds: Rect?,
    close: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val last = remember { LocalStore.read(ctx) }
    var pUrl by remember { mutableStateOf(playUrl) }
    var pKind by remember { mutableStateOf(playKind) }
    var mode by remember {
        mutableStateOf(
            when {
                sos -> Mode.ALERT
                playUrl.isNotEmpty() && playKind in listOf(MsgType.VOICE, MsgType.PTT, MsgType.GIF, MsgType.VIDEO) -> Mode.PLAY
                else -> Mode.MENU
            }
        )
    }
    var busy by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (sos) {
            AudioAlerts.playSosSound(ctx)
            AudioAlerts.vibrate(ctx, isSos = true)
        }
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun job(quit: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
                if (quit) close()
            } catch (e: Exception) {
                toast("Failed: ${e.message}")
                busy = false
            }
        }
    }

    suspend fun sendAndShow(m: Message, localFile: File? = null) {
        if (m.type in listOf(MsgType.TEXT, MsgType.EMOJI, MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO)) {
            runCatching { MediaProcessor.apply(ctx, m, localFile) }
        }
        Repo.send(ctx, m)
    }

    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    var photoCaption by remember { mutableStateOf("") }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && cameraTempFile != null && cameraTempFile!!.exists() && cameraTempFile!!.length() > 0) {
            pendingPhotoFile = cameraTempFile
            mode = Mode.CAPTION
        }
    }

    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val f = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
            cameraTempFile = f
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
            cameraLauncher.launch(uri)
        } else {
            toast("Camera permission needed to snap photos")
        }
    }

    fun launchCameraSnap() {
        cameraPerm.launch(Manifest.permission.CAMERA)
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val small = withContext(Dispatchers.IO) { downscaleToCache(ctx, uri) }
                pendingPhotoFile = small
                mode = Mode.CAPTION
            }
        }
    }

    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) job {
            sendAndShow(Message(type = MsgType.GIF, mediaUrl = Repo.upload(ctx, uri, "gif")))
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) job {
            sendAndShow(Message(type = MsgType.VIDEO, mediaUrl = Repo.upload(ctx, uri, "mp4")))
        }
    }



    // Calculate vertical position near the home-screen widget
    val density = LocalDensity.current.density
    val screenH = ctx.resources.displayMetrics.heightPixels
    val topOffsetDp = remember(widgetBounds) {
        if (widgetBounds != null) {
            val below = widgetBounds.bottom / density + 12f
            val above = (widgetBounds.top / density - 90f).coerceAtLeast(36f)
            if (widgetBounds.bottom + 140 * density < screenH) below.dp else above.dp
        } else {
            ((screenH * 0.36f) / density).dp
        }
    }

    val none = remember { MutableInteractionSource() }

    // Transparent click-outside backdrop: Screen DOES NOT dim!
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(none, null) { close() },
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(androidx.compose.animation.core.tween(120, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) +
                    scaleIn(initialScale = 0.94f, animationSpec = androidx.compose.animation.core.tween(140, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
            exit = fadeOut(androidx.compose.animation.core.tween(90)) + scaleOut(targetScale = 0.95f, animationSpec = androidx.compose.animation.core.tween(90)),
            modifier = Modifier
                .padding(top = topOffsetDp, start = 16.dp, end = 16.dp)
                .clickable(none, null) { /* don't dismiss when tapping pill */ }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xF2080808),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x2BFFFFFF)),
                modifier = Modifier.wrapContentWidth()
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (busy) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                            Spacer(Modifier.width(12.dp))
                            Text("Sending to partner…", fontSize = 14.sp, color = Color.White)
                        }
                    } else when (mode) {
                        // Google widget overlay style: Single horizontal row of colourful minimalistic round icons
                        Mode.MENU -> {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Instant Camera Snap (Snapchat style)
                                MinimalRoundIcon(
                                    emoji = "📸",
                                    gradient = listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),
                                    onClick = { launchCameraSnap() }
                                )

                                // 2. Media: Gallery Photo / Video / GIF
                                MinimalRoundIcon(
                                    emoji = "🖼️",
                                    gradient = listOf(Color(0xFF8B5CF6), Color(0xFFA855F7)),
                                    onClick = { mode = Mode.MEDIA_CHOOSER }
                                )

                                // 3. Text / Mood
                                MinimalRoundIcon(
                                    emoji = "✍️",
                                    gradient = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
                                    onClick = { mode = Mode.TEXT }
                                )

                                // 4. Tap to talk (Live Talk)
                                MinimalRoundIcon(
                                    emoji = "📡",
                                    gradient = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)),
                                    onClick = {
                                        ctx.startActivity(
                                            Intent(ctx, LiveTalkActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                            }
                                        )
                                        close()
                                    }
                                )

                                // 5. Tic-Tac-Toe Game
                                MinimalRoundIcon(
                                    emoji = "🎮",
                                    gradient = listOf(Color(0xFF14B8A6), Color(0xFF0D9488)),
                                    onClick = { mode = Mode.GAME }
                                )

                                // 6. Vibe (Haptic pulse)
                                MinimalRoundIcon(
                                    emoji = "📳",
                                    gradient = listOf(Color(0xFF10B981), Color(0xFF059669)),
                                    onClick = {
                                        AudioAlerts.vibrate(ctx, isSos = false)
                                        job { Repo.send(ctx, Message(type = MsgType.VIBE)) }
                                    }
                                )

                                // 7. SOS
                                MinimalRoundIcon(
                                    emoji = "🆘",
                                    gradient = listOf(Color(0xFFEF4444), Color(0xFFB91C1C)),
                                    onClick = { mode = Mode.CONFIRM_SOS }
                                )

                                if (last.mediaUrl.isNotEmpty() && last.kind in listOf(MsgType.GIF, MsgType.VIDEO, MsgType.VOICE)) {
                                    MinimalRoundIcon(
                                        emoji = "▶️",
                                        gradient = listOf(Color(0xFF38BDF8), Color(0xFF0284C7)),
                                        onClick = {
                                            pUrl = last.mediaUrl
                                            pKind = last.kind
                                            mode = Mode.PLAY
                                        }
                                    )
                                }
                            }
                        }

                        // Submenu: Media Chooser (Snap, Gallery, GIF, Video)
                        Mode.MEDIA_CHOOSER -> {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    SubOption(icon = "📸", label = "Snap") {
                                        launchCameraSnap()
                                    }
                                    SubOption(icon = "🖼️", label = "Photo") {
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                    SubOption(icon = "🎞️", label = "GIF") {
                                        gifPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")))
                                    }
                                    SubOption(icon = "📹", label = "Video") {
                                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton({ mode = Mode.MENU }) { Text("Back", color = Color(0xFF94A3B8)) }
                            }
                        }

                        // Snapchat-style photo preview with text overlay
                        Mode.CAPTION -> {
                            val file = pendingPhotoFile
                            if (file != null) {
                                val settings = remember { LocalStore.readSettings(ctx) }
                                Column(
                                    Modifier.widthIn(max = 320.dp).padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.Black),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = file,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        if (photoCaption.isNotEmpty()) {
                                            Surface(
                                                color = Color(0x99000000),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = 8.dp)
                                            ) {
                                                Text(
                                                    text = photoCaption,
                                                    color = Color(settings.fontColor),
                                                    textAlign = TextAlign.Center,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = photoCaption,
                                        onValueChange = { photoCaption = it.take(100) },
                                        placeholder = { Text("Add text over image (Snapchat style)…", fontSize = 12.sp, color = Color(0xFF888888)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton({
                                            if (pendingPhotoFile?.name?.startsWith("snap_") == true) pendingPhotoFile?.delete()
                                            pendingPhotoFile = null
                                            photoCaption = ""
                                            mode = Mode.MENU
                                        }) {
                                            Text("Cancel", color = Color(0xFF94A3B8))
                                        }
                                        Button(
                                            onClick = {
                                                val fileToSend = file
                                                val caption = photoCaption.trim()
                                                if (pendingPhotoFile?.name?.startsWith("snap_") == true) pendingPhotoFile?.delete()
                                            pendingPhotoFile = null
                                                photoCaption = ""
                                                job {
                                                    val finalFile = withContext(Dispatchers.IO) {
                                                        val optimized = ImageFit.compressForUpload(ctx, fileToSend, maxDim = 1080, quality = 82)
                                                        if (caption.isNotEmpty()) {
                                                            val originalBmp = BitmapFactory.decodeFile(optimized.absolutePath)
                                                            if (originalBmp != null) {
                                                                val currentSettings = LocalStore.readSettings(ctx)
                                                                val stamped = WidgetRenderer.overlayTextOnBitmap(
                                                                    originalBmp,
                                                                    caption,
                                                                    currentSettings.fontStyle,
                                                                    currentSettings.fontColor.toInt()
                                                                )
                                                                val stampedFile = File(ctx.cacheDir, "snap_stamped_${System.currentTimeMillis()}.jpg")
                                                                FileOutputStream(stampedFile).use { out ->
                                                                    stamped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                                                                }
                                                                if (optimized != fileToSend) optimized.delete()
                                                                stampedFile
                                                            } else optimized
                                                        } else optimized
                                                    }
                                                    val url = Repo.upload(ctx, finalFile, "jpg")
                                                    runCatching { finalFile.copyTo(SecureMedia.cacheFile(ctx, url), overwrite = true) }
                                                    sendAndShow(Message(type = MsgType.IMAGE, mediaUrl = url, text = caption), localFile = finalFile)
                                                    if (finalFile != fileToSend) finalFile.delete()
                                                    if (fileToSend.name.startsWith("snap_")) fileToSend.delete()
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                                        ) {
                                            Text("Send Snap 🚀", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                mode = Mode.MENU
                            }
                        }

                        // Text & Mood Composer
                        Mode.TEXT -> {
                            var text by remember { mutableStateOf("") }
                            Column(Modifier.widthIn(max = 320.dp).padding(6.dp)) {
                                Text(
                                    "Tap mood or write note",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                val customMoods = remember { LocalStore.getMoods(ctx) }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    customMoods.forEach { (emoji, label) ->
                                        Box(
                                            Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x22FFFFFF))
                                                .clickable {
                                                    job { sendAndShow(Message(type = MsgType.EMOJI, text = if (label.isNotBlank()) "$emoji $label" else emoji)) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(emoji, fontSize = 18.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it.take(280) },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Note for partner's widget…", fontSize = 13.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton({ mode = Mode.MENU }) { Text("Back", color = Color(0xFF94A3B8)) }
                                    Button(
                                        onClick = { job { sendAndShow(Message(type = MsgType.TEXT, text = text.trim())) } },
                                        enabled = text.isNotBlank(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                                    ) {
                                        Text("Post")
                                    }
                                }
                            }
                        }

                        Mode.GAME -> {
                            TicTacToePanel(
                                onBack = { mode = Mode.MENU },
                                onMoved = { text -> scope.launch { runCatching { Repo.send(ctx, Message(type = MsgType.GAME, text = text)) } } }
                            )
                        }

                        Mode.PLAY -> PlayPanel(pUrl, pKind) { mode = Mode.MENU }

                        Mode.CONFIRM_SOS -> {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🚨 Send Emergency SOS?", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Their phone will loudly ring & vibrate.", fontSize = 12.sp, color = Color(0xFFFDA4AF))
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton({ mode = Mode.MENU }, shape = RoundedCornerShape(12.dp)) { Text("Cancel") }
                                    Button(
                                        onClick = { job { Repo.send(ctx, Message(type = MsgType.SOS, text = "SOS")) } },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Send SOS Now")
                                    }
                                }
                            }
                        }

                        Mode.ALERT -> {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🆘", fontSize = 56.sp)
                                Text("SOS Alert from Partner!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        AudioAlerts.stopSosSound()
                                        close()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Dismiss Alarm")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Beautiful, colourful, minimalistic round icon button.
 * No text under icons.
 */
@Composable
private fun MinimalRoundIcon(
    emoji: String,
    gradient: List<Color>,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient))
            .border(1.dp, Color(0x33FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 21.sp)
    }
}

@Composable
private fun SubOption(icon: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF181818)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}



/** Plays voice / video / GIF. Encrypted media is downloaded and decrypted first (rememberMedia). */
@Composable
private fun PlayPanel(url: String, kind: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val model = rememberMedia(url)
    if (model == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Loading…", fontSize = 13.sp)
        }
        TextButton(onBack) { Text("Back") }
        return
    }
    when (kind) {
        MsgType.VOICE, MsgType.PTT -> {
            var player by remember { mutableStateOf<MediaPlayer?>(null) }
            fun play() {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(ctx, mediaUri(model))
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
            LaunchedEffect(model) { play() }
            DisposableEffect(Unit) { onDispose { player?.release() } }
            Text("🎙️ Playing…", fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(::play) { Text("Replay") }
                TextButton(onBack) { Text("Back") }
            }
        }
        MsgType.VIDEO -> {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(mediaUri(model))
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )
            TextButton(onBack) { Text("Back") }
        }
        else -> { // GIF
            AsyncImage(model, null, imageLoader = remember { mediaLoader(ctx) }, modifier = Modifier.fillMaxWidth().height(240.dp))
            TextButton(onBack) { Text("Back") }
        }
    }
}

/** Copies a picked image to cache and downsizes it so uploads are fast and widget-friendly. */
private fun downscaleToCache(ctx: Context, uri: Uri): File {
    val src = File(ctx.cacheDir, "pick_src_${System.nanoTime()}")
    ctx.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(src).use { i.copyTo(it) } }
    val out = ImageFit.compressForUpload(ctx, src, maxDim = 1080, quality = 82)
    src.delete()
    return out
}
