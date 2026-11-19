package com.yrrider.getlob.fcm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.firestore.FirebaseFirestore
import com.yrrider.getlob.data.Crypto
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Message
import com.yrrider.getlob.widget.MediaProcessor
import kotlinx.coroutines.tasks.await

class WidgetSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "getlob_widget_sync"

        const val KEY_MSG_ID = "key_msg_id"
        const val KEY_TYPE = "key_type"
        const val KEY_TEXT = "key_text"
        const val KEY_MEDIA_URL = "key_media_url"
        const val KEY_FETCH_FULL = "key_fetch_full"
        const val KEY_PAIR_ID = "key_pair_id"

        fun enqueueExpedited(ctx: Context, msg: Message? = null, fetchFull: Boolean = false) {
            val pairId = LocalStore.pairId(ctx)
            val data = workDataOf(
                KEY_MSG_ID to (msg?.id ?: ""),
                KEY_TYPE to (msg?.type ?: ""),
                KEY_TEXT to (msg?.text ?: ""),
                KEY_MEDIA_URL to (msg?.mediaUrl ?: ""),
                KEY_FETCH_FULL to if (fetchFull) "1" else "0",
                KEY_PAIR_ID to pairId
            )
            val req = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }

    override suspend fun doWork(): Result {
        val msgId = inputData.getString(KEY_MSG_ID) ?: ""
        val type = inputData.getString(KEY_TYPE) ?: ""
        var text = inputData.getString(KEY_TEXT) ?: ""
        val mediaUrl = inputData.getString(KEY_MEDIA_URL) ?: ""
        val fetchFull = inputData.getString(KEY_FETCH_FULL) == "1"
        val pairId = inputData.getString(KEY_PAIR_ID).takeUnless { it.isNullOrBlank() }
            ?: LocalStore.pairId(applicationContext)

        if (type.isEmpty()) {
            runCatching { MediaProcessor.refresh(applicationContext) }
            return Result.success()
        }

        if (fetchFull && msgId.isNotEmpty() && pairId.isNotEmpty()) {
            runCatching {
                val doc = FirebaseFirestore.getInstance()
                    .collection("pairs").document(pairId)
                    .collection("messages").document(msgId)
                    .get().await()
                if (doc.exists()) {
                    val rawEncrypted = doc.getString("text") ?: ""
                    text = Crypto.decryptText(applicationContext, rawEncrypted)
                }
            }
        }

        val msg = Message(
            id = msgId,
            type = type,
            text = text,
            mediaUrl = mediaUrl
        )

        runCatching {
            MediaProcessor.apply(applicationContext, msg)
        }.onFailure {
            return Result.retry()
        }

        return Result.success()
    }
}
