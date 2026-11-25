package com.expensetracker.app

/**
 * What an incoming intent (app shortcut, notification, widget) asks [MainActivity] to open.
 * Pure so it can be unit-tested: [extra] reads an extra by name (shortcut XML delivers extras
 * as strings, notifications as booleans — both count).
 */
enum class LaunchRoute {
    ADD_EXPENSE, ADD_INCOME, BULK, REVIEW_CAPTURE, BACKUP, STATS, SETTINGS;

    companion object {
        const val ACTION_ADD = "com.expensetracker.app.action.ADD_EXPENSE"
        const val ACTION_ADD_INCOME = "com.expensetracker.app.action.ADD_INCOME"
        const val ACTION_BULK = "com.expensetracker.app.action.BULK_ADD"
        const val ACTION_REVIEW_CAPTURE = "com.expensetracker.app.action.REVIEW_CAPTURED"

        const val EXTRA_OPEN_ADD = "open_add"
        const val EXTRA_OPEN_BULK = "open_bulk"
        const val EXTRA_OPEN_CAPTURE = com.expensetracker.app.capture.CaptureNotifier.EXTRA_OPEN_CAPTURE
        const val EXTRA_OPEN_BACKUP = com.expensetracker.app.backup.AutoBackup.EXTRA_OPEN_BACKUP
        /** Budget widget: open the Stats tab (or Settings when no budget is set yet). */
        const val EXTRA_OPEN_STATS = "open_stats"
        const val EXTRA_OPEN_SETTINGS = "open_settings"

        private fun on(v: Any?) = v == true || (v as? String).equals("true", ignoreCase = true)

        /** Every screen the intent asks for, in the order to apply them (usually one). */
        fun of(action: String?, extra: (String) -> Any?): List<LaunchRoute> = buildList {
            if (action == ACTION_ADD || on(extra(EXTRA_OPEN_ADD))) add(ADD_EXPENSE)
            if (action == ACTION_ADD_INCOME) add(ADD_INCOME)
            if (action == ACTION_BULK || on(extra(EXTRA_OPEN_BULK))) add(BULK)
            if (action == ACTION_REVIEW_CAPTURE || on(extra(EXTRA_OPEN_CAPTURE))) add(REVIEW_CAPTURE)
            if (on(extra(EXTRA_OPEN_BACKUP))) add(BACKUP)
            if (on(extra(EXTRA_OPEN_STATS))) add(STATS)
            if (on(extra(EXTRA_OPEN_SETTINGS))) add(SETTINGS)
        }

        /** Extras consumed by [of]; removed after handling so a re-delivered intent doesn't replay. */
        val EXTRAS = listOf(EXTRA_OPEN_ADD, EXTRA_OPEN_BULK, EXTRA_OPEN_CAPTURE, EXTRA_OPEN_BACKUP, EXTRA_OPEN_STATS, EXTRA_OPEN_SETTINGS)
    }
}
