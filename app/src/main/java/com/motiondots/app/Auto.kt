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
 * little battery, and keeps working with the app closed). Getting in turns the dots on; the
 * ride ending, walking, or the phone lying still for 4 minutes turns them off, however they
 * were turned on. Turning them off by hand keeps all of this away for 5 minutes.
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
            fun t(type: Int, transition: Int) =
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(transition).build()
            val transitions = listOf(
                t(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
                t(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            )
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(context))
                .addOnFailureListener { log(context, "Couldn't start vehicle detection: ${it.message}") }
        } else {
            runCatching { client.removeActivityTransitionUpdates(pending(context)) }
        }
    }

    /** Turned off by hand less than 5 minutes ago: automatic on/off stays away. */
    fun paused(prefs: Prefs) = System.currentTimeMillis() - prefs.manualOffAt < 5 * 60_000L

    fun log(context: Context, line: String) {
        val prefs = Prefs(context)
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        prefs.autoLog = (prefs.autoLog.lines().filter { it.isNotBlank() } + "$stamp  $line").takeLast(10).joinToString("\n")
    }
}

/** Vehicle ride started or ended, or walking started. */
class AutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val prefs = Prefs(context)
        if (!prefs.auto) return
        val event = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        val entered = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        val what = when (event.activityType) {
            DetectedActivity.IN_VEHICLE -> if (entered) "In a vehicle" else "Ride ended"
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> "Walking"
            DetectedActivity.STILL -> "Still"
            else -> return
        }
        if (Auto.paused(prefs)) {
            Auto.log(context, "$what: ignored (turned off by hand less than 5 min ago)")
            return
        }
        when {
            event.activityType == DetectedActivity.IN_VEHICLE && entered -> {
                if (prefs.on) return
                Auto.log(context, "$what: dots on")
                // With the app briefly in front (allowed with "display over other apps"), so the
                // dots get motion readings; otherwise straight from here.
                val started = android.provider.Settings.canDrawOverlays(context) && runCatching {
                    context.startActivity(
                        Intent(context, ToggleActivity::class.java)
                            .putExtra(ToggleActivity.EXTRA_ON, true).putExtra(ToggleActivity.EXTRA_AUTO, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                }.isSuccess
                if (!started) DotsService.set(context, true, manual = false)
            }
            // Out of the vehicle (also dots turned on by hand); "still" alone isn't enough, as a
            // car at the lights is still too: the phone lying still for 4 minutes is (DotsService).
            event.activityType != DetectedActivity.STILL -> if (prefs.on) {
                Auto.log(context, "$what: dots off")
                DotsService.set(context, false, manual = false)
            }
        }
    }
}

/** Listening for rides stops after a restart or an update: start it again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Auto.sync(context)
}
