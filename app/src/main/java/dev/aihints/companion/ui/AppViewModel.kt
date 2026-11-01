package dev.aihints.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aihints.companion.AiHintsApp
import dev.aihints.companion.anki.BackendStatus
import dev.aihints.companion.core.AppLog
import dev.aihints.companion.core.toDeepMap
import dev.aihints.companion.core.toJsonObject
import dev.aihints.companion.service.BridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SchemaKey(
    val key: String,
    val widget: String,
    val group: String,
    val section: String,
    val description: String,
    val default: Any?,
)

data class TemplateState(
    val ord: Int,
    val name: String,
    val side: String,
    val installed: Boolean,
    val bridge: Boolean,
)

data class ModelTemplates(
    val id: Long,
    val name: String,
    val isCloze: Boolean,
    val installed: Boolean,
    val bridge: Boolean,
    val templates: List<TemplateState>,
)

data class BatchState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val current: Long? = null,
)

data class UiState(
    val loading: Boolean = true,
    val bridgeRunning: Boolean = false,
    val bridgePort: Int = 8766,
    val backend: BackendStatus = BackendStatus(),
    val addonVersion: String = "",
    val config: MutableMap<String, Any?> = mutableMapOf(),
    val schema: List<SchemaKey> = emptyList(),
    val templates: List<ModelTemplates> = emptyList(),
    val batch: BatchState? = null,
    val logs: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val fetchedProvider: String = "",
    val fetchedModels: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as AiHintsApp).graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var batchPolling = false

    init {
        loadSchema()
        refresh()
    }

    // --------------------------------------------------------------- loading
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                graph.py.ensureStarted()
                graph.backend.invalidate()
                val backend = graph.backend.status()
                val configJson = JSONObject(graph.py.call("get_config"))
                val templatesJson = JSONObject(graph.py.call("template_status", graph.prefs.bridgePort))
                val batchJson = JSONObject(graph.py.call("batch_status"))
                Triple(backend, configJson, templatesJson) to batchJson
            }
            val (triple, batchJson) = snapshot
            val (backend, configJson, templatesJson) = triple
            _state.value = _state.value.copy(
                loading = false,
                bridgeRunning = graph.bridgeRunning,
                bridgePort = graph.prefs.bridgePort,
                backend = backend,
                addonVersion = graph.py.addonVersion,
                config = configJson.optJSONObject("config")?.toDeepMap() ?: mutableMapOf(),
                templates = parseTemplates(templatesJson),
                batch = parseBatch(batchJson.optJSONObject("state")),
                error = if (graph.py.lastError != null && !graph.py.ready) graph.py.lastError else null,
            )
        }
    }

    private fun loadSchema() {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) {
                try {
                    val text = graph.appContext.assets.open("config_schema.json")
                        .bufferedReader().use { it.readText() }
                    val array = JSONObject(text).optJSONArray("keys") ?: JSONArray()
                    (0 until array.length()).map { i ->
                        val obj = array.getJSONObject(i)
                        SchemaKey(
                            key = obj.optString("key"),
                            widget = obj.optString("widget", "text"),
                            group = obj.optString("group", "Other"),
                            section = obj.optString("section", "Other"),
                            description = obj.optString("description", ""),
                            default = obj.opt("default"),
                        )
                    }
                } catch (e: Exception) {
                    AppLog.e("Config schema missing", e)
                    emptyList()
                }
            }
            _state.value = _state.value.copy(schema = keys)
        }
    }

    private fun parseTemplates(json: JSONObject): List<ModelTemplates> {
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).map { i ->
            val model = models.getJSONObject(i)
            val tpls = model.optJSONArray("templates") ?: JSONArray()
            ModelTemplates(
                id = model.optLong("id"),
                name = model.optString("name"),
                isCloze = model.optBoolean("isCloze"),
                installed = model.optBoolean("installed"),
                bridge = model.optBoolean("bridge"),
                templates = (0 until tpls.length()).map { j ->
                    val t = tpls.getJSONObject(j)
                    TemplateState(
                        ord = t.optInt("ord"),
                        name = t.optString("name"),
                        side = t.optString("side"),
                        installed = t.optBoolean("installed"),
                        bridge = t.optBoolean("bridge"),
                    )
                },
            )
        }
    }

    private fun parseBatch(obj: JSONObject?): BatchState? {
        if (obj == null) return null
        return BatchState(
            running = obj.optBoolean("running"),
            paused = obj.optBoolean("paused"),
            done = obj.optInt("done"),
            total = obj.optInt("total"),
            ok = obj.optInt("ok"),
            failed = obj.optInt("failed"),
            skipped = obj.optInt("skipped"),
            error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
            current = obj.optLong("current", -1).takeIf { it > 0 },
        )
    }

    // -------------------------------------------------------------- mutations
    fun setValue(key: String, value: Any?) {
        val config = LinkedHashMap(_state.value.config)
        config[key] = value
        _state.value = _state.value.copy(config = config, message = null, error = null)
    }

    fun saveConfig() {
        viewModelScope.launch {
            val payload = _state.value.config.toJsonObject().toString()
            val result = withContext(Dispatchers.IO) { JSONObject(graph.py.call("set_config", payload)) }
            if (result.optBoolean("ok")) {
                _state.value = _state.value.copy(
                    message = "Settings saved",
                    config = result.optJSONObject("config")?.toDeepMap() ?: _state.value.config,
                )
            } else {
                _state.value = _state.value.copy(error = result.optString("error", "Save failed"))
            }
        }
    }

    fun resetConfig() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { JSONObject(graph.py.call("reset_config")) }
            if (result.optBoolean("ok")) {
                _state.value = _state.value.copy(
                    message = "Reset to add-on defaults",
                    config = result.optJSONObject("config")?.toDeepMap() ?: mutableMapOf(),
                )
            }
        }
    }

    fun importConfig(text: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { JSONObject(graph.py.call("import_config", text)) }
            if (result.optBoolean("ok")) {
                val providers = result.optJSONArray("providersWithKeys")
                val names = (0 until (providers?.length() ?: 0)).map { providers!!.optString(it) }
                _state.value = _state.value.copy(
                    message = "Imported ${result.optInt("imported")} keys" +
                        if (names.isEmpty()) "" else " · keys: ${names.joinToString(", ")}",
                    config = result.optJSONObject("config")?.toDeepMap() ?: _state.value.config,
                )
            } else {
                _state.value = _state.value.copy(error = result.optString("error", "Import failed"))
            }
        }
    }

    fun exportConfig(): String = _state.value.config.toJsonObject().toString(2)

    fun installTemplates(modelId: Long? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Installing template blocks…")
            val result = withContext(Dispatchers.IO) {
                try {
                    JSONObject(
                        graph.py.call(
                            "install_templates",
                            graph.prefs.bridgePort,
                            modelId ?: -1L
                        )
                    )
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
            }
            if (result.optBoolean("ok")) {
                _state.value = _state.value.copy(
                    message = "Updated ${result.optInt("changedModels")} note type(s)",
                    templates = parseTemplates(
                        JSONObject(
                            withContext(Dispatchers.IO) {
                                graph.py.call("template_status", graph.prefs.bridgePort)
                            }
                        )
                    ),
                )
            } else {
                _state.value = _state.value.copy(error = result.optString("error", "Install failed"))
            }
        }
    }

    fun removeTemplates(modelId: Long? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    JSONObject(
                        graph.py.call(
                            "remove_templates",
                            modelId ?: -1L
                        )
                    )
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
            }
            if (result.optBoolean("ok")) {
                _state.value = _state.value.copy(
                    message = "Removed from ${result.optInt("changedModels")} note type(s)",
                    templates = parseTemplates(
                        JSONObject(
                            withContext(Dispatchers.IO) {
                                graph.py.call("template_status", graph.prefs.bridgePort)
                            }
                        )
                    ),
                )
            } else {
                _state.value = _state.value.copy(error = result.optString("error", "Remove failed"))
            }
        }
    }

    // ------------------------------------------------------------ collection ops
    /** Skip / unskip / clear every card matching an Anki search (desktop browser menu parity). */
    fun bulkAction(query: String, action: String, limit: Int) {
        val label = when (action) {
            "skip" -> "Skipped"
            "unskip" -> "Unskipped"
            "clear" -> "Cleared"
            "tag_hinted" -> "Tagged"
            else -> action
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "$label: working…", error = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    JSONObject(graph.py.call("bulk_action", query, action, limit))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
            }
            if (result.optBoolean("ok")) {
                val failed = result.optInt("failed")
                _state.value = _state.value.copy(
                    message = "$label ${result.optInt("changed")} of ${result.optInt("total")} card(s)" +
                        if (failed > 0) " · $failed failed" else "",
                    error = result.optJSONArray("errors")
                        ?.takeIf { it.length() > 0 }
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() },
                )
            } else {
                _state.value = _state.value.copy(
                    error = result.optString("error", "$label failed"),
                )
            }
        }
    }

    // ------------------------------------------------------------------ models
    fun fetchModels(provider: String) {
        if (provider.isBlank()) {
            _state.value = _state.value.copy(error = "Choose a provider first")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                fetchingModels = true,
                fetchedProvider = provider,
                fetchedModels = emptyList(),
                error = null,
            )
            val result = withContext(Dispatchers.IO) {
                try {
                    JSONObject(graph.py.call("fetch_models", provider))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
            }
            val models = result.optJSONArray("models")
            val names = (0 until (models?.length() ?: 0)).map { models!!.optString(it) }
                .filter { it.isNotBlank() }
            _state.value = _state.value.copy(
                fetchingModels = false,
                fetchedModels = names,
                message = if (result.optBoolean("ok")) {
                    "${names.size} model(s) for $provider"
                } else null,
                error = if (result.optBoolean("ok")) null else result.optString("error", "Fetch failed"),
            )
        }
    }

    fun closeFetchedModels() {
        _state.value = _state.value.copy(fetchedProvider = "", fetchedModels = emptyList())
    }

    fun selectModel(provider: String, model: String) {
        val models = LinkedHashMap<String, Any?>()
        (_state.value.config["models"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key != null) models[key.toString()] = value
        }
        models[provider] = model
        setValue("models", models)
        closeFetchedModels()
        saveConfig()
    }

    // ------------------------------------------------------------------ batch
    fun startBatch(query: String, limit: Int, provider: String, model: String, skipExisting: Boolean) {        viewModelScope.launch {
            val payload = JSONObject()
                .put("query", query)
                .put("limit", limit)
                .put("provider", provider)
                .put("model", model)
                .put("skipExisting", skipExisting)
                .toString()
            val result = withContext(Dispatchers.IO) { JSONObject(graph.py.call("batch_start", payload)) }
            if (!result.optBoolean("ok")) {
                _state.value = _state.value.copy(error = result.optString("error", "Batch failed"))
                return@launch
            }
            pollBatch()
        }
    }

    private fun pollBatch() {
        if (batchPolling) return
        batchPolling = true
        viewModelScope.launch {
            try {
                while (true) {
                    val batch = withContext(Dispatchers.IO) {
                        parseBatch(JSONObject(graph.py.call("batch_status")).optJSONObject("state"))
                    }
                    _state.value = _state.value.copy(batch = batch)
                    if (batch == null || !batch.running) break
                    delay(700)
                }
            } finally {
                batchPolling = false
            }
        }
    }

    fun pauseBatch() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.py.call("batch_pause") }
        pollBatch()
    }

    fun resumeBatch() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.py.call("batch_resume") }
        pollBatch()
    }

    fun cancelBatch() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.py.call("batch_cancel") }
    }

    fun stopAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.py.call("cancel_all") }
            _state.value = _state.value.copy(message = "Stopped all AI work")
            pollBatch()
        }
    }

    // ------------------------------------------------------------------ bridge
    fun restartBridge() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { graph.restartBridge() }
            _state.value = _state.value.copy(
                bridgeRunning = graph.bridgeRunning,
                message = if (ok) "Bridge restarted" else null,
                error = if (ok) null else graph.bridgeError,
            )
        }
    }

    fun stopBridge() {
        BridgeService.stop(graph.appContext)
        graph.stopBridge()
        _state.value = _state.value.copy(bridgeRunning = false, message = "Bridge stopped")
    }

    fun startBridge() {
        BridgeService.start(graph.appContext)
        viewModelScope.launch {
            delay(600)
            refresh()
        }
    }

    // -------------------------------------------------------------------- logs
    fun refreshLogs() {
        viewModelScope.launch {
            val fileLines = withContext(Dispatchers.IO) {
                val json = JSONObject(graph.py.call("logs_tail", 500))
                val arr = json.optJSONArray("lines") ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it).trimEnd() }
            }
            val live = AppLog.entries.value.map { it.format() }
            _state.value = _state.value.copy(logs = (live + fileLines).takeLast(800))
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.py.call("clear_logs") }
            AppLog.clear()
            _state.value = _state.value.copy(logs = emptyList(), message = "Logs cleared")
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    fun testProvider(provider: String, model: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Testing $provider…")
            val result = withContext(Dispatchers.IO) { JSONObject(graph.py.call("test_provider", provider, model)) }
            _state.value = if (result.optBoolean("ok")) {
                _state.value.copy(message = "$provider responded with usable hints")
            } else {
                _state.value.copy(error = "$provider failed: ${result.optString("error", "unknown")}")
            }
        }
    }
}
