package com.yrrider.getlob.ui.pairing

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupPassphraseScreen(
    partnerName: String,
    pairId: String,
    onSetPassphrase: (String) -> Unit,
    onUnpair: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var liveFp by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val mismatch = confirmPass.isNotEmpty() && pass != confirmPass
    val valid = pass.trim().length >= 6 && pass == confirmPass

    LaunchedEffect(pass) {
        if (pass.trim().length >= 6) {
            delay(250)
            val computed = withContext(Dispatchers.Default) {
                try {
                    val spec = javax.crypto.spec.PBEKeySpec(
                        pass.trim().toCharArray(),
                        "pairwidget:$pairId".toByteArray(),
                        120_000,
                        256
                    )
                    val k = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).encoded
                    val h = java.security.MessageDigest.getInstance("SHA-256")
                        .digest("fp".toByteArray() + k)
                    h.take(3).joinToString("-") { "%02X".format(it) }
                } catch (_: Exception) {
                    ""
                }
            }
            liveFp = computed
        } else {
            liveFp = ""
        }
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
            onClick = {
                scope.launch {
                    onSetPassphrase(pass.trim())
                }
            },
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
