package com.yrrider.getlob.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.yrrider.getlob.GetLobApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object SecureMedia {

    private val inFlightDownloads = ConcurrentHashMap<String, Deferred<File>>()

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cacheFile(ctx: Context, url: String): File {
        val hash = sha256Hex(url.substringBefore('?'))
        return File(ctx.cacheDir, "dec_$hash")
    }

    fun purgeOldCacheIfNeeded(ctx: Context) {
        val now = System.currentTimeMillis()
        val lastPurge = LocalStore.lastCachePurgeAt(ctx)
        if (now - lastPurge < 6 * 3600 * 1000L) return
        LocalStore.setLastCachePurgeAt(ctx, now)

        val cutoff = now - 24 * 3600 * 1000L
        ctx.cacheDir?.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("snap_") || name.startsWith("fast_") || name.startsWith("dec_") || name.startsWith("dl_") || name.startsWith("enc_")) {
                if (file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    suspend fun fetch(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        val finalFile = cacheFile(ctx, url)
        if (finalFile.exists() && finalFile.length() > 0) {
            return@withContext finalFile
        }

        // Deduplicate in-flight downloads by URL
        val key = url.substringBefore('?')
        while (true) {
            val existing = inFlightDownloads[key]
            if (existing != null) {
                return@withContext existing.await()
            }

            val deferred = CompletableDeferred<File>()
            val wasPut = inFlightDownloads.putIfAbsent(key, deferred) == null
            if (wasPut) {
                try {
                    val downloaded = executeFetch(ctx, url, finalFile)
                    deferred.complete(downloaded)
                    return@withContext downloaded
                } catch (e: Throwable) {
                    deferred.completeExceptionally(e)
                    throw e
                } finally {
                    inFlightDownloads.remove(key)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        finalFile
    }

    private suspend fun executeFetch(ctx: Context, url: String, finalFile: File): File = withContext(Dispatchers.IO) {
        purgeOldCacheIfNeeded(ctx)
        if (finalFile.exists() && finalFile.length() > 0) return@withContext finalFile

        val tmpDl = File(ctx.cacheDir, "dl_${System.nanoTime()}.tmp")
        val tmpDec = File(ctx.cacheDir, "dec_${System.nanoTime()}.tmp")

        try {
            val request = Request.Builder().url(url).build()
            val client = GetLobApp.okHttpClient
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download media: HTTP ${response.code}")
            }

            response.body?.use { body ->
                tmpDl.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            } ?: throw IllegalStateException("Empty response body from media URL")

            // Decrypt directly into tmpDec
            Crypto.decryptOrCopy(ctx, url, tmpDl, tmpDec)

            // Atomically rename tmpDec to finalFile
            if (tmpDec.exists() && tmpDec.length() > 0) {
                if (finalFile.exists()) finalFile.delete()
                val renamed = tmpDec.renameTo(finalFile)
                if (!renamed) {
                    tmpDec.copyTo(finalFile, overwrite = true)
                    tmpDec.delete()
                }
            } else {
                throw IllegalStateException("Decrypted file is empty")
            }
        } finally {
            if (tmpDl.exists()) tmpDl.delete()
            if (tmpDec.exists()) tmpDec.delete()
        }

        finalFile
    }

    suspend fun fetchTo(ctx: Context, url: String, name: String): File {
        val dec = fetch(ctx, url)
        return File(ctx.cacheDir, name).also { dec.copyTo(it, overwrite = true) }
    }
}

@Composable
fun rememberMedia(url: String): Any? {
    val ctx = LocalContext.current
    val v by produceState<Any?>(null, url) {
        value = when {
            url.isEmpty() -> null
            !Crypto.isEncryptedUrl(url) -> url
            else -> runCatching { SecureMedia.fetch(ctx, url) }.getOrNull()
        }
    }
    return v
}

fun mediaUri(model: Any): Uri = if (model is File) Uri.fromFile(model) else Uri.parse(model as String)
