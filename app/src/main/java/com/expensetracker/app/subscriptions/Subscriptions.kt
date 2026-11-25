@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.subscriptions

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.Subscription
import com.expensetracker.app.data.SubscriptionDetector
import com.expensetracker.app.ui.formatRupees
import com.expensetracker.app.ui.formatShortDate
import com.expensetracker.app.ui.incomeColor
import com.expensetracker.app.ui.kit.Card
import com.expensetracker.app.ui.kit.TopAppBar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Subscriptions & bills: your choices ("not a subscription", reminders on/off) and the daily
 * check that posts "due tomorrow" reminders. Kept in its own prefs file ("subscriptions").
 */
object Subscriptions {
    private const val PREFS = "subscriptions"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_REMIND = "remind"
    private const val KEY_LAST_CHECK = "lastCheck"
    private const val CHANNEL_ID = "subscription_due"
    private const val REQUEST_CODE = 1401
    private const val NOTIFICATION_ID = 1401
    const val EXTRA_OPEN = "com.expensetracker.app.OPEN_SUBSCRIPTIONS"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hidden(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
    fun setHidden(ctx: Context, key: String, hide: Boolean) {
        val now = hidden(ctx).toMutableSet().apply { if (hide) add(key) else remove(key) }
        prefs(ctx).edit { putStringSet(KEY_HIDDEN, now) }
    }

    fun remindEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_REMIND, true)
    fun setRemind(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_REMIND, on) }
        restore(ctx)
    }

    internal fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Arm/re-arm the daily ~10 am "due tomorrow" check (boot, app start, toggle). */
    fun restore(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(ctx))
        if (!remindEnabled(ctx)) return
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(10, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, SubscriptionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Posts one notification for whatever is due tomorrow (once per day). Off the main thread. */
    internal fun checkDue(ctx: Context) {
        val p = prefs(ctx)
        val today = LocalDate.now()
        if (!remindEnabled(ctx) || !canNotify(ctx) || p.getString(KEY_LAST_CHECK, null) == today.toString()) return
        CategoryRegistry.ensureLoaded(ctx)
        val db = ExpenseDb(ctx)
        val all = try { db.all() } finally { db.close() }
        val due = SubscriptionDetector.dueTomorrow(SubscriptionDetector.detect(all, today, hidden(ctx)), today)
        p.edit { putString(KEY_LAST_CHECK, today.toString()) }
        if (due.isEmpty()) return

        val locked = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("appLock", false)
        val title = if (due.size == 1) "${due[0].name} is due tomorrow" else "${due.size} payments due tomorrow"
        val text = if (locked) "Tap to see what's coming up." else due.joinToString(" · ") { "${it.name} ${formatRupees(it.amountPaise)}" }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Subscriptions & bills", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A heads-up the day before a recurring payment is due"
                },
            )
        }
        val open = PendingIntent.getActivity(
            ctx, REQUEST_CODE,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setColor(com.expensetracker.app.ui.notificationAccent(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (locked) "A payment is due tomorrow" else title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(if (locked) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}

/** Daily due-tomorrow check. Declared in the manifest. */
class SubscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { Subscriptions.checkDue(app) } catch (_: Throwable) {} finally { pending.finish() }
        }.start()
    }
}

@Composable
fun SubscriptionsScreen(vm: ExpenseViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val catVersion by CategoryRegistry.version.collectAsStateWithLifecycle()
    var hidden by remember { mutableStateOf(Subscriptions.hidden(ctx)) }
    var remind by remember { mutableStateOf(Subscriptions.remindEnabled(ctx)) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val subs = remember(expenses, hidden, catVersion) { SubscriptionDetector.detect(expenses, today, hidden) }
    val hiddenSubs = remember(expenses, hidden, catVersion) {
        SubscriptionDetector.detect(expenses, today).filter { it.key in hidden }
    }

    fun setRemind(on: Boolean) { Subscriptions.setRemind(ctx, on); remind = on }
    fun setHidden(key: String, hide: Boolean) { Subscriptions.setHidden(ctx, key, hide); hidden = Subscriptions.hidden(ctx) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> setRemind(granted) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions & bills") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Every month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatRupees(SubscriptionDetector.monthlyTotal(subs)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${subs.size} recurring payment${if (subs.size == 1) "" else "s"} found in your history",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remind me the day before", style = MaterialTheme.typography.titleSmall)
                            Text("A notification around 10 am", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = remind,
                            onCheckedChange = { on ->
                                if (on && !Subscriptions.canNotify(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else setRemind(on)
                            },
                        )
                    }
                }
            }
            if (subs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔁", style = MaterialTheme.typography.displaySmall)
                    Text("No subscriptions found yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Payments show up here once the same note (e.g. “Netflix” or “Gym”) is paid about once a month for 3 months.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(subs, key = { it.key }) { s -> SubscriptionRow(s, today, menuLabel = "Not a subscription") { setHidden(s.key, true) } }
            if (hiddenSubs.isNotEmpty()) {
                item {
                    TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.padding(top = 8.dp)) {
                        Text((if (showHidden) "Hide" else "Show") + " ${hiddenSubs.size} hidden")
                    }
                }
                if (showHidden) items(hiddenSubs, key = { "h_" + it.key }) { s ->
                    SubscriptionRow(s, today, menuLabel = "Show again", dimmed = true) { setHidden(s.key, false) }
                }
            }
        }
    }
}

internal fun dueLabel(s: Subscription, today: LocalDate): String {
    val d = s.daysUntilDue(today)
    return when {
        d < -1 -> "Overdue by ${-d} days"
        d == -1L -> "Was due yesterday"
        d == 0L -> "Due today"
        d == 1L -> "Due tomorrow"
        d < 7 -> "Due in $d days"
        else -> "Due " + formatShortDate(s.nextDue)
    }
}

@Composable
private fun SubscriptionRow(s: Subscription, today: LocalDate, menuLabel: String, dimmed: Boolean = false, onMenu: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.category.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (dimmed) variant else MaterialTheme.colorScheme.onSurface,
                )
                val soon = s.daysUntilDue(today) <= 1
                Text(
                    dueLabel(s, today) + " · last paid " + formatShortDate(s.lastPaid),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (soon && !dimmed) MaterialTheme.colorScheme.primary else variant,
                )
                s.previousAmountPaise?.let { before ->
                    val up = s.amountPaise > before
                    Text(
                        (if (up) "Price went up: " else "Price went down: ") + formatRupees(before) + " → " + formatRupees(s.amountPaise),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        color = if (up) MaterialTheme.colorScheme.error else incomeColor(),
                    )
                }
            }
            Text(formatRupees(s.amountPaise), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, "More")
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(menuLabel) }, onClick = { menu = false; onMenu() })
                }
            }
        }
    }
}
