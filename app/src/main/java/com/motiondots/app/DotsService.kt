package com.motiondots.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Keeps the dots running while they're on (with a notification that can stop them). Draws them
 * itself as a normal overlay, unless the accessibility service is set to and running, which
 * then draws them instead (over everything).
 */
class DotsService : Service() {
    private lateinit var prefs: Prefs
    private var overlay: Overlay? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "use_a11y" || key == "a11y_running") update()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (intent?.action == ACTION_TOGGLE) {
            // From the widget.
            prefs.on = !prefs.on
            prefs.autoStarted = false
            refreshSwitches(this)
        }
        if (!prefs.on) {
            stopSelf()
            return START_NOT_STICKY
        }
        update()
        return START_STICKY
    }

    private fun update() {
        val a11yDraws = prefs.useAccessibility && DotsAccessibilityService.running
        if (a11yDraws || !Settings.canDrawOverlays(this)) {
            overlay?.hide()
            overlay = null
        } else {
            if (overlay == null) overlay = Overlay(this, accessibility = false)
            overlay?.show()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Motion dots are on")
            .setContentText("Tap to adjust them")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dots"
        const val ACTION_TOGGLE = "com.motiondots.app.TOGGLE"

        /** The tile and the widget show the current state. */
        fun refreshSwitches(context: Context) {
            TileService.requestListeningState(context, android.content.ComponentName(context, DotsTile::class.java))
            DotsWidget.refresh(context)
        }

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Dots are on", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            )
        }

        /** Switches the dots on or off from anywhere (app, tile, notification). */
        fun set(context: Context, on: Boolean) {
            Prefs(context).on = on
            Prefs(context).autoStarted = false
            val intent = Intent(context, DotsService::class.java)
            if (on) runCatching { context.startForegroundService(intent) }.onFailure { Auto.log(context, "Couldn't start the dots: ${it.message}") }
            else context.stopService(intent)
            refreshSwitches(context)
        }
    }
}

/** The notification's "Turn off". */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DotsService.set(context, false)
}
