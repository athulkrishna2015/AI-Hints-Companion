package dev.aihints.companion.anki

import android.content.Context
import dev.aihints.companion.core.AppLog
import dev.aihints.companion.core.Prefs

/**
 * Chooses the live backend and remembers why it is unavailable.
 *
 * Availability is re-checked lazily: AnkiDroid closes its collection whenever
 * the app is backgrounded, so a cached "connected" answer goes stale quickly.
 */
class BackendManager(
    context: Context,
    private val prefs: Prefs,
) {

    private val provider = ContentProviderPort(context.applicationContext)
    private val ankiConnect = AnkiConnectPort { prefs.ankiConnectUrl }

    @Volatile
    private var cachedPort: AnkiPort? = null
    @Volatile
    private var cachedStatus: BackendStatus = BackendStatus()
    @Volatile
    private var checkedAt = 0L

    fun port(): AnkiPort? {
        val now = System.currentTimeMillis()
        if (now - checkedAt < CACHE_MS) return cachedPort
        return refresh()
    }

    fun status(): BackendStatus {
        val now = System.currentTimeMillis()
        if (now - checkedAt < CACHE_MS) return cachedStatus
        refresh()
        return cachedStatus
    }

    fun invalidate() {
        checkedAt = 0L
        cachedPort = null
    }

    @Synchronized
    fun refresh(): AnkiPort? {
        val mode = prefs.backendMode
        val candidates = when (mode) {
            "provider" -> listOf(provider)
            "ankiconnect" -> listOf(ankiConnect)
            else -> listOf(provider, ankiConnect)
        }
        for (candidate in candidates) {
            try {
                if (candidate.isAvailable()) {
                    cachedPort = candidate
                    cachedStatus = BackendStatus(
                        backend = candidate.id,
                        label = candidate.label,
                        available = true,
                        reason = "",
                        capabilities = candidate.capabilities(),
                        checkedAt = System.currentTimeMillis(),
                    )
                    checkedAt = System.currentTimeMillis()
                    return candidate
                }
            } catch (e: Exception) {
                AppLog.d("Backend ${candidate.id} check failed: ${e.javaClass.simpleName}")
            }
        }
        val reason = when (mode) {
            "provider" -> "AnkiDroid API not available. Enable Settings → Advanced → Allow external API access."
            "ankiconnect" -> "AnkiConnect not reachable at ${prefs.ankiConnectUrl}."
            else -> "Neither the AnkiDroid API nor AnkiConnect is reachable."
        }
        cachedPort = null
        cachedStatus = BackendStatus(
            backend = "none",
            label = "Not connected",
            available = false,
            reason = reason,
            checkedAt = System.currentTimeMillis(),
        )
        checkedAt = System.currentTimeMillis()
        return null
    }

    companion object {
        private const val CACHE_MS = 3_000L
    }
}
