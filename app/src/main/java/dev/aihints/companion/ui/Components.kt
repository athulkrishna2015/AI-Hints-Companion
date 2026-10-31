package dev.aihints.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun StatusPill(text: String, positive: Boolean) {
    val bg = if (positive) Color(0xFF1B7F4B) else Color(0xFFB45309)
    Box(
        Modifier
            .background(bg.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = bg,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

@Composable
fun ActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun ExpandableHeader(title: String, subtitle: String?, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
    }
}

/**
 * Generic editor for one add-on config key. The widget comes from the schema
 * generated out of `config.json` + the add-on docs.
 */
@Composable
fun ConfigField(
    schema: SchemaKey,
    value: Any?,
    onChange: (Any?) -> Unit,
) {
    when (schema.widget) {
        "switch" -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(schema.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (schema.description.isNotBlank()) {
                    Text(
                        schema.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = value as? Boolean ?: false, onCheckedChange = onChange)
        }

        "number" -> NumberField(schema, value, integer = true, onChange = onChange)
        "decimal" -> NumberField(schema, value, integer = false, onChange = onChange)

        "list" -> CommittedField(
            schema = schema,
            value = (value as? List<*>)?.joinToString(", ") { it.toString() } ?: "",
            onChange = { text ->
                onChange(text.split(',').map { it.trim() }.filter { it.isNotBlank() })
            },
        )

        "string_map" -> MapEditor(schema, value, secret = false, onChange = onChange)
        "secret_map" -> MapEditor(schema, value, secret = true, onChange = onChange)
        "list_map" -> ListMapEditor(schema, value, onChange = onChange)

        "json" -> CommittedField(
            schema = schema,
            value = (value as? Map<*, *>)?.let { JSONObject(it.toJsonStringMap()).toString(2) } ?: "{}",
            multiline = true,
            onChange = { text ->
                try {
                    val parsed = JSONObject(text).toDeepMapPublic()
                    onChange(parsed)
                } catch (_: Exception) {
                    // Keep the invalid text in the field; do not commit.
                }
            },
        )

        "multiline" -> CommittedField(
            schema = schema,
            value = value?.toString() ?: "",
            multiline = true,
            onChange = onChange,
        )

        else -> CommittedField(
            schema = schema,
            value = value?.toString() ?: "",
            onChange = onChange,
        )
    }
}

@Composable
private fun NumberField(schema: SchemaKey, value: Any?, integer: Boolean, onChange: (Any?) -> Unit) {
    var text by remember(schema.key) { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) {
        val external = value?.toString() ?: ""
        if (external != text) text = external
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            if (integer) newText.toIntOrNull()?.let(onChange)
            else newText.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(schema.key) },
        supportingText = if (schema.description.isNotBlank()) {
            { Text(schema.description, style = MaterialTheme.typography.bodySmall) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CommittedField(
    schema: SchemaKey,
    value: String,
    onChange: (String) -> Unit,
    multiline: Boolean = false,
) {
    var text by remember(schema.key) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != text && !multiline) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(schema.key) },
        supportingText = if (schema.description.isNotBlank()) {
            { Text(schema.description, style = MaterialTheme.typography.bodySmall) }
        } else null,
        singleLine = !multiline,
        minLines = if (multiline) 4 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MapEditor(schema: SchemaKey, value: Any?, secret: Boolean, onChange: (Any?) -> Unit) {
    val map = (value as? Map<*, *>) ?: emptyMap<Any?, Any?>()
    var showAll by remember(schema.key) { mutableStateOf(false) }
    val entries = map.entries.mapNotNull { entry ->
        entry.key?.toString()?.let { it to entry.value }
    }
    val filled = entries.filter { (_, v) -> v?.toString()?.isNotBlank() == true }
    val visible = if (showAll) entries else filled.take(PREVIEW_LIMIT)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(schema.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (schema.description.isNotBlank()) {
                    Text(
                        schema.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entries.size > PREVIEW_LIMIT) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Hide empty" else "Show all (${entries.size})")
                }
            }
        }
        if (!showAll && filled.isEmpty()) {
            Text(
                if (secret) "No keys set yet — tap Show all to add one."
                else "Nothing set yet — tap Show all to pick a value.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visible.forEach { (key, rawValue) ->
            var text by remember(schema.key, key) { mutableStateOf(rawValue?.toString() ?: "") }
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    val copy = LinkedHashMap<String, Any?>()
                    map.forEach { (k, v) -> if (k != null) copy[k.toString()] = v }
                    copy[key] = newText
                    onChange(copy)
                },
                label = { Text(key) },
                singleLine = !secret,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val PREVIEW_LIMIT = 8

@Composable
private fun ListMapEditor(schema: SchemaKey, value: Any?, onChange: (Any?) -> Unit) {
    val map = (value as? Map<*, *>) ?: emptyMap<Any?, Any?>()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(schema.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        map.forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: return@forEach
            val joined = (rawValue as? List<*>)?.joinToString(", ") { it.toString() } ?: ""
            var text by remember(schema.key, key) { mutableStateOf(joined) }
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    val copy = LinkedHashMap<String, Any?>()
                    map.forEach { (k, v) -> if (k != null) copy[k.toString()] = v }
                    copy[key] = newText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    onChange(copy)
                },
                label = { Text(key) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Map<*, *>.toJsonStringMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    forEach { (k, v) -> if (k != null) out[k.toString()] = v }
    return out
}

private fun JSONObject.toDeepMapPublic(): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    keys().forEach { key ->
        out[key] = when (val v = opt(key)) {
            is JSONObject -> v.toDeepMapPublic()
            is org.json.JSONArray -> (0 until v.length()).map { i ->
                when (val item = v.opt(i)) {
                    is JSONObject -> item.toDeepMapPublic()
                    else -> item
                }
            }
            JSONObject.NULL -> null
            else -> v
        }
    }
    return out
}
