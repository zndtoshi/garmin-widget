package com.zndtoshi.garminwidget.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher by lazy { TokenCipher() }

    fun backendUrl(): String = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL

    fun isConfigured(): Boolean = prefs.contains(KEY_ENCRYPTED_TOKEN)

    fun bearerToken(): String? {
        val encrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
        return cipher.decrypt(encrypted)
    }

    fun save(backendUrl: String, token: String?) {
        val replacement = token?.takeIf { it.isNotBlank() }?.let { cipher.encrypt(it) }
        persistSettings(prefs, backendUrl, encryptedToken = replacement, replaceToken = replacement != null)
    }

    fun widgetOpacityPercent(): Int {
        // Missing key returns default; corrupt types and out-of-range ints are safe.
        if (!prefs.contains(KEY_WIDGET_OPACITY_PERCENT)) {
            return DEFAULT_WIDGET_OPACITY_PERCENT
        }
        val stored = runCatching { prefs.getInt(KEY_WIDGET_OPACITY_PERCENT, DEFAULT_WIDGET_OPACITY_PERCENT) }
            .getOrElse { DEFAULT_WIDGET_OPACITY_PERCENT }
        return clampOpacity(stored)
    }

    fun saveWidgetOpacityPercent(value: Int) {
        prefs.edit().putInt(KEY_WIDGET_OPACITY_PERCENT, clampOpacity(value)).commit()
    }

    fun activityHrColorMode(): ActivityHrColorMode {
        if (!prefs.contains(KEY_ACTIVITY_HR_COLOR_MODE)) {
            return ActivityHrColorMode.DEFAULT
        }
        val stored = runCatching { prefs.getString(KEY_ACTIVITY_HR_COLOR_MODE, null) }
            .getOrNull()
        return ActivityHrColorMode.fromStorage(stored)
    }

    fun saveActivityHrColorMode(mode: ActivityHrColorMode) {
        prefs.edit().putString(KEY_ACTIVITY_HR_COLOR_MODE, mode.storageValue).commit()
    }

    companion object {
        const val PREFS_NAME = "garmin_widget_settings"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        const val KEY_WIDGET_OPACITY_PERCENT = "widget_opacity_percent"
        const val KEY_ACTIVITY_HR_COLOR_MODE = "activity_hr_color_mode"
        const val DEFAULT_BACKEND_URL = "https://garmin.zndtoshi.com"
        const val DEFAULT_WIDGET_OPACITY_PERCENT = 88

        internal fun clampOpacity(value: Int?): Int = (value ?: DEFAULT_WIDGET_OPACITY_PERCENT).coerceIn(0, 100)

        /**
         * SharedPreferences write rule used by [save]: blank/null token must not remove an existing
         * encrypted token. Cipher is intentionally outside this seam for Robolectric coverage.
         */
        internal fun persistSettings(
            prefs: SharedPreferences,
            backendUrl: String,
            encryptedToken: String?,
            replaceToken: Boolean,
        ) {
            val editor = prefs.edit()
            editor.putString(KEY_BACKEND_URL, backendUrl.trimEnd('/'))
            if (replaceToken && encryptedToken != null) {
                editor.putString(KEY_ENCRYPTED_TOKEN, encryptedToken)
            }
            editor.commit()
        }
    }
}
