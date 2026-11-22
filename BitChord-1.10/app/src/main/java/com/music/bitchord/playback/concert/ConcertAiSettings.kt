package com.music.bitchord.playback.concert

import android.content.Context
import com.music.bitchord.auth.AuthStore

/** OpenRouter configuration for the optional AI set-list designer. The API key is encrypted. */
object ConcertAiSettings {
    private const val PREFS = "bitchord_concert_ai"
    private const val KEY_SECRET = "concert_openrouter_api_key"
    private const val KEY_URL = "openrouter_base_url"
    private const val KEY_MODEL = "openrouter_model"

    fun apiKey(context: Context): String = AuthStore(context.applicationContext).settingSecret(KEY_SECRET).orEmpty()
    fun baseUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_URL, DEFAULT_URL).orEmpty()
    fun model(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()

    fun save(context: Context, apiKey: String, baseUrl: String, model: String) {
        AuthStore(context.applicationContext).setSettingSecret(KEY_SECRET, apiKey.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, baseUrl.trim().ifBlank { DEFAULT_URL })
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }

    private const val DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEFAULT_MODEL = "google/gemini-3.1-flash-lite"
}
