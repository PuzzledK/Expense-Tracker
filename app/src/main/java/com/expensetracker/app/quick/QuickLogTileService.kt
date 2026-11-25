package com.expensetracker.app.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.expensetracker.app.R
import com.expensetracker.app.data.ExpenseDb

/**
 * Quick Settings tile "Log expense": tap it in the notification shade and the quick-log sheet
 * opens over whatever you were doing. On Android 10+ the tile's subtitle shows today's spend.
 */
class QuickLogTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_log_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_add)
        tile.updateTile()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val app = applicationContext
            QuickLog.worker.execute {
                val sub = try {
                    val db = ExpenseDb(app)
                    val all = try { db.all() } finally { db.close() }
                    subtitleFor(QuickLog.summary(all).todayPaise)
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't read today's spend", e)
                    null
                }
                // The tile may have stopped listening meanwhile; updating then is a harmless no-op.
                qsTile?.let { it.subtitle = sub; it.updateTile() }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = Intent(this, QuickLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 41, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "QuickLogTile"

        /** "Today ₹1,240", or "Nothing today" — shown under the tile label. */
        fun subtitleFor(todayPaise: Long): String =
            if (todayPaise <= 0) "Nothing today" else "Today ${QuickLog.rupees(todayPaise)}"

        /** Ask the system to re-bind the tile so a visible shade shows the new total. */
        fun requestRefresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, QuickLogTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "tile refresh request failed", e)
            }
        }
    }
}
