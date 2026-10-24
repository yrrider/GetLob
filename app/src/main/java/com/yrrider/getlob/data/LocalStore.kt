package com.yrrider.getlob.data

import android.content.Context
import java.io.File

/** What the widget currently shows. Persisted locally so the widget renders with no network. */
data class WidgetState(
    val kind: String = "",          // "", text, emoji, image, gif, video, voice, sos, game
    val text: String = "",
    val posterPath: String = "",    // scaled still (image, or first frame of gif/video)
    val frameCount: Int = 0,        // >0 => animated slideshow frames exist
    val mediaUrl: String = "",      // for playing gif/video/voice in the overlay
    val fitCrop: Boolean = true,    // true = fill (crop), false = fit (letterbox)
    val updatedAt: Long = 0L,
)

/** User customizable widget appearance & shared partner settings. Synced between both devices. */
data class WidgetSettings(
    val partnerName: String = "Partner",
    val fontStyle: String = "Calligraphy",   // "Calligraphy", "Cursive", "Serif", "Modern"
    val fontColor: Long = 0xFFFFFFFFL,
    val bgTransparency: Float = 0.35f,       // 0.0f (transparent) to 1.0f (opaque)
    val bgColor: Long = 0L,                  // Widget background color (ARGB), 0L = theme default
    val borderColor: Long = 0x80FFFFFFL,     // ARGB long
    val borderTransparency: Float = 1f,      // 0.0f = invisible, 1.0f = supplied color alpha
    val borderWidthDp: Float = 1.5f,
    val glassEffect: Boolean = true,         // Frosted glass specular gradient and highlight
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

    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)
    private fun settingsPrefs(c: Context) = c.getSharedPreferences(P_SETTINGS, Context.MODE_PRIVATE)

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

        // Keep widget_style prefs in sync for legacy readers
        c.getSharedPreferences("widget_style", Context.MODE_PRIVATE).edit()
            .putInt("theme", s.themeIndex)
            .putInt("corner", s.cornerDp)
            .apply()
        prefs(c).edit().putBoolean("fitCrop", s.fitCrop).apply()
    }

    fun getLastClearedAt(c: Context): Long = prefs(c).getLong("lastClearedAt", 0L)
    fun setLastClearedAt(c: Context, timestamp: Long) {
        prefs(c).edit().putLong("lastClearedAt", timestamp).apply()
    }

    var Context.pairId: String
        get() = prefs(this).getString("pairId", "") ?: ""
        set(v) { prefs(this).edit().putString("pairId", v).apply() }

    var Context.pairCode: String
        get() = prefs(this).getString("pairCode", "") ?: ""
        set(v) { prefs(this).edit().putString("pairCode", v).apply() }
}
