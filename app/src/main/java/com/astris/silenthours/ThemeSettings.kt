package com.astris.silenthours

import android.content.Context
import android.content.SharedPreferences

class ThemeSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    var isDarkTheme: Boolean
        get() = prefs.getBoolean("is_dark_theme", false)
        set(value) = prefs.edit().putBoolean("is_dark_theme", value).apply()

    var isThemeSetByUser: Boolean
        get() = prefs.getBoolean("is_theme_set_by_user", false)
        set(value) = prefs.edit().putBoolean("is_theme_set_by_user", value).apply()
}
