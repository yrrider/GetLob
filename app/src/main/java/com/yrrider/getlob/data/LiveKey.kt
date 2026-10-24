package com.yrrider.getlob.data

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Derives the LiveKit shared E2EE key from the same passphrase-derived key used for messages.
 * Both phones that typed the same passphrase get the same live key, with no extra setup.
 * The server never sees it. Returns null when encryption is off.
 */
object LiveKey {
    fun get(ctx: Context): String? {
        val k = ctx.getSharedPreferences("crypto", Context.MODE_PRIVATE).getString("key", null) ?: return null
        val h = MessageDigest.getInstance("SHA-256").digest(("live:$k").toByteArray())
        return Base64.encodeToString(h, Base64.NO_WRAP)
    }
}
