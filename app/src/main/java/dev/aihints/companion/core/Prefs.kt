package dev.aihints.companion.core

import android.content.Context

/** App-local preferences. Add-on configuration lives in [dev.aihints.companion.data.ConfigRepository]. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("aihints_prefs", Context.MODE_PRIVATE)

    var bridgePort: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /** auto | provider | ankiconnect */
    var backendMode: String
        get() = sp.getString(KEY_BACKEND, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_BACKEND, value).apply()

    var ankiConnectUrl: String
        get() = sp.getString(KEY_ANKICONNECT, "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
        set(value) = sp.edit().putString(KEY_ANKICONNECT, value).apply()

    var bridgeEnabled: Boolean
        get() = sp.getBoolean(KEY_BRIDGE_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_BRIDGE_ENABLED, value).apply()

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var pregenEnabled: Boolean
        get() = sp.getBoolean(KEY_PREGEN, true)
        set(value) = sp.edit().putBoolean(KEY_PREGEN, value).apply()

    var lastSeenAddonVersion: String?
        get() = sp.getString(KEY_ADDON_VERSION, null)
        set(value) = sp.edit().putString(KEY_ADDON_VERSION, value).apply()

    companion object {
        const val DEFAULT_PORT = 8766
        private const val KEY_PORT = "bridge_port"
        private const val KEY_BACKEND = "backend_mode"
        private const val KEY_ANKICONNECT = "ankiconnect_url"
        private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_PREGEN = "pregen_enabled"
        private const val KEY_ADDON_VERSION = "addon_version"
    }
}
