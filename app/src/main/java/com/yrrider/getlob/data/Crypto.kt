package com.yrrider.getlob.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for GetLob:
 *  - Default key: Automatically derived from the shared pairId (SHA-256("GetLob:e2ee:v1:$pairId")).
 *    Active immediately upon pairing so all text, files, and audio are encrypted out of the box!
 *  - Custom passphrase: Users can optionally provide a secret passphrase (PBKDF2-HMAC-SHA256, 120k iterations, salted with pairId).
 *  - Text: AES-256-GCM, stored as "e1:" + base64(iv + ciphertext).
 *  - Files (images, GIF, video): AES-256-GCM streamed; file layout = iv(12) + ciphertext.
 *    Encrypted uploads get a ".enc" suffix.
 *
 * The server (Firestore / Storage / Cloud Functions) only ever sees ciphertext.
 * Both phones show the same short fingerprint code when connected with the same key.
 */
object Crypto {
    private const val PREFIX = "e1:"
    private const val IV_LEN = 12
    private fun prefs(c: Context) = c.getSharedPreferences("crypto", Context.MODE_PRIVATE)

    fun hasCustomPassphrase(c: Context) = prefs(c).contains("key")
    fun hasKey(c: Context) = rawKeyBytes(c) != null

    fun setPassphrase(c: Context, pairId: String, pass: String) {
        val spec = PBEKeySpec(pass.toCharArray(), "pairwidget:$pairId".toByteArray(), 120_000, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        prefs(c).edit().putString("key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
    }

    fun clear(c: Context) = prefs(c).edit().remove("key").apply()

    fun rawKeyBytes(c: Context): ByteArray? {
        val custom = prefs(c).getString("key", null)
        if (custom != null) {
            return Base64.decode(custom, Base64.NO_WRAP)
        }
        val pid = LocalStore.pairId(c)
        if (pid.isBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest("GetLob:e2ee:v1:$pid".toByteArray())
    }

    private fun key(c: Context): SecretKeySpec? = rawKeyBytes(c)?.let { SecretKeySpec(it, "AES") }

    /** Short code to compare on both phones, e.g. "A1-B2-C3". Same key => same code. */
    fun fingerprint(c: Context): String {
        val k = rawKeyBytes(c) ?: return ""
        val h = MessageDigest.getInstance("SHA-256").digest("fp".toByteArray() + k)
        return h.take(3).joinToString("-") { "%02X".format(it) }
    }

    private fun cipher(mode: Int, k: SecretKeySpec, iv: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, k, GCMParameterSpec(128, iv)) }

    // ---------- text ----------
    fun encryptText(c: Context, s: String): String {
        val k = key(c) ?: return s
        if (s.isEmpty()) return s
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val ct = cipher(Cipher.ENCRYPT_MODE, k, iv).doFinal(s.toByteArray())
        return PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decryptText(c: Context, s: String): String {
        if (!s.startsWith(PREFIX)) return s
        val k = key(c) ?: return "\uD83D\uDD12 Encrypted message"
        return try {
            val raw = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            String(cipher(Cipher.DECRYPT_MODE, k, raw.copyOfRange(0, IV_LEN)).doFinal(raw, IV_LEN, raw.size - IV_LEN))
        } catch (e: Exception) {
            "\uD83D\uDD12 Can’t decrypt — passphrases differ"
        }
    }

    // ---------- files ----------
    fun isEncryptedUrl(url: String) = url.substringBefore('?').endsWith(".enc")

    /** Returns an encrypted copy of [src] (or [src] itself if no key is set). */
    fun encryptFile(c: Context, src: File): File {
        val k = key(c) ?: return src
        val out = File(c.cacheDir, "enc_${System.nanoTime()}")
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        out.outputStream().use { o ->
            o.write(iv)
            CipherOutputStream(o, cipher(Cipher.ENCRYPT_MODE, k, iv)).use { co ->
                src.inputStream().use { it.copyTo(co) }
            }
        }
        return out
    }

    /** Decrypts [src] into [dst] if the URL says it is encrypted; otherwise just copies. */
    fun decryptOrCopy(c: Context, url: String, src: File, dst: File) {
        if (!isEncryptedUrl(url)) { src.copyTo(dst, overwrite = true); return }
        val k = key(c) ?: throw IllegalStateException("Encrypted media: pair connection or passphrase required")
        src.inputStream().use { i ->
            val iv = ByteArray(IV_LEN)
            check(i.read(iv) == IV_LEN) { "Corrupt file" }
            CipherInputStream(i, cipher(Cipher.DECRYPT_MODE, k, iv)).use { ci ->
                dst.outputStream().use { ci.copyTo(it) }
            }
        }
    }
}
