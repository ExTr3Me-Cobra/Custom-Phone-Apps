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

/**
 * Keeps a "Workout in Progress" notification with a running session timer until the workout
 * ends. Also keeps the app alive so the rest timer always sounds.
 */
class WorkoutService : Service() {
    companion object {
        private const val CHANNEL = "workout"
        private const val ID = 41

        fun start(c: Context) {
            if (Store.active.value == null) return
            runCatching { c.startForegroundService(Intent(c, WorkoutService::class.java)) }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WorkoutService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.load(applicationContext)
        val w = Store.active.value
        if (w == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openWorkout", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Workout in Progress")
            .setContentText(if (w.paused > 0) "${w.name} · Paused at ${Units.clock(Workouts.activeMs(w))}" else w.name)
            // Counts the same time as Duration in the app (pauses and long idle stretches left out).
            .setUsesChronometer(w.paused == 0L)
            .setWhen(System.currentTimeMillis() - Workouts.activeMs(w))
            .setShowWhen(w.paused == 0L)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .build()
        runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        return START_STICKY
    }
}
