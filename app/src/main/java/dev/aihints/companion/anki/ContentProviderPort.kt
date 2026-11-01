package dev.aihints.companion.anki

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import dev.aihints.companion.core.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnkiDroid's content provider ("Allow external API access").
 *
 * Unlike AnkiConnect this exposes note types, card templates and tags, which is
 * what makes full add-on parity possible: template installation, tag rules and
 * cloze/ordinal-aware parsing all need them.
 */
class ContentProviderPort(private val context: Context) : AnkiPort {

    private val resolver: ContentResolver get() = context.contentResolver

    override val id = "ankidroid"
    override val label = "AnkiDroid API"

    override fun isAvailable(): Boolean {
        if (context.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            resolver.query(
                Uri.withAppendedPath(MODEL_URI, "current"),
                arrayOf("_id"),
                null, null, null,
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            AppLog.d("AnkiDroid provider unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun capabilities(): Set<String> = setOf(
        AnkiPort.CAP_TEMPLATES,
        AnkiPort.CAP_TAGS,
        AnkiPort.CAP_SEARCH,
        AnkiPort.CAP_SCHEDULE,
    )

    // ------------------------------------------------------------------ notes
    override fun readNote(nid: Long): String? {
        val uri = ContentUris.withAppendedId(NOTE_URI, nid)
        return try {
            resolver.query(uri, arrayOf("_id", "mid", "tags", "flds"), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val mid = cursor.getLong(1)
                val tags = (cursor.getString(2) ?: "").split(' ').filter { it.isNotBlank() }
                val rawFields = (cursor.getString(3) ?: "").split(FIELD_SEP)
                val model = readModel(mid) ?: return null
                val names = model.optJSONArray("flds") ?: JSONArray()
                val fields = JSONArray()
                for (i in 0 until maxOf(names.length(), rawFields.size)) {
                    val name = if (i < names.length()) {
                        names.getJSONObject(i).optString("name")
                    } else {
                        "Field ${i + 1}"
                    }
                    fields.put(
                        JSONObject()
                            .put("name", name)
                            .put("value", rawFields.getOrElse(i) { "" })
                    )
                }
                JSONObject()
                    .put("nid", nid)
                    .put("mid", mid)
                    .put("model", model)
                    .put("fields", fields)
                    .put("tags", JSONArray(tags))
                    .toString()
            }
        } catch (e: Exception) {
            AppLog.e("readNote($nid) failed", e)
            null
        }
    }

    override fun updateFields(nid: Long, fieldsJson: String) {
        val changed = JSONObject(fieldsJson)
        val uri = ContentUris.withAppendedId(NOTE_URI, nid)
        resolver.query(uri, arrayOf("mid", "flds"), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val mid = cursor.getLong(0)
            val values = (cursor.getString(1) ?: "").split(FIELD_SEP).toMutableList()
            val model = readModel(mid) ?: return
            val names = model.optJSONArray("flds") ?: JSONArray()
            while (values.size < names.length()) values.add("")
            for (i in 0 until names.length()) {
                val name = names.getJSONObject(i).optString("name")
                if (changed.has(name)) values[i] = changed.optString(name)
            }
            val cv = ContentValues().apply { put("flds", values.joinToString(FIELD_SEP)) }
            resolver.update(uri, cv, null, null)
        }
    }

    override fun updateTags(nid: Long, tagsJson: String) {
        val tags = JSONArray(tagsJson)
        val list = (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() }
        val cv = ContentValues().apply { put("tags", list.joinToString(" ")) }
        resolver.update(ContentUris.withAppendedId(NOTE_URI, nid), cv, null, null)
    }

    // ----------------------------------------------------------------- models
    override fun listModels(): String {
        val models = JSONArray()
        resolver.query(
            MODEL_URI,
            arrayOf("_id", "name", "field_names", "type", "num_cards"),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val model = readModel(cursor.getLong(0)) ?: continue
                models.put(model)
            }
        }
        return models.toString()
    }

    private fun readModel(mid: Long): JSONObject? {
        val uri = ContentUris.withAppendedId(MODEL_URI, mid)
        return resolver.query(
            uri,
            arrayOf("_id", "name", "field_names", "type", "num_cards"),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(1) ?: "Note type $mid"
            val fieldNames = (cursor.getString(2) ?: "").split(FIELD_SEP)
            val type = cursor.getInt(3)
            val flds = JSONArray()
            fieldNames.forEach { flds.put(JSONObject().put("name", it)) }
            JSONObject()
                .put("id", mid)
                .put("name", name)
                .put("type", type)
                .put("flds", flds)
                .put("tmpls", readTemplates(mid))
        }
    }

    private fun readTemplates(mid: Long): JSONArray {
        val templates = JSONArray()
        val uri = Uri.withAppendedPath(
            Uri.withAppendedPath(MODEL_URI, mid.toString()), "templates"
        )
        try {
            resolver.query(
                uri,
                arrayOf("_id", "ord", "card_template_name", "question_format", "answer_format",
                    "browser_question_format", "browser_answer_format"),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    templates.put(
                        JSONObject()
                            .put("ord", cursor.getInt(1))
                            .put("name", cursor.getString(2) ?: "")
                            .put("qfmt", cursor.getString(3) ?: "")
                            .put("afmt", cursor.getString(4) ?: "")
                            .put("bqfmt", cursor.getString(5) ?: "")
                            .put("bafmt", cursor.getString(6) ?: "")
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.w("Template query failed for model $mid (${e.javaClass.simpleName}); upgrade AnkiDroid for template sync.")
        }
        return templates
    }

    override fun updateModel(modelJson: String) {
        val model = JSONObject(modelJson)
        val mid = model.optLong("id")
        val templates = model.optJSONArray("tmpls") ?: return
        for (i in 0 until templates.length()) {
            val tmpl = templates.getJSONObject(i)
            val ord = tmpl.optInt("ord", i)
            val uri = Uri.withAppendedPath(
                Uri.withAppendedPath(
                    Uri.withAppendedPath(MODEL_URI, mid.toString()), "templates"
                ),
                ord.toString(),
            )
            val cv = ContentValues().apply {
                put("card_template_name", tmpl.optString("name", "Card ${ord + 1}"))
                put("question_format", tmpl.optString("qfmt", ""))
                put("answer_format", tmpl.optString("afmt", ""))
                put("browser_question_format", tmpl.optString("bqfmt", ""))
                put("browser_answer_format", tmpl.optString("bafmt", ""))
            }
            try {
                resolver.update(uri, cv, null, null)
            } catch (e: Exception) {
                AppLog.w("Template update failed for model $mid ord $ord: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ cards
    override fun findCards(query: String, limit: Int): String {
        val cards = JSONArray()
        val selection = query.ifBlank { "deck:*" }
        resolver.query(
            CARD_URI,
            arrayOf("note_id", "ord"),
            selection, null, null,
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && (limit <= 0 || count < limit)) {
                cards.put(
                    JSONObject()
                        .put("nid", cursor.getLong(0))
                        .put("ord", cursor.getInt(1))
                )
                count++
            }
        }
        return cards.toString()
    }

    override fun answerCard(nid: Long, ord: Int, ease: Int) {
        val cv = ContentValues().apply {
            put("note_id", nid)
            put("ord", ord)
            put("answer_ease", ease.coerceIn(1, 4))
            put("time_taken", 0)
        }
        resolver.update(SCHEDULE_URI, cv, null, null)
    }

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private const val FIELD_SEP = "\u001f"
        private val NOTE_URI: Uri = Uri.parse("content://$AUTHORITY/notes")
        private val MODEL_URI: Uri = Uri.parse("content://$AUTHORITY/models")
        private val CARD_URI: Uri = Uri.parse("content://$AUTHORITY/cards")
        private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")
    }
}
