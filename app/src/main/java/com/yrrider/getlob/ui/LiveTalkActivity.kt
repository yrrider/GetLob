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
            MaterialTheme(colorScheme = darkColorScheme()) { LiveScreen(sendAlert) { finish() } }
        }
    }
}

@Composable
private fun LiveScreen(sendAlert: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val liveKey = remember { LiveKey.get(ctx) }
    val room = remember {
        val e2ee = liveKey?.let { k -> E2EEOptions().also { it.keyProvider.setSharedKey(k) } }
        LiveKit.create(ctx.applicationContext, options = RoomOptions(e2eeOptions = e2ee))
    }
    var status by remember { mutableStateOf("Connecting\u2026") }
    var connected by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }

    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (!hasMic()) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        try {
            // Server verifies we belong to this pair and returns a short-lived room token.
            val res = FirebaseFunctions.getInstance().getHttpsCallable("getLiveToken").call().await().data as Map<*, *>
            if (liveKey != null) room.e2eeManager?.enableE2EE(true)   // must be set before connecting
            room.connect(res["url"] as String, res["token"] as String)
            connected = true
            status = if (liveKey != null) "\uD83D\uDD12 Encrypted \u2014 hold to talk" else "\uD83D\uDD13 Not encrypted \u2014 hold to talk"
            if (sendAlert) Repo.send(ctx, Message(type = "live"))
        } catch (e: Exception) {
            status = "Could not connect: ${e.message}"
        }
    }
    DisposableEffect(Unit) { onDispose { room.disconnect(); room.release() } }

    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(none, null) { close() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().clickable(none, null) { },
            shape = RoundedCornerShape(24.dp), color = Color(0xFF2A2A3C), tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\uD83D\uDCE1 Live talk", fontSize = 18.sp)
                Text(status, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.size(128.dp).clip(CircleShape)
                        .background(if (talking) Color(0xFFD32F2F) else if (connected) Color(0xFF4F6BED) else Color(0xFF555566))
                        .pointerInput(connected) {
                            detectTapGestures(onPress = {
                                if (connected && hasMic()) {
                                    talking = true
                                    runCatching { room.localParticipant.setMicrophoneEnabled(true) }
                                    tryAwaitRelease()
                                    talking = false
                                    runCatching { room.localParticipant.setMicrophoneEnabled(false) }
                                }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(if (talking) "\uD83D\uDD34" else "\uD83C\uDF99", fontSize = 44.sp) }
                Spacer(Modifier.height(12.dp))
                TextButton(close) { Text("Leave") }
            }
        }
    }
}
