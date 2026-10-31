package dev.aihints.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.aihints.companion.anki.ContentProviderPort
import dev.aihints.companion.core.AppLog
import dev.aihints.companion.service.BridgeService
import dev.aihints.companion.ui.AppRoot
import dev.aihints.companion.ui.AppViewModel
import dev.aihints.companion.ui.AiHintsTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        vm.refresh()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the bridge works without the notification; nothing else to do */ }

    private val openConfig = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importFrom(uri)
    }

    private val graph get() = (application as AiHintsApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (graph.prefs.bridgeEnabled) {
            runCatching { BridgeService.start(this) }
                .onFailure { AppLog.e("Could not start bridge service", it) }
        }

        setContent {
            AiHintsTheme {
                AppRoot(
                    vm = vm,
                    onRequestAnkiPermission = { requestAnkiAccess() },
                    onOpenAnkiDroid = { openAnkiDroid() },
                    onPickConfig = {
                        runCatching {
                            openConfig.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
                        }.onFailure { AppLog.e("No file picker available", it) }
                    },
                )
            }
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importFrom(uri)
    }

    private fun importFrom(uri: android.net.Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                vm.importConfig(reader.readText())
            } ?: error("could not open $uri")
        }.onFailure {
            AppLog.e("Config import failed", it)
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAnkiAccess() {
        val permission = ContentProviderPort.PERMISSION
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            vm.refresh()
            return
        }
        runCatching { requestAnkiPermission.launch(permission) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Install AnkiDroid first, then grant API access in its settings.",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    private fun openAnkiDroid() {
        val intent = packageManager.getLaunchIntentForPackage("com.ichi2.anki")
        if (intent == null) {
            Toast.makeText(this, "AnkiDroid is not installed", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent)
    }
}
