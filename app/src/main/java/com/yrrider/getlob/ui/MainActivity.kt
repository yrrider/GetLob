package com.yrrider.getlob.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import com.yrrider.getlob.data.Crypto
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.fcm.Notifier
import com.yrrider.getlob.ui.chat.ChatScreen
import com.yrrider.getlob.ui.chat.ChatViewModel
import com.yrrider.getlob.ui.pairing.PairingScreen
import com.yrrider.getlob.ui.pairing.SetupPassphraseScreen
import com.yrrider.getlob.ui.settings.SettingsScreen
import com.yrrider.getlob.widget.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class AppScreen {
    CHAT,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.rgb(0x0B, 0x14, 0x1B)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false

        runCatching {
            Notifier.createChannels(this)
        }.onFailure { e ->
            Log.e("GetLob", "Failed to create notification channels", e)
        }

        LiveTokenCache.prewarm(this)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel()

    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pairId by remember { mutableStateOf(with(LocalStore) { ctx.pairId }) }
    var inviteCode by remember { mutableStateOf(with(LocalStore) { ctx.pairCode }) }
    var joinCode by remember { mutableStateOf("") }
    var isCreatingCode by remember { mutableStateOf(false) }
    var isJoiningCode by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(AppScreen.CHAT) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    var headerLogo by remember {
        mutableStateOf(ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("headerLogo", "") ?: "")
    }

    val headerLogoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val avatarFile = LocalStore.partnerAvatarFile(ctx)
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        avatarFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                withContext(Dispatchers.Main) {
                    headerLogo = avatarFile.absolutePath
                    ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
                        .putString("headerLogo", headerLogo).apply()
                }
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
            Repo.uid()
            val remotePairId = Repo.pairId(ctx)
            if (remotePairId.isNotEmpty()) {
                pairId = remotePairId
                viewModel.setPairId(remotePairId)
            }
        } catch (e: Throwable) {
            Log.w("GetLob", "Init check: ${e.message}")
            error = "Couldn't check your connection. Check your internet, then retry pairing."
        } finally {
            ready = true
        }

        launch(Dispatchers.IO) {
            runCatching {
                val t = FirebaseMessaging.getInstance().token.await()
                Repo.saveToken(t, ctx)
            }.onFailure { e -> Log.w("GetLob", "Failed to register push token", e) }
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

    LaunchedEffect(pairInfo?.canvasClearedAt) {
        val remoteCleared = pairInfo?.canvasClearedAt ?: 0L
        if (remoteCleared > 0L && remoteCleared > LocalStore.getLastClearedAt(ctx)) {
            LocalStore.setLastClearedAt(ctx, remoteCleared)
            MediaProcessor.clearCanvas(ctx)
        }
    }

    val widgetSettings by viewModel.widgetSettings.collectAsState()
    var partnerNameInput by remember(widgetSettings.partnerName) { mutableStateOf(widgetSettings.partnerName) }
    var hasUnsavedWidgetChanges by remember { mutableStateOf(false) }

    var localTransparency by remember(widgetSettings.bgTransparency) { mutableFloatStateOf(widgetSettings.bgTransparency) }
    var localCorner by remember(widgetSettings.cornerDp) { mutableFloatStateOf(widgetSettings.cornerDp.toFloat()) }

    val hasCustomPassphrase = remember(pairId, keyTick) {
        pairId.isNotEmpty() && Crypto.hasKey(ctx)
    }

    val fingerprint = remember(keyTick, pairId) {
        if (pairId.isNotEmpty()) Crypto.fingerprint(ctx) else ""
    }

    BackHandler(enabled = currentScreen == AppScreen.SETTINGS) {
        currentScreen = AppScreen.CHAT
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
        PairingScreen(
            pairInfo = pairInfo,
            inviteCode = inviteCode,
            joinCode = joinCode,
            isCreatingCode = isCreatingCode,
            isJoiningCode = isJoiningCode,
            error = error,
            deviceUuid = LocalStore.appUuid(ctx),
            onEnterPairingMode = {
                scope.launch {
                    isCreatingCode = true
                    error = ""
                    try {
                        inviteCode = Repo.createPair(ctx)
                        pairId = Repo.pairId(ctx)
                        viewModel.setPairId(pairId)
                        error = ""
                    } catch (e: Exception) {
                        error = e.message ?: "Failed to generate code"
                    } finally {
                        isCreatingCode = false
                    }
                }
            },
            onExitPairingMode = {
                scope.launch {
                    runCatching { Repo.exitPairingMode(ctx) }
                    pairId = ""
                    inviteCode = ""
                    viewModel.setPairId("")
                    error = ""
                }
            },
            onJoinCodeChange = {
                joinCode = it.uppercase().take(6)
                error = ""
            },
            onJoinPartner = {
                scope.launch {
                    isJoiningCode = true
                    error = ""
                    try {
                        Repo.joinPair(ctx, joinCode)
                        pairId = Repo.pairId(ctx)
                        viewModel.setPairId(pairId)
                        error = ""
                    } catch (e: Exception) {
                        error = e.message ?: "Invalid code or partner not in pairing mode"
                    } finally {
                        isJoiningCode = false
                    }
                }
            }
        )
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
                viewModel = viewModel,
                partnerName = widgetSettings.partnerName,
                headerLogo = headerLogo,
                onHeaderLogoClick = {
                    headerLogoPicker.launch("image/*")
                },
                onLiveTalk = {
                    ctx.startActivity(Intent(ctx, LiveTalkActivity::class.java))
                },
                onOpenSettings = {
                    currentScreen = AppScreen.SETTINGS
                },
                onClearChat = {
                    showClearHistoryDialog = true
                },
                onUnpair = {
                    showUnpairDialog = true
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
                    val updated = widgetSettings.copy(partnerName = trimmed)
                    LocalStore.writeSettings(ctx, updated)
                    viewModel.widgetSettings.value = updated
                    toast("Partner name updated locally")
                },
                inviteCode = inviteCode,
                fingerprint = fingerprint,
                localTransparency = localTransparency,
                onTransparencyChange = {
                    localTransparency = it
                    hasUnsavedWidgetChanges = true
                },
                onTransparencyFinished = {
                    viewModel.updateWidgetSettings(widgetSettings.copy(bgTransparency = localTransparency))
                },
                localCorner = localCorner,
                onCornerChange = {
                    localCorner = it
                    hasUnsavedWidgetChanges = true
                },
                onCornerFinished = {
                    viewModel.updateWidgetSettings(widgetSettings.copy(cornerDp = localCorner.toInt()))
                },
                hasUnsavedWidgetChanges = hasUnsavedWidgetChanges,
                onUpdateSettings = { newS ->
                    hasUnsavedWidgetChanges = true
                    viewModel.updateWidgetSettings(newS)
                },
                onSaveSettings = {
                    viewModel.updateWidgetSettings(
                        widgetSettings.copy(
                            bgTransparency = localTransparency,
                            cornerDp = localCorner.toInt()
                        )
                    )
                    hasUnsavedWidgetChanges = false
                    toast("Widget settings saved")
                },
                onBack = {
                    currentScreen = AppScreen.CHAT
                },
                onClearCanvas = {
                    showClearHistoryDialog = true
                },
                onUnpair = {
                    showUnpairDialog = true
                },
                onBatteryClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )
        }
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair from Partner?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to disconnect from ${widgetSettings.partnerName}? All shared messages, uploaded photos/videos, and pair data will be permanently and immediately deleted.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairDialog = false
                        viewModel.unpair {
                            pairId = ""
                            inviteCode = ""
                            keyTick++
                            currentScreen = AppScreen.CHAT
                            toast("Unpaired from partner. All local data cleared.")
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
                        viewModel.clearHistory()
                        scope.launch {
                            MediaProcessor.clearCanvas(ctx)
                        }
                        toast("Chat & Canvas cleared")
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
}
