package dev.aihints.companion.core

import org.json.JSONArray
import org.json.JSONObject

/** JSON <-> plain Kotlin collections, for editable Compose state. */
fun JSONObject.toDeepMap(): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    keys().forEach { key -> out[key] = deepValue(opt(key)) }
    return out
}

private fun deepValue(value: Any?): Any? = when (value) {
    is JSONObject -> value.toDeepMap()
    is JSONArray -> (0 until value.length()).map { deepValue(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}

fun Map<String, Any?>.toJsonObject(): JSONObject {
    val out = JSONObject()
    forEach { (key, value) -> out.put(key, jsonValue(value)) }
    return out
}

fun jsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> {
        val obj = JSONObject()
        value.forEach { (k, v) -> if (k != null) obj.put(k.toString(), jsonValue(v)) }
        obj
    }
    is List<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
    is Number, is Boolean, is String -> value
    else -> value.toString()
}

fun prettyJson(value: Map<String, Any?>): String = value.toJsonObject().toString(2)

fun parseJsonOrNull(text: String): JSONObject? = try {
    JSONObject(text)
} catch (e: Exception) {
    null
}
