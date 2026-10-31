package dev.aihints.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun BatchScreen(
    state: UiState,
    modifier: Modifier,
    onStart: (String, Int, String, String, Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val provider = state.config["ai_provider"]?.toString().orEmpty()
    val model = (state.config["models"] as? Map<*, *>)?.get(provider)?.toString().orEmpty()

    var query by rememberSaveable { mutableStateOf("is:new") }
    var limit by rememberSaveable { mutableStateOf("20") }
    var providerField by rememberSaveable { mutableStateOf("") }
    var modelField by rememberSaveable { mutableStateOf("") }
    var skipExisting by rememberSaveable { mutableStateOf(true) }

    val batch = state.batch

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Batch generation",
            "Runs the add-on's AI pipeline over an Anki search and writes the same hints block to each note.",
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Anki search") },
                supportingText = { Text("e.g. is:new deck:Japanese -tag:ai-hints::skipped") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter { ch -> ch.isDigit() } },
                label = { Text("Limit") },
                supportingText = { Text("0 = no limit") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = providerField,
                onValueChange = { providerField = it },
                label = { Text("Provider override") },
                supportingText = { Text("Blank uses the configured provider ($provider)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelField,
                onValueChange = { modelField = it },
                label = { Text("Model override") },
                supportingText = { Text("Blank uses the configured model ($model)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skip notes that already have hints", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Only notes with no AI-Hints block are sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = skipExisting, onCheckedChange = { skipExisting = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = batch?.running != true,
                    onClick = {
                        onStart(
                            query.trim(),
                            limit.toIntOrNull() ?: 0,
                            providerField.trim(),
                            modelField.trim(),
                            skipExisting,
                        )
                    },
                ) { Text("Start") }
                if (batch?.running == true) {
                    if (batch.paused) {
                        OutlinedButton(onClick = onResume) { Text("Resume") }
                    } else {
                        OutlinedButton(onClick = onPause) { Text("Pause") }
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }

        if (batch != null) {
            SectionCard("Progress") {
                val total = batch.total.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (batch.done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${batch.done} / ${if (batch.total == 0) "?" else batch.total} cards",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill("ok ${batch.ok}", true)
                    if (batch.failed > 0) StatusPill("failed ${batch.failed}", false)
                    if (batch.skipped > 0) StatusPill("skipped ${batch.skipped}", false)
                }
                if (batch.running) {
                    Text(
                        if (batch.paused) "Paused" else "Running…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!batch.error.isNullOrBlank()) {
                    Text(
                        batch.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard("Notes", null) {
            Text(
                "Batch generation runs in the background while the bridge keeps serving the reviewer. " +
                    "Progress is also emitted as a batch event on the local event stream.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(2.dp))
        }
    }
}
