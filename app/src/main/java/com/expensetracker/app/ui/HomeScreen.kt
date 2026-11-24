@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.expensetracker.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    vm: ExpenseViewModel,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    onAdd: () -> Unit,
    onBulkAdd: (LocalDate) -> Unit,
    onEdit: (Expense) -> Unit,
    onOpenImport: () -> Unit,
) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val spendInfo by vm.spendInfo.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    val today = LocalDate.now()
    val q = query.trim()
    val visible = remember(expenses, month, q, searching) {
        if (searching && q.isNotEmpty()) {
            expenses.filter {
                it.note.contains(q, ignoreCase = true) || it.category.label.contains(q, ignoreCase = true) ||
                    it.place?.contains(q, ignoreCase = true) == true
            }
        } else {
            expenses.filter { month.contains(it.date) }
        }
    }
    val grouped = remember(visible) {
        visible.groupBy { it.date }.toSortedMap(compareByDescending { it })
    }

    fun deleteWithUndo(e: Expense) {
        vm.delete(e)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                "Deleted ${formatRupees(e.amountPaise)} · ${e.note}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) vm.restore(e)
        }
    }

    fun announceAdded(e: Expense) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(
                "Added ${formatRupees(e.amountPaise)} · ${e.note}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) undoAdd(vm, e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search all expenses") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    } else {
                        Text("Expenses", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { onBulkAdd(if (month == YearMonth.from(today)) today else month.atEndOfMonth()) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add multiple", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add expense") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (searching && q.isNotEmpty()) {
                item("search-summary") {
                    val total = visible.filter { !it.isIncome }.sumOf { it.amountPaise }
                    Text(
                        "${visible.size} matches · ${formatRupees(total)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else if (!searching) {
                item("month") {
                    MonthSelector(month, onMonthChange, Modifier.padding(horizontal = 4.dp))
                }
                item("summary") {
                    SummaryCard(expenses, month, settings, today, spendInfo)
                }
                item("quick") {
                    QuickAddBar(
                        defaultDate = if (month == YearMonth.from(today)) today else month.atEndOfMonth(),
                        onSubmit = { text, date ->
                            val e = vm.quickAdd(text, date)
                            if (e == null) {
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar("No amount found — try “120 - Lunch”")
                                }
                                false
                            } else {
                                announceAdded(e)
                                true
                            }
                        },
                        templates = templates.map { t ->
                            "${t.category.emoji} ${t.note} · ${formatRupees(t.amountPaise)}" to { date: LocalDate ->
                                val e = Expense(
                                    amountPaise = t.amountPaise,
                                    note = t.note,
                                    category = t.category,
                                    date = date,
                                    time = if (date == LocalDate.now()) java.time.LocalTime.now().withSecond(0).withNano(0) else null,
                                )
                                vm.add(e)
                                announceAdded(e)
                            }
                        },
                    )
                }
                if (expenses.isEmpty()) {
                    item("empty") { EmptyState(onOpenImport) }
                } else if (visible.isEmpty()) {
                    item("empty-month") {
                        Text(
                            "Nothing logged in ${formatMonth(month)}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }

            grouped.forEach { (date, dayItems) ->
                stickyHeader(key = "h-$date") { DayHeader(date, dayItems, today, onAddMore = { onBulkAdd(date) }) }
                items(dayItems, key = { "e-${it.id}-${it.createdAt}" }) { e ->
                    SwipeableExpenseRow(
                        expense = e,
                        spend = spendInfo[e.id],
                        onClick = { onEdit(e) },
                        onDelete = { deleteWithUndo(e) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Removes an entry that was just added. The returned Expense may not carry the DB id yet. */
fun undoAdd(vm: ExpenseViewModel, e: Expense) {
    val target = if (e.id != 0L) e else vm.expenses.value.firstOrNull {
        it.createdAt == e.createdAt && it.amountPaise == e.amountPaise && it.note == e.note
    }
    target?.let { vm.delete(it) }
}

@Composable
private fun SummaryCard(all: List<Expense>, month: YearMonth, settings: Settings, today: LocalDate, info: Map<Long, SpendInfo>) {
    val inMonth = all.filter { month.contains(it.date) }
    val split = splitSpend(inMonth, info)
    val spent = inMonth.filter { it.countsAsSpend(settings.excludeSavingsFromSpend) }.sumOf { it.amountPaise }
    val income = inMonth.filter { it.isIncome }.sumOf { it.amountPaise }
    val saved = if (settings.excludeSavingsFromSpend) {
        inMonth.filter { !it.isIncome && it.category == com.expensetracker.app.data.Category.SAVINGS }.sumOf { it.amountPaise }
    } else 0L
    val isCurrent = month == YearMonth.from(today)
    val todaySpent = all.filter { it.date == today && it.countsAsSpend(settings.excludeSavingsFromSpend) }.sumOf { it.amountPaise }
    val daysElapsed = if (isCurrent) today.dayOfMonth else month.lengthOfMonth()

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Text("Spent in ${formatMonth(month)}", style = MaterialTheme.typography.labelLarge)
            Text(
                formatRupees(spent),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isCurrent) Stat("Today", formatRupees(todaySpent))
                Stat("Everyday avg", formatRupees(if (daysElapsed > 0) split.everyday / daysElapsed else 0))
                if (income > 0) Stat("Received", "+" + formatRupees(income), incomeColor())
                if (saved > 0) Stat("Saved", formatRupees(saved))
            }
            if (split.oneOff > 0 || split.fixed > 0) {
                Text(
                    listOfNotNull(
                        split.oneOff.takeIf { it > 0 }?.let { "${formatRupees(it)} one-offs" },
                        split.fixed.takeIf { it > 0 }?.let { "${formatRupees(it)} fixed" },
                    ).joinToString(" · ", prefix = "Incl. "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (settings.monthlyBudgetPaise > 0) {
                val budget = settings.monthlyBudgetPaise
                val frac = (spent.toFloat() / budget).coerceIn(0f, 1f)
                val left = budget - spent
                val over = left < 0
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(6.dp))
                val text = if (over) {
                    "Over budget by ${formatRupees(-left)} (budget ${formatRupees(budget)})"
                } else if (isCurrent) {
                    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
                    "${formatRupees(left)} left · ${formatRupees(left / daysLeft)}/day for $daysLeft days"
                } else {
                    "${formatRupees(left)} under budget of ${formatRupees(budget)}"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/**
 * Notes-style quick entry: type "120 - Lunch" and hit send.
 * [templates] are (label, add-on-date) pairs for one-tap chips.
 */
@Composable
private fun QuickAddBar(
    defaultDate: LocalDate,
    onSubmit: (String, LocalDate) -> Boolean,
    templates: List<Pair<String, (LocalDate) -> Unit>>,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var date by remember(defaultDate) { mutableStateOf(defaultDate) }
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val today = LocalDate.now()

    fun submit() {
        if (text.isBlank()) return
        if (onSubmit(text, date)) {
            text = ""
            focus.clearFocus()
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("120 - Lunch") },
            label = { Text("Quick add") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add")
                }
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = { Text(formatDateChip(date, today)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Today") }, onClick = { date = today; menu = false })
                    DropdownMenuItem(text = { Text("Yesterday") }, onClick = { date = today.minusDays(1); menu = false })
                    DropdownMenuItem(text = { Text("Pick a date…") }, onClick = { menu = false; picker = true })
                }
            }
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(templates, key = { it.first }) { (label, add) ->
                    SuggestionChip(onClick = { add(date) }, label = { Text(label, maxLines = 1) })
                }
            }
        }
    }
    if (picker) DatePickDialog(initial = date, onPick = { date = it }, onDismiss = { picker = false })
}

@Composable
private fun DayHeader(date: LocalDate, items: List<Expense>, today: LocalDate, onAddMore: () -> Unit) {
    val spent = items.filter { !it.isIncome }.sumOf { it.amountPaise }
    val income = items.filter { it.isIncome }.sumOf { it.amountPaise }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDayHeader(date, today),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (income > 0) {
            Text(
                "+" + formatRupees(income),
                style = MaterialTheme.typography.labelLarge,
                color = incomeColor(),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (spent > 0) {
            Text(formatRupees(spent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Icon(
            Icons.Filled.Add,
            contentDescription = "Add more to this day",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(22.dp)
                .clickable(onClick = onAddMore),
        )
    }
}

@Composable
private fun SwipeableExpenseRow(
    expense: Expense,
    spend: SpendInfo?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { onDelete() },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ExpenseRow(expense, onClick, spend = spend)
    }
}

@Composable
fun ExpenseRow(expense: Expense, onClick: () -> Unit, showDate: Boolean = false, spend: SpendInfo? = null) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryBadge(expense)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    expense.note.ifBlank { expense.category.label },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val home = LocalHomeCity.current
                    val away = expense.place?.takeIf { p ->
                        p.isNotBlank() && com.expensetracker.app.data.Places.key(p) != com.expensetracker.app.data.Places.key(home)
                    }
                    Text(
                        (if (showDate) "${expense.category.label} · ${formatShortDate(expense.date)}" else expense.category.label) +
                            (expense.time?.let { " · ${formatTime(it.hour, it.minute)}" } ?: "") +
                            (away?.let { " · 📍 $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    val t = spend?.type
                    if (!expense.isIncome && (t == SpendType.ONE_OFF || t == SpendType.FIXED)) {
                        Text(
                            "  ${t.emoji} ${if (t == SpendType.ONE_OFF) "One-off" else "Fixed"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                (if (expense.isIncome) "+" else "") + formatRupees(expense.amountPaise),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (expense.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
fun CategoryBadge(expense: Expense) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(expense.category.color).copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (expense.isIncome) "💰" else expense.category.emoji, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyState(onOpenImport: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("No expenses yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type “120 - Lunch” above, or bring over your old notes in one go.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenImport) { Text("Import from notes") }
        }
    }
}
