package com.yrrider.getlob.data

import android.content.Context
import java.io.File

data class WidgetState(
    val kind: String = "",
    val text: String = "",
    val posterPath: String = "",
    val frameCount: Int = 0,
    val mediaUrl: String = "",
    val fitCrop: Boolean = true,
    val updatedAt: Long = 0L,
)

data class ChatSettings(
    val wallpaperColor: Long = 0xFF0B141BL,
    val myBubbleColor: Long = 0xFF005C4BL,
    val partnerBubbleColor: Long = 0xFF202C33L,
    val messageFontSize: Int = 15,
    val bubbleCornerDp: Int = 14,
    val fontStyle: String = "Modern",
    val swipeToReplyEnabled: Boolean = true
)

data class WidgetSettings(
    val partnerName: String = "Partner",
    val fontStyle: String = "Calligraphy",
    val fontColor: Long = 0xFFFFFFFFL,
    val bgTransparency: Float = 0.35f,
    val bgColor: Long = 0L,
    val borderColor: Long = 0x80FFFFFFL,
    val borderTransparency: Float = 1f,
    val borderWidthDp: Float = 1.5f,
    val glassEffect: Boolean = true,
    val themeIndex: Int = 0,
    val cornerDp: Int = 26,
    val fitCrop: Boolean = true,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "partnerName" to partnerName,
        "fontStyle" to fontStyle,
        "fontColor" to fontColor,
        "bgTransparency" to bgTransparency.toDouble(),
        "bgColor" to bgColor,
        "borderColor" to borderColor,
        "borderTransparency" to borderTransparency.toDouble(),
        "borderWidthDp" to borderWidthDp.toDouble(),
        "glassEffect" to glassEffect,
        "themeIndex" to themeIndex,
        "cornerDp" to cornerDp,
        "fitCrop" to fitCrop,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>?): WidgetSettings {
            if (m == null) return WidgetSettings()
            return WidgetSettings(
                partnerName = (m["partnerName"] as? String)?.takeIf { it.isNotBlank() } ?: "Partner",
                fontStyle = (m["fontStyle"] as? String) ?: "Calligraphy",
                fontColor = (m["fontColor"] as? Number)?.toLong() ?: 0xFFFFFFFFL,
                bgTransparency = (m["bgTransparency"] as? Number)?.toFloat() ?: 0.35f,
                bgColor = (m["bgColor"] as? Number)?.toLong() ?: 0L,
                borderColor = (m["borderColor"] as? Number)?.toLong() ?: 0x80FFFFFFL,
                borderTransparency = (m["borderTransparency"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 1f,
                borderWidthDp = (m["borderWidthDp"] as? Number)?.toFloat() ?: 1.5f,
                glassEffect = (m["glassEffect"] as? Boolean) ?: true,
                themeIndex = (m["themeIndex"] as? Number)?.toInt() ?: 0,
                cornerDp = (m["cornerDp"] as? Number)?.toInt() ?: 26,
                fitCrop = (m["fitCrop"] as? Boolean) ?: true,
            )
        }
    }
}

