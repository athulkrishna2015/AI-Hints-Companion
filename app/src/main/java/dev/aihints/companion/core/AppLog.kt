package dev.aihints.companion.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory log ring for the Logs screen. The authoritative log is the
 * add-on's rotating file (written by the vendored logger); this mirrors the
 * events the Kotlin side produces so a failure before Python starts is visible.
 */
object AppLog {

    private const val TAG = "AiHints"
    private const val LIMIT = 600

    data class Entry(val at: Long, val level: String, val message: String) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
            return "$time ${level.padEnd(5)} $message"
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun d(message: String) = add("D", message)

    fun i(message: String) = add("I", message)

    fun w(message: String) = add("W", message)

    fun e(message: String, error: Throwable? = null) {
        add("E", if (error == null) message else "$message: ${error.message ?: error.javaClass.simpleName}")
        Log.e(TAG, message, error)
    }

    private fun add(level: String, message: String) {
        Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, message)
        synchronized(this) {
            _entries.value = (_entries.value + Entry(System.currentTimeMillis(), level, message)).takeLast(LIMIT)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
