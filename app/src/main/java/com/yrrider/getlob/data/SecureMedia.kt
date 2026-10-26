package com.yrrider.getlob.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL


object SecureMedia {

    fun purgeOldCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        ctx.cacheDir?.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("snap_") || name.startsWith("fast_") || name.startsWith("dec_") || name.startsWith("dl_")) {
                if (file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }
    }
    fun cacheFile(ctx: Context, url: String): File =
        File(ctx.cacheDir, "dec_${url.substringBefore('?').hashCode()}")

    suspend fun fetch(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        purgeOldCache(ctx)
        val out = cacheFile(ctx, url)
        if (out.exists() && out.length() > 0) return@withContext out
        val tmp = File(ctx.cacheDir, "dl_${System.nanoTime()}")
        try {
            val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            Crypto.decryptOrCopy(ctx, url, tmp, out)
        } catch (e: Exception) {
            out.delete(); throw e
        } finally {
            tmp.delete()
        }
        out
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
