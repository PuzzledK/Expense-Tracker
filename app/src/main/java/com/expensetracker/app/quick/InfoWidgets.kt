package com.expensetracker.app.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.expensetracker.app.LaunchRoute
import com.expensetracker.app.MainActivity
import com.expensetracker.app.R
import com.expensetracker.app.data.BudgetLevel
import com.expensetracker.app.data.CategoryBudgets
import com.expensetracker.app.data.Expense
import com.expensetracker.app.subscriptions.Subscriptions
import java.time.LocalDate

/** Home-screen widget: this month against the budget, plus the categories closest to their limits. */
class BudgetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = InfoWidgets.refreshFromProvider(this, context)
    override fun onEnabled(context: Context) = QuickLog.scheduleMidnightRefresh(context)
}

/** Home-screen widget: the next subscriptions / bills and their monthly total. */
class BillsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = InfoWidgets.refreshFromProvider(this, context)
    override fun onEnabled(context: Context) = QuickLog.scheduleMidnightRefresh(context)
}

/** Draws the Budget and Upcoming-bills widgets. Called from [QuickLog.refreshAll] with every change. */
internal object InfoWidgets {
    private const val TAG = "InfoWidgets"

    private val catRows = listOf(
        Row(R.id.widget_cat1, R.id.widget_cat1_label, R.id.widget_cat1_value, R.id.widget_cat1_bar),
        Row(R.id.widget_cat2, R.id.widget_cat2_label, R.id.widget_cat2_value, R.id.widget_cat2_bar),
        Row(R.id.widget_cat3, R.id.widget_cat3_label, R.id.widget_cat3_value, R.id.widget_cat3_bar),
    )
    private val billRows = listOf(
        Row(R.id.widget_bill1, R.id.widget_bill1_name, R.id.widget_bill1_amount, R.id.widget_bill1_due),
        Row(R.id.widget_bill2, R.id.widget_bill2_name, R.id.widget_bill2_amount, R.id.widget_bill2_due),
        Row(R.id.widget_bill3, R.id.widget_bill3_name, R.id.widget_bill3_amount, R.id.widget_bill3_due),
    )

    private data class Row(val row: Int, val a: Int, val b: Int, val c: Int)

    fun refreshFromProvider(p: AppWidgetProvider, ctx: Context) {
        val pending = p.goAsync()
        val app = ctx.applicationContext
        QuickLog.worker.execute {
            try { QuickLog.refreshAll(app) } finally { pending.finish() }
        }
    }

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun hasAny(ctx: Context) = ids(ctx, BudgetWidget::class.java).isNotEmpty() || ids(ctx, BillsWidget::class.java).isNotEmpty()

    fun update(ctx: Context, all: List<Expense>) {
        try {
            updateBudget(ctx, all)
            updateBills(ctx, all)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't draw the info widgets", e)
        }
    }

    private fun locked(ctx: Context) = com.expensetracker.app.security.AppLock.isEnabled(ctx)

