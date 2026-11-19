package com.yrrider.getlob.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val TAG = "Crypto"
    private const val PREFIX = "e1:"
    private const val IV_LEN = 12
    private const val KEYSTORE_ALIAS = "getlob_master_key_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private val MAGIC_GL2 = "GL2!".toByteArray(Charsets.US_ASCII)
    private const val CHUNK_SIZE = 64 * 1024

    private fun prefs(c: Context) = c.getSharedPreferences("crypto", Context.MODE_PRIVATE)

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            val kpg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            kpg.init(spec)
            return kpg.generateKey()
        }
        return (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun wrapKey(rawKey: ByteArray): String {
        val master = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, master)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(rawKey)
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun unwrapKey(wrappedB64: String): ByteArray? {
        return try {
            val master = getOrCreateMasterKey()
            val raw = Base64.decode(wrappedB64, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val iv = raw.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, iv))
            cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap key from keystore", e)
            null
        }
    }

    fun hasCustomPassphrase(c: Context): Boolean {
        val p = prefs(c)
        return p.contains("key_wrapped") || p.contains("key")
    }

    fun hasKey(c: Context) = rawKeyBytes(c) != null

    fun setPassphrase(c: Context, pairId: String, pass: String) {
        val spec = PBEKeySpec(pass.toCharArray(), "pairwidget:$pairId".toByteArray(), 120_000, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val wrapped = runCatching { wrapKey(k) }.getOrNull()
        if (wrapped != null) {
            prefs(c).edit().putString("key_wrapped", wrapped).remove("key").apply()
        } else {
            // Fallback if Keystore is unavailable
            prefs(c).edit().putString("key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
        }
    }

    suspend fun setPassphraseAsync(c: Context, pairId: String, pass: String) = withContext(Dispatchers.Default) {
        setPassphrase(c, pairId, pass)
    }

    fun clear(c: Context) {
        prefs(c).edit().remove("key").remove("key_wrapped").apply()
    }

    fun rawKeyBytes(c: Context): ByteArray? {
        val p = prefs(c)

        val wrapped = p.getString("key_wrapped", null)
        if (wrapped != null) {
            val unwrapped = unwrapKey(wrapped)
            if (unwrapped != null) return unwrapped
        }

        val legacyPlain = p.getString("key", null)
        if (legacyPlain != null) {
            val bytes = Base64.decode(legacyPlain, Base64.NO_WRAP)
            // Migrate automatically to Keystore-wrapped key
            runCatching {
                val newWrapped = wrapKey(bytes)
                p.edit().putString("key_wrapped", newWrapped).remove("key").apply()
            }
            return bytes
        }

        val pid = LocalStore.pairId(c)
        if (pid.isBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest("GetLob:e2ee:v1:$pid".toByteArray())
    }

    private fun key(c: Context): SecretKeySpec? = rawKeyBytes(c)?.let { SecretKeySpec(it, "AES") }

    fun fingerprint(c: Context): String {
        val k = rawKeyBytes(c) ?: return ""
        val h = MessageDigest.getInstance("SHA-256").digest("fp".toByteArray() + k)
        return h.take(3).joinToString("-") { "%02X".format(it) }
    }

    private fun cipher(mode: Int, k: SecretKeySpec, iv: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, k, GCMParameterSpec(128, iv)) }

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

    fun isEncryptedUrl(url: String) = url.substringBefore('?').endsWith(".enc")

    private fun deriveChunkIv(baseNonce: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseNonce.copyOf()
        val idxBytes = ByteBuffer.allocate(4).putInt(chunkIndex).array()
        for (i in 0..3) {
            iv[IV_LEN - 4 + i] = (iv[IV_LEN - 4 + i].toInt() xor idxBytes[i].toInt()).toByte()
        }
        return iv
    }

    fun encryptFile(c: Context, src: File): File {
        val k = key(c) ?: return src
        val out = File(c.cacheDir, "enc_${System.nanoTime()}")
        val baseNonce = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        FileOutputStream(out).use { fos ->
            fos.write(MAGIC_GL2)
            fos.write(baseNonce)

            FileInputStream(src).use { fis ->
                val buffer = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0

                var bytesRead = fis.read(buffer)
                while (bytesRead > 0) {
                    val nextPeek = fis.read()
                    val isFinal = if (nextPeek == -1) 1 else 0

                    val chunkIv = deriveChunkIv(baseNonce, chunkIndex)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, chunkIv))

                    val aad = ByteBuffer.allocate(5).putInt(chunkIndex).put(isFinal.toByte()).array()
                    cipher.updateAAD(aad)

                    val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)

                    val lenBytes = ByteBuffer.allocate(4).putInt(encryptedChunk.size).array()
                    fos.write(lenBytes)
                    fos.write(isFinal)
                    fos.write(encryptedChunk)

                    chunkIndex++

                    if (nextPeek != -1) {
                        buffer[0] = nextPeek.toByte()
                        val restRead = fis.read(buffer, 1, CHUNK_SIZE - 1)
                        bytesRead = if (restRead >= 0) restRead + 1 else 1
                    } else {
                        bytesRead = -1
                    }
                }
            }
        }
        return out
    }

    fun decryptOrCopy(c: Context, url: String, src: File, dst: File) {
        if (!isEncryptedUrl(url)) {
            src.copyTo(dst, overwrite = true)
            return
        }
        val k = key(c) ?: throw IllegalStateException("Encrypted media: pair connection or passphrase required")

        FileInputStream(src).use { fis ->
            val magic = ByteArray(MAGIC_GL2.size)
            val readMagic = fis.read(magic)

            if (readMagic == MAGIC_GL2.size && magic.contentEquals(MAGIC_GL2)) {
                // Versioned GL2! chunked format
                val baseNonce = ByteArray(IV_LEN)
                check(fis.read(baseNonce) == IV_LEN) { "Corrupt GL2 file header" }

                FileOutputStream(dst).use { fos ->
                    var chunkIndex = 0
                    val lenBuf = ByteArray(4)

                    while (true) {
                        val lenRead = fis.read(lenBuf)
                        if (lenRead < 4) break

                        val cipherLen = ByteBuffer.wrap(lenBuf).int
                        val isFinal = fis.read()
                        if (isFinal == -1) break

                        val cipherChunk = ByteArray(cipherLen)
                        var totalRead = 0
                        while (totalRead < cipherLen) {
                            val r = fis.read(cipherChunk, totalRead, cipherLen - totalRead)
                            if (r == -1) break
                            totalRead += r
                        }
                        check(totalRead == cipherLen) { "Truncated cipher chunk at $chunkIndex" }

                        val chunkIv = deriveChunkIv(baseNonce, chunkIndex)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, chunkIv))

                        val aad = ByteBuffer.allocate(5).putInt(chunkIndex).put(isFinal.toByte()).array()
                        cipher.updateAAD(aad)

                        val plain = cipher.doFinal(cipherChunk)
                        fos.write(plain)

                        chunkIndex++
                        if (isFinal == 1) break
                    }
                }
            } else {
                // Fall back to legacy AES-GCM stream decryption
                FileInputStream(src).use { legacyFis ->
                    val iv = ByteArray(IV_LEN)
                    check(legacyFis.read(iv) == IV_LEN) { "Corrupt legacy file" }
                    CipherInputStream(legacyFis, cipher(Cipher.DECRYPT_MODE, k, iv)).use { ci ->
                        FileOutputStream(dst).use { fos ->
                            ci.copyTo(fos)
                        }
                    }
                }
            }
        }
    }
}
