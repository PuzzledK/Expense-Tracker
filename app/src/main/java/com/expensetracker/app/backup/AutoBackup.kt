package com.expensetracker.app.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.capture.PlaceholderScreen

/** STUBS — owned by the "backup" session (scheduled backups to a chosen folder). */
object AutoBackup {
    /** Arm/re-arm the schedule from saved settings (called on boot, app update and settings change). */
    fun restore(ctx: Context) = Unit
}

/** Fired by the backup alarm. Declared in the manifest. */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Folder picker, frequency, how many to keep, last backup status, "Back up now". */
@Composable
fun BackupSettingsScreen(vm: ExpenseViewModel, onBack: () -> Unit) = PlaceholderScreen("Automatic backups", onBack)
