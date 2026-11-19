package com.yrrider.getlob.ui.pairing

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yrrider.getlob.data.PairInfo
import com.yrrider.getlob.ui.NeonHeartLoader

@Composable
fun PairingScreen(
    pairInfo: PairInfo?,
    inviteCode: String,
    joinCode: String,
    isCreatingCode: Boolean,
    isJoiningCode: Boolean,
    error: String,
    deviceUuid: String,
    onEnterPairingMode: () -> Unit,
    onExitPairingMode: () -> Unit,
    onJoinCodeChange: (String) -> Unit,
    onJoinPartner: () -> Unit
) {
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
            Text(
                "GetLob",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
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
                            Text(
                                "PAIRING MODE ACTIVE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
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
                            Text(
                                "YOUR PAIR CODE",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.SemiBold
                            )
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
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF25D366)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Waiting for partner to enter code…",
                                    fontSize = 12.sp,
                                    color = Color(0xFF25D366)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onExitPairingMode,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Exit Pairing Mode / Cancel")
                    }
                } else {
                    Button(
                        onClick = onEnterPairingMode,
                        enabled = !isCreatingCode,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A884),
                            contentColor = Color.White
                        )
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

                    Text(
                        "Or enter your partner's code:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = onJoinCodeChange,
                        singleLine = true,
                        placeholder = { Text("6-CHAR CODE") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onJoinPartner,
                        enabled = joinCode.length == 6 && !isJoiningCode,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A884),
                            contentColor = Color.White
                        )
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
                    "Device UUID: $deviceUuid",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
