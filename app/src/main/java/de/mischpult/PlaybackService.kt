package de.mischpult

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.mischpult.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it [DjController]'s audio engine) alive while a
 * set is playing and the screen is off or another app is in front. The audio
 * itself never moves: the service only holds the foreground state and shows a
 * notification with per-deck play/pause.
 *
 * Started by [DjController] when a deck starts playing; stops itself after the
 * decks have been quiet for a while.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = MischpultApp.instance.controller
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        running = true
        scope.launch {
            var quietSince = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (controller.anyPlaying) quietSince = now
                // In the app the user sees the decks; outside it give them time to come back
                val grace = if (MischpultApp.instance.inForeground) 3_000L else 10 * 60_000L
                if (now - quietSince > grace) {
                    shutdown()
                    break
                }
                refreshNotification()
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_A -> toggle(0)
            ACTION_TOGGLE_B -> toggle(1)
            ACTION_STOP -> {
                controller.stopAll()
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    /** Swiped away in the app switcher: the user closed the app, so the music stops too. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.stopAll()
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    private fun toggle(i: Int) {
        val deck = controller.deck(i)
        if (deck.track != null) deck.playing = !deck.playing
        refreshNotification(force = true)
    }

    /**
     * Stops the refresh loop *before* leaving the foreground. Otherwise the loop
     * can re-post the notification after the system removed it, leaving an
     * ongoing notification behind that the user cannot dismiss.
     */
    private fun shutdown() {
        running = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    private fun refreshNotification(force: Boolean = false) {
        if (!running) return
        val text = describe()
        if (!force && text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun describe(): String = (0..1).joinToString("  ·  ") { i ->
        val ui = controller.decks[i].value
        val state = if (controller.deck(i).playing) "▶" else "❚❚"
        "${"AB"[i]} $state ${ui.track?.title ?: "—"}"
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String, label: String) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE),
        )
        val a = controller.deck(0)
        val b = controller.deck(1)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mischpult")
            .setContentText(describe())
            .setStyle(NotificationCompat.BigTextStyle().bigText(describe().replace("  ·  ", "\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_TOGGLE_A, if (a.playing) "Pause A" else "Play A"))
            .addAction(action(ACTION_TOGGLE_B, if (b.playing) "Pause B" else "Play B"))
            .addAction(action(ACTION_STOP, "Stop"))
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a set is playing"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE_A = "de.mischpult.TOGGLE_A"
        private const val ACTION_TOGGLE_B = "de.mischpult.TOGGLE_B"
        private const val ACTION_STOP = "de.mischpult.STOP"

        @Volatile var running = false
            private set

        /** Removes a notification left behind by a process that died while playing. */
        fun clearStale(context: Context) {
            if (!running) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        fun start(context: Context) {
            if (running) return
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
