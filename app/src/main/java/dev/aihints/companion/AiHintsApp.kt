package dev.aihints.companion

import android.content.Context
import com.chaquo.python.android.PyApplication
import dev.aihints.companion.anki.BackendManager
import dev.aihints.companion.bridge.BridgeServer
import dev.aihints.companion.bridge.EventHub
import dev.aihints.companion.core.AppLog
import dev.aihints.companion.core.Prefs
import dev.aihints.companion.python.PyEngine
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * PyApplication starts Chaquopy with the Android platform before anything calls
 * [com.chaquo.python.Python.getInstance]; a plain Application crashes with
 * "Cannot use GenericPlatform on Android".
 */
class AiHintsApp : PyApplication() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        AppLog.i("AI-Hints Companion ${BuildConfig.VERSION_NAME} starting")
    }
}

/**
 * Manual dependency graph: small enough that a DI framework would be noise.
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    val backend = BackendManager(appContext, prefs)
    val events = EventHub()
    val dataDir: File = File(appContext.filesDir, "ai_hints")
    val py = PyEngine(backend, events, dataDir)

    private var server: BridgeServer? = null

    @Volatile
    var bridgeError: String? = null
        private set

    val bridgeRunning: Boolean
        get() = server?.wasStarted() == true

    @Synchronized
    fun startBridge(): Boolean {
        if (bridgeRunning) return true
        if (!prefs.bridgeEnabled) {
            bridgeError = "Bridge disabled in settings"
            return false
        }
        if (!py.ensureStarted()) {
            bridgeError = py.lastError ?: "Python core failed to start"
            AppLog.e("Bridge not started: $bridgeError")
            return false
        }
        val port = prefs.bridgePort
        val candidate = BridgeServer(appContext, port, events, backend, py)
        return try {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = candidate
            bridgeError = null
            AppLog.i("Bridge listening on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            bridgeError = "Port $port unavailable: ${e.message}"
            AppLog.e("Bridge failed to start on port $port", e)
            try { candidate.stop() } catch (_: Exception) {}
            false
        }
    }

    @Synchronized
    fun stopBridge() {
        server?.stop()
        server = null
        AppLog.i("Bridge stopped")
    }

    fun restartBridge(): Boolean {
        stopBridge()
        backend.invalidate()
        return startBridge()
    }
}
