package com.expensetracker.app

import com.expensetracker.app.LaunchRoute.Companion.of
import com.expensetracker.app.quick.QuickLogTileService
import com.expensetracker.app.ui.SheetState
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchRouteTest {
    private fun extras(vararg kv: Pair<String, Any?>): (String) -> Any? = kv.toMap()::get

    @Test fun shortcutActions() {
        assertEquals(listOf(LaunchRoute.ADD_EXPENSE), of(LaunchRoute.ACTION_ADD, extras()))
        assertEquals(listOf(LaunchRoute.ADD_INCOME), of(LaunchRoute.ACTION_ADD_INCOME, extras()))
        assertEquals(listOf(LaunchRoute.BULK), of(LaunchRoute.ACTION_BULK, extras()))
        assertEquals(listOf(LaunchRoute.REVIEW_CAPTURE), of(LaunchRoute.ACTION_REVIEW_CAPTURE, extras()))
    }

    @Test fun shortcutXmlStringExtraAndAction_openAddOnce() =
        assertEquals(listOf(LaunchRoute.ADD_EXPENSE), of(LaunchRoute.ACTION_ADD, extras("open_add" to "true")))

    @Test fun notificationBooleanExtras() {
        assertEquals(listOf(LaunchRoute.ADD_EXPENSE), of(null, extras("open_add" to true)))
        assertEquals(listOf(LaunchRoute.BULK), of(null, extras("open_bulk" to true)))
        assertEquals(listOf(LaunchRoute.REVIEW_CAPTURE), of(null, extras(LaunchRoute.EXTRA_OPEN_CAPTURE to true)))
        assertEquals(listOf(LaunchRoute.BACKUP), of(null, extras(LaunchRoute.EXTRA_OPEN_BACKUP to true)))
    }

    @Test fun plainLaunchOpensNothing() {
        assertEquals(emptyList<LaunchRoute>(), of("android.intent.action.MAIN", extras()))
        assertEquals(emptyList<LaunchRoute>(), of(null, extras("open_add" to false, "open_bulk" to "no")))
    }

    @Test fun addingIncomeSurvivesTheLock() =
        assertEquals(SheetState.AddingIncome, SheetState.decode(SheetState.encode(SheetState.AddingIncome)) { null })

    @Test fun tileSubtitle() {
        assertEquals("Nothing today", QuickLogTileService.subtitleFor(0))
        assertEquals("Today ₹1,240", QuickLogTileService.subtitleFor(124_000))
    }
}
