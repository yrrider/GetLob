package com.yrrider.getlob.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.functions.FirebaseFunctions
import com.yrrider.getlob.data.LiveKey
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.Repo
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

class LiveTalkActivity : ComponentActivity() {
    companion object { const val ACTION_OPEN_LIVE = "com.yrrider.getlob.OPEN_LIVE" }

    private var proximityWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "getlob:LiveTalkProximity"
                )
            }
        }
        val sendAlert = intent.action != ACTION_OPEN_LIVE
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF0A0A0A))) {
                LiveScreen(sendAlert) { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            if (proximityWakeLock?.isHeld == false) {
                proximityWakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        }
    }
}

@Composable
private fun LiveScreen(sendAlert: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveKey = remember { LiveKey.get(ctx) }

    var initError by remember { mutableStateOf<String?>(null) }
    var roomInstance by remember { mutableStateOf<Room?>(null) }

    val roomIsActive = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        try {
            val e2ee = liveKey?.let { k ->
                runCatching {
                    E2EEOptions().also { it.keyProvider.setSharedKey(k) }
                }.getOrNull()
            }
            roomInstance = LiveKit.create(ctx.applicationContext, options = RoomOptions(e2eeOptions = e2ee))
            roomIsActive.set(true)
        } catch (t: Throwable) {
            Log.e("LiveTalkActivity", "Failed to initialize LiveKit", t)
            initError = t.message ?: "Live Talk audio engine unavailable on this device"
        }
    }

    var status by remember { mutableStateOf("Connecting to room…") }
    var connected by remember { mutableStateOf(false) }
    var partnerJoined by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && !isMuted && connected && partnerJoined) {
            scope.launch {
                runCatching { roomInstance?.localParticipant?.setMicrophoneEnabled(true) }
            }
        }
    }

    LaunchedEffect(roomInstance) {
        val room = roomInstance ?: return@LaunchedEffect
        if (!hasMic()) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        try {
            val res = FirebaseFunctions.getInstance().getHttpsCallable("getLiveToken").call().await().data as? Map<*, *>
            val url = (res?.get("url") as? String)?.trim() ?: ""
            val token = (res?.get("token") as? String)?.trim() ?: ""

            if (!roomIsActive.get() || room !== roomInstance) return@LaunchedEffect

            if (url.isBlank() || token.isBlank()) {
                status = "Live Talk service not configured.\nSet LIVEKIT_URL & secrets in Firebase Functions."
                return@LaunchedEffect
            }

            if (liveKey != null) runCatching { room.e2eeManager?.enableE2EE(true) }
            room.connect(url, token)
            if (!roomIsActive.get() || room !== roomInstance) return@LaunchedEffect
            connected = true
            status = "Waiting for partner to join…"
            if (sendAlert) runCatching { Repo.send(ctx, Message(type = "live")) }
        } catch (e: kotlinx.coroutines.CancellationException) {

            throw e
        } catch (e: Exception) {
            Log.e("LiveTalkActivity", "Connect failed", e)
            status = "Could not connect: ${e.message}"
        }
    }

    LaunchedEffect(connected, roomInstance) {
        val room = roomInstance
        if (connected && room != null) {
            partnerJoined = room.remoteParticipants.isNotEmpty()
            if (partnerJoined) status = "🟢 Partner Joined! (Live)"
            runCatching {
                room.events.events.collect {
                    val hasPartner = room.remoteParticipants.isNotEmpty()
                    if (hasPartner != partnerJoined) {
                        partnerJoined = hasPartner
                        if (hasPartner) {
                            status = "🟢 Partner Joined! (Live)"
                        } else {
                            status = "Waiting for partner to join…"
                            isMuted = true
                            scope.launch {
                                runCatching { room.localParticipant.setMicrophoneEnabled(false) }
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(roomInstance) {
        val room = roomInstance
        onDispose {

            if (room != null) {
                roomIsActive.set(false)
                connected = false
                runCatching { room.disconnect() }
                runCatching { room.release() }
            }
        }
    }

    fun toggleMute() {
        val room = roomInstance ?: return
        if (!connected || !partnerJoined) return
        if (!hasMic()) {
            isMuted = false
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val nextMute = !isMuted
        isMuted = nextMute
        scope.launch {
            runCatching { room.localParticipant.setMicrophoneEnabled(!nextMute) }
        }
    }

    val none = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(none, null) { close() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .clickable(none, null) { },
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0D0D0D),
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📡 Tap to Talk", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (liveKey != null) Color(0x2210B981) else Color(0x22EF4444))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (liveKey != null) "🔒 Encrypted" else "🔓 Voice Encryption Off",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (liveKey != null) Color(0xFF34D399) else Color(0xFFF87171)
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (initError != null) {
                    Surface(
                        color = Color(0x33EF4444),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "Live audio unavailable:\n$initError",
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    Text(
                        text = status,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (partnerJoined) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            !connected -> Color(0xFF94A3B8)
                            partnerJoined -> Color(0xFF10B981)
                            else -> Color(0xFFFBBF24)
                        }
                    )
                    if (connected && !partnerJoined) {
                        Spacer(Modifier.height(2.dp))
                        Text("Invitation sent to partner 📡", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    } else if (connected && partnerJoined) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isMuted) "Microphone muted (Tap button to speak)" else "Broadcasting your voice live (Tap button to mute)",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                val buttonBg = when {
                    initError != null -> Color(0xFF27272A)
                    !connected -> Color(0xFF27272A)
                    !partnerJoined -> Color(0xFFB45309)
                    isMuted -> Color(0xFFDC2626)
                    else -> Color(0xFF059669)
                }

                Box(
                    Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(enabled = connected && partnerJoined && initError == null) { toggleMute() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            initError != null -> "⚠️"
                            !connected -> "⏳"
                            !partnerJoined -> "📡"
                            isMuted -> "🔇"
                            else -> "🎤"
                        },
                        fontSize = 38.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = close,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x40FFFFFF))
                ) {
                    Text("Leave", color = Color.White)
                }
            }
        }
    }
}
