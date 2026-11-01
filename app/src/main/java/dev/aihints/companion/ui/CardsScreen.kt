package dev.aihints.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aihints.companion.anki.AnkiPort

/**
 * Collection-wide actions that desktop exposes through the browser / deck
 * context menu: generate for a search, skip, unskip and clear.
 */
@Composable
fun CardsScreen(
    state: UiState,
    modifier: Modifier,
    onBulkAction: (String, String, Int) -> Unit,
    onBatch: (String, Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("100") }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    val canSearch = state.backend.capabilities.contains(AnkiPort.CAP_SEARCH)
    val batchRunning = state.batch?.running == true
    val limitValue = limit.toIntOrNull() ?: 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            "Find cards",
            "Same Anki search syntax as the desktop browser, e.g. deck:Japanese -tag:ai-hints::skipped.",
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Anki search (blank = all cards)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter { ch -> ch.isDigit() } },
                label = { Text("Limit") },
                supportingText = { Text("0 = no limit (the desktop “full scan” setting also applies)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!canSearch) {
                Text(
                    "This backend cannot search cards. Grant the AnkiDroid API permission to use these actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(
            "Generate",
            "Runs the same pipeline as desktop batch generation. Notes that already have hints are skipped.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canSearch && !batchRunning,
                    onClick = { onBatch(query.trim(), limitValue) },
                ) { Text("Generate for search") }
                if (batchRunning) {
                    StatusPill("batch running", false)
                }
            }
            if (batchRunning) {
                Text(
                    "Progress is on the Batch tab. You can pause or cancel it there while reviewing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            "Card actions",
            "Desktop browser context menu equivalents. Each action applies to every card matched by the search.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = canSearch,
                    onClick = { pending = "skip" },
                ) { Text("Skip AI") }
                OutlinedButton(
                    enabled = canSearch,
                    onClick = { pending = "unskip" },
                ) { Text("Unskip") }
                OutlinedButton(
                    enabled = canSearch,
                    onClick = { pending = "clear" },
                ) { Text("Clear hints") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = canSearch,
                    onClick = { pending = "tag_hinted" },
                ) { Text("Tag hinted notes") }
                Text(
                    "Adds the configured hint tag to notes that already have hints " +
                        "(desktop Advanced → Tag All Cards with Hints).",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Skip writes the empty marker (and the skip tag when enabled); Unskip removes it; " +
                    "Clear deletes the stored hints block.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Review", null) {
            Text(
                "While the bridge is running, open a card in AnkiDroid. The floating ✨ button shows " +
                    "the extra controls: Generate/Regenerate, model picker, hints, options, refresh, " +
                    "skip/unskip, clear, undo/redo and the app shortcut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val action = pending
    if (action != null) {
        val label = when (action) {
            "skip" -> "Skip AI generation"
            "unskip" -> "Allow AI generation again"
            "tag_hinted" -> "Tag notes with hints"
            else -> "Permanently clear hints"
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(label) },
            text = {
                Text(
                    "Apply to every card matching \"${query.ifBlank { "all cards" }}\" " +
                        "(limit ${if (limitValue == 0) "none" else limitValue.toString()})?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onBulkAction(query.trim(), action, limitValue)
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }
}
