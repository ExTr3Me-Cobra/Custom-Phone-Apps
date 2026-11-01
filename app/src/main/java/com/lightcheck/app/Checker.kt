package com.lightcheck.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
     * that don't answer get two more tries 15 s apart (Wi-Fi lights miss a single ask now and
     * then), and one that moved to another address is looked for by its name meanwhile. The
     * scheduled check starts a minute early, so all of this is done by the set time.
     */
    suspend fun check(context: Context, log: (String) -> Unit): Outcome = coroutineScope {
        val started = System.currentTimeMillis()
        val prefs = Prefs(context)
        val bulbs = prefs.bulbs
        if (bulbs.isEmpty()) return@coroutineScope Outcome.CouldNotCheck("no lights saved yet (Find lights)")
        val state = Net.wifiState(context) ?: return@coroutineScope Outcome.CouldNotCheck("the phone isn't on Wi-Fi")
        log("Wi-Fi: name ${state.ssid?.let { "\"$it\"" } ?: "unknown (no location access)"}, phone ${state.ip}/${state.prefix}, router ${state.gateway}")
        // On the home Wi-Fi? By its name when Android tells it, else by the router's fingerprint.
        if (prefs.homeSsid.isNotEmpty() && state.ssid != null) {
            if (state.ssid != prefs.homeSsid) return@coroutineScope Outcome.CouldNotCheck("on \"${state.ssid}\", not the home Wi-Fi")
            log("Home Wi-Fi by name (\"${prefs.homeSsid}\")")
        } else if (prefs.homeRouter.isNotEmpty()) {
            val router = Net.routerFingerprint(context)
            log("Router fingerprint now \"$router\", saved \"${prefs.homeRouter}\"")
            if (router != prefs.homeRouter) return@coroutineScope Outcome.CouldNotCheck("not on the home Wi-Fi (router: ${router.ifEmpty { "none" }})")
            log("Home Wi-Fi by its router")
        } else {
            log("No home Wi-Fi saved: assuming this is it")
        }
        log("Checking ${bulbs.size} light(s); ring when ${if (prefs.alarmWhenAll) "all are" else "any is"} off")
        var off = bulbs
        // Started after the first miss, so it's done by the last try.
        var search: kotlinx.coroutines.Deferred<List<Found>?>? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                log("Not answering yet: ${off.joinToString { it.label }}. Try ${attempt + 1} of 3 in 15 s")
                delay(15_000)
            }
            off = off.filter { bulb ->
                val (answered, detail) = Net.answersDetail(context, bulb)
                log("  ${bulb.label} (${bulb.ip}): $detail")
                !answered
            }
            if (!shouldRing(prefs, bulbs, off)) {
                search?.cancel()
                return@coroutineScope Outcome.LightsOn
            }
            if (search == null && off.any { it.name.isNotEmpty() }) {
                log("Meanwhile looking for ${off.filter { it.name.isNotEmpty() }.joinToString { it.label }} at other addresses")
                search = async { withTimeoutOrNull(55_000) { Net.scan(context) } }
            }
        }
        // Maybe the router gave a light a new address: look for it by name.
        val named = off.filter { it.name.isNotEmpty() }
        if (named.isNotEmpty() && search != null) {
            val found = search.await()
            if (found == null) log("Address search ran out of time")
            else {
                log("Address search found ${found.size} devices")
                val moved = named.mapNotNull { bulb -> found.firstOrNull { it.name == bulb.name }?.let { bulb to it.ip } }
                if (moved.isNotEmpty()) {
                    prefs.bulbs = prefs.bulbs.map { b -> moved.firstOrNull { it.first == b }?.let { b.copy(ip = it.second) } ?: b }
                    log("Moved: ${moved.joinToString { "${it.first.label} → ${it.second}" }}")
                    off = off.filterNot { b -> moved.any { it.first == b } }
                } else log("Not found at another address either")
            }
        } else if (off.isNotEmpty()) {
            search?.cancel()
            log("The lights not answering have no name on the Wi-Fi, so they can't be looked for elsewhere")
        }
        log("Checks done in ${(System.currentTimeMillis() - started) / 1000} s")
        if (shouldRing(prefs, bulbs, off)) Outcome.LightsOff(off) else Outcome.LightsOn
    }

    private fun shouldRing(prefs: Prefs, all: List<Bulb>, off: List<Bulb>) =
        if (prefs.alarmWhenAll) off.size == all.size else off.isNotEmpty()
}

/** Keeps the next check scheduled (exact, also when the phone is asleep). */
object Scheduler {
    private const val REQUEST = 1
    private const val SNOOZE_REQUEST = 2
    /** The check starts this long before the set time, so the alarm can ring right on it. */
    const val LEAD_MS = 60_000L

    fun nextCheck(context: Context): Long? {
        val prefs = Prefs(context)
        if (prefs.days.isEmpty()) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.hour)
            set(Calendar.MINUTE, prefs.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Today's is past once its early start is (the early start itself reschedules).
        if (cal.timeInMillis - LEAD_MS <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(7) {
            if (cal.get(Calendar.DAY_OF_WEEK) in prefs.days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextCheck(context)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, CheckReceiver::class.java).putExtra(CheckReceiver.EXTRA_RING_AT, at ?: 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - LEAD_MS, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - LEAD_MS, pending)
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        Prefs(context).log("Next check: ${fmt.format(Date(at))} (starts ${fmt.format(Date(at - LEAD_MS))})")
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
        CheckService.start(context, CheckService.ACTION_SCHEDULED, intent.getLongExtra(EXTRA_RING_AT, 0L))
    }

    companion object {
        const val EXTRA_SNOOZE = "snooze"
        const val EXTRA_RING_AT = "ring_at"
    }
}

/** After a restart, an update or a clock change, the next check is scheduled again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.schedule(context)
}
