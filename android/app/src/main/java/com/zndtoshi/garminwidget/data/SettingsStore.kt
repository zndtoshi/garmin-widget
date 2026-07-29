package com.zndtoshi.garminwidget.data

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = TokenCipher()

    fun backendUrl(): String = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL

    fun isConfigured(): Boolean = prefs.contains(KEY_ENCRYPTED_TOKEN)

    fun bearerToken(): String? {
        val encrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
        return cipher.decrypt(encrypted)
    }

    fun save(backendUrl: String, token: String?) {
        val editor = prefs.edit()
        editor.putString(KEY_BACKEND_URL, backendUrl.trimEnd('/'))
        if (token != null) {
            editor.putString(KEY_ENCRYPTED_TOKEN, cipher.encrypt(token))
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "garmin_widget_settings"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        const val DEFAULT_BACKEND_URL = "https://garmin.zndtoshi.com"
    }
}
