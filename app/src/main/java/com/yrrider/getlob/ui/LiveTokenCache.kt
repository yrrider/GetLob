package com.yrrider.getlob.ui

import android.content.Context
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.yrrider.getlob.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object LiveTokenCache {
    private const val TAG = "LiveTokenCache"

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUrl: String? = null
    @Volatile private var expiresAt: Long = 0L

    fun getValid(): Pair<String, String>? {
        val t = cachedToken
        val u = cachedUrl
        if (t != null && u != null && System.currentTimeMillis() < expiresAt) {
            return u to t
        }
        return null
    }

    fun set(url: String, token: String, expires: Long) {
        cachedUrl = url
        cachedToken = token
        expiresAt = expires
    }

    suspend fun fetchOrGet(ctx: Context): Pair<String, String>? {
        getValid()?.let { return it }
        val pid = LocalStore.pairId(ctx)
        val data = if (pid.isNotBlank()) mapOf("pairId" to pid) else emptyMap<String, String>()
        return try {
            val res = FirebaseFunctions.getInstance().getHttpsCallable("getLiveToken")
                .call(data).await().data as? Map<*, *> ?: return null
            val url = (res["url"] as? String)?.trim() ?: ""
            val token = (res["token"] as? String)?.trim() ?: ""
            val exp = (res["expiresAt"] as? Number)?.toLong() ?: (System.currentTimeMillis() + 25 * 60 * 1000L)
            if (url.isNotEmpty() && token.isNotEmpty()) {
                set(url, token, exp)
                url to token
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching live token", e)
            null
        }
    }

    fun prewarm(ctx: Context) {
        val pid = LocalStore.pairId(ctx)
        if (pid.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetchOrGet(ctx) }
        }
    }
}
