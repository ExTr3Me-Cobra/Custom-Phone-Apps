package com.motiondots.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 1×1 home-screen widget: tap to turn the dots on or off. */
class DotsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DotsWidget::class.java))
            if (ids.isEmpty()) return
            val on = Prefs(context).on
            // Starts the service directly, which Android allows from a widget tap.
            val toggle = PendingIntent.getForegroundService(
                context, 3, Intent(context, DotsService::class.java).setAction(DotsService.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (on) R.drawable.widget_on else R.drawable.widget_off)
                setInt(R.id.widget_icon, "setColorFilter", if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setTextViewText(R.id.widget_label, if (on) "On" else "Off")
                setTextColor(R.id.widget_label, if (on) 0xFF000000.toInt() else 0xFF9AA0A6.toInt())
                setOnClickPendingIntent(R.id.widget_root, toggle)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
