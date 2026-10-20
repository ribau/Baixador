package com.ribau.baixador

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ClipboardTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is required below API 34; guarded below.
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val intent = Intent(this, ClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else startActivityAndCollapse(intent)
        }
    }
}
