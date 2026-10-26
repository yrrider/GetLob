package com.yrrider.getlob.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yrrider.getlob.data.GameState
import com.yrrider.getlob.data.Repo
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch



@Composable
fun TicTacToePanel(onBack: () -> Unit, onMoved: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf("") }
    var pairId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        me = Repo.uid()
        pairId = Repo.pairId(ctx)
    }
    val game by remember(pairId) {
        if (pairId.isNotEmpty()) Repo.gameFlow(pairId) else emptyFlow()
    }.collectAsState(initial = null)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎮 Tic-tac-toe", fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        val g = game
        if (g == null) {
            Button({ scope.launch { runCatching { Repo.newGame(ctx) } } }) { Text("Start new game") }
        } else {
            val mySym = if (g.x == me) "X" else if (g.o == me) "O" else ""
            val turnText = when {
                g.winner.isNotEmpty() -> if (g.winner == "draw") "Draw!" else if (g.winner == mySym) "You won! 🎉" else "They won!"
                g.turn == me -> "Your turn ($mySym)"
                else -> "Partner's turn"
            }
            Text(turnText, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            for (r in 0..2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0..2) {
                        val i = r * 3 + c
                        val ch = g.board[i]
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A50))
                                .clickable(enabled = ch == '-' && g.turn == me && g.winner.isEmpty()) {
                                    scope.launch {
                                        runCatching { Repo.move(ctx, i) }
                                        onMoved("🎮 Made a move!")
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (ch) { 'X' -> "❌"; 'O' -> "⭕"; else -> "" },
                                fontSize = 28.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (g.winner.isNotEmpty()) Button({ scope.launch { runCatching { Repo.newGame(ctx) } } }) { Text("Play again") }
        }
        TextButton(onBack) { Text("Back") }
    }
}
