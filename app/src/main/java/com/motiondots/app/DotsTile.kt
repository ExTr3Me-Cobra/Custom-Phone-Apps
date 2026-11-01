package com.motiondots.app

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: swipe down, tap to turn the dots on or off. */
class DotsTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = Prefs(this)
        if (!prefs.on && !Settings.canDrawOverlays(this) && !(prefs.useAccessibility && DotsAccessibilityService.running)) {
            // Needs a permission first: open the app.
            val intent = android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
            )
            return
        }
        DotsService.set(this, !prefs.on)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (Prefs(this).on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
