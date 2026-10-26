package com.yrrider.getlob.data

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PairInfo(
    val pairId: String = "",
    val code: String = "",
    val members: List<String> = emptyList(),
    val canvasClearedAt: Long = 0L,
    val createdAt: Long = 0L,
)

object Repo {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val fns get() = FirebaseFunctions.getInstance()
    private val storage get() = FirebaseStorage.getInstance()

    suspend fun uid(): String = auth.currentUser?.uid
        ?: requireNotNull(auth.signInAnonymously().await().user?.uid) { "Anonymous sign-in did not return a user" }

    suspend fun saveToken(token: String, ctx: Context? = null) {
        val u = uid()
        db.collection("users").document(u)
            .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge()).await()
        ctx?.let {
            val pid = with(LocalStore) { it.pairId }
            if (pid.isNotBlank()) {
                runCatching {
                    db.collection("pairs").document(pid)
                        .set(mapOf("tokens" to mapOf(u to token)), com.google.firebase.firestore.SetOptions.merge()).await()
                }
            }
        }
    }

    suspend fun createPair(ctx: Context): String {
        uid()
        val deviceUuid = LocalStore.appUuid(ctx)
        val res = fns.getHttpsCallable("createPair")
            .call(mapOf("deviceUuid" to deviceUuid)).await().data as Map<*, *>
        val pid = res["pairId"] as String
        val c = res["code"] as String
        with(LocalStore) {
            ctx.pairId = pid
            ctx.pairCode = c
        }
        return c
    }

    suspend fun joinPair(ctx: Context, code: String) {
        uid()
        val deviceUuid = LocalStore.appUuid(ctx)
        val res = fns.getHttpsCallable("joinPair")
            .call(mapOf("code" to code.trim().uppercase(), "deviceUuid" to deviceUuid)).await().data as Map<*, *>
        val pid = res["pairId"] as String
        with(LocalStore) {
            ctx.pairId = pid
            ctx.pairCode = ""
        }
    }

    suspend fun exitPairingMode(ctx: Context) {
        runCatching { fns.getHttpsCallable("exitPairingMode").call().await() }
        with(LocalStore) {
            ctx.pairId = ""
            ctx.pairCode = ""
        }
    }

    suspend fun pairId(ctx: Context): String {
        val cached = with(LocalStore) { ctx.pairId }
        if (cached.isNotEmpty()) return cached
        return runCatching {
            val id = db.collection("users").document(uid()).get().await().getString("pairId") ?: ""
            with(LocalStore) { ctx.pairId = id }
            id
        }.getOrDefault("")
    }

    suspend fun unpair(ctx: Context) {
        val pid = pairId(ctx)
        if (pid.isNotEmpty()) {

            runCatching {
                fns.getHttpsCallable("leavePair").call(mapOf("pairId" to pid)).await()
            }

            runCatching {
                val storageRef = storage.reference.child("pairs/$pid/media")
                storageRef.listAll().await().items.forEach { item ->
                    runCatching { item.delete().await() }
                }
            }
            runCatching {
                val messages = db.collection("pairs").document(pid).collection("messages").limit(500).get().await()
                if (!messages.isEmpty) {
                    val batch = db.batch()
                    messages.documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                db.collection("pairs").document(pid).delete().await()
            }
        }

        Crypto.clear(ctx)

        LocalStore.clearAllData(ctx)
        with(LocalStore) {
            ctx.pairId = ""
            ctx.pairCode = ""
        }
    }

    suspend fun clearHistory(ctx: Context) {
        val pid = pairId(ctx)
        require(pid.isNotEmpty()) { "Not paired yet" }

        val cloudSuccess = runCatching {
            fns.getHttpsCallable("clearPairHistory").call().await()
        }.isSuccess

        if (!cloudSuccess) {
            val messages = db.collection("pairs").document(pid).collection("messages").limit(500).get().await()
            if (!messages.isEmpty) {
                val batch = db.batch()
                messages.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            runCatching {
                storage.reference.child("pairs/$pid/media").listAll().await().items.forEach { item ->
                    item.delete().await()
                }
            }
            db.collection("pairs").document(pid)
                .set(mapOf("canvasClearedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
                .await()
        }
    }

    fun pairFlow(pairId: String): Flow<PairInfo?> = callbackFlow {
        if (pairId.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val reg = db.collection("pairs").document(pairId).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) {
                val members = snap.get("members") as? List<*> ?: emptyList<Any>()
                val code = snap.getString("code") ?: ""
                val createdAt = snap.getLong("createdAt") ?: snap.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                trySend(
                    PairInfo(
                        pairId = snap.id,
                        code = code,
                        members = members.filterIsInstance<String>(),
                        canvasClearedAt = snap.getLong("canvasClearedAt") ?: 0L,
                        createdAt = createdAt
                    )
                )
            } else {
                trySend(null)
            }
        }
        awaitClose { reg.remove() }
    }

    suspend fun partnerUid(ctx: Context): String {
        val me = uid()
        val members = db.collection("pairs").document(pairId(ctx)).get().await()
            .get("members") as? List<*> ?: emptyList<Any>()
        return members.filterIsInstance<String>().firstOrNull { it != me } ?: ""
    }

    fun settingsFlow(pairId: String): Flow<WidgetSettings?> = callbackFlow {
        if (pairId.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val reg = db.collection("pairs").document(pairId).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) {
                @Suppress("UNCHECKED_CAST")
                val map = snap.get("settings") as? Map<String, Any?>
                trySend(map?.let { WidgetSettings.fromMap(it) })
            } else {
                trySend(null)
            }
        }
        awaitClose { reg.remove() }
    }

    suspend fun saveSettings(ctx: Context, settings: WidgetSettings) {
        LocalStore.writeSettings(ctx, settings)
        val pid = pairId(ctx)
        if (pid.isNotEmpty()) {
            runCatching {
                db.collection("pairs").document(pid)
                    .set(mapOf("settings" to settings.toMap()), com.google.firebase.firestore.SetOptions.merge())
                    .await()
            }.onFailure { e ->
                android.util.Log.w("Repo", "Failed to sync settings to Firestore: ${e.message}")
            }
        }
    }

    suspend fun send(ctx: Context, m: Message) {
        val pid = pairId(ctx)
        require(pid.isNotEmpty()) { "Not paired yet" }
        val sealed = m.copy(
            text = Crypto.encryptText(ctx, m.text),
            replyText = if (m.replyText.isNotEmpty()) Crypto.encryptText(ctx, m.replyText) else ""
        )
        val payload = sealed.copy(from = uid()).toMap().toMutableMap()

        payload["createdAt"] = FieldValue.serverTimestamp()
        db.collection("pairs").document(pid).collection("messages").add(payload).await()
    }

    suspend fun upload(ctx: Context, uri: Uri, ext: String): String {
        val tmp = withContext(Dispatchers.IO) {
            File(ctx.cacheDir, "up_${System.nanoTime()}").also { f ->
                requireNotNull(ctx.contentResolver.openInputStream(uri)) { "Unable to open selected media" }
                    .use { input -> f.outputStream().use { output -> input.copyTo(output) } }
            }
        }
        return try { upload(ctx, tmp, ext) } finally { tmp.delete() }
    }

    suspend fun upload(ctx: Context, file: File, ext: String): String {
        require(file.exists() && file.length() > 0) { "Selected media file does not exist or is empty" }
        uid()
        val pid = pairId(ctx)
        require(pid.isNotEmpty()) { "You must be paired before sending media" }
        val encrypted = Crypto.hasKey(ctx)
        val toSend = withContext(Dispatchers.IO) { Crypto.encryptFile(ctx, file) }
        val name = if (encrypted) "$ext.enc" else ext
        val ref = storage.reference.child("pairs/$pid/media/${UUID.randomUUID()}.$name")
        try {
            ref.putFile(Uri.fromFile(toSend)).await()
            return ref.downloadUrl.await().toString()
        } finally {
            if (toSend != file) toSend.delete()
        }
    }

    fun messagesFlow(pairId: String): Flow<List<Message>> = callbackFlow {
        val reg: ListenerRegistration = db.collection("pairs").document(pairId).collection("messages")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, _ ->
                if (snap != null) trySend(snap.documents.map { Message.fromDoc(it) })
            }
        awaitClose { reg.remove() }
    }

    private fun gameDoc(pid: String) = db.collection("pairs").document(pid).collection("games").document("current")

    fun gameFlow(pairId: String): Flow<GameState?> = callbackFlow {
        val reg = gameDoc(pairId).addSnapshotListener { s, _ ->
            trySend(
                s?.takeIf { it.exists() }?.let {
                    GameState(
                        board = it.getString("board") ?: "---------",
                        turn = it.getString("turn") ?: "",
                        x = it.getString("x") ?: "",
                        o = it.getString("o") ?: "",
                        winner = it.getString("winner") ?: "",
                    )
                }
            )
        }
        awaitClose { reg.remove() }
    }

    suspend fun newGame(ctx: Context) {
        val me = uid()
        val other = partnerUid(ctx)
        gameDoc(pairId(ctx)).set(
            mapOf("board" to "---------", "turn" to me, "x" to me, "o" to other, "winner" to "")
        ).await()
    }

    suspend fun move(ctx: Context, index: Int) {
        val me = uid()
        val ref = gameDoc(pairId(ctx))
        db.runTransaction { tx ->
            val s = tx.get(ref)
            val board = (s.getString("board") ?: "---------").toCharArray()
            val turn = s.getString("turn") ?: ""
            val x = s.getString("x") ?: ""
            val o = s.getString("o") ?: ""
            val myMark = if (me == x) 'X' else if (me == o) 'O' else return@runTransaction
            if (turn != me || index !in 0..8 || board[index] != '-') return@runTransaction

            board[index] = myMark
            val win = checkWin(board)
            val full = !board.contains('-')
            val winner = if (win) myMark.toString() else if (full) "draw" else ""
            val nextTurn = if (winner.isNotEmpty()) "" else if (me == x) o else x
            tx.update(ref, mapOf("board" to String(board), "turn" to nextTurn, "winner" to winner))
        }.await()
    }

    private fun checkWin(b: CharArray): Boolean {
        val lines = listOf(
            0 to 1 to 2, 3 to 4 to 5, 6 to 7 to 8,
            0 to 3 to 6, 1 to 4 to 7, 2 to 5 to 8,
            0 to 4 to 8, 2 to 4 to 6
        )
        return lines.any { (p, c) -> val (a, bIdx) = p; b[a] != '-' && b[a] == b[bIdx] && b[a] == b[c] }
    }
}
