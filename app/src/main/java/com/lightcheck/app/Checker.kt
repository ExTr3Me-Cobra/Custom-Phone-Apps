package com.lightcheck.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** What a check found. */
sealed class Outcome {
    data object LightsOn : Outcome()
    data class LightsOff(val off: List<Bulb>) : Outcome()
    data class CouldNotCheck(val why: String) : Outcome()
}

object Checker {

    /**
     * Whether the saved lights answer. Not on the home Wi-Fi: [Outcome.CouldNotCheck]. Lights
     * that don't answer get two more tries 20 s apart (Wi-Fi lights miss a single ask now and
     * then), and one that moved to another address is looked for by its name.
     */
    suspend fun check(context: Context, log: (String) -> Unit): Outcome {
        val prefs = Prefs(context)
        val bulbs = prefs.bulbs
        if (bulbs.isEmpty()) return Outcome.CouldNotCheck("no lights saved yet (Find lights)")
        val state = Net.wifiState(context) ?: return Outcome.CouldNotCheck("the phone isn't on Wi-Fi")
        // On the home Wi-Fi? By its name when Android tells it, else by the router's fingerprint.
        if (prefs.homeSsid.isNotEmpty() && state.ssid != null) {
            if (state.ssid != prefs.homeSsid) return Outcome.CouldNotCheck("on \"${state.ssid}\", not the home Wi-Fi")
        } else if (prefs.homeRouter.isNotEmpty()) {
            val router = Net.routerFingerprint(context)
            if (router != prefs.homeRouter) return Outcome.CouldNotCheck("not on the home Wi-Fi (router: ${router.ifEmpty { "none" }})")
        }
        var off = bulbs
        repeat(3) { attempt ->
            if (attempt > 0) {
                log("Not answering: ${off.joinToString { it.label }}; trying again in 20 s")
                delay(20_000)
            }
            off = off.filterNot { Net.answers(context, it) }
            if (!shouldRing(prefs, bulbs, off)) return Outcome.LightsOn
        }
        // Maybe the router gave a light a new address: look for it by name.
        val named = off.filter { it.name.isNotEmpty() }
        if (named.isNotEmpty()) {
            log("Looking for ${named.joinToString { it.label }} at other addresses")
            val found = Net.scan(context)
            val moved = named.mapNotNull { bulb -> found.firstOrNull { it.name == bulb.name }?.let { bulb to it.ip } }
            if (moved.isNotEmpty()) {
                prefs.bulbs = prefs.bulbs.map { b -> moved.firstOrNull { it.first == b }?.let { b.copy(ip = it.second) } ?: b }
                log("Moved: ${moved.joinToString { "${it.first.label} → ${it.second}" }}")
                off = off.filterNot { b -> moved.any { it.first == b } }
            }
        }
        return if (shouldRing(prefs, bulbs, off)) Outcome.LightsOff(off) else Outcome.LightsOn
    }

    private fun shouldRing(prefs: Prefs, all: List<Bulb>, off: List<Bulb>) =
        if (prefs.alarmWhenAll) off.size == all.size else off.isNotEmpty()
}

/** Keeps the next check scheduled (exact, also when the phone is asleep). */
object Scheduler {
    private const val REQUEST = 1
    private const val SNOOZE_REQUEST = 2

    fun nextCheck(context: Context): Long? {
        val prefs = Prefs(context)
        if (prefs.days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.hour)
            set(Calendar.MINUTE, prefs.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(7) {
            if (cal.get(Calendar.DAY_OF_WEEK) in prefs.days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, CheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = nextCheck(context)
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        Prefs(context).log("Next check: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))}")
    }

    /** A check again in [minutes] (snooze), on top of the nightly one. */
    fun checkAgainIn(context: Context, minutes: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST, Intent(context, CheckReceiver::class.java).putExtra(CheckReceiver.EXTRA_SNOOZE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + minutes * 60_000L
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}

/** The scheduled time: run the check (in the service) and schedule the next one. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_SNOOZE, false)) Scheduler.schedule(context)
        CheckService.start(context, CheckService.ACTION_SCHEDULED)
    }

    companion object {
        const val EXTRA_SNOOZE = "snooze"
    }
}

/** After a restart, an update or a clock change, the next check is scheduled again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.schedule(context)
}
