package com.mammyframe.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Keeps Mammy Frame open: the app is pinned (Home and Recents can't leave it), Back does nothing on
 * the home screen, and a small background service opens it again if it's ever left or stopped.
 * The only way out is "Force close Mammy Frame" in the developer settings.
 */
object Kiosk {
    /** Set by "Force close" until Mammy Frame is opened again. */
    @Volatile var forceClosed = false

    /** True while Mammy Frame is on screen. */
    @Volatile var onScreen = false

    /** Until this time (elapsedRealtime), Mammy Frame may be left: the folder picker or a settings page. */
    @Volatile var awayUntil = 0L

    /** Before opening the folder picker or one of Android's settings pages from the developer settings. */
    fun stepAside(a: Activity?) {
        awayUntil = SystemClock.elapsedRealtime() + 3 * 60_000
        runCatching { a?.stopLockTask() }
    }

    fun steppingAside() = SystemClock.elapsedRealtime() < awayUntil

    /** Called whenever Mammy Frame comes on screen. */
    fun onResume(a: Activity) {
        onScreen = true
        awayUntil = 0L
        if (forceClosed) return
        // With the full kiosk set-up (Mammy Frame is the tablet's "device owner"): lock the tablet
        // to Mammy Frame. Home and Recents do nothing and Android shows no messages about it.
        // Without it, there's no pinning (its "to unpin, touch & hold…" message comes up on every
        // button press); leaving with Home just brings Mammy Frame straight back (KeepOpenService).
        if (isOwner(a)) {
            runCatching {
                val dpm = a.getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(a, KioskAdmin::class.java)
                dpm.setLockTaskPackages(admin, arrayOf(a.packageName))
                // The power menu stays (to turn the tablet off or restart it).
                if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            }
            val am = a.getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { a.startLockTask() }
        } else {
            // Undo pinning from the earlier version.
            val am = a.getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED) runCatching { a.stopLockTask() }
        }
        runCatching { a.startForegroundService(Intent(a, KeepOpenService::class.java)) }
    }

    fun onPause() { onScreen = false }

    /** Whether the full kiosk set-up has been done (adb … dpm set-device-owner, see the README). */
    fun isOwner(c: Context) = runCatching {
        c.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(c.packageName)
    }.getOrDefault(false)

    /** Undoes the full kiosk set-up (so Mammy Frame can be uninstalled normally again). */
    fun removeOwner(a: Activity) {
        runCatching { a.stopLockTask() }
        runCatching { a.getSystemService(DevicePolicyManager::class.java).clearDeviceOwnerApp(a.packageName) }
    }

    /** "Force close Mammy Frame" in the developer settings. */
    fun forceClose(a: Activity) {
        forceClosed = true
        runCatching { a.stopLockTask() }
        a.stopService(Intent(a, KeepOpenService::class.java))
        a.finishAndRemoveTask()
    }

    fun open(c: Context) = runCatching {
        c.startActivity(
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}

/** Watches that Mammy Frame is on screen, and opens it again if it isn't (shown as a quiet notification). */
class KeepOpenService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var offSince = 0L

    private val check = object : Runnable {
        override fun run() {
            if (Kiosk.forceClosed) { stopSelf(); return }
            val now = SystemClock.elapsedRealtime()
            // Only while the screen is on: turning the screen off with the power button is fine
            // (Mammy Frame is still there when it comes back on).
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            if (Kiosk.onScreen || Kiosk.steppingAside() || !screenOn) offSince = 0L
            else if (offSince == 0L) offSince = now
            else if (now - offSince > 400) { Kiosk.open(this@KeepOpenService); offSince = now }
            handler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Kiosk.forceClosed) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Keeping the frame open", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Mammy Frame")
            .setContentText("Keeping the photo frame open")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(check)
        handler.post(check)
        // If Android stops it, it's started again (and opens Mammy Frame again).
        return START_STICKY
    }

    /** Swiped away from Recents (only possible if pinning is off): open it again. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Kiosk.forceClosed) handler.postDelayed({ Kiosk.open(this) }, 1_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "keepopen"
        const val ID = 8
    }
}

/** Only used for the optional full kiosk set-up (device owner); it has nothing else to do. */
class KioskAdmin : DeviceAdminReceiver()
