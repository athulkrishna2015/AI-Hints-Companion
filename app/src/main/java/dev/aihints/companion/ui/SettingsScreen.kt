package dev.aihints.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aihints.companion.core.prettyJson
import org.json.JSONObject

private val ESSENTIALS = listOf(
    "ai_provider",
    "api_keys",
    "models",
    "generate_hints_enabled",
    "generate_options_enabled",
    "auto_show_hints",
    "auto_show_options",
    "auto_show_hints_answer",
    "auto_show_options_answer",
    "show_on_card",
    "manual_show_hints",
    "manual_show_options",
    "options_before_hints",
    "options_count",
    "rate_good_on_correct",
    "rate_again_on_wrong",
    "tag_hinted_notes",
    "tag_skipped_notes",
    "hint_tag",
    "skip_tag",
    "provider_priority",
    "global_model_priority",
    "system_prompt",
    "additional_system_instructions",
    "debug_logging",
)

@Composable
fun SettingsScreen(
    state: UiState,
    modifier: Modifier,
    onValueChange: (String, Any?) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onImport: () -> Unit,
    onTestProvider: (String, String) -> Unit,
    onFetchModels: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onCloseModels: () -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var rawText by remember(state.config) { mutableStateOf(prettyJson(state.config)) }
    var rawError by remember { mutableStateOf<String?>(null) }

    val schemaByKey = remember(state.schema) { state.schema.associateBy { it.key } }
    val provider = state.config["ai_provider"]?.toString().orEmpty()
    val model = (state.config["models"] as? Map<*, *>)?.get(provider)?.toString().orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == 0,
                    onClick = { section = 0 },
                    label = { Text("Essentials") },
                )
                FilterChip(
                    selected = section == 1,
                    onClick = { section = 1 },
                    label = { Text("All keys") },
                )
                FilterChip(
                    selected = section == 2,
                    onClick = { section = 2 },
                    label = { Text("Raw JSON") },
                )
            }
        }

        if (section == 0) {
            item {
                SectionCard("Provider", "Every add-on provider is available; keys are stored on this device only.") {
                    KeyValue("Provider", provider.ifBlank { "—" })
                    KeyValue("Model", model.ifBlank { "—" })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Import meta.json") }
                        OutlinedButton(onClick = { onTestProvider(provider, model) }) {
                            Text("Test provider")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = provider.isNotBlank() && !state.fetchingModels,
                            onClick = { onFetchModels(provider) },
                        ) {
                            Text(if (state.fetchingModels) "Fetching…" else "Fetch models")
                        }
                        Text(
                            "Fetch asks the provider for its model list, like the desktop provider tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(ESSENTIALS.filter { it in state.config }) { key ->
                val schema = schemaByKey[key] ?: SchemaKey(key, "text", "Essentials", "Essentials", "", null)
                SectionCard(schema.key, null) {
                    ConfigField(
                        schema = schema,
                        value = state.config[key],
                        onChange = { value -> onValueChange(key, value) },
                    )
                }
            }
        }

        if (section == 1) {
            val grouped = state.schema.groupBy { it.group.ifBlank { "Other" } }
            grouped.forEach { (group, keys) ->
                item(key = "group-$group") {
                    val expanded = group in expandedGroups
                    ExpandableHeader(
                        title = group,
                        subtitle = "${keys.size} settings",
                        expanded = expanded,
                        onToggle = {
                            expandedGroups = if (expanded) expandedGroups - group else expandedGroups + group
                        },
                    )
                    if (expanded) {
                        HorizontalDivider()
                    }
                }
                if (group in expandedGroups) {
                    items(keys, key = { "key-${it.key}" }) { schema ->
                        Column(Modifier.fillMaxWidth()) {
                            ConfigField(
                                schema = schema,
                                value = state.config[schema.key],
                                onChange = { value -> onValueChange(schema.key, value) },
                            )
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        if (section == 2) {
            item {
                SectionCard(
                    "Raw configuration",
                    "The full add-on config, exactly as the AI core reads it.",
                ) {
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { text ->
                            rawText = text
                            rawError = try {
                                JSONObject(text)
                                null
                            } catch (e: Exception) {
                                e.message ?: "invalid JSON"
                            }
                        },
                        label = { Text("config.json") },
                        isError = rawError != null,
                        supportingText = if (rawError != null) {
                            { Text(rawError!!, style = MaterialTheme.typography.bodySmall) }
                        } else null,
                        minLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = rawError == null,
                            onClick = {
                                val parsed = JSONObject(rawText)
                                parsed.keys().forEach { key -> onValueChange(key, parsed.opt(key)) }
                                onSave()
                            },
                        ) { Text("Apply raw JSON") }
                        OutlinedButton(onClick = { rawText = prettyJson(state.config); rawError = null }) {
                            Text("Revert")
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Save settings") }
                OutlinedButton(onClick = onReset) { Text("Reset defaults") }
            }
        }

        item {
            Text(
                "Settings are stored in the app's private directory and are never bundled into the APK.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
        }
    }

    if (state.fetchedProvider.isNotBlank()) {
        AlertDialog(
            onDismissRequest = onCloseModels,
            title = { Text("Models · ${state.fetchedProvider}") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.fetchedModels.isEmpty()) {
                        Text(
                            if (state.fetchingModels) "Fetching…" else "No models were returned.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.fetchedModels.forEach { name ->
                        TextButton(
                            onClick = { onSelectModel(state.fetchedProvider, name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onCloseModels) { Text("Close") }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.fetchingModels,
                    onClick = { onFetchModels(state.fetchedProvider) },
                ) { Text("Refresh") }
            },
        )
    }
}
