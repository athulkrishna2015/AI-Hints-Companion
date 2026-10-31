package dev.aihints.companion.python

import dev.aihints.companion.anki.AnkiPort
import dev.aihints.companion.anki.BackendManager
import dev.aihints.companion.bridge.EventHub
import dev.aihints.companion.core.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * The object Python sees. Every method is called from Python threads, so it
 * must be cheap, thread-safe and must never throw back into the interpreter.
 */
class PyPort(
    private val backend: BackendManager,
    private val events: EventHub,
) {

    fun emit(json: String) {
        try {
            events.publish(json)
        } catch (e: Exception) {
            AppLog.w("Event publish failed: ${e.message}")
        }
    }

    fun readNote(nid: Long): String {
        return try {
            backend.port()?.readNote(nid) ?: "{}"
        } catch (e: Exception) {
            AppLog.e("readNote($nid) failed", e)
            "{}"
        }
    }

    fun updateFields(nid: Long, fieldsJson: String) {
        try {
            backend.port()?.updateFields(nid, fieldsJson)
        } catch (e: Exception) {
            AppLog.e("updateFields($nid) failed", e)
            throw e
        }
    }

    fun updateTags(nid: Long, tagsJson: String) {
        try {
            backend.port()?.updateTags(nid, tagsJson)
        } catch (e: UnsupportedOperationException) {
            AppLog.w("This backend cannot update tags; skipping tag rules.")
        } catch (e: Exception) {
            AppLog.e("updateTags($nid) failed", e)
            throw e
        }
    }

    fun listModels(): String {
        return try {
            backend.port()?.listModels() ?: "[]"
        } catch (e: Exception) {
            AppLog.e("listModels failed", e)
            "[]"
        }
    }

    fun updateModel(modelJson: String) {
        backend.port()?.updateModel(modelJson)
    }

    fun findCards(query: String, limit: Int): String {
        return try {
            backend.port()?.findCards(query, limit) ?: "[]"
        } catch (e: Exception) {
            AppLog.e("findCards failed", e)
            "[]"
        }
    }

    fun answerCard(nid: Long, ord: Int, ease: Int) {
        try {
            backend.port()?.answerCard(nid, ord, ease)
        } catch (e: Exception) {
            AppLog.e("answerCard($nid) failed", e)
        }
    }

    fun log(level: String, message: String) {
        when (level.uppercase()) {
            "E", "ERROR" -> AppLog.e(message)
            "W", "WARNING" -> AppLog.w(message)
            "D", "DEBUG" -> AppLog.d(message)
            else -> AppLog.i(message)
        }
    }

    fun backendInfo(): String {
        val status = backend.status()
        return JSONObject()
            .put("backend", status.backend)
            .put("available", status.available)
            .put("capabilities", JSONArray(status.capabilities.toList()))
            .toString()
    }
}
