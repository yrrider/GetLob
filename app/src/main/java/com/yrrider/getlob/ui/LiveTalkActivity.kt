package com.yrrider.getlob.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Live walkie-talkie over WebRTC (LiveKit), end-to-end encrypted when a passphrase is set.
 *
 *  - Opened from the widget's satellite button: joins the pair's room and sends a "live" alert
 *    so the partner's phone shows "Tap to talk".
 *  - Opened from that notification (action OPEN_LIVE): just joins the same room.
 *  - Hold the big button to transmit; release to stop. Remote audio plays automatically.
 *
 * E2EE: audio frames are encrypted on the phone with a shared key derived from your passphrase
 * (LiveKey), so the LiveKit server only relays ciphertext. If the passphrases differ you will
 * connect but hear silence.
 *
 * Android only allows microphone capture while the app is visible, which is why both people
 * must have this panel open (the notification is the "tap to talk" step).
 */
class LiveTalkActivity : ComponentActivity() {
    companion object { const val ACTION_OPEN_LIVE = "com.yrrider.getlob.OPEN_LIVE" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sendAlert = intent.action != ACTION_OPEN_LIVE
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF0D0D0D))) {
                LiveScreen(sendAlert) { finish() }
            }
        }
    }
}

@Composable
private fun LiveScreen(sendAlert: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveKey = remember { LiveKey.get(ctx) }
    val room = remember {
        val e2ee = liveKey?.let { k -> E2EEOptions().also { it.keyProvider.setSharedKey(k) } }
        LiveKit.create(ctx.applicationContext, options = RoomOptions(e2eeOptions = e2ee))
    }
    var status by remember { mutableStateOf("Connecting to room\u2026") }
    var connected by remember { mutableStateOf(false) }
    var partnerJoined by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (!hasMic()) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        try {
            val res = FirebaseFunctions.getInstance().getHttpsCallable("getLiveToken").call().await().data as Map<*, *>
            if (liveKey != null) room.e2eeManager?.enableE2EE(true)
            room.connect(res["url"] as String, res["token"] as String)
            connected = true
            status = "Waiting for partner to join\u2026"
            if (sendAlert) Repo.send(ctx, Message(type = "live"))
        } catch (e: Exception) {
            status = "Could not connect: ${e.message}"
        }
    }

    LaunchedEffect(connected) {
        if (connected) {
            while (isActive) {
                val hasPartner = room.remoteParticipants.isNotEmpty()
                if (hasPartner != partnerJoined) {
                    partnerJoined = hasPartner
                    if (hasPartner) {
                        status = "🟢 Partner Joined! (Live)"
                        if (!isMuted && hasMic()) {
                            runCatching { room.localParticipant.setMicrophoneEnabled(true) }
                        }
                    } else {
                        status = "Waiting for partner to join\u2026"
                        runCatching { room.localParticipant.setMicrophoneEnabled(false) }
                    }
                }
                delay(600)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            room.disconnect()
            room.release()
        }
    }

    fun toggleMute() {
        if (!connected || !partnerJoined) return
        if (!hasMic()) {
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
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(none, null) { close() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().clickable(none, null) { },
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0D0D0D),
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📡 Tap to Talk", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = status,
                    fontSize = 14.sp,
                    fontWeight = if (partnerJoined) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !connected -> Color(0xFF94A3B8)
                        partnerJoined -> Color(0xFF10B981)
                        else -> Color(0xFFFBBF24)
                    }
                )
                if (connected && !partnerJoined) {
                    Text("Invitation & vibrations sent to partner 📡", fontSize = 11.sp, color = Color(0xFF94A3B8))
                } else if (connected && partnerJoined) {
                    Text(if (isMuted) "Microphone muted (Tap to speak)" else "Broadcasting your voice live (Tap to mute)", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                }
                Spacer(Modifier.height(18.dp))

                val buttonBg = when {
                    !connected -> Color(0xFF27272A)
                    !partnerJoined -> Color(0xFFB45309)
                    isMuted -> Color(0xFFDC2626)
                    else -> Color(0xFF059669)
                }

                Box(
                    Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(enabled = connected && partnerJoined) { toggleMute() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            !connected -> "⏳"
                            !partnerJoined -> "📡"
                            isMuted -> "🔇"
                            else -> "🎤"
                        },
                        fontSize = 44.sp
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
