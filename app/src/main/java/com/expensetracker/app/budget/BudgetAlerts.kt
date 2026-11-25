package com.expensetracker.app.budget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.CategoryBudgets
import com.expensetracker.app.data.Expense
import com.expensetracker.app.ui.formatRupeesWhole
import java.time.YearMonth

/**
 * "Food: 80% of this month's budget used" nudges. Runs after every change to the entries
 * (QuickLog.refreshAll, which the app, widget, notification and capture all go through).
 * Each category gets at most one 80% and one 100% nudge per month; what was sent is kept
 * in SharedPreferences "budget_alerts".
 */
object BudgetAlerts {
    private const val TAG = "BudgetAlerts"
    private const val CHANNEL_ID = "budget_alerts"
    private const val PREFS = "budget_alerts"
    private const val KEY_SENT = "sent"

    @Synchronized
    fun check(ctx: Context, all: List<Expense>) {
        try {
            val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val limits = CategoryBudgets.decode(settings.getString("categoryBudgets", ""))
            if (limits.isEmpty()) return
            val excl = settings.getBoolean("excludeSavingsFromSpend", true)
            val month = YearMonth.now()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sent = prefs.getStringSet(KEY_SENT, emptySet()) ?: emptySet()
            val due = CategoryBudgets.dueAlerts(limits, all, month, excl, sent)
            if (due.isEmpty()) return
            prefs.edit().putStringSet(KEY_SENT, CategoryBudgets.markSent(sent, due, month)).apply()
            if (!canNotify(ctx)) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Category budgets", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "When a category reaches 80% and 100% of its monthly budget"
                    },
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0x6b75,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            for (a in due) {
                val c = a.category
                val title = if (a.percent >= 100) "${c.emoji} ${c.label}: over budget" else "${c.emoji} ${c.label}: ${a.percent}% of budget used"
                val text = if (a.percent >= 100) {
                    "Spent ${formatRupeesWhole(a.spentPaise)} of ${formatRupeesWhole(a.limitPaise)} this month (${formatRupeesWhole(a.spentPaise - a.limitPaise)} over)."
                } else {
                    "Spent ${formatRupeesWhole(a.spentPaise)} of ${formatRupeesWhole(a.limitPaise)} — ${formatRupeesWhole(a.limitPaise - a.spentPaise)} left this month."
                }
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setColor(com.expensetracker.app.ui.notificationAccent(ctx))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                // One notification per category; the 100% one replaces the 80% one.
                nm.notify(CHANNEL_ID, c.id.hashCode(), n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "budget check failed", e)
        }
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
