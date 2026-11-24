package com.expensetracker.app.categories

import androidx.compose.runtime.Composable
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.capture.PlaceholderScreen

/** STUB — owned by the "backup" session (custom categories UI). */
@Composable
fun ManageCategoriesScreen(vm: ExpenseViewModel, onBack: () -> Unit) = PlaceholderScreen("Categories", onBack)
