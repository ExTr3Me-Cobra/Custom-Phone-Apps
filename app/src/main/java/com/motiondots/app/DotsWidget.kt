package com.motiondots.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 1×1 home-screen widget: tap to turn the dots on or off. */
/** The widget's tap: switch the dots and close at once (nothing is shown). */
class ToggleActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, true) else !prefs.on
        DotsService.set(this, on, manual = !intent.getBooleanExtra(EXTRA_AUTO, false))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_ON = "on"
        const val EXTRA_AUTO = "auto"
    }
}

class DotsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DotsWidget::class.java))
            if (ids.isEmpty()) return
            val on = Prefs(context).on
            // Through an invisible screen, so the dots start with the app in front, the same as from
            // the app; started in the background, Android may give them no motion readings.
            val toggle = PendingIntent.getActivity(
                context, 3, Intent(context, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget).apply {
                // No background: white when on, faded grey when off, named like the app icons.
                setInt(R.id.widget_icon, "setColorFilter", if (on) 0xFFFFFFFF.toInt() else 0xFF9AA0A6.toInt())
                setInt(R.id.widget_icon, "setImageAlpha", if (on) 255 else 140)
                setOnClickPendingIntent(R.id.widget_root, toggle)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
