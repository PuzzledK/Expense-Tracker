package com.expensetracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.expensetracker.app.ui.ExpenseApp
import com.expensetracker.app.ui.ExpenseTheme
import com.expensetracker.app.ui.LocalHomeCity
import com.expensetracker.app.ui.LocalAppLock
import com.expensetracker.app.ui.LockScreen
import com.expensetracker.app.security.AppLockController
import com.expensetracker.app.data.Places
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    /** Incremented each time the "Add expense" shortcut (or reminder) asks to open the add sheet. */
    private val openAddRequests = mutableIntStateOf(0)
    /** Same for the "Add multiple" sheet (notification action / widget ≡ button). */
    private val openBulkRequests = mutableIntStateOf(0)
    /** Feature screen a notification wants opened (capture review, backup settings). */
    private val overlayRequest = androidx.compose.runtime.mutableStateOf<com.expensetracker.app.ui.Overlay?>(null)
    private val vm: ExpenseViewModel by viewModels()
    /** Phone-unlock gate; registered here because it needs an activity-result launcher. */
    val appLock = AppLockController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        appLock.onCreate()
        // Force-stop clears alarms; re-arm scheduled backups and the weekly summary on every launch.
        com.expensetracker.app.backup.AutoBackup.restore(this)
        com.expensetracker.app.insights.Insights.restore(this)
        setContent {
            ExpenseTheme {
                val all by vm.expenses.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val home = remember(all, settings.homeCity) { Places.homeCity(all, settings) }
                CompositionLocalProvider(LocalHomeCity provides home, LocalAppLock provides appLock) {
                    if (appLock.locked) {
                        LockScreen(onUnlock = appLock::unlock)
                    } else {
                        ExpenseApp(
                            vm = vm,
                            openAddRequests = openAddRequests.intValue,
                            openBulkRequests = openBulkRequests.intValue,
                            overlayRequest = overlayRequest.value,
                            onOverlayRequestHandled = { overlayRequest.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        super.onStop()
        appLock.onStop()
        // Backs up on a background thread only if one is overdue.
        com.expensetracker.app.backup.AutoBackup.onAppBackground(this)
    }

    override fun onResume() {
        super.onResume()
        vm.reload()
        com.expensetracker.app.capture.CaptureStore.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Shortcut XML delivers extras as strings; notifications deliver a boolean.
        @Suppress("DEPRECATION")
        val flag = intent.extras?.get(EXTRA_OPEN_ADD)
        if (intent.action == ACTION_ADD || flag == true || flag == "true") {
            openAddRequests.intValue++
            intent.removeExtra(EXTRA_OPEN_ADD)
        }
        if (intent.getBooleanExtra(com.expensetracker.app.capture.CaptureNotifier.EXTRA_OPEN_CAPTURE, false)) {
            overlayRequest.value = com.expensetracker.app.ui.Overlay.CAPTURE
            intent.removeExtra(com.expensetracker.app.capture.CaptureNotifier.EXTRA_OPEN_CAPTURE)
        }
        if (intent.getBooleanExtra(com.expensetracker.app.backup.AutoBackup.EXTRA_OPEN_BACKUP, false)) {
            overlayRequest.value = com.expensetracker.app.ui.Overlay.BACKUP
            intent.removeExtra(com.expensetracker.app.backup.AutoBackup.EXTRA_OPEN_BACKUP)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_BULK, false)) {
            openBulkRequests.intValue++
            intent.removeExtra(EXTRA_OPEN_BULK)
        }
    }

    companion object {
        const val EXTRA_OPEN_ADD = "open_add"
        const val EXTRA_OPEN_BULK = "open_bulk"
        const val ACTION_ADD = "com.expensetracker.app.action.ADD_EXPENSE"
    }
}
