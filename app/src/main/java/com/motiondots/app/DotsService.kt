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
            if (!prefs.on) prefs.manualOffAt = System.currentTimeMillis()
            refreshSwitches(this)
        }
        if (!prefs.on) {
            stopSelf()
            return START_NOT_STICKY
        }
        update()
        handler.removeCallbacks(stillCheck)
        handler.postDelayed(stillCheck, STILL_CHECK_MS)
        return START_STICKY
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Automatic mode: the phone hasn't moved at all for 4 minutes (put down on a nightstand,
     * a table…): the ride is over, whatever Play services says. A car waiting at lights still
     * shakes a little, which counts as moving.
     */
    private val stillCheck = object : Runnable {
        override fun run() {
            val motion = Motion.active
            if (prefs.on && prefs.auto && !Auto.paused(prefs) && motion != null) {
                val still = android.os.SystemClock.elapsedRealtime() - motion.lastMovingAt
                if (still > STILL_OFF_MS) {
                    Auto.log(this@DotsService, "Phone still for ${still / 60_000} min: dots off")
                    set(this@DotsService, false, manual = false)
                    return
                }
            }
            handler.postDelayed(this, STILL_CHECK_MS)
        }
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
        handler.removeCallbacks(stillCheck)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dots"
        const val ACTION_TOGGLE = "com.motiondots.app.TOGGLE"
        private const val STILL_CHECK_MS = 15_000L
        private const val STILL_OFF_MS = 4 * 60_000L

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

        /**
         * Switches the dots on or off from anywhere (app, tile, widget, notification: [manual];
         * a ride starting or ending: not). Turning them off by hand keeps automatic on/off away
         * for 5 minutes.
         */
        fun set(context: Context, on: Boolean, manual: Boolean = true) {
            Prefs(context).on = on
            if (manual && !on) Prefs(context).manualOffAt = System.currentTimeMillis()
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
