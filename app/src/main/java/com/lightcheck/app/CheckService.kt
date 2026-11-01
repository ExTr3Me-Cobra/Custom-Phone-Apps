package com.lightcheck.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a check (scheduled, snoozed or "Check now") and, if the lights are off, rings the alarm:
 * a full-screen alarm with sound and vibration until it's dismissed or snoozed. "Test alarm"
 * rings it straight away.
 */
class CheckService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var checking = false
    private var ringingNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SCHEDULED
        if (action == ACTION_STOP) {
            stopRinging()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val prefs = Prefs(this)
        // Android needs startForeground after every start; while ringing, keep the alarm's notification.
        startForeground(ID_WORKING, ringingNotification ?: working("Checking the living-room lights…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (action == ACTION_TEST) {
            prefs.log("Test alarm")
            ring("Test alarm", "This is how the alarm sounds.")
            return START_NOT_STICKY
        }
        if (checking || ringingNotification != null) {
            // One check at a time: a second one would be cancelled when the first finished.
            val why = if (checking) "a check is already running" else "the alarm is ringing"
            prefs.log("${if (action == ACTION_MANUAL) "Check now" else "Scheduled check"} ignored: $why")
            if (action == ACTION_MANUAL) {
                android.widget.Toast.makeText(this, if (checking) "Already checking…" else "The alarm is ringing", android.widget.Toast.LENGTH_SHORT).show()
            }
            return START_NOT_STICKY
        }
        checking = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightCheck:check").apply { acquire(5 * 60_000L) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LightCheck:check").apply { acquire() }
        val ringAt = intent?.getLongExtra(EXTRA_RING_AT, 0L) ?: 0L
        scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            prefs.log("── ${when (action) { ACTION_MANUAL -> "Check now"; ACTION_SCHEDULED -> "Scheduled check"; else -> action }} ──")
            prefs.log(Diagnostics.summary(this@CheckService))
            if (ringAt > 0) prefs.log("Alarm time ${clock(ringAt)}; checking early")
            val outcome = try {
                withContext(Dispatchers.Default) { Checker.check(this@CheckService) { prefs.log(it) } }
            } catch (e: CancellationException) {
                prefs.log("Check stopped (the app was closed by Android) after ${seconds(started)}")
                checking = false
                throw e
            } catch (e: Throwable) {
                prefs.log("Error: ${e.stackTraceToString().lines().take(14).joinToString("\n    ")}")
                Outcome.CouldNotCheck("something went wrong (${e.javaClass.simpleName}: ${e.message})")
            }
            checking = false
            releaseLocks()
            prefs.log("Check took ${seconds(started)}")
            when (outcome) {
                is Outcome.LightsOn -> {
                    prefs.lastResult = "Lights on: all answered (${now()})"
                    prefs.log("Result: lights on")
                    // Scheduled and snoozed checks stay silent when the lights are on.
                    if (action == ACTION_MANUAL) notifyInfo("Lights are on", "All living-room lights answered.")
                    stopSelf()
                }
                is Outcome.CouldNotCheck -> {
                    prefs.lastResult = "Couldn't check: ${outcome.why} (${now()})"
                    prefs.log("Result: couldn't check: ${outcome.why}")
                    // Told at the set time, like the alarm would be.
                    waitForRingTime(ringAt)
                    notifyInfo("Couldn't check the living-room lights", "Not verified: ${outcome.why}.")
                    stopSelf()
                }
                is Outcome.LightsOff -> {
                    val which = outcome.off.joinToString { it.label }
                    prefs.lastResult = "Lights off: $which (${now()})"
                    if (untilRingTime(ringAt)) prefs.log("Result: lights off ($which); ringing at ${clock(ringAt)}")
                    else prefs.log("Result: lights off ($which); ringing")
                    waitForRingTime(ringAt)
                    ring("Living-room lights are off", "Not answering: $which. The switch may be off.")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun clock(at: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))

    private fun untilRingTime(ringAt: Long) = ringAt - System.currentTimeMillis() in 1..3 * 60_000L

    /** A scheduled check finishes early: hold the result until the set time (busy meanwhile). */
    private suspend fun waitForRingTime(ringAt: Long) {
        if (!untilRingTime(ringAt)) return
        val wait = ringAt - System.currentTimeMillis()
        checking = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightCheck:wait").apply { acquire(wait + 30_000) }
        delay(wait)
        releaseLocks()
        checking = false
    }

    private fun seconds(since: Long) = "${(android.os.SystemClock.elapsedRealtime() - since) / 100 / 10.0} s"

    private fun now() = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

    private fun ring(title: String, text: String) {
        val prefs = Prefs(this)
        val full = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java).putExtra("title", title).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dismiss", action(AlarmActionReceiver.DISMISS)).build())
            .addAction(Notification.Action.Builder(null, "Snooze ${prefs.snoozeMinutes} min", action(AlarmActionReceiver.SNOOZE)).build())
            .build()
        ringingNotification = notification
        startForeground(ID_WORKING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        runCatching { startActivity(Intent(this, AlarmActivity::class.java).putExtra("title", title).putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        playSound(prefs)
        if (prefs.vibrate) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
        // Rings until dismissed (at most 10 minutes), or once for about a minute.
        scope.launch {
            delay(if (prefs.ringUntilDismissed) 10 * 60_000L else 60_000L)
            prefs.log("Alarm timed out (nobody dismissed it)")
            stopRinging()
        }
    }

    private fun playSound(prefs: Prefs) {
        val audio = getSystemService(AudioManager::class.java)
        val asAlarm = prefs.ringInSilent
        if (!asAlarm && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            prefs.log("No sound: the phone is on silent/vibrate and \"Ring in silent mode\" is off")
            return
        }
        val stream = if (asAlarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        savedVolume = audio.getStreamVolume(stream)
        runCatching { audio.setStreamVolume(stream, (audio.getStreamMaxVolume(stream) * prefs.volume / 100).coerceAtLeast(1), 0) }
        val uri = prefs.soundUri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        prefs.log("Ringing: ${if (asAlarm) "alarm" else "notification"} volume ${prefs.volume}% (was $savedVolume of ${audio.getStreamMaxVolume(stream)}), sound ${if (prefs.soundUri.isEmpty()) "default" else uri}, vibrate ${prefs.vibrate}, ${if (prefs.ringUntilDismissed) "until dismissed (max 10 min)" else "about 1 min"}")
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (asAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@CheckService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { prefs.log("Couldn't play the alarm sound: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    private fun stopRinging() {
        if (ringingNotification != null) Prefs(this).log("Alarm stopped")
        ringingNotification = null
        player?.runCatching { stop(); release() }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        if (savedVolume >= 0) {
            val audio = getSystemService(AudioManager::class.java)
            val stream = if (Prefs(this).ringInSilent) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
            runCatching { audio.setStreamVolume(stream, savedVolume, 0) }
            savedVolume = -1
        }
        sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(packageName))
        if (!checking) stopSelf()
    }

    private fun action(which: String) = PendingIntent.getBroadcast(
        this, which.hashCode(), Intent(this, AlarmActionReceiver::class.java).setAction(which),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun working(text: String) = Notification.Builder(this, CHANNEL_WORKING)
        .setSmallIcon(R.drawable.ic_stat)
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        .setContentTitle(text)
        .build()

    private fun notifyInfo(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(
            ID_INFO,
            Notification.Builder(this, CHANNEL_INFO).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
        )
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        player?.runCatching { stop(); release() }
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SCHEDULED = "com.lightcheck.app.SCHEDULED"
        const val ACTION_MANUAL = "com.lightcheck.app.MANUAL"
        const val ACTION_TEST = "com.lightcheck.app.TEST"
        const val ACTION_STOP = "com.lightcheck.app.STOP"
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_INFO = "info"
        const val CHANNEL_WORKING = "working_quiet"
        const val EXTRA_RING_AT = "ring_at"
        private const val ID_WORKING = 1
        private const val ID_INFO = 2

        fun start(context: Context, action: String, ringAt: Long = 0L) {
            context.startForegroundService(Intent(context, CheckService::class.java).setAction(action).putExtra(EXTRA_RING_AT, ringAt))
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM, "Lights-off alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the app plays the chosen sound itself
                enableVibration(false)
                setBypassDnd(true)
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_INFO, "Couldn't check / results", NotificationManager.IMPORTANCE_DEFAULT))
            // Android requires a notification while the check runs; this one makes no sound, has no
            // status-bar icon and sits collapsed at the bottom of the shade. Gone when the check is.
            nm.deleteNotificationChannel("working")
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WORKING, "Checking (silent)", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        }
    }
}

/** The alarm's Dismiss and Snooze buttons (in the notification and on the alarm screen). */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (intent.action == SNOOZE) {
            prefs.log("Snoozed ${prefs.snoozeMinutes} min (the lights are checked again then)")
            Scheduler.checkAgainIn(context, prefs.snoozeMinutes)
        } else {
            prefs.log("Alarm dismissed")
        }
        context.startService(Intent(context, CheckService::class.java).setAction(CheckService.ACTION_STOP))
    }

    companion object {
        const val DISMISS = "com.lightcheck.app.DISMISS"
        const val SNOOZE = "com.lightcheck.app.SNOOZE"
    }
}
