package com.yrrider.getlob.widget

import android.content.Context
import com.yrrider.getlob.data.LocalStore

/** A widget colour theme. Colours are ARGB longs. */
data class WidgetTheme(val name: String, val bg: Long, val fg: Long, val accent: Long)

object WidgetThemes {
    val all = listOf(
        WidgetTheme("Pitch Black", 0xFF000000L, 0xFFFFFFFFL, 0xFF38BDF8L),
        WidgetTheme("Frosted Glass", 0xFF181F38L, 0xFFFFFFFFL, 0xFF38BDF8L),
        WidgetTheme("Midnight", 0xFF1E1E2EL, 0xFFFFFFFFL, 0xFF4F6BEDL),
        WidgetTheme("Rose", 0xFF3B1F2BL, 0xFFFFE4ECL, 0xFFFF5C8AL),
        WidgetTheme("Ocean", 0xFF0F2A3DL, 0xFFE6F4FFL, 0xFF29B6F6L),
        WidgetTheme("Forest", 0xFF14301FL, 0xFFE8F5E9L, 0xFF66BB6AL),
        WidgetTheme("Sunset", 0xFF3A2112L, 0xFFFFF0E0L, 0xFFFF9800L),
        WidgetTheme("Paper", 0xFFF7F3EAL, 0xFF2B2B2BL, 0xFF8D6E63L),
        WidgetTheme("Mono", 0xFF000000L, 0xFFFFFFFFL, 0xFF9E9E9EL),
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