object LocalStore {
    private const val P = "pairwidget"
    private const val P_SETTINGS = "widget_settings"
    private const val P_CHAT = "chat_settings"

    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)
    private fun settingsPrefs(c: Context) = c.getSharedPreferences(P_SETTINGS, Context.MODE_PRIVATE)
    private fun chatPrefs(c: Context) = c.getSharedPreferences(P_CHAT, Context.MODE_PRIVATE)

    fun framesDir(c: Context) = File(c.filesDir, "frames").apply { mkdirs() }
    fun frameFile(c: Context, i: Int) = File(framesDir(c), "f$i.jpg")
    fun posterFile(c: Context) = File(c.filesDir, "poster.jpg")

    fun read(c: Context): WidgetState {
        val p = prefs(c)
        return WidgetState(
            kind = p.getString("kind", "") ?: "",
            text = p.getString("text", "") ?: "",
            posterPath = p.getString("poster", "") ?: "",
            frameCount = p.getInt("frames", 0),
            mediaUrl = p.getString("mediaUrl", "") ?: "",
            fitCrop = p.getBoolean("fitCrop", true),
            updatedAt = p.getLong("updatedAt", 0L),
        )
    }

    fun write(c: Context, s: WidgetState) {
        prefs(c).edit()
            .putString("kind", s.kind)
            .putString("text", s.text)
            .putString("poster", s.posterPath)
            .putInt("frames", s.frameCount)
            .putString("mediaUrl", s.mediaUrl)
            .putBoolean("fitCrop", s.fitCrop)
            .putLong("updatedAt", System.currentTimeMillis())
            .commit()
    }

    fun setFitCrop(c: Context, crop: Boolean) {
        prefs(c).edit().putBoolean("fitCrop", crop).putLong("updatedAt", System.currentTimeMillis()).commit()
        val s = readSettings(c)
        writeSettings(c, s.copy(fitCrop = crop))
    }

    fun readSettings(c: Context): WidgetSettings {
        val p = settingsPrefs(c)
        val stylePrefs = c.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
        val fallbackTheme = stylePrefs.getInt("theme", 0)
        val fallbackCorner = stylePrefs.getInt("corner", 26)
        val fallbackCrop = prefs(c).getBoolean("fitCrop", true)

        return WidgetSettings(
            partnerName = p.getString("partnerName", "Partner") ?: "Partner",
            fontStyle = p.getString("fontStyle", "Calligraphy") ?: "Calligraphy",
            fontColor = p.getLong("fontColor", 0xFFFFFFFFL),
            bgTransparency = p.getFloat("bgTransparency", 0.35f),
            bgColor = p.getLong("bgColor", 0L),
            borderColor = p.getLong("borderColor", 0x80FFFFFFL),
            borderTransparency = p.getFloat("borderTransparency", 1f).coerceIn(0f, 1f),
            borderWidthDp = p.getFloat("borderWidthDp", 1.5f),
            glassEffect = p.getBoolean("glassEffect", true),
            themeIndex = p.getInt("themeIndex", fallbackTheme),
            cornerDp = p.getInt("cornerDp", fallbackCorner),
            fitCrop = p.getBoolean("fitCrop", fallbackCrop),
        )
    }

    fun writeSettings(c: Context, s: WidgetSettings) {
        settingsPrefs(c).edit()
            .putString("partnerName", s.partnerName)
            .putString("fontStyle", s.fontStyle)
            .putLong("fontColor", s.fontColor)
            .putFloat("bgTransparency", s.bgTransparency)
            .putLong("bgColor", s.bgColor)
            .putLong("borderColor", s.borderColor)
            .putFloat("borderTransparency", s.borderTransparency)
            .putFloat("borderWidthDp", s.borderWidthDp)
            .putBoolean("glassEffect", s.glassEffect)
            .putInt("themeIndex", s.themeIndex)
            .putInt("cornerDp", s.cornerDp)
            .putBoolean("fitCrop", s.fitCrop)
            .commit()

        c.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit()
            .putInt("theme", s.themeIndex)
            .putInt("corner", s.cornerDp)
            .apply()
        prefs(c).edit().putBoolean("fitCrop", s.fitCrop).apply()
    }

    fun readChatSettings(c: Context): ChatSettings {
        val p = chatPrefs(c)
        return ChatSettings(
            wallpaperColor = p.getLong("wallpaperColor", 0xFF0B141BL),
            myBubbleColor = p.getLong("myBubbleColor", 0xFF005C4BL),
            partnerBubbleColor = p.getLong("partnerBubbleColor", 0xFF202C33L),
            messageFontSize = p.getInt("messageFontSize", 15),
            bubbleCornerDp = p.getInt("bubbleCornerDp", 14),
            fontStyle = p.getString("fontStyle", "Modern") ?: "Modern",
            swipeToReplyEnabled = p.getBoolean("swipeToReplyEnabled", true)
        )
    }

    fun writeChatSettings(c: Context, s: ChatSettings) {
        chatPrefs(c).edit()
            .putLong("wallpaperColor", s.wallpaperColor)
            .putLong("myBubbleColor", s.myBubbleColor)
            .putLong("partnerBubbleColor", s.partnerBubbleColor)
            .putInt("messageFontSize", s.messageFontSize)
            .putInt("bubbleCornerDp", s.bubbleCornerDp)
            .putString("fontStyle", s.fontStyle)
            .putBoolean("swipeToReplyEnabled", s.swipeToReplyEnabled)
            .apply()
    }

    fun getLastClearedAt(c: Context): Long = prefs(c).getLong("lastClearedAt", 0L)
    fun setLastClearedAt(c: Context, timestamp: Long) {
        prefs(c).edit().putLong("lastClearedAt", timestamp).apply()
    }

    fun pairedAt(c: Context): Long = prefs(c).getLong("pairedAt", 0L)
    fun setPairedAt(c: Context, timestamp: Long) {
        if (timestamp > 0L) {
            val existing = pairedAt(c)
            if (existing == 0L || timestamp < existing) {
                prefs(c).edit().putLong("pairedAt", timestamp).apply()
            }
        }
    }

    val DEFAULT_MOODS = listOf(
        "❤️" to "Love",
        "😊" to "Happy",
        "🥺" to "Miss You",
        "😴" to "Sleepy",
        "🔥" to "Excited",
        "☕" to "Coffee",
        "🍕" to "Food",
        "💼" to "Working"
    )

    fun getMoods(c: Context): List<Pair<String, String>> {
        val raw = prefs(c).getString("custom_moods_json", null) ?: return DEFAULT_MOODS
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val e = obj.optString("e")
                val l = obj.optString("l")
                if (e.isNotBlank()) {
                    list.add(e to l)
                }
            }
            if (list.isEmpty()) DEFAULT_MOODS else list
        } catch (_: Exception) {
            DEFAULT_MOODS
        }
    }

    fun saveMoods(c: Context, moods: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        for ((e, l) in moods) {
            val obj = org.json.JSONObject()
            obj.put("e", e)
            obj.put("l", l)
            arr.put(obj)
        }
        prefs(c).edit().putString("custom_moods_json", arr.toString()).apply()
    }

    fun addCustomMood(c: Context, emoji: String, label: String) {
        val current = getMoods(c).toMutableList()
        val idx = current.indexOfFirst { it.first == emoji.trim() }
        if (idx >= 0) {
            current[idx] = emoji.trim() to label.trim()
        } else {
            current.add(emoji.trim() to label.trim())
        }
        saveMoods(c, current)
    }

    fun removeCustomMood(c: Context, emoji: String) {
        val current = getMoods(c).filterNot { it.first == emoji.trim() }
        saveMoods(c, current)
    }

    fun resetMoods(c: Context) {
        prefs(c).edit().remove("custom_moods_json").apply()
    }

    fun pairId(c: Context): String = prefs(c).getString("pairId", "") ?: ""
    fun pairCode(c: Context): String = prefs(c).getString("pairCode", "") ?: ""

    var Context.pairId: String
        get() = pairId(this)
        set(v) { prefs(this).edit().putString("pairId", v).apply() }

    var Context.pairCode: String
        get() = pairCode(this)
        set(v) { prefs(this).edit().putString("pairCode", v).apply() }

    fun appUuid(c: Context): String {
        val p = prefs(c)
        var uuid = p.getString("app_uuid", null)
        if (uuid.isNullOrBlank()) {
            uuid = java.util.UUID.randomUUID().toString()
            p.edit().putString("app_uuid", uuid).commit()
        }
        return uuid
    }

    fun clearAllData(c: Context) {
        val uuid = appUuid(c)
        runCatching { posterFile(c).delete() }
        runCatching { framesDir(c).deleteRecursively() }
        runCatching {
            c.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("snap_") || file.name.startsWith("up_") || file.name.startsWith("enc_")) {
                    file.delete()
                }
            }
        }

        prefs(c).edit().clear().putString("app_uuid", uuid).commit()
        settingsPrefs(c).edit().clear().commit()
        chatPrefs(c).edit().clear().commit()
        c.getSharedPreferences("crypto", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit().clear().commit()

        write(c, WidgetState())
    }
}
