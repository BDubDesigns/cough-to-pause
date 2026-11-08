package dev.harness.coughtopause.adventure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provider settings for the optional AI dungeon master.
 *
 * The key is supplied by the player at runtime and stored Keystore-backed on this device. It is
 * never compiled in, never committed, and never logged. With no key the app runs the deterministic
 * narrator and says so.
 */
data class AiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
) {
    val configured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** Full chat-completions URL, tolerating a base that already includes the path. */
    val completionsUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"
    }
}

class AiConfigStore(context: Context) {

    private val prefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    fun load(): AiConfig = AiConfig(
        baseUrl = prefs?.getString(KEY_BASE_URL, null) ?: AiConfig.DEFAULT_BASE_URL,
        model = prefs?.getString(KEY_MODEL, null) ?: AiConfig.DEFAULT_MODEL,
        apiKey = prefs?.getString(KEY_API_KEY, null).orEmpty(),
    )

    fun save(config: AiConfig) {
        prefs?.edit()
            ?.putString(KEY_BASE_URL, config.baseUrl.trim())
            ?.putString(KEY_MODEL, config.model.trim())
            ?.putString(KEY_API_KEY, config.apiKey.trim())
            ?.apply()
    }

    fun clearKey() {
        prefs?.edit()?.remove(KEY_API_KEY)?.apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
    }
}
