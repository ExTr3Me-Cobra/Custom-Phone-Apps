package com.ironlog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Badge pictures made in the background: a queue worked through by [BadgeGenService], which keeps going when you
 * leave the screen or the app (with a notification showing progress and a Stop button).
 */
object BadgeGen {
    val queue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    var current by mutableStateOf<String?>(null)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    /** A set whose level I was just remade while it has other levels: the screen offers to redo them. */
    var askRedo by mutableStateOf<String?>(null)
    val running get() = current != null || queue.isNotEmpty()

    /** Adds badges to make (in order) and starts the background work. */
    fun start(c: Context, badges: List<Badges.Badge>) {
        val new = badges.map { it.id }.filter { it !in queue && it != current }
        if (new.isEmpty()) return
        if (!running) { done = 0; total = 0 }
        queue.addAll(new); total += new.size
        c.startForegroundService(Intent(c, BadgeGenService::class.java))
    }

    fun stop(c: Context) {
        queue.clear()
        c.startService(Intent(c, BadgeGenService::class.java).setAction(BadgeGenService.STOP))
    }
}

class BadgeGenService : Service() {
    companion object {
        const val STOP = "stop"
        private const val CHANNEL = "badge_pictures"
        private const val ID = 41
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.load(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Making badge pictures", NotificationManager.IMPORTANCE_LOW))
        runCatching { startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        if (intent?.action == STOP) {
            BadgeGen.queue.clear()
            job?.cancel()
            BadgeGen.current = null
            BadgeGen.message = "Stopped. ${BadgeGen.done} pictures made."
            finish()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loadout:badges")
                .apply { acquire(6 * 3600 * 1000L) }
            job = scope.launch { run() }
        }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        while (true) {
            val id = BadgeGen.queue.poll() ?: break
            val b = Badges.all.firstOrNull { it.id == id } ?: continue
            BadgeGen.current = id
            BadgeGen.message = "Making ${b.name}… (${BadgeGen.done} of ${BadgeGen.total} done)"
            update(b.name)
            val hadOthers = b.tier == 0 && b.family.targets.indices.drop(1).any { BadgePictures.hasPicture("${b.family.id}_${it + 1}") }
            val err = makeBadgePicture(b, applicationContext)
            if (err == null) {
                BadgeGen.done++
                // Remade level I of a set that has other levels (and isn't redoing them already): offer to redo them.
                if (hadOthers && BadgeGen.queue.none { it.startsWith(b.family.id + "_") }) BadgeGen.askRedo = b.family.id
            } else {
                BadgeGen.message = "${b.name}: $err"
                // Nothing more can work without a key; and higher levels of a set whose level I failed can't be made.
                if (err.contains("API key")) BadgeGen.queue.clear()
                else if (b.tier == 0) BadgeGen.queue.removeAll { it.startsWith(b.family.id + "_") }
            }
        }
        BadgeGen.current = null
        if (BadgeGen.message?.contains(":") != true || BadgeGen.message?.startsWith("Making") == true)
            BadgeGen.message = "Made ${BadgeGen.done} of ${BadgeGen.total} pictures."
        finish()
    }

    private fun update(name: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, note(name)) }
    }

    private fun note(name: String = ""): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, BadgeGenService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Making badge pictures ${BadgeGen.done}/${BadgeGen.total}")
            .setContentText(name)
            .setProgress(BadgeGen.total.coerceAtLeast(1), BadgeGen.done, BadgeGen.total == 0)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun finish() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
