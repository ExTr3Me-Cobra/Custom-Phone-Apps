package com.lightcheck.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A light found on the home Wi-Fi: where it was, its name there, and how it answers. */
data class Bulb(val ip: String, val name: String, val how: String, val brand: String) {
    fun encode() = listOf(ip, name, how, brand).joinToString("|") { it.replace("|", "/") }

    val label: String get() = name.ifEmpty { ip }

    companion object {
        fun decode(line: String): Bulb? = line.split("|").takeIf { it.size == 4 }?.let { Bulb(it[0], it[1], it[2], it[3]) }
    }
}

/** Everything the app remembers. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("light_check", Context.MODE_PRIVATE)

    var bulbs: List<Bulb>
        get() = sp.getString("bulbs", "").orEmpty().lines().mapNotNull(Bulb::decode)
        set(value) = sp.edit().putString("bulbs", value.joinToString("\n") { it.encode() }).apply()

    var hour: Int
        get() = sp.getInt("hour", 22)
        set(value) = sp.edit().putInt("hour", value).apply()
    var minute: Int
        get() = sp.getInt("minute", 0)
        set(value) = sp.edit().putInt("minute", value).apply()

    /** Days the check runs, as Calendar day numbers (1 = Sunday … 7 = Saturday). */
    var days: Set<Int>
        get() = sp.getString("days", "1234567").orEmpty().mapNotNull { it.digitToIntOrNull() }.toSet()
        set(value) = sp.edit().putString("days", value.sorted().joinToString("")).apply()

    /** The home Wi-Fi's name, and a fingerprint of its router for when the name can't be read. */
    var homeSsid: String
        get() = sp.getString("home_ssid", "").orEmpty()
        set(value) = sp.edit().putString("home_ssid", value).apply()
    var homeRouter: String
        get() = sp.getString("home_router", "").orEmpty()
        set(value) = sp.edit().putString("home_router", value).apply()

    /** Ring when any light is off, or only when all of them are. */
    var alarmWhenAll: Boolean
        get() = sp.getBoolean("alarm_when_all", false)
        set(value) = sp.edit().putBoolean("alarm_when_all", value).apply()

    var soundUri: String
        get() = sp.getString("sound", "").orEmpty()
        set(value) = sp.edit().putString("sound", value).apply()
    var volume: Int
        get() = sp.getInt("volume", 80)
        set(value) = sp.edit().putInt("volume", value).apply()
    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(value) = sp.edit().putBoolean("vibrate", value).apply()
    var ringUntilDismissed: Boolean
        get() = sp.getBoolean("ring_until_dismissed", true)
        set(value) = sp.edit().putBoolean("ring_until_dismissed", value).apply()
    /** Ring as an alarm (also in silent mode and Do Not Disturb) or as a notification. */
    var ringInSilent: Boolean
        get() = sp.getBoolean("ring_in_silent", true)
        set(value) = sp.edit().putBoolean("ring_in_silent", value).apply()
    var snoozeMinutes: Int
        get() = sp.getInt("snooze", 10)
        set(value) = sp.edit().putInt("snooze", value).apply()

    var lastResult: String
        get() = sp.getString("last_result", "No check yet").orEmpty()
        set(value) = sp.edit().putString("last_result", value).apply()

    /** The newest 500 lines of what the app did, for Diagnostics. */
    val log: List<String> get() = sp.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }

    fun log(line: String) = synchronized(Prefs) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = (log + "$stamp  $line").takeLast(500)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().remove("log").apply()

    companion object
}
