package com.yrrider.getlob.fcm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yrrider.getlob.data.LocalStore
import com.yrrider.getlob.data.Repo

class TokenSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "getlob_token_sync"

        fun enqueue(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = OneTimeWorkRequestBuilder<TokenSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }

    override suspend fun doWork(): Result {
        val token = LocalStore.pendingFcmToken(applicationContext)
        if (token.isBlank()) return Result.success()

        return try {
            Repo.saveToken(token, applicationContext)
            LocalStore.setTokenSynced(applicationContext, true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
