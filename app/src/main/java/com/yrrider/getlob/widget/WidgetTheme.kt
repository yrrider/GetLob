package com.yrrider.getlob.widget

import android.content.Context
import com.yrrider.getlob.data.LocalStore

/** A widget colour theme. Colours are ARGB longs. */
data class WidgetTheme(val name: String, val bg: Long, val fg: Long, val accent: Long)

object WidgetThemes {
    val all = listOf(
        WidgetTheme("Frosted Glass", 0xFF181F38, 0xFFFFFFFF, 0xFF38BDF8),
        WidgetTheme("Midnight", 0xFF1E1E2E, 0xFFFFFFFF, 0xFF4F6BED),
        WidgetTheme("Rose", 0xFF3B1F2B, 0xFFFFE4EC, 0xFFFF5C8A),
        WidgetTheme("Ocean", 0xFF0F2A3D, 0xFFE6F4FF, 0xFF29B6F6),
        WidgetTheme("Forest", 0xFF14301F, 0xFFE8F5E9, 0xFF66BB6A),
        WidgetTheme("Sunset", 0xFF3A2112, 0xFFFFF0E0, 0xFFFF9800),
        WidgetTheme("Paper", 0xFFF7F3EA, 0xFF2B2B2B, 0xFF8D6E63),
        WidgetTheme("Mono", 0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E),
    )

    fun theme(c: Context): WidgetTheme {
        val s = LocalStore.readSettings(c)
        return all[s.themeIndex.coerceIn(all.indices)]
    }

    fun themeIndex(c: Context): Int = LocalStore.readSettings(c).themeIndex

    fun setTheme(c: Context, i: Int) {
        val s = LocalStore.readSettings(c)
        LocalStore.writeSettings(c, s.copy(themeIndex = i))
    }

    fun cornerDp(c: Context): Int = LocalStore.readSettings(c).cornerDp

    fun setCornerDp(c: Context, dp: Int) {
        val s = LocalStore.readSettings(c)
        LocalStore.writeSettings(c, s.copy(cornerDp = dp))
    }

    /** Used as a cache-buster so Glance re-decodes when style changes. */
    fun version(c: Context): Int {
        val s = LocalStore.readSettings(c)
        return s.themeIndex * 10000 + s.cornerDp * 100 + (s.bgTransparency * 100).toInt() + s.fontStyle.hashCode().and(0x7F)
    }
}
