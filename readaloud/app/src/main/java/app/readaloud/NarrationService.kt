package app.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps the app alive (and the CPU awake) while an
 * article is being read or exported with the screen off. All real work
 * happens in [Narrator]; this only mirrors its status in a notification.
 */
class NarrationService : Service(), Narrator.Listener {

    companion object {
        private const val CHANNEL = "narration"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_START = "app.readaloud.START"
        private const val ACTION_TOGGLE = "app.readaloud.TOGGLE"
        private const val ACTION_STOP = "app.readaloud.STOP"
        private const val EXTRA_EXPORT = "export"

        fun start(ctx: Context, export: Boolean) {
            val i = Intent(ctx, NarrationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_EXPORT, export)
            ctx.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false
    private var lastStartId = 0
    private var lastShown: Pair<Narrator.Phase, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val export = intent.getBooleanExtra(EXTRA_EXPORT, false)
                // Must happen promptly after startForegroundService().
                goForeground(build(Narrator.status), export)
                acquireWakeLock()
                if (!listening) {
                    listening = true
                    Narrator.addListener(this)
                }
                refresh()
            }
            ACTION_TOGGLE -> Narrator.togglePause()
            ACTION_STOP -> Narrator.stop()
            else -> refresh()
        }
        return START_NOT_STICKY
    }

    // Called on Narrator's worker threads; hop to the main thread.
    override fun onStatus(status: Narrator.Status) {
        main.post { refresh() }
    }

    private fun refresh() {
        val s = Narrator.status
        if (!s.active) {
            shutdown()
            return
        }
        val key = s.phase to s.index
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
    }

    private fun goForeground(n: Notification, export: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (export) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, n, type)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun build(s: Narrator.Status): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun action(act: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, NarrationService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when (s.phase) {
            Narrator.Phase.LOADING -> "Loading voice…"
            Narrator.Phase.EXPORTING -> "Saving audio · part ${s.index} of ${s.total}"
            Narrator.Phase.PAUSED -> "Paused · part ${s.index} of ${s.total}"
            else -> "Reading · part ${s.index} of ${s.total}"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifBlank { "ReadAloud" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setProgress(maxOf(1, s.total), s.index, s.phase == Narrator.Phase.LOADING)
        if (s.mode == Narrator.Mode.PLAY && s.phase != Narrator.Phase.LOADING) {
            val label = if (s.phase == Narrator.Phase.PAUSED) "Resume" else "Pause"
            b.addAction(Notification.Action.Builder(null, label, action(ACTION_TOGGLE, 1)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 2)).build())
        return b.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadAloud:narration").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // safety timeout: 4 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun shutdown() {
        if (listening) {
            listening = false
            Narrator.removeListener(this)
        }
        releaseWakeLock()
        lastShown = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Only stops if no newer start request is waiting to be delivered.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        if (listening) Narrator.removeListener(this)
        releaseWakeLock()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
