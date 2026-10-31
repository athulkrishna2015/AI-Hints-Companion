package dev.aihints.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.aihints.companion.AiHintsApp
import dev.aihints.companion.MainActivity
import dev.aihints.companion.R
import dev.aihints.companion.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the loopback bridge alive while the user reviews in AnkiDroid.
 *
 * A foreground service is the only reliable way to stay reachable: AnkiDroid
 * reloads card HTML at arbitrary times and the WebView cannot wake us.
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val graph get() = (application as AiHintsApp).graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(getString(R.string.service_text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            val ok = graph.startBridge()
            val text = if (ok) {
                "Bridge on 127.0.0.1:${graph.prefs.bridgePort} · ${graph.backend.status().label}"
            } else {
                graph.bridgeError ?: "Bridge failed to start"
            }
            AppLog.i("Bridge service: $text")
            with(Dispatchers.Main) {
                if (ok) {
                    notify(text)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        graph.stopBridge()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.service_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "aihints_bridge"
        private const val NOTIFICATION_ID = 4311
        const val ACTION_STOP = "dev.aihints.companion.STOP_BRIDGE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
