package com.expensetracker.app.goals

import androidx.compose.runtime.Composable
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.capture.PlaceholderScreen

/** STUBS — owned by the "goals-insights" session (custom savings goals). */

/** Create/edit/delete goals, see progress. */
@Composable
fun GoalsScreen(vm: ExpenseViewModel, onBack: () -> Unit) = PlaceholderScreen("Savings goals", onBack)

/** Compact progress card at the top of Stats. Renders nothing when there are no goals. */
@Composable
fun GoalsCard(vm: ExpenseViewModel, onOpen: () -> Unit) = Unit

/**
 * Row in the add/edit sheet to link an entry to a goal ("Counts towards: Goa trip").
 * Shown by the sheet when the category is Savings or a goal is already set.
 */
@Composable
fun GoalPickerRow(vm: ExpenseViewModel, goalId: String?, onChange: (String?) -> Unit) = Unit
