package com.lightcheck.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/** One line on the app and the phone, logged at the start of every check. */
object Diagnostics {
    fun summary(context: Context): String {
        fun granted(permission: String) =
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "yes" else "NO"
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val power = context.getSystemService(PowerManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        return listOf(
            "App $version, Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})",
            "location ${granted(Manifest.permission.ACCESS_FINE_LOCATION)}, background location ${granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}",
            "notifications ${if (nm?.areNotificationsEnabled() == true) "yes" else "NO"}, full-screen ${if (Build.VERSION.SDK_INT < 34 || nm?.canUseFullScreenIntent() == true) "yes" else "NO"}",
            "exact alarms ${if (alarms?.canScheduleExactAlarms() == true) "yes" else "NO"}",
            "battery unrestricted ${if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) "yes" else "no"}",
            "screen ${if (power?.isInteractive == true) "on" else "off"}, battery saver ${if (power?.isPowerSaveMode == true) "ON" else "off"}, idle ${if (power?.isDeviceIdleMode == true) "yes" else "no"}"
        ).joinToString("; ")
    }
}