    private fun updateBudget(ctx: Context, all: List<Expense>) {
        val ids = ids(ctx, BudgetWidget::class.java)
        if (ids.isEmpty()) return
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val m = InfoWidgetModels.budget(
            all,
            monthlyBudgetPaise = prefs.getLong("monthlyBudgetPaise", 0),
            categoryLimits = CategoryBudgets.decode(prefs.getString("categoryBudgets", "")),
            excludeSavings = prefs.getBoolean("excludeSavingsFromSpend", true),
        )
        val hidden = locked(ctx)
        val rupees = QuickLog::rupees
        val v = RemoteViews(ctx.packageName, R.layout.widget_budget)
        val p = WidgetTheme.frame(ctx, v)
        p.dim(R.id.widget_budget_title); p.dim(R.id.widget_budget_left); p.text(R.id.widget_budget_amount); p.dim(R.id.widget_budget_empty)
        v.setTextViewText(R.id.widget_budget_title, "Budget · ${m.monthLabel}")
        val overall = m.overall
        if (hidden) {
            v.setTextViewText(R.id.widget_budget_amount, "₹ •••")
            v.setTextViewText(R.id.widget_budget_left, "")
            v.setViewVisibility(R.id.widget_budget_bar, View.GONE)
            v.setViewVisibility(R.id.widget_budget_empty, View.VISIBLE)
            v.setTextViewText(R.id.widget_budget_empty, "Locked · tap to open")
            catRows.forEach { v.setViewVisibility(it.row, View.GONE) }
        } else {
            v.setTextViewText(
                R.id.widget_budget_amount,
                if (overall != null) InfoWidgetModels.spentOf(overall, rupees) else "${rupees(m.spentPaise)} spent",
            )
            v.setTextViewText(
                R.id.widget_budget_left,
                overall?.let { if (it.level == BudgetLevel.OVER) "Over" else "${rupees(it.limitPaise - it.spentPaise)} left" } ?: "",
            )
            v.setViewVisibility(R.id.widget_budget_bar, if (overall != null) View.VISIBLE else View.GONE)
            if (overall != null) {
                v.setProgressBar(R.id.widget_budget_bar, 100, overall.percent, false)
                p.bar(R.id.widget_budget_bar, overall.level)
            }
            v.setViewVisibility(R.id.widget_budget_empty, if (m.isEmpty) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_budget_empty, "No budget set — tap to set")
            catRows.forEachIndexed { i, r ->
                val bar = m.categories.getOrNull(i)
                v.setViewVisibility(r.row, if (bar != null) View.VISIBLE else View.GONE)
                if (bar != null) {
                    p.text(r.a); p.dim(r.b)
                    v.setTextViewText(r.a, bar.label)
                    v.setTextViewText(r.b, InfoWidgetModels.spentOf(bar, rupees))
                    v.setProgressBar(r.c, 100, bar.percent, false)
                    p.bar(r.c, bar.level)
                }
            }
        }
        val route = if (m.isEmpty) LaunchRoute.EXTRA_OPEN_SETTINGS else LaunchRoute.EXTRA_OPEN_STATS
        v.setOnClickPendingIntent(R.id.widget_root, open(ctx, 40, Intent(ctx, MainActivity::class.java).putExtra(route, true)))
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
    }

    private fun updateBills(ctx: Context, all: List<Expense>) {
        val ids = ids(ctx, BillsWidget::class.java)
        if (ids.isEmpty()) return
        val m = InfoWidgetModels.bills(all, Subscriptions.hidden(ctx), LocalDate.now())
        val hidden = locked(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_bills)
        val p = WidgetTheme.frame(ctx, v)
        p.dim(R.id.widget_bills_title); p.dim(R.id.widget_bills_total); p.dim(R.id.widget_bills_empty)
        v.setTextViewText(R.id.widget_bills_title, "Upcoming bills")
        v.setTextViewText(
            R.id.widget_bills_total,
            when {
                m.count == 0 -> ""
                hidden -> "₹ ••• / month"
                else -> "${QuickLog.rupees(m.monthlyTotalPaise)} / month"
            },
        )
        v.setViewVisibility(R.id.widget_bills_empty, if (m.rows.isEmpty()) View.VISIBLE else View.GONE)
        billRows.forEachIndexed { i, r ->
            val row = m.rows.getOrNull(i)
            v.setViewVisibility(r.row, if (row != null) View.VISIBLE else View.GONE)
            if (row != null) {
                p.text(r.a); p.text(r.b); p.dim(r.c)
                v.setTextViewText(r.a, row.name)
                v.setTextViewText(r.b, if (hidden) "₹ •••" else QuickLog.rupees(row.amountPaise))
                v.setTextViewText(r.c, row.due)
            }
        }
        v.setOnClickPendingIntent(
            R.id.widget_root,
            open(ctx, 41, Intent(ctx, MainActivity::class.java).putExtra(Subscriptions.EXTRA_OPEN, true)),
        )
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
    }

    private fun open(ctx: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        ctx, code,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
