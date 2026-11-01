package dev.aihints.companion.bridge

import android.content.Context
import android.content.Intent
import dev.aihints.companion.MainActivity
import dev.aihints.companion.anki.BackendManager
import dev.aihints.companion.core.AppLog
import dev.aihints.companion.python.PyEngine
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local HTTP surface for the reviewer WebView.
 *
 * Bound to 127.0.0.1 only. Requests from a non-loopback Origin are rejected so
 * a web page cannot read card data from the device; the check is defence in
 * depth, not a substitute for the loopback bind.
 */
class BridgeServer(
    private val context: Context,
    private val port: Int,
    private val hub: EventHub,
    private val backend: BackendManager,
    private val py: PyEngine,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val origin = session.headers["origin"]
        val path = session.uri.substringBefore('?')

        if (session.method == Method.OPTIONS) {
            return preflight(session, origin)
        }
        if (!isLoopback(origin) && (session.method == Method.POST || path == "/events")) {
            AppLog.w("Rejected bridge request from origin $origin")
            return text(session, Response.Status.FORBIDDEN, "forbidden", origin)
        }

        return try {
            when {
                path == "/health" && session.method == Method.GET -> health(session, origin)
                path == "/bridge.js" && session.method == Method.GET -> asset(session, origin, "ai_hints_bridge.js")
                path == "/template.js" && session.method == Method.GET -> asset(session, origin, "_ai_hints_template.js")
                path == "/state" && session.method == Method.POST -> state(session, origin)
                path == "/cmd" && session.method == Method.POST -> command(session, origin)
                path == "/events" && session.method == Method.GET -> events(session, origin)
                else -> text(session, Response.Status.NOT_FOUND, "not found", origin)
            }
        } catch (e: Exception) {
            AppLog.e("Bridge error on $path", e)
            text(session, Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"internal"}""", origin)
        }
    }

    // --------------------------------------------------------------- handlers
    private fun debug(session: IHTTPSession, origin: String?): Response {
        val models = backend.port()?.listModels() ?: "[]"
        return text(session, Response.Status.OK, models, origin)
    }

    private fun health(session: IHTTPSession, origin: String?): Response {
        val status = backend.status()
        val body = JSONObject()
            .put("ok", true)
            .put("port", port)
            .put("addonVersion", py.addonVersion)
            .put("pythonReady", py.ready)
            .put("backend", JSONObject()
                .put("id", status.backend)
                .put("label", status.label)
                .put("available", status.available)
                .put("reason", status.reason))
            .toString()
        return text(session, Response.Status.OK, body, origin)
    }

    private fun state(session: IHTTPSession, origin: String?): Response {
        val payload = JSONObject(body(session))
        val nid = payload.optLong("nid", -1)
        if (nid < 0) return text(session, Response.Status.BAD_REQUEST, """{"ok":false,"error":"nid required"}""", origin)
        val ord = payload.optInt("ord", 0)
        val isAnswer = payload.optBoolean("isAnswer", false)
        val result = py.call("state", nid, ord, isAnswer)
        return text(session, Response.Status.OK, result, origin)
    }

    private fun command(session: IHTTPSession, origin: String?): Response {
        val payload = JSONObject(body(session))
        val nid = payload.optLong("nid", -1)
        if (nid < 0) return text(session, Response.Status.BAD_REQUEST, """{"ok":false,"error":"nid required"}""", origin)
        val ord = payload.optInt("ord", 0)
        val command = payload.optJSONObject("command") ?: payload
        if (command.optString("action") == "open_app") {
            return openApp(session, origin, command.optString("tab", ""))
        }
        val result = py.call("command", nid, ord, command.toString())
        return text(session, Response.Status.OK, result, origin)
    }

    /**
     * The reviewer's "App…" button. A WebView in another app cannot start this
     * activity directly, so the bridge (a foreground service) does it on its
     * behalf. If Android blocks the background activity launch, the persistent
     * notification remains the fallback.
     */
    private fun openApp(session: IHTTPSession, origin: String?, tab: String): Response {
        return try {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (tab.isNotBlank()) intent.putExtra(MainActivity.EXTRA_TAB, tab)
            context.startActivity(intent)
            text(session, Response.Status.OK, """{"ok":true}""", origin)
        } catch (e: Exception) {
            AppLog.w("Could not open app from reviewer: ${e.message}")
            text(
                session,
                Response.Status.OK,
                """{"ok":false,"error":"open the AI-Hints app from the notification"}""",
                origin,
            )
        }
    }

    private fun events(session: IHTTPSession, origin: String?): Response {
        val nid = session.parameters["nid"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val queue: ArrayBlockingQueue<String> = hub.subscribe(nid)
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 16 * 1024)
        thread(isDaemon = true, name = "aihints-sse-$nid") {
            try {
                out.write("retry: 3000\n\n".toByteArray())
                out.flush()
                while (!Thread.currentThread().isInterrupted) {
                    val event = queue.poll(15, TimeUnit.SECONDS)
                    if (event == null) {
                        out.write(": ping\n\n".toByteArray())
                    } else {
                        out.write("data: $event\n\n".toByteArray())
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                // Client went away (AnkiDroid reloaded the card). Expected.
            } finally {
                hub.unsubscribe(nid, queue)
                try { out.close() } catch (_: Exception) {}
            }
        }
        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", input)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        addCors(response, origin)
        return response
    }

    private fun asset(session: IHTTPSession, origin: String?, name: String): Response {
        val bytes = context.assets.open(name).use { it.readBytes() }
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/javascript; charset=utf-8",
            bytes.inputStream(),
            bytes.size.toLong(),
        )
        response.addHeader("Cache-Control", "no-store")
        addCors(response, origin)
        return response
    }

    // ---------------------------------------------------------------- helpers
    private fun body(session: IHTTPSession): String {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun text(
        session: IHTTPSession,
        status: Response.Status,
        body: String,
        origin: String?,
    ): Response {
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", body)
        addCors(response, origin)
        return response
    }

    private fun preflight(session: IHTTPSession, origin: String?): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        addCors(response, origin)
        response.addHeader("Access-Control-Max-Age", "600")
        return response
    }

    private fun addCors(response: Response, origin: String?) {
        response.addHeader("Access-Control-Allow-Origin", if (origin == null) "*" else origin)
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        response.addHeader("Vary", "Origin")
    }

    private fun isLoopback(origin: String?): Boolean {
        if (origin == null || origin == "null") return true
        return origin.startsWith("http://127.0.0.1") ||
            origin.startsWith("http://localhost") ||
            origin.startsWith("http://[::1]")
    }
}
