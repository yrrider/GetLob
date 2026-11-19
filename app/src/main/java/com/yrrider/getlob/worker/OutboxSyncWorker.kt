package com.yrrider.getlob.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.data.Repo
import com.yrrider.getlob.data.SecureMedia
import com.yrrider.getlob.data.db.AppDatabase
import com.yrrider.getlob.data.db.SyncStatus
import java.io.File

class OutboxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val outboxDao = db.outboxDao()
        val messageDao = db.messageDao()

        var hasFailures = false

        while (true) {
            val pending = outboxDao.getAllPending()
            if (pending.isEmpty()) break

            for (item in pending) {
                try {
                    var mediaUrl = ""
                    if (item.localMediaPath.isNotEmpty()) {
                        val localFile = File(item.localMediaPath)
                        if (localFile.exists() && localFile.length() > 0) {
                            mediaUrl = Repo.upload(applicationContext, localFile, item.mediaExt)
                            runCatching {
                                val cachedTarget = SecureMedia.cacheFile(applicationContext, mediaUrl)
                                localFile.copyTo(cachedTarget, overwrite = true)
                            }
                        }
                    }

                    val msg = Message(
                        id = item.msgId,
                        type = item.type,
                        text = item.plainText,
                        mediaUrl = mediaUrl,
                        from = Repo.uid(),
                        createdAt = item.clientCreatedAt,
                        replyToId = item.replyToId,
                        replyText = item.replyText,
                        replySender = item.replySender,
                        clientCreatedAt = item.clientCreatedAt,
                        thumbB64 = item.thumbB64,
                        syncStatus = SyncStatus.SENT,
                        localMediaPath = item.localMediaPath
                    )

                    Repo.sendIdempotent(applicationContext, msg)
                    messageDao.updateSentStatus(item.msgId, SyncStatus.SENT, mediaUrl)
                    outboxDao.delete(item.msgId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending outbox msg ${item.msgId}", e)
                    val nextAttempts = item.attempts + 1
                    if (nextAttempts >= MAX_ATTEMPTS) {
                        Log.e(TAG, "Outbox msg ${item.msgId} reached max attempts ($MAX_ATTEMPTS), marking FAILED")
                        messageDao.updateSentStatus(item.msgId, SyncStatus.FAILED, "")
                        outboxDao.delete(item.msgId)
                    } else {
                        outboxDao.updateAttempts(item.msgId, nextAttempts)
                        hasFailures = true
                    }
                }
            }
        }

        return if (hasFailures) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "OutboxSyncWorker"
        private const val MAX_ATTEMPTS = 5
        private const val UNIQUE_WORK_NAME = "outbox_sync"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
