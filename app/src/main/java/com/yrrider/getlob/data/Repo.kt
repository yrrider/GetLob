package com.yrrider.getlob.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.yrrider.getlob.data.db.AppDatabase
import com.yrrider.getlob.data.db.MessageEntity
import com.yrrider.getlob.data.db.OutboxEntity
import com.yrrider.getlob.data.db.SyncStatus
import com.yrrider.getlob.fcm.WidgetSyncWorker
import com.yrrider.getlob.widget.MediaProcessor
import com.yrrider.getlob.worker.OutboxSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val settings: WidgetSettings? = null,
    val deliveredCursors: Map<String, Long> = emptyMap(),
    val readCursors: Map<String, Long> = emptyMap(),
)

object Repo {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val fns get() = FirebaseFunctions.getInstance()
    private val storage get() = FirebaseStorage.getInstance()

    @Volatile private var lastReportedDelivered = 0L
    @Volatile private var lastReportedRead = 0L

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
        }

        runCatching {
            val appDb = AppDatabase.getInstance(ctx)
            appDb.messageDao().clearAll()
            appDb.outboxDao().clearAll()
        }

        Crypto.clear(ctx)
        LocalStore.clearAllData(ctx)
        MediaProcessor.clearCanvas(ctx)

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

        runCatching {
            val appDb = AppDatabase.getInstance(ctx)
            appDb.messageDao().clearPair(pid)
            appDb.outboxDao().clearPair(pid)
        }
    }

    fun pairSnapshotFlow(pairId: String): Flow<PairInfo?> = pairFlow(pairId)

    fun pairFlow(pairId: String): Flow<PairInfo?> = callbackFlow {
        if (pairId.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val reg = db.collection("pairs").document(pairId).addSnapshotListener { snap, error ->
            if (error != null) Log.w("Repo", "Pair listener failed for $pairId", error)
            if (snap != null && snap.exists()) {
                val members = snap.get("members") as? List<*> ?: emptyList<Any>()
                val code = snap.getString("code") ?: ""
                val createdAt = snap.getLong("createdAt") ?: snap.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                @Suppress("UNCHECKED_CAST")
                val settingsMap = snap.get("settings") as? Map<String, Any?>
                val settings = settingsMap?.let { WidgetSettings.fromMap(it) }

                val delivered = mutableMapOf<String, Long>()
                val read = mutableMapOf<String, Long>()
                snap.data?.forEach { (k, v) ->
                    if (k.startsWith("deliveredTo_") && v is Number) {
                        delivered[k.removePrefix("deliveredTo_")] = v.toLong()
                    } else if (k.startsWith("readTo_") && v is Number) {
                        read[k.removePrefix("readTo_")] = v.toLong()
                    }
                }

                trySend(
                    PairInfo(
                        pairId = snap.id,
                        code = code,
                        members = members.filterIsInstance<String>(),
                        canvasClearedAt = snap.getLong("canvasClearedAt") ?: 0L,
                        createdAt = createdAt,
                        settings = settings,
                        deliveredCursors = delivered,
                        readCursors = read
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

    fun settingsFlow(pairId: String): Flow<WidgetSettings?> = pairFlow(pairId).map { it?.settings }

    suspend fun saveSettings(ctx: Context, settings: WidgetSettings) {
        LocalStore.writeSettings(ctx, settings)
        val pid = pairId(ctx)
        if (pid.isNotEmpty()) {
            runCatching {
                db.collection("pairs").document(pid)
                    .set(mapOf("settings" to settings.toMap()), com.google.firebase.firestore.SetOptions.merge())
                    .await()
            }.onFailure { e ->
                Log.w("Repo", "Failed to sync settings to Firestore: ${e.message}")
            }
        }
    }

    suspend fun sendIdempotent(ctx: Context, m: Message) {
        val pid = pairId(ctx)
        require(pid.isNotEmpty()) { "Not paired yet" }
        val sealed = m.copy(
            text = if (m.text.isNotEmpty()) Crypto.encryptText(ctx, m.text) else "",
            replyText = if (m.replyText.isNotEmpty()) Crypto.encryptText(ctx, m.replyText) else ""
        )
        val payload = sealed.copy(from = uid()).toMap().toMutableMap()
        payload["createdAt"] = FieldValue.serverTimestamp()
        payload["clientCreatedAt"] = m.clientCreatedAt
        db.collection("pairs").document(pid).collection("messages").document(m.id).set(payload).await()
    }

    suspend fun send(ctx: Context, m: Message) {
        sendIdempotent(ctx, m)
    }

    suspend fun enqueueMessage(
        ctx: Context,
        m: Message,
        mediaFile: File? = null,
        mediaExt: String = ""
    ): String {
        val pid = pairId(ctx)
        require(pid.isNotEmpty()) { "Not paired yet" }
        val myUid = uid()

        val msgId = if (m.id.isNotEmpty()) m.id else UuidV7.generate()
        val now = System.currentTimeMillis()
        val clientCreatedAt = if (m.clientCreatedAt > 0L) m.clientCreatedAt else now

        val dbInstance = AppDatabase.getInstance(ctx)
        val messageDao = dbInstance.messageDao()
        val outboxDao = dbInstance.outboxDao()

        val localMediaPath = mediaFile?.absolutePath ?: m.localMediaPath

        val msgEntity = MessageEntity(
            id = msgId,
            pairId = pid,
            type = m.type,
            plainText = m.text,
            mediaUrl = m.mediaUrl,
            localMediaPath = localMediaPath,
            thumbB64 = m.thumbB64,
            fromUid = myUid,
            clientCreatedAt = clientCreatedAt,
            serverCreatedAt = 0L,
            syncStatus = SyncStatus.SENDING,
            replyToId = m.replyToId,
            replyText = m.replyText,
            replySender = m.replySender
        )
        messageDao.insertOrReplace(msgEntity)

        val outboxEntity = OutboxEntity(
            msgId = msgId,
            pairId = pid,
            type = m.type,
            plainText = m.text,
            localMediaPath = localMediaPath,
            mediaExt = mediaExt,
            thumbB64 = m.thumbB64,
            replyToId = m.replyToId,
            replyText = m.replyText,
            replySender = m.replySender,
            clientCreatedAt = clientCreatedAt,
            attempts = 0,
            createdAt = now
        )
        outboxDao.insert(outboxEntity)

        OutboxSyncWorker.enqueue(ctx)

        if (m.type in listOf(MsgType.IMAGE, MsgType.GIF, MsgType.VIDEO, MsgType.EMOJI, MsgType.TEXT)) {
            WidgetSyncWorker.enqueueExpedited(ctx, m.copy(id = msgId))
        }

        return msgId
    }

    suspend fun updateDeliveredCursor(ctx: Context, pairId: String, timestampMs: Long) {
        if (pairId.isEmpty() || timestampMs <= lastReportedDelivered) return
        lastReportedDelivered = timestampMs
        val me = uid()
        runCatching {
            db.collection("pairs").document(pairId)
                .set(mapOf("deliveredTo_$me" to timestampMs), com.google.firebase.firestore.SetOptions.merge())
                .await()
        }
    }

    suspend fun updateReadCursor(ctx: Context, pairId: String, timestampMs: Long) {
        if (pairId.isEmpty() || timestampMs <= lastReportedRead) return
        lastReportedRead = timestampMs
        val me = uid()
        runCatching {
            db.collection("pairs").document(pairId)
                .set(mapOf("readTo_$me" to timestampMs), com.google.firebase.firestore.SetOptions.merge())
                .await()
        }
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

    fun syncMessagesToRoom(ctx: Context, pairId: String): Flow<Unit> = callbackFlow {
        if (pairId.isEmpty()) {
            close()
            return@callbackFlow
        }
        val dbInstance = AppDatabase.getInstance(ctx)
        val messageDao = dbInstance.messageDao()
        val myUid = uid()

        val reg = db.collection("pairs").document(pairId).collection("messages")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w("Repo", "syncMessagesToRoom listener failed for $pairId", error)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener

                CoroutineScope(Dispatchers.IO).launch {
                    var maxPartnerTs = 0L
                    var newestPartnerMsg: Message? = null

                    for (doc in snap.documents) {
                        val m = Message.fromDoc(doc)
                        val existing = messageDao.getMessageById(m.id)

                        val plainText = if (m.text.isNotEmpty()) {
                            if (existing != null && existing.plainText.isNotEmpty() && !existing.plainText.startsWith("e1:")) {
                                existing.plainText
                            } else {
                                Crypto.decryptText(ctx, m.text)
                            }
                        } else ""

                        val plainReply = if (m.replyText.isNotEmpty()) {
                            if (existing != null && existing.replyText.isNotEmpty() && !existing.replyText.startsWith("e1:")) {
                                existing.replyText
                            } else {
                                Crypto.decryptText(ctx, m.replyText)
                            }
                        } else ""

                        val entity = MessageEntity(
                            id = m.id,
                            pairId = pairId,
                            type = m.type,
                            plainText = plainText,
                            mediaUrl = m.mediaUrl,
                            localMediaPath = existing?.localMediaPath ?: "",
                            thumbB64 = m.thumbB64.ifEmpty { existing?.thumbB64 ?: "" },
                            fromUid = m.from,
                            clientCreatedAt = m.clientCreatedAt,
                            serverCreatedAt = m.createdAt,
                            syncStatus = existing?.syncStatus ?: SyncStatus.SENT,
                            replyToId = m.replyToId,
                            replyText = plainReply,
                            replySender = m.replySender
                        )
                        messageDao.upsertFromPushOrRemote(entity)

                        if (m.from != myUid) {
                            if (m.clientCreatedAt > maxPartnerTs) {
                                maxPartnerTs = m.clientCreatedAt
                            }
                            if (newestPartnerMsg == null) {
                                newestPartnerMsg = m
                            }
                        }
                    }

                    if (maxPartnerTs > 0L) {
                        updateDeliveredCursor(ctx, pairId, maxPartnerTs)
                    }

                    newestPartnerMsg?.let { msg ->
                        if (msg.id.isNotEmpty() && msg.id != LocalStore.lastWidgetMsgId(ctx)) {
                            WidgetSyncWorker.enqueueExpedited(ctx, msg)
                        }
                    }
                    trySend(Unit)
                }
            }
        awaitClose { reg.remove() }
    }

    fun messagesFlow(pairId: String): Flow<List<Message>> = callbackFlow {
        val reg: ListenerRegistration = db.collection("pairs").document(pairId).collection("messages")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, error ->
                if (error != null) Log.w("Repo", "Messages listener failed for $pairId", error)
                if (snap != null) trySend(snap.documents.map { Message.fromDoc(it) })
            }
        awaitClose { reg.remove() }
    }

    private fun gameDoc(pid: String) = db.collection("pairs").document(pid).collection("games").document("current")

    fun gameFlow(pairId: String): Flow<GameState?> = callbackFlow {
        val reg = gameDoc(pairId).addSnapshotListener { s, error ->
            if (error != null) Log.w("Repo", "Game listener failed for $pairId", error)
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
