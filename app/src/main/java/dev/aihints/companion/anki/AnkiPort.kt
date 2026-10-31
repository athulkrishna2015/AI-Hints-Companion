package dev.aihints.companion.anki

/**
 * The small collection surface the Python engine needs.
 *
 * Composite values are JSON strings so the Chaquopy boundary stays a handful of
 * primitive conversions. [capabilities] lets the UI hide features a backend
 * cannot support instead of failing at the last moment.
 */
interface AnkiPort {

    val id: String
    val label: String

    fun isAvailable(): Boolean

    /** Note snapshot: nid, mid, model (with templates), fields, tags. */
    fun readNote(nid: Long): String?

    fun updateFields(nid: Long, fieldsJson: String)

    fun updateTags(nid: Long, tagsJson: String)

    /** All note types with their templates, as JSON. */
    fun listModels(): String

    fun updateModel(modelJson: String)

    /** Cards matching an Anki search, as `[{"nid":1,"ord":0}]`. */
    fun findCards(query: String, limit: Int): String

    fun answerCard(nid: Long, ord: Int, ease: Int)

    fun capabilities(): Set<String> = emptySet()

    companion object {
        const val CAP_TEMPLATES = "templates"
        const val CAP_TAGS = "tags"
        const val CAP_SEARCH = "search"
        const val CAP_SCHEDULE = "schedule"
    }
}

data class BackendStatus(
    val backend: String = "none",
    val label: String = "Not connected",
    val available: Boolean = false,
    val reason: String = "AnkiDroid API not available",
    val capabilities: Set<String> = emptySet(),
    val checkedAt: Long = 0L,
)
