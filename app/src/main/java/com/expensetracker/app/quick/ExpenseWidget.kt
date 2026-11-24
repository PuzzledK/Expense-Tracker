package com.expensetracker.app.quick

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Home-screen widget: today's / this month's spend, + quick log, ≡ add multiple. */
class ExpenseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        QuickLog.worker.execute {
            try {
                QuickLog.refreshAll(app)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        QuickLog.scheduleMidnightRefresh(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed: the alarm is only still needed for the notification.
        if (!QuickLog.isEnabled(context)) QuickLog.disable(context)
    }
}
