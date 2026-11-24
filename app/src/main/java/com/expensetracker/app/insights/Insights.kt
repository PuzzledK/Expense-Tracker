package com.expensetracker.app.insights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.capture.PlaceholderScreen

/** STUBS — owned by the "goals-insights" session (spending insights). */
object Insights {
    /** Arm/re-arm the weekly summary notification from settings. */
    fun restore(ctx: Context) = Unit
}

/** Weekly summary alarm. Declared in the manifest. */
class InsightsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** 1–3 headline insights on Home (tap → full screen). Renders nothing when there's nothing useful. */
@Composable
fun InsightsCard(vm: ExpenseViewModel, onOpen: () -> Unit) = Unit

@Composable
fun InsightsScreen(vm: ExpenseViewModel, onBack: () -> Unit) = PlaceholderScreen("Insights", onBack)
