package com.expensetracker.app.quick

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.ExpenseDb
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.ml.Predictor
import com.expensetracker.app.location.LocationTagger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Everything outside the main screen that shows or logs today's spending:
 * the always-on quick-log notification (with inline reply) and the home-screen widget.
 */
object QuickLog {
    private const val CHANNEL_ID = "quick_log"
    private const val NOTIFICATION_ID = 2
    const val KEY_TEXT = "quick_log_text"
    const val ACTION_LOG = "com.expensetracker.app.action.QUICK_LOG"
    const val ACTION_REFRESH = "com.expensetracker.app.action.QUICK_REFRESH"
    const val ACTION_DISMISSED = "com.expensetracker.app.action.QUICK_DISMISSED"
    private const val PREFS = "settings"

    /** Single background thread so DB writes from the shade/widget never race each other. */
    val worker = Executors.newSingleThreadExecutor()

    data class Summary(val todayPaise: Long, val todayCount: Int, val monthPaise: Long)

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("quickLogNotification", false)

    fun summary(all: List<Expense>, today: LocalDate = LocalDate.now()): Summary {
        val spend = com.expensetracker.app.data.Ledger.counted(all).filter { !it.isIncome && it.category != Category.SAVINGS }
        val todays = spend.filter { it.date == today }
        val month = spend.filter { it.date.year == today.year && it.date.month == today.month }
        return Summary(todays.sumOf { it.amountPaise }, todays.size, month.sumOf { it.amountPaise })
    }

