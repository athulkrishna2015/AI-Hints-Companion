package dev.aihints.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    vm: AppViewModel,
    onRequestAnkiPermission: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onPickConfig: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.consumeMessage()
    }

    LaunchedEffect(tab) {
        if (tab == 3) vm.refreshLogs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI-Hints", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.bridgeRunning) {
                                "Bridge on port ${state.bridgePort}"
                            } else {
                                "Bridge stopped"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.loading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(12.dp))
                    } else {
                        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    label = { Text("Status") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                    label = { Text("Batch") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("Logs") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> StatusScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onStartBridge = { vm.startBridge() },
                onStopBridge = { vm.stopBridge() },
                onRestartBridge = { vm.restartBridge() },
                onRequestAnkiPermission = onRequestAnkiPermission,
                onOpenAnkiDroid = onOpenAnkiDroid,
                onInstallTemplates = { vm.installTemplates(null) },
                onRemoveTemplates = { vm.removeTemplates(null) },
                onPickConfig = onPickConfig,
                onSettings = { tab = 2 },
            )
            1 -> BatchScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onStart = { query, limit, provider, model, skip -> vm.startBatch(query, limit, provider, model, skip) },
                onPause = { vm.pauseBatch() },
                onResume = { vm.resumeBatch() },
                onCancel = { vm.cancelBatch() },
            )
            2 -> SettingsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onValueChange = { key, value -> vm.setValue(key, value) },
                onSave = { vm.saveConfig() },
                onReset = { vm.resetConfig() },
                onImport = onPickConfig,
                onTestProvider = { provider, model -> vm.testProvider(provider, model) },
            )
            else -> LogsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onRefresh = { vm.refreshLogs() },
                onClear = { vm.clearLogs() },
            )
        }
    }
}

@Composable
private fun StatusScreen(
    state: UiState,
    modifier: Modifier,
    onStartBridge: () -> Unit,
    onStopBridge: () -> Unit,
    onRestartBridge: () -> Unit,
    onRequestAnkiPermission: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onInstallTemplates: () -> Unit,
    onRemoveTemplates: () -> Unit,
    onPickConfig: () -> Unit,
    onSettings: () -> Unit,
) {
    val installed = state.templates.count { it.installed }
    val total = state.templates.size
    val provider = state.config["ai_provider"]?.toString().orEmpty()
    val model = (state.config["models"] as? Map<*, *>)?.get(provider)?.toString().orEmpty()
    val keys = (state.config["api_keys"] as? Map<*, *>)?.entries
        ?.filter { (_, value) -> value?.toString()?.isNotBlank() == true }
        ?.map { it.key.toString() }
        ?.sorted()
        .orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Reviewer bridge", "Serves the add-on renderer to AnkiDroid's card WebView.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        if (state.bridgeRunning) "Running" else "Stopped",
                        state.bridgeRunning,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "127.0.0.1:${state.bridgePort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionRow {
                    if (state.bridgeRunning) {
                        OutlinedButton(onClick = onStopBridge) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Stop")
                        }
                        OutlinedButton(onClick = onRestartBridge) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Restart")
                        }
                    } else {
                        Button(onClick = onStartBridge) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Start bridge")
                        }
                    }
                }
            }
        }

        item {
            SectionCard("AnkiDroid connection", "The AnkiDroid API exposes notes, tags and card templates.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        if (state.backend.available) state.backend.label else "Not connected",
                        state.backend.available,
                    )
                }
                if (!state.backend.available) {
                    Text(
                        state.backend.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionRow {
                    if (!state.backend.available) {
                        Button(onClick = onRequestAnkiPermission) { Text("Grant API access") }
                    }
                    OutlinedButton(onClick = onOpenAnkiDroid) { Text("Open AnkiDroid") }
                }
                if (state.backend.available) {
                    KeyValue(
                        "Capabilities",
                        state.backend.capabilities.sorted().joinToString(", ").ifBlank { "notes only" },
                    )
                }
            }
        }

        item {
            SectionCard(
                "Add-on core",
                "Bundled renderer and AI engine, synced from the desktop add-on.",
            ) {
                KeyValue("Version", state.addonVersion.ifBlank { "—" }, mono = true)
                KeyValue("Provider", provider.ifBlank { "—" })
                KeyValue("Model", model.ifBlank { "—" })
                KeyValue(
                    "API keys",
                    if (keys.isEmpty()) "none" else keys.joinToString(", "),
                )
                ActionRow {
                    OutlinedButton(onClick = onPickConfig) { Text("Import meta.json") }
                    OutlinedButton(onClick = onSettings) { Text("Open settings") }
                }
            }
        }

        item {
            SectionCard(
                "Card templates",
                "$installed of $total note types have the AI-Hints block.",
            ) {
                ActionRow {
                    Button(onClick = onInstallTemplates) { Text("Install / repair") }
                    OutlinedButton(onClick = onRemoveTemplates) { Text("Remove") }
                }
                Text(
                    "Installing templates changes the note type, which triggers a full AnkiWeb sync — the same as on desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.templates.isNotEmpty()) {
            items(state.templates, key = { it.id }) { model ->
                TemplateRow(model)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TemplateRow(model: ModelTemplates) {
    SectionCard(model.name, if (model.isCloze) "Cloze" else "Standard") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (model.installed) "Installed" else "Missing", model.installed)
            if (model.bridge) {
                Spacer(Modifier.size(6.dp))
                StatusPill("Bridge JS", true)
            }
        }
        model.templates.forEach { template ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${template.ord + 1}. ${template.name}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    template.side,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                StatusPill(if (template.installed) "ok" else "—", template.installed)
            }
        }
    }
}
