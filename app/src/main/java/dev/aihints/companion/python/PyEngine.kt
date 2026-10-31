package dev.aihints.companion.python

import com.chaquo.python.Python
import dev.aihints.companion.anki.BackendManager
import dev.aihints.companion.bridge.EventHub
import dev.aihints.companion.core.AppLog
import org.json.JSONObject
import java.io.File

/**
 * Thin, JSON-only facade over `aihints_android.api`.
 *
 * Chaquopy calls are blocking, so every caller must be on an IO dispatcher.
 */
class PyEngine(
    backend: BackendManager,
    events: EventHub,
    dataDir: File,
) {

    private val pyPort = PyPort(backend, events)
    private val dataDir = dataDir

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var addonVersion: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun ensureStarted(): Boolean {
        if (ready) return true
        return try {
            dataDir.mkdirs()
            val module = Python.getInstance().getModule("aihints_android.api")
            val raw = module.callAttr("start", "", dataDir.absolutePath, pyPort).toString()
            val parsed = JSONObject(raw)
            if (parsed.optBoolean("ok")) {
                ready = true
                addonVersion = parsed.optString("addonVersion")
                lastError = null
                AppLog.i("Python core ready (add-on $addonVersion)")
                true
            } else {
                lastError = parsed.optString("error", "Python start failed")
                AppLog.e("Python start failed: $lastError")
                false
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            AppLog.e("Python start crashed", e)
            false
        }
    }

    fun call(function: String, vararg args: Any?): String {
        if (!ensureStarted()) {
            return JSONObject()
                .put("ok", false)
                .put("error", lastError ?: "Python core not started")
                .toString()
        }
        return try {
            val module = Python.getInstance().getModule("aihints_android.api")
            module.callAttr(function, *args).toString()
        } catch (e: Exception) {
            AppLog.e("Python call $function failed", e)
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName)
                .toString()
        }
    }

    fun shutdown() {
        if (!ready) return
        try {
            val module = Python.getInstance().getModule("aihints_android.api")
            module.callAttr("shutdown")
        } catch (e: Exception) {
            AppLog.w("Python shutdown failed: ${e.message}")
        }
    }
}