    /**
     * Parses one entry per line ("120 - Lunch", "+500 Richa") and saves them for [date].
     * Blocking: call from a background thread. Returns what was saved (empty if nothing parsed).
     */
    fun log(ctx: Context, text: String, date: LocalDate = LocalDate.now()): List<Expense> {
        Predictor.init(ctx)
        val db = ExpenseDb(ctx)
        val all = db.all()
        val now = System.currentTimeMillis()
        val time = if (date == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else null
        val rules = com.expensetracker.app.data.AutoRules.saved(ctx)
        val entries = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { NotesParser.parseLine(it) }
            .mapIndexed { i, q ->
                com.expensetracker.app.data.AutoRules.apply(rules, Expense(
                    amountPaise = q.amountPaise,
                    note = q.note,
                    category = Predictor.also { it.train(all) }.predictCategory(q.note, q.amountPaise, java.time.LocalDateTime.now()),
                    date = date,
                    isIncome = q.isIncome,
                    createdAt = now + i,
                    time = time,
                ))
            }
        if (entries.isNotEmpty() && date == LocalDate.now()) {
            val ids = db.insertAll(entries)
            // City is looked up in the background, then the notification/widget redraw.
            LocationTagger.tagAsync(ctx, ids) { refreshAsync(ctx) }
        } else if (entries.isNotEmpty()) {
            db.insertAll(entries)
        }
        db.close()
        return entries
    }

    /** Re-reads the DB and updates the notification (if on) and all widgets. Blocking. */
    fun refreshAll(ctx: Context, status: String? = null) {
        val db = ExpenseDb(ctx)
        val list = db.all()
        db.close()
        refreshAll(ctx, list, status)
    }

    /** Same as above with an already-loaded list (newest first). Safe from any thread. */
    fun refreshAll(ctx: Context, all: List<Expense>, status: String? = null) {
        val s = summary(all)
        if (isEnabled(ctx)) showNotification(ctx, s, status)
        updateWidgets(ctx, s)
        QuickLogTileService.requestRefresh(ctx)
        com.expensetracker.app.budget.BudgetAlerts.check(ctx, all)
    }

    fun refreshAsync(ctx: Context, status: String? = null) {
        val app = ctx.applicationContext
        worker.execute { refreshAll(app, status) }
    }

    // ---------------- notification ----------------

    fun enable(ctx: Context) {
        scheduleMidnightRefresh(ctx)
        refreshAsync(ctx)
    }

    fun disable(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        // Widgets still want the midnight reset, so keep the alarm if any widget exists.
        if (widgetIds(ctx).isEmpty()) cancelMidnightRefresh(ctx)
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun showNotification(ctx: Context, s: Summary, status: String?) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quick log", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Always-on notification to log today's expenses"
                    setShowBadge(false)
                },
            )
        }

        val remoteInput = RemoteInput.Builder(KEY_TEXT).setLabel("120 - Lunch").build()
        val replyIntent = PendingIntent.getBroadcast(
            ctx, 20,
            Intent(ctx, QuickLogReceiver::class.java).setAction(ACTION_LOG),
            // Must be mutable so the system can attach the typed text.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val logAction = NotificationCompat.Action.Builder(R.drawable.ic_shortcut_add, "Log expense", replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val hidden = com.expensetracker.app.security.AppLock.isEnabled(ctx)
        val title = if (hidden) "Expenses"
        else if (s.todayCount == 0) "Today: nothing logged yet"
        else "Today: ${rupees(s.todayPaise)} · ${s.todayCount} ${if (s.todayCount == 1) "entry" else "entries"}"
        val monthName = LocalDate.now().month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setColor(com.expensetracker.app.ui.notificationAccent(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (hidden) (if (status != null) "Logged ✓" else "Tap “Log expense” and type 120 - Lunch")
                else status ?: "Tap “Log expense” and type 120 - Lunch",
            )
            .setSubText(if (hidden) "Locked" else "$monthName ${rupees(s.monthPaise)}")
            .setContentIntent(activityIntent(ctx, 21, Intent(ctx, QuickLogActivity::class.java)))
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    ctx, 22,
                    Intent(ctx, QuickLogReceiver::class.java).setAction(ACTION_DISMISSED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(logAction)
            .addAction(
                NotificationCompat.Action(
                    0, "Add multiple",
                    activityIntent(ctx, 23, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BULK, true)),
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    // ---------------- widget ----------------

    private fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ExpenseWidget::class.java))

    fun updateWidgets(ctx: Context, s: Summary) {
        val ids = widgetIds(ctx)
        if (ids.isEmpty()) return
        val monthName = LocalDate.now().month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val views = RemoteViews(ctx.packageName, R.layout.widget_expense).apply {
            WidgetTheme.apply(ctx, this)
            val hidden = com.expensetracker.app.security.AppLock.isEnabled(ctx)
            setTextViewText(R.id.widget_today, if (hidden) "₹ •••" else rupees(s.todayPaise))
            setTextViewText(
                R.id.widget_today_label,
                if (s.todayCount == 0) "Today · nothing yet" else "Today · ${s.todayCount} ${if (s.todayCount == 1) "entry" else "entries"}",
            )
            setTextViewText(R.id.widget_month, if (hidden) "Locked · tap + to log" else "$monthName ${rupees(s.monthPaise)}")
            setViewVisibility(R.id.widget_month, View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root, activityIntent(ctx, 30, Intent(ctx, MainActivity::class.java)))
            setOnClickPendingIntent(R.id.widget_add, activityIntent(ctx, 31, Intent(ctx, QuickLogActivity::class.java)))
            setOnClickPendingIntent(
                R.id.widget_bulk,
                activityIntent(ctx, 32, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_BULK, true)),
            )
        }
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, views)
    }

    // ---------------- midnight reset ----------------

    fun scheduleMidnightRefresh(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val next = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(0, 1))
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC, at, AlarmManager.INTERVAL_DAY, midnightIntent(ctx))
    }

    private fun cancelMidnightRefresh(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(ctx))
    }

    private fun midnightIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 24,
        Intent(ctx, QuickLogReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** After boot / app update: re-arm the midnight alarm and redraw. */
    fun restore(ctx: Context) {
        if (isEnabled(ctx) || widgetIds(ctx).isNotEmpty()) scheduleMidnightRefresh(ctx)
        refreshAsync(ctx)
    }

    // ---------------- helpers ----------------

    private fun activityIntent(ctx: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        ctx, code,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** ₹ with Indian digit grouping, paise only when non-zero. */
    fun rupees(paise: Long): String {
        val neg = paise < 0
        val p = kotlin.math.abs(paise)
        val r = (p / 100).toString()
        val grouped = if (r.length <= 3) r else {
            val head = r.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + r.takeLast(3)
        }
        val frac = if (p % 100 != 0L) ".%02d".format(p % 100) else ""
        return (if (neg) "-₹" else "₹") + grouped + frac
    }
}

/** Handles the inline "Log expense" reply, the midnight refresh, and re-posting if swiped away. */
class QuickLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        QuickLog.worker.execute {
            try {
                when (intent.action) {
                    QuickLog.ACTION_LOG -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QuickLog.KEY_TEXT)?.toString().orEmpty()
                        val saved = QuickLog.log(app, text)
                        val status = when {
                            saved.isEmpty() -> "No amount found in “${text.take(30)}” — try 120 - Lunch"
                            saved.size == 1 -> "Logged ${QuickLog.rupees(saved[0].amountPaise)} · ${saved[0].note}"
                            else -> "Logged ${saved.size} entries · ${QuickLog.rupees(saved.filter { !it.isIncome }.sumOf { it.amountPaise })}"
                        }
                        // Re-posting also clears the reply spinner in the shade.
                        QuickLog.refreshAll(app, status)
                    }
                    // Android 14+ lets users swipe ongoing notifications away; it stays while the setting is on.
                    QuickLog.ACTION_DISMISSED, QuickLog.ACTION_REFRESH -> QuickLog.refreshAll(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
