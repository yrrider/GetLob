package com.yrrider.getlob.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.yrrider.getlob.R
import com.yrrider.getlob.data.*
import com.yrrider.getlob.data.db.SyncStatus
import com.yrrider.getlob.fcm.AppForegroundTracker
import com.yrrider.getlob.widget.ImageFit
import com.yrrider.getlob.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val CHAT_EMOJIS = listOf(
    "❤️", "🥰", "😘", "🥺", "💕", "✨", "🔥", "☕",
    "😴", "🍕", "🎉", "💪", "🤗", "🌹", "💌", "💋",
    "😍", "💖", "🌙", "⭐", "🧸", "🍫", "🥂", "🎶"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    partnerName: String,
    headerLogo: String,
    onHeaderLogoClick: () -> Unit,
    onLiveTalk: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
    onUnpair: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val history by viewModel.messages.collectAsState()
    val me by viewModel.myUid.collectAsState()
    val widgetSettings by viewModel.widgetSettings.collectAsState()
    val chatSettings by viewModel.chatSettings.collectAsState()
    val replyingToMessage by viewModel.replyingTo.collectAsState()
    val pairInfo by viewModel.pairInfo.collectAsState()

    var messageText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showPartnerProfile by remember { mutableStateOf(false) }
    var showChatCustomization by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showMoodsDialog by remember { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var moods by remember { mutableStateOf(LocalStore.getMoods(ctx)) }

    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    var photoCaption by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Track foreground chat visibility for FCM notifications & mark chat as read
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppForegroundTracker.isChatVisible.value = true
                viewModel.markChatRead()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                AppForegroundTracker.isChatVisible.value = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            AppForegroundTracker.isChatVisible.value = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(history.firstOrNull()?.id) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(0)
            viewModel.markChatRead()
        }
    }

    val cameraPhotoFile = remember {
        File(ctx.cacheDir, "camera_snap_${System.currentTimeMillis()}.jpg")
    }
    val cameraUri = remember(cameraPhotoFile) {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cameraPhotoFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraPhotoFile.exists() && cameraPhotoFile.length() > 0) {
            pendingPhotoFile = cameraPhotoFile
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = downscaleToCache(ctx, uri)
            pendingPhotoFile = f
        }
    }

    val gifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "gif_${System.currentTimeMillis()}.gif")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                viewModel.sendGif(f, "")
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "vid_${System.currentTimeMillis()}.mp4")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                viewModel.sendVideo(f, "")
            }
        }
    }

    // Snap preview dialog
    if (pendingPhotoFile != null) {
        AlertDialog(
            onDismissRequest = {
                pendingPhotoFile = null
                photoCaption = ""
            },
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

                        scope.launch(Dispatchers.IO) {
                            val prepared = ImageFit.prepareImageForUpload(
                                ctx,
                                fileToSend,
                                caption = captionToSend,
                                fontStyle = widgetSettings.fontStyle,
                                fontColor = widgetSettings.fontColor.toInt()
                            )
                            viewModel.sendSnap(prepared.file, captionToSend, prepared.thumbB64)
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
                    val avatarFile = LocalStore.partnerAvatarFile(ctx)
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFFF43F5E))))
                            .clickable { showPartnerProfile = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarFile.exists()) {
                            AsyncImage(
                                model = avatarFile,
                                contentDescription = "Profile picture",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (headerLogo.isNotBlank()) {
                            AsyncImage(
                                model = Uri.parse(headerLogo),
                                contentDescription = "Profile picture",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("❤️", fontSize = 22.sp)
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

                    IconButton(onClick = { viewModel.sendVibe() }) {
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
                                text = { Text("💔 Disconnect / Unpair", color = Color(0xFFEF4444)) },
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

        // Messages list
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(history, key = { it.id }) { m ->
                val isMine = m.from == me
                ChatMessageBubble(
                    m = m,
                    mine = isMine,
                    partnerName = partnerName,
                    chatSettings = chatSettings,
                    onReply = { viewModel.setReplyingTo(m) },
                    onImageClick = { url -> previewImageUrl = url },
                    onMediaPlay = {},
                    onRetry = { viewModel.retryMessage(m) }
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        // Reply preview strip
        if (replyingToMessage != null) {
            Surface(
                color = Color(0xFF1F2C34),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(36.dp)
                            .background(Color(0xFF25D366), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (replyingToMessage!!.from == me) "You" else partnerName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF25D366)
                        )
                        Text(
                            text = replyingToMessage!!.text.ifEmpty { "[Media]" },
                            fontSize = 12.sp,
                            color = Color(0xFF8696A0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { viewModel.setReplyingTo(null) }, modifier = Modifier.size(24.dp)) {
                        Text("✕", color = Color(0xFF8696A0), fontSize = 14.sp)
                    }
                }
            }
        }

        // Quick moods / emoji bar
        Surface(color = Color(0xFF1F2C34), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showMoodsDialog = true }, modifier = Modifier.size(32.dp)) {
                    Text("⚙️", fontSize = 16.sp)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    moods.forEach { (emoji, _) ->
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF2A3942),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.sendEmoji(emoji)
                                }
                        ) {
                            Text(
                                emoji,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom text bar
        Surface(
            color = Color(0xFF1F2C34),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_emoji),
                        contentDescription = "Emojis",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = { showAttachDialog = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_attach),
                        contentDescription = "Attach",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = { cameraLauncher.launch(cameraUri) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_snap_camera),
                        contentDescription = "Camera",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2A3942))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (messageText.isEmpty()) {
                        Text("Message…", color = Color(0xFF8696A0), fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color(0xFF25D366)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.width(6.dp))

                FloatingActionButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            viewModel.sendText(messageText)
                            messageText = ""
                        } else {
                            viewModel.sendSos()
                        }
                    },
                    containerColor = if (messageText.isNotBlank()) Color(0xFF00A884) else Color(0xFFDC2626),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (messageText.isNotBlank()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chat_send),
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_sos_alert),
                            contentDescription = "SOS",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Inline emoji picker
        if (showEmojiPicker) {
            Surface(
                color = Color(0xFF1F2C34),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CHAT_EMOJIS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    messageText += emoji
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }

    // Attach sheet
    if (showAttachDialog) {
        ModalBottomSheet(
            onDismissRequest = { showAttachDialog = false },
            containerColor = Color(0xFF1F2C34),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Share with $partnerName", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_photo_gallery,
                        label = "Gallery",
                        color = Color(0xFF9333EA),
                        onClick = {
                            showAttachDialog = false
                            galleryLauncher.launch("image/*")
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_gif_motion,
                        label = "GIF",
                        color = Color(0xFFEAB308),
                        onClick = {
                            showAttachDialog = false
                            gifLauncher.launch("image/gif")
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    ActionTile(
                        modifier = Modifier.weight(1f),
                        iconRes = R.drawable.ic_video_record,
                        label = "Video",
                        color = Color(0xFFEF4444),
                        onClick = {
                            showAttachDialog = false
                            videoLauncher.launch("video/*")
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showMoodsDialog) {
        CustomizeMoodsDialog(
            moods = moods,
            onDismiss = { showMoodsDialog = false },
            onAdd = { e, l ->
                LocalStore.addCustomMood(ctx, e, l)
                moods = LocalStore.getMoods(ctx)
            },
            onRemove = { e ->
                LocalStore.removeCustomMood(ctx, e)
                moods = LocalStore.getMoods(ctx)
            },
            onReset = {
                LocalStore.resetMoods(ctx)
                moods = LocalStore.getMoods(ctx)
            }
        )
    }

    if (showPartnerProfile) {
        PartnerProfileSheet(
            partnerName = partnerName,
            headerLogo = headerLogo,
            pairedAtMs = pairInfo?.createdAt ?: 0L,
            onChangePhoto = onHeaderLogoClick,
            onLiveTalk = onLiveTalk,
            onVibe = { viewModel.sendVibe() },
            onCustomiseChat = { showChatCustomization = true },
            onOpenSettings = onOpenSettings,
            onClearChat = onClearChat,
            onUnpair = onUnpair,
            onDismiss = { showPartnerProfile = false }
        )
    }

    if (showChatCustomization) {
        ChatCustomizationSheet(
            currentSettings = chatSettings,
            onSave = { updated ->
                viewModel.updateChatSettings(updated)
                showChatCustomization = false
            },
            onDismiss = { showChatCustomization = false }
        )
    }

    if (previewImageUrl != null) {
        ImagePreviewDialog(
            imageUrl = previewImageUrl!!,
            onDismiss = { previewImageUrl = null }
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun ChatMessageBubble(
    m: Message,
    mine: Boolean,
    partnerName: String,
    chatSettings: ChatSettings,
    onReply: () -> Unit,
    onImageClick: (String) -> Unit,
    onMediaPlay: () -> Unit,
    onRetry: () -> Unit
) {
    val bubbleBg = if (mine) Color(chatSettings.myBubbleColor) else Color(chatSettings.partnerBubbleColor)
    val cornerRadius = chatSettings.bubbleCornerDp.dp
    val bubbleShape = if (mine) {
        RoundedCornerShape(cornerRadius, cornerRadius, 2.dp, cornerRadius)
    } else {
        RoundedCornerShape(cornerRadius, cornerRadius, cornerRadius, 2.dp)
    }
    val fontFamily = fontStyleToFamily(chatSettings.fontStyle)
    val fontSize = chatSettings.messageFontSize.sp

    val offsetX = remember { Animatable(0f) }
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
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
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
                                onImageClick(m.mediaUrl.ifEmpty { m.localMediaPath })
                            } else if (m.type in listOf(MsgType.GIF, MsgType.VIDEO)) {
                                onMediaPlay()
                            }
                        },
                        onLongClick = onReply
                    )
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // Quoted reply
                    if (m.replyToId.isNotEmpty() || m.replyText.isNotEmpty()) {
                        Surface(
                            color = Color(0x33000000),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .background(Color(0xFF25D366), RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = m.replySender.ifEmpty { partnerName },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF25D366)
                                    )
                                    Text(
                                        text = m.replyText,
                                        fontSize = 11.sp,
                                        color = Color(0xFFCBD5E1),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Media rendering
                    if (m.type == MsgType.IMAGE || m.type == MsgType.GIF || m.type == MsgType.VIDEO) {
                        val mediaTarget = if (m.localMediaPath.isNotEmpty() && File(m.localMediaPath).exists()) {
                            File(m.localMediaPath)
                        } else {
                            m.mediaUrl
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = mediaTarget,
                                contentDescription = "Media",
                                modifier = Modifier.fillMaxSize()
                            )
                            if (m.type == MsgType.VIDEO) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x99000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_play_arrow),
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // Text rendering
                    if (m.text.isNotEmpty()) {
                        WhatsAppTextMessage(
                            text = m.text,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = fontSize,
                                fontFamily = fontFamily
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MessageTimestamp(
                            createdAt = m.clientCreatedAt,
                            mine = mine,
                            syncStatus = m.syncStatus,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageTimestamp(
    createdAt: Long,
    mine: Boolean,
    syncStatus: String = SyncStatus.SENT,
    onRetry: () -> Unit = {}
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
            when (syncStatus) {
                SyncStatus.SENDING -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_clock),
                        contentDescription = "Sending",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(12.dp)
                    )
                }
                SyncStatus.SENT -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_single_check),
                        contentDescription = "Sent",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(13.dp)
                    )
                }
                SyncStatus.DELIVERED -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_double_check),
                        contentDescription = "Delivered",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(13.dp)
                    )
                }
                SyncStatus.READ -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_double_check),
                        contentDescription = "Read",
                        tint = Color(0xFF53BDEB),
                        modifier = Modifier.size(13.dp)
                    )
                }
                SyncStatus.FAILED -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_error),
                        contentDescription = "Failed, tap to retry",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onRetry() }
                    )
                }
                else -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_single_check),
                        contentDescription = "Sent",
                        tint = Color(0xFF8696A0),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppTextMessage(
    text: String,
    style: TextStyle
) {
    Text(
        text = text,
        style = style
    )
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
    val ctx = LocalContext.current
    val avatarFile = LocalStore.partnerAvatarFile(ctx)

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
                if (avatarFile.exists()) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = "Partner profile photo",
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (headerLogo.isNotBlank()) {
                    AsyncImage(
                        model = Uri.parse(headerLogo),
                        contentDescription = "Partner profile photo",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("❤️", fontSize = 46.sp)
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
                color = Color(0xFF1F2C34),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connection Status", fontSize = 11.sp, color = Color(0xFF8696A0), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatConnectedDuration(pairedAtMs),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD279),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PartnerActionCircle(
                    iconRes = R.drawable.ic_satellite_talk,
                    label = "Live Talk",
                    color = Color(0xFF38BDF8),
                    onClick = onLiveTalk
                )
                PartnerActionCircle(
                    iconRes = R.drawable.ic_vibe_pulse,
                    label = "Vibe",
                    color = Color(0xFF25D366),
                    onClick = onVibe
                )
                PartnerActionCircle(
                    iconRes = R.drawable.ic_more_vert,
                    label = "Customise",
                    color = Color(0xFFFBBF24),
                    onClick = onCustomiseChat
                )
            }

            Spacer(Modifier.height(24.dp))

            Surface(
                color = Color(0xFF1F2C34),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ProfileMenuRow("🎨 Customise Chat Appearance", "Change bubble colors, font & wallpaper", onClick = onCustomiseChat)
                    HorizontalDivider(color = Color(0xFF2A3942))
                    ProfileMenuRow("⚙️ Widget & App Settings", "Configure your shared home widget", onClick = onOpenSettings)
                    HorizontalDivider(color = Color(0xFF2A3942))
                    ProfileMenuRow("🧹 Clear Chat History", "Permanently remove messages for both", onClick = onClearChat, titleColor = Color(0xFFFCA5A5))
                    HorizontalDivider(color = Color(0xFF2A3942))
                    ProfileMenuRow("💔 Disconnect / Unpair", "Leave pair and reset all local keys", onClick = onUnpair, titleColor = Color(0xFFEF4444))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatCustomizationSheet(
    currentSettings: ChatSettings,
    onSave: (ChatSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var wallpaperColor by remember { mutableStateOf(currentSettings.wallpaperColor) }
    var myBubbleColor by remember { mutableStateOf(currentSettings.myBubbleColor) }
    var partnerBubbleColor by remember { mutableStateOf(currentSettings.partnerBubbleColor) }
    var fontSize by remember { mutableStateOf(currentSettings.messageFontSize.toFloat()) }
    var cornerDp by remember { mutableStateOf(currentSettings.bubbleCornerDp.toFloat()) }
    var fontStyle by remember { mutableStateOf(currentSettings.fontStyle) }
    var swipeToReply by remember { mutableStateOf(currentSettings.swipeToReplyEnabled) }

    val wallpapers = listOf(
        0xFF0B141BL to "Dark Slate",
        0xFF121212L to "Pitch Black",
        0xFF1E293BL to "Navy Night",
        0xFF1C1917L to "Warm Earth",
        0xFF1E1B4BL to "Indigo Dreams",
        0xFF064E3BL to "Forest Pine",
        0xFF3B0764L to "Deep Velvet"
    )

    val myColors = listOf(
        0xFF005C4BL to "Emerald",
        0xFF0284C7L to "Sky Ocean",
        0xFF7C3AEDL to "Royal Violet",
        0xFFDB2777L to "Rose Passion",
        0xFFD97706L to "Amber Sunset",
        0xFF25D366L to "WhatsApp Green",
        0xFF334155L to "Slate Steel"
    )

    val partnerColors = listOf(
        0xFF202C33L to "Default Charcoal",
        0xFF27272AL to "Matte Black",
        0xFF1E293BL to "Midnight Blue",
        0xFF2E1065L to "Deep Purple",
        0xFF14532DL to "Dark Pine",
        0xFF3F3F46L to "Stone Gray"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B21),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("🎨 Customise Chat Appearance", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

            Column {
                Text("Chat Wallpaper Theme", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    wallpapers.forEach { (c, label) ->
                        ColorCirclePicker(
                            colorLong = c,
                            label = label,
                            selected = wallpaperColor == c,
                            onSelect = { wallpaperColor = c }
                        )
                    }
                }
            }

            Column {
                Text("My Message Bubble Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    myColors.forEach { (c, label) ->
                        ColorCirclePicker(
                            colorLong = c,
                            label = label,
                            selected = myBubbleColor == c,
                            onSelect = { myBubbleColor = c }
                        )
                    }
                }
            }

            Column {
                Text("Partner's Message Bubble Colour", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    partnerColors.forEach { (c, label) ->
                        ColorCirclePicker(
                            colorLong = c,
                            label = label,
                            selected = partnerBubbleColor == c,
                            onSelect = { partnerBubbleColor = c }
                        )
                    }
                }
            }

            Column {
                Text("Message Font Size: ${fontSize.toInt()} sp", fontSize = 13.sp, color = Color.White)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 12f..22f,
                    steps = 9
                )
            }

            Column {
                Text("Bubble Corner Roundness: ${cornerDp.toInt()} dp", fontSize = 13.sp, color = Color.White)
                Slider(
                    value = cornerDp,
                    onValueChange = { cornerDp = it },
                    valueRange = 4f..28f,
                    steps = 11
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Swipe to Reply", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Quickly swipe right on any message to quote it", fontSize = 11.sp, color = Color(0xFF8696A0))
                }
                Switch(
                    checked = swipeToReply,
                    onCheckedChange = { swipeToReply = it }
                )
            }

            Button(
                onClick = {
                    onSave(
                        ChatSettings(
                            wallpaperColor = wallpaperColor,
                            myBubbleColor = myBubbleColor,
                            partnerBubbleColor = partnerBubbleColor,
                            messageFontSize = fontSize.toInt(),
                            bubbleCornerDp = cornerDp.toInt(),
                            fontStyle = fontStyle,
                            swipeToReplyEnabled = swipeToReply
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884), contentColor = Color.White)
            ) {
                Text("Apply & Save Theme", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImagePreviewDialog(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Full preview",
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ColorCirclePicker(
    colorLong: Long,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(colorLong))
                .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF25D366) else Color(0x33FFFFFF), CircleShape)
                .clickable { onSelect() }
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, color = if (selected) Color(0xFF25D366) else Color(0xFF8696A0), maxLines = 1)
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
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileMenuRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    titleColor: Color = Color.White
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF8696A0), fontSize = 11.sp)
        }
        Text("›", fontSize = 20.sp, color = Color(0xFF8696A0))
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
        title = { Text("Customize Moods & Reactions", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Add or remove quick reactions that you and your partner can tap.", fontSize = 12.sp, color = Color(0xFF94A3B8))
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
                        placeholder = { Text("Label") },
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
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
        Log.e("ChatScreen", "Failed to save image to gallery", e)
        false
    }
}
