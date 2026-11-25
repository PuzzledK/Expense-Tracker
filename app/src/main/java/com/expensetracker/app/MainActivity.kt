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
    /** "Add income" app shortcut: opens the add sheet already switched to income. */
    private val openIncomeRequests = mutableIntStateOf(0)
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
            val settings by vm.settings.collectAsStateWithLifecycle()
            val theme = com.expensetracker.app.ui.AppThemeId.of(settings.themeId)
            val dark = com.expensetracker.app.ui.isDarkFor(theme, settings.darkMode)
            // Status/navigation bar icons must follow the app's light/dark, not the phone's.
            androidx.compose.runtime.LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
                    navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
                )
            }
            ExpenseTheme(themeId = settings.themeId, darkMode = settings.darkMode, pureBlack = settings.pureBlack) {
                val all by vm.expenses.collectAsStateWithLifecycle()
                val home = remember(all, settings.homeCity) { Places.homeCity(all, settings) }
                // The lock screen replaces the app while locked; this holder saves the app's state
                // (tab, open page, scroll positions) when it's removed and restores it on unlock.
                val appState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                CompositionLocalProvider(LocalHomeCity provides home, LocalAppLock provides appLock) {
                    if (appLock.locked) {
                        LockScreen(onUnlock = appLock::unlock)
                    } else appState.SaveableStateProvider("app") {
                        ExpenseApp(
                            vm = vm,
                            openAddRequests = openAddRequests.intValue,
                            openBulkRequests = openBulkRequests.intValue,
                            openIncomeRequests = openIncomeRequests.intValue,
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
        com.expensetracker.app.capture.CaptureListener.ensureRunning(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val routes = LaunchRoute.of(intent.action) { intent.extras?.get(it) }
        for (r in routes) when (r) {
            LaunchRoute.ADD_EXPENSE -> openAddRequests.intValue++
            LaunchRoute.ADD_INCOME -> openIncomeRequests.intValue++
            LaunchRoute.BULK -> openBulkRequests.intValue++
            LaunchRoute.REVIEW_CAPTURE -> overlayRequest.value = com.expensetracker.app.ui.Overlay.CAPTURE
            LaunchRoute.BACKUP -> overlayRequest.value = com.expensetracker.app.ui.Overlay.BACKUP
        }
        LaunchRoute.EXTRAS.forEach(intent::removeExtra)
        // Handled: a re-created activity (rotation, process restore) mustn't replay the shortcut.
        if (routes.isNotEmpty()) intent.action = Intent.ACTION_MAIN
    }

    companion object {
        const val EXTRA_OPEN_ADD = LaunchRoute.EXTRA_OPEN_ADD
        const val EXTRA_OPEN_BULK = LaunchRoute.EXTRA_OPEN_BULK
        const val ACTION_ADD = LaunchRoute.ACTION_ADD
    }
}
