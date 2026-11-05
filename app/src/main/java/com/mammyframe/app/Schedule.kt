package com.mammyframe.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import java.util.Calendar

/** Whether the schedule says photos should be showing (ON) or the screen should be black (OFF). */
enum class Phase { ON, OFF }

object Schedule {
    const val EXTRA_PHASE = "com.mammyframe.app.PHASE"

    fun minuteOfDay(cal: Calendar = Calendar.getInstance()) =
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    /** Null when the schedule is off (or start and end are the same time). */
    fun phaseNow(s: Settings, cal: Calendar = Calendar.getInstance()): Phase? {
        if (!s.scheduleOn || s.startMinute == s.endMinute) return null
        val now = minuteOfDay(cal)
        val on = if (s.startMinute < s.endMinute) now >= s.startMinute && now < s.endMinute
        else now >= s.startMinute || now < s.endMinute // overnight, e.g. 8 PM to 2 AM
        return if (on) Phase.ON else Phase.OFF
    }

    /** The next start or end time from now, and which phase begins there. */
    private fun nextBoundary(s: Settings): Pair<Long, Phase> {
        val now = Calendar.getInstance()
        fun next(minute: Int): Calendar = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val start = next(s.startMinute)
        val end = next(s.endMinute)
        return if (start.before(end)) start.timeInMillis to Phase.ON else end.timeInMillis to Phase.OFF
    }

    private fun alarmIntent(c: Context, phase: Phase?): PendingIntent {
        val i = Intent(c, ScheduleReceiver::class.java)
        if (phase != null) i.putExtra(EXTRA_PHASE, phase.name)
        return PendingIntent.getBroadcast(
            c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Sets (or clears) the wake-up for the next start/end time, so it works even if the app is closed. */
    fun arm(c: Context, s: Settings = Prefs.load(c)) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (phaseNow(s) == null) {
            am.cancel(alarmIntent(c, null))
            return
        }
        val (at, phase) = nextBoundary(s)
        val pi = alarmIntent(c, phase)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pi)
        }
    }

    /** Opening the app by itself from the background needs "Display over other apps". */
    fun canOpenByItself(c: Context) = AndroidSettings.canDrawOverlays(c)

    fun canWakeOnTime(c: Context) = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun openApp(c: Context, phase: Phase) {
        c.startActivity(
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PHASE, phase.name)
        )
    }
}

/** Fires at each start and end time: opens the app in the right mode and sets the next wake-up. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val s = Prefs.load(c)
        Schedule.arm(c, s)
        val phase = Schedule.phaseNow(s) ?: return
        runCatching { Schedule.openApp(c, phase) }
    }
}

/** After a restart (or an app update), set the wake-up again and open the frame. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val s = Prefs.load(c)
        Schedule.arm(c, s)
        val phase = Schedule.phaseNow(s) ?: return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { Schedule.openApp(c, phase) }
    }
}
