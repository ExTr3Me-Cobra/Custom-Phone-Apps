package com.mammyframe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the AI check going in the background (with a progress notification) until it's finished. */
class RotateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AI rotate", NotificationManager.IMPORTANCE_LOW)
        )
        // Android 14+: "special use" has no daily time limit (a full check can take many hours).
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID, build(0, 0), type)
        if (!started) {
            started = true
            // Keep the processor working while checking, even if the screen goes off.
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MammyFrame:airotate")
                .apply { setReferenceCounted(false) }
            scope.launch {
                var shown = -1
                AiRotate.status.collectLatest { s ->
                    if (!s.running) {
                        stopSelf()
                    } else {
                        // Renewed as long as checking goes on; lapses by itself if anything gets stuck.
                        wake?.acquire(10 * 60 * 1000L)
                        if (s.done != shown) { shown = s.done; nm.notify(ID, build(s.done, s.total)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Mammy Frame is checking photos")
            .setContentText(if (total > 0) "$done of $total" else "Getting started…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    /** Android can still stop long work on some versions; stop tidily so nothing is lost. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AiRotate.stop(
            "Android paused checking after its time limit. Tap Check new photos to carry on " +
                "(photos already checked aren't checked again)."
        )
        stopSelf()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "airotate"
        private const val ID = 7
    }
}
