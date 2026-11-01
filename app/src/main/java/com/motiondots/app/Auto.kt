package com.motiondots.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automatic mode: Google Play services' activity recognition tells the app when the phone
 * starts and stops riding in a vehicle (it combines the motion sensors over time, uses very
 * little battery, and keeps working with the app closed). Getting in turns the dots on;
 * the ride ending turns them off again, unless you turned them on yourself.
 */
object Auto {
    private const val REQUEST = 7

    fun permitted(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, AutoReceiver::class.java),
        // Mutable: Play services adds the transition details to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Starts or stops listening, to match the setting. Safe to call any time. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val prefs = Prefs(context)
        val client = ActivityRecognition.getClient(context)
        if (prefs.auto && permitted(context)) {
            val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
            }
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(context))
                .addOnFailureListener { log(context, "Couldn't start vehicle detection: ${it.message}") }
        } else {
            runCatching { client.removeActivityTransitionUpdates(pending(context)) }
        }
    }

    fun log(context: Context, line: String) {
        val prefs = Prefs(context)
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        prefs.autoLog = (prefs.autoLog.lines().filter { it.isNotBlank() } + "$stamp  $line").takeLast(6).joinToString("\n")
    }
}

/** Vehicle ride started or ended. */
class AutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val prefs = Prefs(context)
        if (!prefs.auto) return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        if (event.activityType != DetectedActivity.IN_VEHICLE) return
        if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            Auto.log(context, "In a vehicle: dots on")
            if (!prefs.on) {
                // With the app briefly in front (allowed with "display over other apps"), so the
                // dots get motion readings; otherwise straight from here.
                val started = android.provider.Settings.canDrawOverlays(context) && runCatching {
                    context.startActivity(
                        Intent(context, ToggleActivity::class.java)
                            .putExtra(ToggleActivity.EXTRA_ON, true).putExtra(ToggleActivity.EXTRA_AUTO, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                }.isSuccess
                if (!started) {
                    DotsService.set(context, true)
                    prefs.autoStarted = true
                }
            }
        } else {
            Auto.log(context, "Ride ended: ${if (prefs.autoStarted && prefs.on) "dots off" else "left as is"}")
            if (prefs.autoStarted && prefs.on) DotsService.set(context, false)
        }
    }
}

/** Listening for rides stops after a restart or an update: start it again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Auto.sync(context)
}
