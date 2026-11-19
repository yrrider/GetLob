package com.yrrider.getlob.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yrrider.getlob.data.*
import com.yrrider.getlob.data.db.AppDatabase
import com.yrrider.getlob.data.db.OutboxEntity
import com.yrrider.getlob.data.db.SyncStatus
import com.yrrider.getlob.worker.OutboxSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val messageDao = db.messageDao()
    private val outboxDao = db.outboxDao()

    val pairId = MutableStateFlow(LocalStore.pairId(application))
    val myUid = MutableStateFlow(LocalStore.myUid(application))
    val partnerUid = MutableStateFlow("")

    val widgetSettings = MutableStateFlow(LocalStore.readSettings(application))
    val chatSettings = MutableStateFlow(LocalStore.readChatSettings(application))

    val replyingTo = MutableStateFlow<Message?>(null)

    private val _pairInfo = MutableStateFlow<PairInfo?>(null)
    val pairInfo: StateFlow<PairInfo?> = _pairInfo.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = pairId
        .flatMapLatest { pid ->
            if (pid.isEmpty()) flowOf(emptyList())
            else messageDao.observeMessages(pid)
        }
        .combine(_pairInfo) { entities, info ->
            val pUid = partnerUid.value
            val readCursor = info?.readCursors?.get(pUid) ?: 0L
            val delCursor = info?.deliveredCursors?.get(pUid) ?: 0L
            val me = myUid.value

            entities.map { entity ->
                val base = entity.toMessage()
                if (entity.fromUid == me) {
                    val computedStatus = when {
                        entity.syncStatus == SyncStatus.FAILED -> SyncStatus.FAILED
                        entity.syncStatus == SyncStatus.SENDING -> SyncStatus.SENDING
                        readCursor > 0L && entity.clientCreatedAt <= readCursor -> SyncStatus.READ
                        delCursor > 0L && entity.clientCreatedAt <= delCursor -> SyncStatus.DELIVERED
                        else -> SyncStatus.SENT
                    }
                    base.copy(syncStatus = computedStatus)
                } else {
                    base.copy(syncStatus = SyncStatus.READ)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val u = runCatching { Repo.uid() }.getOrDefault("")
            myUid.value = u
            LocalStore.setMyUid(getApplication(), u)

            val p = LocalStore.pairId(getApplication())
            pairId.value = p
            if (p.isNotEmpty()) {
                val partner = runCatching { Repo.partnerUid(getApplication()) }.getOrDefault("")
                partnerUid.value = partner
            }
        }

        viewModelScope.launch {
            pairId.collectLatest { pid ->
                if (pid.isNotEmpty()) {
                    launch(Dispatchers.IO) {
                        val partner = runCatching { Repo.partnerUid(getApplication()) }.getOrDefault("")
                        partnerUid.value = partner
                    }
                    launch(Dispatchers.IO) {
                        Repo.pairSnapshotFlow(pid).collect { info ->
                            _pairInfo.value = info
                            if (info?.settings != null) {
                                widgetSettings.value = info.settings
                                LocalStore.writeSettings(getApplication(), info.settings)
                            }
                        }
                    }
                    launch(Dispatchers.IO) {
                        Repo.syncMessagesToRoom(getApplication(), pid).collect {
                            // Room updates trigger observeMessages automatically
                        }
                    }
                }
            }
        }
    }

    fun setPairId(pid: String) {
        pairId.value = pid
        LocalStore.savePairId(getApplication(), pid)
    }

    fun updateWidgetSettings(settings: WidgetSettings) {
        widgetSettings.value = settings
        viewModelScope.launch(Dispatchers.IO) {
            Repo.saveSettings(getApplication(), settings)
        }
    }

    fun updateChatSettings(settings: ChatSettings) {
        chatSettings.value = settings
        LocalStore.writeChatSettings(getApplication(), settings)
    }

    fun setReplyingTo(m: Message?) {
        replyingTo.value = m
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val currentReply = replyingTo.value
        replyingTo.value = null

        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.TEXT,
            text = trimmed,
            replyToId = currentReply?.id ?: "",
            replyText = currentReply?.text ?: "",
            replySender = if (currentReply != null) {
                if (currentReply.from == myUid.value) "You" else widgetSettings.value.partnerName
            } else "",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg)
        }
    }

    fun sendEmoji(emoji: String) {
        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.EMOJI,
            text = emoji,
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg)
        }
    }

    fun sendSnap(preparedFile: File, caption: String, thumbB64: String) {
        val currentReply = replyingTo.value
        replyingTo.value = null

        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.IMAGE,
            text = caption.trim(),
            thumbB64 = thumbB64,
            localMediaPath = preparedFile.absolutePath,
            replyToId = currentReply?.id ?: "",
            replyText = currentReply?.text ?: "",
            replySender = if (currentReply != null) {
                if (currentReply.from == myUid.value) "You" else widgetSettings.value.partnerName
            } else "",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg, mediaFile = preparedFile, mediaExt = "jpg")
        }
    }

    fun sendGif(gifFile: File, caption: String) {
        val currentReply = replyingTo.value
        replyingTo.value = null

        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.GIF,
            text = caption.trim(),
            localMediaPath = gifFile.absolutePath,
            replyToId = currentReply?.id ?: "",
            replyText = currentReply?.text ?: "",
            replySender = if (currentReply != null) {
                if (currentReply.from == myUid.value) "You" else widgetSettings.value.partnerName
            } else "",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg, mediaFile = gifFile, mediaExt = "gif")
        }
    }

    fun sendVideo(videoFile: File, caption: String) {
        val currentReply = replyingTo.value
        replyingTo.value = null

        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.VIDEO,
            text = caption.trim(),
            localMediaPath = videoFile.absolutePath,
            replyToId = currentReply?.id ?: "",
            replyText = currentReply?.text ?: "",
            replySender = if (currentReply != null) {
                if (currentReply.from == myUid.value) "You" else widgetSettings.value.partnerName
            } else "",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg, mediaFile = videoFile, mediaExt = "mp4")
        }
    }

    fun sendVibe() {
        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.VIBE,
            text = "💓",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg)
        }
    }

    fun sendSos() {
        val msg = Message(
            id = UuidV7.generate(),
            type = MsgType.SOS,
            text = "🚨 SOS ALERT",
            clientCreatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            Repo.enqueueMessage(getApplication(), msg)
        }
    }

    fun retryMessage(msg: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            val pid = pairId.value
            if (pid.isEmpty() || msg.id.isEmpty()) return@launch

            messageDao.updateSentStatus(msg.id, SyncStatus.SENDING, msg.mediaUrl)
            val ext = if (msg.type == MsgType.IMAGE) "jpg" else if (msg.type == MsgType.GIF) "gif" else if (msg.type == MsgType.VIDEO) "mp4" else ""
            outboxDao.insert(
                OutboxEntity(
                    msgId = msg.id,
                    pairId = pid,
                    type = msg.type,
                    plainText = msg.text,
                    localMediaPath = msg.localMediaPath,
                    mediaExt = ext,
                    thumbB64 = msg.thumbB64,
                    replyToId = msg.replyToId,
                    replyText = msg.replyText,
                    replySender = msg.replySender,
                    clientCreatedAt = msg.clientCreatedAt,
                    attempts = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            OutboxSyncWorker.enqueue(getApplication())
        }
    }

    fun markChatRead() {
        val pid = pairId.value
        val me = myUid.value
        if (pid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val list = messages.value
            val maxPartnerTs = list.filter { it.from != me && it.from.isNotEmpty() }
                .maxOfOrNull { it.clientCreatedAt } ?: 0L

            if (maxPartnerTs > 0L) {
                Repo.updateReadCursor(getApplication(), pid, maxPartnerTs)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Repo.clearHistory(getApplication()) }
        }
    }

    fun unpair(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Repo.unpair(getApplication()) }
            pairId.value = ""
            partnerUid.value = ""
            _pairInfo.value = null
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
