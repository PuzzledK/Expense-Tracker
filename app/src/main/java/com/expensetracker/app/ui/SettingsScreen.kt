@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.Reminders
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(
    vm: ExpenseViewModel,
    snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    onOpenImport: () -> Unit,
    onOpen: (Overlay) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()

    var budgetDialog by remember { mutableStateOf(false) }
    var categoryBudgetsDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var homeCityDialog by remember { mutableStateOf(false) }
    var lockTimeoutDialog by remember { mutableStateOf(false) }
    val appLock = LocalAppLock.current
    var tripDialog by remember { mutableStateOf(false) }
    val recentPlaces by vm.recentPlaces.collectAsStateWithLifecycle()
    val detectedHome = remember(expenses) { com.expensetracker.app.data.Places.homeCity(expenses, com.expensetracker.app.data.Settings()) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    fun enableReminder() {
        vm.updateSettings { it.copy(reminderEnabled = true) }
        Reminders.schedule(ctx, settings.reminderHour, settings.reminderMinute)
        toast("Reminder set for ${formatTime(settings.reminderHour, settings.reminderMinute)} daily")
    }

    fun enableQuickLog() {
        vm.updateSettings { it.copy(quickLogNotification = true) }
        toast("Quick log notification is on — pull down the shade to log")
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.updateSettings { it.copy(locationTagging = true) }
            toast("New expenses will be tagged with your city")
        } else {
            toast("Location permission denied — you can still type places by hand")
        }
    }

    val quickLogPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableQuickLog() else toast("Notifications are blocked — allow them in system settings")
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableReminder() else toast("Notifications are blocked — allow them in system settings")
    }

    fun writeTo(uri: android.net.Uri?, content: () -> String, what: String) {
        if (uri == null) return
        runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(content()) }
        }.onSuccess { toast("$what saved") }
            .onFailure { toast("Couldn't save: ${it.message}") }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        writeTo(it, vm::exportCsv, "CSV")
    }
    val notesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        writeTo(it, vm::exportNotesText, "Notes text")
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeTo(it, vm::exportJson, "Backup")
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .onSuccess { pendingRestore = it }
                .onFailure { toast("Couldn't read file: ${it.message}") }
        }
    }

    val stamp = LocalDate.now().toString()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Appearance")
            AppearanceSection(
                settings = settings,
                onTheme = { id -> vm.updateSettings { it.copy(themeId = id) } },
                onDarkMode = { m -> vm.updateSettings { it.copy(darkMode = m) } },
                onPureBlack = { on -> vm.updateSettings { it.copy(pureBlack = on) } },
            )

            HorizontalDivider()
            SectionTitle("Budget")
            ListItem(
                headlineContent = { Text("Monthly budget") },
                supportingContent = {
                    Text(if (settings.monthlyBudgetPaise > 0) formatRupees(settings.monthlyBudgetPaise) else "Not set — tap to set one")
                },
                modifier = Modifier.clickable { budgetDialog = true },
            )
            ListItem(
                headlineContent = { Text("Category budgets") },
                supportingContent = {
                    val n = settings.categoryBudgets.size
                    Text(if (n > 0) "$n ${if (n == 1) "category" else "categories"} · ${formatRupees(settings.categoryBudgets.values.sum())}/month" else "Limits per category, with a nudge at 80% and 100%")
                },
                modifier = Modifier.clickable { categoryBudgetsDialog = true },
            )
            ListItem(
                headlineContent = { Text("Keep savings out of spending") },
                supportingContent = { Text("FDs / RDs show as “Saved”, not as spend") },
                trailingContent = {
                    Switch(
                        checked = settings.excludeSavingsFromSpend,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(excludeSavingsFromSpend = v) } },
                    )
                },
            )

            HorizontalDivider()
            SectionTitle("Privacy")
            ListItem(
                headlineContent = { Text("App lock") },
                supportingContent = {
                    Text(
                        if (com.expensetracker.app.security.AppLock.isDeviceSecure(ctx))
                            "Unlock with your fingerprint, face, or phone PIN/pattern. Amounts are hidden on the notification and widget while it's on."
                        else "Set a screen lock (PIN, pattern or password) in your phone's settings first",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.appLock,
                        onCheckedChange = { on ->
                            if (!com.expensetracker.app.security.AppLock.isDeviceSecure(ctx)) {
                                toast("Set a screen lock in your phone's settings first")
                            } else if (appLock == null) {
                                vm.updateSettings { it.copy(appLock = on) }
                            } else {
                                // Confirm with the phone unlock both to turn it on (proves it works) and off.
                                appLock.authenticate(
                                    if (on) "Turn on app lock" else "Turn off app lock",
                                    "Confirm it's you",
                                ) { ok ->
                                    if (ok) {
                                        vm.updateSettings { it.copy(appLock = on) }
                                        appLock.refresh()
                                        toast(if (on) "App lock is on" else "App lock is off")
                                    }
                                }
                            }
                        },
                    )
                },
            )
            if (settings.appLock) {
                ListItem(
                    headlineContent = { Text("Lock after") },
                    supportingContent = { Text(lockTimeoutLabel(settings.lockTimeoutSec)) },
                    modifier = Modifier.clickable { lockTimeoutDialog = true },
                )
            }

            HorizontalDivider()
            SectionTitle("Location")
            ListItem(
                headlineContent = { Text("Tag expenses with location") },
                supportingContent = { Text("Adds the city (approximate location only) to new expenses logged today, so trips and other cities show up in Stats") },
                trailingContent = {
                    Switch(
                        checked = settings.locationTagging,
                        onCheckedChange = { on ->
                            if (on && !com.expensetracker.app.location.LocationTagger.hasPermission(ctx)) {
                                locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            } else {
                                vm.updateSettings { it.copy(locationTagging = on) }
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Home city") },
                supportingContent = {
                    Text(
                        when {
                            settings.homeCity.isNotBlank() -> settings.homeCity
                            detectedHome != null -> "$detectedHome (detected)"
                            else -> "Not set — spending elsewhere counts as travel"
                        },
                    )
                },
                modifier = Modifier.clickable { homeCityDialog = true },
            )
            ListItem(
                headlineContent = { Text("Tag a past trip") },
                supportingContent = { Text("Give a city to every entry between two dates — for history logged before this") },
                modifier = Modifier.clickable { tripDialog = true },
            )

            HorizontalDivider()
            SectionTitle("Quick logging")
            ListItem(
                headlineContent = { Text("Quick log notification") },
                supportingContent = { Text("Always-on, silent. Shows today's total; reply “120 - Lunch” right from the shade") },
                trailingContent = {
                    Switch(
                        checked = settings.quickLogNotification,
                        onCheckedChange = { on ->
                            if (on) {
                                val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                if (needsAsk) quickLogPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else enableQuickLog()
                            } else {
                                vm.updateSettings { it.copy(quickLogNotification = false) }
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Home-screen widget") },
                supportingContent = { Text("Long-press your home screen → Widgets → Expenses. Shows today's spend with + and ≡ buttons") },
            )

            HorizontalDivider()
            SectionTitle("Reminder")
            ListItem(
                headlineContent = { Text("Daily reminder") },
                supportingContent = { Text("“Logged today's expenses?” notification") },
                trailingContent = {
                    Switch(
                        checked = settings.reminderEnabled,
                        onCheckedChange = { on ->
                            if (on) {
                                val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                if (needsAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else enableReminder()
                            } else {
                                vm.updateSettings { it.copy(reminderEnabled = false) }
                                Reminders.cancel(ctx)
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Reminder time") },
                supportingContent = { Text(formatTime(settings.reminderHour, settings.reminderMinute)) },
                modifier = Modifier.clickable { timeDialog = true },
            )

            HorizontalDivider()
            SectionTitle("Smart features")
            ListItem(
                headlineContent = { Text("Auto-capture payments") },
                supportingContent = { Text("Turn UPI and bank payment notifications into entries to review") },
                modifier = Modifier.clickable { onOpen(Overlay.CAPTURE) },
            )
            ListItem(
                headlineContent = { Text("Savings goals") },
                supportingContent = { Text("Set your own goals and track progress") },
                modifier = Modifier.clickable { onOpen(Overlay.GOALS) },
            )
            ListItem(
                headlineContent = { Text("Insights") },
                supportingContent = { Text("Trends, habits and a weekly summary") },
                modifier = Modifier.clickable { onOpen(Overlay.INSIGHTS) },
            )
            ListItem(
                headlineContent = { Text("Subscriptions & bills") },
                supportingContent = { Text("Recurring payments, what's due next and price changes") },
                modifier = Modifier.clickable { onOpen(Overlay.SUBSCRIPTIONS) },
            )
            ListItem(
                headlineContent = { Text("Trips & events") },
                supportingContent = { Text("Trip mode, and what each trip cost — kept out of everyday averages") },
                modifier = Modifier.clickable { onOpen(Overlay.TRIPS) },
            )
            ListItem(
                headlineContent = { Text("Categories") },
                supportingContent = { Text("Add your own, rename, recolour or hide") },
                modifier = Modifier.clickable { onOpen(Overlay.CATEGORIES) },
            )

            HorizontalDivider()
            SectionTitle("Your data")
            ListItem(
                headlineContent = { Text("Automatic backups") },
                supportingContent = { Text("Back up to a folder of your choice on a schedule") },
                modifier = Modifier.clickable { onOpen(Overlay.BACKUP) },
            )
            ListItem(
                headlineContent = { Text("Import from notes") },
                supportingContent = { Text("Paste your old notes-app history (“5000 - Mammi” style)") },
                modifier = Modifier.clickable(onClick = onOpenImport),
            )
            ListItem(
                headlineContent = { Text("Back up (JSON)") },
                supportingContent = { Text("Everything incl. settings — keep it in Drive") },
                modifier = Modifier.clickable { backupLauncher.launch("expenses-backup-$stamp.json") },
            )
            ListItem(
                headlineContent = { Text("Restore backup") },
                supportingContent = { Text("From a JSON backup file") },
                modifier = Modifier.clickable { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Export CSV") },
                supportingContent = { Text("Open in Sheets / Excel") },
                modifier = Modifier.clickable { csvLauncher.launch("expenses-$stamp.csv") },
            )
            ListItem(
                headlineContent = { Text("Export as notes text") },
                supportingContent = { Text("Same format as your old notes") },
                modifier = Modifier.clickable { notesLauncher.launch("expenses-$stamp.txt") },
            )
            ListItem(
                headlineContent = { Text("Copy as notes text") },
                supportingContent = { Text("To paste anywhere") },
                modifier = Modifier.clickable {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Expenses", vm.exportNotesText()))
                    toast("Copied ${expenses.size} entries")
                },
            )
            ListItem(
                headlineContent = { Text("Delete all data", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("${expenses.size} entries stored on this phone") },
                modifier = Modifier.clickable { deleteDialog = true },
            )
        }
    }

    if (budgetDialog) {
        var text by remember { mutableStateOf(if (settings.monthlyBudgetPaise > 0) paiseToInput(settings.monthlyBudgetPaise) else "") }
        AlertDialog(
            onDismissRequest = { budgetDialog = false },
            title = { Text("Monthly budget") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' } },
                    prefix = { Text("₹") },
                    placeholder = { Text("15000") },
                    supportingText = { Text("Leave empty for no budget. Rent etc. counts too.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = parseAmountInput(text) ?: 0L
                    vm.updateSettings { it.copy(monthlyBudgetPaise = p) }
                    budgetDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { budgetDialog = false }) { Text("Cancel") } },
        )
    }

    if (categoryBudgetsDialog) {
        com.expensetracker.app.budget.CategoryBudgetsDialog(
            current = settings.categoryBudgets,
            onDismiss = { categoryBudgetsDialog = false },
            onSave = { m -> vm.updateSettings { it.copy(categoryBudgets = m) }; categoryBudgetsDialog = false },
        )
    }

    if (lockTimeoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { lockTimeoutDialog = false },
            title = { Text("Lock after leaving the app") },
            text = {
                Column {
                    listOf(0, 30, 60, 300).forEach { sec ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateSettings { it.copy(lockTimeoutSec = sec) }
                                    lockTimeoutDialog = false
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = settings.lockTimeoutSec == sec,
                                onClick = {
                                    vm.updateSettings { it.copy(lockTimeoutSec = sec) }
                                    lockTimeoutDialog = false
                                },
                            )
                            Text(lockTimeoutLabel(sec))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lockTimeoutDialog = false }) { Text("Close") } },
        )
    }
    if (homeCityDialog) {
        HomeCityDialog(
            current = settings.homeCity,
            detected = detectedHome,
            onDismiss = { homeCityDialog = false },
            onSave = { c ->
                vm.updateSettings { it.copy(homeCity = c) }
                homeCityDialog = false
            },
        )
    }
    if (tripDialog) {
        TagTripDialog(
            recent = recentPlaces,
            onDismiss = { tripDialog = false },
            onTag = { from, to, city, overwrite ->
                tripDialog = false
                vm.tagTrip(from, to, city, overwrite) { n ->
                    toast(if (n == 0) "No entries to tag in that range" else "Tagged $n entries with $city")
                }
            },
        )
    }
    if (timeDialog) {
        TimePickDialog(
            hour = settings.reminderHour,
            minute = settings.reminderMinute,
            onPick = { h, m ->
                vm.updateSettings { it.copy(reminderHour = h, reminderMinute = m) }
                if (settings.reminderEnabled) Reminders.schedule(ctx, h, m)
            },
            onDismiss = { timeDialog = false },
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete everything?") },
            text = { Text("All ${expenses.size} entries will be removed from this phone. This can't be undone — make a backup first if unsure.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    deleteDialog = false
                    toast("All data deleted")
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
    }

    pendingRestore?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Replace: wipe the ${expenses.size} current entries and load the backup.\n" +
                        "Merge: add the backup's entries, skipping exact duplicates.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreJson(json, replaceExisting = true) { n -> Reminders.rescheduleFromPrefs(ctx); toast("Restored $n entries") }
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreJson(json, replaceExisting = false) { n -> toast("Added $n entries") }
                }) { Text("Merge") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val style = LocalAppStyle.current
    Text(
        if (style.capsLabels) text.uppercase() else text,
        style = if (style.capsLabels) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

fun formatTime(h: Int, m: Int): String {
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
    return "$h12:${m.toString().padStart(2, '0')} $ampm"
}

private fun lockTimeoutLabel(sec: Int): String = when (sec) {
    0 -> "Immediately"
    in 1..59 -> "After $sec seconds"
    60 -> "After 1 minute"
    else -> "After ${sec / 60} minutes"
}
