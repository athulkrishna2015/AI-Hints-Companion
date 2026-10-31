package dev.aihints.companion.anki

import dev.aihints.companion.core.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fallback backend for people running AnkiConnectAndroid.
 *
 * AnkiConnectAndroid implements a deliberately small action set: notes and
 * fields, but no templates, tags or card info. Generation and note writes work;
 * template installation and tag rules do not, so the UI disables them.
 */
class AnkiConnectPort(
    private val urlProvider: () -> String,
) : AnkiPort {

    override val id = "ankiconnect"
    override val label = "AnkiConnect"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun call(action: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Any? {
        val body = JSONObject()
            .put("action", action)
            .put("version", 6)
            .put("params", params)
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url(urlProvider())
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val text = response.body?.string() ?: error("empty response")
            val parsed = JSONObject(text)
            val error = parsed.opt("error")
            if (error != null && error != JSONObject.NULL) error(error.toString())
            return parsed.opt("result")
        }
    }

    override fun isAvailable(): Boolean = try {
        call("version", timeoutMs = 1500) != null
    } catch (e: Exception) {
        false
    }

    override fun capabilities(): Set<String> = setOf(AnkiPort.CAP_SEARCH)

    override fun readNote(nid: Long): String? {
        return try {
            val params = JSONObject().put("notes", JSONArray().put(nid))
            val result = call("notesInfo", params) as? JSONArray ?: return null
            if (result.length() == 0) return null
            val note = result.getJSONObject(0)
            if (note.optLong("noteId", -1) < 0 && note.optLong("id", -1) < 0) return null
            val modelName = note.optString("modelName")
            val fieldNames = try {
                (call("modelFieldNames", JSONObject().put("modelName", modelName)) as? JSONArray)
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val fieldsObj = note.optJSONObject("fields") ?: JSONObject()
            val names = if (fieldNames.isNotEmpty()) fieldNames else fieldsObj.keys().asSequence().toList()
            val fields = JSONArray()
            names.forEach { name ->
                val entry = fieldsObj.optJSONObject(name)
                val value = entry?.optString("value") ?: ""
                fields.put(JSONObject().put("name", name).put("value", value))
            }
            val tags = note.optJSONArray("tags") ?: JSONArray()
            val cardCount = note.optJSONArray("cards")?.length() ?: 1
            val tmpls = JSONArray()
            for (i in 0 until cardCount) {
                tmpls.put(
                    JSONObject()
                        .put("ord", i)
                        .put("name", "Card ${i + 1}")
                        .put("qfmt", if (i == 0) "{{${names.firstOrNull() ?: ""}}}" else "")
                        .put("afmt", "")
                        .put("bqfmt", "")
                        .put("bafmt", "")
                )
            }
            JSONObject()
                .put("nid", nid)
                .put("mid", 0)
                .put("model", JSONObject()
                    .put("id", 0)
                    .put("name", modelName)
                    .put("type", if (modelName.contains("cloze", ignoreCase = true)) 1 else 0)
                    .put("flds", JSONArray().apply { names.forEach { put(JSONObject().put("name", it)) } })
                    .put("tmpls", tmpls))
                .put("fields", fields)
                .put("tags", tags)
                .toString()
        } catch (e: Exception) {
            AppLog.e("AnkiConnect readNote($nid) failed", e)
            null
        }
    }

    override fun updateFields(nid: Long, fieldsJson: String) {
        val changed = JSONObject(fieldsJson)
        val fields = JSONObject()
        changed.keys().forEach { key -> fields.put(key, changed.optString(key)) }
        val note = JSONObject().put("id", nid).put("fields", fields)
        call("updateNoteFields", JSONObject().put("note", note))
    }

    override fun updateTags(nid: Long, tagsJson: String) {
        throw UnsupportedOperationException("AnkiConnectAndroid cannot update tags")
    }

    override fun listModels(): String {
        val models = JSONArray()
        val result = call("modelNamesAndIds") as? JSONObject ?: return "[]"
        result.keys().forEach { name ->
            val mid = result.optLong(name)
            val fieldNames = try {
                (call("modelFieldNames", JSONObject().put("modelName", name)) as? JSONArray)
                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            models.put(
                JSONObject()
                    .put("id", mid)
                    .put("name", name)
                    .put("type", if (name.contains("cloze", ignoreCase = true)) 1 else 0)
                    .put("flds", JSONArray().apply { fieldNames.forEach { put(JSONObject().put("name", it)) } })
                    .put("tmpls", JSONArray())
            )
        }
        return models.toString()
    }

    override fun updateModel(modelJson: String) {
        throw UnsupportedOperationException("AnkiConnectAndroid cannot update templates")
    }

    override fun findCards(query: String, limit: Int): String {
        val cards = JSONArray()
        val ids = call("findNotes", JSONObject().put("query", query.ifBlank { "deck:*" })) as? JSONArray
            ?: return "[]"
        val wanted = if (limit > 0) minOf(limit, ids.length()) else ids.length()
        if (wanted == 0) return "[]"
        val noteIds = JSONArray()
        for (i in 0 until wanted) noteIds.put(ids.getLong(i))
        val info = call("notesInfo", JSONObject().put("notes", noteIds)) as? JSONArray ?: return "[]"
        for (i in 0 until info.length()) {
            val note = info.getJSONObject(i)
            val nid = note.optLong("noteId", note.optLong("id", -1))
            if (nid < 0) continue
            val count = note.optJSONArray("cards")?.length() ?: 1
            for (ord in 0 until count) cards.put(JSONObject().put("nid", nid).put("ord", ord))
        }
        return cards.toString()
    }

    override fun answerCard(nid: Long, ord: Int, ease: Int) {
        throw UnsupportedOperationException("AnkiConnectAndroid cannot answer cards")
    }
}
