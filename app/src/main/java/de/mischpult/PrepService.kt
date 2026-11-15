package de.mischpult

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.mischpult.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps offline gig prep (downloading and decoding a whole crate) alive when
 * the user leaves the app, with progress in a notification. The work itself
 * runs in [DjController]; this service only holds the foreground state.
 */
class PrepService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = MischpultApp.instance.controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Offline preparation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while crates are downloaded for offline use"
                    setShowBadge(false)
                }
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(controller.prep.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            controller.prep.collect { state ->
                if (state == null || !state.running) {
                    ServiceCompat.stopForeground(this@PrepService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    scope.cancel()
                } else {
                    nm.notify(NOTIFICATION_ID, build(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) controller.cancelPrep()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: DjController.PrepState?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Preparing “${state?.crate.orEmpty()}” for offline")
        .setContentText(
            if (state == null) "Starting…"
            else "${state.done} of ${state.total}" +
                (if (state.failed.isNotEmpty()) " · ${state.failed.size} failed" else "") +
                (state.current.firstOrNull()?.let { " · $it" } ?: "")
        )
        .setProgress(state?.total ?: 0, state?.done ?: 0, state == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(
            0, "Cancel",
            PendingIntent.getService(this, 2, Intent(this, PrepService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "prep"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "de.mischpult.CANCEL_PREP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PrepService::class.java))
        }
    }
}
